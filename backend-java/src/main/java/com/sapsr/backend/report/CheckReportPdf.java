package com.sapsr.backend.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.User;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF-отчёт о проверке работы для студента и преподавателя: шапка (кто, что, когда, итог), сводка и замечания
 * двумя разделами — оформление и содержание. Замечания — из format_errors (форма v1 с полями v2).
 * Без значков-эмодзи: шрифты Arial и DejaVu их не рисуют.
 */
public final class CheckReportPdf {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");
    private static final Map<String, String> WORK_TYPES = Map.of(
            "course_work", "Курсовая работа",
            "course_project", "Курсовой проект",
            "diploma", "Дипломная работа");
    private static final Map<String, String> ORIGINS = Map.of(
            "symbolic", "найдено правилами",
            "neural", "найдено нейросетью",
            "hybrid", "найдено нейросетью и правилами");

    private static final Color TEXT = new Color(30, 30, 30);
    private static final Color HINT = new Color(100, 100, 100);
    private static final Color OK = new Color(46, 125, 50);
    private static final Color ERROR = new Color(198, 40, 40);
    private static final Color WARNING = new Color(191, 90, 0);
    private static final Color SNIPPET_BG = new Color(245, 245, 245);

    private CheckReportPdf() {}

    public static byte[] build(Submission s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 56, 56, 56, 56);
        PdfWriter.getInstance(doc, out);
        doc.open();

        BaseFont bf = cyrillicFont();
        Font title = new Font(bf, 16, Font.BOLD, TEXT);
        Font section = new Font(bf, 13, Font.BOLD, TEXT);
        Font heading = new Font(bf, 11, Font.BOLD, TEXT);
        Font normal = new Font(bf, 10, Font.NORMAL, TEXT);
        Font label = new Font(bf, 10, Font.BOLD, HINT);
        Font small = new Font(bf, 9, Font.NORMAL, HINT);

        List<JsonNode> findings = findings(s.getFormatErrors());
        List<JsonNode> format = new ArrayList<>();
        List<JsonNode> content = new ArrayList<>();
        for (JsonNode f : findings) (isContent(f) ? content : format).add(f);
        // Сначала то, что мешает сдаче.
        format.sort((a, b) -> Boolean.compare(!isError(a), !isError(b)));
        long errors = format.stream().filter(CheckReportPdf::isError).count();
        boolean passed = !"REJECTED".equals(s.getStatus());

        doc.add(new Paragraph("Отчёт о проверке работы", title));
        Paragraph meta = new Paragraph();
        meta.setSpacingBefore(8);
        User student = s.getStudent();
        if (student != null) {
            String group = student.getGroupNumber();
            line(meta, "Студент", student.displayName() + (group == null ? "" : ", группа " + group), label, normal);
        }
        line(meta, "Файл", s.displayFileName(), label, normal);
        String workType = s.getWorkType() == null ? null : WORK_TYPES.get(s.getWorkType());
        if (workType != null) line(meta, "Вид работы", workType, label, normal);
        if (s.getCreatedAt() != null) line(meta, "Загружена", DATE_TIME.format(s.getCreatedAt()), label, normal);
        doc.add(meta);

        Paragraph result = new Paragraph(passed ? "Оформление без ошибок" : "Есть ошибки оформления: " + errors,
                new Font(bf, 12, Font.BOLD, passed ? OK : ERROR));
        result.setSpacingBefore(10);
        doc.add(result);
        Paragraph summary = new Paragraph(summary(format.size() - errors, content.size()), normal);
        summary.setSpacingAfter(6);
        doc.add(summary);

        if (findings.isEmpty()) {
            doc.add(new Paragraph("Замечаний нет.", normal));
        }
        int number = 0;
        if (!format.isEmpty()) {
            doc.add(sectionTitle("Оформление и структура", section));
            for (JsonNode f : format) addFinding(doc, ++number, f, heading, normal, label, small, bf);
        }
        if (!content.isEmpty()) {
            doc.add(sectionTitle("Содержание", section));
            Paragraph note = new Paragraph("Замечания по содержанию сдаче не мешают; решение принимает преподаватель.", small);
            note.setSpacingAfter(4);
            doc.add(note);
            for (JsonNode f : content) addFinding(doc, ++number, f, heading, normal, label, small, bf);
        }

        Paragraph footer = new Paragraph("Проверено автоматически системой SAPSR.", small);
        footer.setSpacingBefore(16);
        footer.setAlignment(Element.ALIGN_CENTER);
        doc.add(footer);
        doc.close();
        return out.toByteArray();
    }

    private static void addFinding(Document doc, int number, JsonNode f, Font heading, Font normal, Font label,
                                   Font small, BaseFont bf) throws Exception {
        Paragraph head = new Paragraph(number + ". " + text(f, "message", text(f, "title", "Замечание")), heading);
        head.setSpacingBefore(10);
        doc.add(head);

        List<String> tags = new ArrayList<>();
        tags.add(severityLabel(f));
        String where = text(f, "location", "");
        if (where.isBlank() && !text(f, "page", "").isBlank()) where = "стр. " + text(f, "page", "");
        if (!where.isBlank()) tags.add(where);
        if (isContent(f)) {
            tags.add(ORIGINS.getOrDefault(text(f, "origin", "symbolic"), "найдено правилами"));
            if (f.path("needs_teacher_review").asBoolean(false)) tags.add("требует решения преподавателя");
        }
        Font tagFont = new Font(bf, 9, Font.NORMAL, isError(f) && !isContent(f) ? ERROR : WARNING);
        doc.add(new Paragraph(String.join(" · ", tags), tagFont));

        Paragraph details = new Paragraph();
        details.setSpacingBefore(2);
        String found = text(f, "found", "");
        String expected = text(f, "expected", text(f, "rule", ""));
        String fix = text(f, "fix", "");
        if (!found.isBlank()) line(details, "Найдено", found, label, normal);
        if (!expected.isBlank() && !expected.equals(text(f, "message", ""))) line(details, "Нужно", expected, label, normal);
        if (!fix.isBlank()) line(details, "Как исправить", fix, label, normal);
        doc.add(details);

        String context = text(f, "context", "");
        if (!context.isBlank()) {
            PdfPTable box = new PdfPTable(1);
            box.setWidthPercentage(100);
            box.setSpacingBefore(3);
            PdfPCell cell = new PdfPCell(new Phrase(context.replace("\n", " "), small));
            cell.setBackgroundColor(SNIPPET_BG);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(6);
            box.addCell(cell);
            doc.add(box);
        }
    }

    private static Paragraph sectionTitle(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(14);
        return p;
    }

    private static void line(Paragraph p, String name, String value, Font label, Font normal) {
        if (!p.isEmpty()) p.add(new Phrase("\n"));
        p.add(new Phrase(name + ": ", label));
        p.add(new Phrase(value, normal));
    }

    private static String summary(long warnings, int content) {
        List<String> parts = new ArrayList<>();
        if (warnings > 0) parts.add("предупреждений по оформлению — " + warnings);
        if (content > 0) parts.add("замечаний по содержанию — " + content);
        if (parts.isEmpty()) return "";
        String text = String.join(", ", parts);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    private static List<JsonNode> findings(String errorsJson) {
        List<JsonNode> list = new ArrayList<>();
        if (errorsJson == null || errorsJson.isBlank()) return list;
        try {
            JsonNode arr = JSON.readTree(errorsJson);
            if (arr.isArray()) arr.forEach(f -> { if (f.isObject()) list.add(f); });
        } catch (Exception e) {
            // Повреждённый отчёт — PDF без замечаний лучше, чем ошибка скачивания.
        }
        return list;
    }

    /** Содержательные — от языковой модели и правил над её фактами; сдачу не блокируют (решение 5). */
    private static boolean isContent(JsonNode f) {
        String category = text(f, "category", "");
        return "content".equals(category) || "requirements".equals(category) || !"symbolic".equals(text(f, "origin", "symbolic"));
    }

    private static boolean isError(JsonNode f) {
        String severity = text(f, "severity", "");
        return "critical".equals(severity) || "major".equals(severity);
    }

    private static String severityLabel(JsonNode f) {
        return switch (text(f, "severity", "")) {
            case "critical" -> "критическая ошибка";
            case "major" -> "ошибка";
            case "info" -> "примечание";
            default -> "предупреждение";
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static BaseFont cyrillicFont() {
        String[] candidates = {
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                try {
                    return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } catch (Exception ignored) {
                    // следующий шрифт
                }
            }
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
        } catch (Exception e) {
            throw new IllegalStateException("Не найден шрифт для отчёта", e);
        }
    }
}
