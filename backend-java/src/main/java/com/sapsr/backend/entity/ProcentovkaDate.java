package com.sapsr.backend.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Срок в половине учебного года для одного курса (решение 11): stage 1–3 — опроцентовки, по ним сервер
 * определяет этап работы; stage 4 ({@link #DEADLINE}) — срок сдачи работы.
 */
@Entity
@Table(name = "procentovka_dates")
@IdClass(ProcentovkaDate.Key.class)
public class ProcentovkaDate {

    /** Номер «этапа» для срока сдачи работы. */
    public static final int DEADLINE = 4;

    @Id
    @Column(name = "academic_year")
    private Integer academicYear;

    @Id
    @Column(name = "half")
    private String half;

    @Id
    @Column(name = "course")
    private Integer course;

    @Id
    @Column(name = "stage")
    private Integer stage;

    @Column(name = "held_on", nullable = false)
    private LocalDate heldOn;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public ProcentovkaDate() {}

    public ProcentovkaDate(Integer academicYear, String half, Integer course, Integer stage, LocalDate heldOn,
                           LocalDateTime updatedAt, Long updatedBy) {
        this.academicYear = academicYear;
        this.half = half;
        this.course = course;
        this.stage = stage;
        this.heldOn = heldOn;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public Integer getAcademicYear() { return academicYear; }
    public String getHalf() { return half; }
    public Integer getCourse() { return course; }
    public Integer getStage() { return stage; }
    public LocalDate getHeldOn() { return heldOn; }
    public void setHeldOn(LocalDate heldOn) { this.heldOn = heldOn; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public static class Key implements Serializable {
        private Integer academicYear;
        private String half;
        private Integer course;
        private Integer stage;

        public Key() {}

        public Key(Integer academicYear, String half, Integer course, Integer stage) {
            this.academicYear = academicYear;
            this.half = half;
            this.course = course;
            this.stage = stage;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(academicYear, k.academicYear) && Objects.equals(half, k.half)
                    && Objects.equals(course, k.course) && Objects.equals(stage, k.stage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(academicYear, half, course, stage);
        }
    }
}
