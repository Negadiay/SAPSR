package com.sapsr.backend;

import com.sapsr.backend.entity.User;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SapsrTelegramBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;
    private final String webAppUrl;
    private final UserRepository userRepository;

    private static final Pattern STUDENT_PATTERN = Pattern.compile("^(.+),\\s*(\\d{6})$");
    private static final String BSUIR_DOMAIN = "@bsuir.by";

    public SapsrTelegramBot(@Value("${telegram.bot.token}") String botToken,
                            @Value("${bot.webapp.url}") String webAppUrl,
                            UserRepository userRepository) {
        this.botToken = botToken;
        this.webAppUrl = webAppUrl;
        this.userRepository = userRepository;
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

            String text = update.getMessage().getText().trim();
            long chatId = update.getMessage().getChatId();

            if ("/start".equals(text)) {
                Optional<User> existing = userRepository.findById(chatId);
                if (existing.isPresent()) {
                    sendTextMessage(chatId, "С возвращением, " + existing.get().getFullName() + "!");
                    sendStartMessage(chatId);
                } else {
                    sendTextMessage(chatId,
                            "Добро пожаловать! Введите ФИО и номер группы " +
                            "(например: Иванов И.И., 123456), " +
                            "либо почту @bsuir.by, если вы преподаватель.");
                }
                return;
            }

            Optional<User> existing = userRepository.findById(chatId);
            if (existing.isPresent()) {
                sendStartMessage(chatId);
                return;
            }

            if (text.contains(BSUIR_DOMAIN)) {
                User teacher = new User();
                teacher.setTelegramId(chatId);
                teacher.setRole("TEACHER");
                teacher.setFullName(text);
                userRepository.save(teacher);

                sendTextMessage(chatId,
                        "Роль: ПРЕПОДАВАТЕЛЬ\n" +
                        "[DEBUG] Код подтверждения: 1234. Введите его в чат.");
                return;
            }

            Matcher m = STUDENT_PATTERN.matcher(text);
            if (m.matches()) {
                String fullName = m.group(1).trim();
                String group = m.group(2).trim();

                User student = new User();
                student.setTelegramId(chatId);
                student.setRole("STUDENT");
                student.setFullName(fullName + " (гр. " + group + ")");
                userRepository.save(student);

                sendTextMessage(chatId, "Вы зарегистрированы как СТУДЕНТ: " + fullName + ", группа " + group);
                sendStartMessage(chatId);
                return;
            }

            if (text.equals("1234")) {
                sendTextMessage(chatId, "Почта подтверждена! Теперь вы можете пользоваться системой.");
                sendStartMessage(chatId);
                return;
            }

            sendTextMessage(chatId,
                    "Не удалось распознать формат. Отправьте:\n" +
                    "• ФИО, номер группы (Иванов И.И., 123456)\n" +
                    "• или email@bsuir.by для преподавателей");
        });
    }

    private void sendStartMessage(long chatId) {
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
                .text("Нажмите кнопку ниже, чтобы открыть SAPSR")
                .replyMarkup(keyboard)
                .build();

        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendTextMessage(long chatId, String text) {
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

    public void notifyUser(Long chatId, String text) {
        sendTextMessage(chatId, text);
    }
}