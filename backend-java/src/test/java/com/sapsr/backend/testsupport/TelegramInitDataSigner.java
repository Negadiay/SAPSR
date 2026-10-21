package com.sapsr.backend.testsupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Подписывает данные запуска Telegram Mini App (initData) тестовым токеном бота — для MockMvc и интеграционных тестов.
 * Алгоритм тот же, что проверяет {@code TelegramInitDataInterceptor}; «тестового обхода» проверки в бэкенде нет.
 * Python-двойник: worker-python/tests/telegram_init_data.py; обе реализации сверяются с test-fixtures/telegram-init-data.json.
 */
public final class TelegramInitDataSigner {

    /** Фальшивый токен в формате настоящего; годится только для тестов. */
    public static final String TEST_BOT_TOKEN = "123456789:AAtest-token-for-sapsr-tests-only000";

    private TelegramInitDataSigner() {}

    /** initData пользователя с данным Telegram ID, auth_date — сейчас. Для заголовка {@code Authorization}. */
    public static String forUser(long telegramId) {
        return forUser(telegramId, TEST_BOT_TOKEN, Instant.now());
    }

    public static String forUser(long telegramId, String botToken, Instant authDate) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("auth_date", Long.toString(authDate.getEpochSecond()));
        fields.put("user", "{\"id\":" + telegramId + ",\"first_name\":\"Тест\"}");
        return sign(fields, botToken);
    }

    /** Подписывает готовый набор полей и собирает строку initData. */
    public static String sign(Map<String, String> fields, String botToken) {
        Map<String, String> signed = new TreeMap<>(fields);
        signed.remove("hash");
        signed.put("hash", hash(signed, botToken));
        return signed.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    /** hex(HMAC_SHA256(HMAC_SHA256("WebAppData", token), data_check_string)). */
    public static String hash(Map<String, String> fields, String botToken) {
        String dataCheckString = new TreeMap<>(fields).entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        byte[] secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // %20 вместо «+»: так же, как кодирует Telegram и Python-двойник
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
