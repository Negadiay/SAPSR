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
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.messaging.TaskMessage;
import com.sapsr.backend.pipeline.PipelineState;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.service.BsuirApiService;
import com.sapsr.backend.service.EmailVerificationService;
import com.sapsr.backend.storage.FileStorage;
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
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class UploadController {

    private static final Pattern WORK_TYPE_PATTERN = Pattern.compile("[a-z_]{1,32}");

    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final BsuirApiService bsuirApiService;
    private final SapsrTelegramBot bot;
    private final FileStorage fileStorage;
    private final ReviewFileService reviewFiles;
    private final AcademicCalendar calendar;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    public UploadController(RabbitTemplate rabbitTemplate,
                            SubmissionRepository submissionRepository,
                            UserRepository userRepository,
                            EmailVerificationService emailVerificationService,
                            BsuirApiService bsuirApiService,
                            SapsrTelegramBot bot,
                            FileStorage fileStorage,
                            ReviewFileService reviewFiles,
                            AcademicCalendar calendar) {
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.bsuirApiService = bsuirApiService;
        this.bot = bot;
        this.fileStorage = fileStorage;
        this.reviewFiles = reviewFiles;
        this.calendar = calendar;
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
            // Свои данные для экрана настроек (api-v1.md, раздел 5); имя и отчество у старых аккаунтов пусты.
            Map<String, Object> me = new LinkedHashMap<>();
            me.put("telegram_id", u.getTelegramId());
            me.put("role", u.getRole());
            me.put("full_name", u.getFullName() != null ? u.getFullName() : "");
            me.put("last_name", u.getLastName());
            me.put("first_name", u.getFirstName());
            me.put("middle_name", u.getMiddleName());
            if ("STUDENT".equals(u.getRole())) {
                me.put("group", u.getGroupNumber());
                // Курс, семестр и этап определяет сервер (решение 11): студент их видит, но не выбирает.
                AcademicCalendar.Term term = calendar.termFor(u.getGroupNumber());
                me.put("course", term.course());
                me.put("semester", term.semester());
                me.put("stage", term.stage());
                // Срок сдачи работы — у каждого курса свой (у 3 курса весной год короче из-за практики).
                me.put("deadline", term.deadline() == null ? null : term.deadline().toString());
                me.put("course_overridden", term.courseOverridden());
            }
            return ResponseEntity.ok(me);
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

        // Роль администратора задаётся только в базе (решение 11): регистрацией её не получить и не потерять.
        Optional<User> current = userRepository.findById(telegramId);
        if (current.isPresent() && AdminController.ROLE.equals(current.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("code", "ADMIN_ACCOUNT",
                    "error", "Это аккаунт администратора: его роль меняется только в базе данных"));
        }

        if (!"STUDENT".equals(role) && !"TEACHER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Роль должна быть STUDENT или TEACHER"));
        }

        // Для студента имя обязательно (для преподавателя подтягивается из IIS)
        if ("STUDENT".equals(role) && fullName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Заполните ФИО / данные"));
        }

        String groupNumber = null;
        if ("STUDENT".equals(role)) {
            if (!fullName.matches("^[А-ЯЁ][а-яё]+\\s[А-ЯЁ]\\.[А-ЯЁ]\\. \\(гр\\. \\d{6}\\)$")) {
                return ResponseEntity.badRequest().body(Map.of("error", "Укажите ФИО и группу в формате: Иванов И.И., 123456"));
            }
            Matcher groupMatcher = Pattern.compile("\\(гр\\.\\s*(\\d{6})\\)").matcher(fullName);
            if (!groupMatcher.find()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Укажите номер группы в формате: Иванов И.И., 123456"));
            }
            groupNumber = groupMatcher.group(1);
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
                    return ResponseEntity.badRequest().body(Map.of("error", "Почта не обнаружена в ИИС БГУИР"));
                }
                fullName   = info.get().fio();
                urlIdToSave = info.get().urlId();
            } catch (BsuirApiService.IisUnavailableException e) {
                return ResponseEntity.status(503).body(Map.of("error",
                        "Сервис IIS БГУИР временно недоступен. Попробуйте позже."));
            }
        }

        User user = current.orElseGet(User::new);
        user.setTelegramId(telegramId);
        user.setRole(role);
        user.setFullName(fullName);
        setNameParts(user, fullName, groupNumber);
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

    /**
     * Раскладывает ФИО по полям пользователя — так же, как миграция V2 для уже зарегистрированных.
     * Студент пока вводит «Иванов И.И.»: известна только фамилия, имя и отчество остаются пустыми.
     * У преподавателя ФИО из ИИС полное: «Фамилия Имя Отчество».
     */
    public static void setNameParts(User user, String fullName, String groupNumber) {
        String[] parts = fullName.strip().split("\\s+");
        boolean initialsOnly = parts.length < 2 || parts[1].contains(".");
        user.setLastName(parts[0].isEmpty() ? null : parts[0]);
        user.setFirstName(initialsOnly ? null : parts[1]);
        user.setMiddleName(initialsOnly || parts.length < 3
                ? null : String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)));
        user.setGroupNumber(groupNumber);
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
            item.put("status", normalizeDbCode(s.getStatus()));
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            // Ход проверки (queue-v2.md, разделы 4 и 6): этап для индикатора и идёт ли ещё проверка содержания.
            item.put("pipeline_stage", s.getPipelineStage());
            item.put("content_status", s.getContentStatus());
            item.put("format_errors", s.getFormatErrors());
            item.put("teacher_verdict", normalizeNullableDbCode(s.getTeacherVerdict()));
            item.put("teacher_comment", s.getTeacherComment());
            item.put("file_name", s.displayFileName());
            // Комментарии преподавателя в файле видны студенту только после вердикта.
            boolean reviewed = s.getTeacherVerdict() != null && !s.getTeacherVerdict().isBlank();
            item.put("review_annotations", reviewed ? reviewFiles.countAnnotations(s.getId()) : 0);
            item.put("has_review_file", reviewed && reviewFiles.find(s.getId()).isPresent());
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

        reviewFiles.deleteFor(id);
        submissionRepository.deleteById(id);
        deleteStoredFile(s);
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    // Файл удаляется вместе с записью (решение 7). Если удалить не вышло, работа всё равно считается отозванной.
    private void deleteStoredFile(Submission s) {
        if (s.getStorageKey() == null) return;
        try {
            fileStorage.delete(s.getStorageKey());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[WITHDRAW] Не удалось удалить файл работы #" + s.getId() + ": " + e.getMessage());
        }
    }

    @GetMapping("/submissions/{id}/report")
    public ResponseEntity<byte[]> getReport(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        Optional<Submission> opt = submissionRepository.findById(id);

        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Submission s = opt.get();
        // Отчёт видят автор работы и её преподаватель (решение 9: преподаватель получает и отклонённые работы).
        boolean isStudent = s.getStudent() != null && s.getStudent().getTelegramId().equals(telegramId);
        boolean isTeacher = s.getTeacher() != null && s.getTeacher().getTelegramId().equals(telegramId);
        if (telegramId == null || !(isStudent || isTeacher)) {
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
        doc.add(new Paragraph("SAPSR — отчёт об автоматической проверке", fontTitle));

        String studentName = (s.getStudent() != null && s.getStudent().getFullName() != null)
                ? s.getStudent().getFullName() : "—";
        String fileName = s.displayFileName();

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
                    String category = getJsonText(err, "category", "");
                    String origin   = getJsonText(err, "origin",   "symbolic");
                    boolean needsReview = err.path("needs_teacher_review").asBoolean(false);
                    // Содержательные замечания — от языковой модели и правил вместе; сдачу не блокируют.
                    if ("content".equals(category) || "requirements".equals(category) || !"symbolic".equals(origin)) {
                        message = "[Содержание] " + message;
                    }
                    if (needsReview) message = message + " (требует проверки преподавателем)";

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

    private String normalizeDbCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullableDbCode(String value) {
        String normalized = normalizeDbCode(value);
        return normalized.isBlank() ? null : normalized;
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

    /**
     * Загрузка работы. Вид работы выбирает студент; семестр и этап опроцентовки определяет сервер по группе
     * студента и учебному календарю (решение 11) — поля semester и stage от клиента не принимаются.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "teacher_id", required = false) Long teacherId,
                                        @RequestParam(value = "work_type", required = false) String workType,
                                        HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой!"));
        }
        // Вид работы необязателен: без профиля обработчик применяет базовый.
        if (workType != null && !WORK_TYPE_PATTERN.matcher(workType).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Неизвестный вид работы"));
        }

        // На диск файл попадает под сгенерированным ключом; имя от пользователя — только в базу.
        FileStorage.StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = fileStorage.store(content, TaskMessage.SOURCE_FORMAT_PDF);
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Ошибка при сохранении файла"));
        }

        Submission submission = new Submission();
        submission.setStorageKey(stored.key());
        submission.setFileSha256(stored.sha256());
        submission.setOriginalFileName(Submission.cleanFileName(file.getOriginalFilename()));
        submission.setWorkType(workType);
        submission.setTraceId(UUID.randomUUID());
        // В очереди, пока обработчик не начал: первое сообщение о ходе проверки переведёт в PROCESSING.
        submission.setStatus(PipelineState.QUEUED);

        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId != null) {
            Optional<User> student = userRepository.findById(telegramId);
            student.ifPresent(submission::setStudent);
        }
        AcademicCalendar.Term term = calendar.termFor(
                submission.getStudent() != null ? submission.getStudent().getGroupNumber() : null);
        submission.setSemester(term.semester());
        submission.setStage(term.stage());

        if (teacherId != null) {
            Optional<User> teacher = userRepository.findById(teacherId);
            teacher.ifPresent(submission::setTeacher);
        }
        linkPreviousVersion(submission);

        Submission savedSubmission = submissionRepository.save(submission);

        TaskMessage task = TaskMessage.check(savedSubmission, fileStorage.resolve(savedSubmission.getStorageKey()));
        rabbitTemplate.convertAndSend(tasksQueue, task);
        // ФИО студента в лог не пишем — только идентификаторы.
        System.out.println("Отправлено задание обработчику: task_id=" + task.taskId()
                + ", storage_key=" + task.file().storageKey() + ", trace_id=" + task.traceId());

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Файл успешно загружен в систему SAPSR!",
                "db_id", savedSubmission.getId()
        ));
    }

    /**
     * Решение 9: новая загрузка студента тому же преподавателю — следующая версия последней работы,
     * если та ещё не принята. Новая версия заменяет предыдущую в очереди преподавателя, а срок сдачи
     * по-прежнему считается от первой версии. После «принято» загрузка начинает новую работу.
     */
    private void linkPreviousVersion(Submission submission) {
        if (submission.getStudent() == null || submission.getTeacher() == null) return;
        submissionRepository.findFirstByStudent_TelegramIdAndTeacher_TelegramIdOrderByCreatedAtDescIdDesc(
                        submission.getStudent().getTelegramId(), submission.getTeacher().getTelegramId())
                .filter(previous -> !"APPROVED".equals(normalizeDbCode(previous.getTeacherVerdict())))
                .ifPresent(previous -> {
                    submission.setParentId(previous.getId());
                    submission.setFirstSubmittedAt(previous.submittedForDeadline());
                });
    }
}