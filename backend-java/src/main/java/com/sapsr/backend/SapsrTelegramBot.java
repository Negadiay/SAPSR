package com.sapsr.backend;

import com.sapsr.backend.bot.BotMessages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Бот SAPSR: уведомления о проверке и решении преподавателя, команды /start и /help.
 * Работа с самими работами — в мини-приложении; кнопка «Открыть SAPSR» есть под каждым сообщением.
 */
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
            notifyUser(chatId, replyTo(update.getMessage().getText()));
        });
    }

    /** Ответ на сообщение пользователя: «/start» и «/start параметр», «/help», всё остальное. */
    static String replyTo(String text) {
        String command = text.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        int at = command.indexOf('@');  // «/help@SapsrBot» в группах
        if (at > 0) command = command.substring(0, at);
        return switch (command) {
            case "/start" -> BotMessages.welcome();
            case "/help" -> BotMessages.help();
            default -> BotMessages.unknownMessage();
        };
    }

    /**
     * Кнопка меню слева от поля ввода и список команд. Кнопка задаётся при каждом запуске с текущим адресом
     * мини-приложения: настроенная вручную в BotFather устаревает, когда меняется адрес, и перестаёт работать.
     */
    public void configureMenu() {
        try {
            telegramClient.execute(SetChatMenuButton.builder()
                    .menuButton(MenuButtonWebApp.builder()
                            .text("Открыть")
                            .webAppInfo(WebAppInfo.builder().url(webAppUrl).build())
                            .build())
                    .build());
            telegramClient.execute(SetMyCommands.builder()
                    .command(BotCommand.builder().command("start").description("Открыть SAPSR").build())
                    .command(BotCommand.builder().command("help").description("Как пользоваться ботом").build())
                    .build());
        } catch (TelegramApiException | RuntimeException e) {
            System.err.println("[BOT] Не удалось настроить кнопку меню и команды: " + e.getMessage());
        }
    }

    public void sendWelcomeMessage(long chatId) {
        notifyUser(chatId, BotMessages.startupNotice());
    }

    /**
     * Сообщение с HTML-разметкой (см. {@link BotMessages}) и кнопкой «Открыть SAPSR».
     * Возвращает номер первого сообщения или null, если отправить не удалось.
     */
    public Integer notifyUser(Long chatId, String html) {
        if (chatId == null || html == null || html.isBlank()) return null;

        // Тексты из BotMessages укладываются в предел; если всё же нет — режем уже без разметки,
        // чтобы не разорвать тег посередине.
        boolean fits = html.length() <= TELEGRAM_TEXT_LIMIT;
        List<String> chunks = fits ? List.of(html) : splitTelegramText(stripTags(html));
        Integer firstMessageId = null;
        try {
            for (int i = 0; i < chunks.size(); i++) {
                SendMessage.SendMessageBuilder<?, ?> message = SendMessage.builder()
                        .chatId(chatId)
                        .text(chunks.get(i))
                        .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build());
                if (fits) message.parseMode(ParseMode.HTML);
                if (i == chunks.size() - 1) message.replyMarkup(openAppKeyboard());
                Integer messageId = telegramClient.execute(message.build()).getMessageId();
                if (firstMessageId == null) firstMessageId = messageId;
            }
        } catch (TelegramApiException | RuntimeException e) {
            System.err.println("[BOT] Не удалось отправить сообщение: " + e.getMessage());
        }
        return firstMessageId;
    }

    private InlineKeyboardMarkup openAppKeyboard() {
        InlineKeyboardButton open = InlineKeyboardButton.builder()
                .text("📂 Открыть SAPSR")
                .webApp(WebAppInfo.builder().url(webAppUrl).build())
                .build();
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(open)).build();
    }

    static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
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
