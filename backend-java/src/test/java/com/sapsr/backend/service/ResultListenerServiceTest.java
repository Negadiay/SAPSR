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
        student.setGroupNumber("321702");
        User teacher = new User();
        teacher.setTelegramId(TEACHER_ID);
        work = new Submission();
        work.setId(153);
        work.setStatus("QUEUED");
        work.setStudent(student);
        work.setTeacher(teacher);
        work.setOriginalFileName("Курсовая.pdf");
        when(submissions.findByIdForUpdate(153)).thenReturn(Optional.of(work));
        when(bot.send(anyLong(), anyString(), any())).thenReturn(555);
        // Номер сообщения студенту база запоминает отдельным запросом — здесь его повторяет заглушка.
        doAnswer(call -> { work.setStudentMessageId(call.getArgument(1)); return 1; })
                .when(submissions).setStudentMessageId(eq(153), anyInt());
    }

    // ── результат v1 ─────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("typical")
    void rejectedWorkStillGoesToTeacher() {
        listener.handleResult(REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(work.getContentStatus()).isEqualTo("DONE");
        verify(submissions).setTeacherMessageId(153, 555);
        verify(submissions).setStudentMessageId(153, 555);
        assertThat(sent(TEACHER_ID)).startsWith("📥 <b>Новая работа</b>\nТерешкова А.В. · 321702\nКурсовая.pdf")
                .contains("Оформление: ошибок — 1")
                .contains("Содержание: 2 замечания, 1 — на ваше решение");
        assertThat(sent(STUDENT_ID)).startsWith("❌ <b>Ошибки в оформлении: 1</b>")
                .contains("• Левое поле меньше 30 мм")
                .contains("Содержание: 2 замечания")
                .contains("срок засчитан");
    }

    @Test
    @Tag("typical")
    void workTypeComesFromTitlePageAndDiplomaOnlyInEighthSemester() {
        work.setSemester(7);
        listener.handleResult(formatWithTitle("course_work"));
        assertThat(work.getWorkType()).isEqualTo("course_work");

        // Дипломная на титуле в 7 семестре — ошибка титула: вид работы прежний.
        listener.handleResult(contentWithTitle("diploma"));
        assertThat(work.getWorkType()).isEqualTo("course_work");
    }

    @Test
    @Tag("boundary")
    void eighthSemesterWorkStaysDiploma() {
        work.setSemester(8);
        work.setWorkType("diploma");
        listener.handleResult(formatWithTitle("course_project"));
        assertThat(work.getWorkType()).isEqualTo("diploma");
    }

    private static String formatWithTitle(String workType) {
        return """
                {"schema": 2, "task_id": 153, "phase": "format", "passed": true, "final": false,
                 "report": {"schema": 2, "findings": [], "document": {"title_page": {"work_type": "%s"}}}}"""
                .formatted(workType);
    }

    private static String contentWithTitle(String workType) {
        return formatWithTitle(workType).replace("\"format\"", "\"content\"").replace("\"final\": false", "\"final\": true");
    }

    @Test
    @Tag("typical")
    void newVersionMentionsFirstArrival() {
        work.setParentId(150);
        work.setFirstSubmittedAt(LocalDateTime.of(2026, 10, 20, 9, 15));

        listener.handleResult("{\"task_id\": 153, \"status\": \"SUCCESS\", \"errors\": []}");

        assertThat(sent(TEACHER_ID)).contains("Новая версия работы").contains("Первая версия — 20.10.2026, 09:15")
                .contains("Оформление: без ошибок").doesNotContain("Содержание");
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
    void studentGetsOneMessageThatIsCompletedAfterContent() throws IOException {
        listener.handleResult(example("result-format.json"));

        assertThat(work.getStatus()).isEqualTo("PROCESSING");
        assertThat(work.getContentStatus()).isEqualTo("PENDING");
        assertThat(work.getReport()).contains("FMT-MARGIN-LEFT");
        // Неблокирующее замечание уровня major в форме v1 — предупреждение, как переводит обработчик.
        assertThat(work.getFormatErrors()).contains("\"severity\":\"warning\"");
        assertThat(sent(STUDENT_ID)).startsWith("✅ <b>Оформление без ошибок</b>")
                .contains("итог появится в этом сообщении");
        verify(bot, never()).send(eq(TEACHER_ID), anyString(), any());

        clearInvocations(bot);
        when(bot.edit(anyLong(), anyInt(), anyString(), any())).thenReturn(true);
        listener.handleResult(example("result-content.json"));
        listener.handleResult(example("result-content.json"));  // повтор доставки

        assertThat(work.getStatus()).isEqualTo("SUCCESS");
        assertThat(work.getContentStatus()).isEqualTo("DONE");
        assertThat(edited(STUDENT_ID)).contains("Содержание:").endsWith("Работа у преподавателя.");
        verify(bot, never()).send(eq(STUDENT_ID), anyString(), any());
        assertThat(sent(TEACHER_ID)).startsWith("📥 <b>Новая работа</b>").contains("Содержание:");
    }

    @Test
    @Tag("boundary")
    void deletedMessageIsReplacedByNewOne() throws IOException {
        listener.handleResult(example("result-format.json"));
        clearInvocations(bot);
        when(bot.edit(anyLong(), anyInt(), anyString(), any())).thenReturn(false);

        listener.handleResult(example("result-content.json"));

        assertThat(sent(STUDENT_ID)).contains("Работа у преподавателя.");
    }

    @Test
    @Tag("typical")
    void rejectedFormatIsVisibleAtOnceAndTeacherGetsFullReport() {
        listener.handleResult(FORMAT_REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(sent(STUDENT_ID)).startsWith("❌ <b>Ошибки в оформлении: 1</b>")
                .contains("Содержание проверяется").contains("срок засчитан");
        verify(bot, never()).send(eq(TEACHER_ID), anyString(), any());

        clearInvocations(bot);
        when(bot.edit(anyLong(), anyInt(), anyString(), any())).thenReturn(true);
        listener.handleResult(CONTENT_REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(edited(STUDENT_ID)).contains("Содержание: 1 замечание, сдаче не мешают.");
        assertThat(sent(TEACHER_ID)).contains("Оформление: ошибок — 1")
                .contains("Содержание: 1 замечание, 1 — на ваше решение");
    }

    @Test
    @Tag("boundary")
    void teacherWhoAlreadyDecidedIsNotCalledAgain() {
        // Отклонённую работу преподаватель видит сразу и успел вынести вердикт, пока проверялось содержание.
        listener.handleResult(FORMAT_REJECTED);
        work.setTeacherVerdict("REVISION");
        clearInvocations(bot);
        when(bot.edit(anyLong(), anyInt(), anyString(), any())).thenReturn(true);

        listener.handleResult(CONTENT_REJECTED);

        assertThat(work.getContentStatus()).isEqualTo("DONE");
        assertThat(edited(STUDENT_ID)).contains("Содержание: 1 замечание");
        verify(bot, never()).send(eq(TEACHER_ID), anyString(), any());
    }

    @Test
    @Tag("negative")
    void failureBeforeFormatClosesWorkWithoutTeacher() throws IOException {
        listener.handleResult(example("result-failed.json"));

        assertThat(work.getStatus()).isEqualTo("FAILED");
        assertThat(work.getFailureCode()).isEqualTo("PDF_ENCRYPTED");
        assertThat(sent(STUDENT_ID)).startsWith("⚠️ <b>Работу не удалось проверить</b>")
                .contains("Файл защищён паролем").contains("Загрузите её снова");
        verify(bot, never()).send(eq(TEACHER_ID), anyString(), any());

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
        when(bot.edit(anyLong(), anyInt(), anyString(), any())).thenReturn(true);

        listener.handleResult(example("result-failed.json"));

        assertThat(work.getStatus()).isEqualTo("SUCCESS");
        assertThat(work.getContentStatus()).isEqualTo("FAILED");
        assertThat(work.getReport()).contains("CONTENT_FAILED");
        assertThat(edited(STUDENT_ID)).contains("Содержание: не проверено (сбой проверки).");
        assertThat(sent(TEACHER_ID)).contains("Содержание: не проверено (сбой проверки)");
    }

    @Test
    @Tag("boundary")
    void contentTimeoutSendsWorkAndLateContentReplacesReportSilently() throws IOException {
        listener.handleResult(example("result-format.json"));
        clearInvocations(bot);
        when(bot.edit(anyLong(), anyInt(), anyString(), any())).thenReturn(true);
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
        assertThat(sent(TEACHER_ID)).contains("не проверено (не хватило времени)");
        assertThat(edited(STUDENT_ID)).contains("не проверено (не хватило времени)");

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
        assertThat(sent(STUDENT_ID)).contains("Проверка зависла и остановлена.");
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

    /** Единственное новое сообщение этому человеку. */
    private String sent(long chatId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot, atLeastOnce()).send(eq(chatId), text.capture(), any());
        assertThat(text.getAllValues()).hasSize(1);
        return text.getValue();
    }

    /** Единственная правка сообщения, отправленного раньше (номер 555). */
    private String edited(long chatId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot, atLeastOnce()).edit(eq(chatId), eq(555), text.capture(), any());
        assertThat(text.getAllValues()).hasSize(1);
        return text.getValue();
    }
}
