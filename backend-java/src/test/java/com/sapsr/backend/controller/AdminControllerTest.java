package com.sapsr.backend.controller;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.admin.AdminUsers;
import com.sapsr.backend.entity.GroupCourseOverride;
import com.sapsr.backend.entity.ProcentovkaDate;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.storage.FileStorage;
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

/** Экран администратора (решение 11): только роль ADMIN, курсы групп, половина года, сроки по курсам, люди. */
class AdminControllerTest {

    private static final long ADMIN_ID = 9001L;
    private static final long STUDENT_ID = 1001L;

    private final AcademicSettingRepository settings = mock(AcademicSettingRepository.class);
    private final GroupCourseOverrideRepository overrides = mock(GroupCourseOverrideRepository.class);
    private final ProcentovkaDateRepository procentovki = mock(ProcentovkaDateRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private AdminController controller;
    private User student;

    @BeforeEach
    void setUp() {
        LocalDateTime minskNoon = LocalDateTime.of(2026, 10, 23, 12, 0);
        Clock clock = Clock.fixed(minskNoon.atZone(AcademicCalendar.ZONE).toInstant(), AcademicCalendar.ZONE);
        AcademicCalendar calendar = new AcademicCalendar(settings, overrides, procentovki, clock);
        AdminUsers adminUsers = new AdminUsers(users, submissions, mock(ReviewFileService.class), mock(FileStorage.class),
                mock(SapsrTelegramBot.class), calendar);
        controller = new AdminController(calendar, users, overrides, adminUsers);
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, "ADMIN")));
        student = user(STUDENT_ID, "STUDENT");
        student.setFullName("Терешкова А.В. (гр. 321702)");
        student.setLastName("Терешкова");
        student.setGroupNumber("321702");
        when(users.findById(STUDENT_ID)).thenReturn(Optional.of(student));
    }

    @Test
    @Tag("negative")
    void onlyAdminSeesTheScreen() {
        assertThat(status(controller.getCalendar(as(STUDENT_ID)))).isEqualTo(403);
        assertThat(status(controller.getCalendar(new MockHttpServletRequest()))).isEqualTo(401);
        assertThat(status(controller.setCourse("321702", Map.of("course", 1), as(STUDENT_ID)))).isEqualTo(403);
        assertThat(status(controller.makeAdmin(STUDENT_ID, as(STUDENT_ID)))).isEqualTo(403);
        verify(overrides, never()).save(any());
    }

    @Test
    @Tag("typical")
    @SuppressWarnings("unchecked")
    void viewShowsCoursesSchedulesAndPossibleDigits() {
        when(users.countStudentsByGroup()).thenReturn(List.<Object[]>of(new Object[]{"321702", 3L},
                new Object[]{"621701", 1L}, new Object[]{"721701", 1L}));
        User teacher = user(2002L, "TEACHER");
        teacher.setTeacherGroups("[\"321702\",\"321703\"]");
        when(users.findByRole("TEACHER")).thenReturn(List.of(teacher));
        when(overrides.findByAcademicYear(2026)).thenReturn(List.of(
                new GroupCourseOverride("451003", 2026, 2, LocalDateTime.now(), ADMIN_ID)));
        when(procentovki.findByAcademicYearAndHalf(2026, "AUTUMN")).thenReturn(List.of(
                new ProcentovkaDate(2026, "AUTUMN", 4, 2, LocalDate.of(2026, 10, 29), LocalDateTime.now(), ADMIN_ID),
                new ProcentovkaDate(2026, "AUTUMN", 4, 4, LocalDate.of(2026, 12, 26), LocalDateTime.now(), ADMIN_ID)));

        Map<String, Object> view = (Map<String, Object>) controller.getCalendar(as(ADMIN_ID)).getBody();

        assertThat(view).containsEntry("academic_year_title", "2026/2027").containsEntry("half", "AUTUMN")
                .containsEntry("half_overridden", false).containsEntry("group_digits", List.of(3, 4, 5, 6));
        List<Map<String, Object>> schedule = (List<Map<String, Object>>) view.get("schedule");
        assertThat(schedule).hasSize(4);
        Map<String, Object> fourth = schedule.get(3);
        assertThat(fourth).containsEntry("course", 4).containsEntry("semester", 7).containsEntry("current_stage", 2);
        assertThat((Map<String, Object>) fourth.get("dates")).containsEntry("2", "2026-10-29")
                .containsEntry("deadline", "2026-12-26").containsEntry("1", null);
        assertThat(schedule.get(0)).containsEntry("current_stage", null);

        List<Map<String, Object>> groups = (List<Map<String, Object>>) view.get("groups");
        assertThat(groups).extracting(g -> g.get("group")).containsExactly("321702", "321703", "451003", "621701", "721701");
        assertThat(groups.get(0)).containsEntry("course", 4).containsEntry("semester", 7)
                .containsEntry("students", 3L).containsEntry("teachers", 1L).containsEntry("manual", false);
        // 451003 — только запись администратора, никого в ней нет: добавлена вручную.
        assertThat(groups.get(2)).containsEntry("course", 2).containsEntry("course_overridden", true)
                .containsEntry("manual", true);
        assertThat(groups.get(4)).containsEntry("course", null);
    }

    @Test
    @Tag("typical")
    void deletesOnlyEmptyGroupAddedByHand() {
        when(overrides.findByAcademicYear(2026)).thenReturn(List.of(
                new GroupCourseOverride("451003", 2026, 2, LocalDateTime.now(), ADMIN_ID),
                new GroupCourseOverride("321702", 2026, 3, LocalDateTime.now(), ADMIN_ID)));
        when(users.findByRoleAndGroupNumber("STUDENT", "321702")).thenReturn(List.of(student));

        assertThat(status(controller.deleteGroup("451003", as(ADMIN_ID)))).isEqualTo(200);
        verify(overrides).deleteById(new GroupCourseOverride.Key("451003", 2026));

        // В группе есть студент — удалить нельзя; группы нет среди добавленных — тоже.
        assertThat(status(controller.deleteGroup("321702", as(ADMIN_ID)))).isEqualTo(409);
        assertThat(status(controller.deleteGroup("621701", as(ADMIN_ID)))).isEqualTo(404);
        assertThat(status(controller.deleteGroup("4510", as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.deleteGroup("451003", as(STUDENT_ID)))).isEqualTo(403);
        verify(overrides, never()).deleteById(new GroupCourseOverride.Key("321702", 2026));
    }

    @Test
    @Tag("negative")
    void teacherDataIsReadOnly() {
        User teacher = user(2002L, "TEACHER");
        teacher.setFullName("Гракова Наталья Викторовна");
        when(users.findById(2002L)).thenReturn(Optional.of(teacher));

        assertThat(status(controller.updateUser(2002L, Map.of("name", "Гракова Н.В."), as(ADMIN_ID)))).isEqualTo(409);
        assertThat(teacher.getFullName()).isEqualTo("Гракова Наталья Викторовна");
        verify(users, never()).save(any());
    }

    @Test
    @Tag("typical")
    void setsCourseForCurrentAcademicYear() {
        ResponseEntity<?> response = controller.setCourse("451003", Map.of("course", 2), as(ADMIN_ID));

        assertThat(status(response)).isEqualTo(200);
        ArgumentCaptor<GroupCourseOverride> saved = ArgumentCaptor.forClass(GroupCourseOverride.class);
        verify(overrides).save(saved.capture());
        assertThat(saved.getValue().getAcademicYear()).isEqualTo(2026);
        assertThat(saved.getValue().getCourse()).isEqualTo(2);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(ADMIN_ID);

        assertThat(status(controller.resetCourse("451003", as(ADMIN_ID)))).isEqualTo(200);
        verify(overrides).deleteById(new GroupCourseOverride.Key("451003", 2026));
    }

    @Test
    @Tag("negative")
    void rejectsImpossibleCourseAndGroup() {
        assertThat(status(controller.setCourse("451003", Map.of("course", 5), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.setCourse("451003", Map.of("course", "пятый"), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.setCourse("45100", Map.of("course", 2), as(ADMIN_ID)))).isEqualTo(400);
        // В 2026/2027 группы начинаются с 3–6: 0 и 7 — год поступления не из текущих курсов.
        assertThat(status(controller.setCourse("021701", Map.of("course", 2), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.setCourse("721701", Map.of("course", 1), as(ADMIN_ID)))).isEqualTo(400);
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
    @Tag("typical")
    void savesScheduleForChosenCourses() {
        Map<String, Object> dates = new HashMap<>();
        dates.put("1", "2026-10-01");
        dates.put("2", "2026-10-29");
        dates.put("3", null);
        dates.put("deadline", "2026-12-26");

        assertThat(status(controller.setSchedule(Map.of("courses", List.of(1, 2, 4), "dates", dates), as(ADMIN_ID))))
                .isEqualTo(200);
        verify(procentovki, times(9)).save(any());
        verify(procentovki).deleteById(new ProcentovkaDate.Key(2026, "AUTUMN", 4, 3));
        verify(procentovki, never()).deleteById(new ProcentovkaDate.Key(2026, "AUTUMN", 3, 3));
    }

    @Test
    @Tag("negative")
    void scheduleMustBeInOrderAndInsideSemester() {
        Map<String, Object> reversed = Map.of("courses", List.of(4), "dates", Map.of("1", "2026-10-29", "2", "2026-10-01"));
        Map<String, Object> deadlineFirst = Map.of("courses", List.of(4), "dates", Map.of("3", "2026-11-26", "deadline", "2026-11-01"));
        Map<String, Object> lastYear = Map.of("courses", List.of(4), "dates", Map.of("2", "2025-10-29"));
        Map<String, Object> badFormat = Map.of("courses", List.of(4), "dates", Map.of("1", "29.10.2026"));
        Map<String, Object> noCourses = Map.of("courses", List.of(), "dates", Map.of());
        Map<String, Object> fifthCourse = Map.of("courses", List.of(5), "dates", Map.of());

        for (Map<String, Object> body : List.of(reversed, deadlineFirst, lastYear, badFormat, noCourses, fifthCourse)) {
            assertThat(status(controller.setSchedule(body, as(ADMIN_ID)))).as(body.toString()).isEqualTo(400);
        }
        verify(procentovki, never()).save(any());
    }

    @Test
    @Tag("typical")
    @SuppressWarnings("unchecked")
    void showsPeopleOfGroupAndFixesTypo() {
        User teacher = user(2002L, "TEACHER");
        teacher.setFullName("Гракова Наталья Викторовна");
        teacher.setTeacherGroups("[\"321702\"]");
        when(users.findByRole("TEACHER")).thenReturn(List.of(teacher));
        when(users.findByRoleAndGroupNumber("STUDENT", "321702")).thenReturn(List.of(student));

        Map<String, Object> people = (Map<String, Object>) controller.getPeople("321702", as(ADMIN_ID)).getBody();
        assertThat((List<AdminUsers.Person>) people.get("students"))
                .containsExactly(new AdminUsers.Person(STUDENT_ID, "Терешкова А.В.", "321702"));
        assertThat((List<AdminUsers.Person>) people.get("teachers"))
                .containsExactly(new AdminUsers.Person(2002L, "Гракова Наталья Викторовна", null));

        ResponseEntity<?> fixed = controller.updateUser(STUDENT_ID,
                Map.of("name", "  Терешкова   Анна Вячеславовна ", "group", "321703"), as(ADMIN_ID));

        assertThat(status(fixed)).isEqualTo(200);
        assertThat(student.getFullName()).isEqualTo("Терешкова Анна Вячеславовна (гр. 321703)");
        assertThat(student.getFirstName()).isEqualTo("Анна");
        assertThat(student.getGroupNumber()).isEqualTo("321703");
        assertThat((Map<String, Object>) fixed.getBody()).containsKey("people").containsKey("groups");
    }

    @Test
    @Tag("negative")
    void rejectsBadNameAndImpossibleGroup() {
        assertThat(status(controller.updateUser(STUDENT_ID, Map.of("name", "терешкова"), as(ADMIN_ID)))).isEqualTo(400);
        assertThat(status(controller.updateUser(STUDENT_ID, Map.of("name", "Терешкова <b>", "group", "321702"), as(ADMIN_ID))))
                .isEqualTo(400);
        assertThat(status(controller.updateUser(STUDENT_ID, Map.of("name", "Терешкова А.В.", "group", "021702"), as(ADMIN_ID))))
                .isEqualTo(400);
        assertThat(status(controller.updateUser(424242L, Map.of("name", "Кто-то"), as(ADMIN_ID)))).isEqualTo(404);
        verify(users, never()).save(any());
    }

    @Test
    @Tag("typical")
    void studentBecomesAdmin() {
        assertThat(status(controller.makeAdmin(STUDENT_ID, as(ADMIN_ID)))).isEqualTo(200);

        assertThat(student.getRole()).isEqualTo("ADMIN");
        assertThat(student.getGroupNumber()).isNull();
        assertThat(student.getFullName()).isEqualTo("Терешкова А.В.");
        verify(users).save(student);
    }

    @Test
    @Tag("negative")
    void onlyStudentCanBecomeAdmin() {
        when(users.findById(2002L)).thenReturn(Optional.of(user(2002L, "TEACHER")));

        assertThat(status(controller.makeAdmin(2002L, as(ADMIN_ID)))).isEqualTo(409);
        assertThat(status(controller.makeAdmin(ADMIN_ID, as(ADMIN_ID)))).isEqualTo(409);
        assertThat(status(controller.makeAdmin(424242L, as(ADMIN_ID)))).isEqualTo(404);
        verify(users, never()).save(any());
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
