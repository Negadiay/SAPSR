package com.sapsr.backend.db;

import com.sapsr.backend.config.FlywayConfig;
import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.AnnotationRepository;
import com.sapsr.backend.repository.ReviewFileRepository;
import com.sapsr.backend.repository.SubmissionRepository;
import com.sapsr.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Пустая база: схема создаётся миграциями с нуля, и Hibernate в режиме validate с ней согласен.
 * Нужен запущенный Docker; без него тест пропускается.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class FreshDatabaseMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired SubmissionRepository submissions;
    @Autowired ReviewFileRepository reviewFiles;
    @Autowired AnnotationRepository annotations;

    @Test
    void appliesAllMigrationsFromScratch() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4");
    }

    @Test
    void teacherQueueShowsRejectedWorkAndOnlyLatestVersion() {
        // Решение 9: отклонённая работа видна преподавателю, новая версия заменяет предыдущую.
        User teacher = user(6001L, "TEACHER");
        User student = user(6002L, "STUDENT");
        Submission v1 = submission(student, teacher, "REJECTED", null);
        Submission v2 = submission(student, teacher, "SUCCESS", v1.getId());
        Submission approved = submission(user(6003L, "STUDENT"), teacher, "SUCCESS", null);
        approved.setTeacherVerdict("APPROVED");
        submissions.saveAndFlush(approved);
        submission(user(6004L, "STUDENT"), teacher, "PROCESSING", null);
        Submission rejectedOther = submission(user(6005L, "STUDENT"), teacher, "REJECTED", null);

        assertThat(submissions.findPendingForTeacher(6001L)).extracting(Submission::getId)
                .containsExactlyInAnyOrder(v2.getId(), rejectedOther.getId());
        assertThat(submissions.existsByParentId(v1.getId())).isTrue();
        assertThat(submissions.existsByParentId(v2.getId())).isFalse();
        // Строки вставлены в одной транзакции с одинаковым created_at — порядок решает id.
        assertThat(submissions.findFirstByStudent_TelegramIdAndTeacher_TelegramIdOrderByCreatedAtDescIdDesc(6002L, 6001L))
                .map(Submission::getId).contains(v2.getId());
    }

    @Test
    void reviewFileAndCommentsAreDeletedWithWork() {
        Submission work = submission(user(7002L, "STUDENT"), user(7001L, "TEACHER"), "SUCCESS", null);
        ReviewFile file = new ReviewFile();
        file.setSubmissionId(work.getId());
        file.setStorageKey("2026/10/" + UUID.randomUUID() + ".pdf");
        file.setUploadedAt(LocalDateTime.now());
        file.setWarnings("[{\"code\":\"NO_ANNOTATIONS\",\"message\":\"...\"}]");
        file = reviewFiles.saveAndFlush(file);
        Annotation question = annotation(file, 0, null);
        Annotation reply = annotation(file, 1, question.getId());

        assertThat(annotations.findByReviewFileIdOrderByPositionAsc(file.getId()))
                .extracting(Annotation::getReplyToId).containsExactly(null, question.getId());
        assertThat(reply.getId()).isNotNull();

        submissions.deleteById(work.getId());
        submissions.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_files", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM annotations", Integer.class)).isZero();
    }

    private User user(long id, String role) {
        User user = new User();
        user.setTelegramId(id);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private Submission submission(User student, User teacher, String status, Integer parentId) {
        Submission s = new Submission();
        s.setStudent(student);
        s.setTeacher(teacher);
        s.setStatus(status);
        s.setParentId(parentId);
        return submissions.saveAndFlush(s);
    }

    private Annotation annotation(ReviewFile file, int position, Integer replyTo) {
        Annotation a = new Annotation();
        a.setReviewFileId(file.getId());
        a.setPosition(position);
        a.setPage(3);
        a.setKind("NOTE");
        a.setComment("Комментарий " + position);
        a.setRect("[500.0, 680.0, 520.0, 700.0]");
        a.setReplyToId(replyTo);
        return annotations.saveAndFlush(a);
    }

    @Test
    void hasNoStudentCardColumn() {
        // Решение 4 в редакции 18.10.2026: номер студенческого не храним.
        Integer columns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'users' AND column_name LIKE '%card%'",
                Integer.class);
        assertThat(columns).isZero();
    }

    @Test
    void savesUserAndSubmissionWithNewFields() {
        User student = new User();
        student.setTelegramId(5001L);
        student.setRole("STUDENT");
        student.setFullName("Иванов И.И. (гр. 321702)");
        student.setLastName("Иванов");
        student.setGroupNumber("321702");
        users.saveAndFlush(student);

        UUID traceId = UUID.randomUUID();
        Submission submission = new Submission();
        submission.setStudent(student);
        submission.setStatus("PROCESSING");
        submission.setStorageKey("2026/10/" + UUID.randomUUID() + ".pdf");
        submission.setOriginalFileName("Курсовая \"итог\".pdf");
        submission.setFileSha256("a".repeat(64));
        submission.setWorkType("course_work");
        submission.setSemester(7);
        submission.setStage(2);
        submission.setTraceId(traceId);
        Integer id = submissions.saveAndFlush(submission).getId();

        assertThat(jdbc.queryForObject("SELECT trace_id FROM submissions WHERE id = ?", UUID.class, id))
                .isEqualTo(traceId);
        assertThat(jdbc.queryForObject("SELECT original_file_name FROM submissions WHERE id = ?", String.class, id))
                .isEqualTo("Курсовая \"итог\".pdf");
        assertThat(jdbc.queryForObject("SELECT group_number FROM users WHERE telegram_id = 5001", String.class))
                .isEqualTo("321702");
    }
}
