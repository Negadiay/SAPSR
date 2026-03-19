package com.sapsr.backend.config;

import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration // Говорим Spring, что это файл настроек
public class RabbitMQConfig {

    // Читаем название очереди из application.properties
    @Value("${sapsr.rabbitmq.tasks-queue}")
    private String tasksQueue;

    // Этот метод автоматически создаст очередь в RabbitMQ при старте сервера
    @Bean
    public Queue pdfTasksQueue() {
        return new Queue(tasksQueue, true); // true = очередь будет сохраняться при перезагрузке
    }
}