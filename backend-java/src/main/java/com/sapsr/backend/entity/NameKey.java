package com.sapsr.backend.entity;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ФИО студента для сравнения тёзок и приведение частей имени к одному виду (решение 4).
 * Ключ: нижний регистр, «ё» = «е», дефисы не учитываются, пробелы схлопнуты — то же делает миграция V11.
 */
public final class NameKey {

    /** Часть имени: буквы кириллицы, дефис и пробел, от 1 до 100 символов (api-v1.md, раздел 4). */
    private static final Pattern PART = Pattern.compile("[А-ЯЁа-яё]+(?:[ -][А-ЯЁа-яё]+)*");
    private static final int MAX_PART = 100;

    private NameKey() {}

    public static String of(String lastName, String firstName, String middleName) {
        StringBuilder full = new StringBuilder();
        for (String part : new String[] {lastName, firstName, middleName}) {
            if (part != null && !part.isBlank()) full.append(part).append(' ');
        }
        return of(full.toString());
    }

    /** Ключ по готовой строке — для аккаунтов старой формы («Иванов И.И.»). Пустая строка — null. */
    public static String of(String name) {
        if (name == null) return null;
        String key = name.toLowerCase(Locale.ROOT).replace('ё', 'е').replace("-", "")
                .replaceAll("\\s+", " ").strip();
        return key.isEmpty() ? null : key;
    }

    /** Ключ студента: по полям, а если имени нет (старая форма) — по строке для показа. */
    public static String of(User student) {
        return student.getFirstName() != null && !student.getFirstName().isBlank()
                ? of(student.getLastName(), student.getFirstName(), student.getMiddleName())
                : of(student.displayName());
    }

    /**
     * Часть имени в едином виде: без лишних пробелов, с заглавной буквы в начале и после дефиса и пробела.
     * null — часть не прошла проверку (пусто, не кириллица, длиннее 100 символов).
     */
    public static String clean(String raw) {
        if (raw == null) return null;
        String value = raw.strip().replaceAll("\\s+", " ").replaceAll("\\s*-\\s*", "-");
        if (value.isEmpty() || value.length() > MAX_PART || !PART.matcher(value).matches()) return null;
        StringBuilder result = new StringBuilder(value.length());
        boolean start = true;
        for (char ch : value.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(start ? Character.toUpperCase(ch) : ch);
            start = ch == '-' || ch == ' ';
        }
        return result.toString();
    }
}