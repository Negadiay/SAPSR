package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Student;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.Teacher;
import com.sapsr.backend.repository.StudentRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.TeacherRepository;
import com.sapsr.backend.service.PdfReportService;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private static final String UPLOAD_DIR = "../storage/";

    private static final Pattern STUDENT_FIO_PATTERN =
            Pattern.compile("^[А-ЯЁ][а-яё]+(-[А-ЯЁ][а-яё]+)?\\s+[А-ЯЁ]\\.[А-ЯЁ]\\.$");

    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final PdfReportService pdfReportService;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate,
                            SubmissionRepository submissionRepository,
                            StudentRepository studentRepository,
                            TeacherRepository teacherRepository,
                            PdfReportService pdfReportService) {
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.pdfReportService = pdfReportService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }

        Optional<Student> student = studentRepository.findById(telegramId);
        if (student.isPresent()) {
            Student s = student.get();
            return ResponseEntity.ok(Map.of(
                    "telegram_id", s.getTelegramId(),
                    "role", "STUDENT",
                    "full_name", s.getFullName(),
                    "group_number", s.getGroupNumber()
            ));
        }

        Optional<Teacher> teacher = teacherRepository.findByTelegramId(telegramId);
        if (teacher.isPresent()) {
            Teacher t = teacher.get();
            return ResponseEntity.ok(Map.of(
                    "telegram_id", telegramId,
                    "role", "TEACHER",
                    "full_name", t.getFullName(),
                    "email", t.getEmail()
            ));
        }

        return ResponseEntity.ok(Map.of("telegram_id", telegramId, "role", "NONE"));
    }

    @PostMapping("/register/student")
    public ResponseEntity<?> registerStudent(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));
        }

        String fullName = body.getOrDefault("full_name", "").trim();
        String groupNumber = body.getOrDefault("group_number", "").trim();

        if (!STUDENT_FIO_PATTERN.matcher(fullName).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "ФИО должно быть в формате: Фамилия И.О. (например, Иванов И.И.)"
            ));
        }

        if (!groupNumber.matches("^\\d{6}$")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Номер группы должен содержать 6 цифр"
            ));
        }

        Student student = studentRepository.findById(telegramId).orElseGet(Student::new);
        student.setTelegramId(telegramId);
        student.setFullName(fullName);
        student.setGroupNumber(groupNumber);
        studentRepository.save(student);

        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "role", "STUDENT",
                "full_name", fullName,
                "group_number", groupNumber
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

            if (s.getTeacher() != null) {
                item.put("teacher_name", s.getTeacher().getFullName());
            }

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

        byte[] pdfContent = pdfReportService.generateReport(s);

        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"report_" + id + ".pdf\"")
                .body(pdfContent);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "teacher_id", required = false) Integer teacherId,
                                        HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой!"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Допускаются только PDF-файлы"));
        }

        try {
            File directory = new File(UPLOAD_DIR);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String fileName = System.currentTimeMillis() + "_" + originalFilename;
            Path filePath = Paths.get(UPLOAD_DIR + fileName);
            Files.write(filePath, file.getBytes());

            Submission submission = new Submission();
            submission.setFilePath(filePath.toAbsolutePath().toString());
            submission.setStatus("PROCESSING");

            Long telegramId = (Long) request.getAttribute("telegram_id");
            if (telegramId != null) {
                Optional<Student> student = studentRepository.findById(telegramId);
                student.ifPresent(submission::setStudent);
            }

            if (teacherId != null) {
                Optional<Teacher> teacher = teacherRepository.findById(teacherId);
                teacher.ifPresent(submission::setTeacher);
            }

            Submission savedSubmission = submissionRepository.save(submission);

            String jsonMessage = String.format(
                    "{\"task_id\": %d, \"file_path\": \"%s\", \"status\": \"PROCESSING\"}",
                    savedSubmission.getId(),
                    filePath.toAbsolutePath().toString().replace("\\", "/"));

            rabbitTemplate.convertAndSend(tasksQueue, jsonMessage);

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
