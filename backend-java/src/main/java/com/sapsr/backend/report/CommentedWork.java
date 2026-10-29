package com.sapsr.backend.report;

import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.entity.WorkFileName;
import com.sapsr.backend.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Файл работы с комментариями автопроверки ({@link CommentedPdf}) — для бота и для скачивания в приложении. */
@Component
public class CommentedWork {

    private static final Logger log = LoggerFactory.getLogger(CommentedWork.class);

    private final FileStorage storage;

    public CommentedWork(FileStorage storage) {
        this.storage = storage;
    }

    public record File(byte[] content, String name) {}

    /** Пусто, если файла работы нет или PDF не удалось прочитать: тогда сообщение уходит без файла. */
    public Optional<File> build(Submission s) {
        if (s.getStorageKey() == null) return Optional.empty();
        try {
            Path path = storage.resolve(s.getStorageKey());
            if (!Files.exists(path)) return Optional.empty();
            return Optional.of(new File(CommentedPdf.build(Files.readAllBytes(path), s.getFormatErrors()), fileName(s)));
        } catch (Exception e) {
            log.warn("Работа #{}: не удалось собрать файл с комментариями: {}", s.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** «КР7_321702_Терешкова — комментарии.pdf»: рядом с самой работой на диске не путается с ней. */
    static String fileName(Submission s) {
        String base = WorkFileName.of(s).orElseGet(() -> {
            String name = s.displayFileName();
            return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        });
        return base + " — комментарии.pdf";
    }
}
