package com.sapsr.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "students")
public class Student {

    @Id
    @Column(name = "telegram_id")
    private Long telegramId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "group_number", nullable = false)
    private String groupNumber;

    public Student() {}

    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getGroupNumber() { return groupNumber; }
    public void setGroupNumber(String groupNumber) { this.groupNumber = groupNumber; }
}
