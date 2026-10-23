package com.sapsr.backend.config;

import com.sapsr.backend.controller.AdminController;
import com.sapsr.backend.controller.ReviewController;
import com.sapsr.backend.controller.TeacherController;
import com.sapsr.backend.controller.TeacherDashboardController;
import com.sapsr.backend.controller.UploadController;
import com.sapsr.backend.controller.VerificationController;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CORS — список адресов из настройки вместо «*» на каждом контроллере (план Жерко, Ж5, пункт 9). */
class WebMvcConfigTest {

    private final TelegramInitDataInterceptor interceptor =
            new TelegramInitDataInterceptor("token", Duration.ofHours(24), Clock.systemUTC());

    @Test
    @Tag("negative")
    void foreignOriginIsRejected() {
        CorsConfiguration api = mappings("https://negadiay.github.io, https://sapsr.ngrok-free.app").get("/api/**");

        assertThat(api.checkOrigin("https://negadiay.github.io")).isEqualTo("https://negadiay.github.io");
        assertThat(api.checkOrigin("https://sapsr.ngrok-free.app")).isNotNull();
        assertThat(api.checkOrigin("https://evil.example")).isNull();
        assertThat(api.getAllowedHeaders()).containsExactlyInAnyOrder("Authorization", "Content-Type");
    }

    @Test
    @Tag("typical")
    void noOriginsMeansSameOriginOnly() {
        // Мини-приложение и API на одном адресе (nginx проксирует /api/): чужим источникам — ничего.
        assertThat(mappings("")).isEmpty();
    }

    @Test
    @Tag("negative")
    void controllersDoNotOpenThemselvesToAnyOrigin() {
        List<Class<?>> controllers = List.of(AdminController.class, ReviewController.class, TeacherController.class,
                TeacherDashboardController.class, UploadController.class, VerificationController.class);
        assertThat(controllers).allSatisfy(c -> assertThat(c.isAnnotationPresent(CrossOrigin.class))
                .as(c.getSimpleName()).isFalse());
    }

    private Map<String, CorsConfiguration> mappings(String origins) {
        ExposedRegistry registry = new ExposedRegistry();
        new WebMvcConfig(interceptor, origins).addCorsMappings(registry);
        return registry.configurations();
    }

    private static final class ExposedRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
