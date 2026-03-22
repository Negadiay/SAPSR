package com.sapsr.backend.security;

import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class TelegramSecurityServiceTest {

    private TelegramSecurityService securityService;
    private final String testToken = "123456789:ABCdefGHIjklMNOpqrsTUVwxyZ"; // Фейковый токен для теста

    @BeforeEach
    void setUp() {
        securityService = new TelegramSecurityService();
        // Внедряем токен в private поле сервиса, так как @Value не работает в обычном Unit-тесте
        ReflectionTestUtils.setField(securityService, "botToken", testToken);
    }

    @Test
    @DisplayName("Должен успешно валидировать корректные данные Telegram")
    void shouldValidateCorrectData() {
        // 1. Готовим данные (имитируем то, что придет от фронтенда)
        String authDate = "1710000000";
        String queryId = "AAHdAn0BAAAAAN0CfQE";
        String userJson = "{\"id\":123456789,\"first_name\":\"Ivan\",\"last_name\":\"Ivanov\",\"username\":\"ivan_dev\"}";

        // 2. Формируем строку проверки (data_check_string) - ключи строго по алфавиту
        // Важно: порядок auth_date, query_id, user
        String dataCheckString = "auth_date=" + authDate + "\n" +
                "query_id=" + queryId + "\n" +
                "user=" + userJson;

        // 3. Вычисляем правильный хеш по алгоритму Telegram
        byte[] secretKey = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, "WebAppData").hmac(testToken);
        String correctHash = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secretKey).hmacHex(dataCheckString);

        // 4. Собираем финальную строку initData
        String initData = "query_id=" + queryId + "&user=" + userJson + "&auth_date=" + authDate + "&hash=" + correctHash;

        // Проверка
        assertTrue(securityService.validateTelegramData(initData), "Валидация должна пройти успешно");
    }

    @Test
    @DisplayName("Должен отклонить данные с неверным хешем")
    void shouldRejectInvalidHash() {
        String initData = "query_id=123&user={}&auth_date=1710000000&hash=wrong_hash_here";
        assertFalse(securityService.validateTelegramData(initData), "Валидация должна провалиться");
    }

    @Test
    @DisplayName("Должен правильно извлекать Telegram ID из JSON")
    void shouldExtractCorrectUserId() {
        String userJson = "{\"id\":987654321,\"first_name\":\"Test\"}";
        String initData = "user=" + userJson + "&hash=anyhash";

        Long extractedId = securityService.extractUserId(initData);

        assertEquals(987654321L, extractedId, "ID должен совпадать с тем, что в JSON");
    }

    @Test
    @DisplayName("Должен возвращать null, если user отсутствует в строке")
    void shouldReturnNullIfNoUser() {
        String initData = "query_id=123&hash=anyhash";
        assertNull(securityService.extractUserId(initData));
    }
}