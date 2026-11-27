package com.sapsr.backend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.testsupport.TelegramInitDataSigner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Подпись данных запуска тестовым токеном: функция подписи и проверка бэкенда сходятся; свежесть (Ж5, пункт 3). */
class TelegramInitDataInterceptorTest {

    // Общий пример для Python и Java; тесты Maven запускаются из backend-java
    private static final Path VECTOR = Path.of("..", "test-fixtures", "telegram-init-data.json");
    private static final String TOKEN = TelegramInitDataSigner.TEST_BOT_TOKEN;
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private final TelegramInitDataInterceptor interceptor =
            new TelegramInitDataInterceptor(TOKEN, MAX_AGE, Clock.systemUTC());

    @Test
    void signerMatchesSharedVector() throws IOException {
        JsonNode vector = new ObjectMapper().readTree(VECTOR.toFile());
        Map<String, String> fields = new TreeMap<>();
        vector.get("fields").properties().forEach(e -> fields.put(e.getKey(), e.getValue().asText()));

        assertThat(TelegramInitDataSigner.hash(fields, vector.get("bot_token").asText()))
                .isEqualTo(vector.get("hash").asText());
    }

    @Test
    @Tag("typical")
    void acceptsSharedVectorWhileItIsFresh() throws Exception {
        // У общего примера auth_date из 2025 года: сравниваем с часами того же дня, а не с сегодняшними.
        JsonNode vector = new ObjectMapper().readTree(VECTOR.toFile());
        Map<String, String> fields = new TreeMap<>();
        vector.get("fields").properties().forEach(e -> fields.put(e.getKey(), e.getValue().asText()));
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(fields.get("auth_date")));
        String initData = TelegramInitDataSigner.sign(fields, vector.get("bot_token").asText());

        TelegramInitDataInterceptor sameDay = new TelegramInitDataInterceptor(TOKEN, MAX_AGE,
                Clock.fixed(signedAt.plus(Duration.ofHours(3)), ZoneOffset.UTC));
        MockHttpServletRequest request = get(initData);
        assertThat(sameDay.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute("telegram_id")).isEqualTo(vector.get("telegram_id").asLong());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(get(initData), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INIT_DATA_EXPIRED");
    }

    @Test
    void acceptsSignedInitDataAndSetsTelegramId() throws Exception {
        MockHttpServletRequest request = get(TelegramInitDataSigner.forUser(100500L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute("telegram_id")).isEqualTo(100500L);
    }

    @Test
    @Tag("negative")
    void rejectsInitDataOlderThanADay() throws Exception {
        String stale = TelegramInitDataSigner.forUser(100500L, TOKEN, Instant.now().minus(Duration.ofHours(25)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(get(stale), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INIT_DATA_EXPIRED");
    }

    @Test
    @Tag("boundary")
    void toleratesClockSkewButNotFutureDates() throws Exception {
        String slightlyAhead = TelegramInitDataSigner.forUser(100500L, TOKEN, Instant.now().plusSeconds(60));
        String future = TelegramInitDataSigner.forUser(100500L, TOKEN, Instant.now().plus(Duration.ofHours(1)));

        assertThat(interceptor.preHandle(get(slightlyAhead), new MockHttpServletResponse(), new Object())).isTrue();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(get(future), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @Tag("negative")
    void rejectsSignedDataWithoutAuthDate() throws Exception {
        Map<String, String> fields = new TreeMap<>(Map.of("user", "{\"id\":100500}"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(get(TelegramInitDataSigner.sign(fields, TOKEN)), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @Tag("negative")
    void userIdIsTakenFromJsonNotFromSubstring() throws Exception {
        // Раньше id вырезался из строки по первым цифрам после «"id"»: здесь он строкой — пользователя нет.
        Map<String, String> fields = new TreeMap<>(Map.of(
                "auth_date", Long.toString(Instant.now().getEpochSecond()),
                "user", "{\"id\":\"100500\",\"first_name\":\"Тест\"}"));
        MockHttpServletRequest request = get(TelegramInitDataSigner.sign(fields, TOKEN));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(request.getAttribute("telegram_id")).isNull();
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
