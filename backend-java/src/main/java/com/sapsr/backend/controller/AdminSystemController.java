package com.sapsr.backend.controller;

import com.sapsr.backend.llm.LlmModels;
import com.sapsr.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * «Красная зона» администратора (решение 13): состояние обработчика и модель, которая проверяет работы.
 * Модель выбирается из тех, что есть у обработчика, и действует для новых проверок без перезапуска.
 * Методы — docs/contracts/api-v1.md, раздел 16.
 */
@RestController
@RequestMapping("/api/v1/admin/system")
public class AdminSystemController {

    private final LlmModels models;
    private final UserRepository users;

    public AdminSystemController(LlmModels models, UserRepository users) {
        this.models = models;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<?> getSystem(HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = AdminController.deny(request, users);
        return denied.orElseGet(() -> ResponseEntity.ok(models.view()));
    }

    /** {"model": "heavy" | null, "confirm_cloud": true} — null: снова модель по умолчанию. */
    @PutMapping("/model")
    public ResponseEntity<?> setModel(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = AdminController.deny(request, users);
        if (denied.isPresent()) return denied.get();
        Object model = body.get("model");
        try {
            models.select(model == null ? null : String.valueOf(model), Boolean.TRUE.equals(body.get("confirm_cloud")),
                    (Long) request.getAttribute("telegram_id"));
        } catch (LlmModels.Refused e) {
            int status = "UNKNOWN_MODEL".equals(e.code()) ? 400 : 409;
            return ResponseEntity.status(status).body(Map.of("code", e.code(), "error", e.getMessage()));
        }
        return ResponseEntity.ok(models.view());
    }
}
