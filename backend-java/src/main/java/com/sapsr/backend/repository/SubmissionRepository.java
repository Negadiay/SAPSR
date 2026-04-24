package com.sapsr.backend.repository;

import com.sapsr.backend.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Integer> {
    List<Submission> findByStudent_TelegramIdOrderByCreatedAtDesc(Long telegramId);
    List<Submission> findByTeacher_TelegramIdAndStatusAndTeacherVerdictIsNullOrderByCreatedAtDesc(Long teacherTelegramId, String status);
}
