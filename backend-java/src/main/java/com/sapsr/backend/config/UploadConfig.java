package com.sapsr.backend.config;

import com.sapsr.backend.controller.ApiErrors;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Предел размера загружаемого файла — одна настройка {@code sapsr.upload.max-size-mb} (решение 15):
 * от неё считаются и пределы Spring, и проверки в контроллерах, и поле {@code max_upload_mb} в {@code GET /me}.
 * Без этого класса Spring принимает только 1 МБ.
 */
@Configuration
@RestControllerAdvice
public class UploadConfig {

    private final int maxMb;

    public UploadConfig(@Value("${sapsr.upload.max-size-mb:50}") int maxMb) {
        this.maxMb = maxMb;
    }

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        // Запрос — файл и служебные поля формы: предел запроса чуть больше, чтобы файл ровно в предел проходил
        // до контроллера и получал понятный ответ, а не обрыв соединения.
        factory.setMaxFileSize(DataSize.ofMegabytes(maxMb + 1L));
        factory.setMaxRequestSize(DataSize.ofMegabytes(maxMb + 2L));
        return factory.createMultipartConfig();
    }

    /** Файл больше предела Spring до контроллера не доходит — ответ тот же, что у проверки в контроллере. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge() {
        return ApiErrors.fileTooLarge(maxMb);
    }
}
