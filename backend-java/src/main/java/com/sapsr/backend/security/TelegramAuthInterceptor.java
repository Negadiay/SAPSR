package com.sapsr.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TelegramAuthInterceptor implements HandlerInterceptor {

    private final TelegramSecurityService securityService;

    public TelegramAuthInterceptor(TelegramSecurityService securityService) {
        this.securityService = securityService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. Извлекаем заголовок Authorization
        String initData = request.getHeader("Authorization");

        // 2. Если заголовка нет или данные невалидны — прерываем запрос
        if (initData == null || !securityService.validateTelegramData(initData)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Telegram Authorization Failed");
            return false; // Запрос дальше не пойдет
        }

        // 3. Извлекаем ID пользователя
        Long telegramId = securityService.extractUserId(initData);
        if (telegramId == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid User Data");
            return false;
        }

        // 4. Кладем ID в атрибуты запроса, чтобы контроллер мог его достать
        request.setAttribute("currentTelegramId", telegramId);

        return true; // Разрешаем выполнение запроса (переход к контроллеру)
    }
}