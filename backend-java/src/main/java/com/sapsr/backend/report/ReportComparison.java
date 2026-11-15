package com.sapsr.backend.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сравнение замечаний двух версий работы (решение 17). Замечания группируются по требованию
 * ({@code requirement_id}): было и нет — исправлено, есть в обеих — осталось, только в новой — появилось.
 * Сравнивать можно только отчёты v2; если у одной из версий отчёта нет или он старого формата — сравнения нет.
 */
public final class ReportComparison {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReportComparison() {}

    /** Отчёт v2 из столбца {@code submissions.report} или null: пусто, не JSON, другая схема. */
    public static JsonNode parseV2(String report) {
        if (report == null || report.isBlank()) return null;
        try {
            JsonNode node = JSON.readTree(report);
            // Столбец jsonb может вернуть строку с JSON внутри.
            if (node.isTextual()) node = JSON.readTree(node.asText());
            return node.isObject() && node.path("schema").asInt() == 2 && node.path("findings").isArray() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Поле {@code comparison} ответа {@code GET /submissions/{id}/report-v2} или null. */
    public static Map<String, Object> compare(Integer previousId, String previousReport, String currentReport) {
        JsonNode before = parseV2(previousReport);
        JsonNode after = parseV2(currentReport);
        if (previousId == null || before == null || after == null) return null;

        Map<String, Group> groups = new LinkedHashMap<>();
        collect(before, groups, true);
        collect(after, groups, false);

        List<Map<String, Object>> fixed = new ArrayList<>(), remaining = new ArrayList<>(), added = new ArrayList<>();
        for (Group group : groups.values()) {
            (group.after == 0 ? fixed : group.before == 0 ? added : remaining).add(group.view());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("previous_submission_id", previousId);
        result.put("fixed", fixed);
        result.put("remaining", remaining);
        result.put("new", added);
        return result;
    }

    private static void collect(JsonNode report, Map<String, Group> groups, boolean previous) {
        for (JsonNode finding : report.path("findings")) {
            String requirement = finding.path("requirement_id").asText("");
            if (requirement.isEmpty()) continue;
            Group group = groups.computeIfAbsent(requirement, Group::new);
            if (previous) group.before++; else group.after++;
            // Название и категория — из новой версии, если требование в ней есть.
            if (!previous || group.title == null) {
                group.title = finding.path("title").asText(finding.path("message").asText(requirement));
                group.category = finding.path("category").asText(null);
            }
        }
    }

    private static final class Group {
        final String requirementId;
        String title;
        String category;
        int before;
        int after;

        Group(String requirementId) { this.requirementId = requirementId; }

        Map<String, Object> view() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("requirement_id", requirementId);
            item.put("title", title);
            item.put("category", category);
            item.put("count_before", before);
            item.put("count_after", after);
            return item;
        }
    }
}