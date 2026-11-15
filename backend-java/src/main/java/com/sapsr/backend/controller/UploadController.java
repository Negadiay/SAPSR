package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.academic.WorkTypes;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.llm.LlmModels;
import com.sapsr.backend.messaging.TaskMessage;
import com.sapsr.backend.pipeline.PipelineState;
import com.sapsr.backend.report.CheckReportPdf;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.service.BsuirApiService;
import com.sapsr.backend.service.DocxConverter;
import com.sapsr.backend.storage.FileKind;
import com.sapsr.backend.service.EmailVerificationService;
import com.sapsr.backend.service.Namesakes;
import com.sapsr.backend.entity.NameKey;
import com.sapsr.backend.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.ContentDisposition;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class UploadController {

    static final String NOT_IN_TELEGRAM = "Откройте приложение из Telegram.";

    /** Студент старой формы: «Иванов И.И. (гр. 321702)». */
    private static final Pattern STUDENT_NAME = Pattern.compile("^[А-ЯЁ][а-яё]+\\s[А-ЯЁ]\\.[А-ЯЁ]\\. \\(гр\\. (\\d{6})\\)$");

    private final RabbitTemplate rabbitTemplate;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final BsuirApiService bsuirApiService;
    private final SapsrTelegramBot bot;
    private final FileStorage fileStorage;
    private final ReviewFileService reviewFiles;
    private final AcademicCalendar calendar;
    private final LlmModels llmModels;
    private final DocxConverter docxConverter;

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    /** Секрет для ключей преподавателей без идентификатора ИИС (service/TeacherKey). */
    @Value("${telegram.bot.token:}")
    private String keySecret = "";

    /** Предел размера работы, МБ (решение 15): предел Telegram на отправку файла ботом. */
    @Value("${sapsr.upload.max-size-mb:50}")
    private int maxUploadMb = 50;

    public UploadController(RabbitTemplate rabbitTemplate,
                            SubmissionRepository submissionRepository,
                            UserRepository userRepository,
                            EmailVerificationService emailVerificationService,
                            BsuirApiService bsuirApiService,
                            SapsrTelegramBot bot,
                            FileStorage fileStorage,
                            ReviewFileService reviewFiles,
                            AcademicCalendar calendar,
                            LlmModels llmModels,
                            DocxConverter docxConverter) {
        this.docxConverter = docxConverter;
        this.rabbitTemplate = rabbitTemplate;
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.bsuirApiService = bsuirApiService;
        this.bot = bot;
        this.fileStorage = fileStorage;
        this.reviewFiles = reviewFiles;
        this.calendar = calendar;
        this.llmModels = llmModels;
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_IN_TELEGRAM));
        }
        Optional<User> user = userRepository.findById(telegramId);
        if (user.isPresent()) {
            return ResponseEntity.ok(meView(user.get()));
        }
        return ResponseEntity.ok(Map.of("telegram_id", telegramId, "role", "NONE"));
    }

    /** Свои данные пользователя — ответ GET /me, PATCH /me и поле user после регистрации (api-v1.md, раздел 5). */
    private Map<String, Object> meView(User u) {
        {
            // Свои данные для экрана настроек (api-v1.md, раздел 5); имя и отчество у старых аккаунтов пусты.
            Map<String, Object> me = new LinkedHashMap<>();
            me.put("telegram_id", u.getTelegramId());
            me.put("role", u.getRole());
            me.put("full_name", u.getFullName() != null ? u.getFullName() : "");
            me.put("last_name", u.getLastName());
            me.put("first_name", u.getFirstName());
            me.put("middle_name", u.getMiddleName());
            me.put("max_upload_mb", maxUploadMb);
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
                // Проверяет облачная модель (её выбрал администратор) — студенту говорится об этом при сдаче.
                me.put("cloud_check", llmModels.cloudActive());
                // Номер виден, только если тёзка есть: «Среди студентов группы с таким же ФИО вы — №2».
                me.put("namesake_number", Namesakes.hasNamesake(userRepository, u) ? u.getNamesakeNumber() : null);
                // Аккаунт по старой форме — известны только фамилия и инициалы: интерфейс предложит дополнить ФИО.
                me.put("profile_incomplete", u.getFirstName() == null || u.getFirstName().isBlank());
            }
            return me;
        }
    }

    /**
     * Исправление ФИО студентом (api-v1.md, раздел 5). Группу и роль меняет только администратор (решение 15).
     * ФИО изменилось — студент получает номер тёзки заново.
     */
    @PatchMapping("/me")
    public ResponseEntity<?> patchMe(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ApiErrors.error(401, "UNAUTHORIZED", NOT_IN_TELEGRAM);
        Optional<User> current = userRepository.findById(telegramId);
        if (current.isEmpty() || !"STUDENT".equals(current.get().getRole())) {
            return ApiErrors.error(403, "FORBIDDEN", "Изменить ФИО может только студент.");
        }
        if (body.containsKey("group")) {
            return ApiErrors.error(400, "VALIDATION_ERROR", "Группу меняет администратор.", "group");
        }
        User user = current.get();
        Optional<ResponseEntity<?>> invalid = applyNameFields(user, body);
        if (invalid.isPresent()) return invalid.get();
        return ResponseEntity.ok(meView(user));
    }

    /** Проверяет и записывает last_name, first_name, middle_name; сохраняет студента с номером тёзки. */
    private Optional<ResponseEntity<?>> applyNameFields(User user, Map<String, ?> body) {
        String last = NameKey.clean(text(body.get("last_name")));
        String first = NameKey.clean(text(body.get("first_name")));
        String rawMiddle = text(body.get("middle_name"));
        String middle = rawMiddle == null || rawMiddle.isBlank() ? null : NameKey.clean(rawMiddle);
        String hint = " — от 1 до 100 букв кириллицы, можно дефис и пробел.";
        if (last == null) return Optional.of(ApiErrors.error(400, "VALIDATION_ERROR", "Фамилия" + hint, "last_name"));
        if (first == null) return Optional.of(ApiErrors.error(400, "VALIDATION_ERROR", "Имя" + hint, "first_name"));
        if (rawMiddle != null && !rawMiddle.isBlank() && middle == null) {
            return Optional.of(ApiErrors.error(400, "VALIDATION_ERROR", "Отчество" + hint, "middle_name"));
        }
        String previousKey = user.getNameKey();
        String previousGroup = user.getNamesakeNumber() == null ? null : user.getGroupNumber();
        user.setLastName(last);
        user.setFirstName(first);
        user.setMiddleName(middle);
        String full = last + " " + first + (middle == null ? "" : " " + middle);
        user.setFullName(full + " (гр. " + user.getGroupNumber() + ")");
        Namesakes.saveStudent(userRepository, user, previousKey, previousGroup);
        return Optional.empty();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_IN_TELEGRAM));
        }

        String role = body.getOrDefault("role", "").toUpperCase();
        String fullName = body.getOrDefault("full_name", "").trim();

        // Роль администратора задаётся только в базе (решение 11): регистрацией её не получить и не потерять.
        Optional<User> current = userRepository.findById(telegramId);
        if (current.isPresent() && AdminController.ROLE.equals(current.get().getRole())) {
            return ResponseEntity.status(403).body(Map.of("code", "ADMIN_ACCOUNT",
                    "error", "Это аккаунт администратора. Для учёбы нужен другой аккаунт Telegram."));
        }

        // Один аккаунт Telegram — один человек (решение 15): повторная регистрация ничего не меняет.
        if (current.isPresent() && ("STUDENT".equals(current.get().getRole())
                || "TEACHER".equals(current.get().getRole()))) {
            return ApiErrors.error(409, "ALREADY_REGISTERED", "Этот аккаунт Telegram уже зарегистрирован. "
                    + "Исправить ФИО можно в настройках, сменить группу — у администратора.");
        }

        if (!"STUDENT".equals(role) && !"TEACHER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Неизвестная роль."));
        }

        // Студент по полям (api-v1.md, раздел 4): фамилия, имя, отчество и группа по отдельности.
        if ("STUDENT".equals(role) && body.containsKey("last_name")) {
            String group = body.getOrDefault("group", "").trim();
            if (!group.matches("\\d{6}")) {
                return ApiErrors.error(400, "VALIDATION_ERROR", "Номер группы — шесть цифр.", "group");
            }
            try {
                if (!bsuirApiService.groupExists(group)) {
                    return ApiErrors.error(400, "GROUP_NOT_FOUND",
                            "Группы " + group + " нет в ИИС БГУИР. Проверьте номер.", "group");
                }
            } catch (BsuirApiService.IisUnavailableException e) {
                return ApiErrors.error(503, "IIS_UNAVAILABLE", "ИИС БГУИР не отвечает, номер группы не проверить. "
                        + "Попробуйте позже.");
            }
            User user = new User();
            user.setTelegramId(telegramId);
            user.setRole(role);
            user.setGroupNumber(group);
            Optional<ResponseEntity<?>> invalid = applyNameFields(user, body);
            if (invalid.isPresent()) return invalid.get();
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "OK");
            answer.put("role", role);
            answer.put("full_name", user.getFullName());
            answer.put("user", meView(user));
            return ResponseEntity.ok(answer);
        }

        String groupNumber = null;
        if ("STUDENT".equals(role)) {
            Matcher name = STUDENT_NAME.matcher(fullName);
            if (!name.matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Пример: Иванов И.И., 321702"));
            }
            groupNumber = name.group(1);
            boolean exists;
            try {
                exists = bsuirApiService.groupExists(groupNumber);
            } catch (BsuirApiService.IisUnavailableException e) {
                return ApiErrors.error(503, "IIS_UNAVAILABLE", "ИИС БГУИР не отвечает, номер группы не проверить. "
                        + "Попробуйте позже.");
            }
            if (!exists) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Группы " + groupNumber + " нет в ИИС БГУИР. Проверьте номер."));
            }
        }

        String email        = null;
        String urlIdToSave  = null;
        if ("TEACHER".equals(role)) {
            email = body.getOrDefault("email", "").trim().toLowerCase();
            String code = body.getOrDefault("code", "").trim();

            if (email.isEmpty() || code.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Введите почту и код."));
            }
            if (!emailVerificationService.isAllowedDomain(email)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Нужна почта @bsuir.by."));
            }
            Optional<User> byEmail = userRepository.findByEmail(email);
            if (byEmail.isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Эта почта уже зарегистрирована."));
            }
            if (!emailVerificationService.verifyCode(email, code)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Неверный или устаревший код. Запросите новый."));
            }

            // Поиск преподавателя в IIS и автозаполнение ФИО + urlId
            try {
                Optional<BsuirApiService.TeacherInfo> info = bsuirApiService.findTeacherByEmail(email);
                if (info.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Такой почты нет в ИИС БГУИР."));
                }
                fullName   = info.get().fio();
                urlIdToSave = info.get().urlId();
            } catch (BsuirApiService.IisUnavailableException e) {
                return ResponseEntity.status(503).body(Map.of("error", "ИИС БГУИР не отвечает. Попробуйте позже."));
            }
        }

        User user = current.orElseGet(User::new);
        user.setTelegramId(telegramId);
        user.setRole(role);
        user.setFullName(fullName);
        setNameParts(user, fullName, groupNumber);
        if (email != null) user.setEmail(email);
        if ("STUDENT".equals(role)) {
            // Старая форма «Иванов И.И. (гр. 321702)» — на переходный период; номер тёзки выдаётся так же.
            Namesakes.saveStudent(userRepository, user, null, null);
            return ResponseEntity.ok(Map.of("status", "OK", "role", role, "full_name", fullName));
        }
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

    /**
     * Работы студента, новые сверху. Версии одной работы связаны parent_id (решение 9): интерфейс собирает
     * из них цепочку, номер версии считается здесь же.
     */
    @GetMapping("/submissions")
    public ResponseEntity<?> getSubmissions(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return ResponseEntity.status(401).body(Map.of("error", NOT_IN_TELEGRAM));
        }

        List<Submission> submissions = submissionRepository.findByStudent_TelegramIdOrderByCreatedAtDesc(telegramId);
        Map<Integer, Integer> parents = new HashMap<>();
        for (Submission s : submissions) parents.put(s.getId(), s.getParentId());

        List<Map<String, Object>> result = new ArrayList<>();
        for (Submission s : submissions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("status", normalizeDbCode(s.getStatus()));
            item.put("created_at", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            item.put("parent_id", s.getParentId());
            item.put("version", version(s.getId(), parents));
            item.put("work_type", s.getWorkType());
            if (s.getTeacher() != null) {
                // Ключ преподавателя, как в GET /teachers, а не его Telegram ID (план Котко, К7).
                item.put("teacher_id", com.sapsr.backend.service.TeacherKey.of(s.getTeacher(), keySecret));
                item.put("teacher_name", s.getTeacher().displayName());
            }
            // Ход проверки (queue-v2.md, разделы 4 и 6): этап для индикатора и идёт ли ещё проверка содержания.
            item.put("pipeline_stage", s.getPipelineStage());
            item.put("content_status", s.getContentStatus());
            // Причина сбоя (queue-v2.md, раздел 5) — интерфейс показывает её словами.
            item.put("failure_code", s.getFailureCode());
            item.put("format_errors", s.getFormatErrors());
            item.put("teacher_verdict", normalizeNullableDbCode(s.getTeacherVerdict()));
            item.put("teacher_comment", s.getTeacherComment());
            item.put("file_name", s.displayFileName());
            // Сдана в DOCX — проверялась PDF-версия, номера страниц в замечаниях относятся к ней (решение 3).
            item.put("source_format", s.getSourceFormat());
            // Комментарии преподавателя в файле видны студенту только после вердикта.
            boolean reviewed = s.getTeacherVerdict() != null && !s.getTeacherVerdict().isBlank();
            item.put("review_annotations", reviewed ? reviewFiles.countAnnotations(s.getId()) : 0);
            item.put("has_review_file", reviewed && reviewFiles.find(s.getId()).isPresent());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    /** Номер версии по цепочке parent_id внутри работ студента: 1 — первая загрузка. */
    static int version(Integer id, Map<Integer, Integer> parents) {
        int version = 1;
        Set<Integer> seen = new HashSet<>();
        Integer parent = parents.get(id);
        while (parent != null && seen.add(parent)) {
            version++;
            parent = parents.get(parent);
        }
        return version;
    }

    @DeleteMapping("/submissions/{id}")
    public ResponseEntity<?> withdrawSubmission(@PathVariable Integer id, HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ResponseEntity.status(401).body(Map.of("error", NOT_IN_TELEGRAM));

        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Submission s = opt.get();
        if (s.getStudent() == null || !s.getStudent().getTelegramId().equals(telegramId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Это не ваша работа."));
        }
        if (!"SUCCESS".equals(s.getStatus()) || s.getTeacherVerdict() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Эту работу уже нельзя отозвать."));
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
        for (String key : new String[] {s.getStorageKey(), s.getSourceStorageKey()}) {
            if (key == null) continue;
            try {
                fileStorage.delete(key);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("[WITHDRAW] Не удалось удалить файл работы #" + s.getId() + ": " + e.getMessage());
            }
        }
    }

    /** PDF-версия работы — та, что проверялась (api-v1.md, раздел 18): автору и преподавателю работы. */
    @GetMapping("/submissions/{id}/pdf")
    public ResponseEntity<?> getPdf(@PathVariable Integer id, HttpServletRequest request) {
        return download(id, request, false);
    }

    /** Исходный DOCX работы, сданной в Word (api-v1.md, раздел 18): автору и преподавателю работы. */
    @GetMapping("/submissions/{id}/source")
    public ResponseEntity<?> getSource(@PathVariable Integer id, HttpServletRequest request) {
        return download(id, request, true);
    }

    private ResponseEntity<?> download(Integer id, HttpServletRequest request, boolean source) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) return ApiErrors.error(401, "UNAUTHORIZED", NOT_IN_TELEGRAM);
        Optional<Submission> opt = submissionRepository.findById(id);
        if (opt.isEmpty()) return ApiErrors.error(404, "NOT_FOUND", "Работа не найдена. Возможно, её отозвали.");
        Submission s = opt.get();
        boolean isStudent = s.getStudent() != null && s.getStudent().getTelegramId().equals(telegramId);
        boolean isTeacher = s.getTeacher() != null && s.getTeacher().getTelegramId().equals(telegramId);
        if (!(isStudent || isTeacher)) return ApiErrors.error(403, "FORBIDDEN", "Это не ваша работа.");
        String key = source ? s.getSourceStorageKey() : s.getStorageKey();
        if (key == null) {
            return ApiErrors.error(404, "NOT_FOUND", source ? "Работа сдана в PDF — исходного DOCX нет." : "Файл не найден.");
        }
        try {
            Path path = fileStorage.resolve(key);
            if (!Files.isRegularFile(path)) return ApiErrors.error(404, "NOT_FOUND", "Файл не найден.");
            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(source ? s.displayFileName() : s.pdfFileName(), StandardCharsets.UTF_8).build();
            return ResponseEntity.ok()
                    .header("Content-Type", source
                            ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            : "application/pdf")
                    .header("Content-Disposition", disposition.toString())
                    .body(Files.readAllBytes(path));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[DOWNLOAD] Не удалось отдать файл работы #" + id + ": " + e.getMessage());
            return ApiErrors.error(500, "INTERNAL", "Не удалось открыть файл. Попробуйте ещё раз.");
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
            byte[] content = CheckReportPdf.build(s);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"report_" + id + ".pdf\"")
                    .body(content);
        } catch (Exception e) {
            System.err.println("[REPORT] Не удалось собрать отчёт работы #" + id + ": " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private String normalizeDbCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullableDbCode(String value) {
        String normalized = normalizeDbCode(value);
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * Загрузка работы. Семестр и этап опроцентовки определяет сервер по группе студента и учебному календарю,
     * вид работы — по семестру и титульному листу (решение 11); от клиента нужны только файл и преподаватель.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        @RequestParam(value = "teacher_id", required = false) String teacherId,
                                        HttpServletRequest request) {
        // Сдавать работы может только студент, и только преподавателю (план Котко, К7).
        Long uploaderId = (Long) request.getAttribute("telegram_id");
        if (uploaderId == null) return ApiErrors.error(401, "UNAUTHORIZED", NOT_IN_TELEGRAM);
        Optional<User> uploader = userRepository.findById(uploaderId);
        if (uploader.isEmpty() || !"STUDENT".equals(uploader.get().getRole())) {
            return ApiErrors.error(403, "FORBIDDEN", "Сдавать работы может только студент.");
        }
        User chosenTeacher = null;
        if (teacherId != null && !teacherId.isBlank()) {
            chosenTeacher = findTeacher(teacherId.strip()).orElse(null);
            if (chosenTeacher == null) {
                return ApiErrors.error(400, "VALIDATION_ERROR", "Преподаватель не найден. Выберите его из списка.",
                        "teacher_id");
            }
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой."));
        }
        if (ApiErrors.tooLarge(file.getSize(), maxUploadMb)) {
            return ApiErrors.fileTooLarge(maxUploadMb);
        }

        // Вид файла — по содержимому (решение 3): PDF проверяется как есть, DOCX сначала становится PDF.
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            System.err.println("[UPLOAD] Не удалось прочитать файл: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Не удалось сохранить файл. Попробуйте ещё раз."));
        }
        FileKind kind = FileKind.of(original);
        if (kind == FileKind.UNKNOWN) {
            return ApiErrors.error(400, "UNSUPPORTED_FORMAT", "Нужен файл PDF или DOCX.");
        }
        byte[] pdf = original;
        if (kind == FileKind.DOCX) {
            try {
                pdf = docxConverter.toPdf(original);
            } catch (DocxConverter.ConversionException e) {
                System.err.println("[UPLOAD] DOCX не преобразован в PDF: " + e.getMessage());
                return ApiErrors.error(422, "CONVERSION_FAILED",
                        "Не получилось преобразовать DOCX в PDF. Сохраните работу в PDF и загрузите его.");
            }
        }

        // На диск файл попадает под сгенерированным ключом; имя от пользователя — только в базу.
        FileStorage.StoredFile stored;
        String sourceKey = null;
        try {
            stored = fileStorage.store(new ByteArrayInputStream(pdf), TaskMessage.SOURCE_FORMAT_PDF);
            if (kind == FileKind.DOCX) {
                try {
                    sourceKey = fileStorage.store(new ByteArrayInputStream(original), kind.extension()).key();
                } catch (IOException e) {
                    fileStorage.delete(stored.key());
                    throw e;
                }
            }
        } catch (IOException e) {
            System.err.println("[UPLOAD] Не удалось сохранить файл: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Не удалось сохранить файл. Попробуйте ещё раз."));
        }

        Submission submission = new Submission();
        submission.setStorageKey(stored.key());
        submission.setSourceStorageKey(sourceKey);
        submission.setSourceFormat(kind.extension());
        submission.setFileSha256(stored.sha256());
        submission.setOriginalFileName(Submission.cleanFileName(file.getOriginalFilename()));
        submission.setTraceId(UUID.randomUUID());
        // В очереди, пока обработчик не начал: первое сообщение о ходе проверки переведёт в PROCESSING.
        submission.setStatus(PipelineState.QUEUED);

        submission.setStudent(uploader.get());
        AcademicCalendar.Term term = calendar.termFor(
                submission.getStudent() != null ? submission.getStudent().getGroupNumber() : null);
        submission.setSemester(term.semester());
        submission.setStage(term.stage());
        // Курсовая работа или проект станет известна из титульного листа (PipelineState); дипломная — сразу.
        submission.setWorkType(WorkTypes.atUpload(term.semester()));

        if (chosenTeacher != null) submission.setTeacher(chosenTeacher);
        linkPreviousVersion(submission);

        Submission savedSubmission = submissionRepository.save(submission);

        TaskMessage task = TaskMessage.check(savedSubmission, fileStorage.resolve(savedSubmission.getStorageKey()),
                llmModels.forTask());
        rabbitTemplate.convertAndSend(tasksQueue, task);
        // ФИО студента в лог не пишем — только идентификаторы.
        System.out.println("Отправлено задание обработчику: task_id=" + task.taskId()
                + ", storage_key=" + task.file().storageKey() + ", trace_id=" + task.traceId());

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Работа отправлена на проверку.",
                "db_id", savedSubmission.getId()
        ));
    }

    /**
     * Преподаватель по ключу из GET /teachers; на переходный период принимается и Telegram ID —
     * но только пользователя с ролью преподавателя.
     */
    private Optional<User> findTeacher(String key) {
        Optional<User> byKey = com.sapsr.backend.service.TeacherKey.find(userRepository.findByRole("TEACHER"), key, keySecret);
        if (byKey.isPresent() || !key.matches("\\d{1,18}")) return byKey;
        return userRepository.findById(Long.parseLong(key)).filter(u -> "TEACHER".equals(u.getRole()));
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
