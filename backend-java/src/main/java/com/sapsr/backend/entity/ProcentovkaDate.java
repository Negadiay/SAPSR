package com.sapsr.backend.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/** Дата опроцентовки в половине учебного года (решение 11): по ним сервер определяет этап работы. */
@Entity
@Table(name = "procentovka_dates")
@IdClass(ProcentovkaDate.Key.class)
public class ProcentovkaDate {

    @Id
    @Column(name = "academic_year")
    private Integer academicYear;

    @Id
    @Column(name = "half")
    private String half;

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

    public ProcentovkaDate(Integer academicYear, String half, Integer stage, LocalDate heldOn,
                           LocalDateTime updatedAt, Long updatedBy) {
        this.academicYear = academicYear;
        this.half = half;
        this.stage = stage;
        this.heldOn = heldOn;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public Integer getAcademicYear() { return academicYear; }
    public String getHalf() { return half; }
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
        private Integer stage;

        public Key() {}

        public Key(Integer academicYear, String half, Integer stage) {
            this.academicYear = academicYear;
            this.half = half;
            this.stage = stage;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(academicYear, k.academicYear)
                    && Objects.equals(half, k.half) && Objects.equals(stage, k.stage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(academicYear, half, stage);
        }
    }
}
