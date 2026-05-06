package com.sapsr.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;

@Component
public class SapsrTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final int TELEGRAM_SAFE_TEXT_LIMIT = 3900;

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
        } catch (TelegramApiException | RuntimeException e) {
            e.printStackTrace();
        }
    }

    public void sendWelcomeMessage(long chatId) {
        WebAppInfo webAppInfo = WebAppInfo.builder().url(webAppUrl).build();
        InlineKeyboardButton webAppButton = InlineKeyboardButton.builder()
                .text("Открыть SAPSR")
                .webApp(webAppInfo)
                .build();
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(webAppButton))
                .build();
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Система SAPSR запущена и готова к работе.\nНажмите кнопку ниже, чтобы открыть приложение.")
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public Integer notifyUser(Long chatId, String text) {
        if (chatId == null || text == null || text.isBlank()) return null;

        Integer firstMessageId = null;
        try {
            for (String chunk : splitTelegramText(text)) {
                SendMessage message = SendMessage.builder()
                        .chatId(chatId)
                        .text(chunk)
                        .build();
                Integer messageId = telegramClient.execute(message).getMessageId();
                if (firstMessageId == null) firstMessageId = messageId;
            }
        } catch (TelegramApiException | RuntimeException e) {
            e.printStackTrace();
        }
        return firstMessageId;
    }

    private List<String> splitTelegramText(String text) {
        if (text.length() <= TELEGRAM_TEXT_LIMIT) return List.of(text);

        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + TELEGRAM_SAFE_TEXT_LIMIT, text.length());
            if (end < text.length()) {
                int splitAt = Math.max(
                        Math.max(text.lastIndexOf('\n', end), text.lastIndexOf(' ', end)),
                        start
                );
                if (splitAt > start) end = splitAt;
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) parts.add(part);
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
        return parts;
    }

    public void deleteMessage(Long chatId, Integer messageId) {
        if (chatId == null || messageId == null) return;
        try {
            telegramClient.execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}