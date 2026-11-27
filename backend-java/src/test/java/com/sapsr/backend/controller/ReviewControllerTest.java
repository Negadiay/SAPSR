package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReviewControllerTest {

    private static final long TEACHER_ID = 2002L;
    private static final long OTHER_TEACHER_ID = 2003L;
    private static final long STUDENT_ID = 1001L;
    private static final MockMultipartFile PDF =
            new MockMultipartFile("file", "Курсовая.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ReviewFileService reviewFiles = mock(ReviewFileService.class);
    private ReviewController controller;
    private Submission work;

    @BeforeEach
    void setUp() {
        controller = new ReviewController(submissions, users, reviewFiles);
        User teacher = user(TEACHER_ID, "TEACHER");
        user(OTHER_TEACHER_ID, "TEACHER");
        User student = user(STUDENT_ID, "STUDENT");
        work = new Submission();
        work.setId(153);
        work.setTeacher(teacher);
        work.setStudent(student);
        work.setOriginalFileName("Курсовая.pdf");
        when(submissions.findById(153)).thenReturn(Optional.of(work));
        when(reviewFiles.find(153)).thenReturn(Optional.empty());
    }

    @Test
    @Tag("typical")
    void teacherUploadsAnnotatedFileAndSeesExtractedComments() throws Exception {
        ReviewFile file = new ReviewFile();
        file.setId(7);
        Annotation note = new Annotation();
        note.setId(31);
        note.setPage(4);
        note.setKind("HIGHLIGHT");
        note.setComment("Сверьте с разделом 1.5");
        note.setQuotedText("PDF и DOCX");
        note.setRect("[72.0,700.0,300.0,712.0]");
        when(reviewFiles.attach(any(), any(), anyString())).thenReturn(new ReviewFileService.Attached(
                file, List.of(note), List.of(new ReviewFileService.Warning("DIFFERENT_DOCUMENT", "..."))));

        ResponseEntity<?> response = controller.uploadReviewFile(153, PDF, as(TEACHER_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = body(response);
        assertThat(body).containsEntry("review_file_id", 7);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) body.get("annotations")).get(0);
        assertThat(first).containsEntry("page", 4).containsEntry("kind", "HIGHLIGHT")
                .containsEntry("quoted_text", "PDF и DOCX").containsEntry("has_snapshot", false);
        assertThat((float[]) first.get("rect")).containsExactly(72f, 700f, 300f, 712f);
    }

    @Test
    @Tag("negative")
    void onlyTeacherOfTheWorkCanUploadAndOnlyBeforeVerdict() throws Exception {
        assertThat(controller.uploadReviewFile(153, PDF, as(OTHER_TEACHER_ID)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.uploadReviewFile(153, PDF, as(STUDENT_ID)).getStatusCode().value()).isEqualTo(403);
        work.setTeacherVerdict("REVISION");
        ResponseEntity<?> late = controller.uploadReviewFile(153, PDF, as(TEACHER_ID));
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(body(late)).containsEntry("code", "VERDICT_ALREADY_SET");
        verify(reviewFiles, never()).attach(any(), any(), anyString());
    }

    @Test
    @Tag("negative")
    void notAPdfGivesCode() throws Exception {
        when(reviewFiles.attach(any(), any(), anyString())).thenThrow(new ReviewFileService.NotAPdfException("x", null));

        ResponseEntity<?> response = controller.uploadReviewFile(153, PDF, as(TEACHER_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(body(response)).containsEntry("code", "NOT_A_PDF");
    }

    @Test
    @Tag("typical")
    void studentSeesCommentsOnlyAfterVerdict() {
        ReviewFile file = new ReviewFile();
        file.setId(7);
        file.setUploadedAt(LocalDateTime.of(2026, 10, 21, 15, 0));
        when(reviewFiles.find(153)).thenReturn(Optional.of(file));
        when(reviewFiles.annotationsOf(file)).thenReturn(List.of());

        assertThat(controller.getAnnotations(153, as(STUDENT_ID)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.getAnnotations(153, as(TEACHER_ID)).getStatusCode().value()).isEqualTo(200);
        work.setTeacherVerdict("REVISION");
        ResponseEntity<?> response = controller.getAnnotations(153, as(STUDENT_ID));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response)).containsEntry("review_file_id", 7).containsEntry("uploaded_at", "2026-10-21T15:00");
    }

    @Test
    @Tag("boundary")
    void noReviewFileGivesEmptyListNotError() {
        work.setTeacherVerdict("APPROVED");

        ResponseEntity<?> response = controller.getAnnotations(153, as(STUDENT_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response)).containsEntry("review_file_id", null).containsEntry("annotations", List.of());
        assertThat(controller.downloadReviewFile(153, as(STUDENT_ID)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Tag("typical")
    void downloadNameMarksComments() {
        assertThat(ReviewController.withCommentsSuffix("Курсовая.pdf")).isEqualTo("Курсовая (с комментариями).pdf");
        assertThat(ReviewController.withCommentsSuffix("work.PDF")).isEqualTo("work (с комментариями).pdf");
        assertThat(ReviewController.withCommentsSuffix("без расширения")).isEqualTo("без расширения (с комментариями).pdf");
    }

    private User user(long id, String role) {
        User user = new User();
        user.setTelegramId(id);
        user.setRole(role);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private static MockHttpServletRequest as(long telegramId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("telegram_id", telegramId);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
