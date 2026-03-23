package com.sapsr.backend.config;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    @Value("${sapsr.rabbitmq.results-queue}")
    private String resultsQueue;

    @Bean
    public Queue pdfTasksQueue() {
        return new Queue(tasksQueue, true);
    }

    @Bean
    public Queue pdfResultsQueue() {
        return new Queue(resultsQueue, true);
    }
}