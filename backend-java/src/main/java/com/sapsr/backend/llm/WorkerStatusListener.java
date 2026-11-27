package com.sapsr.backend.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

/** Состояние обработчика из worker_status_queue (queue-v2.md, раздел 10) — для «Красной зоны» администратора. */
@Service
public class WorkerStatusListener {

    private static final Logger log = LoggerFactory.getLogger(WorkerStatusListener.class);

    private final LlmModels models;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkerStatusListener(LlmModels models) {
        this.models = models;
    }

    @RabbitListener(queues = "${sapsr.rabbitmq.status-queue:worker_status_queue}")
    public void handleStatus(String message) {
        try {
            models.onStatus(objectMapper.readTree(message));
        } catch (Exception e) {
            log.warn("Состояние обработчика не разобрано: {}", e.getMessage());
        }
    }
}
