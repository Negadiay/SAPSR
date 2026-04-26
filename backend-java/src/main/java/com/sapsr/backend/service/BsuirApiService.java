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

    private static final String SCHEDULE_URL        = "https://iis.bsuir.by/api/v1/schedule?studentGroup=";
    private static final String STUDENT_GROUPS_URL  = "https://iis.bsuir.by/api/v1/student-groups";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Кэш: номер группы → набор нормализованных ФИО преподавателей
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();
    // Кэш: номер группы → набор email преподавателей (@bsuir.by)
    private final Map<String, Set<String>> emailCache = new ConcurrentHashMap<>();
    private final Set<String> knownGroupsCache = ConcurrentHashMap.newKeySet();
    public Set<String> getTeachersForGroup(String groupNumber) {
        return cache.computeIfAbsent(groupNumber, this::fetchFromApi);
    }

    public Set<String> getTeacherEmailsForGroup(String groupNumber) {
        return emailCache.computeIfAbsent(groupNumber, this::fetchEmailsFromApi);
    }

    private static final String EMPLOYEE_SCHEDULE_URL = "https://iis.bsuir.by/api/v1/employees/schedule/";

    /**
     * Ищет ФИО преподавателя в IIS БГУИР по корпоративной почте.
     *
     * Алгоритм (O(1) HTTP-запросов):
     *  1. Из email берётся urlId = часть до '@' (напр. "n.zotov").
     *  2. Вызывается GET /employees/schedule/{urlId}.
     *  3. В ответе сравнивается employee.email с переданной почтой.
     *  4. При совпадении возвращается ФИО; при несовпадении — null.
     *
     * Примечание: у БГУИР urlId всегда совпадает с логином email,
     * поэтому один запрос покрывает 100% реальных случаев.
     */
    public String findTeacherNameByEmail(String email) {
        if (email == null) return null;
        String normalized = email.trim().toLowerCase();
        String urlId      = normalized.contains("@") ? normalized.split("@")[0] : normalized;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(EMPLOYEE_SCHEDULE_URL + urlId))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                System.err.println("[BSUIR API] /employees/schedule/" + urlId + " → HTTP " + resp.statusCode());
                return null;
            }
            JsonNode root     = objectMapper.readTree(resp.body());
            JsonNode employee = root.get("employee");
            if (employee == null || employee.isNull()) return null;

            String iisEmail = text(employee, "email");
            if (iisEmail == null || !iisEmail.trim().equalsIgnoreCase(normalized)) {
                System.err.println("[BSUIR API] Email не совпал: IIS=" + iisEmail + ", введено=" + normalized);
                return null;
            }
            String fio = text(employee, "fio");
            if (fio == null) fio = buildFio(employee);
            System.out.println("[BSUIR API] Найден преподаватель: " + fio + " (" + iisEmail + ")");
            return fio;
        } catch (Exception e) {
            System.err.println("[BSUIR API] Ошибка поиска по email: " + e.getMessage());
            return null;
        }
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

    /**
     * Извлекает преподавателей (нормализованные ФИО и email) из расписания группы.
     * BSUIR API может возвращать уроки в трёх структурах:
     *   A) schedules.{day}[].employees[]            — урок прямо в массиве дня
     *   B) schedules.{day}[].lessons[]              — уроки во вложенном массиве
     *   C) schedules.{day}[].lessons.{type}[].      — уроки сгруппированы по типу
     */
    private record GroupScheduleData(Set<String> names, Set<String> emails) {}

    private GroupScheduleData fetchGroupSchedule(String groupNumber) {
        Set<String> names  = new HashSet<>();
        Set<String> emails = new HashSet<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SCHEDULE_URL + groupNumber))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return new GroupScheduleData(names, emails);

            JsonNode root      = objectMapper.readTree(response.body());
            JsonNode schedules = root.get("schedules");
            if (schedules == null || !schedules.isObject()) return new GroupScheduleData(names, emails);

            for (var dayEntry : schedules.properties()) {
                JsonNode daySlots = dayEntry.getValue();
                if (!daySlots.isArray()) continue;
                for (JsonNode slot : daySlots) {
                    // Структура A: employees прямо в слоте
                    extractEmployees(slot.get("employees"), names, emails);

                    JsonNode lessons = slot.get("lessons");
                    if (lessons == null) continue;

                    if (lessons.isArray()) {
                        // Структура B: lessons — массив уроков
                        for (JsonNode lesson : lessons) {
                            extractEmployees(lesson.get("employees"), names, emails);
                        }
                    } else if (lessons.isObject()) {
                        // Структура C: lessons — объект { "ЛК": [...], "ПЗ": [...] }
                        for (var typeEntry : lessons.properties()) {
                            JsonNode lessonList = typeEntry.getValue();
                            if (lessonList.isArray()) {
                                for (JsonNode lesson : lessonList) {
                                    extractEmployees(lesson.get("employees"), names, emails);
                                }
                            }
                        }
                    }
                }
            }
            System.out.printf("[BSUIR API] Группа %s: %d преподавателей, %d email(ов)%n",
                    groupNumber, names.size(), emails.size());
        } catch (Exception e) {
            System.err.println("[BSUIR API] Ошибка расписания для группы " + groupNumber + ": " + e.getMessage());
        }
        return new GroupScheduleData(names, emails);
    }

    private void extractEmployees(JsonNode employees, Set<String> names, Set<String> emails) {
        if (employees == null || !employees.isArray()) return;
        for (JsonNode emp : employees) {
            String fio = buildFio(emp);
            if (fio != null) names.add(normalize(fio));
            String email = text(emp, "email");
            if (email != null && email.contains("@")) emails.add(email.toLowerCase());
        }
    }

    private Set<String> fetchFromApi(String groupNumber) {
        return fetchGroupSchedule(groupNumber).names();
    }

    private Set<String> fetchEmailsFromApi(String groupNumber) {
        return fetchGroupSchedule(groupNumber).emails();
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
        emailCache.clear();
        knownGroupsCache.clear();
        System.out.println("[BSUIR API] Кэш расписаний очищен");
    }
}
