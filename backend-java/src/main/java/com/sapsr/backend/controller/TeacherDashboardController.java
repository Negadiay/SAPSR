package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/teacher")
public class TeacherDashboardController {

    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final SapsrTelegramBot bot;

    public TeacherDashboardController(SubmissionRepository submissionRepository,
                                      UserRepository userRepository,
                                      SapsrTelegramBot bot) {
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.bot = bot;
    }

    @GetMapping("/submissions")
    public ResponseEntity<?> getSubmissions(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));

        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "Доступ запрещён"));
        }

        List<Submission> submissions = submissionRepository
                .findByTeacher_TelegramIdAndStatusAndTeacherVerdictIsNullOrderByCreatedAtDesc(telegramId, "SUCCESS");

        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            item.put("format_errors", s.getFormatErrors());
            if (s.getStudent() != null) item.put("student_name", s.getStudent().getFullName());

            String fp = s.getFilePath();
            if (fp != null) {
                String name = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;
                name = name.contains("\\") ? name.substring(name.lastIndexOf('\\') + 1) : name;
                if (name.matches("^\\d+_.*")) name = name.substring(name.indexOf('_') + 1);
                item.put("file_name", name);
            } else {
                item.put("file_name", "file.pdf");
            }
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/submissions/{id}/verdict")
    public ResponseEntity<?> setVerdict(@PathVariable Integer id,
                                        @RequestBody Map<String, String> body,
                                        HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));

        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "Доступ запрещён"));
        }

        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission submission = opt.get();
        if (submission.getTeacher() == null || !submission.getTeacher().getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Эта работа не назначена вам"));
        }

        String verdict = body.getOrDefault("verdict", "").toUpperCase();
        if (!"APPROVED".equals(verdict) && !"REVISION".equals(verdict)) {
            return ResponseEntity.badRequest().body(Map.of("error", "verdict должен быть APPROVED или REVISION"));
        }

        String comment = body.getOrDefault("comment", "").trim();
        if ("REVISION".equals(verdict) && comment.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "При отправке на доработку укажите комментарий"));
        }

        submission.setTeacherVerdict(verdict);
        submission.setTeacherComment(comment.isEmpty() ? null : comment);
        submissionRepository.save(submission);

        if (submission.getStudent() != null) {
            String teacherName = teacher.get().getFullName() != null ? teacher.get().getFullName() : "Преподаватель";
            String fileName = submissionFileName(submission);
            String text = "APPROVED".equals(verdict)
                    ? "✅ Преподаватель " + teacherName + " принял вашу работу!\n\nРабота: " + fileName
                    : "🔄 Преподаватель " + teacherName + " отправил работу на доработку.\n\nРабота: " + fileName + "\nКомментарий: " + comment;
            bot.notifyUser(submission.getStudent().getTelegramId(), text);
        }

        return ResponseEntity.ok(Map.of("status", "OK", "verdict", verdict));
    }

    @GetMapping("/history")
    public ResponseEntity<?> getHistory(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));

        Optional<User> teacher = userRepository.findById(telegramId);
        if (teacher.isEmpty() || !"TEACHER".equals(teacher.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "Доступ запрещён"));
        }

        List<Submission> submissions = submissionRepository
                .findByTeacher_TelegramIdAndTeacherVerdictIsNotNullOrderByCreatedAtDesc(telegramId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            item.put("teacher_verdict", s.getTeacherVerdict());
            item.put("teacher_comment", s.getTeacherComment());
            if (s.getStudent() != null) item.put("student_name", s.getStudent().getFullName());
            item.put("file_name", submissionFileName(s));
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

        try {
            File file = new File(submission.getFilePath());
            if (!file.exists()) return ResponseEntity.notFound().build();

            byte[] content = Files.readAllBytes(file.toPath());
            String fileName = file.getName();
            if (fileName.matches("^\\d+_.*")) fileName = fileName.substring(fileName.indexOf('_') + 1);

            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private String submissionFileName(Submission submission) {
        String fp = submission.getFilePath();
        if (fp == null || fp.isBlank()) return "file.pdf";
        String name = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;
        name = name.contains("\\") ? name.substring(name.lastIndexOf('\\') + 1) : name;
        if (name.matches("^\\d+_.*")) name = name.substring(name.indexOf('_') + 1);
        return name;
    }
}
