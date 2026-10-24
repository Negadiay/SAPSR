package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.ProcentovkaDate;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.messaging.TaskMessage;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.service.BsuirApiService;
import com.sapsr.backend.service.EmailVerificationService;
import com.sapsr.backend.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadControllerTest {

    private static final long STUDENT_ID = 1001L;
    private static final byte[] PDF = "%PDF-1.7 курсовая".getBytes(StandardCharsets.UTF_8);

    @TempDir Path root;
    private Path storageRoot;

    private static final long TEACHER_ID = 2002L;

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ReviewFileService reviewFiles = mock(ReviewFileService.class);
    private final ProcentovkaDateRepository procentovki = mock(ProcentovkaDateRepository.class);
    private UploadController controller;

    @BeforeEach
    void setUp() throws IOException {
        // Корень хранилища — вложенная папка, чтобы проверить, что за его пределы ничего не пишется.
        storageRoot = Files.createDirectory(root.resolve("storage"));
        // 23 октября 2026 года, вторая опроцентовка 29-го: студент 321702 — 4 курс, 7 семестр, этап 2.
        Clock october = Clock.fixed(LocalDateTime.of(2026, 10, 23, 12, 0).atZone(AcademicCalendar.ZONE).toInstant(),
                AcademicCalendar.ZONE);
        when(procentovki.findByAcademicYearAndHalf(2026, "AUTUMN")).thenReturn(List.of(
                new ProcentovkaDate(2026, "AUTUMN", 4, 2, LocalDate.of(2026, 10, 29), LocalDateTime.now(), 1L),
                new ProcentovkaDate(2026, "AUTUMN", 4, ProcentovkaDate.DEADLINE, LocalDate.of(2026, 12, 26),
                        LocalDateTime.now(), 1L)));
        AcademicCalendar calendar = new AcademicCalendar(mock(AcademicSettingRepository.class),
                mock(GroupCourseOverrideRepository.class), procentovki, october);
        controller = new UploadController(rabbit, submissions, users,
                mock(EmailVerificationService.class), mock(BsuirApiService.class), mock(SapsrTelegramBot.class),
                new LocalFileStorage(storageRoot, Clock.systemDefaultZone()), reviewFiles, calendar);
        ReflectionTestUtils.setField(controller, "tasksQueue", "pdf_tasks_queue");

        User student = new User();
        student.setTelegramId(STUDENT_ID);
        student.setRole("STUDENT");
        student.setLastName("Иванов");
        student.setGroupNumber("321702");
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(student));
        User teacher = new User();
        teacher.setTelegramId(TEACHER_ID);
        teacher.setRole("TEACHER");
        when(users.findById(TEACHER_ID)).thenReturn(Optional.of(teacher));
        when(submissions.save(any(Submission.class))).thenAnswer(call -> {
            Submission saved = call.getArgument(0);
            saved.setId(153);
            return saved;
        });
    }

    @Test
    void storesFileUnderGeneratedKeyAndKeepsOriginalNameInDatabase() throws IOException {
        ResponseEntity<?> response = upload("Курсовая \"итог\".pdf");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Submission saved = savedSubmission();
        assertThat(saved.getOriginalFileName()).isEqualTo("Курсовая \"итог\".pdf");
        assertThat(saved.getStorageKey()).matches("\\d{4}/\\d{2}/[0-9a-f-]{36}\\.pdf");
        assertThat(saved.getFileSha256()).hasSize(64);
        assertThat(saved.getTraceId()).isNotNull();
        assertThat(saved.getStatus()).as("в очереди, пока обработчик не начал").isEqualTo("QUEUED");

        List<Path> files = storedFiles();
        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.getFileName().toString()).doesNotContain("Курсовая");
            assertThat(Files.readAllBytes(file)).isEqualTo(PDF);
        });
    }

    @Test
    void sendsTaskRecordWithStorageKey() {
        upload("Курсовая.pdf");

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(org.mockito.ArgumentMatchers.eq("pdf_tasks_queue"), message.capture());
        assertThat(message.getValue()).isInstanceOf(TaskMessage.class);

        TaskMessage task = (TaskMessage) message.getValue();
        Submission saved = savedSubmission();
        assertThat(task.taskId()).isEqualTo(153);
        assertThat(task.file().storageKey()).isEqualTo(saved.getStorageKey());
        assertThat(task.file().sha256()).isEqualTo(saved.getFileSha256());
        assertThat(task.traceId()).isEqualTo(saved.getTraceId());
        assertThat(task.student().lastName()).isEqualTo("Иванов");
        assertThat(task.student().group()).isEqualTo("321702");
        // Переходное поле для нынешнего обработчика: абсолютный путь к тому же файлу.
        assertThat(Path.of(task.filePath())).isEqualTo(storageRoot.resolve(saved.getStorageKey()).toAbsolutePath());
    }

    @Test
    void passesCheckProfileToTask() {
        // Семестр и этап студент не выбирает: сервер определяет их по группе и календарю (решение 11).
        controller.uploadFile(pdf("Курсовая.pdf"), null, "course_work", request());

        Submission saved = savedSubmission();
        assertThat(saved.getWorkType()).isEqualTo("course_work");
        assertThat(saved.getSemester()).isEqualTo(7);
        assertThat(saved.getStage()).isEqualTo(2);
        ArgumentCaptor<TaskMessage> task = ArgumentCaptor.forClass(TaskMessage.class);
        verify(rabbit).convertAndSend(anyString(), task.capture());
        assertThat(task.getValue().profile()).isEqualTo(new TaskMessage.CheckProfile("course_work", 7, 2));
    }

    @Test
    void stripsBrowserPathFromOriginalName() {
        upload("C:\\fakepath\\работа.pdf");

        assertThat(savedSubmission().getOriginalFileName()).isEqualTo("работа.pdf");
    }

    @Test
    void restoresQuotesEncodedByBrowser() {
        // Так браузеры и requests передают имя «Курсовая "итог".pdf» в multipart.
        upload("Курсовая %22итог%22.pdf");

        assertThat(savedSubmission().getOriginalFileName()).isEqualTo("Курсовая \"итог\".pdf");
    }

    @Test
    void replacesEmptyOriginalName() {
        upload("   ");

        assertThat(savedSubmission().getOriginalFileName()).isEqualTo("file.pdf");
    }

    @Test
    void fileNameWithParentDirectoryCannotEscapeStorage() throws IOException {
        upload("../../evil.pdf");

        assertThat(savedSubmission().getOriginalFileName()).isEqualTo("evil.pdf");
        try (Stream<Path> outside = Files.list(root)) {
            assertThat(outside).containsExactly(storageRoot);
        }
        assertThat(storedFiles()).hasSize(1);
    }

    @Test
    void studentWithoutKnownCourseGetsNoSemester() {
        User graduate = users.findById(STUDENT_ID).orElseThrow();
        graduate.setGroupNumber("921701");  // 2019 год поступления — курс больше четвёртого

        controller.uploadFile(pdf("Курсовая.pdf"), null, "course_work", request());

        assertThat(savedSubmission().getSemester()).isNull();
        assertThat(savedSubmission().getStage()).as("сроки — по курсу, а курс не определился").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void meShowsCourseSemesterAndStage() {
        Map<String, Object> me = (Map<String, Object>) controller.getMe(request()).getBody();

        assertThat(me).containsEntry("group", "321702").containsEntry("course", 4).containsEntry("semester", 7)
                .containsEntry("stage", 2).containsEntry("deadline", "2026-12-26").containsEntry("course_overridden", false);
    }

    @Test
    void adminAccountCannotBeReRegistered() {
        // Роль администратора задаётся только в базе (решение 11).
        User admin = new User();
        admin.setTelegramId(9001L);
        admin.setRole("ADMIN");
        when(users.findById(9001L)).thenReturn(Optional.of(admin));

        ResponseEntity<?> response = controller.register(
                Map.of("role", "STUDENT", "full_name", "Иванов И.И. (гр. 321702)"), requestAs(9001L));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        verify(users, never()).save(any());
    }

    @Test
    void registrationCannotGrantAdminRole() {
        ResponseEntity<?> response = controller.register(Map.of("role", "ADMIN", "full_name", "Хакер"), requestAs(7777L));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(users, never()).save(any());
    }

    @Test
    void rejectsInvalidProfileWithoutStoringAnything() throws IOException {
        ResponseEntity<?> badType = controller.uploadFile(pdf("a.pdf"), null, "Курсовая", request());

        assertThat(List.of(badType))
                .allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(400));
        assertThat(storedFiles()).isEmpty();
        verifyNoInteractions(rabbit);
        verify(submissions, never()).save(any());
    }

    @Test
    void withdrawalDeletesStoredFile() throws IOException {
        upload("Курсовая.pdf");
        Submission saved = savedSubmission();
        saved.setStatus("SUCCESS");
        when(submissions.findById(153)).thenReturn(Optional.of(saved));
        assertThat(storedFiles()).hasSize(1);

        ResponseEntity<?> response = controller.withdrawSubmission(153, request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(submissions).deleteById(153);
        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void withdrawalSucceedsWhenFileIsAlreadyGone() throws IOException {
        upload("Курсовая.pdf");
        Submission saved = savedSubmission();
        saved.setStatus("SUCCESS");
        when(submissions.findById(153)).thenReturn(Optional.of(saved));
        Files.delete(storageRoot.resolve(saved.getStorageKey()));

        ResponseEntity<?> response = controller.withdrawSubmission(153, request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(submissions).deleteById(153);
    }

    @Test
    void withdrawalOfOldSubmissionDeletesFileByLegacyKey() throws IOException {
        // После V3 у старых работ ключ — имя файла в корне хранилища.
        Path legacyFile = Files.write(storageRoot.resolve("1712345678901_Курсовая.pdf"), PDF);
        Submission old = new Submission();
        old.setId(7);
        old.setStatus("SUCCESS");
        old.setStorageKey("1712345678901_Курсовая.pdf");
        User student = users.findById(STUDENT_ID).orElseThrow();
        old.setStudent(student);
        when(submissions.findById(7)).thenReturn(Optional.of(old));

        controller.withdrawSubmission(7, request());

        assertThat(legacyFile).doesNotExist();
    }

    @Test
    void newUploadToSameTeacherBecomesNextVersionAndKeepsFirstDate() {
        // Решение 9: срок сдачи считается от первой версии, даже если она не прошла оформление.
        LocalDateTime firstArrival = LocalDateTime.of(2026, 10, 20, 9, 15);
        Submission previous = new Submission();
        previous.setId(150);
        previous.setStatus("REJECTED");
        previous.setFirstSubmittedAt(firstArrival);
        when(submissions.findFirstByStudent_TelegramIdAndTeacher_TelegramIdOrderByCreatedAtDescIdDesc(STUDENT_ID, TEACHER_ID))
                .thenReturn(Optional.of(previous));

        controller.uploadFile(pdf("Курсовая v2.pdf"), TEACHER_ID, null, request());

        Submission saved = savedSubmission();
        assertThat(saved.getParentId()).isEqualTo(150);
        assertThat(saved.getFirstSubmittedAt()).isEqualTo(firstArrival);
        assertThat(saved.submittedForDeadline()).isEqualTo(firstArrival);
    }

    @Test
    void uploadAfterApprovedWorkStartsNewWork() {
        Submission approved = new Submission();
        approved.setId(150);
        approved.setStatus("SUCCESS");
        approved.setTeacherVerdict("APPROVED");
        approved.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(submissions.findFirstByStudent_TelegramIdAndTeacher_TelegramIdOrderByCreatedAtDescIdDesc(STUDENT_ID, TEACHER_ID))
                .thenReturn(Optional.of(approved));

        controller.uploadFile(pdf("Новая работа.pdf"), TEACHER_ID, null, request());

        Submission saved = savedSubmission();
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getFirstSubmittedAt()).isNull();
    }

    @Test
    void reportIsAvailableToStudentAndTeacherOnly() {
        Submission s = new Submission();
        s.setId(153);
        s.setStatus("REJECTED");
        s.setStudent(users.findById(STUDENT_ID).orElseThrow());
        s.setTeacher(users.findById(TEACHER_ID).orElseThrow());
        s.setFormatErrors("[{\"severity\":\"warning\",\"category\":\"content\",\"origin\":\"hybrid\","
                + "\"needs_teacher_review\":true,\"page\":4,\"message\":\"Форматы описаны по-разному\"}]");
        when(submissions.findById(153)).thenReturn(Optional.of(s));

        assertThat(controller.getReport(153, requestAs(STUDENT_ID)).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.getReport(153, requestAs(TEACHER_ID)).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.getReport(153, requestAs(9999L)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.getReport(153, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void withdrawalDeletesTeacherReviewFile() {
        upload("Курсовая.pdf");
        Submission saved = savedSubmission();
        saved.setStatus("SUCCESS");
        when(submissions.findById(153)).thenReturn(Optional.of(saved));

        controller.withdrawSubmission(153, request());

        verify(reviewFiles).deleteFor(153);
    }

    @Test
    void splitsStudentNameFromCurrentForm() {
        User user = new User();

        UploadController.setNameParts(user, "Иванов И.И. (гр. 321702)", "321702");

        assertThat(user.getLastName()).isEqualTo("Иванов");
        assertThat(user.getFirstName()).isNull();
        assertThat(user.getMiddleName()).isNull();
        assertThat(user.getGroupNumber()).isEqualTo("321702");
    }

    @Test
    void splitsTeacherNameFromIis() {
        User user = new User();

        UploadController.setNameParts(user, "Петрова Анна Сергеевна", null);

        assertThat(user.getLastName()).isEqualTo("Петрова");
        assertThat(user.getFirstName()).isEqualTo("Анна");
        assertThat(user.getMiddleName()).isEqualTo("Сергеевна");
        assertThat(user.getGroupNumber()).isNull();
    }

    private ResponseEntity<?> upload(String fileName) {
        return controller.uploadFile(pdf(fileName), null, null, request());
    }

    private static MockMultipartFile pdf(String fileName) {
        return new MockMultipartFile("file", fileName, "application/pdf", PDF);
    }

    private static MockHttpServletRequest request() {
        return requestAs(STUDENT_ID);
    }

    private static MockHttpServletRequest requestAs(long telegramId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("telegram_id", telegramId);
        return request;
    }

    private Submission savedSubmission() {
        ArgumentCaptor<Submission> saved = ArgumentCaptor.forClass(Submission.class);
        verify(submissions).save(saved.capture());
        return saved.getValue();
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(storageRoot)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }
}
