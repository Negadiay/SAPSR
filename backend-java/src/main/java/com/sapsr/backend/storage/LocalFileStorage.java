package com.sapsr.backend.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

/** Хранилище на локальном диске: в Docker это общий том бэкенда и обработчика. */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;
    private final Clock clock;

    @Autowired
    public LocalFileStorage(@Value("${sapsr.storage.root:../storage}") String root) {
        this(Paths.get(root), Clock.systemDefaultZone());
    }

    public LocalFileStorage(Path root, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    @Override
    public StoredFile store(InputStream content, String extension) throws IOException {
        LocalDate today = LocalDate.now(clock);
        String key = String.format("%d/%02d/%s.%s", today.getYear(), today.getMonthValue(), UUID.randomUUID(), extension);
        Path target = resolve(key);
        Files.createDirectories(target.getParent());

        MessageDigest sha256 = newSha256();
        try (DigestInputStream in = new DigestInputStream(content, sha256)) {
            long size = Files.copy(in, target);
            return new StoredFile(key, HexFormat.of().formatHex(sha256.digest()), size);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    @Override
    public Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Пустой ключ файла");
        }
        Path relative = Paths.get(key);
        Path path = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Ключ файла указывает за пределы хранилища: " + key);
        }
        return path;
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
