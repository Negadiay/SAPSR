package com.sapsr.backend.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Задание собирается по контракту docs/contracts/queue-v2.md — тем же конвертером, что в RabbitMQConfig. */
class TaskMessageTest {

    // Тесты Maven запускаются из backend-java, контракт лежит в docs/ в корне репозитория.
    private static final Path CONTRACT_EXAMPLE = Path.of("..", "docs", "contracts", "examples", "queue-v2", "task.json");
    private static final UUID TRACE_ID = UUID.fromString("4b1e0c7a-2f3d-4a5b-8c6d-7e8f9a0b1c2d");

    private final ObjectMapper json = new ObjectMapper();
    private final JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();

    @Test
    void hasExactlyTheFieldsOfContractExample() throws IOException {
        JsonNode actual = send(TaskMessage.check(submission(student("Иванов", "Иван", "321702")), storagePath()));
        JsonNode example = json.readTree(CONTRACT_EXAMPLE.toFile());

        assertThat(fieldPaths(actual)).isEqualTo(fieldPaths(example));
    }

    @Test
    void fillsFieldsFromSubmission() throws IOException {
        JsonNode task = send(TaskMessage.check(submission(student("Иванов", "Иван", "321702")), storagePath()));

        assertThat(task.get("schema").asInt()).isEqualTo(2);
        assertThat(task.get("task_id").asInt()).isEqualTo(153);
        assertThat(task.get("type").asText()).isEqualTo("CHECK");
        assertThat(task.at("/file/storage_key").asText()).isEqualTo("2026/10/8f3a2c1e.pdf");
        assertThat(task.at("/file/sha256").asText()).isEqualTo("b".repeat(64));
        assertThat(task.at("/file/source_format").asText()).isEqualTo("pdf");
        assertThat(task.at("/profile/work_type").asText()).isEqualTo("course_work");
        assertThat(task.at("/profile/semester").asInt()).isEqualTo(7);
        assertThat(task.at("/profile/stage").asInt()).isEqualTo(2);
        assertThat(task.at("/student/last_name").asText()).isEqualTo("Иванов");
        assertThat(task.at("/student/first_name").asText()).isEqualTo("Иван");
        assertThat(task.at("/student/group").asText()).isEqualTo("321702");
        assertThat(task.get("previous").isNull()).isTrue();
        assertThat(task.get("trace_id").asText()).isEqualTo(TRACE_ID.toString());
    }

    @Test
    void filePathUsesForwardSlashesForWorker() throws IOException {
        Path windowsLike = Path.of("C:\\Users\\dev\\SAPSR\\storage\\2026\\10\\8f3a2c1e.pdf");

        JsonNode task = send(TaskMessage.check(submission(null), windowsLike));

        assertThat(task.get("file_path").asText()).doesNotContain("\\").endsWith("8f3a2c1e.pdf");
    }

    @Test
    void sendsJsonContentType() {
        Message message = converter.toMessage(TaskMessage.check(submission(null), storagePath()), new MessageProperties());

        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    }

    @Test
    void quotesInNamesDoNotBreakJson() throws IOException {
        // Раньше задание собиралось String.format, и кавычка в данных ломала JSON.
        JsonNode task = send(TaskMessage.check(submission(student("О'Нил \"мл.\"", "Иван\\", "321702")), storagePath()));

        assertThat(task.at("/student/last_name").asText()).isEqualTo("О'Нил \"мл.\"");
        assertThat(task.at("/student/first_name").asText()).isEqualTo("Иван\\");
    }

    @Test
    void originalFileNameNeverGoesToWorker() {
        Submission submission = submission(null);
        submission.setOriginalFileName("Секретное имя \"файла\".pdf");

        String body = new String(converter.toMessage(TaskMessage.check(submission, storagePath()),
                new MessageProperties()).getBody(), StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("Секретное имя");
    }

    @Test
    void oldAccountsAndMissingProfileGiveNulls() throws IOException {
        Submission submission = submission(student("Иванов", null, "321702"));
        submission.setWorkType(null);
        submission.setSemester(null);
        submission.setStage(null);

        JsonNode task = send(TaskMessage.check(submission, storagePath()));

        assertThat(task.at("/student/first_name").isNull()).isTrue();
        assertThat(task.at("/profile/work_type").isNull()).isTrue();
        assertThat(task.at("/profile/semester").isNull()).isTrue();
        assertThat(task.at("/profile/stage").isNull()).isTrue();
    }

    @Test
    void submissionWithoutStudentStillHasStudentBlock() throws IOException {
        JsonNode task = send(TaskMessage.check(submission(null), storagePath()));

        assertThat(task.get("student").isObject()).isTrue();
        assertThat(task.at("/student/last_name").isNull()).isTrue();
    }

    private JsonNode send(TaskMessage task) throws IOException {
        return json.readTree(converter.toMessage(task, new MessageProperties()).getBody());
    }

    private static Path storagePath() {
        return Path.of("/app/storage/2026/10/8f3a2c1e.pdf");
    }

    private static User student(String lastName, String firstName, String group) {
        User user = new User();
        user.setTelegramId(1001L);
        user.setRole("STUDENT");
        user.setLastName(lastName);
        user.setFirstName(firstName);
        user.setGroupNumber(group);
        return user;
    }

    private static Submission submission(User student) {
        Submission submission = new Submission();
        submission.setId(153);
        submission.setStudent(student);
        submission.setStorageKey("2026/10/8f3a2c1e.pdf");
        submission.setFileSha256("b".repeat(64));
        submission.setOriginalFileName("Курсовая.pdf");
        submission.setWorkType("course_work");
        submission.setSemester(7);
        submission.setStage(2);
        submission.setTraceId(TRACE_ID);
        return submission;
    }

    /** Пути всех полей: «file.storage_key», «previous» и т. д. Значение null считается листом. */
    private static TreeSet<String> fieldPaths(JsonNode node) {
        TreeSet<String> paths = new TreeSet<>();
        collect(node, "", paths);
        return paths;
    }

    private static void collect(JsonNode node, String prefix, TreeSet<String> out) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String path = prefix + field.getKey();
            if (field.getValue().isObject()) {
                collect(field.getValue(), path + ".", out);
            } else {
                out.add(path);
            }
        }
    }
}
