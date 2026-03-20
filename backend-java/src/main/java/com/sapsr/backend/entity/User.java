package com.sapsr.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "role", nullable = false, length = 50)
    private String role;

    @Column(name = "full_name", length = 255)
    private String fullName;

    public User() {
    }

    public User(Long telegramId, String role, String fullName) {
        this.telegramId = telegramId;
        this.role = role;
        this.fullName = fullName;
    }

    public Long getTelegramId() {
        return telegramId;
    }

    public void setTelegramId(Long telegramId) {
        this.telegramId = telegramId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
