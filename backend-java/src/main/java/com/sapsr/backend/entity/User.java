package com.sapsr.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "full_name")
    private String fullName;

    public User() {}

    // Геттеры и сеттеры
    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
}