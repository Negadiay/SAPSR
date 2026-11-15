package com.sapsr.backend.controller;

import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ответ с ошибкой по контракту (api-v1.md, раздел 1): {@code code}, {@code message} и, если ошибка в поле, {@code field}.
 * Поле {@code error} с тем же текстом остаётся для прежних версий интерфейса.
 */
public final class ApiErrors {

    private ApiErrors() {}

    public static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return error(status, code, message, null);
    }

    public static ResponseEntity<Map<String, Object>> error(int status, String code, String message, String field) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("error", message);
        if (field != null) body.put("field", field);
        return ResponseEntity.status(status).body(body);
    }

    /** Файл больше предела (решение 15): предел — это предел Telegram на отправку файла ботом. */
    public static ResponseEntity<Map<String, Object>> fileTooLarge(int maxMb) {
        return error(413, "FILE_TOO_LARGE", "Файл больше " + maxMb + " МБ — Telegram не даст переслать его "
                + "преподавателю. Сожмите PDF (картинки) и загрузите снова.");
    }

    public static boolean tooLarge(long sizeBytes, int maxMb) {
        return sizeBytes > maxMb * 1024L * 1024L;
    }
}
