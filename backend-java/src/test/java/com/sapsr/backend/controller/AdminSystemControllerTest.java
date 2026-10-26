package com.sapsr.backend.controller;

import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.AcademicSetting;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.llm.LlmModels;
import com.sapsr.backend.llm.WorkerStatusListener;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** «Красная зона» (решение 13): модель проверки выбирает администратор из моделей обработчика. */
class AdminSystemControllerTest {

    private static final long ADMIN_ID = 9001L;
    private static final long STUDENT_ID = 1001L;
    private static final String STATUS = """
            {"schema": 2, "type": "status", "sent_at": "2026-10-26T12:00:00+03:00", "mode": "hybrid",
             "llm_mode": "live", "cloud_allowed": true, "kb_version": "2026.10.18", "default_model": "main",
             "models": [
               {"id": "light", "title": "Лёгкая", "tier": "light", "model": "qwen3.5:2b", "cloud": false, "available": true},
               {"id": "main", "title": "Основная", "tier": "main", "model": "qwen3.5:4b", "cloud": false, "available": true},
               {"id": "heavy", "title": "Тяжёлая", "tier": "heavy", "model": "qwen3.5:9b", "cloud": false,
                "available": false, "reason": "Модель qwen3.5:9b не загружена в Ollama."},
               {"id": "cloud", "title": "Облачная", "tier": "cloud", "model": "big", "cloud": true, "available": true},
               {"id": "../../etc", "title": "Чужая", "available": true}
             ]}""";

    private final AcademicSettingRepository settings = mock(AcademicSettingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final Map<String, AcademicSetting> stored = new HashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-26T09:00:00Z"), AcademicCalendar.ZONE);
    private LlmModels models;
    private AdminSystemController controller;

    @BeforeEach
    void setUp() {
        models = new LlmModels(settings, users, clock);
        controller = new AdminSystemController(models, users);
        User admin = new User();
        admin.setTelegramId(ADMIN_ID);
        admin.setRole("ADMIN");
        admin.setFullName("Халилов Р.");
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        User student = new User();
        student.setTelegramId(STUDENT_ID);
        student.setRole("STUDENT");
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(student));
        when(settings.findById(LlmModels.SETTING)).thenAnswer(call -> Optional.ofNullable(stored.get(LlmModels.SETTING)));
        when(settings.save(any(AcademicSetting.class))).thenAnswer(call -> {
            AcademicSetting s = call.getArgument(0);
            stored.put(s.getKey(), s);
            return s;
        });
        doAnswer(call -> stored.remove((String) call.getArgument(0))).when(settings).deleteById(any());
    }

    @Test
    @Tag("negative")
    void onlyAdminSeesAndChanges() {
        assertThat(status(controller.getSystem(as(STUDENT_ID)))).isEqualTo(403);
        assertThat(status(controller.getSystem(new MockHttpServletRequest()))).isEqualTo(401);
        assertThat(status(controller.setModel(Map.of("model", "light"), as(STUDENT_ID)))).isEqualTo(403);
        verify(settings, never()).save(any());
    }

    @Test
    @Tag("boundary")
    void beforeWorkerReportsNothingCanBeChosen() {
        ResponseEntity<?> response = controller.setModel(Map.of("model", "light"), as(ADMIN_ID));

        assertThat(status(response)).isEqualTo(409);
        assertThat(body(controller.getSystem(as(ADMIN_ID)))).containsEntry("active", null);
    }

    @Test
    @Tag("typical")
    @SuppressWarnings("unchecked")
    void adminChoosesModelForNewChecksWithoutRestart() {
        report(STATUS);

        Map<String, Object> view = body(controller.setModel(Map.of("model", "light"), as(ADMIN_ID)));

        assertThat(view).containsEntry("selected", "light").containsEntry("active", "light")
                .containsEntry("default_model", "main").containsEntry("changed_by", "Халилов Р.");
        assertThat(models.forTask()).isEqualTo("light");
        assertThat((List<LlmModels.Model>) view.get("models")).extracting(LlmModels.Model::id)
                .as("id не по форме каталога не принимается").containsExactly("light", "main", "heavy", "cloud");
        assertThat((Map<String, Object>) view.get("worker")).containsEntry("online", true)
                .containsEntry("cloud_allowed", true);

        // Модель по умолчанию — это снять выбор: новые задания уходят без id.
        body(controller.setModel(Map.of("model", "main"), as(ADMIN_ID)));
        assertThat(models.forTask()).isNull();
    }

    @Test
    @Tag("negative")
    void unavailableOrUnknownModelIsRefused() {
        report(STATUS);

        assertThat(status(controller.setModel(Map.of("model", "heavy"), as(ADMIN_ID)))).isEqualTo(409);
        assertThat(status(controller.setModel(Map.of("model", "gpt-5"), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(models.forTask()).isNull();
    }

    @Test
    @Tag("typical")
    void cloudNeedsExplicitConfirmationAndStudentsAreTold() {
        report(STATUS);

        ResponseEntity<?> refused = controller.setModel(Map.of("model", "cloud"), as(ADMIN_ID));
        assertThat(status(refused)).isEqualTo(409);
        assertThat(body(refused)).containsEntry("code", "CLOUD_CONFIRMATION_REQUIRED");
        assertThat(models.cloudActive()).isFalse();

        controller.setModel(Map.of("model", "cloud", "confirm_cloud", true), as(ADMIN_ID));
        assertThat(models.forTask()).isEqualTo("cloud");
        assertThat(models.cloudActive()).isTrue();
    }

    @Test
    @Tag("boundary")
    void silentWorkerIsShownOffline() {
        report(STATUS);
        clock.advance(Duration.ofMinutes(3));

        @SuppressWarnings("unchecked")
        Map<String, Object> worker = (Map<String, Object>) body(controller.getSystem(as(ADMIN_ID))).get("worker");
        assertThat(worker).containsEntry("online", false).containsEntry("seconds_ago", 180L);
    }

    private void report(String json) {
        new WorkerStatusListener(models).handleStatus(json);
    }

    private static MockHttpServletRequest as(long telegramId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("telegram_id", telegramId);
        return request;
    }

    private static int status(ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
