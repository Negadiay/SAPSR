package com.sapsr.backend.controller;

import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.service.BsuirApiService;
import com.sapsr.backend.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1/register")
public class VerificationController {

    private final EmailVerificationService emailVerificationService;
    private final UserRepository userRepository;
    private final BsuirApiService bsuirApiService;

    public VerificationController(EmailVerificationService emailVerificationService,
                                  UserRepository userRepository,
                                  BsuirApiService bsuirApiService) {
        this.emailVerificationService = emailVerificationService;
        this.userRepository = userRepository;
        this.bsuirApiService = bsuirApiService;
    }

    @PostMapping("/send-code")
    public ResponseEntity<?> sendCode(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }

        String email = body.getOrDefault("email", "").trim().toLowerCase();

        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Укажите email"));
        }

        if (!emailVerificationService.isAllowedDomain(email)) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Для регистрации преподавателя требуется почта @bsuir.by")
            );
        }

        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Эта почта уже зарегистрирована в системе"));
        }

        try {
            Optional<BsuirApiService.TeacherInfo> info = bsuirApiService.findTeacherByEmail(email);
            if (info.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Почта " + email + " не найдена среди преподавателей IIS БГУИР"));
            }
        } catch (BsuirApiService.IisUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of("error",
                    "Сервис IIS БГУИР временно недоступен. Попробуйте позже."));
        }

        try {
            emailVerificationService.sendVerificationCode(email);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "message", "Код отправлен на " + email,
                    "expires_in_seconds", EmailVerificationService.CODE_TTL_SECONDS
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ошибка отправки письма: " + e.getMessage()));
        }
    }
}
