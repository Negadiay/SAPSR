package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private final String UPLOAD_DIR = "../storage/";

    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate,
                            SubmissionRepository submissionRepository,
                            UserRepository userRepository,
                            EmailVerificationService emailVerificationService) {
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }
        Optional<User> user = userRepository.findById(telegramId);
        if (user.isPresent()) {
            User u = user.get();
            return ResponseEntity.ok(Map.of(
                    "telegram_id", u.getTelegramId(),
                    "role", u.getRole(),
                    "full_name", u.getFullName() != null ? u.getFullName() : ""
            ));
        }
        return ResponseEntity.ok(Map.of("telegram_id", telegramId, "role", "NONE"));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }

        String role = body.getOrDefault("role", "").toUpperCase();
        String fullName = body.getOrDefault("full_name", "").trim();

        if (!"STUDENT".equals(role) && !"TEACHER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Роль должна быть STUDENT или TEACHER"));
        }
        if (fullName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Заполните ФИО / данные"));
        }

        String email = null;
        if ("TEACHER".equals(role)) {
            email = body.getOrDefault("email", "").trim().toLowerCase();
            String code = body.getOrDefault("code", "").trim();

            if (email.isEmpty() || code.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Для преподавателя требуются email и код подтверждения"));
            }
            if (!emailVerificationService.isAllowedDomain(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Требуется почта @bsuir.by"));
            }
            if (!emailVerificationService.verifyCode(email, code)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Неверный или просроченный код подтверждения"));
            }
        }

        Optional<User> existing = userRepository.findById(telegramId);
        User user = existing.orElseGet(User::new);
        user.setTelegramId(telegramId);
        user.setRole(role);
        user.setFullName(fullName);
        if (email != null) user.setEmail(email);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "role", role,
                "full_name", fullName
        ));
    }

    @GetMapping("/submissions")
    public ResponseEntity<?> getSubmissions(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }

        List<Submission> submissions = submissionRepository.findByStudent_TelegramIdOrderByCreatedAtDesc(telegramId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("status", s.getStatus());
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            item.put("format_errors", s.getFormatErrors());
            item.put("score", s.getScore());
            item.put("teacher_verdict", s.getTeacherVerdict());
            item.put("teacher_comment", s.getTeacherComment());

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

    @GetMapping("/submissions/{id}/report")
    public ResponseEntity<byte[]> getReport(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        Optional<Submission> opt = submissionRepository.findById(id);

        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Submission s = opt.get();
        if (s.getStudent() != null && telegramId != null
                && !s.getStudent().getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(403).build();
        }

        StringBuilder report = new StringBuilder();
        report.append("SAPSR — Отчёт о проверке форматирования\n");
        report.append("========================================\n\n");
        report.append("Файл: ").append(s.getFilePath()).append("\n");
        report.append("Статус: ").append(s.getStatus()).append("\n");
        report.append("Дата: ").append(s.getCreatedAt()).append("\n\n");

        if (s.getFormatErrors() != null && !s.getFormatErrors().equals("[]")) {
            report.append("Ошибки:\n");
            report.append(s.getFormatErrors()).append("\n");
        } else {
            report.append("Ошибок не обнаружено.\n");
        }

        byte[] content = report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"report_" + id + ".txt\"")
                .body(content);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "teacher_id", required = false) Long teacherId,
                                        HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой!"));
        }

        try {
            File directory = new File(UPLOAD_DIR);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR + fileName);
            Files.write(filePath, file.getBytes());

            Submission submission = new Submission();
            submission.setFilePath(filePath.toAbsolutePath().toString());
            submission.setStatus("PROCESSING");

            Long telegramId = (Long) request.getAttribute("telegram_id");
            if (telegramId != null) {
                Optional<User> student = userRepository.findById(telegramId);
                student.ifPresent(submission::setStudent);
            }

            if (teacherId != null) {
                Optional<User> teacher = userRepository.findById(teacherId);
                teacher.ifPresent(submission::setTeacher);
            }

            Submission savedSubmission = submissionRepository.save(submission);

            String jsonMessage = String.format(
                    "{\"task_id\": %d, \"file_path\": \"%s\", \"status\": \"PROCESSING\"}",
                    savedSubmission.getId(),
                    filePath.toAbsolutePath().toString().replace("\\", "/"));

            rabbitTemplate.convertAndSend(tasksQueue, jsonMessage);
            System.out.println("Отправлено задание Питону: " + jsonMessage);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Файл успешно загружен в систему SAPSR!",
                    "db_id", savedSubmission.getId()
            ));

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Ошибка при сохранении файла"));
        }
    }
}