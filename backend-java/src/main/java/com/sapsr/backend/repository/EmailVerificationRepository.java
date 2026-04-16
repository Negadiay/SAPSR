package com.sapsr.backend.repository;

import com.sapsr.backend.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Integer> {
    Optional<EmailVerification> findTopByEmailAndUsedFalseOrderByCreatedAtDesc(String email);
}
