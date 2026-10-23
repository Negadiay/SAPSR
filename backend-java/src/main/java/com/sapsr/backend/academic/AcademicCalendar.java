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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Учебный календарь (решение 11): курс группы, половина учебного года, семестр и этап опроцентовки.
 * Студент их не выбирает — их определяет сервер, а администратор исправляет то, что определилось неверно.
 *
 * <ul>
 *   <li>Курс — по первой цифре номера группы: это последняя цифра года поступления (2023 → 321702,
 *       2026 → 621701). Сверено 23.10.2026 со всеми 430 группами ИИС — расхождений нет.</li>
 *   <li>Половина года — по дате: с 1 августа по 15 января — осенняя (нечётный семестр), иначе весенняя.</li>
 *   <li>Семестр — 2 × курс − 1 осенью, 2 × курс весной.</li>
 *   <li>Этап — ближайшая не прошедшая опроцентовка по датам, которые ввёл администратор; после последней —
 *       итоговая сдача (этапа нет, проверяются все требования семестра).</li>
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

    public enum Half {
        AUTUMN("осенний"), SPRING("весенний");

        private final String title;

        Half(String title) { this.title = title; }

        public String title() { return title; }
    }

    /** Что сервер знает о работе студента группы: курс, семестр и этап могут быть неизвестны (null). */
    public record Term(Integer course, Integer semester, Integer stage, boolean courseOverridden) {}

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

    /**
     * Учебный год — год его начала: 2026 — это 2026/2027. Весна всегда относится к году, начавшемуся
     * прошлой осенью — и тогда, когда администратор продлил весну на август.
     */
    public static int academicYear(LocalDate date, Half half) {
        if (half == Half.SPRING) return date.getYear() - 1;
        return date.getMonthValue() >= Month.AUGUST.getValue() ? date.getYear() : date.getYear() - 1;
    }

    /** Курс по номеру группы; пусто, если номер не из шести цифр или курс получается больше четвёртого. */
    public static Optional<Integer> courseByGroup(String group, int academicYear) {
        if (group == null || !group.matches("\\d{6}")) return Optional.empty();
        int yearDigit = group.charAt(0) - '0';
        int course = Math.floorMod(academicYear - yearDigit, 10) + 1;
        return course <= MAX_COURSE ? Optional.of(course) : Optional.empty();
    }

    public static int semester(int course, Half half) {
        return half == Half.AUTUMN ? 2 * course - 1 : 2 * course;
    }

    /** Ближайшая не прошедшая опроцентовка (в день опроцентовки — она сама); после всех — пусто. */
    public static Optional<Integer> stageByDates(LocalDate today, Map<Integer, LocalDate> dates) {
        return dates.entrySet().stream()
                .filter(e -> e.getValue() != null && !today.isAfter(e.getValue()))
                .min(Map.Entry.<Integer, LocalDate>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey);
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
        return academicYear(today(), half());
    }

    public Optional<Integer> courseOverride(String group) {
        if (group == null) return Optional.empty();
        return overrides.findByGroupNumberAndAcademicYear(group, academicYear()).map(GroupCourseOverride::getCourse);
    }

    public Optional<Integer> course(String group) {
        return courseOverride(group).or(() -> courseByGroup(group, academicYear()));
    }

    /** Даты опроцентовок текущей половины года: номер → дата (только введённые). */
    public Map<Integer, LocalDate> procentovkaDates() {
        Map<Integer, LocalDate> dates = new TreeMap<>();
        for (ProcentovkaDate d : procentovki.findByAcademicYearAndHalfOrderByStageAsc(academicYear(), half().name())) {
            dates.put(d.getStage(), d.getHeldOn());
        }
        return dates;
    }

    public Optional<Integer> stage() {
        return stageByDates(today(), procentovkaDates());
    }

    public Term termFor(String group) {
        Half half = half();
        Optional<Integer> override = courseOverride(group);
        Optional<Integer> course = override.or(() -> courseByGroup(group, academicYear()));
        return new Term(course.orElse(null), course.map(c -> semester(c, half)).orElse(null),
                stage().orElse(null), override.isPresent());
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

    /** Даты опроцентовок текущей половины года; отсутствующий номер — дата снята. */
    public void setProcentovkaDates(Map<Integer, LocalDate> dates, Long adminId) {
        int year = academicYear();
        String half = half().name();
        LocalDateTime now = LocalDateTime.now(clock);
        for (int stage = 1; stage <= 3; stage++) {
            LocalDate date = dates.get(stage);
            ProcentovkaDate.Key key = new ProcentovkaDate.Key(year, half, stage);
            if (date == null) procentovki.deleteById(key);
            else procentovki.save(new ProcentovkaDate(year, half, stage, date, now, adminId));
        }
    }
}
