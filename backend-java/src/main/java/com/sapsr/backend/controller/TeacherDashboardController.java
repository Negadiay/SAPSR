package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.bot.AppLink;
import com.sapsr.backend.bot.BotMessages;
import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.entity.WorkFileName;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.storage.FileStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/teacher")
public class TeacherDashboardController {

    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final SapsrTelegramBot bot;
    private final FileStorage fileStorage;
    private final ReviewFileService reviewFiles;

    public TeacherDashboardController(SubmissionRepository submissionRepository,
                                      UserRepository userRepository,
                                      SapsrTelegramBot bot,
                                      FileStorage fileStorage,
                                      ReviewFileService reviewFiles) {
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.bot = bot;
        this.reviewFiles = reviewFiles;
        this.fileStorage = fileStorage;
    }

    @GetMapping("/submissions")
    public ResponseEntity<?> getSubmissions(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Откройте приложение из Telegram."));

        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "Раздел только для преподавателя."));
        }

        List<Submission> submissions = submissionRepository.findPendingForTeacher(telegramId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            // Срок сдачи засчитывается по приходу первой версии (решение 9).
            LocalDateTime deadlineDate = s.submittedForDeadline();
            item.put("first_submitted_at", deadlineDate != null ? deadlineDate.toString() : "");
            item.put("version", versionOf(s));
            item.put("status", normalizeDbCode(s.getStatus()));
            // Отклонённая работа видна сразу после фазы format (решение 9): PENDING — содержание ещё проверяется.
            item.put("content_status", s.getContentStatus());
            item.put("format_errors", s.getFormatErrors());
            putStudent(item, s);
            item.put("file_name", s.displayFileName());
            item.put("review_annotations", reviewFiles.countAnnotations(s.getId()));
            item.put("has_review_file", reviewFiles.find(s.getId()).isPresent());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /** ФИО без «(гр. …)» и группа отдельным полем. */
    private static void putStudent(Map<String, Object> item, Submission s) {
        if (s.getStudent() == null) return;
        item.put("student_name", s.getStudent().displayName());
        item.put("student_group", s.getStudent().getGroupNumber());
    }

    /** Номер версии работы: 1 — первая загрузка, дальше по цепочке parent_id. */
    private int versionOf(Submission s) {
        int version = 1;
        Integer parentId = s.getParentId();
        Set<Integer> seen = new HashSet<>();
        while (parentId != null && seen.add(parentId)) {
            version++;
            parentId = submissionRepository.findById(parentId).map(Submission::getParentId).orElse(null);
        }
        return version;
    }

    @PostMapping("/submissions/{id}/verdict")
    public ResponseEntity<?> setVerdict(@PathVariable Integer id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Откройте приложение из Telegram."));

        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "Раздел только для преподавателя."));
        }

        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission submission = opt.get();
        if (submission.getTeacher() == null || !submission.getTeacher().getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Это работа другого преподавателя."));
        }

        if (submission.getTeacherVerdict() != null && !submission.getTeacherVerdict().isBlank()) {
            return ResponseEntity.status(409).body(Map.of("code", "VERDICT_ALREADY_SET",
                    "error", "Решение по работе уже принято."));
        }

        String verdict = String.valueOf(body.getOrDefault("verdict", "")).toUpperCase(Locale.ROOT);
        if (!"APPROVED".equals(verdict) && !"REVISION".equals(verdict)) {
            return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "field", "verdict",
                    "error", "Неизвестное решение."));
        }

        Object rawComment = body.get("comment");
        String comment = rawComment == null ? "" : String.valueOf(rawComment).trim();

        // Размеченный файл у работы один; если интерфейс передал его номер, он должен совпадать.
        Optional<ReviewFile> reviewFile = reviewFiles.find(submission.getId());
        Object reviewFileId = body.get("review_file_id");
        if (reviewFileId != null && (reviewFile.isEmpty()
                || !String.valueOf(reviewFileId).equals(String.valueOf(reviewFile.get().getId())))) {
            return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "field", "review_file_id",
                    "error", "Файл с пометками — от другой работы. Загрузите его заново."));
        }
        long annotationCount = reviewFiles.countAnnotations(submission.getId());

        // На доработку — с текстом или хотя бы с одним комментарием в размеченном файле (api-v1.md, раздел 9).
        if ("REVISION".equals(verdict) && comment.isEmpty() && annotationCount == 0) {
            return ResponseEntity.badRequest().body(Map.of("code", "COMMENT_REQUIRED",
                    "error", "Для доработки загрузите файл с пометками."));
        }

        submission.setTeacherVerdict(verdict);
        submission.setTeacherComment(comment.isEmpty() ? null : comment);
        submissionRepository.save(submission);

        if (submission.getStudent() != null) {
            String teacherName = teacher.get().getFullName() != null ? teacher.get().getFullName() : "Преподаватель";
            // Комментарии из файла — прямо в сообщении: студент видит их сразу, файл понадобится при исправлении.
            List<Annotation> annotations = reviewFile.map(reviewFiles::annotationsOf).orElse(List.of());
            bot.send(submission.getStudent().getTelegramId(), BotMessages.verdictForStudent(
                    teacherName, submission.displayFileName(), "APPROVED".equals(verdict), comment,
                    annotations, reviewFile.isPresent()), List.of(AppLink.work(submission.getId())));
        }

        return ResponseEntity.ok(Map.of("status", "OK", "verdict", verdict));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Откройте приложение из Telegram."));

        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "Раздел только для преподавателя."));
        }

        List<Submission> submissions = submissionRepository.findHistoryForTeacher(telegramId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            item.put("teacher_verdict", normalizeNullableDbCode(s.getTeacherVerdict()));
            item.put("teacher_comment", s.getTeacherComment());
            item.put("version", versionOf(s));
            putStudent(item, s);
            item.put("file_name", s.displayFileName());
            item.put("review_annotations", reviewFiles.countAnnotations(s.getId()));
            item.put("has_review_file", reviewFiles.find(s.getId()).isPresent());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/submissions/{id}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).build();

        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission submission = opt.get();
        if (submission.getTeacher() == null || !submission.getTeacher().getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(403).build();
        }

        if (submission.getStorageKey() == null) return ResponseEntity.notFound().build();

        try {
            Path file = fileStorage.resolve(submission.getStorageKey());
            if (!Files.exists(file)) return ResponseEntity.notFound().build();

            byte[] content = Files.readAllBytes(file);
            // Имя для скачивания — по форме «КР7_321702_Фамилия.pdf», если профиль известен, иначе исходное;
            // кириллица передаётся по RFC 5987.
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(WorkFileName.forDownload(submission), StandardCharsets.UTF_8)
                    .build();

            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", disposition.toString())
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private String normalizeDbCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullableDbCode(String value) {
        String normalized = normalizeDbCode(value);
        return normalized.isBlank() ? null : normalized;
    }
}
