package com.sapsr.backend.repository;

import com.sapsr.backend.entity.ReviewFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReviewFileRepository extends JpaRepository<ReviewFile, Integer> {
    Optional<ReviewFile> findBySubmissionId(Integer submissionId);
}
