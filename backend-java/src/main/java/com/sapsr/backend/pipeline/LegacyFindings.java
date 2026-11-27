package com.sapsr.backend.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Замечания отчёта v2 в форме результата v1 — для format_errors. По ним работают PDF-отчёт, уведомления
 * и интерфейс, пока он не перешёл на отчёт v2 (report-v2.md, раздел 10).
 *
 * Поля v1 в замечаниях v2 уже есть; меняется только severity: в v1 она отражает блокировку — неблокирующее
 * замечание уровня major приходит как warning, чтобы «ошибки» в счётчиках совпадали со статусом работы.
 * Так же переводит обработчик: worker-python/sapsr/report/legacy.py, legacy_severity.
 */
public final class LegacyFindings {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LegacyFindings() {}

    public static String of(JsonNode report) {
        ArrayNode errors = JSON.createArrayNode();
        for (JsonNode finding : report.path("findings")) {
            if (!finding.isObject()) continue;
            ObjectNode copy = finding.deepCopy();
            copy.put("severity", severity(finding.path("severity").asText(""), finding.path("blocking").asBoolean(false)));
            errors.add(copy);
        }
        return errors.toString();
    }

    static String severity(String severity, boolean blocking) {
        if (blocking) return "critical".equals(severity) || "major".equals(severity) ? severity : "major";
        return "minor".equals(severity) ? "minor" : "warning";
    }
}
