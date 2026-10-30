package com.sapsr.backend.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Задание на проверку в pdf_tasks_queue — протокол очередей v2, docs/contracts/queue-v2.md, раздел 3.
 * Сериализуется Jackson-ом (RabbitMQConfig); пример — docs/contracts/examples/queue-v2/task.json.
 */
public record TaskMessage(
        int schema,
        @JsonProperty("task_id") int taskId,
        String type,
        FileRef file,
        CheckProfile profile,
        StudentInfo student,
        LlmChoice llm,
        PreviousVersion previous,
        @JsonProperty("trace_id") UUID traceId,
        // Переходное поле: нынешний обработчик читает абсолютный путь. Удалить, когда он перейдёт на storage_key.
        @JsonProperty("file_path") String filePath) {

    public static final int SCHEMA = 2;
    public static final String TYPE_CHECK = "CHECK";
    public static final String SOURCE_FORMAT_PDF = "pdf";

    public record FileRef(
            @JsonProperty("storage_key") String storageKey,
            String sha256,
            // Файл по ключу всегда PDF; docx — если работа пришла в Word и была сконвертирована (решение 3).
            @JsonProperty("source_format") String sourceFormat) {}

    public record CheckProfile(
            @JsonProperty("work_type") String workType,
            Integer semester,
            Integer stage) {}

    public record StudentInfo(
            @JsonProperty("last_name") String lastName,
            @JsonProperty("first_name") String firstName,
            String group) {}

    /**
     * Модель, которую выбрал администратор (решение 13): id из каталога обработчика, адреса здесь нет.
     * null — модель обработчика по умолчанию.
     */
    public record LlmChoice(String model) {}

    /** Предыдущая версия той же работы. Пока всегда null; аннотации добавятся вместе со связью версий. */
    public record PreviousVersion(@JsonProperty("task_id") int taskId) {}

    /** Задание на проверку сохранённой работы моделью по умолчанию; {@code file} — абсолютный путь к её файлу. */
    public static TaskMessage check(Submission submission, Path file) {
        return check(submission, file, null);
    }

    /** То же, моделью {@code modelId} из каталога обработчика; null — по умолчанию. */
    public static TaskMessage check(Submission submission, Path file, String modelId) {
        User student = submission.getStudent();
        return new TaskMessage(
                SCHEMA,
                submission.getId(),
                TYPE_CHECK,
                new FileRef(submission.getStorageKey(), submission.getFileSha256(), SOURCE_FORMAT_PDF),
                new CheckProfile(submission.getWorkType(), submission.getSemester(), submission.getStage()),
                student == null
                        ? new StudentInfo(null, null, null)
                        : new StudentInfo(student.getLastName(), student.getFirstName(), student.getGroupNumber()),
                modelId == null ? null : new LlmChoice(modelId),
                null,
                submission.getTraceId(),
                file.toString().replace('\\', '/'));
    }
}
