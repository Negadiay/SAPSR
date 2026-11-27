package com.sapsr.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Проверка данных запуска Telegram Mini App (initData): подпись HMAC-SHA256 токеном бота и свежесть.
 * Без свежести перехваченная строка действовала бы вечно (план Жерко, Ж5, пункты 3 и 5).
 */
@Component
public class TelegramInitDataInterceptor implements HandlerInterceptor {

    /** Допуск на расхождение часов телефона, Telegram и сервера. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String botToken;
    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public TelegramInitDataInterceptor(@Value("${telegram.bot.token}") String botToken,
                                       @Value("${sapsr.telegram.init-data-max-age:24h}") String maxAge) {
        this(botToken, DurationStyle.detectAndParse(maxAge), Clock.systemUTC());
    }

    public TelegramInitDataInterceptor(String botToken, Duration maxAge, Clock clock) {
        this.botToken = botToken;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String initData = request.getHeader("Authorization");

        // Для GET-запросов (скачивание файлов) принимаем initData из query-параметра tg_auth
        if ((initData == null || initData.isBlank()) && "GET".equals(request.getMethod())) {
            initData = request.getParameter("tg_auth");
        }

        if (initData == null || initData.isBlank()) {
            return reject(response, 401, "NO_INIT_DATA", "Откройте приложение из Telegram.");
        }

        Map<String, String> params;
        try {
            params = parseInitData(initData);
        } catch (IllegalArgumentException e) {
            return reject(response, 401, "INIT_DATA_FORMAT", "Откройте приложение из Telegram.");
        }
        String hash = params.remove("hash");
        if (hash == null) {
            return reject(response, 401, "INIT_DATA_FORMAT", "Откройте приложение из Telegram.");
        }
        if (!verifyHash(params, hash)) {
            return reject(response, 403, "INIT_DATA_SIGNATURE", "Откройте приложение из Telegram.");
        }

        Instant authDate = parseAuthDate(params.get("auth_date"));
        Instant now = clock.instant();
        if (authDate == null || authDate.isAfter(now.plus(CLOCK_SKEW)) || authDate.isBefore(now.minus(maxAge))) {
            return reject(response, 401, "INIT_DATA_EXPIRED",
                    "Сессия устарела. Закройте и откройте приложение.");
        }

        Long telegramId = extractTelegramId(params.get("user"));
        if (telegramId != null) {
            request.setAttribute("telegram_id", telegramId);
        }
        return true;
    }

    private static boolean reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.createObjectNode().put("code", code).put("error", message).toString());
        return false;
    }

    private Map<String, String> parseInitData(String initData) {
        Map<String, String> params = new TreeMap<>();
        String[] pairs = initData.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    private boolean verifyHash(Map<String, String> params, String hash) throws Exception {
        String[] sortedEntries = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        String dataCheckString = String.join("\n", sortedEntries);

        Mac hmacSha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeyForToken = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmacSha256.init(secretKeyForToken);
        byte[] secretKey = hmacSha256.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

        Mac hmacData = Mac.getInstance("HmacSHA256");
        hmacData.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        byte[] calculatedHash = hmacData.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8));

        // Сравнение за постоянное время: по времени ответа нельзя подобрать подпись побайтно.
        byte[] expected = HexFormat.of().formatHex(calculatedHash).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private static Instant parseAuthDate(String value) {
        if (value == null || !value.matches("\\d{1,12}")) return null;
        return Instant.ofEpochSecond(Long.parseLong(value));
    }

    /** Идентификатор пользователя — разбором JSON, а не поиском подстроки. */
    private static Long extractTelegramId(String userJson) {
        if (userJson == null) return null;
        try {
            JsonNode id = JSON.readTree(userJson).path("id");
            return id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0 ? id.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
