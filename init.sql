CREATE TABLE users (
                       telegram_id BIGINT PRIMARY KEY,
                       role VARCHAR(50) NOT NULL,
                       full_name VARCHAR(255)
);

CREATE TABLE submissions (
                             id SERIAL PRIMARY KEY,
                             student_telegram_id BIGINT REFERENCES users(telegram_id),
                             status VARCHAR(50) NOT NULL,
                             file_path VARCHAR(500),
                             format_errors JSONB,
                             created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);