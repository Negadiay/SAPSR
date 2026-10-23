package com.sapsr.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.pipeline.NoticeSender;
import com.sapsr.backend.pipeline.PipelineState;
import com.sapsr.backend.pipeline.PipelineTimeouts;
import com.sapsr.backend.pipeline.PipelineWatchdog;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Результаты обработчика v1 и v2 и ход проверки — queue-v2.md, разделы 4–6. */
class ResultListenerServiceTest {

    private static final long STUDENT_ID = 1001L;
    private static final long TEACHER_ID = 2002L;
    // Образцы сообщений — общие с обработчиком (TaskMessageTest сверяет по ним задание).
    private static final Path EXAMPLES = Path.of("..", "docs", "contracts", "examples", "queue-v2");
    // Результат v1 от обработчика: ошибка оформления и два содержательных замечания (одно — на проверку).
    private static final String REJECTED = """
            {"task_id": 153, "status": "FAIL", "errors": [
              {"severity": "major", "category": "format", "origin": "symbolic", "page": 3, "message": "Левое поле меньше 30 мм"},
              {"severity": "warning", "category": "content", "origin": "hybrid", "needs_teacher_review": false, "page": 4,
               "message": "Форматы входных файлов описаны по-разному"},
              {"severity": "warning", "category": "requirements", "origin": "hybrid", "needs_teacher_review": true,
               "message": "Не удалось установить, описаны ли точки интеграции"}
            ]}""";
    private static final String FORMAT_REJECTED = """
            {"schema": 2, "task_id": 153, "phase": "format", "passed": false, "final": false,
             "report": {"schema": 2, "findings": [
               {"id": "f-001", "requirement_id": "FMT-MARGIN-LEFT", "category": "format", "severity": "major",
                "origin": "symbolic", "blocking": true, "message": "Левое поле меньше 30 мм", "page": 3}],
              "pipeline": {"warnings": []}}}""";
    private static final String CONTENT_REJECTED = """
            {"schema": 2, "task_id": 153, "phase": "content", "passed": false, "final": true,
             "report": {"schema": 2, "findings": [
               {"id": "f-001", "requirement_id": "FMT-MARGIN-LEFT", "category": "format", "severity": "major",
                "origin": "symbolic", "blocking": true, "message": "Левое поле меньше 30 мм", "page": 3},
               {"id": "f-002", "requirement_id": "CNT-CONSIST-002", "category": "content", "severity": "major",
                "origin": "hybrid", "blocking": false, "needs_teacher_review": true,
                "message": "Форматы входных файлов описаны по-разному"}]}}""";

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final SapsrTelegramBot bot = mock(SapsrTelegramBot.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T09:00:00Z"), ZoneId.of("Europe/Minsk"));
    private final PipelineTimeouts timeouts = new PipelineTimeouts(Duration.ofMinutes(5), Duration.ofMinutes(15));
    private final PipelineState state = new PipelineState(submissions, timeouts, clock);
    private final NoticeSender sender = new NoticeSender(bot, submissions);
    private final ResultListenerService listener = new ResultListenerService(state, sender, submissions, clock);
    private final PipelineWatchdog watchdog = new PipelineWatchdog(submissions, state, sender, timeouts, clock);
    private Submission work;

    @BeforeEach
    void setUp() {
        User student = new User();
        student.setTelegramId(STUDENT_ID);
        student.setFullName("Терешкова А.В. (гр. 321702)");
        User teacher = new User();
        teacher.setTelegramId(TEACHER_ID);
        work = new Submission();
        work.setId(153);
        work.setStatus("QUEUED");
        work.setStudent(student);
        work.setTeacher(teacher);
        work.setOriginalFileName("Курсовая.pdf");
        when(submissions.findByIdForUpdate(153)).thenReturn(Optional.of(work));
        when(bot.notifyUser(anyLong(), anyString())).thenReturn(555);
    }

    // ── результат v1 ─────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("typical")
    void rejectedWorkStillGoesToTeacher() {
        listener.handleResult(REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(work.getContentStatus()).isEqualTo("DONE");
        verify(submissions).setTeacherMessageId(153, 555);
        String teacherText = sent(TEACHER_ID);
        assertThat(teacherText).startsWith("📥 <b>Новая работа</b>")
                .contains("👤 Терешкова А.В. (гр. 321702)")
                .contains("Оформление: ❌ ошибок — 1")
                .contains("Содержание: замечаний — 2, на вашу проверку — 1");
        String studentText = sent(STUDENT_ID);
        assertThat(studentText).startsWith("❌ <b>В работе есть ошибки оформления</b>")
                .contains("Оформление: ошибок — 1.")
                .contains("• Левое поле меньше 30 мм")
                .contains("Содержание: замечаний — 2")
                .contains("срок сдачи засчитан");
    }

    @Test
    @Tag("typical")
    void newVersionMentionsFirstArrival() {
        work.setParentId(150);
        work.setFirstSubmittedAt(LocalDateTime.of(2026, 10, 20, 9, 15));

        listener.handleResult("{\"task_id\": 153, \"status\": \"SUCCESS\", \"errors\": []}");

        assertThat(sent(TEACHER_ID)).contains("Новая версия работы").contains("Первая версия пришла 20.10.2026 09:15")
                .contains("Оформление: ✅ без ошибок").doesNotContain("Содержание");
    }

    @Test
    @Tag("boundary")
    void supersededVersionIsSavedSilently() {
        // Студент успел загрузить новую версию, пока эта проверялась.
        when(submissions.existsByParentId(153)).thenReturn(true);

        listener.handleResult(REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(work.getFormatErrors()).contains("Левое поле");
        verifyNoInteractions(bot);
    }

    // ── протокол v2 ──────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("typical")
    void passedFormatWaitsForContentThenGoesToTeacherOnce() throws IOException {
        listener.handleResult(example("result-format.json"));

        assertThat(work.getStatus()).isEqualTo("PROCESSING");
        assertThat(work.getContentStatus()).isEqualTo("PENDING");
        assertThat(work.getReport()).contains("FMT-MARGIN-LEFT");
        // Неблокирующее замечание уровня major в форме v1 — предупреждение, как переводит обработчик.
        assertThat(work.getFormatErrors()).contains("\"severity\":\"warning\"");
        assertThat(sent(STUDENT_ID)).startsWith("✅ <b>Работа прошла проверку оформления</b>")
                .contains("Содержание ещё проверяется").contains("уйдёт преподавателю");
        verify(bot, never()).notifyUser(eq(TEACHER_ID), anyString());

        clearInvocations(bot);
        listener.handleResult(example("result-content.json"));
        listener.handleResult(example("result-content.json"));  // повтор доставки

        assertThat(work.getStatus()).isEqualTo("SUCCESS");
        assertThat(work.getContentStatus()).isEqualTo("DONE");
        assertThat(sent(STUDENT_ID)).startsWith("🧠 <b>Проверка содержания закончена</b>");
        assertThat(sent(TEACHER_ID)).startsWith("📥 <b>Новая работа</b>").contains("Содержание: замечаний");
    }

    @Test
    @Tag("typical")
    void rejectedFormatIsVisibleAtOnceAndTeacherGetsFullReport() {
        listener.handleResult(FORMAT_REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(sent(STUDENT_ID)).startsWith("❌ <b>В работе есть ошибки оформления</b>")
                .contains("Содержание ещё проверяется").contains("срок сдачи засчитан");
        verify(bot, never()).notifyUser(eq(TEACHER_ID), anyString());

        clearInvocations(bot);
        listener.handleResult(CONTENT_REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(sent(STUDENT_ID)).contains("Содержание: замечаний — 1, из них на проверку преподавателю — 1")
                .contains("Отчёт у преподавателя дополнен");
        assertThat(sent(TEACHER_ID)).contains("Оформление: ❌ ошибок — 1")
                .contains("Содержание: замечаний — 1, на вашу проверку — 1");
    }

    @Test
    @Tag("boundary")
    void teacherWhoAlreadyDecidedIsNotCalledAgain() {
        // Отклонённую работу преподаватель видит сразу и успел вынести вердикт, пока проверялось содержание.
        listener.handleResult(FORMAT_REJECTED);
        work.setTeacherVerdict("REVISION");
        clearInvocations(bot);

        listener.handleResult(CONTENT_REJECTED);

        assertThat(work.getContentStatus()).isEqualTo("DONE");
        assertThat(sent(STUDENT_ID)).startsWith("🧠 <b>Проверка содержания закончена</b>");
        verify(bot, never()).notifyUser(eq(TEACHER_ID), anyString());
    }

    @Test
    @Tag("negative")
    void failureBeforeFormatClosesWorkWithoutTeacher() throws IOException {
        listener.handleResult(example("result-failed.json"));

        assertThat(work.getStatus()).isEqualTo("FAILED");
        assertThat(work.getFailureCode()).isEqualTo("PDF_ENCRYPTED");
        assertThat(sent(STUDENT_ID)).startsWith("⚠️ <b>Проверка не удалась</b>")
                .contains("Файл защищён паролем").contains("Загрузите работу снова");
        verify(bot, never()).notifyUser(eq(TEACHER_ID), anyString());

        // Поздняя фаза по закрытой работе — в лог, без изменений.
        clearInvocations(bot);
        listener.handleResult(FORMAT_REJECTED);
        assertThat(work.getStatus()).isEqualTo("FAILED");
        verifyNoInteractions(bot);
    }

    @Test
    @Tag("boundary")
    void failureAfterFormatSendsWorkWithFormatReport() throws IOException {
        listener.handleResult(example("result-format.json"));
        clearInvocations(bot);

        listener.handleResult(example("result-failed.json"));

        assertThat(work.getStatus()).isEqualTo("SUCCESS");
        assertThat(work.getContentStatus()).isEqualTo("FAILED");
        assertThat(work.getReport()).contains("CONTENT_FAILED");
        assertThat(sent(STUDENT_ID)).startsWith("⚠️ <b>Содержание проверить не удалось</b>");
        assertThat(sent(TEACHER_ID)).contains("Содержание: ⚠️ проверка содержания не удалась");
    }

    @Test
    @Tag("boundary")
    void contentTimeoutSendsWorkAndLateContentReplacesReportSilently() throws IOException {
        listener.handleResult(example("result-format.json"));
        clearInvocations(bot);
        when(submissions.findContentOverdue(any())).thenAnswer(call ->
                work.getFormatReceivedAt().isBefore(call.getArgument(0)) ? List.of(153) : List.of());
        when(submissions.findStuck(any())).thenReturn(List.of());

        clock.advance(Duration.ofMinutes(4));
        watchdog.tick();
        verifyNoInteractions(bot);

        clock.advance(Duration.ofMinutes(2));
        watchdog.tick();
        assertThat(work.getStatus()).isEqualTo("SUCCESS");
        assertThat(work.getContentStatus()).isEqualTo("TIMEOUT");
        assertThat(work.getReport()).contains("CONTENT_TIMEOUT").contains("не уложилась в 5 мин");
        assertThat(sent(TEACHER_ID)).contains("не проверено — проверка не уложилась в 5 мин");
        assertThat(sent(STUDENT_ID)).contains("Содержательная проверка не уложилась в 5 мин");

        clearInvocations(bot);
        listener.handleResult(example("result-content.json"));
        assertThat(work.getContentStatus()).isEqualTo("DONE");
        assertThat(work.getReport()).doesNotContain("CONTENT_TIMEOUT");
        verifyNoInteractions(bot);
    }

    @Test
    @Tag("negative")
    void watchdogFailsWorkWithoutFormatPhase() {
        work.setStatus("PROCESSING");
        work.setProcessingStartedAt(LocalDateTime.now(clock));
        when(submissions.findContentOverdue(any())).thenReturn(List.of());
        when(submissions.findStuck(any())).thenReturn(List.of(153));

        clock.advance(Duration.ofMinutes(10));
        watchdog.tick();
        assertThat(work.getStatus()).isEqualTo("PROCESSING");  // сторож перепроверяет время сам

        clock.advance(Duration.ofMinutes(6));
        watchdog.tick();
        assertThat(work.getStatus()).isEqualTo("FAILED");
        assertThat(work.getFailureCode()).isEqualTo("STUCK");
        assertThat(sent(STUDENT_ID)).contains("обработчик не ответил");
    }

    @Test
    @Tag("typical")
    void progressRecordsStageAndIgnoresUnknown() throws IOException {
        listener.handleProgress(example("progress.json"));
        listener.handleProgress("{\"schema\": 2, \"task_id\": 153, \"stage\": \"<script>\"}");
        listener.handleProgress("не json");

        verify(submissions).recordProgress(eq(153), eq("structure"), any());
        verify(submissions, times(1)).recordProgress(anyInt(), anyString(), any());
    }

    @Test
    @Tag("negative")
    void messageForWithdrawnWorkIsSkipped() {
        when(submissions.findByIdForUpdate(153)).thenReturn(Optional.empty());

        listener.handleResult(FORMAT_REJECTED);
        listener.handleResult("{\"phase\": \"format\"}");

        verify(submissions, never()).save(any());
        verifyNoInteractions(bot);
    }

    private static String example(String name) throws IOException {
        JsonNode json = new ObjectMapper().readTree(Files.readString(EXAMPLES.resolve(name)));
        return json.toString();
    }

    private String sent(long chatId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot, atLeastOnce()).notifyUser(eq(chatId), text.capture());
        assertThat(text.getAllValues()).hasSize(1);
        return text.getValue();
    }
}
