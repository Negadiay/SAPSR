package com.sapsr.backend.academic;

import com.sapsr.backend.academic.AcademicCalendar.Half;
import com.sapsr.backend.entity.AcademicSetting;
import com.sapsr.backend.entity.GroupCourseOverride;
import com.sapsr.backend.entity.ProcentovkaDate;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Курс, половина года, семестр и этап (решение 11). */
class AcademicCalendarTest {

    private final AcademicSettingRepository settings = mock(AcademicSettingRepository.class);
    private final GroupCourseOverrideRepository overrides = mock(GroupCourseOverrideRepository.class);
    private final ProcentovkaDateRepository procentovki = mock(ProcentovkaDateRepository.class);

    @ParameterizedTest(name = "{0} → {1}")
    @Tag("boundary")
    @CsvSource({
            "2026-08-01, AUTUMN", "2026-10-23, AUTUMN", "2026-12-31, AUTUMN",
            "2027-01-15, AUTUMN", "2027-01-16, SPRING", "2027-05-20, SPRING", "2027-07-31, SPRING",
    })
    void halfByDate(LocalDate date, Half half) {
        assertThat(AcademicCalendar.halfByDate(date)).isEqualTo(half);
    }

    @ParameterizedTest(name = "{0} в {1}/{1}+1 → {2} курс")
    @Tag("typical")
    @CsvSource({
            // Пример из задачи: поступили в 2023 — 321702, в 2026 — 621701.
            "321702, 2026, 4", "621701, 2026, 1", "451003, 2026, 3", "513803, 2026, 2",
            // Через год 3-й набор выпустился, а новый набор — 7xxxxx.
            "721701, 2027, 1", "421701, 2027, 4",
    })
    void courseByGroupNumber(String group, int academicYear, int course) {
        assertThat(AcademicCalendar.courseByGroup(group, academicYear)).contains(course);
    }

    @Test
    @Tag("negative")
    void noCourseBeyondFourthOrForBadNumber() {
        assertThat(AcademicCalendar.courseByGroup("721701", 2026)).as("пятого курса нет").isEmpty();
        assertThat(AcademicCalendar.courseByGroup("321702", 2027)).as("выпустились").isEmpty();
        assertThat(AcademicCalendar.courseByGroup("32170", 2026)).isEmpty();
        assertThat(AcademicCalendar.courseByGroup(null, 2026)).isEmpty();
    }

    @Test
    @Tag("typical")
    void semesterAndAcademicYear() {
        assertThat(AcademicCalendar.semester(4, Half.AUTUMN)).isEqualTo(7);
        assertThat(AcademicCalendar.semester(4, Half.SPRING)).isEqualTo(8);
        assertThat(AcademicCalendar.academicYear(LocalDate.of(2026, 10, 23), Half.AUTUMN)).isEqualTo(2026);
        assertThat(AcademicCalendar.academicYear(LocalDate.of(2027, 1, 10), Half.AUTUMN)).isEqualTo(2026);
        assertThat(AcademicCalendar.academicYear(LocalDate.of(2027, 3, 1), Half.SPRING)).isEqualTo(2026);
        assertThat(AcademicCalendar.academicYear(LocalDate.of(2027, 8, 10), Half.SPRING))
                .as("администратор продлил весну на август").isEqualTo(2026);
    }

    @Test
    @Tag("typical")
    void stageIsNearestProcentovkaNotYetPassed() {
        Map<Integer, LocalDate> dates = Map.of(1, LocalDate.of(2026, 10, 1), 2, LocalDate.of(2026, 10, 29),
                3, LocalDate.of(2026, 11, 26));
        assertThat(AcademicCalendar.stageByDates(LocalDate.of(2026, 9, 15), dates)).contains(1);
        assertThat(AcademicCalendar.stageByDates(LocalDate.of(2026, 10, 23), dates)).contains(2);
        assertThat(AcademicCalendar.stageByDates(LocalDate.of(2026, 10, 29), dates)).as("в день опроцентовки").contains(2);
        assertThat(AcademicCalendar.stageByDates(LocalDate.of(2026, 10, 30), dates)).contains(3);
        assertThat(AcademicCalendar.stageByDates(LocalDate.of(2026, 12, 20), dates)).as("итоговая сдача").isEmpty();
        assertThat(AcademicCalendar.stageByDates(LocalDate.of(2026, 10, 23), Map.of())).isEmpty();
    }

    @Test
    @Tag("typical")
    void termForStudentOfGroup() {
        AcademicCalendar calendar = calendar("2026-10-23T12:00");
        when(procentovki.findByAcademicYearAndHalfOrderByStageAsc(2026, "AUTUMN")).thenReturn(List.of(
                new ProcentovkaDate(2026, "AUTUMN", 2, LocalDate.of(2026, 10, 29), LocalDateTime.now(), 1L)));

        assertThat(calendar.termFor("321702")).isEqualTo(new AcademicCalendar.Term(4, 7, 2, false));
        assertThat(calendar.termFor(null)).isEqualTo(new AcademicCalendar.Term(null, null, 2, false));
    }

    @Test
    @Tag("boundary")
    void adminCorrectionsWin() {
        AcademicCalendar calendar = calendar("2026-10-23T12:00");
        when(settings.findById(AcademicSetting.HALF)).thenReturn(Optional.of(
                new AcademicSetting(AcademicSetting.HALF, "SPRING", LocalDateTime.now(), 1L)));
        when(overrides.findByGroupNumberAndAcademicYear("721701", 2025)).thenReturn(Optional.of(
                new GroupCourseOverride("721701", 2025, 2, LocalDateTime.now(), 1L)));

        // Весна, назначенная в октябре, относится к учебному году 2025/2026.
        assertThat(calendar.half()).isEqualTo(Half.SPRING);
        assertThat(calendar.academicYear()).isEqualTo(2025);
        assertThat(calendar.termFor("721701")).isEqualTo(new AcademicCalendar.Term(2, 4, null, true));
    }

    @Test
    @Tag("boundary")
    void manualHalfIsDroppedOnceTheDateCatchesUp() {
        // В январе администратор назначил весну раньше 16-го; 16-го дата её догнала — исправление больше не нужно.
        AcademicCalendar calendar = calendar("2027-01-16T00:30");
        when(settings.findById(AcademicSetting.HALF)).thenReturn(Optional.of(
                new AcademicSetting(AcademicSetting.HALF, "SPRING", LocalDateTime.now(), 1L)));

        assertThat(calendar.halfOverride()).isEmpty();
        assertThat(calendar.half()).isEqualTo(Half.SPRING);
        verify(settings, atLeastOnce()).deleteById(AcademicSetting.HALF);
    }

    @Test
    @Tag("typical")
    void settingHalfEqualToDateRemovesOverride() {
        AcademicCalendar calendar = calendar("2026-10-23T12:00");

        calendar.setHalf(Half.AUTUMN, 1L);
        calendar.setHalf(null, 1L);
        calendar.setHalf(Half.SPRING, 1L);

        verify(settings, times(2)).deleteById(AcademicSetting.HALF);
        verify(settings).save(any(AcademicSetting.class));
    }

    @Test
    @Tag("boundary")
    void dateBoundaryIsMinskMidnight() {
        // 15 января 22:30 по UTC — уже 16-е в Минске: весна.
        AcademicCalendar calendar = new AcademicCalendar(settings, overrides, procentovki,
                Clock.fixed(ZonedDateTime.parse("2027-01-15T22:30:00Z").toInstant(), java.time.ZoneOffset.UTC));
        assertThat(calendar.today()).isEqualTo(LocalDate.of(2027, 1, 16));
        assertThat(calendar.half()).isEqualTo(Half.SPRING);
    }

    private AcademicCalendar calendar(String minskTime) {
        ZonedDateTime at = LocalDateTime.parse(minskTime).atZone(AcademicCalendar.ZONE);
        return new AcademicCalendar(settings, overrides, procentovki, Clock.fixed(at.toInstant(), AcademicCalendar.ZONE));
    }
}
