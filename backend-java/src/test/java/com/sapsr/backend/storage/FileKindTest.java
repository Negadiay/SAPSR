package com.sapsr.backend.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Вид файла определяется по содержимому, а не по имени (решение 3). */
public class FileKindTest {

    public static byte[] zip(String... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<xml/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    @Tag("typical")
    void pdfIsRecognisedBySignature() {
        assertThat(FileKind.of("%PDF-1.7 работа".getBytes(StandardCharsets.UTF_8))).isEqualTo(FileKind.PDF);
    }

    @Test
    @Tag("typical")
    void docxIsZipWithMainDocumentPart() throws IOException {
        byte[] docx = zip("[Content_Types].xml", "word/document.xml", "word/styles.xml");
        assertThat(FileKind.of(docx)).isEqualTo(FileKind.DOCX);
        assertThat(FileKind.DOCX.extension()).isEqualTo("docx");
    }

    @Test
    @Tag("negative")
    void zipWithoutWordPartIsNotDocx() throws IOException {
        assertThat(FileKind.of(zip("xl/workbook.xml"))).isEqualTo(FileKind.UNKNOWN);
    }

    @Test
    @Tag("negative")
    void renamedExecutableIsRejected() {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
        assertThat(FileKind.of(exe)).isEqualTo(FileKind.UNKNOWN);
    }

    @Test
    @Tag("boundary")
    void emptyTruncatedAndBrokenFiles() {
        assertThat(FileKind.of(new byte[0])).isEqualTo(FileKind.UNKNOWN);
        assertThat(FileKind.of(null)).isEqualTo(FileKind.UNKNOWN);
        assertThat(FileKind.of("%PD".getBytes(StandardCharsets.US_ASCII))).isEqualTo(FileKind.UNKNOWN);
        assertThat(FileKind.of(new byte[] {'P', 'K', 3, 4, 1, 2, 3})).isEqualTo(FileKind.UNKNOWN);
    }
}
