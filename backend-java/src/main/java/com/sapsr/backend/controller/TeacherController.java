package com.sapsr.backend.controller;

import com.sapsr.backend.entity.User;
import com.sapsr.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class TeacherController {

    private final UserRepository userRepository;

    public TeacherController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/teachers")
    public ResponseEntity<List<Map<String, Object>>> getTeachers() {
        List<User> teachers = userRepository.findByRole("TEACHER");

        List<Map<String, Object>> result = teachers.stream()
                .map(t -> Map.<String, Object>of(
                        "telegram_id", t.getTelegramId(),
                        "full_name", t.getFullName() != null ? t.getFullName() : "Без имени"
                ))
                .toList();

        return ResponseEntity.ok(result);
    }
}
