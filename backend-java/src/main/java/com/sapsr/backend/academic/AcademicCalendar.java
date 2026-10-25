package com.sapsr.backend.academic;

import com.sapsr.backend.entity.AcademicSetting;
import com.sapsr.backend.entity.GroupCourseOverride;
import com.sapsr.backend.entity.ProcentovkaDate;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * Учебный календарь (решение 11): курс группы, половина учебного года, семестр, этап опроцентовки и срок сдачи.
 * Студент их не выбирает — их определяет сервер, а администратор исправляет то, что определилось неверно.
 *
 * <ul>
 *   <li>Учебный год — только по дате: с 1 августа начинается следующий. Половина года, заданная вручную,
 *       меняет семестр внутри того же учебного года и курсы не сдвигает: весна, выбранная в октябре, —
 *       весна 2026/2027, 4 курс остаётся четвёртым (8 семестр).</li>
 *   <li>Курс — по первой цифре номера группы: это последняя цифра года поступления (2023 → 321702,
 *       2026 → 621701). Сверено 23.10.2026 со всеми 430 группами ИИС — расхождений нет.</li>
 *   <li>Половина года — по дате, по Минску: с 1 августа по 15 января — осенняя (нечётный семестр), иначе весенняя.</li>
 *   <li>Семестр — 2 × курс − 1 осенью, 2 × курс весной.</li>
 *   <li>Сроки — у каждого курса свои (у 3 курса весной год короче из-за практики): даты 1–3-й опроцентовок
 *       и срок сдачи. Этап — ближайшая не прошедшая опроцентовка курса; после последней — итоговая сдача
 *       (этапа нет, проверяются все требования семестра).</li>
 * </ul>
 */
@Service
public class AcademicCalendar {

    /** Даты — по времени Минска, а не сервера: граница семестра и опроцентовки — в полночь по Минску. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Minsk");
    /** В БГУИР курсов не больше четырёх. */
    public static final int MAX_COURSE = 4;
    /** Последний день осенней половины — 15 января: середина января, конец зимней сессии. */
    static final int AUTUMN_LAST_JANUARY_DAY = 15;

    public enum Half { AUTUMN, SPRING }

    /** Сроки курса в половине года: опроцентовки 1–3 (только введённые) и срок сдачи работы. */
    public record Schedule(Map<Integer, LocalDate> procentovki, LocalDate deadline) {
        static final Schedule EMPTY = new Schedule(Map.of(), null);
    }

    /** Что сервер знает о работе студента группы: курс, семестр, этап и срок могут быть неизвестны (null). */
    public record Term(Integer course, Integer semester, Integer stage, LocalDate deadline, boolean courseOverridden) {}

    /** Какие даты сроков допустимы в половине года — от этого ловятся опечатки в годе. */
    public record Window(LocalDate from, LocalDate to) {
        public boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    private final AcademicSettingRepository settings;
    private final GroupCourseOverrideRepository overrides;
    private final ProcentovkaDateRepository procentovki;
    private final Clock clock;

    @Autowired
    public AcademicCalendar(AcademicSettingRepository settings, GroupCourseOverrideRepository overrides,
                            ProcentovkaDateRepository procentovki) {
        this(settings, overrides, procentovki, Clock.system(ZONE));
    }

    public AcademicCalendar(AcademicSettingRepository settings, GroupCourseOverrideRepository overrides,
                            ProcentovkaDateRepository procentovki, Clock clock) {
        this.settings = settings;
        this.overrides = overrides;
        this.procentovki = procentovki;
        this.clock = clock;
    }

    // ── правила без базы ──────────────────────────────────────────────────────────────────────

    public static Half halfByDate(LocalDate date) {
        if (date.getMonthValue() >= Month.AUGUST.getValue()) return Half.AUTUMN;
        if (date.getMonth() == Month.JANUARY && date.getDayOfMonth() <= AUTUMN_LAST_JANUARY_DAY) return Half.AUTUMN;
        return Half.SPRING;
    }

    /** Учебный год — год его начала: 2026 — это 2026/2027. Только по дате, ручная половина года на него не влияет. */
    public static int academicYear(LocalDate date) {
        return date.getMonthValue() >= Month.AUGUST.getValue() ? date.getYear() : date.getYear() - 1;
    }

    /** Курс по номеру группы; пусто, если номер не из шести цифр или курс получается больше четвёртого. */
    public static Optional<Integer> courseByGroup(String group, int academicYear) {
        if (group == null || !group.matches("\\d{6}")) return Optional.empty();
        int yearDigit = group.charAt(0) - '0';
        int course = Math.floorMod(academicYear - yearDigit, 10) + 1;
        return course <= MAX_COURSE ? Optional.of(course) : Optional.empty();
    }

    /**
     * Первые цифры номеров групп, возможные в этом учебном году: курс c — поступившие в год academicYear − c + 1.
     * В 2026/2027 — 3, 4, 5, 6; в 2027/2028 — 4, 5, 6, 7.
     */
    public static List<Integer> groupYearDigits(int academicYear) {
        return IntStream.rangeClosed(1, MAX_COURSE)
                .map(course -> Math.floorMod(academicYear - course + 1, 10))
                .sorted().boxed().toList();
    }

    public static int semester(int course, Half half) {
        return half == Half.AUTUMN ? 2 * course - 1 : 2 * course;
    }

    /** Ближайшая не прошедшая опроцентовка (в день опроцентовки — она сама); после всех — пусто. */
    public static Optional<Integer> stageByDates(LocalDate today, Map<Integer, LocalDate> dates) {
        return dates.entrySet().stream()
                .filter(e -> e.getKey() >= 1 && e.getKey() <= 3)
                .filter(e -> e.getValue() != null && !today.isAfter(e.getValue()))
                .min(Map.Entry.<Integer, LocalDate>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
    }

    /** Осенью — с 1 августа по 31 января, весной — с 16 января по 31 июля следующего календарного года. */
    public static Window window(int academicYear, Half half) {
        return half == Half.AUTUMN
                ? new Window(LocalDate.of(academicYear, 8, 1), LocalDate.of(academicYear + 1, 1, 31))
                : new Window(LocalDate.of(academicYear + 1, 1, AUTUMN_LAST_JANUARY_DAY + 1),
                             LocalDate.of(academicYear + 1, 7, 31));
    }

    // ── с исправлениями администратора ────────────────────────────────────────────────────────

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    public Half halfByDate() {
        return halfByDate(today());
    }

    /**
     * Половина года, заданная администратором. Как только дата её догоняет, она больше не нужна и снимается:
     * иначе забытое «весенний» в январе осталось бы и следующей осенью.
     */
    public Optional<Half> halfOverride() {
        Optional<Half> override = settings.findById(AcademicSetting.HALF).map(s -> Half.valueOf(s.getValue()));
        if (override.isPresent() && override.get() == halfByDate()) {
            settings.deleteById(AcademicSetting.HALF);
            return Optional.empty();
        }
        return override;
    }

    public Half half() {
        return halfOverride().orElseGet(this::halfByDate);
    }

    public int academicYear() {
        return academicYear(today());
    }

    public List<Integer> groupYearDigits() {
        return groupYearDigits(academicYear());
    }

    /** Номер группы из шести цифр, возможный в этом учебном году (первая цифра — год поступления курсов 1–4). */
    public boolean isPossibleGroup(String group) {
        return courseByGroup(group, academicYear()).isPresent();
    }

    public Window window() {
        return window(academicYear(), half());
    }

    public Optional<Integer> courseOverride(String group) {
        if (group == null) return Optional.empty();
        return overrides.findByGroupNumberAndAcademicYear(group, academicYear()).map(GroupCourseOverride::getCourse);
    }

    /** Сроки всех курсов текущей половины года; у курса без введённых дат — пустые. */
    public Map<Integer, Schedule> schedules() {
        Map<Integer, Map<Integer, LocalDate>> procentovkiByCourse = new TreeMap<>();
        Map<Integer, LocalDate> deadlines = new TreeMap<>();
        for (ProcentovkaDate d : procentovki.findByAcademicYearAndHalf(academicYear(), half().name())) {
            if (d.getStage() == ProcentovkaDate.DEADLINE) deadlines.put(d.getCourse(), d.getHeldOn());
            else procentovkiByCourse.computeIfAbsent(d.getCourse(), c -> new TreeMap<>()).put(d.getStage(), d.getHeldOn());
        }
        Map<Integer, Schedule> result = new TreeMap<>();
        for (int course = 1; course <= MAX_COURSE; course++) {
            result.put(course, new Schedule(procentovkiByCourse.getOrDefault(course, Map.of()), deadlines.get(course)));
        }
        return result;
    }

    public Schedule schedule(Integer course) {
        return course == null ? Schedule.EMPTY : schedules().getOrDefault(course, Schedule.EMPTY);
    }

    public Term termFor(String group) {
        Half half = half();
        Optional<Integer> override = courseOverride(group);
        Optional<Integer> course = override.or(() -> courseByGroup(group, academicYear()));
        Schedule schedule = schedule(course.orElse(null));
        return new Term(course.orElse(null), course.map(c -> semester(c, half)).orElse(null),
                stageByDates(today(), schedule.procentovki()).orElse(null), schedule.deadline(), override.isPresent());
    }

    // ── изменения (AdminController) ──────────────────────────────────────────────────────────

    /** null — снова по дате. */
    public void setHalf(Half half, Long adminId) {
        if (half == null || half == halfByDate()) {
            settings.deleteById(AcademicSetting.HALF);
            return;
        }
        settings.save(new AcademicSetting(AcademicSetting.HALF, half.name(), LocalDateTime.now(clock), adminId));
    }

    /** null — снова по номеру группы. */
    public void setCourse(String group, Integer course, Long adminId) {
        int year = academicYear();
        GroupCourseOverride.Key key = new GroupCourseOverride.Key(group, year);
        if (course == null) {
            overrides.deleteById(key);
            return;
        }
        overrides.save(new GroupCourseOverride(group, year, course, LocalDateTime.now(clock), adminId));
    }

    /**
     * Сроки текущей половины года для перечисленных курсов: опроцентовки 1–3 и срок сдачи ({@link ProcentovkaDate#DEADLINE}).
     * Отсутствующий номер — дата снята.
     */
    public void setSchedule(Collection<Integer> courses, Map<Integer, LocalDate> dates, Long adminId) {
        int year = academicYear();
        String half = half().name();
        LocalDateTime now = LocalDateTime.now(clock);
        for (int course : courses) {
            for (int stage = 1; stage <= ProcentovkaDate.DEADLINE; stage++) {
                LocalDate date = dates.get(stage);
                if (date == null) procentovki.deleteById(new ProcentovkaDate.Key(year, half, course, stage));
                else procentovki.save(new ProcentovkaDate(year, half, course, stage, date, now, adminId));
            }
        }
    }
}
