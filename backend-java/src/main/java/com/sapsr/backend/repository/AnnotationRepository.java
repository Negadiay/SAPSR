package com.sapsr.backend.repository;

import com.sapsr.backend.entity.Annotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnnotationRepository extends JpaRepository<Annotation, Integer> {
    List<Annotation> findByReviewFileIdOrderByPositionAsc(Integer reviewFileId);

    long countByReviewFileId(Integer reviewFileId);
}
