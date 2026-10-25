package com.sapsr.backend.pipeline;

import com.sapsr.backend.bot.AppLink;

import java.util.List;

/**
 * Уведомление, которое нужно отправить после изменения состояния работы. Отправляется, когда транзакция
 * уже зафиксирована: сбой Telegram не откатывает результат, а откат не оставляет отправленного сообщения.
 *
 * @param to           кому: студенту или преподавателю работы
 * @param submissionId работа, у которой запомнить номер сообщения: студенту — чтобы дописать в него итог,
 *                     преподавателю — чтобы удалить, если студент отзовёт работу; null — не запоминать
 * @param editMessageId номер сообщения, которое заменить этим текстом; null — новое сообщение
 */
public record Notice(Long chatId, String html, List<AppLink> links, Recipient to, Integer submissionId,
                     Integer editMessageId) {

    public enum Recipient { STUDENT, TEACHER }

    public static Notice student(Long chatId, String html, List<AppLink> links, Integer submissionId) {
        return new Notice(chatId, html, links, Recipient.STUDENT, submissionId, null);
    }

    /** Итог проверки в то же сообщение, где студент уже видел оформление. */
    public static Notice studentUpdate(Long chatId, Integer messageId, String html, List<AppLink> links,
                                       Integer submissionId) {
        return new Notice(chatId, html, links, Recipient.STUDENT, submissionId, messageId);
    }

    public static Notice teacher(Long chatId, String html, List<AppLink> links, Integer submissionId) {
        return new Notice(chatId, html, links, Recipient.TEACHER, submissionId, null);
    }
}
