package com.sapsr.backend.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Клиент конвертера DOCX → PDF на поддельном сервере Gotenberg. */
class DocxConverterTest {

    private static final byte[] PDF = "%PDF-1.7 версия".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile byte[] answer = PDF;
    private volatile long delayMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forms/libreoffice/convert", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DocxConverter converter(Duration timeout) {
        return new DocxConverter("http://127.0.0.1:" + server.getAddress().getPort() + "/", timeout);
    }

    @Test
    @Tag("typical")
    void sendsDocxAsMultipartAndReturnsPdf() throws Exception {
        byte[] pdf = converter(Duration.ofSeconds(5)).toPdf("PK-docx-содержимое".getBytes(StandardCharsets.UTF_8));

        assertThat(pdf).isEqualTo(PDF);
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(requestBody.get()).contains("name=\"files\"; filename=\"work.docx\"").contains("PK-docx-");
    }

    @Test
    @Tag("negative")
    void converterErrorIsReported() {
        status = 500;
        answer = "LibreOffice failed".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> converter(Duration.ofSeconds(5)).toPdf(new byte[] {1}))
                .isInstanceOf(DocxConverter.ConversionException.class).hasMessageContaining("500");
    }

    @Test
    @Tag("negative")
    void answerThatIsNotPdfIsRejected() {
        answer = "<html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> converter(Duration.ofSeconds(5)).toPdf(new byte[] {1}))
                .isInstanceOf(DocxConverter.ConversionException.class).hasMessageContaining("не PDF");
    }

    @Test
    @Tag("boundary")
    void timeoutIsConversionFailure() {
        delayMs = 1500;

        assertThatThrownBy(() -> converter(Duration.ofMillis(300)).toPdf(new byte[] {1}))
                .isInstanceOf(DocxConverter.ConversionException.class);
    }

    @Test
    @Tag("negative")
    void unreachableConverterIsConversionFailure() {
        DocxConverter converter = new DocxConverter("http://127.0.0.1:1", Duration.ofSeconds(2));

        assertThatThrownBy(() -> converter.toPdf(new byte[] {1}))
                .isInstanceOf(DocxConverter.ConversionException.class);
    }
}
