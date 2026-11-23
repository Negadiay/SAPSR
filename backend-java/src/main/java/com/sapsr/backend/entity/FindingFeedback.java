package com.sapsr.backend.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Отметка преподавателя у замечания автопроверки: «верно» или «неверно» (решение 17, миграция V10). */
@Entity
@Table(name = "finding_feedback")
public class FindingFeedback {

    public static final String CORRECT = "correct";
    public static final String INCORRECT = "incorrect";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_id", nullable = false)
    private Integer submissionId;

    @Column(name = "finding_id", nullable = false)
    private String findingId;

    // Из отчёта, а не из запроса: по ним считается качество правил и модели.
    @Column(name = "requirement_id", nullable = false)
    private String requirementId;

    @Column(name = "origin", nullable = false)
    private String origin;

    @Column(name = "verdict", nullable = false)
    private String verdict;

    @Column(name = "teacher_telegram_id", nullable = false)
    private Long teacherTelegramId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public FindingFeedback() {}

    public Long getId() { return id; }
    public Integer getSubmissionId() { return submissionId; }
    public void setSubmissionId(Integer submissionId) { this.submissionId = submissionId; }
    public String getFindingId() { return findingId; }
    public void setFindingId(String findingId) { this.findingId = findingId; }
    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public Long getTeacherTelegramId() { return teacherTelegramId; }
    public void setTeacherTelegramId(Long teacherTelegramId) { this.teacherTelegramId = teacherTelegramId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}