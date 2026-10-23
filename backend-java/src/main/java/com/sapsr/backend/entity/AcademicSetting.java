package com.sapsr.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Настройка учебного календаря, которую меняет администратор (решение 11): пока только «half». */
@Entity
@Table(name = "academic_settings")
public class AcademicSetting {

    public static final String HALF = "half";

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public AcademicSetting() {}

    public AcademicSetting(String key, String value, LocalDateTime updatedAt, Long updatedBy) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
}
