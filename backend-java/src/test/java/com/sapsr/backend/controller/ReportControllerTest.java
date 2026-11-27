package com.sapsr.backend.controller;

import com.sapsr.backend.entity.FindingFeedback;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.report.ReportComparisonTest;
import com.sapsr.backend.repository.FindingFeedbackRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportControllerTest {

    private static final long TEACHER_ID = 2002L, OTHER_TEACHER_ID = 2003L, STUDENT_ID = 1001L, ADMIN_ID = 9001L;

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final FindingFeedbackRepository feedback = mock(FindingFeedbackRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ReportController controller = new ReportController(submissions, feedback, users);
    private Submission work;

    @BeforeEach
    void setUp() {
        work = new Submission();
        work.setId(153);
        work.setTeacher(user(TEACHER_ID, "TEACHER"));
        work.setStudent(user(STUDENT_ID, "STUDENT"));
        work.setReport(ReportComparisonTest.report("FMT-MARGIN-LEFT", "CNT-CONSIST-002"));
        when(submissions.findById(153)).thenReturn(Optional.of(work));
        when(feedback.findBySubmissionIdAndFindingId(any(), any())).thenReturn(Optional.empty());
        when(feedback.save(any(FindingFeedback.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @Tag("typical")
    void teacherGetsReportWithFeedbackAndComparison() {
        Submission previous = new Submission();
        previous.setId(150);
        previous.setReport(ReportComparisonTest.report("FMT-MARGIN-LEFT", "LST-SIMPLE"));
        work.setParentId(150);
        when(submissions.findById(150)).thenReturn(Optional.of(previous));
        FindingFeedback mark = new FindingFeedback();
        mark.setFindingId("f-002");
        mark.setVerdict("incorrect");
        when(feedback.findBySubmissionId(153)).thenReturn(List.of(mark));

        Map<String, Object> body = body(controller.getReport(153, as(TEACHER_ID)));

        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) body.get("report");
        assertThat((List<?>) report.get("findings")).hasSize(2);
        // Ответ уходит через Jackson 3 (Spring Boot 4): узел Jackson 2 превратился бы в набор служебных полей.
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body);
        assertThat(json).contains("\"requirement_id\":\"CNT-CONSIST-002\"").doesNotContain("nodeType");
        assertThat(body).containsEntry("feedback", Map.of("f-002", "incorrect"));
        @SuppressWarnings("unchecked")
        Map<String, Object> comparison = (Map<String, Object>) body.get("comparison");
        assertThat(comparison).containsEntry("previous_submission_id", 150);
        assertThat((List<?>) comparison.get("fixed")).hasSize(1);
        assertThat((List<?>) comparison.get("new")).hasSize(1);
    }

    @Test
    @Tag("typical")
    void studentSeesReportButNotFeedback() {
        Map<String, Object> body = body(controller.getReport(153, as(STUDENT_ID)));

        assertThat(body.get("report")).isNotNull();
        assertThat(body).doesNotContainKey("feedback").containsEntry("comparison", null);
        verify(feedback, never()).findBySubmissionId(any());
    }

    @Test
    @Tag("boundary")
    void workWithoutV2ReportGivesNull() {
        work.setReport(null);
        assertThat(body(controller.getReport(153, as(STUDENT_ID)))).containsEntry("report", null);
    }

    @Test
    @Tag("negative")
    void strangersGetForbidden() {
        assertThat(controller.getReport(153, as(OTHER_TEACHER_ID)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.getReport(153, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.getReport(999, as(TEACHER_ID)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Tag("typical")
    void feedbackTakesRequirementAndOriginFromReport() {
        ResponseEntity<?> response = controller.putFeedback(153, "f-002",
                Map.of("verdict", "incorrect", "origin", "symbolic", "requirement_id", "ПОДМЕНА"), as(TEACHER_ID));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<FindingFeedback> saved = ArgumentCaptor.forClass(FindingFeedback.class);
        verify(feedback).save(saved.capture());
        assertThat(saved.getValue().getRequirementId()).isEqualTo("CNT-CONSIST-002");
        assertThat(saved.getValue().getOrigin()).isEqualTo("hybrid");
        assertThat(saved.getValue().getVerdict()).isEqualTo("incorrect");
        assertThat(saved.getValue().getTeacherTelegramId()).isEqualTo(TEACHER_ID);
        assertThat(saved.getValue().getCreatedAt()).isNotNull();
        assertThat(body(response)).containsEntry("verdict", "incorrect").containsEntry("origin", "hybrid");
    }

    @Test
    @Tag("typical")
    void repeatedFeedbackReplacesMark() {
        FindingFeedback existing = new FindingFeedback();
        existing.setVerdict("correct");
        when(feedback.findBySubmissionIdAndFindingId(153, "f-001")).thenReturn(Optional.of(existing));

        controller.putFeedback(153, "f-001", Map.of("verdict", "incorrect"), as(TEACHER_ID));

        verify(feedback).save(existing);
        assertThat(existing.getVerdict()).isEqualTo("incorrect");
    }

    @Test
    @Tag("negative")
    void feedbackIsOnlyForTeacherOfTheWork() {
        assertThat(controller.putFeedback(153, "f-001", Map.of("verdict", "correct"), as(STUDENT_ID))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.putFeedback(153, "f-001", Map.of("verdict", "correct"), as(OTHER_TEACHER_ID))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteFeedback(153, "f-001", as(STUDENT_ID)).getStatusCode().value()).isEqualTo(403);
        verify(feedback, never()).save(any());
    }

    @Test
    @Tag("negative")
    void unknownFindingAndBadVerdict() {
        ResponseEntity<?> missing = controller.putFeedback(153, "f-999", Map.of("verdict", "correct"), as(TEACHER_ID));
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<?> bad = controller.putFeedback(153, "f-001", Map.of("verdict", "может быть"), as(TEACHER_ID));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(body(bad)).containsEntry("field", "verdict");
        verify(feedback, never()).save(any());
    }

    @Test
    @Tag("typical")
    void deleteRemovesMark() {
        FindingFeedback existing = new FindingFeedback();
        when(feedback.findBySubmissionIdAndFindingId(153, "f-001")).thenReturn(Optional.of(existing));

        assertThat(controller.deleteFeedback(153, "f-001", as(TEACHER_ID)).getStatusCode().value()).isEqualTo(204);
        verify(feedback).delete(existing);
    }

    @Test
    @Tag("typical")
    void statsAreForAdminOnly() {
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, "ADMIN")));
        when(users.findById(TEACHER_ID)).thenReturn(Optional.of(user(TEACHER_ID, "TEACHER")));
        when(feedback.countByOriginCategoryAndVerdict()).thenReturn(List.of(
                new Object[] {"hybrid", "content", "correct", 3L},
                new Object[] {"hybrid", "content", "incorrect", 1L},
                new Object[] {"symbolic", "format", "correct", 5L}));

        assertThat(controller.feedbackStats(as(TEACHER_ID)).getStatusCode().value()).isEqualTo(403);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stats = (List<Map<String, Object>>) body(controller.feedbackStats(as(ADMIN_ID))).get("stats");
        assertThat(stats).hasSize(2);
        assertThat(stats.get(0)).containsEntry("origin", "hybrid").containsEntry("category", "content")
                .containsEntry("correct", 3L).containsEntry("incorrect", 1L);
        assertThat(stats.get(1)).containsEntry("correct", 5L).containsEntry("incorrect", 0L);
    }

    private static User user(long id, String role) {
        User user = new User();
        user.setTelegramId(id);
        user.setRole(role);
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