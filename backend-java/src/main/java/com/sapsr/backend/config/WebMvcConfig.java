package com.sapsr.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TelegramInitDataInterceptor telegramInitDataInterceptor;
    private final String[] allowedOrigins;

    /**
     * {@code sapsr.cors.allowed-origins} — адреса, с которых интерфейс может ходить в API с другого источника,
     * через запятую (план Жерко, Ж5, пункт 9). Мини-приложение открывается с того же адреса, что и API
     * (nginx в контейнере интерфейса проксирует /api/), поэтому по умолчанию список пуст: чужие источники
     * не допускаются вовсе.
     */
    public WebMvcConfig(TelegramInitDataInterceptor telegramInitDataInterceptor,
                        @Value("${sapsr.cors.allowed-origins:}") String allowedOrigins) {
        this.telegramInitDataInterceptor = telegramInitDataInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(telegramInitDataInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) return;
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("Authorization", "Content-Type");
    }
}
