package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TeacherDashboardControllerTest {

    private static final long TEACHER_ID = 2002L;
    private static final long STUDENT_ID = 1001L;

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SapsrTelegramBot bot = mock(SapsrTelegramBot.class);
    private final ReviewFileService reviewFiles = mock(ReviewFileService.class);
    private TeacherDashboardController controller;
    private Submission work;

    @BeforeEach
    void setUp() {
        controller = new TeacherDashboardController(submissions, users, bot, mock(FileStorage.class), reviewFiles);
        User teacher = new User();
        teacher.setTelegramId(TEACHER_ID);
        teacher.setRole("TEACHER");
        teacher.setFullName("Гракова Наталья Викторовна");
        User student = new User();
        student.setTelegramId(STUDENT_ID);
        student.setRole("STUDENT");
        student.setFullName("Терешкова А.В. (гр. 321702)");
        when(users.findById(TEACHER_ID)).thenReturn(Optional.of(teacher));

        work = new Submission();
        work.setId(153);
        work.setStatus("REJECTED");
        work.setTeacher(teacher);
        work.setStudent(student);
        work.setOriginalFileName("Курсовая.pdf");
        when(submissions.findById(153)).thenReturn(Optional.of(work));
        when(reviewFiles.find(153)).thenReturn(Optional.empty());
    }

    @Test
    @Tag("typical")
    void queueShowsVersionAndDeadlineDateOfFirstVersion() {
        LocalDateTime first = LocalDateTime.of(2026, 10, 20, 9, 15);
        Submission v1 = new Submission();
        v1.setId(150);
        work.setParentId(150);
        work.setFirstSubmittedAt(first);
        work.setCreatedAt(LocalDateTime.of(2026, 10, 21, 12, 0));
        when(submissions.findById(150)).thenReturn(Optional.of(v1));
        when(submissions.findPendingForTeacher(TEACHER_ID)).thenReturn(List.of(work));
        when(reviewFiles.countAnnotations(153)).thenReturn(3L);

        ResponseEntity<?> response = controller.getSubmissions(teacher());

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) response.getBody()).get(0);
        assertThat(item).containsEntry("version", 2)
                .containsEntry("first_submitted_at", first.toString())
                .containsEntry("created_at", "2026-10-21T12:00")
                .containsEntry("status", "REJECTED")
                .containsEntry("review_annotations", 3L);
    }

    @Test
    @Tag("negative")
    void revisionWithoutCommentAndWithoutAnnotationsIsRejected() {
        ResponseEntity<?> response = controller.setVerdict(153, Map.of("verdict", "REVISION"), teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body(response)).containsEntry("code", "COMMENT_REQUIRED");
        verify(submissions, never()).save(any());
    }

    @Test
    @Tag("typical")
    void revisionWithAnnotatedFileNeedsNoText() {
        ReviewFile file = new ReviewFile();
        file.setId(7);
        when(reviewFiles.find(153)).thenReturn(Optional.of(file));
        when(reviewFiles.countAnnotations(153)).thenReturn(4L);
        Annotation highlight = new Annotation();
        highlight.setId(31);
        highlight.setPage(3);
        highlight.setKind("HIGHLIGHT");
        highlight.setQuotedText("Система принимает файлы в форматах DOCX и PDF");
        highlight.setComment("Сведите к одному варианту");
        when(reviewFiles.annotationsOf(file)).thenReturn(List.of(highlight));
        Map<String, Object> body = new HashMap<>();
        body.put("verdict", "REVISION");
        body.put("review_file_id", 7);

        ResponseEntity<?> response = controller.setVerdict(153, body, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(work.getTeacherVerdict()).isEqualTo("REVISION");
        assertThat(work.getTeacherComment()).isNull();
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot).notifyUser(eq(STUDENT_ID), text.capture());
        assertThat(text.getValue()).contains("Работа отправлена на доработку")
                .contains("1. Стр. 3 — «Система принимает файлы в форматах DOCX…»\nСведите к одному варианту");
    }

    @Test
    @Tag("negative")
    void reviewFileOfAnotherWorkIsRejected() {
        Map<String, Object> body = Map.of("verdict", "APPROVED", "review_file_id", 99);

        ResponseEntity<?> response = controller.setVerdict(153, body, teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body(response)).containsEntry("field", "review_file_id");
    }

    @Test
    @Tag("negative")
    void secondVerdictIsRejected() {
        work.setTeacherVerdict("APPROVED");

        ResponseEntity<?> response = controller.setVerdict(153, Map.of("verdict", "REVISION", "comment", "ещё"), teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response)).containsEntry("code", "VERDICT_ALREADY_SET");
        verify(bot, never()).notifyUser(anyLong(), anyString());
    }

    @Test
    @Tag("typical")
    void rejectedByFormatWorkCanBeApproved() {
        // Решение 9: отклонённая автоматом работа у преподавателя; решение за ним.
        ResponseEntity<?> response = controller.setVerdict(153, Map.of("verdict", "APPROVED", "comment", "Засчитано"), teacher());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(work.getTeacherVerdict()).isEqualTo("APPROVED");
        assertThat(work.getTeacherComment()).isEqualTo("Засчитано");
    }

    private static MockHttpServletRequest teacher() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("telegram_id", TEACHER_ID);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
