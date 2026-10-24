package com.sapsr.backend.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.controller.UploadController;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Пользователи на экране администратора (решение 11): студенты и преподаватели группы, исправление ФИО и
 * группы студента без похода в базу, назначение студента администратором. Преподавателей — только просмотр.
 */
@Service
public class AdminUsers {

    public static final String ADMIN = "ADMIN";
    public static final String STUDENT = "STUDENT";
    public static final String TEACHER = "TEACHER";

    private static final Logger log = LoggerFactory.getLogger(AdminUsers.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Хвост «(гр. 321702)», который регистрация дописывает к ФИО студента. */
    private static final Pattern GROUP_SUFFIX = Pattern.compile("\\s*\\(гр\\.\\s*\\d{6}\\)\\s*$");
    /** ФИО: с заглавной, буквы кириллицы и латиницы, пробел, точка (инициалы), дефис, апостроф. */
    private static final Pattern NAME = Pattern.compile("[А-ЯЁA-Z][А-ЯЁа-яёA-Za-z'.\\- ]{1,99}");

    /** Человек на экране администратора. id — Telegram ID: только ключ для изменений, интерфейс его не показывает. */
    public record Person(long id, String name, String group) {}

    /** Ввод не прошёл проверку: field — какое поле. */
    public static class Invalid extends RuntimeException {
        private final String field;

        public Invalid(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() { return field; }
    }

    public static class NotFound extends RuntimeException {
        public NotFound() { super("Пользователь не найден"); }
    }

    /** Действие — только над студентом: данные преподавателя администратор лишь просматривает. */
    public static class NotAStudent extends RuntimeException {
        public NotAStudent(String message) { super(message); }
    }

    private final UserRepository users;
    private final SubmissionRepository submissions;
    private final ReviewFileService reviewFiles;
    private final FileStorage fileStorage;
    private final SapsrTelegramBot bot;
    private final AcademicCalendar calendar;

    public AdminUsers(UserRepository users, SubmissionRepository submissions, ReviewFileService reviewFiles,
                      FileStorage fileStorage, SapsrTelegramBot bot, AcademicCalendar calendar) {
        this.users = users;
        this.submissions = submissions;
        this.reviewFiles = reviewFiles;
        this.fileStorage = fileStorage;
        this.bot = bot;
        this.calendar = calendar;
    }

    // ── кто в группе ─────────────────────────────────────────────────────────────────────────

    public List<Person> students(String group) {
        return users.findByRoleAndGroupNumber(STUDENT, group).stream()
                .map(this::person)
                .sorted(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Преподаватели, у которых группа есть в расписании ИИС (сохранено при регистрации). */
    public List<Person> teachers(String group) {
        return users.findByRole(TEACHER).stream()
                .filter(t -> teacherGroups(t).contains(group))
                .map(this::person)
                .sorted(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public static List<String> teacherGroups(User teacher) {
        if (teacher.getTeacherGroups() == null || teacher.getTeacherGroups().isBlank()) return List.of();
        try {
            List<String> groups = new java.util.ArrayList<>();
            for (JsonNode g : JSON.readTree(teacher.getTeacherGroups())) {
                if (g.asText("").matches("\\d{6}")) groups.add(g.asText());
            }
            return groups;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** ФИО для показа и правки — без «(гр. …)», которое регистрация дописывает студентам. */
    public static String displayName(User user) {
        String full = user.getFullName() == null ? "" : user.getFullName();
        String name = GROUP_SUFFIX.matcher(full).replaceFirst("").strip();
        if (name.isEmpty() && user.getLastName() != null) name = user.getLastName();
        return name;
    }

    private Person person(User u) {
        return new Person(u.getTelegramId(), displayName(u), STUDENT.equals(u.getRole()) ? u.getGroupNumber() : null);
    }

    // ── правка ───────────────────────────────────────────────────────────────────────────────

    /**
     * Исправить у студента ФИО (опечатка) и группу (выбрал не ту). Строка для показа собирается так же,
     * как при регистрации: «Иванов И.И. (гр. 321702)» — по ней же подбираются преподаватели группы.
     * Данные преподавателя не меняются: администратор их только просматривает.
     */
    public Person update(long id, String rawName, String rawGroup) {
        User user = users.findById(id).orElseThrow(NotFound::new);
        if (!STUDENT.equals(user.getRole())) throw new NotAStudent("Изменить можно только данные студента");
        String name = rawName == null ? "" : rawName.strip().replaceAll("\\s+", " ");
        if (!NAME.matcher(name).matches()) {
            throw new Invalid("name", "ФИО — буквы, пробелы, точки и дефисы, с заглавной");
        }
        String group = rawGroup == null ? "" : rawGroup.strip();
        if (!calendar.isPossibleGroup(group)) throw new Invalid("group", groupHint());
        user.setFullName(name + " (гр. " + group + ")");
        UploadController.setNameParts(user, name, group);
        users.save(user);
        return person(user);
    }

    /** Подсказка для поля группы: какие первые цифры возможны в этом учебном году. */
    public String groupHint() {
        List<Integer> digits = calendar.groupYearDigits();
        StringBuilder text = new StringBuilder("Номер группы — шесть цифр, первая — ");
        for (int i = 0; i < digits.size(); i++) {
            if (i > 0) text.append(i == digits.size() - 1 ? " или " : ", ");
            text.append(digits.get(i));
        }
        return text.toString();
    }

    // ── назначение администратором ───────────────────────────────────────────────────────────

    /**
     * Студент, который зарегистрировался, чтобы стать администратором: роль ADMIN, учебные данные удаляются —
     * работы с файлами и пометками преподавателя, группа. Работы исчезают и у преподавателя, его уведомления
     * о них удаляются, как при отзыве работы. ФИО остаётся — для экрана настроек.
     */
    public int makeAdmin(long id, long adminId) {
        if (id == adminId) throw new NotAStudent("Вы уже администратор");
        User user = users.findById(id).orElseThrow(NotFound::new);
        if (!STUDENT.equals(user.getRole())) throw new NotAStudent("Администратором можно сделать только студента");

        List<Submission> works = submissions.findByStudent_TelegramIdOrderByCreatedAtDesc(id);
        for (Submission s : works) {
            if (s.getTeacher() != null && s.getTeacherMessageId() != null) {
                bot.deleteMessage(s.getTeacher().getTelegramId(), s.getTeacherMessageId());
            }
            reviewFiles.deleteFor(s.getId());
            submissions.deleteById(s.getId());
            deleteStoredFile(s);
        }

        String name = displayName(user);
        user.setRole(ADMIN);
        user.setFullName(name.isEmpty() ? null : name);
        user.setGroupNumber(null);
        user.setTeacherGroups(null);
        user.setEmail(null);
        user.setUrlId(null);
        users.save(user);
        log.info("Администратор {} назначил администратором пользователя {}; удалено работ: {}", adminId, id, works.size());
        return works.size();
    }

    private void deleteStoredFile(Submission s) {
        Optional.ofNullable(s.getStorageKey()).ifPresent(key -> {
            try {
                fileStorage.delete(key);
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Не удалось удалить файл работы #{}: {}", s.getId(), e.getMessage());
            }
        });
    }
}
