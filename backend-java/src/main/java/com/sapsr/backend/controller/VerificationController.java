package com.sapsr.backend.controller;

import com.sapsr.backend.service.EmailVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/register")
public class VerificationController {

    private final EmailVerificationService emailVerificationService;

    public VerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/send-code")
    public ResponseEntity<?> sendCode(@RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim().toLowerCase();

        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Укажите email"));
        }

        if (!emailVerificationService.isAllowedDomain(email)) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Для регистрации преподавателя требуется почта @bsuir.by")
            );
        }

        try {
            emailVerificationService.sendVerificationCode(email);
            return ResponseEntity.ok(Map.of("status", "OK", "message", "Код отправлен на " + email));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка отправки письма: " + e.getMessage()));
        }
    }
}
