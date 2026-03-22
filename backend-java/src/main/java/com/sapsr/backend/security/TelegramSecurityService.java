package com.sapsr.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TelegramSecurityService {

    @Value("${telegram.bot.token}")
    private String botToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Задача 1: Валидация initData по алгоритму HMAC-SHA-256
    public boolean validateTelegramData(String initData) {
        try {
            Map<String, String> params = Arrays.stream(initData.split("&"))
                    .map(param -> param.split("=", 2))
                    .collect(Collectors.toMap(
                            p -> p[0],
                            p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)
                    ));

            String hash = params.remove("hash");
            if (hash == null) return false;

            // Сортируем ключи по алфавиту и собираем строку проверки
            String dataCheckString = params.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("\n"));

            // Секретный ключ = HMAC_SHA256("WebAppData", botToken)
            byte[] secretKey = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, "WebAppData").hmac(botToken);

            // Вычисляем HMAC от dataCheckString с использованием secretKey
            String computedHash = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secretKey).hmacHex(dataCheckString);

            return computedHash.equals(hash);
        } catch (Exception e) {
            return false;
        }
    }

    // Задача 2: Извлечение telegram_id
    public Long extractUserId(String initData) {
        try {
            // Ищем параметр user=...
            String userParam = Arrays.stream(initData.split("&"))
                    .filter(p -> p.startsWith("user="))
                    .findFirst()
                    .map(p -> URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8))
                    .orElse(null);

            if (userParam == null) return null;

            JsonNode root = objectMapper.readTree(userParam);
            return root.get("id").asLong();
        } catch (Exception e) {
            return null;
        }
    }
}