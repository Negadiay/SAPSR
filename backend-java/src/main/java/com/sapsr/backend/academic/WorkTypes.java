package com.sapsr.backend.academic;

/**
 * Вид работы студент не выбирает (решение 11, правка 26.10.2026). Дипломная бывает только в 8 семестре;
 * в остальных — курсовая работа или курсовой проект, как написано на титульном листе: обработчик
 * присылает распознанный вид в отчёте, в {@code document.title_page.work_type}.
 */
public final class WorkTypes {

    public static final String COURSE_WORK = "course_work";
    public static final String COURSE_PROJECT = "course_project";
    public static final String DIPLOMA = "diploma";
    public static final int DIPLOMA_SEMESTER = 8;

    private WorkTypes() {}

    /** При загрузке: в 8 семестре — дипломная, иначе вид станет известен после разбора титула. */
    public static String atUpload(Integer semester) {
        return isDiplomaSemester(semester) ? DIPLOMA : null;
    }

    /**
     * Вид работы по титулу с поправкой на семестр; null — оставить прежний. Дипломная не в 8 семестре —
     * ошибка на титуле, её не ставим; если семестр неизвестен, верим титулу.
     */
    public static String fromTitle(String detected, Integer semester) {
        if (isDiplomaSemester(semester)) return DIPLOMA;
        if (COURSE_WORK.equals(detected) || COURSE_PROJECT.equals(detected)) return detected;
        if (DIPLOMA.equals(detected) && semester == null) return DIPLOMA;
        return null;
    }

    private static boolean isDiplomaSemester(Integer semester) {
        return semester != null && semester == DIPLOMA_SEMESTER;
    }
}
