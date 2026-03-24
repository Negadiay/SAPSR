package com.sapsr.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VerificationService {

    private static final int CODE_LENGTH = 6;
    private static final long CODE_TTL_SECONDS = 300; // 5 минут

    private final Map<String, VerificationEntry> codes = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${sapsr.security.dev-mode:false}")
    private boolean devMode;

    public String generateAndSend(String email) {
        String code = generateCode();
        codes.put(email.toLowerCase(), new VerificationEntry(code, Instant.now()));

        if (devMode || mailSender == null) {
            System.out.println("[VERIFICATION] Код для " + email + ": " + code);
        } else {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("SAPSR — Код подтверждения");
            message.setText("Ваш код подтверждения: " + code + "\n\nКод действителен 5 минут.");
            mailSender.send(message);
        }

        return devMode ? code : null;
    }

    public boolean verify(String email, String code) {
        VerificationEntry entry = codes.get(email.toLowerCase());
        if (entry == null) return false;

        boolean expired = Instant.now().isAfter(entry.createdAt.plusSeconds(CODE_TTL_SECONDS));
        if (expired) {
            codes.remove(email.toLowerCase());
            return false;
        }

        boolean valid = entry.code.equals(code.trim());
        if (valid) {
            codes.remove(email.toLowerCase());
        }
        return valid;
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int code = random.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", code);
    }

    private record VerificationEntry(String code, Instant createdAt) {}
}
