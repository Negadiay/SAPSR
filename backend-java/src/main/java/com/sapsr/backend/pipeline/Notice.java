package com.sapsr.backend.pipeline;

/**
 * Уведомление, которое нужно отправить после изменения состояния работы. Отправляется, когда транзакция
 * уже зафиксирована: сбой Telegram не откатывает результат, а откат не оставляет отправленного сообщения.
 *
 * @param teacherMessageFor номер работы, у которой запомнить номер этого сообщения (его удаляют, если студент
 *                          отзовёт работу); null — сообщение студенту.
 */
public record Notice(Long chatId, String html, Integer teacherMessageFor) {

    public static Notice student(Long chatId, String html) {
        return new Notice(chatId, html, null);
    }

    public static Notice teacher(Long chatId, String html, Integer submissionId) {
        return new Notice(chatId, html, submissionId);
    }
}
