package com.sapsr.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TelegramInitDataInterceptor telegramInitDataInterceptor;

    public WebMvcConfig(TelegramInitDataInterceptor telegramInitDataInterceptor) {
        this.telegramInitDataInterceptor = telegramInitDataInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(telegramInitDataInterceptor)
                .addPathPatterns("/api/**");
    }
}
