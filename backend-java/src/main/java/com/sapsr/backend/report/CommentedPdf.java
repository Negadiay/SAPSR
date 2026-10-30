package com.sapsr.backend.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Работа с комментариями автопроверки (api-v1.md, раздел 17): файл студента, в котором каждое замечание — комментарий
 * PDF на своём месте, как в Word. Неправильно оформленный список выделен целиком, комментарий — на нём.
 *
 * Место берётся из отчёта v2: прямоугольники {@code evidence[].areas}, их считает обработчик по модели документа.
 * Замечание к странице целиком (поля, шрифт) — заметка на полях этой страницы, ко всему документу — на первой.
 * Комментарии — обычные аннотации: их показывают Acrobat Reader, Edge, Foxit, Okular, Xodo. Автор — «SAPSR», поэтому
 * их легко отличить от своих, если преподаватель допишет пометки в этот же файл и вернёт его студенту.
 *
 * Файл собирается по текущему отчёту: пока идёт содержательная проверка — только оформление, потом — всё.
 */
public final class CommentedPdf {

    public static final String AUTHOR = "SAPSR";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** У замечания на многих страницах (шрифт, поля) заметок не больше этого — остальные страницы названы в тексте. */
    private static final int MAX_PAGE_NOTES = 10;
    private static final float NOTE_SIZE = 18;
    private static final float NOTE_STEP = 24;
    private static final float PAD = 1.5f;
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    // Выделение кладётся «умножением», как маркер: текст под ним остаётся читаемым. Цвета светлые — список
    // целиком не должен заливать страницу.
    private static final PDColor ERROR = rgb(1f, 0.78f, 0.75f);
    private static final PDColor WARNING = rgb(1f, 0.92f, 0.55f);
    private static final PDColor CONTENT = rgb(0.76f, 0.87f, 1f);

    private CommentedPdf() {}

    /** Место замечания на странице: прямоугольники в пунктах, начало — левый верхний угол (как в отчёте). */
    record Place(int page, List<float[]> boxes) {}

    /**
     * @param pdf      файл работы
     * @param findings замечания: {@code format_errors} работы — замечания v2 (с местами) или v1 (только страница)
     */
    public static byte[] build(byte[] pdf, String findings) throws IOException {
        JsonNode list = JSON.readTree(findings == null || findings.isBlank() ? "[]" : findings);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            Map<Integer, Integer> notesOnPage = new LinkedHashMap<>();
            Calendar now = Calendar.getInstance();
            int number = 0;
            for (JsonNode f : list.isArray() ? list : JSON.createArrayNode()) {
                if (!f.isObject()) continue;
                number++;
                String id = f.path("id").asText("f-" + number);
                String comment = comment(f);
                PDColor color = color(f);
                Map<Integer, List<float[]>> boxes = boxes(f, pages);
                for (Map.Entry<Integer, List<float[]>> place : boxes.entrySet()) {
                    PDPage page = document.getPage(place.getKey() - 1);
                    if (page.getRotation() % 360 != 0) continue;  // повёрнутая страница — заметкой ниже
                    PDAnnotationHighlight mark = new PDAnnotationHighlight();
                    describe(mark, id + "-p" + place.getKey(), f, comment, color, now);
                    highlight(document, mark, page, place.getValue(), color);
                    add(document, page, mark);
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
                    PDPage page = document.getPage(pageNumber - 1);
                    int k = notesOnPage.merge(pageNumber, 1, Integer::sum) - 1;
                    PDAnnotationText note = new PDAnnotationText();
                    describe(note, id + "-n" + pageNumber, f, text, color, now);
                    note.setName(PDAnnotationText.NAME_COMMENT);
                    note.setRectangle(noteRect(page, k));
                    add(document, page, note);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Текст комментария: само замечание и как исправить. */
    static String comment(JsonNode f) {
        StringBuilder text = new StringBuilder(f.path("message").asText("Замечание автопроверки"));
        String fix = f.path("fix").asText("");
        if (!fix.isBlank()) text.append("\n\nКак исправить: ").append(fix.strip());
        if (f.path("needs_teacher_review").asBoolean(false)) {
            text.append("\n\nМодель не уверена — нужна проверка преподавателя.");
        }
        return text.toString();
    }

    private static PDColor color(JsonNode f) {
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

    private static void describe(PDAnnotationMarkup annotation, String name, JsonNode f, String text, PDColor color,
                                 Calendar now) {
        annotation.setAnnotationName("sapsr-" + name);
        annotation.setTitlePopup(AUTHOR);
        String title = f.path("title").asText("");
        if (!title.isBlank()) annotation.setSubject(title);
        annotation.setContents(text);
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

    /** Заметка на правом поле, сверху вниз — по одной на замечание. */
    private static PDRectangle noteRect(PDPage page, int index) {
        PDRectangle media = page.getMediaBox();
        float x = media.getUpperRightX() - NOTE_SIZE - 8;
        float y = media.getUpperRightY() - 12 - NOTE_SIZE - (index % 30) * NOTE_STEP;
        return new PDRectangle(x, y, NOTE_SIZE, NOTE_SIZE);
    }

    /** Аннотация с внешним видом (иначе часть программ её не нарисует) и закрытым окном комментария. */
    private static void add(PDDocument document, PDPage page, PDAnnotationMarkup annotation) throws IOException {
        annotation.setPage(page);
        if (annotation.getAppearance() == null) annotation.constructAppearances(document);
        PDRectangle rect = annotation.getRectangle();
        PDRectangle media = page.getMediaBox();
        PDAnnotationPopup popup = new PDAnnotationPopup();
        float width = 220, height = 120;
        float x = Math.max(media.getLowerLeftX(), Math.min(rect.getUpperRightX(), media.getUpperRightX() - width));
        float y = Math.max(media.getLowerLeftY(), Math.min(rect.getUpperRightY() - height, media.getUpperRightY() - height));
        popup.setRectangle(new PDRectangle(x, y, width, height));
        popup.setOpen(false);
        popup.setParent(annotation);
        popup.setPage(page);
        annotation.setPopup(popup);
        List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
        annotations.add(annotation);
        annotations.add(popup);
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
