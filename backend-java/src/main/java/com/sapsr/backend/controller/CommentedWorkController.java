package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.report.CommentedWork;
import com.sapsr.backend.repository.SubmissionRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Работа с комментариями автопроверки (api-v1.md, раздел 17): тот же PDF, в котором замечания стоят комментариями
 * на своих местах. Собирается по текущему отчёту — после содержательной проверки в нём и её замечания.
 * Скачать могут автор работы и её преподаватель — как PDF-отчёт.
 */
@RestController
@RequestMapping("/api/v1")
public class CommentedWorkController {

    private final SubmissionRepository submissions;
    private final CommentedWork commented;

    public CommentedWorkController(SubmissionRepository submissions, CommentedWork commented) {
        this.submissions = submissions;
        this.commented = commented;
    }

    @GetMapping("/submissions/{id}/commented")
    public ResponseEntity<byte[]> download(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).build();
        Optional<Submission> opt = submissions.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission s = opt.get();
        boolean isStudent = s.getStudent() != null && telegramId.equals(s.getStudent().getTelegramId());
        boolean isTeacher = s.getTeacher() != null && telegramId.equals(s.getTeacher().getTelegramId());
        if (!isStudent && !isTeacher) return ResponseEntity.status(403).build();
        // Пока оформление не проверено, комментировать нечего.
        if (s.getFormatErrors() == null) return ResponseEntity.status(409).build();

        return commented.build(s)
                .map(file -> ResponseEntity.ok()
                        .header("Content-Type", "application/pdf")
                        .header("Content-Disposition", ContentDisposition.attachment()
                                .filename(file.name(), StandardCharsets.UTF_8).build().toString())
                        .body(file.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
