-- Базовая схема: ровно то, что раньше создавал init.sql вместе с дописанными ALTER TABLE.
-- На уже существующей базе эта миграция не выполняется: Flyway отмечает её как базовую
-- (spring.flyway.baseline-on-migrate=true) и применяет только следующие.

CREATE TABLE users (
    telegram_id    BIGINT PRIMARY KEY,
    role           VARCHAR(50) NOT NULL,
    full_name      VARCHAR(255),
    email          VARCHAR(255),
    url_id         VARCHAR(100),
    teacher_groups TEXT
);

CREATE TABLE submissions (
    id                  SERIAL PRIMARY KEY,
    student_telegram_id BIGINT REFERENCES users (telegram_id),
    teacher_telegram_id BIGINT REFERENCES users (telegram_id),
    status              VARCHAR(50) NOT NULL,
    file_path           VARCHAR(500),
    format_errors       JSONB,
    teacher_verdict     VARCHAR(50),
    teacher_comment     TEXT,
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    teacher_message_id  INTEGER
);

CREATE TABLE email_verifications (
    id         SERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    code       VARCHAR(6) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used       BOOLEAN DEFAULT FALSE
);
