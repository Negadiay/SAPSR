package com.sapsr.backend.controller;

import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.service.BsuirApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class TeacherController {

    private final UserRepository userRepository;
    private final BsuirApiService bsuirApiService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TeacherController(UserRepository userRepository, BsuirApiService bsuirApiService) {
        this.userRepository = userRepository;
        this.bsuirApiService = bsuirApiService;
    }

    @GetMapping("/teachers")
    public ResponseEntity<List<Map<String, Object>>> getTeachers(HttpServletRequest request) {
        List<User> allTeachers = userRepository.findByRole("TEACHER");

        String groupNumber = extractGroupNumber(request);

        List<User> filtered = null;

        if (groupNumber != null && !groupNumber.isBlank()) {
            final String group = groupNumber;

            // 1. Быстрый путь: преподаватели с сохранёнными группами (данные из БД, без IIS)
            List<User> byStoredGroups = allTeachers.stream()
                    .filter(t -> t.getTeacherGroups() != null && teacherHasGroup(t.getTeacherGroups(), group))
                    .toList();

            if (!byStoredGroups.isEmpty()) {
                filtered = byStoredGroups;
            } else {
                // 2. Fallback: старая логика через IIS (для преподавателей без сохранённых групп)
                Set<String> iisEmails = bsuirApiService.getTeacherEmailsForGroup(groupNumber);
                Set<String> iisNames  = bsuirApiService.getTeachersForGroup(groupNumber);
                boolean iisHasData = !iisEmails.isEmpty() || !iisNames.isEmpty();

                if (iisHasData) {
                    if (!iisEmails.isEmpty()) {
                        List<User> byEmail = allTeachers.stream()
                                .filter(t -> t.getEmail() != null && iisEmails.contains(t.getEmail().toLowerCase()))
                                .toList();
                        if (!byEmail.isEmpty()) filtered = byEmail;
                    }
                    if (filtered == null && !iisNames.isEmpty()) {
                        List<User> byName = allTeachers.stream()
                                .filter(t -> t.getFullName() != null
                                        && iisNames.contains(BsuirApiService.normalize(t.getFullName())))
                                .toList();
                        if (!byName.isEmpty()) filtered = byName;
                    }
                    if (filtered == null) filtered = Collections.emptyList();
                }
            }
        }
        if (filtered == null) filtered = allTeachers;

        List<Map<String, Object>> result = filtered.stream()
                .map(t -> Map.<String, Object>of(
                        "telegram_id", t.getTelegramId(),
                        "full_name", t.getFullName() != null ? t.getFullName() : "Без имени"
                ))
                .toList();

        return ResponseEntity.ok(result);
    }

    private boolean teacherHasGroup(String groupsJson, String groupNumber) {
        try {
            List<String> groups = MAPPER.readValue(groupsJson, new TypeReference<>() {});
            return groups.contains(groupNumber);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractGroupNumber(HttpServletRequest request) {
        try {
            Long telegramId = (Long) request.getAttribute("telegram_id");
            if (telegramId == null) return null;
            // Группа — из своего поля: его заполняют регистрация, правка администратора и миграция V2.
            return userRepository.findById(telegramId).map(User::getGroupNumber).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
