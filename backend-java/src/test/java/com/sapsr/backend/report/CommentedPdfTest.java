package com.sapsr.backend.report;

import com.sapsr.backend.review.AnnotationExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Работа с комментариями автопроверки — api-v1.md, раздел 17. */
class CommentedPdfTest {

    // Замечания как в format_errors: список с местами на первой странице, шрифт на второй (без места),
    // замечание ко всему документу и замечание формата v1 (только страница).
    private static final String FINDINGS = """
            [
              {"id": "f-001", "category": "format", "origin": "symbolic", "severity": "major", "blocking": true,
               "title": "Пунктуация простого списка", "message": "Неправильно оформлен простой список",
               "fix": "Элементы — со строчной буквы и «;», последний — с точкой.",
               "evidence": [{"page": 1, "block_id": "b-0002", "block_end": "b-0003", "areas": [
                 {"page": 1, "bbox": [85.0, 100.0, 500.0, 114.0]}, {"page": 1, "bbox": [85.0, 120.0, 480.0, 134.0]}]}]},
              {"id": "f-002", "category": "format", "origin": "symbolic", "severity": "minor", "blocking": false,
               "message": "Основной шрифт — не Times New Roman", "evidence": [{"page": 2}]},
              {"id": "f-003", "category": "content", "origin": "hybrid", "severity": "major", "blocking": false,
               "needs_teacher_review": true, "message": "Не описаны точки интеграции", "evidence": []},
              {"severity": "warning", "category": "format", "page": "2", "message": "Нет подписи рисунка"}
            ]""";

    @Test
    @Tag("typical")
    void eachFindingBecomesCommentInItsPlace() throws IOException {
        byte[] result = CommentedPdf.build(twoPages(), FINDINGS);

        try (PDDocument doc = Loader.loadPDF(result)) {
            List<PDAnnotation> first = doc.getPage(0).getAnnotations();
            PDAnnotationHighlight list = (PDAnnotationHighlight) first.stream()
                    .filter(a -> a instanceof PDAnnotationHighlight).findFirst().orElseThrow();
            // Весь список выделен одной пометкой: по четырёхугольнику на элемент.
            assertThat(list.getQuadPoints()).hasSize(16);
            assertThat(list.getContents()).startsWith("Неправильно оформлен простой список")
                    .contains("Как исправить: Элементы — со строчной буквы");
            assertThat(list.getTitlePopup()).isEqualTo("SAPSR");
            assertThat(list.getSubject()).isEqualTo("Пунктуация простого списка");
            // Отчёт считает сверху, PDF — снизу: верх первого элемента на высоте 842 − 100.
            assertThat(list.getQuadPoints()[1]).isBetween(742f, 744f);
            assertThat(list.getAppearance()).isNotNull();
            assertThat(first).anyMatch(a -> a instanceof PDAnnotationPopup);
            // Замечание ко всему документу — заметкой на первой странице.
            assertThat(first).filteredOn(a -> a instanceof PDAnnotationText)
                    .singleElement().extracting(PDAnnotation::getContents).asString()
                    .contains("Не описаны точки интеграции").contains("нужна проверка преподавателя");

            List<PDAnnotation> second = doc.getPage(1).getAnnotations();
            assertThat(second).filteredOn(a -> a instanceof PDAnnotationText)
                    .extracting(PDAnnotation::getContents)
                    .containsExactly("Основной шрифт — не Times New Roman", "Нет подписи рисунка");

            // Свои же комментарии читаются, как пометки преподавателя: файл можно дополнить и вернуть студенту.
            assertThat(AnnotationExtractor.extract(doc)).hasSize(4)
                    .allSatisfy(c -> assertThat(c.author()).isEqualTo("SAPSR"));
        }
    }

    @Test
    @Tag("boundary")
    void placesOutsideFileAreSkipped() throws IOException {
        String outside = """
                [{"message": "Поле", "evidence": [{"page": 9, "areas": [{"page": 9, "bbox": [1, 2, 3, 4]}]},
                                                  {"page": 1, "areas": [{"page": 1, "bbox": [50, 60, 40, 70]}]}]}]""";
        try (PDDocument doc = Loader.loadPDF(CommentedPdf.build(twoPages(), outside))) {
            // Ни одного годного места — заметка на первой странице, а не исключение.
            assertThat(doc.getPage(0).getAnnotations()).filteredOn(a -> a instanceof PDAnnotationText).hasSize(1);
            assertThat(doc.getPage(1).getAnnotations()).isEmpty();
        }
        try (PDDocument doc = Loader.loadPDF(CommentedPdf.build(twoPages(), null))) {
            assertThat(doc.getPage(0).getAnnotations()).isEmpty();
        }
    }

    private static byte[] twoPages() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.addPage(new PDPage(PDRectangle.A4));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
