package com.sapsr.backend;

import com.sapsr.backend.bot.BotMessages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Без сети и токена: проверяется только выбор ответа и запасной текст без разметки.
class SapsrTelegramBotTest {

    @Test
    @Tag("typical")
    void answersCommands() {
        assertThat(SapsrTelegramBot.replyTo("/start")).isEqualTo(BotMessages.welcome());
        assertThat(SapsrTelegramBot.replyTo("/start work_153")).isEqualTo(BotMessages.welcome());
        assertThat(SapsrTelegramBot.replyTo("/HELP")).isEqualTo(BotMessages.help());
        assertThat(SapsrTelegramBot.replyTo("/help@SapsrBot")).isEqualTo(BotMessages.help());
    }

    @Test
    @Tag("negative")
    void anyOtherTextGetsShortHint() {
        assertThat(SapsrTelegramBot.replyTo("Войти")).isEqualTo(BotMessages.unknownMessage());
        assertThat(SapsrTelegramBot.replyTo("привет")).isEqualTo(BotMessages.unknownMessage());
    }

    @Test
    @Tag("boundary")
    void tooLongTextIsSentWithoutMarkup() {
        assertThat(SapsrTelegramBot.stripTags("<b>Итог</b> &lt;раздел&gt; &amp; <i>ещё</i>"))
                .isEqualTo("Итог <раздел> & ещё");
    }
}
