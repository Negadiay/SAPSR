package com.sapsr.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.testsupport.TelegramInitDataSigner;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Подпись данных запуска тестовым токеном: функция подписи и проверка бэкенда сходятся. */
class TelegramInitDataInterceptorTest {

    // Общий пример для Python и Java; тесты Maven запускаются из backend-java
    private static final Path VECTOR = Path.of("..", "test-fixtures", "telegram-init-data.json");
    private static final String TOKEN = TelegramInitDataSigner.TEST_BOT_TOKEN;

    private final TelegramInitDataInterceptor interceptor = new TelegramInitDataInterceptor(TOKEN);

    @Test
    void signerMatchesSharedVector() throws IOException {
        JsonNode vector = new ObjectMapper().readTree(VECTOR.toFile());
        Map<String, String> fields = new TreeMap<>();
        vector.get("fields").properties().forEach(e -> fields.put(e.getKey(), e.getValue().asText()));

        assertThat(TelegramInitDataSigner.hash(fields, vector.get("bot_token").asText()))
                .isEqualTo(vector.get("hash").asText());
    }

    @Test
    void acceptsSignedInitDataAndSetsTelegramId() throws Exception {
        MockHttpServletRequest request = get(TelegramInitDataSigner.forUser(100500L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("telegram_id")).isEqualTo(100500L);
    }

    @Test
    void rejectsInitDataSignedWithAnotherToken() throws Exception {
        String foreign = TelegramInitDataSigner.forUser(100500L, "987654321:AAanother-token", Instant.now());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(get(foreign), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsTamperedUserId() throws Exception {
        String tampered = TelegramInitDataSigner.forUser(100500L).replace("100500", "100501");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(get(tampered), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private static MockHttpServletRequest get(String initData) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", initData);
        return request;
    }
}
