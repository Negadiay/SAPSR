package com.sapsr.backend.service;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.SubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ResultListenerServiceTest {

    private static final long STUDENT_ID = 1001L;
    private static final long TEACHER_ID = 2002L;
    // Результат v1 от обработчика: ошибка оформления и два содержательных замечания (одно — на проверку).
    private static final String REJECTED = """
            {"task_id": 153, "status": "FAIL", "errors": [
              {"severity": "major", "category": "format", "origin": "symbolic", "page": 3, "message": "Левое поле меньше 30 мм"},
              {"severity": "warning", "category": "content", "origin": "hybrid", "needs_teacher_review": false, "page": 4,
               "message": "Форматы входных файлов описаны по-разному"},
              {"severity": "warning", "category": "requirements", "origin": "hybrid", "needs_teacher_review": true,
               "message": "Не удалось установить, описаны ли точки интеграции"}
            ]}""";

    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final SapsrTelegramBot bot = mock(SapsrTelegramBot.class);
    private final ResultListenerService listener = new ResultListenerService(submissions, bot);
    private Submission work;

    @BeforeEach
    void setUp() {
        User student = new User();
        student.setTelegramId(STUDENT_ID);
        student.setFullName("Терешкова А.В. (гр. 321702)");
        User teacher = new User();
        teacher.setTelegramId(TEACHER_ID);
        work = new Submission();
        work.setId(153);
        work.setStatus("PROCESSING");
        work.setStudent(student);
        work.setTeacher(teacher);
        work.setOriginalFileName("Курсовая.pdf");
        when(submissions.findById(153)).thenReturn(Optional.of(work));
        when(bot.notifyUser(anyLong(), anyString())).thenReturn(555);
    }

    @Test
    @Tag("typical")
    void rejectedWorkStillGoesToTeacher() {
        listener.handleResult(REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(work.getTeacherMessageId()).isEqualTo(555);
        String teacherText = sent(TEACHER_ID);
        assertThat(teacherText).startsWith("📥 <b>Новая работа</b>")
                .contains("👤 Терешкова А.В. (гр. 321702)")
                .contains("Оформление: ❌ ошибок — 1")
                .contains("Содержание: замечаний — 2, на вашу проверку — 1");
        String studentText = sent(STUDENT_ID);
        assertThat(studentText).startsWith("❌ <b>В работе есть ошибки оформления</b>")
                .contains("Оформление: ошибок — 1.")
                .contains("• Левое поле меньше 30 мм")
                .contains("Содержание: замечаний — 2")
                .contains("срок сдачи засчитан");
    }

    @Test
    @Tag("typical")
    void newVersionMentionsFirstArrival() {
        work.setParentId(150);
        work.setFirstSubmittedAt(LocalDateTime.of(2026, 10, 20, 9, 15));

        listener.handleResult("{\"task_id\": 153, \"status\": \"SUCCESS\", \"errors\": []}");

        assertThat(sent(TEACHER_ID)).contains("Новая версия работы").contains("Первая версия пришла 20.10.2026 09:15")
                .contains("Оформление: ✅ без ошибок").doesNotContain("Содержание");
    }

    @Test
    @Tag("boundary")
    void supersededVersionIsSavedSilently() {
        // Студент успел загрузить новую версию, пока эта проверялась.
        when(submissions.existsByParentId(153)).thenReturn(true);

        listener.handleResult(REJECTED);

        assertThat(work.getStatus()).isEqualTo("REJECTED");
        assertThat(work.getFormatErrors()).contains("Левое поле");
        verifyNoInteractions(bot);
    }

    private String sent(long chatId) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(bot).notifyUser(eq(chatId), text.capture());
        return text.getValue();
    }
}
