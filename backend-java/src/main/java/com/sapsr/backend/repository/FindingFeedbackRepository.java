package com.sapsr.backend.repository;

import com.sapsr.backend.entity.FindingFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FindingFeedbackRepository extends JpaRepository<FindingFeedback, Long> {

    List<FindingFeedback> findBySubmissionId(Integer submissionId);

    Optional<FindingFeedback> findBySubmissionIdAndFindingId(Integer submissionId, String findingId);

    /**
     * Сколько замечаний преподаватели отметили верными и неверными — по происхождению замечания и его категории
     * (категория берётся из сохранённого отчёта работы). Строка: origin, category, verdict, число.
     */
    @Query(value = """
            select f.origin, coalesce(j ->> 'category', 'unknown') as category, f.verdict, count(*)
            from finding_feedback f
            join submissions s on s.id = f.submission_id
            left join lateral jsonb_array_elements(
                case when jsonb_typeof(s.report -> 'findings') = 'array' then s.report -> 'findings' else '[]'::jsonb end
            ) j on j ->> 'id' = f.finding_id
            group by f.origin, coalesce(j ->> 'category', 'unknown'), f.verdict
            order by 1, 2, 3
            """, nativeQuery = true)
    List<Object[]> countByOriginCategoryAndVerdict();
}