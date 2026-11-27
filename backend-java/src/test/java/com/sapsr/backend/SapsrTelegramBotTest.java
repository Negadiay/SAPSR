package com.sapsr.backend;

import com.sapsr.backend.bot.AppLink;
import com.sapsr.backend.bot.BotMessages;
import com.sapsr.backend.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Без сети и токена: проверяется только выбор ответа, кнопки и запасной текст без разметки.
class SapsrTelegramBotTest {

    @Test
    @Tag("typical")
    void answersCommandsForRole() {
        assertThat(SapsrTelegramBot.replyTo("/start", "STUDENT").html()).isEqualTo(BotMessages.welcome("STUDENT"));
        assertThat(SapsrTelegramBot.replyTo("/start work_153", null).html()).isEqualTo(BotMessages.welcome(null));
        assertThat(SapsrTelegramBot.replyTo("/HELP", "TEACHER").html()).isEqualTo(BotMessages.help("TEACHER"));
        assertThat(SapsrTelegramBot.replyTo("/help@SapsrBot", "ADMIN").html()).isEqualTo(BotMessages.help("ADMIN"));
    }

    @Test
    @Tag("typical")
    void buttonsAreOnlyMainActionsOfRole() {
        assertThat(SapsrTelegramBot.replyTo("/start", "STUDENT").links()).containsExactly(AppLink.UPLOAD, AppLink.WORKS);
        assertThat(SapsrTelegramBot.replyTo("/start", "TEACHER").links()).containsExactly(AppLink.QUEUE);
        assertThat(SapsrTelegramBot.replyTo("/start", "ADMIN").links()).containsExactly(AppLink.ADMIN);
        assertThat(SapsrTelegramBot.replyTo("/start", null).links()).containsExactly(AppLink.APP);
    }

    @Test
    @Tag("negative")
    void anyOtherTextGetsShortHint() {
        assertThat(SapsrTelegramBot.replyTo("Войти", "STUDENT").html()).isEqualTo(BotMessages.unknownMessage());
        assertThat(SapsrTelegramBot.replyTo("привет", null).html()).isEqualTo(BotMessages.unknownMessage());
    }

    @Test
    @Tag("typical")
    void buttonOpensRequestedScreen() {
        SapsrTelegramBot bot = new SapsrTelegramBot("1:x", "https://sapsr.example/", mock(UserRepository.class));
        assertThat(bot.appUrl("work-153")).isEqualTo("https://sapsr.example/?open=work-153");
        assertThat(bot.appUrl("")).isEqualTo("https://sapsr.example/");
        SapsrTelegramBot withQuery = new SapsrTelegramBot("1:x", "https://sapsr.example/?v=2", mock(UserRepository.class));
        assertThat(withQuery.appUrl("upload")).isEqualTo("https://sapsr.example/?v=2&open=upload");
    }

    @Test
    @Tag("boundary")
    void tooLongTextIsSentWithoutMarkup() {
        assertThat(SapsrTelegramBot.stripTags("<b>Итог</b> &lt;раздел&gt; &amp; <i>ещё</i>"))
                .isEqualTo("Итог <раздел> & ещё");
    }
}
