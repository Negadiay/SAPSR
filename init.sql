CREATE TABLE users (
    telegram_id BIGINT PRIMARY KEY,
    role VARCHAR(50) NOT NULL,
    full_name VARCHAR(255),
    email VARCHAR(255)
);

CREATE TABLE submissions (
    id SERIAL PRIMARY KEY,
    student_telegram_id BIGINT REFERENCES users(telegram_id),
    teacher_telegram_id BIGINT REFERENCES users(telegram_id),
    status VARCHAR(50) NOT NULL,
    file_path VARCHAR(500),
    format_errors JSONB,
    score INTEGER,
    teacher_verdict VARCHAR(50),
    teacher_comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE email_verifications (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    code VARCHAR(6) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE
);
