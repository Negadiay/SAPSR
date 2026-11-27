package com.sapsr.backend.pipeline;

import com.sapsr.backend.bot.AppLink;

import java.util.List;

/**
 * Уведомление, которое нужно отправить после изменения состояния работы. Отправляется, когда транзакция
 * уже зафиксирована: сбой Telegram не откатывает результат, а откат не оставляет отправленного сообщения.
 *
 * @param to            кому: студенту или преподавателю работы
 * @param submissionId  работа, у которой запомнить номер сообщения: студенту — чтобы дописать в него итог,
 *                      преподавателю — чтобы удалить, если студент отзовёт работу; null — не запоминать
 * @param editMessageId номер сообщения, которое заменить этим текстом; null — новое сообщение
 * @param replyTo       номер сообщения, на которое это — ответ (итог содержания после первого сообщения); null — нет
 * @param commentedWork работа, файл с комментариями автопроверки которой приложить к сообщению; null — без файла
 */
public record Notice(Long chatId, String html, List<AppLink> links, Recipient to, Integer submissionId,
                     Integer editMessageId, Integer replyTo, Integer commentedWork) {

    public enum Recipient { STUDENT, TEACHER }

    public static Notice student(Long chatId, String html, List<AppLink> links, Integer submissionId) {
        return new Notice(chatId, html, links, Recipient.STUDENT, submissionId, null, null, null);
    }

    /** Итог проверки в то же сообщение, где студент уже видел оформление. */
    public static Notice studentUpdate(Long chatId, Integer messageId, String html, List<AppLink> links,
                                       Integer submissionId) {
        return new Notice(chatId, html, links, Recipient.STUDENT, submissionId, messageId, null, null);
    }

    /** Работа на проверку — с файлом, в котором замечания стоят комментариями на своих местах. */
    public static Notice teacher(Long chatId, String html, List<AppLink> links, Integer submissionId) {
        return new Notice(chatId, html, links, Recipient.TEACHER, submissionId, null, null, submissionId);
    }

    /**
     * Итог содержания, который догнал работу, — ответом на первое сообщение. Номер первого не заменяется:
     * при отзыве работы удаляется оно. С файлом — только если итог есть (withFile).
     */
    public static Notice teacherFollowUp(Long chatId, String html, List<AppLink> links, Integer workId,
                                         Integer replyTo, boolean withFile) {
        return new Notice(chatId, html, links, Recipient.TEACHER, null, null, replyTo, withFile ? workId : null);
    }
}
