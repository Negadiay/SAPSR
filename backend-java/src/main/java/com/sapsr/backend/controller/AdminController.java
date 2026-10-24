package com.sapsr.backend.controller;

import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.academic.AcademicCalendar.Half;
import com.sapsr.backend.academic.AcademicCalendar.Schedule;
import com.sapsr.backend.admin.AdminUsers;
import com.sapsr.backend.entity.GroupCourseOverride;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Экран администратора (решение 11): то, что сервер определяет сам — половина года, курсы групп, сроки
 * по курсам, — администратор видит и исправляет в приложении, не заходя в код; там же исправляет ФИО и
 * группы пользователей и назначает администраторов из студентов.
 *
 * Первого администратора записывают в базу вручную по Telegram ID (README, «Администратор»); регистрацией
 * роль не получить. Работы администратор не проверяет. Методы — docs/contracts/api-v1.md, раздел 14.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public static final String ROLE = AdminUsers.ADMIN;

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Ключи сроков в запросах и ответах: опроцентовки 1–3 и срок сдачи. */
    private static final List<String> DATE_KEYS = List.of("1", "2", "3", "deadline");

    private final AcademicCalendar calendar;
    private final UserRepository users;
    private final GroupCourseOverrideRepository overrides;
    private final AdminUsers adminUsers;

    public AdminController(AcademicCalendar calendar, UserRepository users, GroupCourseOverrideRepository overrides,
                           AdminUsers adminUsers) {
        this.calendar = calendar;
        this.users = users;
        this.overrides = overrides;
        this.adminUsers = adminUsers;
    }

    @GetMapping("/calendar")
    public ResponseEntity<?> getCalendar(HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        return denied.orElseGet(() -> ResponseEntity.ok(view()));
    }

    /** {"half": "AUTUMN" | "SPRING" | "AUTO"} — AUTO: снова по дате. */
    @PutMapping("/calendar/half")
    public ResponseEntity<?> setHalf(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        String value = String.valueOf(body.getOrDefault("half", "")).toUpperCase(Locale.ROOT);
        Half half;
        try {
            half = "AUTO".equals(value) ? null : Half.valueOf(value);
        } catch (IllegalArgumentException e) {
            return invalid("half", "half: AUTUMN, SPRING или AUTO");
        }
        calendar.setHalf(half, adminId(request));
        log.info("Администратор {}: половина года — {}", adminId(request), half == null ? "по дате" : half);
        return ResponseEntity.ok(view());
    }

    /**
     * {"courses": [1, 2, 3, 4], "dates": {"1": "2026-10-01", "2": "2026-10-29", "3": null, "deadline": "2026-12-26"}} —
     * сроки текущей половины года для перечисленных курсов; null — дата снята.
     */
    @PutMapping("/calendar/schedule")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> setSchedule(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();

        Set<Integer> courses = new TreeSet<>();
        if (body.get("courses") instanceof List<?> list) {
            for (Object c : list) {
                try {
                    courses.add(Integer.parseInt(String.valueOf(c)));
                } catch (NumberFormatException e) {
                    return invalid("courses", "Курсы — числа от 1 до " + AcademicCalendar.MAX_COURSE);
                }
            }
        }
        if (courses.isEmpty() || courses.stream().anyMatch(c -> c < 1 || c > AcademicCalendar.MAX_COURSE)) {
            return invalid("courses", "Курсы — числа от 1 до " + AcademicCalendar.MAX_COURSE);
        }

        Map<String, Object> raw = body.get("dates") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        AcademicCalendar.Window window = calendar.window();
        Map<Integer, LocalDate> dates = new TreeMap<>();
        LocalDate previous = null;
        for (int i = 0; i < DATE_KEYS.size(); i++) {
            String key = DATE_KEYS.get(i);
            Object value = raw.get(key);
            if (value == null || String.valueOf(value).isBlank()) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(String.valueOf(value));
            } catch (DateTimeParseException e) {
                return invalid(key, "Дата — в виде ГГГГ-ММ-ДД");
            }
            if (!window.contains(date)) {
                return invalid(key, "Дата вне семестра: " + DAY.format(window.from()) + " – " + DAY.format(window.to()));
            }
            if (previous != null && !date.isAfter(previous)) {
                return invalid(key, "Сроки идут по порядку: каждый следующий позже предыдущего");
            }
            previous = date;
            dates.put(i + 1, date);
        }
        calendar.setSchedule(courses, dates, adminId(request));
        log.info("Администратор {}: сроки курсов {} — {}", adminId(request), courses, dates);
        return ResponseEntity.ok(view());
    }

    /** {"course": 1..4} — курс группы вместо определённого по номеру (на текущий учебный год). */
    @PutMapping("/groups/{group}/course")
    public ResponseEntity<?> setCourse(@PathVariable String group, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        if (!calendar.isPossibleGroup(group)) return invalid("group", adminUsers.groupHint());
        int course;
        try {
            course = Integer.parseInt(String.valueOf(body.get("course")));
        } catch (NumberFormatException e) {
            return invalid("course", "Курс — число от 1 до " + AcademicCalendar.MAX_COURSE);
        }
        if (course < 1 || course > AcademicCalendar.MAX_COURSE) {
            return invalid("course", "Курс — число от 1 до " + AcademicCalendar.MAX_COURSE);
        }
        calendar.setCourse(group, course, adminId(request));
        log.info("Администратор {}: группа {} — {} курс", adminId(request), group, course);
        return ResponseEntity.ok(view());
    }

    /** Снова по номеру группы. */
    @DeleteMapping("/groups/{group}/course")
    public ResponseEntity<?> resetCourse(@PathVariable String group, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        if (!group.matches("\\d{6}")) return invalid("group", "Номер группы — шесть цифр");
        calendar.setCourse(group, null, adminId(request));
        log.info("Администратор {}: группа {} — курс по номеру", adminId(request), group);
        return ResponseEntity.ok(view());
    }

    /** Группу, добавленную вручную, — убрать. Только пустую: студенты и преподаватели держат группу в списке сами. */
    @DeleteMapping("/groups/{group}")
    public ResponseEntity<?> deleteGroup(@PathVariable String group, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        if (!group.matches("\\d{6}")) return invalid("group", "Номер группы — шесть цифр");
        boolean added = overrides.findByAcademicYear(calendar.academicYear()).stream()
                .anyMatch(o -> group.equals(o.getGroupNumber()));
        if (!added) {
            return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND",
                    "error", "Группы " + group + " нет среди добавленных вручную"));
        }
        if (!adminUsers.students(group).isEmpty() || !adminUsers.teachers(group).isEmpty()) {
            return ResponseEntity.status(409).body(Map.of("code", "GROUP_NOT_EMPTY",
                    "error", "В группе есть люди — удалить можно только пустую"));
        }
        calendar.setCourse(group, null, adminId(request));
        log.info("Администратор {}: удалена группа {}", adminId(request), group);
        return ResponseEntity.ok(view());
    }

    /** Студенты группы и её преподаватели — чтобы исправить ФИО или группу студента без похода в базу. */
    @GetMapping("/groups/{group}/people")
    public ResponseEntity<?> getPeople(@PathVariable String group, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        if (!group.matches("\\d{6}")) return invalid("group", "Номер группы — шесть цифр");
        return ResponseEntity.ok(people(group));
    }

    /** {"name": "Терешкова А.В.", "group": "321702"} — только студента. Ответ — люди прежней группы. */
    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable long id, @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        String before = users.findById(id).map(User::getGroupNumber).orElse(null);
        try {
            adminUsers.update(id, text(body.get("name")), text(body.get("group")));
        } catch (AdminUsers.Invalid e) {
            return invalid(e.field(), e.getMessage());
        } catch (AdminUsers.NotAStudent e) {
            return notAStudent(e);
        } catch (AdminUsers.NotFound e) {
            return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND", "error", e.getMessage()));
        }
        log.info("Администратор {}: исправлены данные пользователя {}", adminId(request), id);
        Map<String, Object> result = new LinkedHashMap<>(view());
        if (before != null) result.put("people", people(before));
        return ResponseEntity.ok(result);
    }

    /** Студента — в администраторы; его учебные данные удаляются (интерфейс спрашивает подтверждение). */
    @PostMapping("/users/{id}/admin")
    public ResponseEntity<?> makeAdmin(@PathVariable long id, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        try {
            adminUsers.makeAdmin(id, adminId(request));
        } catch (AdminUsers.NotAStudent e) {
            return notAStudent(e);
        } catch (AdminUsers.NotFound e) {
            return ResponseEntity.status(404).body(Map.of("code", "NOT_FOUND", "error", e.getMessage()));
        }
        return ResponseEntity.ok(view());
    }

    // ── ответ ─────────────────────────────────────────────────────────────────────────────────

    /** Всё, что показывает экран администратора; после изменения возвращается он же. */
    Map<String, Object> view() {
        Half half = calendar.half();
        Half halfByDate = calendar.halfByDate();
        int year = calendar.academicYear();
        LocalDate today = calendar.today();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("today", today.toString());
        view.put("academic_year", year);
        view.put("academic_year_title", year + "/" + (year + 1));
        view.put("half", half.name());
        view.put("half_by_date", halfByDate.name());
        view.put("half_overridden", half != halfByDate);
        view.put("max_course", AcademicCalendar.MAX_COURSE);
        view.put("group_digits", AcademicCalendar.groupYearDigits(year));
        AcademicCalendar.Window window = calendar.window();
        view.put("date_window", Map.of("from", window.from().toString(), "to", window.to().toString()));

        List<Map<String, Object>> schedule = new ArrayList<>();
        for (Map.Entry<Integer, Schedule> e : calendar.schedules().entrySet()) {
            Map<String, Object> dates = new LinkedHashMap<>();
            for (int stage = 1; stage <= 3; stage++) {
                LocalDate date = e.getValue().procentovki().get(stage);
                dates.put(String.valueOf(stage), date == null ? null : date.toString());
            }
            dates.put("deadline", e.getValue().deadline() == null ? null : e.getValue().deadline().toString());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("course", e.getKey());
            row.put("semester", AcademicCalendar.semester(e.getKey(), half));
            row.put("dates", dates);
            row.put("current_stage", AcademicCalendar.stageByDates(today, e.getValue().procentovki()).orElse(null));
            schedule.add(row);
        }
        view.put("schedule", schedule);
        view.put("groups", groups(year, half));
        return view;
    }

    /** Группы, которые знает система: студентов, преподавателей и исправленные администратором. */
    private List<Map<String, Object>> groups(int year, Half half) {
        Map<String, long[]> counts = new TreeMap<>();  // группа → [студентов, преподавателей]
        for (Object[] row : users.countStudentsByGroup()) {
            counts.computeIfAbsent((String) row[0], g -> new long[2])[0] = ((Number) row[1]).longValue();
        }
        for (User teacher : users.findByRole(AdminUsers.TEACHER)) {
            for (String group : AdminUsers.teacherGroups(teacher)) counts.computeIfAbsent(group, g -> new long[2])[1]++;
        }
        Map<String, Integer> overridden = new HashMap<>();
        for (GroupCourseOverride o : overrides.findByAcademicYear(year)) {
            overridden.put(o.getGroupNumber(), o.getCourse());
            counts.computeIfAbsent(o.getGroupNumber(), g -> new long[2]);
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            String group = e.getKey();
            Integer auto = AcademicCalendar.courseByGroup(group, year).orElse(null);
            Integer course = overridden.getOrDefault(group, auto);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", group);
            row.put("course", course);
            row.put("course_by_number", auto);
            row.put("course_overridden", overridden.containsKey(group));
            // Добавлена администратором и пуста: в списке держится только его записью — её можно удалить.
            row.put("manual", overridden.containsKey(group) && e.getValue()[0] == 0 && e.getValue()[1] == 0);
            row.put("semester", course == null ? null : AcademicCalendar.semester(course, half));
            row.put("students", e.getValue()[0]);
            row.put("teachers", e.getValue()[1]);
            groups.add(row);
        }
        return groups;
    }

    private Map<String, Object> people(String group) {
        Map<String, Object> people = new LinkedHashMap<>();
        people.put("group", group);
        people.put("students", adminUsers.students(group));
        people.put("teachers", adminUsers.teachers(group));
        return people;
    }

    // ── доступ ────────────────────────────────────────────────────────────────────────────────

    private Optional<ResponseEntity<?>> deny(HttpServletRequest request) {
        Long telegramId = (Long) request.getAttribute("telegram_id");
        if (telegramId == null) {
            return Optional.of(ResponseEntity.status(401).body(Map.of("code", "UNAUTHORIZED", "error", "Не авторизован")));
        }
        boolean admin = users.findById(telegramId).map(u -> ROLE.equals(u.getRole())).orElse(false);
        if (!admin) {
            return Optional.of(ResponseEntity.status(403).body(Map.of("code", "FORBIDDEN",
                    "error", "Раздел только для администратора")));
        }
        return Optional.empty();
    }

    private static Long adminId(HttpServletRequest request) {
        return (Long) request.getAttribute("telegram_id");
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static ResponseEntity<?> notAStudent(AdminUsers.NotAStudent e) {
        return ResponseEntity.status(409).body(Map.of("code", "NOT_A_STUDENT", "error", e.getMessage()));
    }

    private static ResponseEntity<?> invalid(String field, String message) {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "field", field, "error", message));
    }
}
