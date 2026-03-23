package com.sapsr.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Официальная сущность пользователя системы.
 * Объединяет поля для БД и поля для логики бота.
 */
@Entity
@Table(name = "users")
@Getter @Setter
public class User {

    @Id
    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "role")
    private String role; // STUDENT или TEACHER

    @Column(name = "full_name")
    private String fullName;

    // Поля для стейт-машины регистрации бота
    @Column(name = "group_or_code")
    private String groupOrCode;

    @Column(name = "registration_state")
    private String registrationState;

    public User() {}
}