package com.sapsr.backend.db;

import com.sapsr.backend.config.FlywayConfig;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * База, созданная до Flyway прежним init.sql, с записями в старом формате.
 * Бэкенд должен принять её как версию 1, применить V2 и V3 и правильно разложить старые данные.
 * Нужен запущенный Docker; без него тест пропускается.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FlywayConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class LegacyDatabaseMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withInitScript("db/legacy-init.sql");

    @Autowired JdbcTemplate jdbc;

    @Test
    void baselinesExistingSchemaAndAppliesOnlyNewMigrations() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, type FROM flyway_schema_history WHERE success ORDER BY installed_rank");
        assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(history.get(0).get("type")).isEqualTo("BASELINE");
    }

    @Test
    void splitsOldStudentNames() {
        Map<String, Object> ivanov = user(1001);
        assertThat(ivanov.get("last_name")).isEqualTo("Иванов");
        assertThat(ivanov.get("group_number")).isEqualTo("321702");
        // Известны только инициалы — интерфейс попросит дополнить ФИО.
        assertThat(ivanov.get("first_name")).isNull();
        assertThat(ivanov.get("middle_name")).isNull();

        Map<String, Object> yolkina = user(1002);
        assertThat(yolkina.get("last_name")).isEqualTo("Ёлкина");
        assertThat(yolkina.get("group_number")).isEqualTo("321701");
    }

    @Test
    void splitsTeacherNames() {
        Map<String, Object> full = user(2001);
        assertThat(full.get("last_name")).isEqualTo("Петрова");
        assertThat(full.get("first_name")).isEqualTo("Анна");
        assertThat(full.get("middle_name")).isEqualTo("Сергеевна");
        assertThat(full.get("group_number")).isNull();

        Map<String, Object> initials = user(2002);
        assertThat(initials.get("last_name")).isEqualTo("Сидорова");
        assertThat(initials.get("first_name")).isNull();
        assertThat(initials.get("middle_name")).isNull();
    }

    @Test
    void turnsOldFilePathsIntoKeysAndOriginalNames() {
        Map<String, Object> unixPath = submission(1);
        assertThat(unixPath.get("storage_key")).isEqualTo("1712345678901_Курсовая работа.pdf");
        assertThat(unixPath.get("original_file_name")).isEqualTo("Курсовая работа.pdf");

        Map<String, Object> windowsPath = submission(2);
        assertThat(windowsPath.get("storage_key")).isEqualTo("1712345678902_отчёт_v2.pdf");
        assertThat(windowsPath.get("original_file_name")).isEqualTo("отчёт_v2.pdf");

        Map<String, Object> noFile = submission(3);
        assertThat(noFile.get("storage_key")).isNull();
        assertThat(noFile.get("original_file_name")).isNull();
    }

    @Test
    void finishedWorksAreFinalAndLongStuckOnesFail() {
        // V5: результат v1 уже пришёл — отчёт окончательный; прежний обработчик ронял работы в PROCESSING навсегда.
        assertThat(submission(1)).containsEntry("content_status", "DONE");
        assertThat(submission(2).get("teacher_notified_at")).isNotNull();
        assertThat(submission(4)).containsEntry("status", "FAILED").containsEntry("failure_code", "STUCK");
        // Свежую работу закроет сторож, если обработчик так и не ответит.
        assertThat(submission(3)).containsEntry("status", "PROCESSING");
        assertThat(submission(3).get("processing_started_at")).isNotNull();
    }

    private Map<String, Object> user(long telegramId) {
        return jdbc.queryForMap("SELECT * FROM users WHERE telegram_id = ?", telegramId);
    }

    private Map<String, Object> submission(int id) {
        return jdbc.queryForMap("SELECT * FROM submissions WHERE id = ?", id);
    }
}
