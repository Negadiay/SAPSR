package com.sapsr.backend.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Вид загруженного файла — по содержимому, а не по имени и не по типу от браузера (решение 3):
 * PDF начинается с «%PDF-», DOCX — архив ZIP с частью {@code word/document.xml}.
 */
public enum FileKind {
    PDF("pdf"), DOCX("docx"), UNKNOWN(null);

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final String DOCX_MAIN_PART = "word/document.xml";
    // Основная часть документа — среди первых записей архива; дальше не читаем (архив может быть «бомбой»).
    private static final int MAX_ENTRIES = 2000;

    private final String extension;

    FileKind(String extension) {
        this.extension = extension;
    }

    /** Расширение в хранилище и значение {@code source_format}. */
    public String extension() {
        return extension;
    }

    public static FileKind of(byte[] content) {
        if (startsWith(content, PDF_MAGIC)) return PDF;
        if (startsWith(content, ZIP_MAGIC) && hasEntry(content, DOCX_MAIN_PART)) return DOCX;
        return UNKNOWN;
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content == null || content.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) return false;
        }
        return true;
    }

    private static boolean hasEntry(byte[] zip, String name) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            for (int i = 0; i < MAX_ENTRIES && (entry = in.getNextEntry()) != null; i++) {
                if (name.equals(entry.getName())) return true;
            }
            return false;
        } catch (IOException | IllegalArgumentException e) {
            return false; // повреждённый архив — не DOCX
        }
    }
}
