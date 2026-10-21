package com.sapsr.backend.review;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationExtractorTest {

    private static final String LINE_0 = "1.1 Requirements";
    private static final String LINE_1 = "The system accepts files in PDF and DOCX formats.";
    private static final String LINE_2 = "Only PDF files are supported by the checker.";

    @Test
    @Tag("typical")
    void highlightGivesCommentAuthorDateAndTextUnderSelection() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_0, LINE_1, LINE_2)) {
            PDAnnotationHighlight highlight = new PDAnnotationHighlight();
            float[] quad = AnnotatedPdf.quad(1, LINE_1, LINE_1);
            highlight.setQuadPoints(quad);
            highlight.setRectangle(AnnotatedPdf.bounds(quad));
            highlight.setContents("Противоречит разделу 1.5: там только PDF.");
            highlight.setTitlePopup("Гракова Н.В.");
            highlight.setCreationDate(new GregorianCalendar(2026, Calendar.OCTOBER, 21, 14, 30));
            pdf.annotate(0, highlight);

            List<AnnotationExtractor.Extracted> result = extract(pdf);

            assertThat(result).singleElement().satisfies(a -> {
                assertThat(a.page()).isEqualTo(1);
                assertThat(a.kind()).isEqualTo("HIGHLIGHT");
                assertThat(a.quotedText()).isEqualTo(LINE_1);
                assertThat(a.comment()).isEqualTo("Противоречит разделу 1.5: там только PDF.");
                assertThat(a.author()).isEqualTo("Гракова Н.В.");
                assertThat(a.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 14, 30));
                assertThat(a.rect()).hasSize(4);
                assertThat(a.replyTo()).isNull();
            });
        }
    }

    @Test
    @Tag("boundary")
    void partialSelectionGivesOnlySelectedWords() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_0, LINE_1)) {
            PDAnnotationUnderline underline = new PDAnnotationUnderline();
            float[] quad = AnnotatedPdf.quad(1, LINE_1, "PDF and DOCX");
            underline.setQuadPoints(quad);
            underline.setRectangle(AnnotatedPdf.bounds(quad));
            pdf.annotate(0, underline);

            AnnotationExtractor.Extracted a = extract(pdf).get(0);

            assertThat(a.kind()).isEqualTo("UNDERLINE");
            assertThat(a.quotedText()).isEqualTo("PDF and DOCX");
            assertThat(a.comment()).isNull();
        }
    }

    @Test
    @Tag("typical")
    void selectionAcrossTwoLinesJoinsBothParts() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_0, LINE_1, LINE_2)) {
            PDAnnotationStrikeout strike = new PDAnnotationStrikeout();
            float[] first = AnnotatedPdf.quad(1, LINE_1, "DOCX formats.");
            float[] second = AnnotatedPdf.quad(2, LINE_2, "Only PDF");
            float[] both = new float[16];
            System.arraycopy(first, 0, both, 0, 8);
            System.arraycopy(second, 0, both, 8, 8);
            strike.setQuadPoints(both);
            strike.setRectangle(new PDRectangle(AnnotatedPdf.LEFT, second[5], 400, first[1] - second[5]));
            pdf.annotate(0, strike);

            assertThat(extract(pdf).get(0).quotedText()).isEqualTo("DOCX formats. Only PDF");
        }
    }

    @Test
    @Tag("typical")
    void repliesFollowTheirCommentAndCommentsGoTopToBottom() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_0, LINE_1, LINE_2).page("Second page")) {
            // Добавляем в «неудобном» порядке: снизу вверх и ответ раньше вопроса.
            PDAnnotationText lower = note("Нижняя заметка", 300);
            pdf.annotate(0, lower);
            PDAnnotationText upper = note("Где описан решатель?", 700);
            PDAnnotationText reply = note("Ответ: в разделе 2.3", 0);
            reply.setInReplyTo(upper);
            pdf.annotate(0, reply);
            pdf.annotate(0, upper);
            pdf.annotate(1, note("На второй странице", 780));

            List<AnnotationExtractor.Extracted> result = extract(pdf);

            assertThat(result).extracting(AnnotationExtractor.Extracted::comment).containsExactly(
                    "Где описан решатель?", "Ответ: в разделе 2.3", "Нижняя заметка", "На второй странице");
            assertThat(result.get(1).replyTo()).isEqualTo(0);
            assertThat(result.get(3).page()).isEqualTo(2);
        }
    }

    @Test
    @Tag("typical")
    void recognisesAllCommentKindsAndSkipsServiceAnnotations() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_0, LINE_1)) {
            PDAnnotationFreeText freeText = new PDAnnotationFreeText();
            freeText.setRectangle(new PDRectangle(300, 600, 150, 30));
            freeText.setContents("Нет списка сокращений");
            pdf.annotate(0, freeText);

            PDAnnotationInk ink = new PDAnnotationInk();
            ink.setRectangle(new PDRectangle(100, 500, 50, 50));
            ink.setInkList(new float[][]{{100, 500, 150, 550}});
            pdf.annotate(0, ink);

            PDAnnotationRubberStamp stamp = new PDAnnotationRubberStamp();
            stamp.setRectangle(new PDRectangle(100, 400, 80, 40));
            pdf.annotate(0, stamp);

            PDAnnotationSquare square = new PDAnnotationSquare();
            square.setRectangle(new PDRectangle(100, 300, 80, 40));
            square.setContents("Рисунок без подписи");
            pdf.annotate(0, square);

            PDAnnotationPopup popup = new PDAnnotationPopup();
            popup.setRectangle(new PDRectangle(400, 400, 100, 100));
            pdf.annotate(0, popup);

            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 740, 100, 15));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://bsuir.by");
            link.setAction(uri);
            pdf.annotate(0, link);

            // Отметка статуса рецензирования из Acrobat («Принято») — служебный ответ.
            PDAnnotationText status = note("Accepted", 650);
            status.getCOSObject().setString(COSName.getPDFName("StateModel"), "Review");
            status.getCOSObject().setString(COSName.getPDFName("State"), "Accepted");
            pdf.annotate(0, status);

            assertThat(extract(pdf)).extracting(AnnotationExtractor.Extracted::kind)
                    .containsExactly("FREE_TEXT", "INK", "STAMP", "SHAPE");
        }
    }

    @Test
    @Tag("negative")
    void pdfWithoutAnnotationsGivesEmptyList() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_0, LINE_1)) {
            assertThat(extract(pdf)).isEmpty();
        }
    }

    @Test
    @Tag("typical")
    void wordsComeFromFirstPages() throws IOException {
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(LINE_1).page(LINE_2);
             PDDocument document = Loader.loadPDF(pdf.bytes())) {
            Set<String> firstPage = AnnotationExtractor.words(document, 1);
            Set<String> both = AnnotationExtractor.words(document, 3);

            assertThat(firstPage).contains("system", "docx").doesNotContain("checker");
            assertThat(both).contains("checker");
        }
    }

    private static PDAnnotationText note(String text, float top) {
        PDAnnotationText note = new PDAnnotationText();
        note.setRectangle(new PDRectangle(500, top - 20, 20, 20));
        note.setContents(text);
        return note;
    }

    // Как в жизни: файл сохранён редактором и заново открыт — ссылки «ответ на» идут через объекты PDF.
    private static List<AnnotationExtractor.Extracted> extract(AnnotatedPdf pdf) throws IOException {
        try (PDDocument reloaded = Loader.loadPDF(pdf.bytes())) {
            return AnnotationExtractor.extract(reloaded);
        }
    }
}
