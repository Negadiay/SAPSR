package com.sapsr.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BsuirApiService {

    private static final String SCHEDULE_URL = "https://iis.bsuir.by/api/v1/schedule/student-group/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Кэш: номер группы → набор нормализованных ФИО преподавателей
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();

    public Set<String> getTeachersForGroup(String groupNumber) {
        return cache.computeIfAbsent(groupNumber, this::fetchFromApi);
    }

    private Set<String> fetchFromApi(String groupNumber) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SCHEDULE_URL + groupNumber))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Collections.emptySet();

            JsonNode root = objectMapper.readTree(response.body());
            Set<String> teachers = new HashSet<>();

            JsonNode schedules = root.get("schedules");
            if (schedules == null || !schedules.isObject()) return Collections.emptySet();

            schedules.fields().forEachRemaining(entry -> {
                JsonNode days = entry.getValue();
                if (days.isArray()) {
                    for (JsonNode day : days) {
                        JsonNode lessonsList = day.get("lessons");
                        if (lessonsList != null && lessonsList.isArray()) {
                            for (JsonNode lesson : lessonsList) {
                                JsonNode employees = lesson.get("employees");
                                if (employees != null && employees.isArray()) {
                                    for (JsonNode emp : employees) {
                                        String fio = buildFio(emp);
                                        if (fio != null) teachers.add(normalize(fio));
                                    }
                                }
                            }
                        }
                    }
                }
            });

            System.out.println("[BSUIR API] Группа " + groupNumber + ": найдено " + teachers.size() + " преподавателей");
            return teachers;
        } catch (Exception e) {
            System.err.println("[BSUIR API] Ошибка для группы " + groupNumber + ": " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private String buildFio(JsonNode emp) {
        String lastName = text(emp, "lastName");
        String firstName = text(emp, "firstName");
        String middleName = text(emp, "middleName");
        if (lastName == null) return null;
        StringBuilder sb = new StringBuilder(lastName);
        if (firstName != null) {
            sb.append(" ").append(firstName.charAt(0)).append(".");
            if (middleName != null && !middleName.isBlank()) sb.append(middleName.charAt(0)).append(".");
        }
        return sb.toString();
    }

    private String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull() && !n.asText().isBlank()) ? n.asText().trim() : null;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void clearCache() {
        cache.clear();
        System.out.println("[BSUIR API] Кэш расписаний очищен");
    }
}
