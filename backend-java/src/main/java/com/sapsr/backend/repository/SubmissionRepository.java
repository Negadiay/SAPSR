package com.sapsr.backend.repository;

import com.sapsr.backend.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Integer> {
    List<Submission> findByStudent_TelegramIdOrderByCreatedAtDesc(Long telegramId);

    @Query("""
            select s from Submission s
            where s.teacher.telegramId = :teacherTelegramId
              and upper(trim(coalesce(s.status, ''))) = 'SUCCESS'
              and (s.teacherVerdict is null or trim(s.teacherVerdict) = '')
            order by s.createdAt desc
            """)
    List<Submission> findPendingForTeacher(@Param("teacherTelegramId") Long teacherTelegramId);

    @Query("""
            select s from Submission s
            where s.teacher.telegramId = :teacherTelegramId
              and s.teacherVerdict is not null
              and trim(s.teacherVerdict) <> ''
            order by s.createdAt desc
            """)
    List<Submission> findHistoryForTeacher(@Param("teacherTelegramId") Long teacherTelegramId);
}
