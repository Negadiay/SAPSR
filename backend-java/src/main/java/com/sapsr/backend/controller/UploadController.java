package com.sapsr.backend.controller;

import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.security.TelegramSecurityService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private final String UPLOAD_DIR = "../storage/";
    private final RabbitTemplate rabbitTemplate;
    private final TelegramSecurityService securityService; // Добавлено
    private final UserRepository userRepository; // Добавлено

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate,
                            TelegramSecurityService securityService,
                            UserRepository userRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.securityService = securityService;
        this.userRepository = userRepository;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("Authorization") String initData) { // Получаем данные от фронта

        // 1. Проверяем подлинность данных от Telegram
        if (!securityService.validateTelegramData(initData)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Неверные данные авторизации Telegram"));
        }

        // 2. Извлекаем ID пользователя
        Long telegramId = securityService.extractUserId(initData);
        if (telegramId == null || !userRepository.existsById(telegramId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Пользователь не зарегистрирован в боте!"));
        }

        // 3. Если всё ок — сохраняем файл (твой старый код)
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой!"));
        }

        try {
            File directory = new File(UPLOAD_DIR);
            if (!directory.exists()) directory.mkdirs();

            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR + fileName);
            Files.write(filePath, file.getBytes());

            // Отправляем в RabbitMQ инфо о пользователе и пути к файлу
            String jsonMessage = String.format(
                    "{\"user_id\": %d, \"file_path\": \"%s\", \"status\": \"PROCESSING\"}",
                    telegramId, filePath.toAbsolutePath()
            );

            rabbitTemplate.convertAndSend(tasksQueue, jsonMessage);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Файл принят, проверка запущена",
                    "file_name", fileName
            ));

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Ошибка сервера"));
        }
    }
}