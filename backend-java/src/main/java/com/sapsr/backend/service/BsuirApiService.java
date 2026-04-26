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

//проверочный пуш

@Service
public class BsuirApiService {

    private static final String SCHEDULE_URL = "https://iis.bsuir.by/api/v1/schedule/student-group/";
    private static final String STUDENT_GROUPS_URL = "https://iis.bsuir.by/api/v1/student-groups";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Кэш: номер группы → набор нормализованных ФИО преподавателей
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();
    private final Set<String> knownGroupsCache = ConcurrentHashMap.newKeySet();

    public Set<String> getTeachersForGroup(String groupNumber) {
        return cache.computeIfAbsent(groupNumber, this::fetchFromApi);
    }

    public boolean groupExists(String groupNumber) {
        if (groupNumber == null || !groupNumber.matches("\\d{6}")) return false;
        if (knownGroupsCache.contains(groupNumber)) return true;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(STUDENT_GROUPS_URL))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;

            JsonNode groups = objectMapper.readTree(response.body());
            if (!groups.isArray()) return false;

            for (JsonNode group : groups) {
                String name = text(group, "name");
                if (name != null) knownGroupsCache.add(name);
            }
            return knownGroupsCache.contains(groupNumber);
        } catch (Exception e) {
            System.err.println("[BSUIR API] Не удалось проверить группу " + groupNumber + ": " + e.getMessage());
            return false;
        }
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
        knownGroupsCache.clear();
        System.out.println("[BSUIR API] Кэш расписаний очищен");
    }
}
