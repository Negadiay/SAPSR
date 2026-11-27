package com.sapsr.backend.entity;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Курс группы, заданный администратором вместо определённого по номеру группы (решение 11).
 * Действует один учебный год: через год группа на следующем курсе, и исправление уже не нужно.
 */
@Entity
@Table(name = "group_course_overrides")
@IdClass(GroupCourseOverride.Key.class)
public class GroupCourseOverride {

    @Id
    @Column(name = "group_number")
    private String groupNumber;

    @Id
    @Column(name = "academic_year")
    private Integer academicYear;

    @Column(name = "course", nullable = false)
    private Integer course;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public GroupCourseOverride() {}

    public GroupCourseOverride(String groupNumber, Integer academicYear, Integer course,
                               LocalDateTime updatedAt, Long updatedBy) {
        this.groupNumber = groupNumber;
        this.academicYear = academicYear;
        this.course = course;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getGroupNumber() { return groupNumber; }
    public Integer getAcademicYear() { return academicYear; }
    public Integer getCourse() { return course; }
    public void setCourse(Integer course) { this.course = course; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public static class Key implements Serializable {
        private String groupNumber;
        private Integer academicYear;

        public Key() {}

        public Key(String groupNumber, Integer academicYear) {
            this.groupNumber = groupNumber;
            this.academicYear = academicYear;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(groupNumber, k.groupNumber)
                    && Objects.equals(academicYear, k.academicYear);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupNumber, academicYear);
        }
    }
}
