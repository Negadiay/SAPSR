package com.sapsr.backend.review;

import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.repository.AnnotationRepository;
import com.sapsr.backend.repository.ReviewFileRepository;
import com.sapsr.backend.storage.FileStorage;
import com.sapsr.backend.storage.LocalFileStorage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewFileServiceTest {

    private static final String[] WORK = {"1.1 Requirements", "The system accepts files in PDF and DOCX formats."};

    @TempDir Path root;

    private final ReviewFileRepository reviewFiles = mock(ReviewFileRepository.class);
    private final AnnotationRepository annotations = mock(AnnotationRepository.class);
    private final AtomicInteger ids = new AtomicInteger(100);
    private FileStorage storage;
    private ReviewFileService service;
    private Submission submission;

    @BeforeEach
    void setUp() throws IOException {
        storage = new LocalFileStorage(root, Clock.systemDefaultZone());
        service = new ReviewFileService(reviewFiles, annotations, storage);
        when(reviewFiles.findBySubmissionId(any())).thenReturn(Optional.empty());
        when(reviewFiles.save(any(ReviewFile.class))).thenAnswer(call -> {
            ReviewFile file = call.getArgument(0);
            file.setId(7);
            return file;
        });
        when(annotations.save(any(Annotation.class))).thenAnswer(call -> {
            Annotation a = call.getArgument(0);
            if (a.getId() == null) a.setId(ids.incrementAndGet());
            return a;
        });

        // Работа студента лежит в хранилище, как после загрузки.
        submission = new Submission();
        submission.setId(153);
        try (AnnotatedPdf work = new AnnotatedPdf().page(WORK)) {
            submission.setStorageKey(storage.store(new ByteArrayInputStream(work.bytes()), "pdf").key());
        }
    }

    @Test
    @Tag("typical")
    void savesCommentsWithReplyLinksAndNoWarningsForSameDocument() throws Exception {
        byte[] reviewed;
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(WORK)) {
            PDAnnotationHighlight highlight = new PDAnnotationHighlight();
            float[] quad = AnnotatedPdf.quad(1, WORK[1], "PDF and DOCX");
            highlight.setQuadPoints(quad);
            highlight.setRectangle(AnnotatedPdf.bounds(quad));
            highlight.setContents("Сверьте с разделом 1.5");
            pdf.annotate(0, highlight);
            PDAnnotationText reply = new PDAnnotationText();
            reply.setRectangle(new PDRectangle(500, 700, 20, 20));
            reply.setContents("И с заключением");
            reply.setInReplyTo(highlight);
            pdf.annotate(0, reply);
            reviewed = pdf.bytes();
        }

        ReviewFileService.Attached attached = service.attach(submission, reviewed, "Курсовая.pdf");

        assertThat(attached.warnings()).isEmpty();
        assertThat(attached.file().getSubmissionId()).isEqualTo(153);
        assertThat(attached.annotations()).hasSize(2);
        Annotation first = attached.annotations().get(0);
        Annotation second = attached.annotations().get(1);
        assertThat(first.getQuotedText()).isEqualTo("PDF and DOCX");
        assertThat(first.getRect()).startsWith("[");
        assertThat(second.getReplyToId()).isEqualTo(first.getId());
        assertThat(second.getPosition()).isEqualTo(1);
        assertThat(storage.resolve(attached.file().getStorageKey())).exists();
    }

    @Test
    @Tag("boundary")
    void fileSavedThroughPrintGetsNoAnnotationsWarning() throws Exception {
        byte[] printed;
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(WORK)) {
            printed = pdf.bytes();
        }

        ReviewFileService.Attached attached = service.attach(submission, printed, "Курсовая.pdf");

        assertThat(attached.warnings()).extracting(ReviewFileService.Warning::code).containsExactly("NO_ANNOTATIONS");
        assertThat(attached.annotations()).isEmpty();
        verify(reviewFiles).save(any());
    }

    @Test
    @Tag("boundary")
    void otherDocumentGetsWarningButIsAccepted() throws Exception {
        byte[] other;
        try (AnnotatedPdf pdf = new AnnotatedPdf().page("Completely different text").page("and one more page")) {
            PDAnnotationText note = new PDAnnotationText();
            note.setRectangle(new PDRectangle(500, 700, 20, 20));
            note.setContents("Комментарий");
            pdf.annotate(0, note);
            other = pdf.bytes();
        }

        ReviewFileService.Attached attached = service.attach(submission, other, "чужой.pdf");

        assertThat(attached.warnings()).extracting(ReviewFileService.Warning::code).containsExactly("DIFFERENT_DOCUMENT");
        assertThat(attached.annotations()).hasSize(1);
    }

    @Test
    @Tag("negative")
    void notAPdfIsRejectedWithoutStoringAnything() throws IOException {
        long before = countFiles();

        assertThatThrownBy(() -> service.attach(submission, "просто текст".getBytes(StandardCharsets.UTF_8), "a.pdf"))
                .isInstanceOf(ReviewFileService.NotAPdfException.class);
        assertThatThrownBy(() -> service.attach(submission, "%PDF-1.7 но дальше мусор".getBytes(StandardCharsets.UTF_8), "a.pdf"))
                .isInstanceOf(ReviewFileService.NotAPdfException.class);

        assertThat(countFiles()).isEqualTo(before);
        verify(reviewFiles, never()).save(any());
    }

    @Test
    @Tag("typical")
    void secondUploadReplacesPreviousFile() throws Exception {
        Path oldFile = storage.resolve(storage.store(new ByteArrayInputStream(new byte[]{1}), "pdf").key());
        ReviewFile previous = new ReviewFile();
        previous.setId(5);
        previous.setSubmissionId(153);
        previous.setStorageKey(root.relativize(oldFile).toString().replace('\\', '/'));
        when(reviewFiles.findBySubmissionId(153)).thenReturn(Optional.of(previous));

        byte[] reviewed;
        try (AnnotatedPdf pdf = new AnnotatedPdf().page(WORK)) {
            reviewed = pdf.bytes();
        }
        service.attach(submission, reviewed, "Курсовая.pdf");

        verify(reviewFiles).delete(previous);
        assertThat(oldFile).doesNotExist();
    }

    private long countFiles() throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
