package com.sapsr.backend.service;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class StartupNotificationService {

    private static final Logger log = LoggerFactory.getLogger(StartupNotificationService.class);

    private final UserRepository userRepository;
    private final SapsrTelegramBot bot;

    public StartupNotificationService(UserRepository userRepository, SapsrTelegramBot bot) {
        this.userRepository = userRepository;
        this.bot = bot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        CompletableFuture.runAsync(() -> {
            List<User> users = userRepository.findAll();
            log.info("Отправка приветственного сообщения {} пользователям...", users.size());
            int sent = 0;
            for (User user : users) {
                try {
                    bot.sendWelcomeMessage(user.getTelegramId());
                    sent++;
                    // Небольшая задержка, чтобы не превысить лимиты Telegram Bot API
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Не удалось отправить приветствие пользователю {}: {}", user.getTelegramId(), e.getMessage());
                }
            }
            log.info("Приветственные сообщения отправлены: {}/{}", sent, users.size());
        });
    }
}
