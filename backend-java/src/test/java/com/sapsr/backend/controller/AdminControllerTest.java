package com.sapsr.backend.controller;

import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.GroupCourseOverride;
import com.sapsr.backend.entity.ProcentovkaDate;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import com.sapsr.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Экран администратора (решение 11): только роль ADMIN, курсы групп, половина года, опроцентовки. */
class AdminControllerTest {

    private static final long ADMIN_ID = 9001L;
    private static final long STUDENT_ID = 1001L;

    private final AcademicSettingRepository settings = mock(AcademicSettingRepository.class);
    private final GroupCourseOverrideRepository overrides = mock(GroupCourseOverrideRepository.class);
    private final ProcentovkaDateRepository procentovki = mock(ProcentovkaDateRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private AdminController controller;

    @BeforeEach
    void setUp() {
        LocalDateTime minskNoon = LocalDateTime.of(2026, 10, 23, 12, 0);
        Clock clock = Clock.fixed(minskNoon.atZone(AcademicCalendar.ZONE).toInstant(), AcademicCalendar.ZONE);
        controller = new AdminController(new AcademicCalendar(settings, overrides, procentovki, clock), users, overrides);
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, "ADMIN")));
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(user(STUDENT_ID, "STUDENT")));
    }

    @Test
    @Tag("negative")
    void onlyAdminSeesTheScreen() {
        assertThat(status(controller.getCalendar(as(STUDENT_ID)))).isEqualTo(403);
        assertThat(status(controller.getCalendar(new MockHttpServletRequest()))).isEqualTo(401);
        assertThat(status(controller.setCourse("321702", Map.of("course", 1), as(STUDENT_ID)))).isEqualTo(403);
        verify(overrides, never()).save(any());
    }

    @Test
    @Tag("typical")
    @SuppressWarnings("unchecked")
    void viewShowsCoursesOfKnownGroups() {
        when(users.countStudentsByGroup()).thenReturn(List.<Object[]>of(new Object[]{"321702", 3L},
                new Object[]{"621701", 1L}, new Object[]{"721701", 1L}));
        User teacher = user(2002L, "TEACHER");
        teacher.setTeacherGroups("[\"321702\",\"321703\"]");
        when(users.findByRole("TEACHER")).thenReturn(List.of(teacher));
        when(overrides.findByAcademicYear(2026)).thenReturn(List.of(
                new GroupCourseOverride("721701", 2026, 2, LocalDateTime.now(), ADMIN_ID)));
        when(procentovki.findByAcademicYearAndHalfOrderByStageAsc(2026, "AUTUMN")).thenReturn(List.of(
                new ProcentovkaDate(2026, "AUTUMN", 2, LocalDate.of(2026, 10, 29), LocalDateTime.now(), ADMIN_ID)));

        Map<String, Object> view = (Map<String, Object>) controller.getCalendar(as(ADMIN_ID)).getBody();

        assertThat(view).containsEntry("academic_year_title", "2026/2027").containsEntry("half", "AUTUMN")
                .containsEntry("half_overridden", false).containsEntry("current_stage", 2);
        List<Map<String, Object>> groups = (List<Map<String, Object>>) view.get("groups");
        assertThat(groups).extracting(g -> g.get("group")).containsExactly("321702", "321703", "621701", "721701");
        assertThat(groups.get(0)).containsEntry("course", 4).containsEntry("semester", 7)
                .containsEntry("students", 3L).containsEntry("teachers", 1L);
        assertThat(groups.get(3)).containsEntry("course", 2).containsEntry("course_by_number", null)
                .containsEntry("course_overridden", true).containsEntry("semester", 3);
    }

    @Test
    @Tag("typical")
    void setsCourseForCurrentAcademicYear() {
        ResponseEntity<?> response = controller.setCourse("721701", Map.of("course", 2), as(ADMIN_ID));

        assertThat(status(response)).isEqualTo(200);
        ArgumentCaptor<GroupCourseOverride> saved = ArgumentCaptor.forClass(GroupCourseOverride.class);
        verify(overrides).save(saved.capture());
        assertThat(saved.getValue().getAcademicYear()).isEqualTo(2026);
        assertThat(saved.getValue().getCourse()).isEqualTo(2);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(ADMIN_ID);

        assertThat(status(controller.resetCourse("721701", as(ADMIN_ID)))).isEqualTo(200);
        verify(overrides).deleteById(new GroupCourseOverride.Key("721701", 2026));
    }

    @Test
    @Tag("negative")
    void rejectsImpossibleCourseAndGroup() {
        assertThat(status(controller.setCourse("721701", Map.of("course", 5), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.setCourse("721701", Map.of("course", "пятый"), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.setCourse("72170", Map.of("course", 2), as(ADMIN_ID)))).isEqualTo(400);
        verify(overrides, never()).save(any());
    }

    @Test
    @Tag("typical")
    void setsHalfAndBackToDate() {
        assertThat(status(controller.setHalf(Map.of("half", "spring"), as(ADMIN_ID)))).isEqualTo(200);
        verify(settings).save(any());
        assertThat(status(controller.setHalf(Map.of("half", "AUTO"), as(ADMIN_ID)))).isEqualTo(200);
        verify(settings).deleteById("half");
        assertThat(status(controller.setHalf(Map.of("half", "лето"), as(ADMIN_ID)))).isEqualTo(400);
    }

    @Test
    @Tag("boundary")
    void procentovkiMustGoInOrder() {
        Map<String, Object> dates = new HashMap<>();
        dates.put("1", "2026-10-01");
        dates.put("2", "2026-10-29");
        dates.put("3", null);
        assertThat(status(controller.setProcentovki(dates, as(ADMIN_ID)))).isEqualTo(200);
        verify(procentovki, times(2)).save(any());
        verify(procentovki).deleteById(new ProcentovkaDate.Key(2026, "AUTUMN", 3));

        clearInvocations(procentovki);
        assertThat(status(controller.setProcentovki(Map.of("1", "2026-10-29", "2", "2026-10-01"), as(ADMIN_ID))))
                .isEqualTo(400);
        assertThat(status(controller.setProcentovki(Map.of("1", "29.10.2026"), as(ADMIN_ID)))).isEqualTo(400);
        verify(procentovki, never()).save(any());
    }

    private static User user(long id, String role) {
        User u = new User();
        u.setTelegramId(id);
        u.setRole(role);
        return u;
    }

    private static MockHttpServletRequest as(long telegramId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("telegram_id", telegramId);
        return request;
    }

    private static int status(ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }
}
