package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
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

/**
 * Контроллер для приема файлов.
 * Выполняет сохранение в файловую систему, создание записи в БД и уведомление очереди.
 */
@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private final String UPLOAD_DIR = "../storage/";
    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository; // Репозиторий для сохранения истории сдач

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate, SubmissionRepository submissionRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestAttribute("currentTelegramId") Long telegramId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой!"));
        }

        try {
            File directory = new File(UPLOAD_DIR);
            if (!directory.exists()) directory.mkdirs();

            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR + fileName);
            Files.write(filePath, file.getBytes());

            // --- ИНТЕГРАЦИЯ С БАЗОЙ ДАННЫХ (Задача Техлида) ---
            Submission submission = new Submission();

            // Создаем "прокси"-объект пользователя для связи в БД по ID
            User student = new User();
            student.setTelegramId(telegramId);

            submission.setStudent(student); // Привязываем работу к студенту
            submission.setFilePath(filePath.toAbsolutePath().toString());
            submission.setStatus("PROCESSING"); // Начальный статус проверки

            // Сохраняем информацию о сдаче в таблицу submissions
            Submission savedSubmission = submissionRepository.save(submission);
            // --------------------------------------------------

            // Отправляем задание в очередь RabbitMQ
            String jsonMessage = String.format(
                    "{\"user_id\": %d, \"submission_id\": %d, \"file_path\": \"%s\", \"status\": \"PROCESSING\"}",
                    telegramId, savedSubmission.getId(), filePath.toAbsolutePath()
            );

            rabbitTemplate.convertAndSend(tasksQueue, jsonMessage);

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "submission_id", savedSubmission.getId(),
                    "message", "Файл успешно принят и зарегистрирован в системе"
            ));

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Ошибка при обработке файла"));
        }
    }
}