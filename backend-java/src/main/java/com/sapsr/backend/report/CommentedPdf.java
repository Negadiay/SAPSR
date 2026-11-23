package com.sapsr.backend.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPolyline;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.awt.geom.AffineTransform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Работа с комментариями автопроверки (api-v1.md, раздел 17): файл студента, где неправильно оформленное место
 * выделено, а замечание написано рядом, на добавленном справа поле, как примечания в Word.
 *
 * Текст замечаний — в заметках на поле со встроенным кириллическим шрифтом, а не во всплывающих окнах: Chrome и Edge
 * рисуют такие окна своим шрифтом без кириллицы, и от русского текста остаются только цифры и знаки препинания. По той
 * же причине у выделений нет текста: иначе браузер покажет над ними нечитаемое окно.
 *
 * Место берётся из отчёта v2: прямоугольники {@code evidence[].areas}, их считает обработчик по модели документа.
 * Автор заметок — «SAPSR», поэтому их легко отличить от своих, если преподаватель допишет пометки в этот же файл.
 * Файл собирается по текущему отчёту: пока идёт содержательная проверка — только оформление, потом — всё.
 */
public final class CommentedPdf {

    public static final String AUTHOR = "SAPSR";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** У замечания на многих страницах (шрифт, поля) заметок не больше этого — остальные страницы названы в тексте. */
    private static final int MAX_PAGE_NOTES = 10;
    private static final float PAD = 1.5f;
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /** Шрифт с кириллицей лежит в самом PDFBox: отдельный файл и зависимость не нужны. */
    private static final String NOTE_FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";
    private static final float NOTE_WIDTH = 200;
    private static final float NOTE_GAP = 8;
    /** Полоса между страницей и заметками: в ней сходятся линии от выделений, не задевая основной текст. */
    private static final float NOTE_GUTTER = 28;
    private static final float NOTE_PADDING = 5;
    private static final float NOTE_FONT_SIZE = 8;
    private static final float NOTE_LEADING = 10;
    private static final float PAGE_EDGE = 8;

    // Выделение кладётся «умножением», как маркер: текст под ним остаётся читаемым. Цвета светлые — список
    // целиком не должен заливать страницу. Линия к заметке — того же оттенка, но темнее.
    private static final Tone ERROR = new Tone(rgb(1f, 0.78f, 0.75f), rgb(0.85f, 0.25f, 0.2f));
    private static final Tone WARNING = new Tone(rgb(1f, 0.92f, 0.55f), rgb(0.8f, 0.6f, 0f));
    private static final Tone CONTENT = new Tone(rgb(0.76f, 0.87f, 1f), rgb(0.2f, 0.45f, 0.85f));

    private CommentedPdf() {}

    private record Tone(PDColor fill, PDColor line) {}

    /** Заметка страницы. top — высота места от верха видимой страницы; anchor — правый край выделения или null. */
    private record Note(String name, JsonNode finding, String text, Tone tone, float top, float[] anchor) {}

    /**
     * @param pdf      файл работы
     * @param findings замечания: {@code format_errors} работы — замечания v2 (с местами) или v1 (только страница)
     */
    public static byte[] build(byte[] pdf, String findings) throws IOException {
        JsonNode list = JSON.readTree(findings == null || findings.isBlank() ? "[]" : findings);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            Map<Integer, List<Note>> notes = new TreeMap<>();
            Calendar now = Calendar.getInstance();
            int number = 0;
            for (JsonNode f : list.isArray() ? list : JSON.createArrayNode()) {
                if (!f.isObject()) continue;
                number++;
                String id = f.path("id").asText("f-" + number);
                String comment = comment(f);
                Tone tone = tone(f);
                Map<Integer, List<float[]>> boxes = boxes(f, pages);
                for (Map.Entry<Integer, List<float[]>> place : boxes.entrySet()) {
                    PDPage page = document.getPage(place.getKey() - 1);
                    if (page.getRotation() % 360 != 0) continue;  // повёрнутая страница — заметкой ниже
                    PDAnnotationHighlight mark = new PDAnnotationHighlight();
                    describe(mark, id + "-p" + place.getKey(), f, tone.fill(), now);
                    highlight(document, mark, page, place.getValue(), tone.fill());
                    mark.setPage(page);
                    append(page, mark);
                    float[] first = place.getValue().get(0);
                    float shift = page.getMediaBox().getUpperRightY() - page.getCropBox().getUpperRightY();
                    notes.computeIfAbsent(place.getKey(), p -> new ArrayList<>()).add(new Note(
                            id + "-n" + place.getKey(), f, comment, tone, Math.max(0, first[1] - shift),
                            new float[]{first[2], (first[1] + first[3]) / 2}));
                }
                List<Integer> notePages = new ArrayList<>(notePages(f, pages, boxes.keySet(), document));
                if (notePages.isEmpty() && boxes.isEmpty()) notePages.add(1);
                int shown = Math.min(notePages.size(), MAX_PAGE_NOTES);
                for (int i = 0; i < shown; i++) {
                    int pageNumber = notePages.get(i);
                    String text = comment;
                    if (i == shown - 1 && notePages.size() > shown) {
                        text += "\n\nИ ещё на страницах: " + join(notePages.subList(shown, notePages.size())) + ".";
                    }
                    notes.computeIfAbsent(pageNumber, p -> new ArrayList<>())
                            .add(new Note(id + "-n" + pageNumber, f, text, tone, 0, null));
                }
            }
            if (!notes.isEmpty()) {
                PDFont font = noteFont(document);
                for (Map.Entry<Integer, List<Note>> entry : notes.entrySet()) {
                    placeNotes(document, document.getPage(entry.getKey() - 1), entry.getValue(), font, now);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static PDFont noteFont(PDDocument document) throws IOException {
        try (InputStream font = CommentedPdf.class.getResourceAsStream(NOTE_FONT)) {
            if (font == null) throw new IOException("Нет шрифта " + NOTE_FONT);
            return PDType0Font.load(document, font);
        }
    }

    /** Текст комментария: само замечание и как исправить. */
    static String comment(JsonNode f) {
        StringBuilder text = new StringBuilder(f.path("message").asText("Замечание автопроверки"));
        String fix = f.path("fix").asText("");
        if (!fix.isBlank()) text.append("\n\nКак исправить: ").append(fix.strip());
        if (f.path("needs_teacher_review").asBoolean(false)) {
            text.append("\n\nОкончательное решение примет преподаватель.");
        }
        return text.toString();
    }

    private static Tone tone(JsonNode f) {
        if (CheckReportPdf.isContent(f)) return CONTENT;
        return f.path("blocking").asBoolean(false) || "critical".equals(f.path("severity").asText()) ? ERROR : WARNING;
    }

    /** Прямоугольники мест по страницам; страница вне файла пропускается. */
    static Map<Integer, List<float[]>> boxes(JsonNode f, int pages) {
        Map<Integer, List<float[]>> result = new LinkedHashMap<>();
        for (JsonNode evidence : f.path("evidence")) {
            for (JsonNode area : evidence.path("areas")) {
                int page = area.path("page").asInt(0);
                JsonNode b = area.path("bbox");
                if (page < 1 || page > pages || !b.isArray() || b.size() != 4) continue;
                float[] box = {(float) b.get(0).asDouble(), (float) b.get(1).asDouble(),
                        (float) b.get(2).asDouble(), (float) b.get(3).asDouble()};
                if (box[2] <= box[0] || box[3] <= box[1]) continue;
                result.computeIfAbsent(page, p -> new ArrayList<>()).add(box);
            }
        }
        return result;
    }

    /** Страницы, где места нет прямоугольником: из evidence[].page или из поля page v1 («3», «3–5, 9»). */
    private static Set<Integer> notePages(JsonNode f, int pages, Set<Integer> marked, PDDocument document) {
        Set<Integer> result = new LinkedHashSet<>();
        for (JsonNode evidence : f.path("evidence")) {
            int page = evidence.path("page").asInt(0);
            if (evidence.path("areas").isArray() && !evidence.path("areas").isEmpty()) {
                // Выделено, но на повёрнутой странице выделение не ставится — туда заметку.
                for (JsonNode area : evidence.path("areas")) {
                    int p = area.path("page").asInt(0);
                    if (p >= 1 && p <= pages && document.getPage(p - 1).getRotation() % 360 != 0) result.add(p);
                }
                continue;
            }
            if (page >= 1 && page <= pages && !marked.contains(page)) result.add(page);
        }
        if (result.isEmpty() && marked.isEmpty() && !f.path("evidence").isArray()) {
            JsonNode page = f.path("page");
            Matcher m = NUMBER.matcher(page.isNumber() ? page.asText() : page.asText(""));
            while (m.find()) {
                int p = Integer.parseInt(m.group());
                if (p >= 1 && p <= pages) result.add(p);
            }
        }
        return result;
    }

    private static void describe(PDAnnotationMarkup annotation, String name, JsonNode f, PDColor color, Calendar now) {
        annotation.setAnnotationName("sapsr-" + name);
        annotation.setTitlePopup(AUTHOR);
        String title = f.path("title").asText("");
        if (!title.isBlank()) annotation.setSubject(title);
        annotation.setColor(color);
        annotation.setCreationDate(now);
        annotation.setModifiedDate(now);
        annotation.setPrinted(true);
    }

    /**
     * Отчёт считает от левого верхнего угла, PDF — от левого нижнего. Внешний вид рисуется здесь, а не стандартным
     * обработчиком PDFBox: у того края скруглены и на стыках элементов списка выходят пятна. Все прямоугольники —
     * один контур с одной заливкой: перекрытия не темнеют.
     */
    private static void highlight(PDDocument document, PDAnnotationHighlight mark, PDPage page, List<float[]> boxes,
                                  PDColor color) throws IOException {
        PDRectangle media = page.getMediaBox();
        float[] quads = new float[boxes.size() * 8];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            float[] b = boxes.get(i);
            float x1 = media.getLowerLeftX() + b[0] - PAD, x2 = media.getLowerLeftX() + b[2] + PAD;
            float top = media.getUpperRightY() - b[1] + PAD, bottom = media.getUpperRightY() - b[3] - PAD;
            System.arraycopy(new float[]{x1, top, x2, top, x1, bottom, x2, bottom}, 0, quads, i * 8, 8);
            minX = Math.min(minX, x1);
            maxX = Math.max(maxX, x2);
            minY = Math.min(minY, bottom);
            maxY = Math.max(maxY, top);
        }
        mark.setQuadPoints(quads);
        PDRectangle rect = new PDRectangle(minX, minY, maxX - minX, maxY - minY);
        mark.setRectangle(rect);

        PDAppearanceStream look = new PDAppearanceStream(document);
        look.setBBox(rect);  // форма в координатах страницы: BBox совпадает с Rect, сдвига нет
        look.setResources(new PDResources());
        try (PDPageContentStream draw = new PDPageContentStream(document, look)) {
            PDExtendedGraphicsState multiply = new PDExtendedGraphicsState();
            multiply.setBlendMode(BlendMode.MULTIPLY);
            draw.setGraphicsStateParameters(multiply);
            draw.setNonStrokingColor(color);
            for (int i = 0; i < quads.length; i += 8) {
                draw.addRect(quads[i], quads[i + 5], quads[i + 2] - quads[i], quads[i + 1] - quads[i + 5]);
            }
            draw.fill();
        }
        PDAppearanceDictionary appearance = new PDAppearanceDictionary();
        appearance.setNormalAppearance(look);
        mark.setAppearance(appearance);
    }

    /**
     * Заметки — на поле, добавленном справа от видимой страницы. Каждая стоит на высоте своего места или ниже
     * предыдущей; не поместилась — следующая колонка, поэтому текст не обрезается и основной текст не перекрывается.
     */
    private static void placeNotes(PDDocument document, PDPage page, List<Note> notes, PDFont font, Calendar now)
            throws IOException {
        PDRectangle crop = page.getCropBox();
        int rotation = Math.floorMod(page.getRotation(), 360);
        boolean sideways = rotation % 180 != 0;
        float pageWidth = sideways ? crop.getHeight() : crop.getWidth();
        float pageHeight = sideways ? crop.getWidth() : crop.getHeight();
        float bottom = pageHeight - PAGE_EDGE;
        int maxLines = Math.max(1, (int) ((bottom - PAGE_EDGE - 2 * NOTE_PADDING) / NOTE_LEADING));

        List<Note> ordered = new ArrayList<>(notes);
        ordered.sort(Comparator.comparingDouble(Note::top));
        List<Object[]> placed = new ArrayList<>();
        int column = 0;
        float cursor = PAGE_EDGE;
        for (Note note : ordered) {
            List<String> lines = wrap(printable(note.text(), font), font, NOTE_WIDTH - 2 * NOTE_PADDING);
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(0, maxLines));
                lines.set(maxLines - 1, "…");
            }
            float height = lines.size() * NOTE_LEADING + 2 * NOTE_PADDING;
            float top = Math.max(cursor, Math.min(note.top(), bottom - height));
            if (top + height > bottom) {
                column++;
                top = Math.max(PAGE_EDGE, Math.min(note.top(), bottom - height));
            }
            float left = pageWidth + NOTE_GUTTER + column * (NOTE_WIDTH + NOTE_GAP);
            placed.add(new Object[]{note, lines, left, top, height});
            cursor = top + height + NOTE_GAP / 2;
        }

        widen(page, crop, rotation, NOTE_GUTTER + (column + 1) * (NOTE_WIDTH + NOTE_GAP));
        // Сначала линии, потом заметки: линия ко второй колонке проходит под заметками первой.
        if (rotation == 0) {
            for (Object[] item : placed) {
                Note note = (Note) item[0];
                if (note.anchor() == null) continue;
                connect(document, page, note, crop.getUpperRightX() + NOTE_GUTTER / 3,
                        crop.getLowerLeftX() + (float) item[2],
                        crop.getUpperRightY() - (float) item[3] - NOTE_PADDING - NOTE_FONT_SIZE / 2, now);
            }
        }
        for (Object[] item : placed) {
            Note note = (Note) item[0];
            @SuppressWarnings("unchecked") List<String> lines = (List<String>) item[1];
            float left = (float) item[2], top = (float) item[3], height = (float) item[4];
            addNote(document, page, note, lines, font, unrotated(crop, rotation, left, top, NOTE_WIDTH, height),
                    rotation, height, now);
        }
    }

    /** Поле — справа на экране: у повёрнутой страницы это другой край её координат. */
    private static void widen(PDPage page, PDRectangle crop, int rotation, float margin) {
        float x0 = crop.getLowerLeftX(), y0 = crop.getLowerLeftY(), x1 = crop.getUpperRightX(), y1 = crop.getUpperRightY();
        switch (rotation) {
            case 90 -> y1 += margin;
            case 180 -> x0 -= margin;
            case 270 -> y0 -= margin;
            default -> x1 += margin;
        }
        PDRectangle media = page.getMediaBox();
        float mx0 = Math.min(media.getLowerLeftX(), x0), my0 = Math.min(media.getLowerLeftY(), y0);
        float mx1 = Math.max(media.getUpperRightX(), x1), my1 = Math.max(media.getUpperRightY(), y1);
        page.setMediaBox(new PDRectangle(mx0, my0, mx1 - mx0, my1 - my0));
        page.setCropBox(new PDRectangle(x0, y0, x1 - x0, y1 - y0));
    }

    /** Прямоугольник на экране (от левого верхнего угла страницы) — в координатах PDF с учётом поворота страницы. */
    private static PDRectangle unrotated(PDRectangle c, int rotation, float left, float top, float w, float h) {
        return switch (rotation) {
            case 90 -> new PDRectangle(c.getLowerLeftX() + top, c.getLowerLeftY() + left, h, w);
            case 180 -> new PDRectangle(c.getUpperRightX() - left - w, c.getLowerLeftY() + top, w, h);
            case 270 -> new PDRectangle(c.getUpperRightX() - top - h, c.getUpperRightY() - left - w, h, w);
            default -> new PDRectangle(c.getLowerLeftX() + left, c.getUpperRightY() - top - h, w, h);
        };
    }

    private static void addNote(PDDocument document, PDPage page, Note note, List<String> lines, PDFont font,
                                PDRectangle rect, int rotation, float height, Calendar now) throws IOException {
        PDAnnotationFreeText annotation = new PDAnnotationFreeText();
        describe(annotation, note.name(), note.finding(), note.tone().fill(), now);
        annotation.setContents(note.text());
        annotation.setDefaultAppearance("/Helv " + NOTE_FONT_SIZE + " Tf 0 g");
        annotation.setRectangle(rect);
        annotation.setPage(page);

        PDAppearanceStream look = new PDAppearanceStream(document);
        look.setBBox(new PDRectangle(NOTE_WIDTH, height));
        // Страницу просмотрщик повернёт на rotation — заметку заранее в обратную сторону, чтобы текст стоял прямо.
        if (rotation != 0) look.setMatrix(AffineTransform.getQuadrantRotateInstance(rotation / 90));
        look.setResources(new PDResources());
        try (PDPageContentStream draw = new PDPageContentStream(document, look)) {
            draw.setNonStrokingColor(note.tone().fill());
            draw.addRect(0, 0, NOTE_WIDTH, height);
            draw.fill();
            draw.setNonStrokingColor(note.tone().line());
            draw.addRect(0, 0, 2, height);
            draw.fill();
            draw.setNonStrokingColor(0f, 0f, 0f);
            draw.beginText();
            draw.setFont(font, NOTE_FONT_SIZE);
            draw.setLeading(NOTE_LEADING);
            draw.newLineAtOffset(NOTE_PADDING, height - NOTE_PADDING - NOTE_FONT_SIZE);
            for (String line : lines) {
                if (!line.isEmpty()) draw.showText(line);
                draw.newLine();
            }
            draw.endText();
        }
        PDAppearanceDictionary appearance = new PDAppearanceDictionary();
        appearance.setNormalAppearance(look);
        annotation.setAppearance(appearance);
        append(page, annotation);
    }

    /** Тонкая линия от выделения к его заметке. Без текста: это оформление, а не отдельный комментарий. */
    private static void connect(PDDocument document, PDPage page, Note note, float elbowX, float toX, float toY,
                                Calendar now) throws IOException {
        // Место в отчёте — от угла MediaBox; поле добавлено справа, поэтому левый и верхний края те же.
        PDRectangle media = page.getMediaBox();
        float fromX = media.getLowerLeftX() + note.anchor()[0] + PAD;
        float fromY = media.getUpperRightY() - note.anchor()[1];
        // До края страницы — по своей строке: так линия не перечёркивает соседние строки текста.
        PDAnnotationPolyline line = new PDAnnotationPolyline();
        describe(line, note.name() + "-line", note.finding(), note.tone().line(), now);
        line.setVertices(new float[]{fromX, fromY, elbowX, fromY, toX, toY});
        line.setRectangle(new PDRectangle(Math.min(fromX, toX) - 1, Math.min(fromY, toY) - 1,
                Math.abs(toX - fromX) + 2, Math.abs(toY - fromY) + 2));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0.5f);
        line.setBorderStyle(border);
        line.setPage(page);
        line.constructAppearances(document);
        append(page, line);
    }

    /** Символы, которых нет в шрифте (например, эмодзи), заменяются: иначе PDFBox не запишет строку. */
    static String printable(String text, PDFont font) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (c == '\n') {
                out.append('\n');
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                out.append(' ');
            } else {
                String symbol = new String(Character.toChars(c));
                try {
                    font.encode(symbol);
                    out.append(symbol);
                } catch (IOException | IllegalArgumentException e) {
                    out.append('?');
                }
            }
        });
        return out.toString();
    }

    /** Перенос по словам; слово длиннее строки разбивается. Пустая строка между абзацами сохраняется. */
    static List<String> wrap(String text, PDFont font, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.strip().split(" +")) {
                String rest = word;
                while (textWidth(font, rest) > width) {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    int end = rest.offsetByCodePoints(0, 1);
                    while (end < rest.length()
                            && textWidth(font, rest.substring(0, rest.offsetByCodePoints(end, 1))) <= width) {
                        end = rest.offsetByCodePoints(end, 1);
                    }
                    lines.add(rest.substring(0, end));
                    rest = rest.substring(end);
                }
                if (rest.isEmpty()) continue;
                String candidate = line.isEmpty() ? rest : line + " " + rest;
                if (textWidth(font, candidate) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(rest);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static float textWidth(PDFont font, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * NOTE_FONT_SIZE;
    }

    private static void append(PDPage page, PDAnnotation annotation) throws IOException {
        List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
        annotations.add(annotation);
        page.setAnnotations(annotations);
    }

    private static String join(List<Integer> pages) {
        StringBuilder text = new StringBuilder();
        for (Integer p : pages) text.append(text.isEmpty() ? "" : ", ").append(p);
        return text.toString();
    }

    private static PDColor rgb(float r, float g, float b) {
        return new PDColor(new float[]{r, g, b}, PDDeviceRGB.INSTANCE);
    }
}
