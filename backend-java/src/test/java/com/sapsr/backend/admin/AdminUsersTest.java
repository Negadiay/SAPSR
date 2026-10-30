package com.sapsr.backend.admin;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.AcademicSettingRepository;
import com.sapsr.backend.repository.GroupCourseOverrideRepository;
import com.sapsr.backend.repository.ProcentovkaDateRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.review.ReviewFileService;
import com.sapsr.backend.storage.FileStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Назначение администратором удаляет учебные данные студента (решение 11). */
class AdminUsersTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final ReviewFileService reviewFiles = mock(ReviewFileService.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final SapsrTelegramBot bot = mock(SapsrTelegramBot.class);
    private final AdminUsers adminUsers = new AdminUsers(users, submissions, reviewFiles, fileStorage, bot,
            new AcademicCalendar(mock(AcademicSettingRepository.class), mock(GroupCourseOverrideRepository.class),
                    mock(ProcentovkaDateRepository.class),
                    Clock.fixed(LocalDateTime.of(2026, 10, 24, 12, 0).atZone(AcademicCalendar.ZONE).toInstant(),
                            AcademicCalendar.ZONE)));

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
}
