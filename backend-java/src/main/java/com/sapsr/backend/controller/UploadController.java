package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
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
import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private final String UPLOAD_DIR = "../storage/";

    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate,
                            SubmissionRepository submissionRepository,
                            UserRepository userRepository) {
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
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