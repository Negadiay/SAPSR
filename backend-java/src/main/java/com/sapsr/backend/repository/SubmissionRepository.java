package com.sapsr.backend.repository;

import com.sapsr.backend.entity.Submission;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /**
     * Работа под блокировкой строки до конца транзакции. Результаты, ход проверки и сторож приходят
     * из разных потоков; без блокировки один перезаписал бы изменения другого или уведомил бы дважды.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Submission s where s.id = :id")
    Optional<Submission> findByIdForUpdate(@Param("id") Integer id);

    /**
     * Ход проверки (pdf_progress_queue): этап для индикатора; первое сообщение переводит работу из
     * очереди в проверку. Одним запросом — чтобы не затереть статус, записанный результатом.
     * После окончательного отчёта этапы уже не важны.
     */
    @Transactional
    @Modifying
    @Query("""
            update Submission s
            set s.pipelineStage = :stage,
                s.status = case when s.status = 'QUEUED' then 'PROCESSING' else s.status end,
                s.processingStartedAt = coalesce(s.processingStartedAt, :now)
            where s.id = :id
              and (s.contentStatus is null or s.contentStatus = 'PENDING' or s.contentStatus = 'LATE')
            """)
    int recordProgress(@Param("id") Integer id, @Param("stage") String stage, @Param("now") LocalDateTime now);

    /** Содержательная проверка идёт дольше тайм-аута с прихода фазы format. */
    @Query("select s.id from Submission s where s.contentStatus = 'PENDING' and s.formatReceivedAt < :before")
    List<Integer> findContentOverdue(@Param("before") LocalDateTime before);

    /** Содержание опаздывает (работа уже у преподавателя) и после фазы format прошло больше :before — итога не будет. */
    @Query("select s.id from Submission s where s.contentStatus = 'LATE' and s.formatReceivedAt < :before")
    List<Integer> findContentGaveUp(@Param("before") LocalDateTime before);

    /** Проверка началась, а фазы format нет дольше stuck-timeout — обработчик, скорее всего, упал. */
    @Query("""
            select s.id from Submission s
            where s.status = 'PROCESSING' and s.formatReceivedAt is null and s.processingStartedAt < :before
            """)
    List<Integer> findStuck(@Param("before") LocalDateTime before);

    /** Номер сообщения преподавателю — чтобы удалить его, если студент отзовёт работу. */
    @Transactional
    @Modifying
    @Query("update Submission s set s.teacherMessageId = :messageId where s.id = :id")
    int setTeacherMessageId(@Param("id") Integer id, @Param("messageId") Integer messageId);

    /** Номер сообщения студенту об итоге проверки — чтобы дописать в него итог содержания. */
    @Transactional
    @Modifying
    @Query("update Submission s set s.studentMessageId = :messageId where s.id = :id")
    int setStudentMessageId(@Param("id") Integer id, @Param("messageId") Integer messageId);

    @Query("""
            select s from Submission s
            where s.teacher.telegramId = :teacherTelegramId
              and s.teacherVerdict is not null
              and trim(s.teacherVerdict) <> ''
            order by s.createdAt desc
            """)
    List<Submission> findHistoryForTeacher(@Param("teacherTelegramId") Long teacherTelegramId);
}
