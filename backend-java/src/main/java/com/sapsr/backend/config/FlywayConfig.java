package com.sapsr.backend.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class FlywayConfig {

    /**
     * Базы, созданные до Flyway старым init.sql, принимаются как версия 1 (V1__baseline.sql повторяет ту схему),
     * и к ним применяются только следующие миграции. На пустой базе выполняются все миграции с V1.
     *
     * Задано в коде, а не только в application.properties.example: у каждого участника свой
     * application.properties, и без этой настройки бэкенд не запустится на уже существующей базе.
     * Явно заданное значение spring.flyway.baseline-on-migrate имеет приоритет.
     */
    @Bean
    public FlywayConfigurationCustomizer baselineExistingDatabase(Environment environment) {
        return configuration -> {
            if (!environment.containsProperty("spring.flyway.baseline-on-migrate")) {
                configuration.baselineOnMigrate(true);
            }
        };
    }
}
