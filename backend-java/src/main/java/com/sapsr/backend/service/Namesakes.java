package com.sapsr.backend.service;

import com.sapsr.backend.entity.NameKey;
import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Номера тёзок (решение 4; api-v1.md, раздел 2). Тёзки — студенты одной группы с одинаковым ФИО после
 * нормализации. Номер выдаётся один раз — наибольший среди тёзок группы плюс один — и не меняется,
 * даже если кто-то из тёзок удалён; новый номер студент получает, только если изменились ФИО или группа.
 */
public final class Namesakes {

    private static final int ATTEMPTS = 3;

    private Namesakes() {}

    /**
     * Пересчитывает ключ ФИО студента и, если ключ или группа изменились, выдаёт новый номер; сохраняет запись.
     * Двое регистрируются одновременно — уникальный индекс отклонит второго, и номер выдаётся заново.
     */
    public static User saveStudent(UserRepository users, User student, String previousKey, String previousGroup) {
        String key = NameKey.of(student);
        boolean changed = student.getNamesakeNumber() == null
                || !Objects.equals(key, previousKey) || !Objects.equals(student.getGroupNumber(), previousGroup);
        student.setNameKey(key);
        if (!changed || key == null || student.getGroupNumber() == null) {
            if (key == null || student.getGroupNumber() == null) student.setNamesakeNumber(null);
            return users.saveAndFlush(student);
        }
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            student.setNamesakeNumber(users.maxNamesakeNumber(student.getGroupNumber(), key) + 1);
            try {
                return users.saveAndFlush(student);
            } catch (DataIntegrityViolationException e) {
                last = e;
            }
        }
        throw last;
    }

    public static boolean hasNamesake(UserRepository users, User student) {
        return student.getNameKey() != null && student.getGroupNumber() != null
                && users.countNamesakes(student.getGroupNumber(), student.getNameKey()) > 1;
    }

    /** Объект {@code student} в ответах преподавателю (api-v1.md, раздел 2). */
    public static Map<String, Object> view(UserRepository users, User student) {
        boolean namesake = hasNamesake(users, student);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("last_name", student.getLastName());
        view.put("first_name", student.getFirstName());
        view.put("middle_name", student.getMiddleName());
        view.put("full_name", student.displayName());
        view.put("group", student.getGroupNumber());
        view.put("has_namesake", namesake);
        view.put("namesake_number", namesake ? student.getNamesakeNumber() : null);
        return view;
    }

    /** «Иванов Иван Иванович (2)» — так тёзку видит преподаватель; без тёзок — просто ФИО. */
    public static String label(String displayName, Integer namesakeNumber, boolean hasNamesake) {
        return hasNamesake && namesakeNumber != null ? displayName + " (" + namesakeNumber + ")" : displayName;
    }
}