package com.sapsr.backend.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    private static final byte[] CONTENT = "%PDF-1.7 test".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path root;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        Clock october = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneId.of("Europe/Minsk"));
        storage = new LocalFileStorage(root, october);
    }

    @Test
    void storesUnderGeneratedKeyWithYearAndMonth() throws IOException {
        FileStorage.StoredFile stored = storage.store(new ByteArrayInputStream(CONTENT), "pdf");

        assertThat(stored.key()).matches("2026/10/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");
        assertThat(stored.size()).isEqualTo(CONTENT.length);
        assertThat(Files.readAllBytes(root.resolve(stored.key()))).isEqualTo(CONTENT);
    }

    @Test
    void computesSha256OfContent() throws IOException {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);

        FileStorage.StoredFile stored = storage.store(new ByteArrayInputStream(abc), "pdf");

        // Эталонное значение SHA-256 для "abc" из FIPS 180-2.
        assertThat(stored.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void givesEachFileItsOwnKey() throws IOException {
        String first = storage.store(new ByteArrayInputStream(CONTENT), "pdf").key();
        String second = storage.store(new ByteArrayInputStream(CONTENT), "pdf").key();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void removesPartialFileWhenCopyFails() {
        InputStream broken = new InputStream() {
            private int left = 3;
            @Override public int read() throws IOException {
                if (left-- > 0) return 'x';
                throw new IOException("обрыв соединения");
            }
        };

        assertThatThrownBy(() -> storage.store(broken, "pdf")).isInstanceOf(IOException.class);
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void resolvesKeyInsideRoot() {
        assertThat(storage.resolve("2026/10/a.pdf")).isEqualTo(root.toAbsolutePath().normalize().resolve("2026/10/a.pdf"));
        // Работы до перехода на ключи лежат прямо в корне под старыми именами.
        assertThat(storage.resolve("1712345678901_Курсовая работа.pdf").getParent())
                .isEqualTo(root.toAbsolutePath().normalize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.pdf", "2026/../../outside.pdf", ".", "", "  "})
    void rejectsKeysOutsideRoot(String key) {
        assertThatThrownBy(() -> storage.resolve(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsoluteKey() {
        String absolute = root.getRoot().resolve("etc").resolve("passwd").toString();
        assertThatThrownBy(() -> storage.resolve(absolute)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullKey() {
        assertThatThrownBy(() -> storage.resolve(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesStoredFile() throws IOException {
        String key = storage.store(new ByteArrayInputStream(CONTENT), "pdf").key();

        storage.delete(key);

        assertThat(root.resolve(key)).doesNotExist();
    }

    @Test
    void deletingMissingFileIsNotAnError() throws IOException {
        storage.delete("2026/10/missing.pdf");
    }
}
