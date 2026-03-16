package com.sapsr.backend.bot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
public class MyTelegramBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final String token;
    private final TelegramClient telegramClient;

    public MyTelegramBot(@Value("${bot.token}") String token) {
        this.token = token;
        this.telegramClient = new OkHttpTelegramClient(token);
    }

    @Override
    public String getBotToken() { return token; }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() { return this; }

    @Override
    public void consume(Update update) {
        System.out.println("(!) Я увидел обновление от Telegram!"); // Это ДОЛЖНО быть в консоли

        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            System.out.println("Текст: " + text);

            if (text.equals("/start")) {
                sendStartMessage(chatId); // Чтобы кнопка сработала
            } else {
                SendMessage message = SendMessage.builder()
                        .chatId(chatId)
                        .text("Вы написали: " + text)
                        .build();
                try {
                    telegramClient.execute(message);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void sendStartMessage(long chatId) {
        // 1. Создаем кнопку WebApp
        InlineKeyboardButton webAppButton = InlineKeyboardButton.builder()
                .text("Открыть приложение")
                .webApp(new WebAppInfo("https://google.com"))
                .build();

        // 2. Создаем строку и передаем в неё кнопку (исправлен ClassCastException)
        InlineKeyboardRow row = new InlineKeyboardRow(webAppButton);

        // 3. Создаем разметку
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(row)
                .build();

        // 4. Формируем сообщение
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Привет! Нажми на кнопку ниже, чтобы открыть Web App:")
                .replyMarkup(markup)
                .build();

        // 5. Отправляем сообщение
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка при отправке сообщения: " + e.getMessage());
            e.printStackTrace();
        }
    }
}