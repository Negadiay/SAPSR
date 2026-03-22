package com.sapsr.backend.config;

import com.sapsr.backend.security.TelegramAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TelegramAuthInterceptor telegramAuthInterceptor;

    public WebConfig(TelegramAuthInterceptor telegramAuthInterceptor) {
        this.telegramAuthInterceptor = telegramAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Применяем защиту ко всем путям, начинающимся с /api/v1/
        registry.addInterceptor(telegramAuthInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}