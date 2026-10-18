package com.sapsr.backend.db;

import com.sapsr.backend.config.FlywayConfig;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
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

    @Test
    void appliesAllMigrationsFromScratch() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(applied).containsExactly("1", "2", "3");
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
