package com.sapsr.backend.service;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.bot.AppLink;
import com.sapsr.backend.bot.BotMessages;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * При запуске: кнопка «Открыть» у поля ввода с текущим адресом мини-приложения и сообщение «SAPSR снова
 * работает» всем пользователям — тестеры узнают, что можно продолжать (решение 12, правка 26.10.2026).
 * Выключается {@code sapsr.bot.startup-notice=false}: так делают изолированные прогоны и отладка.
 */
@Service
public class StartupNotificationService {

    private static final Logger log = LoggerFactory.getLogger(StartupNotificationService.class);

    private final UserRepository userRepository;
    private final SapsrTelegramBot bot;
    private final boolean notifyUsers;

    public StartupNotificationService(UserRepository userRepository, SapsrTelegramBot bot,
                                      @Value("${sapsr.bot.startup-notice:true}") boolean notifyUsers) {
        this.userRepository = userRepository;
        this.bot = bot;
        this.notifyUsers = notifyUsers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        CompletableFuture.runAsync(() -> {
            bot.configureMenu();
            if (!notifyUsers) return;
            List<User> users = userRepository.findAll();
            log.info("Сообщение о запуске — {} пользователям", users.size());
            int sent = 0;
            for (User user : users) {
                try {
                    if (bot.send(user.getTelegramId(), BotMessages.startupNotice(), AppLink.forRole(user.getRole())) != null) sent++;
                    // Небольшая задержка, чтобы не превысить лимиты Telegram Bot API
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("Сообщение о запуске отправлено: {}/{}", sent, users.size());
        });
    }
}
