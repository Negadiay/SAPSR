package com.sapsr.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.entity.WorkFileName;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Возврат работы с комментариями преподавателя (docs/contracts/api-v1.md, разделы 8, 10, 11):
 * преподаватель загружает PDF со своими пометками, студент видит комментарии по страницам и скачивает файл.
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final ReviewFileService reviewFiles;
    private final ObjectMapper json = new ObjectMapper();

    public ReviewController(SubmissionRepository submissionRepository, UserRepository userRepository,
                            ReviewFileService reviewFiles) {
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.reviewFiles = reviewFiles;
    }

    @PostMapping("/teacher/submissions/{id}/review-file")
    public ResponseEntity<?> uploadReviewFile(@PathVariable Integer id,
                                              @RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return error(401, "UNAUTHORIZED", "Не авторизован");
        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return error(403, "FORBIDDEN", "Доступ запрещён");
        }
        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return error(404, "NOT_FOUND", "Работа не найдена");
        Submission submission = opt.get();
        if (!isTeacherOf(submission, telegramId)) return error(403, "FORBIDDEN", "Эта работа не назначена вам");
        if (hasVerdict(submission)) return error(409, "VERDICT_ALREADY_SET", "По работе уже вынесен вердикт");
        if (file == null || file.isEmpty()) return error(400, "VALIDATION_ERROR", "Файл пустой");

        try {
            ReviewFileService.Attached attached = reviewFiles.attach(
                    submission, file.getBytes(), Submission.cleanFileName(file.getOriginalFilename()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("review_file_id", attached.file().getId());
            body.put("annotations", attached.annotations().stream().map(this::view).toList());
            body.put("warnings", attached.warnings());
            return ResponseEntity.ok(body);
        } catch (ReviewFileService.NotAPdfException e) {
            return error(400, "NOT_A_PDF", "Файл не открывается как PDF. Сохраните работу с пометками в PDF и загрузите снова.");
        } catch (IOException e) {
            System.err.println("[REVIEW] Не удалось сохранить размеченный файл работы #" + id + ": " + e.getMessage());
            return error(500, "INTERNAL", "Не удалось сохранить файл");
        }
    }

    @GetMapping("/submissions/{id}/annotations")
    public ResponseEntity<?> getAnnotations(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return error(401, "UNAUTHORIZED", "Не авторизован");
        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return error(404, "NOT_FOUND", "Работа не найдена");
        if (!canView(opt.get(), telegramId)) return error(403, "FORBIDDEN", "Нет доступа");

        Map<String, Object> body = new LinkedHashMap<>();
        Optional<ReviewFile> file = reviewFiles.find(id);
        body.put("review_file_id", file.map(ReviewFile::getId).orElse(null));
        body.put("uploaded_at", file.map(f -> f.getUploadedAt().toString()).orElse(null));
        body.put("warnings", file.map(reviewFiles::warningsOf).orElse(List.of()));
        body.put("annotations", file.map(f -> reviewFiles.annotationsOf(f).stream().map(this::view).toList())
                .orElse(List.of()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/submissions/{id}/review-file")
    public ResponseEntity<?> downloadReviewFile(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return error(401, "UNAUTHORIZED", "Не авторизован");
        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return error(404, "NOT_FOUND", "Работа не найдена");
        Submission submission = opt.get();
        if (!canView(submission, telegramId)) return error(403, "FORBIDDEN", "Нет доступа");
        Optional<ReviewFile> file = reviewFiles.find(id);
        if (file.isEmpty()) return error(404, "NOT_FOUND", "Размеченного файла нет");

        try {
            Path path = reviewFiles.pathOf(file.get());
            if (!Files.isRegularFile(path)) return error(404, "NOT_FOUND", "Размеченного файла нет");
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(withCommentsSuffix(WorkFileName.forDownload(submission)), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", disposition.toString())
                    .body(Files.readAllBytes(path));
        } catch (IOException | IllegalArgumentException e) {
            return error(500, "INTERNAL", "Не удалось прочитать файл");
        }
    }

    /** «Курсовая.pdf» → «Курсовая (с комментариями).pdf». */
    static String withCommentsSuffix(String fileName) {
        int dot = fileName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? fileName.length() - 4 : fileName.length();
        return fileName.substring(0, dot) + " (с комментариями).pdf";
    }

    private Map<String, Object> view(Annotation a) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", a.getId());
        item.put("page", a.getPage());
        item.put("kind", a.getKind());
        item.put("author", a.getAuthor());
        item.put("created_at", a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        item.put("comment", a.getComment());
        item.put("quoted_text", a.getQuotedText());
        item.put("rect", parseRect(a.getRect()));
        item.put("reply_to_id", a.getReplyToId());
        item.put("has_snapshot", false);
        return item;
    }

    private float[] parseRect(String rect) {
        try {
            return rect == null ? null : json.readValue(rect, float[].class);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isTeacherOf(Submission s, Long telegramId) {
        return s.getTeacher() != null && telegramId.equals(s.getTeacher().getTelegramId());
    }

    // Студент видит комментарии после вердикта: до него преподаватель может заменить файл.
    private static boolean canView(Submission s, Long telegramId) {
        return isTeacherOf(s, telegramId)
                || (hasVerdict(s) && s.getStudent() != null && telegramId.equals(s.getStudent().getTelegramId()));
    }

    private static boolean hasVerdict(Submission s) {
        return s.getTeacherVerdict() != null && !s.getTeacherVerdict().isBlank();
    }

    // Ошибка с кодом из api-v1.md, раздел 1; поле error — для интерфейса, который ещё читает текст.
    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "error", message, "message", message));
    }
}
