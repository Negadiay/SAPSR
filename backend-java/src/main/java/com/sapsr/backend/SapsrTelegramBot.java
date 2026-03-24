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
public class SapsrTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;
    private final String webAppUrl;

    public SapsrTelegramBot(@Value("${telegram.bot.token}") String botToken,
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
        updates.forEach(update -> {
            if (!update.hasMessage() || !update.getMessage().hasText()) return;

            long chatId = update.getMessage().getChatId();
            sendWebAppButton(chatId);
        });
    }

    private void sendWebAppButton(long chatId) {
        WebAppInfo webAppInfo = WebAppInfo.builder()
                .url(webAppUrl)
                .build();

        InlineKeyboardButton webAppButton = InlineKeyboardButton.builder()
                .text("Открыть кабинет")
                .webApp(webAppInfo)
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(webAppButton))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Добро пожаловать в SAPSR! Нажмите кнопку ниже, чтобы открыть приложение.")
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void notifyUser(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}