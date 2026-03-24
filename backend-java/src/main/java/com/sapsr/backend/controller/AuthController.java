package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Teacher;
import com.sapsr.backend.repository.TeacherRepository;
import com.sapsr.backend.service.VerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final TeacherRepository teacherRepository;
    private final VerificationService verificationService;

    public AuthController(TeacherRepository teacherRepository,
                          VerificationService verificationService) {
        this.teacherRepository = teacherRepository;
        this.verificationService = verificationService;
    }

    @PostMapping("/send-code")
    public ResponseEntity<?> sendCode(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim().toLowerCase();

        if (!email.endsWith("@bsuir.by")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Допускается только почта @bsuir.by"));
        }

        Optional<Teacher> teacher = teacherRepository.findByEmail(email);
        if (teacher.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Преподаватель с такой почтой не найден в системе. Обратитесь к администратору."
            ));
        }

        if (teacher.get().getTelegramId() != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Этот аккаунт уже привязан к другому Telegram-пользователю"
            ));
        }

        String devCode = verificationService.generateAndSend(email);

        var response = new java.util.HashMap<String, Object>();
        response.put("status", "OK");
        response.put("message", "Код отправлен на " + email);
        response.put("teacher_name", teacher.get().getFullName());
        if (devCode != null) {
            response.put("dev_code", devCode);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-code")
    public ResponseEntity<?> verifyCode(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }

        String email = body.getOrDefault("email", "").trim().toLowerCase();
        String code = body.getOrDefault("code", "").trim();

        if (!verificationService.verify(email, code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Неверный или просроченный код"));
        }

        Optional<Teacher> opt = teacherRepository.findByEmail(email);
        if (opt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Преподаватель не найден"));
        }

        Teacher teacher = opt.get();
        if (teacher.getTelegramId() != null && !teacher.getTelegramId().equals(telegramId)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Этот аккаунт уже привязан к другому Telegram-пользователю"
            ));
        }

        teacher.setTelegramId(telegramId);
        teacherRepository.save(teacher);

        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "role", "TEACHER",
                "full_name", teacher.getFullName(),
                "email", teacher.getEmail()
        ));
    }
}
