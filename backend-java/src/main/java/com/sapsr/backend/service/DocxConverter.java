package com.sapsr.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * DOCX → PDF через Gotenberg (LibreOffice в отдельном контейнере без выхода в интернет; решение 3, правка 15.11.2026).
 * Запрос — {@code POST /forms/libreoffice/convert}, multipart с полем {@code files}; ответ — PDF.
 */
@Component
public class DocxConverter {

    /** Конвертер не ответил, ответил ошибкой или вернул не PDF. Работа при этом не создаётся. */
    public static class ConversionException extends Exception {
        public ConversionException(String message, Throwable cause) { super(message, cause); }
    }

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    @Autowired
    public DocxConverter(@Value("${sapsr.converter.url:http://gotenberg:3000}") String baseUrl,
                         @Value("${sapsr.converter.timeout:90s}") Duration timeout) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/forms/libreoffice/convert");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public byte[] toPdf(byte[] docx) throws ConversionException {
        String boundary = "sapsr-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, docx)))
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] pdf = response.body();
            if (response.statusCode() != 200) {
                throw new ConversionException("конвертер ответил " + response.statusCode(), null);
            }
            if (pdf == null || pdf.length < PDF_MAGIC.length || !startsWithPdfMagic(pdf)) {
                throw new ConversionException("конвертер вернул не PDF", null);
            }
            return pdf;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException("преобразование прервано", e);
        } catch (IOException e) { // в том числе тайм-аут
            throw new ConversionException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static boolean startsWithPdfMagic(byte[] pdf) {
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (pdf[i] != PDF_MAGIC[i]) return false;
        }
        return true;
    }

    // Имя файла в запросе — постоянное: имя от студента конвертеру не нужно и в его журнал не попадает.
    private static byte[] multipart(String boundary, byte[] docx) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(docx.length + 300);
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files\"; filename=\"work.docx\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        body.write(head, 0, head.length);
        body.write(docx, 0, docx.length);
        body.write(tail, 0, tail.length);
        return body.toByteArray();
    }
}
