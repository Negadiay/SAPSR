package com.sapsr.backend.review;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * PDF для тестов: строки текста в известных местах и аннотации поверх них — как их сохраняют
 * Acrobat Reader или Edge. Стандартный шрифт Helvetica не знает кириллицы, поэтому текст страниц латиницей;
 * в комментариях кириллица допустима — это строки PDF, а не текст страницы.
 */
final class AnnotatedPdf implements AutoCloseable {

    static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static final float SIZE = 12;
    static final float LEFT = 72;

    final PDDocument document = new PDDocument();

    /** Новая страница A4 со строками: первая на высоте 750 пт, дальше каждые 20 пт ниже. */
    AnnotatedPdf page(String... lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (int i = 0; i < lines.length; i++) {
                content.beginText();
                content.setFont(FONT, SIZE);
                content.newLineAtOffset(LEFT, baseline(i));
                content.showText(lines[i]);
                content.endText();
            }
        }
        return this;
    }

    static float baseline(int line) {
        return 750 - line * 20;
    }

    /** QuadPoints выделения фрагмента строки — как их записывают редакторы: верх-лево, верх-право, низ-лево, низ-право. */
    static float[] quad(int line, String lineText, String fragment) throws IOException {
        int start = lineText.indexOf(fragment);
        if (start < 0) throw new IllegalArgumentException(fragment + " нет в строке");
        float x1 = LEFT + width(lineText.substring(0, start));
        float x2 = x1 + width(fragment);
        float top = baseline(line) + SIZE * 0.9f;
        float bottom = baseline(line) - SIZE * 0.25f;
        return new float[]{x1, top, x2, top, x1, bottom, x2, bottom};
    }

    static PDRectangle bounds(float[] quad) {
        return new PDRectangle(quad[0], quad[5], quad[2] - quad[0], quad[1] - quad[5]);
    }

    private static float width(String text) throws IOException {
        return FONT.getStringWidth(text) / 1000 * SIZE;
    }

    <T extends PDAnnotation> T annotate(int pageIndex, T annotation) throws IOException {
        PDPage page = document.getPage(pageIndex);
        java.util.List<PDAnnotation> annotations = new java.util.ArrayList<>(page.getAnnotations());
        annotations.add(annotation);
        page.setAnnotations(annotations);
        return annotation;
    }

    byte[] bytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        document.close();
    }
}
