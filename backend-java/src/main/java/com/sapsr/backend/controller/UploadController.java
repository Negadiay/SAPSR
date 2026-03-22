package com.sapsr.backend.controller;

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
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private final String UPLOAD_DIR = "../storage/";
    private final RabbitTemplate rabbitTemplate;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("currentTelegramId") Long telegramId) { // Получаем ID из Интерцептора

        // 1. Проверяем, что файл не пустой
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой!"));
        }

        try {
            // 2. Создаем директорию, если её нет
            File directory = new File(UPLOAD_DIR);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // 3. Формируем имя и сохраняем файл
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR + fileName);
            Files.write(filePath, file.getBytes());

            // 4. Отправляем задание в RabbitMQ (Питону)
            // Добавляем user_id в JSON, чтобы Питон знал, чей это файл
            String jsonMessage = String.format(
                    "{\"user_id\": %d, \"file_path\": \"%s\", \"status\": \"PROCESSING\"}",
                    telegramId, filePath.toAbsolutePath()
            );

            rabbitTemplate.convertAndSend(tasksQueue, jsonMessage);
            System.out.println("Пользователь " + telegramId + " загрузил файл. Задание отправлено в очередь.");

            // 5. Возвращаем успешный ответ
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Файл успешно загружен в систему SAPSR!",
                    "user_id", telegramId,
                    "file_name", fileName
            ));

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Ошибка при сохранении файла на сервере"));
        }
    }
}