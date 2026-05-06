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

    @Column(name = "email")
    private String email;

    @Column(name = "url_id")
    private String urlId;

    @Column(name = "teacher_groups")
    private String teacherGroups; // JSON-массив номеров групп: '["321701","321702"]'

    public User() {}

    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getUrlId() { return urlId; }
    public void setUrlId(String urlId) { this.urlId = urlId; }
    public String getTeacherGroups() { return teacherGroups; }
    public void setTeacherGroups(String teacherGroups) { this.teacherGroups = teacherGroups; }
}