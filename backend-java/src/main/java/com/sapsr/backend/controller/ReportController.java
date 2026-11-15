package com.sapsr.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.FindingFeedback;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.report.ReportComparison;
import com.sapsr.backend.repository.FindingFeedbackRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Отчёт v2 для интерфейса, отметки преподавателя «верно / неверно» и сравнение версий
 * (docs/contracts/api-v1.md, разделы 19–20; решение 17).
 */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final SubmissionRepository submissions;
    private final FindingFeedbackRepository feedback;
    private final UserRepository users;

    public ReportController(SubmissionRepository submissions, FindingFeedbackRepository feedback, UserRepository users) {
        this.submissions = submissions;
        this.feedback = feedback;
        this.users = users;
    }

    /** Отчёт v2 работы: автору и преподавателю работы. Отметки видит только преподаватель. */
    @GetMapping("/submissions/{id}/report-v2")
    public ResponseEntity<?> getReport(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ApiErrors.error(401, "UNAUTHORIZED", UploadController.NOT_IN_TELEGRAM);
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ApiErrors.error(404, "NOT_FOUND", "Работа не найдена. Возможно, её отозвали.");
        Submission s = opt.get();
        boolean teacher = isTeacherOf(s, telegramId);
        boolean student = s.getStudent() != null && s.getStudent().getTelegramId().equals(telegramId);
        if (!teacher && !student) return ApiErrors.error(403, "FORBIDDEN", "Это не ваша работа.");

        Map<String, Object> body = new LinkedHashMap<>();
        // Узел Jackson 2 в Map: Spring Boot 4 сериализует ответы Jackson 3, и узел ушёл бы набором служебных полей.
        JsonNode report = ReportComparison.parseV2(s.getReport());
        body.put("report", report == null ? null : JSON.convertValue(report, Map.class));
        if (teacher) {
            Map<String, String> marks = new LinkedHashMap<>();
            for (FindingFeedback mark : feedback.findBySubmissionId(id)) marks.put(mark.getFindingId(), mark.getVerdict());
            body.put("feedback", marks);
        }
        // Сравнение — с ближайшей предыдущей версией той же работы.
        Submission previous = s.getParentId() == null ? null : submissions.findById(s.getParentId()).orElse(null);
        body.put("comparison", previous == null ? null
                : ReportComparison.compare(previous.getId(), previous.getReport(), s.getReport()));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/teacher/submissions/{id}/findings/{findingId}/feedback")
    public ResponseEntity<?> putFeedback(@PathVariable Integer id, @PathVariable String findingId,
                                         @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        Optional<ResponseEntity<?>> denied = denyTeacher(id, telegramId);
        if (denied.isPresent()) return denied.get();
        String verdict = String.valueOf(body.getOrDefault("verdict", "")).toLowerCase(Locale.ROOT);
        if (!FindingFeedback.CORRECT.equals(verdict) && !FindingFeedback.INCORRECT.equals(verdict)) {
            return ApiErrors.error(400, "VALIDATION_ERROR", "Отметка — «верно» или «неверно».", "verdict");
        }
        // Требование и происхождение замечания — из отчёта на сервере, не из запроса.
        JsonNode finding = finding(submissions.findById(id).orElseThrow(), findingId);
        if (finding == null) return ApiErrors.error(404, "NOT_FOUND", "Такого замечания в отчёте нет.");

        FindingFeedback mark = feedback.findBySubmissionIdAndFindingId(id, findingId).orElseGet(FindingFeedback::new);
        mark.setSubmissionId(id);
        mark.setFindingId(findingId);
        mark.setRequirementId(finding.path("requirement_id").asText("unknown"));
        mark.setOrigin(finding.path("origin").asText("symbolic"));
        mark.setVerdict(verdict);
        mark.setTeacherTelegramId(telegramId);
        mark.setCreatedAt(LocalDateTime.now(AcademicCalendar.ZONE));
        feedback.save(mark);

        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("finding_id", findingId);
        saved.put("requirement_id", mark.getRequirementId());
        saved.put("origin", mark.getOrigin());
        saved.put("verdict", verdict);
        saved.put("created_at", mark.getCreatedAt().toString());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/teacher/submissions/{id}/findings/{findingId}/feedback")
    public ResponseEntity<?> deleteFeedback(@PathVariable Integer id, @PathVariable String findingId,
                                            HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        Optional<ResponseEntity<?>> denied = denyTeacher(id, telegramId);
        if (denied.isPresent()) return denied.get();
        feedback.findBySubmissionIdAndFindingId(id, findingId).ifPresent(feedback::delete);
        return ResponseEntity.noContent().build();
    }

    /** Сколько замечаний отмечено верными и неверными — по происхождению и категории; для оценки качества. */
    @GetMapping("/admin/feedback-stats")
    public ResponseEntity<?> feedbackStats(HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = AdminController.deny(request, users);
        if (denied.isPresent()) return denied.get();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Object[] row : feedback.countByOriginCategoryAndVerdict()) {
            Map<String, Object> item = rows.computeIfAbsent(row[0] + "/" + row[1], key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("origin", row[0]);
                created.put("category", row[1]);
                created.put(FindingFeedback.CORRECT, 0L);
                created.put(FindingFeedback.INCORRECT, 0L);
                return created;
            });
            item.put(String.valueOf(row[2]), ((Number) row[3]).longValue());
        }
        return ResponseEntity.ok(Map.of("stats", new ArrayList<>(rows.values())));
    }

    private Optional<ResponseEntity<?>> denyTeacher(Integer id, Long telegramId) {
        if (telegramId == null) return Optional.of(ApiErrors.error(401, "UNAUTHORIZED", UploadController.NOT_IN_TELEGRAM));
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return Optional.of(ApiErrors.error(404, "NOT_FOUND", "Работа не найдена. Возможно, её отозвали."));
        if (!isTeacherOf(opt.get(), telegramId)) {
            return Optional.of(ApiErrors.error(403, "FORBIDDEN", "Отмечать замечания может только преподаватель этой работы."));
        }
        return Optional.empty();
    }

    private static boolean isTeacherOf(Submission s, Long telegramId) {
        return s.getTeacher() != null && s.getTeacher().getTelegramId().equals(telegramId);
    }

    private static JsonNode finding(Submission s, String findingId) {
        JsonNode report = ReportComparison.parseV2(s.getReport());
        if (report == null) return null;
        for (JsonNode finding : report.path("findings")) {
            if (findingId.equals(finding.path("id").asText())) return finding;
        }
        return null;
    }
}