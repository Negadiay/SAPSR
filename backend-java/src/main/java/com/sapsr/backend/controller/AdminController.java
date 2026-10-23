package com.sapsr.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.academic.AcademicCalendar.Half;
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
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Экран администратора (решение 11): то, что сервер определяет сам — половина года, курсы групп, даты
 * опроцентовок, — администратор видит и исправляет в приложении, не заходя в код.
 *
 * Роль ADMIN не выдаётся регистрацией: её записывают в базу вручную по Telegram ID (README, «Администратор»).
 * Работы администратор не проверяет. Методы — docs/contracts/api-v1.md, раздел 14.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public static final String ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AcademicCalendar calendar;
    private final UserRepository users;
    private final GroupCourseOverrideRepository overrides;

    public AdminController(AcademicCalendar calendar, UserRepository users, GroupCourseOverrideRepository overrides) {
        this.calendar = calendar;
        this.users = users;
        this.overrides = overrides;
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

    /** {"1": "2026-10-01", "2": "2026-10-29", "3": null} — даты опроцентовок текущей половины года. */
    @PutMapping("/calendar/procentovki")
    public ResponseEntity<?> setProcentovki(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        Map<Integer, LocalDate> dates = new TreeMap<>();
        for (int stage = 1; stage <= 3; stage++) {
            Object raw = body.get(String.valueOf(stage));
            if (raw == null || String.valueOf(raw).isBlank()) continue;
            try {
                dates.put(stage, LocalDate.parse(String.valueOf(raw)));
            } catch (DateTimeParseException e) {
                return invalid(String.valueOf(stage), "Дата опроцентовки — в виде ГГГГ-ММ-ДД");
            }
        }
        List<LocalDate> ordered = new ArrayList<>(dates.values());
        for (int i = 1; i < ordered.size(); i++) {
            if (!ordered.get(i).isAfter(ordered.get(i - 1))) {
                return invalid("procentovki", "Опроцентовки идут по порядку: каждая следующая позже предыдущей");
            }
        }
        calendar.setProcentovkaDates(dates, adminId(request));
        log.info("Администратор {}: даты опроцентовок {}", adminId(request), dates);
        return ResponseEntity.ok(view());
    }

    /** {"course": 1..4} — курс группы вместо определённого по номеру (на текущий учебный год). */
    @PutMapping("/groups/{group}/course")
    public ResponseEntity<?> setCourse(@PathVariable String group, @RequestBody Map<String, Object> body,
                                       HttpServletRequest request) {
        Optional<ResponseEntity<?>> denied = deny(request);
        if (denied.isPresent()) return denied.get();
        if (!group.matches("\\d{6}")) return invalid("group", "Номер группы — шесть цифр");
        Object raw = body.get("course");
        int course;
        try {
            course = Integer.parseInt(String.valueOf(raw));
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

    // ── ответ ─────────────────────────────────────────────────────────────────────────────────

    /** Всё, что показывает экран администратора; после изменения возвращается он же. */
    Map<String, Object> view() {
        Half half = calendar.half();
        Half halfByDate = calendar.halfByDate();
        int year = calendar.academicYear();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("today", calendar.today().toString());
        view.put("academic_year", year);
        view.put("academic_year_title", year + "/" + (year + 1));
        view.put("half", half.name());
        view.put("half_by_date", halfByDate.name());
        view.put("half_overridden", half != halfByDate);

        Map<Integer, LocalDate> dates = calendar.procentovkaDates();
        List<Map<String, Object>> procentovki = new ArrayList<>();
        for (int stage = 1; stage <= 3; stage++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("date", dates.containsKey(stage) ? dates.get(stage).toString() : null);
            procentovki.add(row);
        }
        view.put("procentovki", procentovki);
        view.put("current_stage", AcademicCalendar.stageByDates(calendar.today(), dates).orElse(null));
        view.put("max_course", AcademicCalendar.MAX_COURSE);
        view.put("groups", groups(year, half));
        return view;
    }

    /** Группы, которые знает система: студентов, преподавателей и исправленные администратором. */
    private List<Map<String, Object>> groups(int year, Half half) {
        Map<String, long[]> counts = new TreeMap<>();  // группа → [студентов, преподавателей]
        for (Object[] row : users.countStudentsByGroup()) {
            counts.computeIfAbsent((String) row[0], g -> new long[2])[0] = ((Number) row[1]).longValue();
        }
        for (User teacher : users.findByRole("TEACHER")) {
            for (String group : teacherGroups(teacher)) counts.computeIfAbsent(group, g -> new long[2])[1]++;
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
            row.put("semester", course == null ? null : AcademicCalendar.semester(course, half));
            row.put("students", e.getValue()[0]);
            row.put("teachers", e.getValue()[1]);
            groups.add(row);
        }
        return groups;
    }

    private static List<String> teacherGroups(User teacher) {
        if (teacher.getTeacherGroups() == null || teacher.getTeacherGroups().isBlank()) return List.of();
        try {
            List<String> groups = new ArrayList<>();
            for (JsonNode g : JSON.readTree(teacher.getTeacherGroups())) {
                if (g.asText("").matches("\\d{6}")) groups.add(g.asText());
            }
            return groups;
        } catch (Exception e) {
            return List.of();
        }
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

    private static ResponseEntity<?> invalid(String field, String message) {
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "field", field, "error", message));
    }
}
