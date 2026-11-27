package com.sapsr.backend.bot;

import java.util.List;

/**
 * Кнопка под сообщением бота: открывает мини-приложение сразу на нужном экране (параметр {@code open} в адресе,
 * его читает интерфейс). В чате — только главные действия роли; всё остальное — в самом приложении.
 *
 * @param screen экран приложения: {@code upload}, {@code works}, {@code queue}, {@code admin}, {@code work-<id>};
 *               пусто — главный экран
 */
public record AppLink(String text, String screen) {

    public static final AppLink APP = new AppLink("Открыть SAPSR", "");
    public static final AppLink UPLOAD = new AppLink("Сдать работу", "upload");
    public static final AppLink WORKS = new AppLink("Мои работы", "works");
    public static final AppLink QUEUE = new AppLink("Работы на проверку", "queue");
    public static final AppLink ADMIN = new AppLink("Управление", "admin");

    public static AppLink work(int submissionId) {
        return new AppLink("Открыть работу", "work-" + submissionId);
    }

    public static AppLink resubmit() {
        return new AppLink("Сдать снова", "upload");
    }

    /** Главные действия роли — под ответами на команды. */
    public static List<AppLink> forRole(String role) {
        return switch (role == null ? "" : role) {
            case "STUDENT" -> List.of(UPLOAD, WORKS);
            case "TEACHER" -> List.of(QUEUE);
            case "ADMIN" -> List.of(ADMIN);
            default -> List.of(APP);
        };
    }
}
