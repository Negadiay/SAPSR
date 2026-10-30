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
import com.sapsr.backend.service.EmailVerificationService;
import com.sapsr.backend.storage.FileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
                            AcademicCalendar calendar,
                            LlmModels llmModels) {
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
                // Проверяет облачная модель (её выбрал администратор) — студенту говорится об этом при сдаче.
                me.put("cloud_check", llmModels.cloudActive());
            }
            return ResponseEntity.ok(me);
        }
        return ResponseEntity.ok(Map.of("telegram_id", telegramId, "role", "NONE"));
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

        if (!"STUDENT".equals(role) && !"TEACHER".equals(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Неизвестная роль."));
        }

        String groupNumber = null;
        if ("STUDENT".equals(role)) {
            Matcher name = STUDENT_NAME.matcher(fullName);
            if (!name.matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Пример: Иванов И.И., 321702"));
            }
            groupNumber = name.group(1);
            if (!bsuirApiService.groupExists(groupNumber)) {
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
                item.put("teacher_id", s.getTeacher().getTelegramId());
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
                                        @RequestParam(value = "teacher_id", required = false) Long teacherId,
                                        HttpServletRequest request) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой."));
        }

        // На диск файл попадает под сгенерированным ключом; имя от пользователя — только в базу.
        FileStorage.StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = fileStorage.store(content, TaskMessage.SOURCE_FORMAT_PDF);
        } catch (IOException e) {
            System.err.println("[UPLOAD] Не удалось сохранить файл: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Не удалось сохранить файл. Попробуйте ещё раз."));
        }

        Submission submission = new Submission();
        submission.setStorageKey(stored.key());
        submission.setFileSha256(stored.sha256());
        submission.setOriginalFileName(Submission.cleanFileName(file.getOriginalFilename()));
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
        // Курсовая работа или проект станет известна из титульного листа (PipelineState); дипломная — сразу.
        submission.setWorkType(WorkTypes.atUpload(term.semester()));

        if (teacherId != null) {
            Optional<User> teacher = userRepository.findById(teacherId);
            teacher.ifPresent(submission::setTeacher);
        }
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
