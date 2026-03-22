package com.sapsr.backend;

import com.sapsr.backend.model.User;
import com.sapsr.backend.repository.UserRepository;
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
import java.util.Optional;

@Component
public class SapsrTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;
    private final String webAppUrl;
    private final UserRepository userRepository;

    public SapsrTelegramBot(@Value("${telegram.bot.token}") String botToken,
                            @Value("${bot.webapp.url}") String webAppUrl,
                            UserRepository userRepository) {
        this.botToken = botToken;
        this.webAppUrl = webAppUrl;
        this.userRepository = userRepository;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public String getBotToken() { return botToken; }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() { return this; }

    @Override
    public void consume(List<Update> updates) {
        updates.forEach(update -> {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleTextMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallback(update);
            }
        });
    }

    private void handleTextMessage(Update update) {
        String text = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();
        Optional<User> userOpt = userRepository.findById(chatId);

        if ("/start".equals(text)) {
            if (userOpt.isPresent() && userOpt.get().getRegistrationState() == null) {
                sendStartMessage(chatId, "Рады видеть вас снова!");
            } else {
                startRegistration(chatId);
            }
            return;
        }

        // Логика стейт-машины (регистрация)
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String state = user.getRegistrationState();

            if ("AWAITING_NAME".equals(state)) {
                user.setFullName(text);
                user.setRegistrationState("AWAITING_GROUP");
                userRepository.save(user);
                String prompt = "STUDENT".equals(user.getRole()) ? "Введите вашу группу:" : "Введите секретный код преподавателя:";
                sendMessage(chatId, "Принято. " + prompt);
            } else if ("AWAITING_GROUP".equals(state)) {
                user.setGroupOrCode(text);
                user.setRegistrationState(null); // Регистрация завершена
                userRepository.save(user);
                sendStartMessage(chatId, "Регистрация успешно завершена!");
            }
        }
    }

    private void startRegistration(long chatId) {
        User newUser = new User();
        newUser.setTelegramId(chatId);
        newUser.setRegistrationState("CHOOSING_ROLE");
        userRepository.save(newUser);

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("Студент").callbackData("ROLE_STUDENT").build(),
                        InlineKeyboardButton.builder().text("Преподаватель").callbackData("ROLE_TEACHER").build()
                )).build();

        sendMessageWithKeyboard(chatId, "Добро пожаловать в SAPSR! Для начала выберите вашу роль:", markup);
    }

    private void handleCallback(Update update) {
        String data = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        User user = userRepository.findById(chatId).orElseThrow();

        if (data.startsWith("ROLE_")) {
            user.setRole(data.replace("ROLE_", ""));
            user.setRegistrationState("AWAITING_NAME");
            userRepository.save(user);
            sendMessage(chatId, "Введите ваше ФИО:");
        }
    }

    private void sendStartMessage(long chatId, String welcomeText) {
        WebAppInfo webAppInfo = WebAppInfo.builder().url(webAppUrl).build();
        InlineKeyboardButton webAppButton = InlineKeyboardButton.builder()
                .text("Открыть кабинет").webApp(webAppInfo).build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(webAppButton)).build();

        sendMessageWithKeyboard(chatId, welcomeText + " Нажмите кнопку ниже, чтобы сдать работу", keyboard);
    }

    // Вспомогательные методы отправки
    private void sendMessage(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendMessageWithKeyboard(long chatId, String text, InlineKeyboardMarkup markup) {
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).replyMarkup(markup).build());
        } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    // Публичный метод для отправки уведомлений
    public void sendMessageToUser(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML") // Позволяет использовать <b> или <i> теги
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Ошибка при отправке уведомления пользователю " + chatId + ": " + e.getMessage());
        }
    }
}