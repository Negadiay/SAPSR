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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class TeacherController {

    private final UserRepository userRepository;
    private final BsuirApiService bsuirApiService;

    private static final Pattern GROUP_PATTERN = Pattern.compile("\\(гр\\.\\s*(\\d{6})\\)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TeacherController(UserRepository userRepository, BsuirApiService bsuirApiService) {
        this.userRepository = userRepository;
        this.bsuirApiService = bsuirApiService;
    }

    @GetMapping("/teachers")
    public ResponseEntity<List<Map<String, Object>>> getTeachers(HttpServletRequest request) {
        List<User> allTeachers = userRepository.findByRole("TEACHER");

        // Пытаемся получить группу студента из его fullName: "Иванов И.И. (гр. 321702)"
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
            return userRepository.findById(telegramId)
                    .map(u -> {
                        if (u.getFullName() == null) return null;
                        Matcher m = GROUP_PATTERN.matcher(u.getFullName());
                        return m.find() ? m.group(1) : null;
                    })
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
