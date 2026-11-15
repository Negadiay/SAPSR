package com.sapsr.backend.report;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Сравнение версий работы по замечаниям (решение 17). */
public class ReportComparisonTest {

    public static String report(String... requirementIds) {
        StringBuilder findings = new StringBuilder();
        int n = 0;
        for (String id : requirementIds) {
            if (n++ > 0) findings.append(',');
            findings.append("{\"id\":\"f-%03d\",\"requirement_id\":\"%s\",\"category\":\"format\",\"origin\":\"%s\",\"title\":\"Требование %s\"}"
                    .formatted(n, id, id.startsWith("CNT") ? "hybrid" : "symbolic", id));
        }
        return "{\"schema\":2,\"phase\":\"content\",\"findings\":[" + findings + "]}";
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> comparison, String key) {
        return ((List<Map<String, Object>>) comparison.get(key)).stream()
                .map(item -> (String) item.get("requirement_id")).toList();
    }

    @Test
    @Tag("typical")
    void splitsIntoFixedRemainingAndNew() {
        Map<String, Object> comparison = ReportComparison.compare(4,
                report("FMT-MARGIN-LEFT", "FMT-MARGIN-LEFT", "LST-SIMPLE", "CNT-CONSIST-002"),
                report("FMT-MARGIN-LEFT", "CNT-CONSIST-002", "REF-COUNT"));

        assertThat(comparison).containsEntry("previous_submission_id", 4);
        assertThat(ids(comparison, "fixed")).containsExactly("LST-SIMPLE");
        assertThat(ids(comparison, "remaining")).containsExactly("FMT-MARGIN-LEFT", "CNT-CONSIST-002");
        assertThat(ids(comparison, "new")).containsExactly("REF-COUNT");
        @SuppressWarnings("unchecked")
        Map<String, Object> margin = ((List<Map<String, Object>>) comparison.get("remaining")).get(0);
        assertThat(margin).containsEntry("count_before", 2).containsEntry("count_after", 1)
                .containsEntry("title", "Требование FMT-MARGIN-LEFT").containsEntry("category", "format");
    }

    @Test
    @Tag("boundary")
    void emptyReportsGiveEmptyLists() {
        Map<String, Object> comparison = ReportComparison.compare(4, report(), report());
        assertThat(ids(comparison, "fixed")).isEmpty();
        assertThat(ids(comparison, "remaining")).isEmpty();
        assertThat(ids(comparison, "new")).isEmpty();
    }

    @Test
    @Tag("typical")
    void sameReportsMeanEverythingRemains() {
        String same = report("FMT-MARGIN-LEFT", "REF-COUNT");
        Map<String, Object> comparison = ReportComparison.compare(4, same, same);
        assertThat(ids(comparison, "remaining")).containsExactly("FMT-MARGIN-LEFT", "REF-COUNT");
        assertThat(ids(comparison, "fixed")).isEmpty();
        assertThat(ids(comparison, "new")).isEmpty();
    }

    @Test
    @Tag("typical")
    void everythingFixed() {
        Map<String, Object> comparison = ReportComparison.compare(4, report("FMT-MARGIN-LEFT", "REF-COUNT"), report());
        assertThat(ids(comparison, "fixed")).containsExactly("FMT-MARGIN-LEFT", "REF-COUNT");
        assertThat(ids(comparison, "remaining")).isEmpty();
    }

    @Test
    @Tag("negative")
    void oldFormatOrMissingReportGivesNoComparison() {
        String v1 = "[{\"type\":\"error\",\"message\":\"Левое поле\"}]";
        assertThat(ReportComparison.compare(4, v1, report("REF-COUNT"))).isNull();
        assertThat(ReportComparison.compare(4, null, report("REF-COUNT"))).isNull();
        assertThat(ReportComparison.compare(4, report("REF-COUNT"), "{}")).isNull();
        assertThat(ReportComparison.compare(4, "не json", report())).isNull();
        assertThat(ReportComparison.compare(null, report(), report())).isNull();
    }
}