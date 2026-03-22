package com.sapsr.backend.service;

import com.sapsr.backend.SapsrTelegramBot;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ResultOrchestrator {

    private final SapsrTelegramBot telegramBot;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResultOrchestrator(SapsrTelegramBot telegramBot, NotificationService notificationService) {
        this.telegramBot = telegramBot;
        this.notificationService = notificationService;
    }

    // Метод будет вызываться автоматически, когда в очереди появится результат от Питона
    @RabbitListener(queues = "${sapsr.rabbitmq.results-queue}")
    public void processCheckResult(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            Long userId = json.get("user_id").asLong();
            String status = json.get("status").asText();

            if ("SUCCESS".equals(status)) {
                telegramBot.sendMessageToUser(userId, notificationService.getSuccessMessage());
                // Тут можно добавить логику уведомления преподавателя
            } else {
                telegramBot.sendMessageToUser(userId, notificationService.getFailureMessage());
            }

        } catch (Exception e) {
            System.err.println("Ошибка обработки результата проверки: " + e.getMessage());
        }
    }
}