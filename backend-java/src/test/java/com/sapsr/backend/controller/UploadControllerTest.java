package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.ProcentovkaDate;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.llm.LlmModels;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
    private final LlmModels llmModels = mock(LlmModels.class);
    private final com.sapsr.backend.service.DocxConverter docxConverter =
            mock(com.sapsr.backend.service.DocxConverter.class);
    private UploadController controller;

    @BeforeEach
    void setUp() throws IOException {
        // Корень хранилища — вложенная папка, чтобы проверить, что за его пределы ничего не пишется.
        storageRoot = Files.createDirectory(root.resolve("storage"));
        // 23 октября 2026 года, вторая опроцентовка 29-го: студент 321702 — 4 курс, 7 семестр, этап 2.
        createController(LocalDateTime.of(2026, 10, 23, 12, 0));
        ReflectionTestUtils.setField(controller, "tasksQueue", "pdf_tasks_queue");
        when(procentovki.findByAcademicYearAndHalf(2026, "AUTUMN")).thenReturn(List.of(
                new ProcentovkaDate(2026, "AUTUMN", 4, 2, LocalDate.of(2026, 10, 29), LocalDateTime.now(), 1L),
                new ProcentovkaDate(2026, "AUTUMN", 4, ProcentovkaDate.DEADLINE, LocalDate.of(2026, 12, 26),
                        LocalDateTime.now(), 1L)));

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
        // Курсовая работа или проект — станет известно из титульного листа (PipelineState).
        controller.uploadFile(pdf("Курсовая.pdf"), null, request());

        Submission saved = savedSubmission();
        assertThat(saved.getWorkType()).isNull();
        assertThat(saved.getSemester()).isEqualTo(7);
        assertThat(saved.getStage()).isEqualTo(2);
        ArgumentCaptor<TaskMessage> task = ArgumentCaptor.forClass(TaskMessage.class);
        verify(rabbit).convertAndSend(anyString(), task.capture());
        assertThat(task.getValue().profile()).isEqualTo(new TaskMessage.CheckProfile(null, 7, 2));
        assertThat(task.getValue().llm()).as("модель не выбрана — по умолчанию у обработчика").isNull();
    }

    @Test
    void eighthSemesterWorkIsDiploma() throws IOException {
        // Февраль 2027 года: 321702 — 4 курс, весна, 8 семестр. Дипломная бывает только в нём.
        createController(LocalDateTime.of(2027, 2, 15, 12, 0));
        ReflectionTestUtils.setField(controller, "tasksQueue", "pdf_tasks_queue");

        controller.uploadFile(pdf("Диплом.pdf"), null, request());

        assertThat(savedSubmission().getSemester()).isEqualTo(8);
        assertThat(savedSubmission().getWorkType()).isEqualTo("diploma");
    }

    @Test
    void taskCarriesModelChosenByAdmin() {
        when(llmModels.forTask()).thenReturn("heavy");

        controller.uploadFile(pdf("Курсовая.pdf"), null, request());

        ArgumentCaptor<TaskMessage> task = ArgumentCaptor.forClass(TaskMessage.class);
        verify(rabbit).convertAndSend(anyString(), task.capture());
        assertThat(task.getValue().llm()).isEqualTo(new TaskMessage.LlmChoice("heavy"));
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

        controller.uploadFile(pdf("Курсовая.pdf"), null, request());

        assertThat(savedSubmission().getSemester()).isNull();
        assertThat(savedSubmission().getStage()).as("сроки — по курсу, а курс не определился").isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void meShowsCourseSemesterAndStage() {
        Map<String, Object> me = (Map<String, Object>) controller.getMe(request()).getBody();

        assertThat(me).containsEntry("group", "321702").containsEntry("course", 4).containsEntry("semester", 7)
                .containsEntry("stage", 2).containsEntry("deadline", "2026-12-26").containsEntry("course_overridden", false)
                .containsEntry("cloud_check", false);
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
    void registeredStudentCannotRegisterAgain() {
        // Один аккаунт Telegram — один человек (решение 15): запись не меняется.
        User student = users.findById(STUDENT_ID).orElseThrow();
        student.setFullName("Иванов И.И. (гр. 321702)");

        ResponseEntity<?> response = controller.register(
                Map.of("role", "STUDENT", "full_name", "Петров П.П. (гр. 421702)"), requestAs(STUDENT_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response)).containsEntry("code", "ALREADY_REGISTERED");
        assertThat((String) body(response).get("message")).contains("уже зарегистрирован");
        assertThat(student.getFullName()).isEqualTo("Иванов И.И. (гр. 321702)");
        assertThat(student.getGroupNumber()).isEqualTo("321702");
        verify(users, never()).save(any());
    }

    @Test
    void registeredTeacherCannotRegisterAgain() {
        ResponseEntity<?> response = controller.register(
                Map.of("role", "STUDENT", "full_name", "Петров П.П. (гр. 421702)"), requestAs(TEACHER_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response)).containsEntry("code", "ALREADY_REGISTERED");
        assertThat(users.findById(TEACHER_ID).orElseThrow().getRole()).isEqualTo("TEACHER");
        verify(users, never()).save(any());
    }

    @Test
    void fileOverLimitIsRejectedWithReason() throws IOException {
        ReflectionTestUtils.setField(controller, "maxUploadMb", 0);

        ResponseEntity<?> response = upload("Курсовая.pdf");

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(body(response)).containsEntry("code", "FILE_TOO_LARGE");
        assertThat((String) body(response).get("message")).contains("Telegram не даст переслать");
        assertThat(storedFiles()).isEmpty();
        verify(submissions, never()).save(any());
    }

    @Test
    void docxIsConvertedAndBothFilesAreKept() throws Exception {
        byte[] docx = com.sapsr.backend.storage.FileKindTest.zip("[Content_Types].xml", "word/document.xml");
        when(docxConverter.toPdf(docx)).thenReturn(PDF);

        ResponseEntity<?> response = controller.uploadFile(
                new MockMultipartFile("file", "Курсовая.docx", "application/octet-stream", docx), null, request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Submission saved = savedSubmission();
        assertThat(saved.getSourceFormat()).isEqualTo("docx");
        assertThat(saved.getStorageKey()).endsWith(".pdf");
        assertThat(saved.getSourceStorageKey()).endsWith(".docx");
        assertThat(Files.readAllBytes(storageRoot.resolve(saved.getStorageKey()))).isEqualTo(PDF);
        assertThat(Files.readAllBytes(storageRoot.resolve(saved.getSourceStorageKey()))).isEqualTo(docx);
        assertThat(saved.pdfFileName()).isEqualTo("Курсовая.pdf");
        ArgumentCaptor<TaskMessage> task = ArgumentCaptor.forClass(TaskMessage.class);
        verify(rabbit).convertAndSend(anyString(), task.capture());
        assertThat(task.getValue().file().sourceFormat()).isEqualTo("docx");
        assertThat(task.getValue().file().storageKey()).isEqualTo(saved.getStorageKey());

        when(submissions.findById(153)).thenReturn(Optional.of(saved));
        ResponseEntity<?> source = controller.getSource(153, request());
        assertThat(source.getStatusCode().value()).isEqualTo(200);
        assertThat((byte[]) source.getBody()).isEqualTo(docx);
        assertThat(controller.getPdf(153, request()).getBody()).isEqualTo(PDF);
        assertThat(controller.getSource(153, requestAs(7777L)).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void failedConversionCreatesNoWork() throws Exception {
        byte[] docx = com.sapsr.backend.storage.FileKindTest.zip("word/document.xml");
        when(docxConverter.toPdf(docx)).thenThrow(
                new com.sapsr.backend.service.DocxConverter.ConversionException("тайм-аут", null));

        ResponseEntity<?> response = controller.uploadFile(
                new MockMultipartFile("file", "Курсовая.docx", "application/octet-stream", docx), null, request());

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(body(response)).containsEntry("code", "CONVERSION_FAILED");
        assertThat(storedFiles()).isEmpty();
        verify(submissions, never()).save(any());
        verifyNoInteractions(rabbit);
    }

    @Test
    void fileThatIsNeitherPdfNorDocxIsRejected() throws IOException {
        ResponseEntity<?> response = controller.uploadFile(
                new MockMultipartFile("file", "Курсовая.pdf", "application/pdf", new byte[] {'M', 'Z', 0, 1}),
                null, request());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body(response)).containsEntry("code", "UNSUPPORTED_FORMAT")
                .containsEntry("message", "Нужен файл PDF или DOCX.");
        assertThat(storedFiles()).isEmpty();
        verify(submissions, never()).save(any());
    }

    @Test
    void pdfSourceHasNoDocx() {
        upload("Курсовая.pdf");
        Submission saved = savedSubmission();
        when(submissions.findById(153)).thenReturn(Optional.of(saved));

        assertThat(saved.getSourceFormat()).isEqualTo("pdf");
        assertThat(controller.getSource(153, request()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void studentRegistersByFieldsAndGetsNamesakeNumber() {
        BsuirApiService iis = mock(BsuirApiService.class);
        ReflectionTestUtils.setField(controller, "bsuirApiService", iis);
        when(iis.groupExists("321702")).thenReturn(true);
        when(users.maxNamesakeNumber("321702", "петров-водкин пётр".replace("-", "").replace('ё', 'е'))).thenReturn(1);
        when(users.countNamesakes(anyString(), anyString())).thenReturn(2L);

        ResponseEntity<?> response = controller.register(Map.of("role", "STUDENT", "last_name", " петров - водкин ",
                "first_name", "пётр", "group", "321702"), requestAs(5005L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertThat(user.getLastName()).isEqualTo("Петров-Водкин");
        assertThat(user.getFirstName()).isEqualTo("Пётр");
        assertThat(user.getMiddleName()).isNull();
        assertThat(user.getGroupNumber()).isEqualTo("321702");
        assertThat(user.getFullName()).isEqualTo("Петров-Водкин Пётр (гр. 321702)");
        assertThat(user.displayName()).isEqualTo("Петров-Водкин Пётр");
        assertThat(user.getNameKey()).isEqualTo("петровводкин петр");
        assertThat(user.getNamesakeNumber()).as("наибольший номер тёзки + 1").isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> me = (Map<String, Object>) body(response).get("user");
        assertThat(me).containsEntry("namesake_number", 2).containsEntry("profile_incomplete", false)
                .containsEntry("group", "321702");
    }

    @Test
    void registrationByFieldsValidatesEachField() {
        BsuirApiService iis = mock(BsuirApiService.class);
        ReflectionTestUtils.setField(controller, "bsuirApiService", iis);
        when(iis.groupExists("321702")).thenReturn(true);

        assertThat(body(controller.register(Map.of("role", "STUDENT", "last_name", "Ivanov", "first_name", "Иван",
                "group", "321702"), requestAs(5005L)))).containsEntry("code", "VALIDATION_ERROR").containsEntry("field", "last_name");
        assertThat(body(controller.register(Map.of("role", "STUDENT", "last_name", "Иванов", "first_name", "",
                "group", "321702"), requestAs(5005L)))).containsEntry("field", "first_name");
        assertThat(body(controller.register(Map.of("role", "STUDENT", "last_name", "Иванов", "first_name", "Иван",
                "middle_name", "X", "group", "321702"), requestAs(5005L)))).containsEntry("field", "middle_name");
        assertThat(body(controller.register(Map.of("role", "STUDENT", "last_name", "Иванов", "first_name", "Иван",
                "group", "3217"), requestAs(5005L)))).containsEntry("field", "group");
        assertThat(body(controller.register(Map.of("role", "STUDENT", "last_name", "Иванов", "first_name", "Иван",
                "group", "999999"), requestAs(5005L)))).containsEntry("code", "GROUP_NOT_FOUND");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void unavailableIisIsNotReportedAsMissingGroup() {
        BsuirApiService iis = mock(BsuirApiService.class);
        ReflectionTestUtils.setField(controller, "bsuirApiService", iis);
        when(iis.groupExists("321702")).thenThrow(new BsuirApiService.IisUnavailableException("connect timed out"));

        ResponseEntity<?> fields = controller.register(Map.of("role", "STUDENT", "last_name", "Иванов",
                "first_name", "Иван", "group", "321702"), requestAs(5005L));
        ResponseEntity<?> legacy = controller.register(
                Map.of("role", "STUDENT", "full_name", "Иванов И.И. (гр. 321702)"), requestAs(5006L));

        for (ResponseEntity<?> response : List.of(fields, legacy)) {
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(body(response)).containsEntry("code", "IIS_UNAVAILABLE");
        }
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void studentFixesNameAndKeepsGroup() {
        User student = users.findById(STUDENT_ID).orElseThrow();
        student.setFullName("Иванов И.И. (гр. 321702)");
        student.setNameKey("иванов и.и.");
        student.setNamesakeNumber(1);
        when(users.maxNamesakeNumber("321702", "иванов иван иванович")).thenReturn(2);

        ResponseEntity<?> response = controller.patchMe(Map.of("last_name", "Иванов", "first_name", "Иван",
                "middle_name", "Иванович"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(student.getFirstName()).isEqualTo("Иван");
        assertThat(student.getNamesakeNumber()).as("ФИО изменилось — номер тёзки новый").isEqualTo(3);
        assertThat(student.getGroupNumber()).isEqualTo("321702");
        assertThat(body(response)).containsEntry("profile_incomplete", false).containsEntry("first_name", "Иван");

        // Те же данные ещё раз — номер не меняется.
        controller.patchMe(Map.of("last_name", "ИВАНОВ", "first_name", "иван", "middle_name", "Иванович"), request());
        assertThat(student.getNamesakeNumber()).isEqualTo(3);
    }

    @Test
    void groupCannotBeChangedByStudent() {
        ResponseEntity<?> response = controller.patchMe(Map.of("last_name", "Иванов", "first_name", "Иван",
                "group", "421702"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body(response)).containsEntry("code", "VALIDATION_ERROR").containsEntry("field", "group")
                .containsEntry("message", "Группу меняет администратор.");
        assertThat(controller.patchMe(Map.of("last_name", "Иванов", "first_name", "Иван"), requestAs(TEACHER_ID))
                .getStatusCode().value()).isEqualTo(403);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void meMarksOldAccountAsIncomplete() {
        assertThat(body(controller.getMe(request()))).containsEntry("profile_incomplete", true)
                .containsEntry("namesake_number", null);
    }

    @Test
    void onlyStudentCanUploadAndOnlyToTeacher() throws IOException {
        // План Котко, К7: преподаватель и незарегистрированный работы не сдают; адресат — только преподаватель.
        assertThat(controller.uploadFile(pdf("Курсовая.pdf"), null, requestAs(TEACHER_ID)).getStatusCode().value())
                .isEqualTo(403);
        assertThat(controller.uploadFile(pdf("Курсовая.pdf"), null, requestAs(7777L)).getStatusCode().value())
                .isEqualTo(403);
        assertThat(controller.uploadFile(pdf("Курсовая.pdf"), null, new MockHttpServletRequest()).getStatusCode().value())
                .isEqualTo(401);
        ResponseEntity<?> toStudent = controller.uploadFile(pdf("Курсовая.pdf"), String.valueOf(STUDENT_ID), request());
        assertThat(toStudent.getStatusCode().value()).isEqualTo(400);
        assertThat(body(toStudent)).containsEntry("field", "teacher_id");
        assertThat(controller.uploadFile(pdf("Курсовая.pdf"), "нет-такого", request()).getStatusCode().value()).isEqualTo(400);
        assertThat(storedFiles()).isEmpty();
        verify(submissions, never()).save(any());
    }

    @Test
    void teacherIsChosenByKeyNotByTelegramId() {
        User teacher = users.findById(TEACHER_ID).orElseThrow();
        teacher.setUrlId("p-petrov");
        teacher.setFullName("Петров Пётр Петрович");
        when(users.findByRole("TEACHER")).thenReturn(List.of(teacher));

        ResponseEntity<?> response = controller.uploadFile(pdf("Курсовая.pdf"), "p-petrov", request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Submission saved = savedSubmission();
        assertThat(saved.getTeacher()).isSameAs(teacher);

        // В списке работ студента и в списке преподавателей — ключ, а не Telegram ID.
        when(submissions.findByStudent_TelegramIdOrderByCreatedAtDesc(STUDENT_ID)).thenReturn(List.of(saved));
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) controller.getSubmissions(request()).getBody()).get(0);
        assertThat(item).containsEntry("teacher_id", "p-petrov");
        TeacherController teachers = new TeacherController(users, mock(BsuirApiService.class));
        List<Map<String, Object>> list = teachers.getTeachers(new MockHttpServletRequest()).getBody();
        assertThat(list).singleElement().satisfies(t -> assertThat(t).containsEntry("id", "p-petrov")
                .containsEntry("full_name", "Петров Пётр Петрович").doesNotContainKey("telegram_id"));

        teacher.setUrlId(null);
        String key = com.sapsr.backend.service.TeacherKey.of(teacher, "");
        assertThat(key).startsWith("t-").doesNotContain(String.valueOf(TEACHER_ID));
        assertThat(com.sapsr.backend.service.TeacherKey.find(List.of(teacher), key, "")).contains(teacher);
        assertThat(com.sapsr.backend.service.TeacherKey.of(teacher, "другой секрет")).isNotEqualTo(key);
    }

    @Test
    void meTellsUploadLimit() {
        assertThat(body(controller.getMe(request()))).containsEntry("max_upload_mb", 50);
    }

    @Test
    void registrationCannotGrantAdminRole() {
        ResponseEntity<?> response = controller.register(Map.of("role", "ADMIN", "full_name", "Хакер"), requestAs(7777L));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(users, never()).save(any());
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

        controller.uploadFile(pdf("Курсовая v2.pdf"), String.valueOf(TEACHER_ID), request());

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

        controller.uploadFile(pdf("Новая работа.pdf"), String.valueOf(TEACHER_ID), request());

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

    @Test
    @SuppressWarnings("unchecked")
    void studentListShowsVersionTeacherAndFailureReason() {
        User teacher = users.findById(TEACHER_ID).orElseThrow();
        teacher.setFullName("Гракова Наталья Викторовна");
        Submission v1 = new Submission();
        v1.setId(150);
        v1.setStatus("REJECTED");
        v1.setTeacher(teacher);
        Submission v2 = new Submission();
        v2.setId(153);
        v2.setParentId(150);
        v2.setStatus("FAILED");
        v2.setFailureCode("PDF_ENCRYPTED");
        v2.setWorkType("course_project");
        v2.setTeacher(teacher);
        when(submissions.findByStudent_TelegramIdOrderByCreatedAtDesc(STUDENT_ID)).thenReturn(List.of(v2, v1));

        List<Map<String, Object>> list = (List<Map<String, Object>>) controller.getSubmissions(request()).getBody();

        assertThat(list.get(0)).containsEntry("version", 2).containsEntry("parent_id", 150)
                .containsEntry("failure_code", "PDF_ENCRYPTED").containsEntry("work_type", "course_project")
                .containsEntry("teacher_id", "t-6682604d6424f175").containsEntry("teacher_name", "Гракова Наталья Викторовна");
        assertThat(list.get(1)).containsEntry("version", 1);
    }

    @Test
    void reportIsReadablePdfWithoutDeveloperDetails() throws IOException {
        Submission s = new Submission();
        s.setId(153);
        s.setStatus("REJECTED");
        s.setStudent(users.findById(STUDENT_ID).orElseThrow());
        s.setCreatedAt(LocalDateTime.of(2026, 10, 25, 10, 2));
        s.setFormatErrors("[{\"severity\":\"major\",\"category\":\"format\",\"origin\":\"symbolic\",\"page\":\"2–8\","
                + "\"message\":\"Левое поле 15 мм вместо 30\",\"found\":\"На 7 страницах\",\"fix\":\"Установите поле 30 мм\"}]");
        when(submissions.findById(153)).thenReturn(Optional.of(s));

        byte[] pdf = controller.getReport(153, request()).getBody();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Отчёт о проверке работы").contains("25.10.2026, 10:02")
                    .contains("Левое поле 15 мм вместо 30").contains("Как исправить:");
        }
    }

    private void createController(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(AcademicCalendar.ZONE).toInstant(), AcademicCalendar.ZONE);
        AcademicCalendar calendar = new AcademicCalendar(mock(AcademicSettingRepository.class),
                mock(GroupCourseOverrideRepository.class), procentovki, clock);
        controller = new UploadController(rabbit, submissions, users,
                mock(EmailVerificationService.class), mock(BsuirApiService.class), mock(SapsrTelegramBot.class),
                new LocalFileStorage(storageRoot, Clock.systemDefaultZone()), reviewFiles, calendar, llmModels,
                docxConverter);
    }

    private ResponseEntity<?> upload(String fileName) {
        return controller.uploadFile(pdf(fileName), null, request());
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
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
