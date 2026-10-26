package com.sapsr.backend.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.academic.WorkTypes;
import com.sapsr.backend.bot.AppLink;
import com.sapsr.backend.bot.BotMessages;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Состояние проверки работы по сообщениям обработчика — протокол очередей, docs/contracts/queue-v2.md, раздел 6.
 *
 * Каждый метод — одна транзакция под блокировкой строки работы (результаты, ход проверки и сторож приходят из
 * разных потоков). Уведомления не отправляются отсюда, а возвращаются списком: их шлёт {@link NoticeSender}
 * после фиксации.
 *
 * Преподаватель получает работу один раз — когда отчёт окончательный: пришла содержательная часть, она не
 * успела за тайм-аут или не удалась. Отклонённая по оформлению работа видна у него в очереди сразу (решение 9),
 * а уведомление приходит с полным отчётом. Студент получает одно сообщение на работу: итог оформления сразу,
 * а итог содержания бот дописывает в то же сообщение.
 */
@Service
public class PipelineState {

    public static final String QUEUED = "QUEUED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";

    public static final String CONTENT_PENDING = "PENDING";
    public static final String CONTENT_DONE = "DONE";
    public static final String CONTENT_TIMEOUT = "TIMEOUT";
    public static final String CONTENT_FAILED = "FAILED";
    public static final String CONTENT_NONE = "NONE";

    public static final String FAILURE_STUCK = "STUCK";

    private static final Logger log = LoggerFactory.getLogger(PipelineState.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SubmissionRepository submissions;
    private final PipelineTimeouts timeouts;
    private final Clock clock;

    @Autowired
    public PipelineState(SubmissionRepository submissions, PipelineTimeouts timeouts) {
        this(submissions, timeouts, Clock.system(AcademicCalendar.ZONE));
    }

    public PipelineState(SubmissionRepository submissions, PipelineTimeouts timeouts, Clock clock) {
        this.submissions = submissions;
        this.timeouts = timeouts;
        this.clock = clock;
    }

    // ── результат v1: {task_id, status, errors} — обработчик с SAPSR_QUEUE_PROTOCOL=1 ─────────────

    @Transactional
    public List<Notice> onResultV1(int taskId, boolean passed, String errorsJson) {
        Submission s = locked(taskId);
        if (s == null || closed(s, "v1")) return List.of();
        boolean repeated = CONTENT_DONE.equals(s.getContentStatus());
        LocalDateTime now = now();
        s.setStatus(passed ? SUCCESS : REJECTED);
        s.setFormatErrors(errorsJson);
        s.setContentStatus(CONTENT_DONE);
        if (s.getFormatReceivedAt() == null) s.setFormatReceivedAt(now);
        List<Notice> notices = new ArrayList<>();
        if (!repeated && !superseded(s)) {
            // Включена ли была модель, протокол 1 не сообщает: строку «без замечаний» не пишем.
            studentResult(s, BotMessages.resultForStudent(s.displayFileName(), passed, errorsJson, false, null))
                    .ifPresent(notices::add);
            teacher(s, passed, null).ifPresent(notices::add);
        }
        submissions.save(s);
        return notices;
    }

    // ── протокол v2: фазы format, content, failed ───────────────────────────────────────────────

    @Transactional
    public List<Notice> onFormat(int taskId, boolean passed, boolean last, JsonNode report) {
        Submission s = locked(taskId);
        if (s == null || closed(s, "format")) return List.of();
        if (s.getFormatReceivedAt() != null) {
            // Повтор фазы: отчёт обновляем, пока не пришёл полный; второго уведомления нет.
            if (CONTENT_PENDING.equals(s.getContentStatus())) {
                storeReport(s, report);
                submissions.save(s);
            }
            return List.of();
        }
        LocalDateTime now = now();
        storeReport(s, report);
        s.setFormatReceivedAt(now);
        if (s.getProcessingStartedAt() == null) s.setProcessingStartedAt(now);
        // Отклонённая работа сразу видна преподавателю (решение 9); прошедшая — после содержательной проверки.
        s.setStatus(!passed ? REJECTED : last ? SUCCESS : PROCESSING);
        s.setContentStatus(last ? CONTENT_NONE : CONTENT_PENDING);
        List<Notice> notices = new ArrayList<>();
        if (!superseded(s)) {
            String text = last
                    ? BotMessages.resultForStudent(s.displayFileName(), passed, s.getFormatErrors(), false, null)
                    : BotMessages.checkingForStudent(s.displayFileName(), passed, s.getFormatErrors());
            studentResult(s, text).ifPresent(notices::add);
            if (last) teacher(s, passed, null).ifPresent(notices::add);
        }
        submissions.save(s);
        return notices;
    }

    @Transactional
    public List<Notice> onContent(int taskId, boolean passed, JsonNode report) {
        Submission s = locked(taskId);
        if (s == null || closed(s, "content")) return List.of();
        String before = s.getContentStatus();
        LocalDateTime now = now();
        storeReport(s, report);
        if (s.getFormatReceivedAt() == null) s.setFormatReceivedAt(now);
        if (s.getProcessingStartedAt() == null) s.setProcessingStartedAt(now);
        s.setContentStatus(CONTENT_DONE);
        // После тайм-аута или повтором: отчёт заменяется полным, статус и уведомления — как были.
        if (CONTENT_TIMEOUT.equals(before) || CONTENT_FAILED.equals(before) || CONTENT_DONE.equals(before)) {
            submissions.save(s);
            return List.of();
        }
        s.setStatus(passed ? SUCCESS : REJECTED);
        List<Notice> notices = new ArrayList<>();
        if (!superseded(s)) {
            // Фаза format потерялась — сообщения ещё нет, итог уйдёт новым.
            studentResult(s, BotMessages.resultForStudent(s.displayFileName(), passed, s.getFormatErrors(), true, null))
                    .ifPresent(notices::add);
            teacher(s, passed, null).ifPresent(notices::add);
        }
        submissions.save(s);
        return notices;
    }

    @Transactional
    public List<Notice> onFailed(int taskId, String code, String message) {
        Submission s = locked(taskId);
        if (s == null || closed(s, "failed")) return List.of();
        if (s.getFormatReceivedAt() == null) {
            // До фазы format: работа не проверена и преподавателю не уходит.
            s.setStatus(FAILED);
            s.setFailureCode(code);
            List<Notice> notices = new ArrayList<>();
            if (!superseded(s)) studentFailed(s, message).ifPresent(notices::add);
            submissions.save(s);
            return notices;
        }
        if (!CONTENT_PENDING.equals(s.getContentStatus())) return List.of();
        // После фазы format — как тайм-аут: работа уходит преподавателю с отчётом по оформлению.
        s.setFailureCode(code);
        return contentIncomplete(s, CONTENT_FAILED, "CONTENT_FAILED", "Проверка содержания не удалась.", "сбой проверки");
    }

    // ── сторож (PipelineWatchdog) ──────────────────────────────────────────────────────────────

    @Transactional
    public List<Notice> onContentTimeout(int id) {
        Submission s = locked(id);
        if (s == null || !CONTENT_PENDING.equals(s.getContentStatus()) || s.getFormatReceivedAt() == null
                || s.getFormatReceivedAt().isAfter(now().minus(timeouts.content()))) {
            return List.of();
        }
        long minutes = Math.max(1, timeouts.content().toMinutes());
        return contentIncomplete(s, CONTENT_TIMEOUT, "CONTENT_TIMEOUT",
                "Проверка содержания не уложилась в " + minutes + " мин.", "не хватило времени");
    }

    @Transactional
    public List<Notice> onStuck(int id) {
        Submission s = locked(id);
        if (s == null || !PROCESSING.equals(s.getStatus()) || s.getFormatReceivedAt() != null
                || s.getProcessingStartedAt() == null
                || s.getProcessingStartedAt().isAfter(now().minus(timeouts.stuck()))) {
            return List.of();
        }
        s.setStatus(FAILED);
        s.setFailureCode(FAILURE_STUCK);
        log.warn("Работа #{}: нет фазы format дольше {} — FAILED", s.getId(), timeouts.stuck());
        List<Notice> notices = new ArrayList<>();
        if (!superseded(s)) studentFailed(s, "Проверка зависла и остановлена.").ifPresent(notices::add);
        submissions.save(s);
        return notices;
    }

    // ── общее ──────────────────────────────────────────────────────────────────────────────────

    /** Содержание не проверено (тайм-аут, сбой): работа уходит преподавателю с отчётом по оформлению. */
    private List<Notice> contentIncomplete(Submission s, String contentStatus, String warningCode,
                                           String reportNote, String note) {
        s.setContentStatus(contentStatus);
        if (PROCESSING.equals(s.getStatus())) s.setStatus(SUCCESS);
        addWarning(s, warningCode, reportNote + " В отчёте — только оформление.");
        boolean passed = !REJECTED.equals(s.getStatus());
        List<Notice> notices = new ArrayList<>();
        if (!superseded(s)) {
            studentResult(s, BotMessages.resultForStudent(s.displayFileName(), passed, s.getFormatErrors(), true, note))
                    .ifPresent(notices::add);
            teacher(s, passed, note).ifPresent(notices::add);
        }
        submissions.save(s);
        return notices;
    }

    private Submission locked(int id) {
        Optional<Submission> s = submissions.findByIdForUpdate(id);
        if (s.isEmpty()) log.info("Работа #{} не найдена (отозвана?) — сообщение пропущено", id);
        return s.orElse(null);
    }

    /** Работа уже закрыта сбоем: поздние сообщения записываются в лог и пропускаются. */
    private boolean closed(Submission s, String what) {
        if (!FAILED.equals(s.getStatus())) return false;
        log.info("Работа #{} уже в FAILED — сообщение {} пропущено", s.getId(), what);
        return true;
    }

    /** Студент успел загрузить новую версию: результат старой сохраняем, но никого не тревожим. */
    private boolean superseded(Submission s) {
        return s.getId() != null && submissions.existsByParentId(s.getId());
    }

    /** Итог проверки студенту: в его сообщение об этой работе, если оно уже есть, иначе новым. */
    private Optional<Notice> studentResult(Submission s, String html) {
        if (s.getStudent() == null) return Optional.empty();
        List<AppLink> links = List.of(AppLink.work(s.getId()));
        Long chatId = s.getStudent().getTelegramId();
        return Optional.of(s.getStudentMessageId() != null
                ? Notice.studentUpdate(chatId, s.getStudentMessageId(), html, links, s.getId())
                : Notice.student(chatId, html, links, s.getId()));
    }

    private Optional<Notice> studentFailed(Submission s, String reason) {
        if (s.getStudent() == null) return Optional.empty();
        return Optional.of(Notice.student(s.getStudent().getTelegramId(),
                BotMessages.failedForStudent(s.displayFileName(), reason), List.of(AppLink.resubmit()), null));
    }

    /**
     * Работа на проверку преподавателю — один раз за работу. Отклонённую он видит в очереди раньше и мог
     * уже вынести вердикт, пока проверялось содержание, — тогда звать его «вынести решение» незачем.
     */
    private Optional<Notice> teacher(Submission s, boolean passed, String contentNote) {
        if (s.getTeacher() == null || s.getTeacherNotifiedAt() != null) return Optional.empty();
        if (s.getTeacherVerdict() != null && !s.getTeacherVerdict().isBlank()) return Optional.empty();
        s.setTeacherNotifiedAt(now());
        User student = s.getStudent();
        String studentName = student != null && !student.displayName().isEmpty() ? student.displayName() : "Студент";
        String html = BotMessages.resultForTeacher(studentName, student != null ? student.getGroupNumber() : null,
                s.displayFileName(), passed, s.getParentId() != null, s.getFirstSubmittedAt(), s.getFormatErrors(),
                contentNote);
        return Optional.of(Notice.teacher(s.getTeacher().getTelegramId(), html, List.of(AppLink.work(s.getId())), s.getId()));
    }

    private static void storeReport(Submission s, JsonNode report) {
        JsonNode body = report != null && report.isObject() ? report : JSON.createObjectNode();
        s.setReport(body.toString());
        s.setFormatErrors(LegacyFindings.of(body));
        // Вид работы студент не выбирает — его распознаёт обработчик по титульному листу.
        JsonNode detected = body.path("document").path("title_page").path("work_type");
        String workType = WorkTypes.fromTitle(detected.isTextual() ? detected.asText() : null, s.getSemester());
        if (workType != null) s.setWorkType(workType);
    }

    /** Пометка в отчёте: почему нет содержательной части. Её видно в pipeline.warnings. */
    private static void addWarning(Submission s, String code, String message) {
        if (s.getReport() == null) return;
        try {
            JsonNode root = JSON.readTree(s.getReport());
            if (!root.isObject()) return;
            ObjectNode pipeline = root.path("pipeline").isObject()
                    ? (ObjectNode) root.get("pipeline") : ((ObjectNode) root).putObject("pipeline");
            ArrayNode warnings = pipeline.path("warnings").isArray()
                    ? (ArrayNode) pipeline.get("warnings") : pipeline.putArray("warnings");
            warnings.addObject().put("code", code).put("message", message);
            s.setReport(root.toString());
        } catch (Exception e) {
            log.warn("Работа #{}: не удалось дописать пометку {} в отчёт: {}", s.getId(), code, e.getMessage());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
