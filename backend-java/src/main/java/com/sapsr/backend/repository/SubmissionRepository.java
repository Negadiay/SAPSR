package com.sapsr.backend.repository;

import com.sapsr.backend.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Integer> {
    List<Submission> findByStudent_TelegramIdOrderByCreatedAtDesc(Long telegramId);

    /**
     * Очередь преподавателя (решение 9): проверенные работы без вердикта — и прошедшие оформление,
     * и отклонённые (так засчитывается срок сдачи). Версия, которую заменила более новая, не показывается.
     */
    @Query("""
            select s from Submission s
            where s.teacher.telegramId = :teacherTelegramId
              and upper(trim(coalesce(s.status, ''))) in ('SUCCESS', 'REJECTED')
              and (s.teacherVerdict is null or trim(s.teacherVerdict) = '')
              and not exists (select c.id from Submission c where c.parentId = s.id)
            order by s.createdAt desc
            """)
    List<Submission> findPendingForTeacher(@Param("teacherTelegramId") Long teacherTelegramId);

    /** Последняя работа студента этому преподавателю — кандидат в предыдущие версии новой загрузки. */
    Optional<Submission> findFirstByStudent_TelegramIdAndTeacher_TelegramIdOrderByCreatedAtDescIdDesc(
            Long studentTelegramId, Long teacherTelegramId);

    /** Есть ли у работы более новая версия. */
    boolean existsByParentId(Integer parentId);

    @Query("""
            select s from Submission s
            where s.teacher.telegramId = :teacherTelegramId
              and s.teacherVerdict is not null
              and trim(s.teacherVerdict) <> ''
            order by s.createdAt desc
            """)
    List<Submission> findHistoryForTeacher(@Param("teacherTelegramId") Long teacherTelegramId);
}
