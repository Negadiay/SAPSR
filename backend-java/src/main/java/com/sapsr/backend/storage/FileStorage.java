package com.sapsr.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Хранилище файлов работ. Файл сохраняется под сгенерированным ключом, исходное имя хранится только в базе.
 * Ключ — путь относительно корня хранилища; обработчик знает тот же корень (docs/contracts/queue-v2.md, раздел 3).
 */
public interface FileStorage {

    /** Сохраняет содержимое под новым ключом вида {@code 2026/10/<UUID>.pdf}. */
    StoredFile store(InputStream content, String extension) throws IOException;

    /**
     * Абсолютный путь к файлу по ключу.
     *
     * @throws IllegalArgumentException если ключ пустой или указывает за пределы хранилища
     */
    Path resolve(String key);

    /** Удаляет файл, если он есть. */
    void delete(String key) throws IOException;

    record StoredFile(String key, String sha256, long size) {}
}
