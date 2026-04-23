package com.sapsr.backend.service;

import com.sapsr.backend.entity.EmailVerification;
import com.sapsr.backend.repository.EmailVerificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class EmailVerificationService {

    private final EmailVerificationRepository repository;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${sapsr.teacher.allowed-domain:@bsuir.by}")
    private String allowedDomain;

    public EmailVerificationService(EmailVerificationRepository repository, JavaMailSender mailSender) {
        this.repository = repository;
        this.mailSender = mailSender;
    }

    public boolean isAllowedDomain(String email) {
        if (allowedDomain == null || allowedDomain.isBlank()) return true;
        return email != null && email.toLowerCase().endsWith(allowedDomain.toLowerCase());
    }

    public void sendVerificationCode(String email) {
        String code = String.format("%06d", new Random().nextInt(1_000_000));

        EmailVerification verification = new EmailVerification();
        verification.setEmail(email.toLowerCase());
        verification.setCode(code);
        verification.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        repository.save(verification);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("SAPSR — Код подтверждения");
        message.setText(
                "Здравствуйте!\n\n" +
                "Ваш код подтверждения для регистрации в системе SAPSR:\n\n" +
                "    " + code + "\n\n" +
                "Код действителен 15 минут.\n\n" +
                "Если вы не запрашивали код — просто проигнорируйте это письмо."
        );
        mailSender.send(message);
    }

    public boolean verifyCode(String email, String code) {
        Optional<EmailVerification> opt = repository
                .findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email.toLowerCase());

        if (opt.isEmpty()) return false;

        EmailVerification v = opt.get();
        if (LocalDateTime.now().isAfter(v.getExpiresAt())) return false;
        if (!v.getCode().equals(code)) return false;

        v.setUsed(true);
        repository.save(v);
        return true;
    }
}
