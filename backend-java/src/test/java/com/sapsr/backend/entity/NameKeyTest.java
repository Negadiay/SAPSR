package com.sapsr.backend.entity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Нормализация ФИО для сравнения тёзок (решение 4): то же делает миграция V11. */
class NameKeyTest {

    @Test
    @Tag("typical")
    void caseYoSpacesAndHyphensDoNotMatter() {
        String key = NameKey.of("Иванов", "Иван", "Иванович");
        assertThat(key).isEqualTo("иванов иван иванович");
        assertThat(NameKey.of("  ИВАНОВ ", "иван", " Иванович")).isEqualTo(key);
        assertThat(NameKey.of("Семёнов", "Пётр", null)).isEqualTo(NameKey.of("Семенов", "Петр", ""));
        assertThat(NameKey.of("Петров-Водкин", "Кузьма", null)).isEqualTo(NameKey.of("Петровводкин", "Кузьма", null));
        assertThat(NameKey.of("Иванов   Иван")).isEqualTo("иванов иван");
    }

    @Test
    @Tag("boundary")
    void differentPeopleHaveDifferentKeys() {
        assertThat(NameKey.of("Иванов", "Иван", "Иванович")).isNotEqualTo(NameKey.of("Иванов", "Иван", "Петрович"));
        assertThat(NameKey.of("Иванов", "Иван", null)).isNotEqualTo(NameKey.of("Иванов", "Иван", "Иванович"));
        assertThat(NameKey.of("", " ", null)).isNull();
        assertThat(NameKey.of((String) null)).isNull();
    }

    @Test
    @Tag("typical")
    void oldAccountsAreKeyedByDisplayName() {
        User old = new User();
        old.setFullName("Иванов И.И. (гр. 321702)");
        old.setLastName("Иванов");
        assertThat(NameKey.of(old)).isEqualTo("иванов и.и.");
        old.setFirstName("Иван");
        old.setMiddleName("Иванович");
        assertThat(NameKey.of(old)).isEqualTo("иванов иван иванович");
    }

    @Test
    @Tag("typical")
    void cleanCapitalisesEveryPart() {
        assertThat(NameKey.clean("  иванов ")).isEqualTo("Иванов");
        assertThat(NameKey.clean("петров - водкин")).isEqualTo("Петров-Водкин");
        assertThat(NameKey.clean("АННА  мария")).isEqualTo("Анна Мария");
    }

    @Test
    @Tag("negative")
    void cleanRejectsWhatIsNotAName() {
        assertThat(NameKey.clean("")).isNull();
        assertThat(NameKey.clean(null)).isNull();
        assertThat(NameKey.clean("Ivanov")).isNull();
        assertThat(NameKey.clean("Иванов2")).isNull();
        assertThat(NameKey.clean("И.И.")).isNull();
        assertThat(NameKey.clean("-Иванов")).isNull();
        assertThat(NameKey.clean("а".repeat(101))).isNull();
    }
}