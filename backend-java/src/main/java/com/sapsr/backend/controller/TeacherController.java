package com.sapsr.backend.controller;

import com.sapsr.backend.entity.Teacher;
import com.sapsr.backend.repository.TeacherRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/v1")
public class TeacherController {

    private final TeacherRepository teacherRepository;

    public TeacherController(TeacherRepository teacherRepository) {
        this.teacherRepository = teacherRepository;
    }

    @GetMapping("/teachers")
    public ResponseEntity<List<Map<String, Object>>> getTeachers() {
        List<Teacher> teachers = teacherRepository.findAll();

        List<Map<String, Object>> result = teachers.stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "full_name", t.getFullName()
                ))
                .toList();

        return ResponseEntity.ok(result);
    }
}
