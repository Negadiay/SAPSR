package com.sapsr.backend.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    @Value("${sapsr.rabbitmq.results-queue}")
    private String resultsQueue;

    @Value("${sapsr.rabbitmq.progress-queue:pdf_progress_queue}")
    private String progressQueue;

    @Value("${sapsr.rabbitmq.status-queue:worker_status_queue}")
    private String statusQueue;

    @Bean
    public Queue pdfTasksQueue() {
        return new Queue(tasksQueue, true);
    }

    @Bean
    public Queue pdfResultsQueue() {
        return new Queue(resultsQueue, true);
    }

    /** Ход проверки (queue-v2.md, раздел 4). Объявляем сами: обработчик с протоколом 1 её не создаёт. */
    @Bean
    public Queue pdfProgressQueue() {
        return new Queue(progressQueue, true);
    }

    /** Состояние обработчика и его моделей для «Красной зоны» (queue-v2.md, раздел 10). */
    @Bean
    public Queue workerStatusQueue() {
        return new Queue(statusQueue, true);
    }

    /**
     * Исходящие сообщения — классы-записи, сериализуемые Jackson (content_type = application/json).
     * Конвертер ставится только на RabbitTemplate, а не общим бином: слушатель результатов получает
     * от обработчика сообщения без content_type и читает их строкой, общий JSON-конвертер это сломал бы.
     */
    @Bean
    public RabbitTemplateCustomizer jsonOutgoingMessages() {
        return template -> template.setMessageConverter(new JacksonJsonMessageConverter());
    }
}
