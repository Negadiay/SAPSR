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
            // Заменить не вышло (сообщение удалили или оно слишком старое) — отправляем новое.
            if (notice.editMessageId() != null
                    && bot.edit(notice.chatId(), notice.editMessageId(), notice.html(), notice.links())) {
                continue;
            }
            Integer messageId = bot.send(notice.chatId(), notice.html(), notice.links());
            if (notice.submissionId() == null || messageId == null) continue;
            if (notice.to() == Notice.Recipient.TEACHER) submissions.setTeacherMessageId(notice.submissionId(), messageId);
            else submissions.setStudentMessageId(notice.submissionId(), messageId);
        }
    }
}
