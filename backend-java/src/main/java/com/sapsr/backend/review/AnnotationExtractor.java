package com.sapsr.backend.review;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.util.DateConverter;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Комментарии преподавателя из аннотаций PDF (решение 6, docs/contracts/api-v1.md, раздел 10).
 * Работает с любым редактором, который сохраняет пометки как аннотации: Acrobat Reader, Edge, Foxit, Okular, Xodo.
 * Если файл сохранён «печатью в PDF», пометки становятся частью страницы и аннотаций в нём нет.
 */
public final class AnnotationExtractor {

    /** Тип аннотации PDF → вид комментария в API. Не перечисленные (Popup, Link, Widget и т. п.) пропускаются. */
    private static final Map<String, String> KINDS = Map.ofEntries(
            Map.entry("Text", "NOTE"),
            Map.entry("Caret", "NOTE"),
            Map.entry("Highlight", "HIGHLIGHT"),
            Map.entry("Underline", "UNDERLINE"),
            Map.entry("StrikeOut", "STRIKEOUT"),
            Map.entry("Squiggly", "SQUIGGLY"),
            Map.entry("FreeText", "FREE_TEXT"),
            Map.entry("Ink", "INK"),
            Map.entry("Stamp", "STAMP"),
            Map.entry("Square", "SHAPE"),
            Map.entry("Circle", "SHAPE"),
            Map.entry("Line", "SHAPE"),
            Map.entry("Polygon", "SHAPE"),
            Map.entry("PolyLine", "SHAPE"));

    private static final COSName STATE_MODEL = COSName.getPDFName("StateModel");

    private AnnotationExtractor() {}

    /**
     * Один комментарий. {@code replyTo} — индекс комментария в том же списке, на который это ответ.
     * {@code rect} — [x1, y1, x2, y2] в пунктах, начало координат — левый нижний угол страницы.
     */
    public record Extracted(int page, String kind, String author, LocalDateTime createdAt,
                            String comment, String quotedText, float[] rect, Integer replyTo) {}

    /** Комментарии в порядке показа: по страницам, на странице сверху вниз, ответы — сразу после своего комментария. */
    public static List<Extracted> extract(PDDocument document) throws IOException {
        List<Raw> raws = new ArrayList<>();
        Map<COSDictionary, Raw> byDictionary = new IdentityHashMap<>();
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            List<Raw> onPage = new ArrayList<>();
            for (PDAnnotation annotation : page.getAnnotations()) {
                String kind = KINDS.get(annotation.getSubtype());
                // Отметки статуса рецензирования («Принято», «Отмечено») — служебные ответы, не комментарии.
                if (kind == null || annotation.getCOSObject().containsKey(STATE_MODEL)) continue;
                Raw raw = new Raw(raws.size(), pageNumber, kind, annotation);
                raws.add(raw);
                onPage.add(raw);
                byDictionary.put(annotation.getCOSObject(), raw);
            }
            readQuotedText(page, onPage);
        }

        for (Raw raw : raws) {
            if (raw.annotation instanceof PDAnnotationMarkup markup) {
                PDAnnotation target = markup.getInReplyTo();
                Raw parent = target == null ? null : byDictionary.get(target.getCOSObject());
                if (parent != null && parent != raw) {
                    raw.parent = parent;
                    parent.replies.add(raw);
                }
            }
        }

        List<Raw> roots = raws.stream().filter(r -> r.parent == null)
                .sorted(Comparator.comparingInt((Raw r) -> r.page)
                        .thenComparing(r -> -r.top())
                        .thenComparing(Raw::left)
                        .thenComparingInt(r -> r.index))
                .toList();
        List<Raw> ordered = new ArrayList<>();
        Set<Raw> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Raw root : roots) appendThread(root, ordered, visited);

        Map<Raw, Integer> position = new IdentityHashMap<>();
        for (int i = 0; i < ordered.size(); i++) position.put(ordered.get(i), i);
        List<Extracted> result = new ArrayList<>();
        for (Raw raw : ordered) {
            result.add(new Extracted(raw.page, raw.kind, author(raw.annotation), createdAt(raw.annotation),
                    comment(raw.annotation), raw.quotedText, rect(raw.annotation),
                    raw.parent == null ? null : position.get(raw.parent)));
        }
        return result;
    }

    /** Слова с первых страниц — чтобы понять, тот ли это документ, что прислал студент. */
    public static Set<String> words(PDDocument document, int maxPages) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(maxPages, document.getNumberOfPages()));
        Set<String> words = new HashSet<>();
        for (String word : stripper.getText(document).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() > 1) words.add(word);
        }
        return words;
    }

    // Ответ идёт сразу за комментарием, на который отвечает; ответы между собой — по дате.
    private static void appendThread(Raw raw, List<Raw> ordered, Set<Raw> visited) {
        if (!visited.add(raw)) return;
        ordered.add(raw);
        raw.replies.sort(Comparator.comparing((Raw r) -> createdAt(r.annotation),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparingInt(r -> r.index));
        for (Raw reply : raw.replies) appendThread(reply, ordered, visited);
    }

    // Текст под выделением: по четырёхугольникам QuadPoints, каждый — строка или её часть.
    private static void readQuotedText(PDPage page, List<Raw> onPage) throws IOException {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.setSortByPosition(true);
        PDRectangle crop = page.getCropBox();
        Map<Raw, List<String>> regions = new IdentityHashMap<>();
        for (Raw raw : onPage) {
            if (!(raw.annotation instanceof PDAnnotationTextMarkup markup)) continue;
            float[] quads = markup.getQuadPoints();
            List<String> names = new ArrayList<>();
            for (int q = 0; quads != null && q + 7 < quads.length; q += 8) {
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (int k = 0; k < 8; k += 2) {
                    minX = Math.min(minX, quads[q + k]);
                    maxX = Math.max(maxX, quads[q + k]);
                    minY = Math.min(minY, quads[q + k + 1]);
                    maxY = Math.max(maxY, quads[q + k + 1]);
                }
                // Область у PDFTextStripperByArea — от левого верхнего угла страницы, ось Y вниз.
                String name = raw.index + ":" + q;
                stripper.addRegion(name, new Rectangle2D.Float(
                        minX - crop.getLowerLeftX() - 1, crop.getUpperRightY() - maxY,
                        maxX - minX + 2, maxY - minY));
                names.add(name);
            }
            if (!names.isEmpty()) regions.put(raw, names);
        }
        if (regions.isEmpty()) return;
        stripper.extractRegions(page);
        for (Map.Entry<Raw, List<String>> entry : regions.entrySet()) {
            StringBuilder text = new StringBuilder();
            for (String name : entry.getValue()) text.append(stripper.getTextForRegion(name)).append(' ');
            entry.getKey().quotedText = blankToNull(normalizeSpaces(text.toString()));
        }
    }

    private static String comment(PDAnnotation annotation) {
        String contents = blankToNull(annotation.getContents());
        if (contents == null && annotation instanceof PDAnnotationMarkup markup) {
            // У некоторых редакторов текст только в форматированном виде (RC) — без разметки.
            String rich = markup.getRichContents();
            if (rich != null) contents = blankToNull(rich.replaceAll("<[^>]*>", " "));
        }
        return contents == null ? null : contents.strip().replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String author(PDAnnotation annotation) {
        return annotation instanceof PDAnnotationMarkup markup ? blankToNull(markup.getTitlePopup()) : null;
    }

    private static LocalDateTime createdAt(PDAnnotation annotation) {
        Calendar calendar = null;
        try {
            if (annotation instanceof PDAnnotationMarkup markup) calendar = markup.getCreationDate();
            if (calendar == null && annotation.getModifiedDate() != null) {
                calendar = DateConverter.toCalendar(annotation.getModifiedDate());
            }
        } catch (RuntimeException e) {
            // Дата в нестандартном виде — комментарий важнее даты.
            return null;
        }
        return calendar == null ? null : LocalDateTime.ofInstant(calendar.toInstant(), ZoneId.systemDefault());
    }

    private static float[] rect(PDAnnotation annotation) {
        PDRectangle r = annotation.getRectangle();
        return r == null ? null
                : new float[]{r.getLowerLeftX(), r.getLowerLeftY(), r.getUpperRightX(), r.getUpperRightY()};
    }

    private static String normalizeSpaces(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static final class Raw {
        final int index;
        final int page;
        final String kind;
        final PDAnnotation annotation;
        final List<Raw> replies = new ArrayList<>();
        Raw parent;
        String quotedText;

        Raw(int index, int page, String kind, PDAnnotation annotation) {
            this.index = index;
            this.page = page;
            this.kind = kind;
            this.annotation = annotation;
        }

        float top() {
            PDRectangle r = annotation.getRectangle();
            return r == null ? 0 : r.getUpperRightY();
        }

        float left() {
            PDRectangle r = annotation.getRectangle();
            return r == null ? 0 : r.getLowerLeftX();
        }
    }
}
