package com.sapsr.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users") //название таблицы с пользователями
@Getter @Setter
public class User {
    @Id
    private Long telegramId;

    private String fullName;
    private String role; // STUDENT или TEACHER
    private String groupOrCode; // Группа для студента или секретный код для препода

    private String registrationState; // Для хранения этапа диалога (например, "AWAITING_NAME")
}