package com.sapsr.backend.pipeline;

import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.bot.BotMessages;
import com.sapsr.backend.report.CommentedWork;
import com.sapsr.backend.repository.SubmissionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Отправка уведомлений, собранных {@link PipelineState}, — уже после фиксации транзакции. */
@Component
public class NoticeSender {

    private final SapsrTelegramBot bot;
    private final SubmissionRepository submissions;
    private final CommentedWork commented;

    public NoticeSender(SapsrTelegramBot bot, SubmissionRepository submissions, CommentedWork commented) {
        this.bot = bot;
        this.submissions = submissions;
        this.commented = commented;
    }

    public void send(List<Notice> notices) {
        for (Notice notice : notices) {
            // Заменить не вышло (сообщение удалили или оно слишком старое) — отправляем новое.
            if (notice.editMessageId() != null
                    && bot.edit(notice.chatId(), notice.editMessageId(), notice.html(), notice.links())) {
                continue;
            }
            Integer messageId = sendNew(notice);
            if (notice.submissionId() == null || messageId == null) continue;
            if (notice.to() == Notice.Recipient.TEACHER) submissions.setTeacherMessageId(notice.submissionId(), messageId);
            else submissions.setStudentMessageId(notice.submissionId(), messageId);
        }
    }

    /** С файлом с комментариями, если его удалось собрать; не удалось (нет файла, PDF не читается) — просто текст. */
    private Integer sendNew(Notice notice) {
        Optional<CommentedWork.File> file = notice.commentedWork() == null ? Optional.empty()
                : submissions.findById(notice.commentedWork()).flatMap(commented::build);
        if (file.isPresent()) {
            Integer messageId = bot.sendDocument(notice.chatId(), file.get().content(), file.get().name(),
                    notice.html() + BotMessages.COMMENTS_IN_FILE, notice.links(), notice.replyTo());
            if (messageId != null) return messageId;
        }
        return notice.replyTo() == null
                ? bot.send(notice.chatId(), notice.html(), notice.links())
                : bot.send(notice.chatId(), notice.html(), notice.links(), notice.replyTo());
    }
}
