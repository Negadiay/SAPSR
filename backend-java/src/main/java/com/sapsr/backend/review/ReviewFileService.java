package com.sapsr.backend.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.entity.Annotation;
import com.sapsr.backend.entity.ReviewFile;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.messaging.TaskMessage;
import com.sapsr.backend.repository.AnnotationRepository;
import com.sapsr.backend.repository.ReviewFileRepository;
import com.sapsr.backend.storage.FileStorage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Размеченный преподавателем PDF (docs/contracts/api-v1.md, разделы 8–11): приём, разбор комментариев,
 * хранение рядом с работами. У работы один такой файл — повторная загрузка заменяет прежний.
 */
@Service
public class ReviewFileService {

    /** Сколько первых страниц сравнивать с работой студента. */
    private static final int COMPARED_PAGES = 3;
    /** Доля общих слов, ниже которой файл считается другим документом. */
    private static final double SAME_DOCUMENT_SIMILARITY = 0.6;

    private final ReviewFileRepository reviewFiles;
    private final AnnotationRepository annotations;
    private final FileStorage fileStorage;
    private final ObjectMapper json = new ObjectMapper();

    public ReviewFileService(ReviewFileRepository reviewFiles, AnnotationRepository annotations, FileStorage fileStorage) {
        this.reviewFiles = reviewFiles;
        this.annotations = annotations;
        this.fileStorage = fileStorage;
    }

    /** Файл не открывается как PDF. */
    public static class NotAPdfException extends Exception {
        public NotAPdfException(String message, Throwable cause) { super(message, cause); }
    }

    public record Warning(String code, String message) {}

    public record Attached(ReviewFile file, List<Annotation> annotations, List<Warning> warnings) {}

    @Transactional
    public Attached attach(Submission submission, byte[] content, String originalFileName)
            throws IOException, NotAPdfException {
        if (!startsWithPdfHeader(content)) {
            throw new NotAPdfException("Файл не PDF", null);
        }
        List<AnnotationExtractor.Extracted> extracted;
        List<Warning> warnings = new ArrayList<>();
        try (PDDocument reviewed = Loader.loadPDF(content)) {
            extracted = AnnotationExtractor.extract(reviewed);
            if (extracted.isEmpty()) {
                warnings.add(new Warning("NO_ANNOTATIONS", "Комментариев не найдено — возможно, файл сохранён "
                        + "через печать в PDF, и пометки стали частью страницы. Файл всё равно приложен."));
            }
            if (!sameDocument(submission, reviewed)) {
                warnings.add(new Warning("DIFFERENT_DOCUMENT", "Похоже, загружен другой файл: "
                        + "он не совпадает с работой студента по числу страниц или тексту."));
            }
        } catch (IOException e) {
            throw new NotAPdfException("Не удалось открыть PDF", e);
        }

        reviewFiles.findBySubmissionId(submission.getId()).ifPresent(previous -> {
            reviewFiles.delete(previous);
            reviewFiles.flush();
            deleteQuietly(previous.getStorageKey());
        });

        FileStorage.StoredFile stored = fileStorage.store(new ByteArrayInputStream(content), TaskMessage.SOURCE_FORMAT_PDF);
        ReviewFile file = new ReviewFile();
        file.setSubmissionId(submission.getId());
        file.setStorageKey(stored.key());
        file.setFileSha256(stored.sha256());
        file.setOriginalFileName(originalFileName);
        file.setUploadedAt(LocalDateTime.now());
        file.setWarnings(json.writeValueAsString(warnings));
        file = reviewFiles.save(file);

        // Сначала сохраняем все комментарии, чтобы знать их идентификаторы, затем — связи «ответ на».
        List<Annotation> saved = new ArrayList<>();
        for (int i = 0; i < extracted.size(); i++) {
            AnnotationExtractor.Extracted e = extracted.get(i);
            Annotation a = new Annotation();
            a.setReviewFileId(file.getId());
            a.setPosition(i);
            a.setPage(e.page());
            a.setKind(e.kind());
            a.setAuthor(truncate(e.author(), 255));
            a.setCreatedAt(e.createdAt());
            a.setComment(e.comment());
            a.setQuotedText(e.quotedText());
            a.setRect(e.rect() == null ? null : json.writeValueAsString(e.rect()));
            saved.add(annotations.save(a));
        }
        for (int i = 0; i < extracted.size(); i++) {
            Integer replyTo = extracted.get(i).replyTo();
            if (replyTo != null) {
                saved.get(i).setReplyToId(saved.get(replyTo).getId());
                annotations.save(saved.get(i));
            }
        }
        return new Attached(file, saved, warnings);
    }

    public Optional<ReviewFile> find(Integer submissionId) {
        return reviewFiles.findBySubmissionId(submissionId);
    }

    public List<Annotation> annotationsOf(ReviewFile file) {
        return annotations.findByReviewFileIdOrderByPositionAsc(file.getId());
    }

    public long countAnnotations(Integer submissionId) {
        return reviewFiles.findBySubmissionId(submissionId)
                .map(f -> annotations.countByReviewFileId(f.getId())).orElse(0L);
    }

    public List<Warning> warningsOf(ReviewFile file) {
        try {
            return file.getWarnings() == null ? List.of()
                    : List.of(json.readValue(file.getWarnings(), Warning[].class));
        } catch (IOException e) {
            return List.of();
        }
    }

    public Path pathOf(ReviewFile file) {
        return fileStorage.resolve(file.getStorageKey());
    }

    /** Файл и запись удаляются вместе с работой — при её отзыве (решение 7). */
    @Transactional
    public void deleteFor(Integer submissionId) {
        reviewFiles.findBySubmissionId(submissionId).ifPresent(file -> {
            reviewFiles.delete(file);
            deleteQuietly(file.getStorageKey());
        });
    }

    // Тот же документ: столько же страниц и в основном те же слова на первых страницах.
    // Аннотации текст страницы не меняют, поэтому у настоящего размеченного файла совпадение почти полное.
    private boolean sameDocument(Submission submission, PDDocument reviewed) throws IOException {
        if (submission.getStorageKey() == null) return true;
        Path original = fileStorage.resolve(submission.getStorageKey());
        if (!Files.isRegularFile(original)) return true;
        try (PDDocument source = Loader.loadPDF(original.toFile())) {
            if (source.getNumberOfPages() != reviewed.getNumberOfPages()) return false;
            Set<String> a = AnnotationExtractor.words(source, COMPARED_PAGES);
            Set<String> b = AnnotationExtractor.words(reviewed, COMPARED_PAGES);
            if (a.isEmpty() && b.isEmpty()) return true;
            Set<String> common = new HashSet<>(a);
            common.retainAll(b);
            Set<String> all = new HashSet<>(a);
            all.addAll(b);
            return (double) common.size() / all.size() >= SAME_DOCUMENT_SIMILARITY;
        } catch (IOException e) {
            // Исходник не читается — сравнить не с чем, предупреждать не о чем.
            return true;
        }
    }

    private static boolean startsWithPdfHeader(byte[] content) {
        if (content == null || content.length < 5) return false;
        // По стандарту заголовок %PDF- может стоять не в самом начале, но в первом килобайте.
        String head = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.ISO_8859_1);
        return head.contains("%PDF-");
    }

    private void deleteQuietly(String key) {
        try {
            fileStorage.delete(key);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("[REVIEW] Не удалось удалить файл " + key + ": " + e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
