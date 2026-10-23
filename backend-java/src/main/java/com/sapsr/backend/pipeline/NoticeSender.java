package com.sapsr.backend.pipeline;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.repository.SubmissionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/** Отправка уведомлений, собранных {@link PipelineState}, — уже после фиксации транзакции. */
@Component
public class NoticeSender {

    private final SapsrTelegramBot bot;
    private final SubmissionRepository submissions;

    public NoticeSender(SapsrTelegramBot bot, SubmissionRepository submissions) {
        this.bot = bot;
        this.submissions = submissions;
    }

    public void send(List<Notice> notices) {
        for (Notice notice : notices) {
            Integer messageId = bot.notifyUser(notice.chatId(), notice.html());
            if (notice.teacherMessageFor() != null && messageId != null) {
                submissions.setTeacherMessageId(notice.teacherMessageFor(), messageId);
            }
        }
    }
}
