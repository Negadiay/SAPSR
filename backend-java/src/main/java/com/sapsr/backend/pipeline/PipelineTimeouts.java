package com.sapsr.backend.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Тайм-ауты проверки (решение 5; queue-v2.md, раздел 6).
 *
 * @param content сколько ждать содержательную часть после фазы format; потом работа уходит преподавателю
 *                с отчётом по оформлению
 * @param stuck   сколько ждать фазу format с начала проверки; потом работа получает FAILED
 */
@Component
public record PipelineTimeouts(Duration content, Duration stuck) {

    @Autowired
    public PipelineTimeouts(@Value("${sapsr.pipeline.content-timeout:5m}") String content,
                            @Value("${sapsr.pipeline.stuck-timeout:15m}") String stuck) {
        this(DurationStyle.detectAndParse(content), DurationStyle.detectAndParse(stuck));
    }
}
