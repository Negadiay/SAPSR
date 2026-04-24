package com.sapsr.backend.controller;

import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import com.sapsr.backend.service.BsuirApiService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public TeacherController(UserRepository userRepository, BsuirApiService bsuirApiService) {
        this.userRepository = userRepository;
        this.bsuirApiService = bsuirApiService;
    }

    @GetMapping("/teachers")
    public ResponseEntity<List<Map<String, Object>>> getTeachers(HttpServletRequest request) {
        List<User> allTeachers = userRepository.findByRole("TEACHER");

        // Пытаемся получить группу студента из его fullName: "Иванов И.И. (гр. 321702)"
        String groupNumber = extractGroupNumber(request);

        List<User> filtered = allTeachers;
        if (groupNumber != null && !groupNumber.isBlank()) {
            Set<String> groupTeachers = bsuirApiService.getTeachersForGroup(groupNumber);
            if (!groupTeachers.isEmpty()) {
                filtered = allTeachers.stream()
                        .filter(t -> t.getFullName() != null
                                && groupTeachers.contains(BsuirApiService.normalize(t.getFullName())))
                        .toList();
                // Fallback: если никто из зарегистрированных не совпал — вернуть всех
                if (filtered.isEmpty()) filtered = allTeachers;
            }
        }

        List<Map<String, Object>> result = filtered.stream()
                .map(t -> Map.<String, Object>of(
                        "telegram_id", t.getTelegramId(),
                        "full_name", t.getFullName() != null ? t.getFullName() : "Без имени"
                ))
                .toList();

        return ResponseEntity.ok(result);
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
