package com.sapsr.backend.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "submissions")
public class Submission {

    private static final String DEFAULT_FILE_NAME = "file.pdf";
    private static final int MAX_FILE_NAME_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "student_telegram_id")
    private User student;

    @ManyToOne
    @JoinColumn(name = "teacher_telegram_id")
    private User teacher;

    @Column(name = "status", nullable = false)
    private String status;

    // Ключ файла в хранилище (FileStorage). Столбец file_path больше не используется.
    @Column(name = "storage_key")
    private String storageKey;

    // Исходное имя файла — только для показа людям; на диске файл лежит под ключом.
    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "file_sha256")
    private String fileSha256;

    // Профиль проверки: вид работы, семестр, номер опроцентовки. Пока интерфейс их не передаёт.
    @Column(name = "work_type")
    private String workType;

    @Column(name = "semester")
    private Integer semester;

    @Column(name = "stage")
    private Integer stage;

    @Column(name = "trace_id")
    private UUID traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "format_errors", columnDefinition = "jsonb")
    private String formatErrors;

    @Column(name = "teacher_verdict")
    private String teacherVerdict;

    @Lob
    @Column(name = "teacher_comment", columnDefinition = "TEXT")
    private String teacherComment;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "teacher_message_id")
    private Integer teacherMessageId;

    public Submission() {}

    /**
     * Имя файла от пользователя без пути: браузеры иногда присылают «C:\fakepath\работа.pdf».
     * Кавычку браузеры по стандарту HTML передают в multipart как %22 — возвращаем её.
     * Пустое имя заменяется на file.pdf, длинное обрезается до размера столбца.
     */
    public static String cleanFileName(String rawName) {
        if (rawName == null) return DEFAULT_FILE_NAME;
        String name = rawName.substring(Math.max(rawName.lastIndexOf('/'), rawName.lastIndexOf('\\')) + 1)
                .replace("%22", "\"")
                .strip();
        if (name.isEmpty()) return DEFAULT_FILE_NAME;
        return name.length() > MAX_FILE_NAME_LENGTH ? name.substring(0, MAX_FILE_NAME_LENGTH) : name;
    }

    /** Имя файла для показа в интерфейсе, отчёте и уведомлениях. */
    public String displayFileName() {
        return originalFileName == null || originalFileName.isBlank() ? DEFAULT_FILE_NAME : originalFileName;
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public User getStudent() { return student; }
    public void setStudent(User student) { this.student = student; }
    public User getTeacher() { return teacher; }
    public void setTeacher(User teacher) { this.teacher = teacher; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(String fileSha256) { this.fileSha256 = fileSha256; }
    public String getWorkType() { return workType; }
    public void setWorkType(String workType) { this.workType = workType; }
    public Integer getSemester() { return semester; }
    public void setSemester(Integer semester) { this.semester = semester; }
    public Integer getStage() { return stage; }
    public void setStage(Integer stage) { this.stage = stage; }
    public UUID getTraceId() { return traceId; }
    public void setTraceId(UUID traceId) { this.traceId = traceId; }
    public String getFormatErrors() { return formatErrors; }
    public void setFormatErrors(String formatErrors) { this.formatErrors = formatErrors; }
    public String getTeacherVerdict() { return teacherVerdict; }
    public void setTeacherVerdict(String teacherVerdict) { this.teacherVerdict = teacherVerdict; }
    public String getTeacherComment() { return teacherComment; }
    public void setTeacherComment(String teacherComment) { this.teacherComment = teacherComment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getTeacherMessageId() { return teacherMessageId; }
    public void setTeacherMessageId(Integer teacherMessageId) { this.teacherMessageId = teacherMessageId; }
}
