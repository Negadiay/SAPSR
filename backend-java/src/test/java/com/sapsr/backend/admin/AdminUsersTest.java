package com.sapsr.backend.admin;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.bot.AppLink;
import com.sapsr.backend.bot.BotMessages;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.FindingFeedbackRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.service.BsuirApiService;
import com.sapsr.backend.storage.FileStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Назначение администратором удаляет учебные данные студента (решение 11). */
class AdminUsersTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final FindingFeedbackRepository findingFeedback = mock(FindingFeedbackRepository.class);
    private final ReviewFileService reviewFiles = mock(ReviewFileService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final SapsrTelegramBot bot = mock(SapsrTelegramBot.class);
    private final BsuirApiService iis = mock(BsuirApiService.class);
    private final AdminUsers adminUsers = new AdminUsers(users, submissions, findingFeedback, reviewFiles, fileStorage,
            bot, new AcademicCalendar(mock(AcademicSettingRepository.class), mock(GroupCourseOverrideRepository.class),
                    mock(ProcentovkaDateRepository.class),
                    Clock.fixed(LocalDateTime.of(2026, 10, 24, 12, 0).atZone(AcademicCalendar.ZONE).toInstant(),
                            AcademicCalendar.ZONE)), iis);

    @Test
    @Tag("typical")
    void makeAdminRemovesWorksFilesAndTeacherMessages() throws IOException {
        User student = new User();
        student.setTelegramId(1001L);
        student.setRole("STUDENT");
        student.setFullName("Иванов И.И. (гр. 321702)");
        student.setGroupNumber("321702");
        User teacher = new User();
        teacher.setTelegramId(2002L);
        Submission work = new Submission();
        work.setId(153);
        work.setStorageKey("2026/10/a.pdf");
        work.setTeacher(teacher);
        work.setTeacherMessageId(555);
        Submission draft = new Submission();
        draft.setId(154);
        when(users.findById(1001L)).thenReturn(Optional.of(student));
        when(submissions.findByStudent_TelegramIdOrderByCreatedAtDesc(1001L)).thenReturn(List.of(work, draft));

        assertThat(adminUsers.makeAdmin(1001L, 9001L)).isEqualTo(2);

        verify(bot).deleteMessage(2002L, 555);
        verify(reviewFiles).deleteFor(153);
        verify(reviewFiles).deleteFor(154);
        verify(submissions).deleteById(153);
        verify(submissions).deleteById(154);
        verify(fileStorage).delete("2026/10/a.pdf");
        assertThat(student.getRole()).isEqualTo("ADMIN");
        assertThat(student.getFullName()).isEqualTo("Иванов И.И.");
        assertThat(student.getGroupNumber()).isNull();
        verify(bot).send(eq(1001L), contains("Удалено: 2 работы"), eq(AppLink.forRole("ADMIN")));
    }

    private User studentIvanov() {
        User student = new User();
        student.setTelegramId(1001L);
        student.setRole("STUDENT");
        student.setFullName("Иванов И.И. (гр. 321702)");
        student.setLastName("Иванов");
        student.setGroupNumber("321702");
        when(users.findById(1001L)).thenReturn(Optional.of(student));
        when(iis.groupExists("321703")).thenReturn(true);
        return student;
    }

    @Test
    @Tag("negative")
    void studentCannotBeMovedIntoInventedGroup() {
        User student = studentIvanov();

        assertThatThrownBy(() -> adminUsers.update(1001L, "Иванов И.И.", "321799"))
                .isInstanceOf(AdminUsers.Invalid.class).hasMessageContaining("нет в ИИС");

        assertThat(student.getGroupNumber()).isEqualTo("321702");
        verifyNoInteractions(bot);
    }

    @Test
    @Tag("negative")
    void unavailableIisDoesNotCreateGroup() {
        User student = studentIvanov();
        when(iis.groupExists("321799")).thenThrow(new BsuirApiService.IisUnavailableException("timeout"));

        assertThatThrownBy(() -> adminUsers.update(1001L, "Иванов И.И.", "321799"))
                .isInstanceOf(AdminUsers.Invalid.class).hasMessageContaining("не отвечает");

        assertThat(student.getGroupNumber()).isEqualTo("321702");
    }

    @Test
    @Tag("typical")
    void studentIsToldWhatAdminChanged() {
        studentIvanov();

        adminUsers.update(1001L, "Иванов Иван Иванович", "321703");

        verify(bot).send(eq(1001L), and(contains("ФИО: Иванов И.И. → Иванов Иван Иванович"),
                contains("Группа: 321702 → 321703")), eq(AppLink.forRole("STUDENT")));
    }

    @Test
    @Tag("boundary")
    void messageListsOnlyTheChangedField() {
        studentIvanov();

        adminUsers.update(1001L, "Иванов И.И.", "321703");

        verify(bot).send(eq(1001L), and(contains("Группа: 321702 → 321703"), not(contains("ФИО:"))), any());
    }

    @Test
    @Tag("boundary")
    void noMessageWhenNothingChanged() {
        studentIvanov();

        adminUsers.update(1001L, "  Иванов   И.И. ", "321702");

        verifyNoInteractions(bot);
    }

    @Test
    @Tag("negative")
    void rejectedEditSendsNothing() {
        studentIvanov();

        assertThatThrownBy(() -> adminUsers.update(1001L, "иванов", "321703")).isInstanceOf(AdminUsers.Invalid.class);

        verifyNoInteractions(bot);
    }

    @Test
    @Tag("negative")
    void oldNameIsEscapedInMessage() {
        assertThat(BotMessages.profileChanged("Иванов <b>", "Иванов И.И.", "321702", "321702"))
                .contains("Иванов &lt;b&gt; → Иванов И.И.");
    }

    @Test
    @Tag("boundary")
    void hintNamesPossibleFirstDigits() {
        assertThat(adminUsers.groupHint()).isEqualTo("Номер группы — шесть цифр, первая — 3, 4, 5 или 6.");
    }

    @Test
    @Tag("typical")
    void displayNameDropsGroupSuffix() {
        User u = new User();
        u.setFullName("Терешкова А.В. (гр. 321702)");
        assertThat(u.displayName()).isEqualTo("Терешкова А.В.");
        u.setFullName("Гракова Наталья Викторовна");
        assertThat(u.displayName()).isEqualTo("Гракова Наталья Викторовна");
    }

    @Test
    @Tag("typical")
    void deleteStudentRemovesWorksFilesTeacherMessagesAndSendsNotice() throws IOException {
        User student = studentIvanov();
        User teacher = new User();
        teacher.setTelegramId(2002L);
        Submission work = new Submission();
        work.setId(155);
        work.setStorageKey("2026/10/b.pdf");
        work.setSourceStorageKey("2026/10/b.docx");
        work.setTeacher(teacher);
        work.setTeacherMessageId(777);
        when(submissions.findByStudent_TelegramIdOrderByCreatedAtDesc(1001L)).thenReturn(List.of(work));

        adminUsers.deleteUser(1001L, 9001L);

        verify(bot).deleteMessage(2002L, 777);
        verify(reviewFiles).deleteFor(155);
        verify(submissions).deleteById(155);
        verify(submissions).flush();
        verify(fileStorage).delete("2026/10/b.pdf");
        verify(fileStorage).delete("2026/10/b.docx");
        verify(users).deleteById(1001L);
        verify(users).flush();
        verify(bot).send(eq(1001L), contains("удалён администратором"), eq(AppLink.forRole(null)));
    }

    @Test
    @Tag("typical")
    void deleteTeacherClearsWorksAndFeedbackAndSendsNotice() {
        User teacher = new User();
        teacher.setTelegramId(2002L);
        teacher.setRole("TEACHER");
        when(users.findById(2002L)).thenReturn(Optional.of(teacher));

        adminUsers.deleteUser(2002L, 9001L);

        verify(findingFeedback).deleteByTeacherTelegramId(2002L);
        verify(submissions).clearTeacher(2002L);
        verify(users).deleteById(2002L);
        verify(users).flush();
        verify(bot).send(eq(2002L), contains("удалён администратором"), eq(AppLink.forRole(null)));
    }

    @Test
    @Tag("negative")
    void cannotDeleteAdminOrSelf() {
        User otherAdmin = new User();
        otherAdmin.setTelegramId(9002L);
        otherAdmin.setRole("ADMIN");
        when(users.findById(9002L)).thenReturn(Optional.of(otherAdmin));

        assertThatThrownBy(() -> adminUsers.deleteUser(9001L, 9001L))
                .isInstanceOf(AdminUsers.CannotDelete.class);
        assertThatThrownBy(() -> adminUsers.deleteUser(9002L, 9001L))
                .isInstanceOf(AdminUsers.CannotDelete.class);
        assertThatThrownBy(() -> adminUsers.deleteUser(424242L, 9001L))
                .isInstanceOf(AdminUsers.NotFound.class);
        verify(users, never()).deleteById(any());
    }
}
