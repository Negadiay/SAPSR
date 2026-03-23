package com.sapsr.backend.service;

import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final UserRepository userRepository;

    public NotificationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // Шаблон 1: Ошибка
    public String getFailureMessage() {
        return "❌ <b>В вашей работе найдены ошибки!</b>\n\nЗайдите в кабинет, чтобы посмотреть подробный отчет и исправить замечания.";
    }

    // Шаблон 2: Успех
    public String getSuccessMessage() {
        return "✅ <b>Оформление идеальное!</b>\n\nВаша работа прошла автоматическую проверку и отправлена преподавателю на рассмотрение.";
    }

    // Шаблон 3: Для преподавателя
    public String getTeacherNotification(Long studentId) {
        // Ищем имя студента в базе
        String studentName = userRepository.findById(studentId)
                .map(User::getFullName)
                .orElse("Неизвестный студент");

        return "📩 <b>Поступила новая работа!</b>\nСтудент: " + studentName + "\nПроверка пройдена успешно.";
    }
}