package com.sapsr.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.pipeline.Notice;
import com.sapsr.backend.pipeline.NoticeSender;
import com.sapsr.backend.pipeline.PipelineState;
import com.sapsr.backend.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Сообщения обработчика: результаты из pdf_results_queue (v1 — одним сообщением, v2 — фазами format, content,
 * failed) и ход проверки из pdf_progress_queue. Протокол — docs/contracts/queue-v2.md, разделы 4–6.
 * Бэкенд принимает обе версии результата, поэтому обработчик переключается настройкой SAPSR_QUEUE_PROTOCOL.
 */
@Service
public class ResultListenerService {

    private static final Logger log = LoggerFactory.getLogger(ResultListenerService.class);
    /** Этапы для индикатора (queue-v2.md, раздел 4); прочие значения в базу не пишем. */
    static final Set<String> STAGES = Set.of("extract", "structure", "format", "claims", "reasoning", "report");

    private final PipelineState state;
    private final NoticeSender sender;
    private final SubmissionRepository submissions;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ResultListenerService(PipelineState state, NoticeSender sender, SubmissionRepository submissions) {
        this(state, sender, submissions, Clock.systemDefaultZone());
    }

    ResultListenerService(PipelineState state, NoticeSender sender, SubmissionRepository submissions, Clock clock) {
        this.state = state;
        this.sender = sender;
        this.submissions = submissions;
        this.clock = clock;
    }

    @RabbitListener(queues = "${sapsr.rabbitmq.results-queue}")
    public void handleResult(String message) {
        JsonNode json = parse(message);
        if (json == null || !json.path("task_id").canConvertToInt()) {
            log.warn("Результат без task_id пропущен");
            return;
        }
        int taskId = json.get("task_id").asInt();
        List<Notice> notices;
        if (json.has("phase")) {
            String phase = json.path("phase").asText("");
            log.info("Результат: task_id={}, фаза {}, trace_id={}", taskId, phase, json.path("trace_id").asText(""));
            notices = switch (phase) {
                case "format" -> state.onFormat(taskId, json.path("passed").asBoolean(false),
                        json.path("final").asBoolean(false), json.path("report"));
                case "content" -> state.onContent(taskId, json.path("passed").asBoolean(false), json.path("report"));
                case "failed" -> state.onFailed(taskId, json.path("error").path("code").asText("INTERNAL"),
                        json.path("error").path("message").asText("Проверка не удалась."));
                default -> {
                    log.warn("Неизвестная фаза {} у task_id={} — пропущено", phase, taskId);
                    yield List.of();
                }
            };
        } else {
            String status = json.path("status").asText("");
            String errors = json.has("errors") ? json.get("errors").toString() : "[]";
            log.info("Результат v1: task_id={}, статус {}", taskId, status);
            notices = state.onResultV1(taskId, "SUCCESS".equals(status), errors);
        }
        sender.send(notices);
    }

    @RabbitListener(queues = "${sapsr.rabbitmq.progress-queue:pdf_progress_queue}")
    public void handleProgress(String message) {
        JsonNode json = parse(message);
        if (json == null || !json.path("task_id").canConvertToInt()) return;
        String stage = json.path("stage").asText("");
        if (!STAGES.contains(stage)) {
            log.warn("Неизвестный этап {} у task_id={} — пропущено", stage, json.get("task_id").asInt());
            return;
        }
        submissions.recordProgress(json.get("task_id").asInt(), stage, LocalDateTime.now(clock));
    }

    private JsonNode parse(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            return json != null && json.isObject() ? json : null;
        } catch (Exception e) {
            log.warn("Сообщение обработчика не разобрано: {}", e.getMessage());
            return null;
        }
    }
}
