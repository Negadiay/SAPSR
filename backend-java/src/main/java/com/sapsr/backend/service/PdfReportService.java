package com.sapsr.backend.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import com.sapsr.backend.entity.Submission;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.format.DateTimeFormatter;

@Service
public class PdfReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private Font titleFont;
    private Font headerFont;
    private Font normalFont;
    private Font boldFont;
    private Font errorFont;
    private Font successFont;

    public PdfReportService() {
        try {
            BaseFont bf = loadCyrillicFont();
            titleFont = new Font(bf, 18, Font.BOLD, new Color(30, 30, 30));
            headerFont = new Font(bf, 13, Font.BOLD, new Color(60, 60, 60));
            normalFont = new Font(bf, 12, Font.NORMAL, new Color(40, 40, 40));
            boldFont = new Font(bf, 12, Font.BOLD, new Color(40, 40, 40));
            errorFont = new Font(bf, 12, Font.NORMAL, new Color(180, 30, 30));
            successFont = new Font(bf, 12, Font.BOLD, new Color(30, 140, 30));
        } catch (Exception e) {
            System.err.println("[PDF] Ошибка загрузки шрифта: " + e.getMessage());
            titleFont = FontFactory.getFont(FontFactory.HELVETICA, 18, Font.BOLD);
            headerFont = FontFactory.getFont(FontFactory.HELVETICA, 13, Font.BOLD);
            normalFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Font.NORMAL);
            boldFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Font.BOLD);
            errorFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Font.NORMAL, new Color(180, 30, 30));
            successFont = FontFactory.getFont(FontFactory.HELVETICA, 12, Font.BOLD, new Color(30, 140, 30));
        }
    }

    private BaseFont loadCyrillicFont() throws Exception {
        String[] fontPaths = {
            "C:/Windows/Fonts/times.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf"
        };

        for (String path : fontPaths) {
            if (new File(path).exists()) {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        }

        throw new RuntimeException("Cyrillic font not found");
    }

    public byte[] generateReport(Submission submission) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, baos);
            document.open();

            Paragraph title = new Paragraph("SAPSR", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            Paragraph subtitle = new Paragraph("Отчёт о проверке форматирования", headerFont);
            subtitle.setAlignment(Element.ALIGN_CENTER);
            subtitle.setSpacingAfter(10);
            document.add(subtitle);

            document.add(new LineSeparator(1f, 100f, new Color(200, 200, 200), Element.ALIGN_CENTER, -2));
            document.add(Chunk.NEWLINE);

            if (submission.getStudent() != null) {
                document.add(createField("Студент:", submission.getStudent().getFullName()));
                document.add(createField("Группа:", submission.getStudent().getGroupNumber()));
            }

            String fileName = extractFileName(submission.getFilePath());
            document.add(createField("Файл:", fileName));

            if (submission.getCreatedAt() != null) {
                document.add(createField("Дата проверки:", submission.getCreatedAt().format(DATE_FMT)));
            }

            String statusText;
            Font statusFont;
            if ("SUCCESS".equals(submission.getStatus())) {
                statusText = "ПРИНЯТО";
                statusFont = successFont;
            } else {
                statusText = "ОТКЛОНЕНО";
                statusFont = errorFont;
            }
            Paragraph statusP = new Paragraph();
            statusP.add(new Chunk("Статус: ", boldFont));
            statusP.add(new Chunk(statusText, statusFont));
            statusP.setSpacingAfter(10);
            document.add(statusP);

            document.add(new LineSeparator(0.5f, 100f, new Color(220, 220, 220), Element.ALIGN_CENTER, -2));
            document.add(Chunk.NEWLINE);

            String errors = submission.getFormatErrors();
            boolean hasErrors = errors != null && !errors.equals("[]") && !errors.equals("null") && !errors.isBlank();

            if (hasErrors) {
                document.add(new Paragraph("Обнаруженные ошибки:", headerFont));
                document.add(Chunk.NEWLINE);

                String cleaned = errors;
                if (cleaned.startsWith("[")) cleaned = cleaned.substring(1);
                if (cleaned.endsWith("]")) cleaned = cleaned.substring(0, cleaned.length() - 1);

                String[] parts = cleaned.split("\\},\\s*\\{");
                int num = 1;
                for (String part : parts) {
                    String page = extractJsonValue(part, "page");
                    String message = extractJsonValue(part, "message");
                    String line = num + ". ";
                    if (page != null && !page.equals("0")) {
                        line += "Стр. " + page + ": ";
                    }
                    line += (message != null ? message : part);
                    document.add(new Paragraph(line, errorFont));
                    num++;
                }
            } else {
                document.add(new Paragraph("Ошибок форматирования не обнаружено.", successFont));
            }

            document.close();
            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Ошибка генерации PDF-отчёта", e);
        }
    }

    private Paragraph createField(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", boldFont));
        p.add(new Chunk(value != null ? value : "—", normalFont));
        p.setSpacingAfter(4);
        return p;
    }

    private String extractFileName(String filePath) {
        if (filePath == null) return "—";
        String name = filePath;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("\\")) name = name.substring(name.lastIndexOf('\\') + 1);
        if (name.matches("^\\d+_.*")) name = name.substring(name.indexOf('_') + 1);
        return name;
    }

    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;

        String rest = json.substring(colonIdx + 1).trim();
        if (rest.startsWith("\"")) {
            int endQuote = rest.indexOf('"', 1);
            return endQuote > 0 ? rest.substring(1, endQuote) : rest.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (c == ',' || c == '}' || c == ']') break;
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
