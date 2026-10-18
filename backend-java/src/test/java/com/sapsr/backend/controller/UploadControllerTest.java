package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.messaging.TaskMessage;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
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
import java.util.List;
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

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private UploadController controller;

    @BeforeEach
    void setUp() throws IOException {
        // Корень хранилища — вложенная папка, чтобы проверить, что за его пределы ничего не пишется.
        storageRoot = Files.createDirectory(root.resolve("storage"));
        controller = new UploadController(rabbit, submissions, users,
                mock(EmailVerificationService.class), mock(BsuirApiService.class), mock(SapsrTelegramBot.class),
                new LocalFileStorage(storageRoot, Clock.systemDefaultZone()));
        ReflectionTestUtils.setField(controller, "tasksQueue", "pdf_tasks_queue");

        User student = new User();
        student.setTelegramId(STUDENT_ID);
        student.setRole("STUDENT");
        student.setLastName("Иванов");
        student.setGroupNumber("321702");
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(student));
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
        assertThat(saved.getStatus()).isEqualTo("PROCESSING");

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
        controller.uploadFile(pdf("Курсовая.pdf"), null, "course_work", 7, 2, request());

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
    void rejectsInvalidProfileWithoutStoringAnything() throws IOException {
        ResponseEntity<?> badSemester = controller.uploadFile(pdf("a.pdf"), null, null, 13, null, request());
        ResponseEntity<?> badStage = controller.uploadFile(pdf("a.pdf"), null, null, null, 4, request());
        ResponseEntity<?> badType = controller.uploadFile(pdf("a.pdf"), null, "Курсовая", null, null, request());

        assertThat(List.of(badSemester, badStage, badType))
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
        return controller.uploadFile(pdf(fileName), null, null, null, null, request());
    }

    private static MockMultipartFile pdf(String fileName) {
        return new MockMultipartFile("file", fileName, "application/pdf", PDF);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("telegram_id", STUDENT_ID);
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
