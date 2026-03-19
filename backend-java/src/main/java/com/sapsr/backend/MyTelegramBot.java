package com.sapsr.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Component
public class MyTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;

    public MyTelegramBot(@Value("${bot.token}") String botToken) {
        this.botToken = botToken;
        this.telegramClient = new OkHttpTelegramClient(botToken);
        System.out.println(">>> [STEP 1] Бот инициализирован токеном: " + botToken.substring(0, 10) + "...");
    }

    @Override
    public String getBotToken() {
        // Этот метод библиотека вызывает постоянно, чтобы проверить токен
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        System.out.println(">>> [STEP 2] Библиотека запросила UpdateConsumer");
        return this;
    }

    @Override
    public void consume(List<Update> updates) {
        // Базовый метод, который получает список обновлений
        System.out.println(">>> [STEP 3] Входящее событие! Количество обновлений: " + updates.size());

        updates.forEach(update -> {
            if (update.hasMessage() && update.getMessage().hasText()) {
                String text = update.getMessage().getText();
                long chatId = update.getMessage().getChatId();

                System.out.println(">>> [STEP 4] Текст сообщения: " + text);

                try {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(chatId)
                            .text("Эхо: " + text)
                            .build());
                    System.out.println(">>> [STEP 5] Ответ отправлен успешно");
                } catch (TelegramApiException e) {
                    System.err.println(">>> ОШИБКА ПРИ ОТПРАВКЕ: " + e.getMessage());
                }
            } else {
                System.out.println(">>> [STEP 4] Получено обновление, но это не текст.");
            }
        });
    }
}