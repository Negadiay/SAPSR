package com.sapsr.backend.service;

import com.sapsr.backend.entity.User;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Ключ преподавателя для интерфейса студента (план Котко, К7; api-v1.md, раздел 1: чужие Telegram ID не отдаются).
 * Это идентификатор преподавателя в ИИС ({@code url_id}), а если его нет — код, вычисленный из Telegram ID
 * с секретом сервера: по коду Telegram ID не восстановить.
 */
public final class TeacherKey {

    private TeacherKey() {}

    public static String of(User teacher, String secret) {
        if (teacher.getUrlId() != null && !teacher.getUrlId().isBlank()) return teacher.getUrlId().strip();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("sapsr-teacher:" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(String.valueOf(teacher.getTelegramId()).getBytes(StandardCharsets.UTF_8));
            return "t-" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 недоступен", e);
        }
    }

    /** Преподаватель по ключу из {@code GET /teachers}. */
    public static Optional<User> find(List<User> teachers, String key, String secret) {
        if (key == null || key.isBlank()) return Optional.empty();
        return teachers.stream().filter(t -> key.equals(of(t, secret))).findFirst();
    }
}