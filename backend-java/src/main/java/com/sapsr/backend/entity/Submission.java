package com.sapsr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "submissions")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "student_telegram_id", referencedColumnName = "telegram_id")
    private User student;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "format_errors", columnDefinition = "jsonb")
    private String formatErrors;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Submission() {
    }

    public Submission(User student, String status, String filePath) {
        this.student = student;
        this.status = status;
        this.filePath = filePath;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public User getStudent() {
        return student;
    }

    public void setStudent(User student) {
        this.student = student;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFormatErrors() {
        return formatErrors;
    }

    public void setFormatErrors(String formatErrors) {
        this.formatErrors = formatErrors;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
