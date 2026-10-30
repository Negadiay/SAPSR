package com.sapsr.backend.entity;

import java.util.Map;
import java.util.Optional;

/**
 * Имя работы по форме: «КР7_321702_Терешкова» — вид работы, семестр, группа, фамилия студента.
 * Под этим именем преподаватель скачивает работу, чтобы файлы разных студентов не путались у него на диске.
 */
public final class WorkFileName {

    private static final Map<String, String> PREFIXES = Map.of(
            "course_work", "КР",
            "course_project", "КП",
            "diploma", "ДР");

    private WorkFileName() {}

    /** Имя без расширения; пусто, если не хватает вида работы, семестра, группы или фамилии. */
    public static Optional<String> of(Submission submission) {
        String prefix = submission.getWorkType() == null ? null : PREFIXES.get(submission.getWorkType());
        User student = submission.getStudent();
        if (prefix == null || submission.getSemester() == null || student == null) return Optional.empty();
        String group = student.getGroupNumber();
        String lastName = lastName(student);
        if (group == null || group.isBlank() || lastName == null) return Optional.empty();
        return Optional.of(prefix + submission.getSemester() + "_" + group + "_" + lastName);
    }

    /** Имя для скачивания: по форме, если её можно собрать, иначе — как загрузил студент. */
    public static String forDownload(Submission submission) {
        return of(submission).map(name -> name + ".pdf").orElse(submission.displayFileName());
    }

    private static String lastName(User student) {
        if (student.getLastName() != null && !student.getLastName().isBlank()) return student.getLastName().strip();
        String fullName = student.getFullName();
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.strip().split("\\s+")[0];
    }
}
