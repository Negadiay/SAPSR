package com.sapsr.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

@Component
public class MyTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;
    private final String webAppUrl;

    // Внедряем значения по ключам из application.properties
    public MyTelegramBot(@Value("${telegram.bot.token}") String botToken,
                         @Value("${bot.webapp.url}") String webAppUrl) {
        this.botToken = botToken;
        this.webAppUrl = webAppUrl;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(List<Update> updates) {
        updates.forEach(this::processUpdate);
    }

    private void processUpdate(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if ("/start".equals(text)) {
                sendStartMessage(chatId);
            } else {
                sendDefaultMessage(chatId);
            }
        }
    }

    private void sendStartMessage(long chatId) {
        // Создаем WebAppInfo с URL фронтенда
        WebAppInfo webAppInfo = WebAppInfo.builder()
                .url(webAppUrl)
                .build();

        // Кнопка для запуска Web App
        InlineKeyboardButton webAppButton = InlineKeyboardButton.builder()
                .text("Открыть приложение SAPSR 🚀")
                .webApp(webAppInfo)
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(webAppButton))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("*Привет!*\n\nНажми на кнопку ниже, чтобы открыть веб-интерфейс и начать обучение.")
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendDefaultMessage(long chatId) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Используй команду /start для запуска приложения.")
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}