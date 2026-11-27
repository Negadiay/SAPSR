package com.sapsr.backend.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.AcademicSetting;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Какая языковая модель проверяет работы (решение 13, «Красная зона» администратора).
 *
 * Список моделей и их доступность сообщает обработчик (worker_status_queue, queue-v2.md, раздел 10): каталог
 * задаёт тот, кто разворачивает систему, а не приложение. Администратор выбирает модель по id; выбор хранится
 * в academic_settings и уходит в каждом новом задании (поле llm.model). Перезапуск не нужен: обработчик берёт
 * модель из задания. Адресов моделей и ключей бэкенд не знает и не хранит.
 */
@Service
public class LlmModels {

    public static final String SETTING = "llm_model";
    /** Обработчик сообщает о себе раз в минуту; молчит дольше — считаем, что не на связи (или занят проверкой). */
    static final Duration ONLINE = Duration.ofSeconds(150);

    private static final Logger log = LoggerFactory.getLogger(LlmModels.class);

    public record Model(String id, String title, String tier, String model, boolean cloud, boolean available,
                        String reason, String note) {}

    record Status(LocalDateTime receivedAt, String mode, String llmMode, boolean cloudAllowed, String kbVersion,
                  String defaultModel, List<Model> models) {

        Optional<Model> model(String id) {
            return models.stream().filter(m -> m.id().equals(id)).findFirst();
        }
    }

    /** Почему модель нельзя выбрать; code — для интерфейса, message — человеку. */
    public static class Refused extends Exception {
        private final String code;

        Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    private final AcademicSettingRepository settings;
    private final UserRepository users;
    private final Clock clock;
    private final AtomicReference<Status> status = new AtomicReference<>();

    @Autowired
    public LlmModels(AcademicSettingRepository settings, UserRepository users) {
        this(settings, users, Clock.system(AcademicCalendar.ZONE));
    }

    public LlmModels(AcademicSettingRepository settings, UserRepository users, Clock clock) {
        this.settings = settings;
        this.users = users;
        this.clock = clock;
    }

    /** Сообщение обработчика о себе. Неполное — пропускается: прежнее состояние остаётся. */
    public void onStatus(JsonNode json) {
        if (json == null || !"status".equals(json.path("type").asText()) || !json.path("models").isArray()) {
            log.warn("Состояние обработчика без списка моделей пропущено");
            return;
        }
        List<Model> models = new ArrayList<>();
        for (JsonNode m : json.get("models")) {
            String id = text(m, "id");
            if (id == null || !id.matches("[a-z0-9_-]{1,32}")) continue;
            models.add(new Model(id, Objects.requireNonNullElse(text(m, "title"), id), text(m, "tier"),
                    text(m, "model"), m.path("cloud").asBoolean(false), m.path("available").asBoolean(false),
                    text(m, "reason"), text(m, "note")));
        }
        status.set(new Status(now(), text(json, "mode"), text(json, "llm_mode"),
                json.path("cloud_allowed").asBoolean(false), text(json, "kb_version"), text(json, "default_model"),
                List.copyOf(models)));
    }

    /** id модели для нового задания; null — модель обработчика по умолчанию. */
    public String forTask() {
        return selected().orElse(null);
    }

    public Optional<String> selected() {
        return settings.findById(SETTING).map(AcademicSetting::getValue);
    }

    /** Проверяет ли сейчас облачная модель — студенту об этом говорится при сдаче. */
    public boolean cloudActive() {
        Status s = status.get();
        if (s == null) return false;
        String active = selected().orElse(s.defaultModel());
        return active != null && s.model(active).map(m -> m.cloud() && m.available()).orElse(false);
    }

    /**
     * Выбор администратора. id = null — снова модель по умолчанию. Облачную — только с явным подтверждением:
     * фрагменты работ уйдут стороннему сервису (без персональных данных — их убирает обработчик).
     */
    public void select(String id, boolean confirmCloud, Long adminId) throws Refused {
        if (id == null || id.isBlank()) {
            settings.deleteById(SETTING);
            log.info("Администратор {}: модель проверки — по умолчанию", adminId);
            return;
        }
        Status s = status.get();
        if (s == null) {
            throw new Refused("WORKER_UNKNOWN", "Обработчик ещё не сообщил, какие модели у него есть. Подождите минуту.");
        }
        Model model = s.model(id).orElseThrow(() -> new Refused("UNKNOWN_MODEL", "Такой модели нет у обработчика."));
        if (!model.available()) {
            throw new Refused("MODEL_UNAVAILABLE", "Модель «" + model.title() + "» недоступна"
                    + (model.reason() == null ? "." : ": " + model.reason()));
        }
        if (model.cloud() && !confirmCloud) {
            throw new Refused("CLOUD_CONFIRMATION_REQUIRED", "Облачную модель нужно подтвердить.");
        }
        if (id.equals(s.defaultModel())) {
            settings.deleteById(SETTING);
        } else {
            settings.save(new AcademicSetting(SETTING, id, now(), adminId));
        }
        log.info("Администратор {}: модель проверки — {}{}", adminId, id, model.cloud() ? " (облачная)" : "");
    }

    /** Всё, что показывает «Красная зона». */
    public Map<String, Object> view() {
        Status s = status.get();
        LocalDateTime now = now();
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("known", s != null);
        if (s != null) {
            long ago = Duration.between(s.receivedAt(), now).toSeconds();
            worker.put("online", ago <= ONLINE.toSeconds());
            worker.put("last_seen", s.receivedAt().withNano(0).toString());
            worker.put("seconds_ago", ago);
            worker.put("mode", s.mode());
            worker.put("llm_mode", s.llmMode());
            worker.put("cloud_allowed", s.cloudAllowed());
            worker.put("kb_version", s.kbVersion());
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("worker", worker);
        view.put("models", s == null ? List.of() : s.models());
        view.put("default_model", s == null ? null : s.defaultModel());
        Optional<AcademicSetting> setting = settings.findById(SETTING);
        String selected = setting.map(AcademicSetting::getValue).orElse(null);
        view.put("selected", selected);
        view.put("active", selected != null ? selected : s == null ? null : s.defaultModel());
        view.put("changed_at", setting.map(x -> x.getUpdatedAt().withNano(0).toString()).orElse(null));
        view.put("changed_by", setting.map(AcademicSetting::getUpdatedBy).flatMap(users::findById)
                .map(User::getFullName).orElse(null));
        return view;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
