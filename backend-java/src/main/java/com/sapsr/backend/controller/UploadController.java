package com.sapsr.backend.controller;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.service.BsuirApiService;
import com.sapsr.backend.service.EmailVerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
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

    private final String UPLOAD_DIR = "../storage/";

    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final BsuirApiService bsuirApiService;
    private final SapsrTelegramBot bot;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate,
                            SubmissionRepository submissionRepository,
                            UserRepository userRepository,
                            EmailVerificationService emailVerificationService,
                            BsuirApiService bsuirApiService,
                            SapsrTelegramBot bot) {
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.bsuirApiService = bsuirApiService;
        this.bot = bot;
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

        // Для студента имя обязательно (для преподавателя подтягивается из IIS)
        if ("STUDENT".equals(role) && fullName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Заполните ФИО / данные"));
        }

        if ("STUDENT".equals(role)) {
            if (!fullName.matches("^[А-ЯЁ][а-яё]+\\s[А-ЯЁ]\\.[А-ЯЁ]\\. \\(гр\\. \\d{6}\\)$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Укажите ФИО и группу в формате: Иванов И.И., 123456"));
            }
            Matcher groupMatcher = Pattern.compile("\\(гр\\.\\s*(\\d{6})\\)").matcher(fullName);
            if (!groupMatcher.find()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Укажите номер группы в формате: Иванов И.И., 123456"));
            }
            String groupNumber = groupMatcher.group(1);
            if (!bsuirApiService.groupExists(groupNumber)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Группа " + groupNumber + " не найдена в IIS БГУИР"));
            }
        }

        String email        = null;
        String urlIdToSave  = null;
        if ("TEACHER".equals(role)) {
            email = body.getOrDefault("email", "").trim().toLowerCase();
            String code = body.getOrDefault("code", "").trim();

            if (email.isEmpty() || code.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Для преподавателя требуются email и код подтверждения"));
            }
            if (!emailVerificationService.isAllowedDomain(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Требуется корпоративная почта @bsuir.by"));
            }
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Эта почта уже зарегистрирована в системе"));
            }
            if (!emailVerificationService.verifyCode(email, code)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Неверный или просроченный код подтверждения"));
            }

            // Поиск преподавателя в IIS и автозаполнение ФИО + urlId
            try {
                Optional<BsuirApiService.TeacherInfo> info = bsuirApiService.findTeacherByEmail(email);
                if (info.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "Преподаватель с почтой " + email + " не найден в IIS БГУИР. Проверьте правильность адреса."));
                }
                fullName   = info.get().fio();
                urlIdToSave = info.get().urlId();
            } catch (BsuirApiService.IisUnavailableException e) {
                return ResponseEntity.status(503).body(Map.of("error",
                        "Сервис IIS БГУИР временно недоступен. Попробуйте позже."));
            }
        }

        Optional<User> existing = userRepository.findById(telegramId);
        User user = existing.orElseGet(User::new);
        user.setTelegramId(telegramId);
        user.setRole(role);
        user.setFullName(fullName);
        if (email != null) user.setEmail(email);
        if (urlIdToSave != null) {
            user.setUrlId(urlIdToSave);
            // Сохраняем список групп преподавателя для быстрой фильтрации
            try {
                Set<String> groups = bsuirApiService.fetchTeacherGroups(urlIdToSave);
                if (!groups.isEmpty()) {
                    user.setTeacherGroups(new ObjectMapper().writeValueAsString(groups));
                }
            } catch (Exception e) {
                System.err.println("[REGISTER] Не удалось загрузить группы для " + urlIdToSave + ": " + e.getMessage());
            }
        }
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

    @DeleteMapping("/submissions/{id}")
    public ResponseEntity<?> withdrawSubmission(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", "Не авторизован"));

        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission s = opt.get();
        if (s.getStudent() == null || !s.getStudent().getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Нет доступа"));
        }
        if (!"SUCCESS".equals(s.getStatus()) || s.getTeacherVerdict() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Работу нельзя отозвать на этом этапе"));
        }

        if (s.getTeacher() != null && s.getTeacherMessageId() != null) {
            bot.deleteMessage(s.getTeacher().getTelegramId(), s.getTeacherMessageId());
        }

        submissionRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "OK"));
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

        try {
            byte[] content = buildPdfReport(s);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"report_" + id + ".pdf\"")
                    .body(content);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private byte[] buildPdfReport(Submission s) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 60, 60);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        BaseFont bf    = loadCyrillicFont();
        BaseFont bfMono = bf; // fallback to same font for snippet blocks

        Font fontTitle    = new Font(bf, 16, Font.BOLD);
        Font fontSection  = new Font(bf, 12, Font.BOLD);
        Font fontHeader   = new Font(bf, 11, Font.BOLD);
        Font fontNormal   = new Font(bf, 10, Font.NORMAL);
        Font fontSmall    = new Font(bf, 9,  Font.NORMAL,  new Color(80, 80, 80));
        Font fontSnippet  = new Font(bfMono, 9, Font.NORMAL, new Color(30, 30, 30));
        Font fontLabel    = new Font(bf, 9,  Font.BOLD,   new Color(80, 80, 80));
        Font fontOk       = new Font(bf, 10, Font.NORMAL,  new Color(56, 142, 60));
        Font fontWarn     = new Font(bf, 10, Font.BOLD,    new Color(230, 81, 0));
        Font fontErr      = new Font(bf, 10, Font.BOLD,    new Color(211, 47, 47));
        Font fontCrit     = new Font(bf, 10, Font.BOLD,    new Color(180, 0, 0));
        Font fontPageBadge = new Font(bf, 9, Font.BOLD,   new Color(255, 255, 255));

        // ── Заголовок отчёта ──────────────────────────────────────────────
        doc.add(new Paragraph("SAPSR — отчёт о проверке оформления", fontTitle));

        String studentName = (s.getStudent() != null && s.getStudent().getFullName() != null)
                ? s.getStudent().getFullName() : "—";
        String fp = s.getFilePath();
        String fileName = fp != null ? fp.replace('\\', '/') : "file.pdf";
        fileName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
        if (fileName.matches("^\\d+_.*")) fileName = fileName.substring(fileName.indexOf('_') + 1);

        boolean isSuccess = "SUCCESS".equals(s.getStatus());

        Paragraph meta = new Paragraph();
        meta.add(new Chunk("Студент: ", fontLabel));
        meta.add(new Chunk(studentName + "\n", fontNormal));
        meta.add(new Chunk("Файл: ",    fontLabel));
        meta.add(new Chunk(fileName + "\n", fontNormal));
        meta.add(new Chunk("Дата: ",    fontLabel));
        meta.add(new Chunk((s.getCreatedAt() != null ? s.getCreatedAt().toString() : "—") + "\n", fontNormal));
        meta.add(new Chunk("Итог: ",    fontLabel));
        meta.add(new Chunk(
                isSuccess ? "Оформление соответствует требованиям ✓" : "Обнаружены нарушения оформления",
                isSuccess ? fontOk : fontErr
        ));
        meta.setSpacingBefore(6);
        meta.setSpacingAfter(6);
        doc.add(meta);

        // Разделительная линия
        doc.add(new Paragraph("─────────────────────────────────────────────────────────────", fontSmall));

        // ── Тело отчёта ───────────────────────────────────────────────────
        String errorsJson = s.getFormatErrors();
        if (errorsJson == null || errorsJson.equals("[]") || errorsJson.equals("null")) {
            Paragraph ok = new Paragraph("Ошибок не найдено. Документ соответствует требованиям оформления.", fontOk);
            ok.setSpacingBefore(10);
            doc.add(ok);
        } else {
            try {
                ObjectMapper om  = new ObjectMapper();
                JsonNode     arr = om.readTree(errorsJson);

                // Счётчики для краткой сводки в начале
                long critical = 0, major = 0, warn = 0;
                for (JsonNode e : arr) {
                    String sev = getJsonText(e, "severity", "");
                    if ("critical".equals(sev)) critical++;
                    else if ("major".equals(sev))    major++;
                    else                              warn++;
                }

                Paragraph summary = new Paragraph();
                summary.setSpacingBefore(4);
                summary.setSpacingAfter(8);
                summary.add(new Chunk("Сводка: ", fontLabel));
                if (critical > 0)
                    summary.add(new Chunk("● " + critical + " критических  ", fontCrit));
                if (major > 0)
                    summary.add(new Chunk("● " + major + " серьёзных  ", fontErr));
                if (warn > 0)
                    summary.add(new Chunk("● " + warn + " предупреждений", fontWarn));
                doc.add(summary);

                int idx = 0;
                for (JsonNode err : arr) {
                    idx++;
                    String severity = getJsonText(err, "severity", "warning");
                    String location = getJsonText(err, "location", "");
                    String page     = getJsonText(err, "page",     "");
                    String message  = getJsonText(err, "message",  err.toString());
                    String rule     = getJsonText(err, "rule",     "");
                    String found    = getJsonText(err, "found",    "");
                    String fix      = getJsonText(err, "fix",      "");
                    String context  = getJsonText(err, "context",  "");

                    Font fSev = switch (severity) {
                        case "critical" -> fontCrit;
                        case "major"    -> fontErr;
                        default         -> fontWarn;
                    };
                    String sevLabel = severityLabel(severity).toUpperCase();

                    // Заголовок нарушения
                    Paragraph errHead = new Paragraph();
                    errHead.setSpacingBefore(10);
                    errHead.add(new Chunk("[" + idx + "] ", fontSmall));
                    errHead.add(new Chunk("[" + sevLabel + "] ", fSev));
                    errHead.add(new Chunk(message, fontHeader));
                    doc.add(errHead);

                    // Местоположение / номер страницы
                    String loc = location.isBlank() ? (page.isBlank() ? "" : "Страница " + page) : location;
                    if (!loc.isBlank()) {
                        Paragraph locP = new Paragraph();
                        locP.add(new Chunk("📍 Место: ", fontLabel));
                        locP.add(new Chunk(loc, fontNormal));
                        locP.setSpacingBefore(2);
                        doc.add(locP);
                    }

                    // Блок с фрагментом текста (context)
                    if (!context.isBlank()) {
                        Paragraph ctxLabel = new Paragraph();
                        ctxLabel.add(new Chunk("Фрагмент из документа:", fontLabel));
                        ctxLabel.setSpacingBefore(3);
                        doc.add(ctxLabel);

                        // Рамка-блок для сниппета
                        PdfPTable snippet = new PdfPTable(1);
                        snippet.setWidthPercentage(100);
                        snippet.setSpacingBefore(1);
                        snippet.setSpacingAfter(2);

                        PdfPCell snippetCell = new PdfPCell(
                                new Phrase("    " + context.replace("\n", " ↵ "), fontSnippet)
                        );
                        snippetCell.setBackgroundColor(new Color(245, 245, 245));
                        snippetCell.setBorder(Rectangle.BOX);
                        snippetCell.setBorderColor(new Color(200, 200, 200));
                        snippetCell.setPadding(4);

                        snippet.addCell(snippetCell);
                        doc.add(snippet);
                    }

                    // Что обнаружено
                    if (!found.isBlank()) {
                        Paragraph foundP = new Paragraph();
                        foundP.add(new Chunk("Обнаружено: ", fontLabel));
                        foundP.add(new Chunk(found, fontNormal));
                        foundP.setSpacingBefore(2);
                        doc.add(foundP);
                    }

                    // Правило
                    if (!rule.isBlank()) {
                        Paragraph ruleP = new Paragraph();
                        ruleP.add(new Chunk("Правило: ", fontLabel));
                        ruleP.add(new Chunk(rule, fontSmall));
                        doc.add(ruleP);
                    }

                    // Рекомендация
                    if (!fix.isBlank()) {
                        Paragraph fixP = new Paragraph();
                        fixP.add(new Chunk("✔ Как исправить: ", fontLabel));
                        fixP.add(new Chunk(fix, fontNormal));
                        fixP.setSpacingBefore(2);
                        doc.add(fixP);
                    }

                    doc.add(new Paragraph("· · · · · · · · · · · · · · · · · · · · · · · · · · · ·", fontSmall));
                }
            } catch (Exception ex) {
                doc.add(new Paragraph(errorsJson, fontNormal));
            }
        }

        doc.close();
        return baos.toByteArray();
    }

    private String getJsonText(JsonNode node, String field, String fallback) {
        if (node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return fallback;
    }

    private String severityLabel(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase()) {
            case "critical" -> "критическое";
            case "major" -> "серьёзное";
            case "warning", "minor" -> "предупреждение";
            default -> "ошибка";
        };
    }

    private Font fontForSeverity(String severity, Font fontErr, Font fontWarn) {
        String normalized = severity == null ? "" : severity.toLowerCase();
        return ("warning".equals(normalized) || "minor".equals(normalized)) ? fontWarn : fontErr;
    }

    private BaseFont loadCyrillicFont() {
        String[] candidates = {
            "C:/Windows/Fonts/arial.ttf",
            "C:/Windows/Fonts/times.ttf",
            "C:/Windows/Fonts/calibri.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                try {
                    return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } catch (Exception ignored) {}
            }
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create font", e);
        }
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