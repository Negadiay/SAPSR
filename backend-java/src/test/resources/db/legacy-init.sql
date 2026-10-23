-- Как создавались базы до Flyway: прежний init.sql (до 18.10.2026) без строки с реальным преподавателем,
-- плюс вымышленные записи в старом формате. Нужен LegacyDatabaseMigrationTest.
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
    teacher_verdict VARCHAR(50),
    teacher_comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


ALTER TABLE submissions ADD COLUMN IF NOT EXISTS teacher_message_id INTEGER;
ALTER TABLE submissions ALTER COLUMN teacher_comment TYPE TEXT;

ALTER TABLE users ADD COLUMN IF NOT EXISTS url_id VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS teacher_groups TEXT;

CREATE TABLE email_verifications (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    code VARCHAR(6) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE
);

-- Вымышленные записи в старом формате.
INSERT INTO users (telegram_id, role, full_name) VALUES
    (1001, 'STUDENT', 'Иванов И.И. (гр. 321702)'),
    (1002, 'STUDENT', 'Ёлкина А.Б. (гр. 321701)');

INSERT INTO users (telegram_id, role, full_name, email, url_id) VALUES
    (2001, 'TEACHER', 'Петрова Анна Сергеевна', 'petrova@example.test', 'a-petrova'),
    (2002, 'TEACHER', 'Сидорова Н.В.', 'sidorova@example.test', NULL);

INSERT INTO submissions (id, student_telegram_id, teacher_telegram_id, status, file_path) VALUES
    (1, 1001, 2001, 'SUCCESS', '/app/backend/../storage/1712345678901_Курсовая работа.pdf'),
    (2, 1002, 2001, 'REJECTED', 'C:\Users\dev\SAPSR\backend-java\..\storage\1712345678902_отчёт_v2.pdf'),
    (3, 1001, NULL, 'PROCESSING', NULL);
-- Работа, которую прежний обработчик уронил два дня назад: V5 закрывает её как FAILED.
INSERT INTO submissions (id, student_telegram_id, teacher_telegram_id, status, file_path, created_at) VALUES
    (4, 1002, 2001, 'PROCESSING', NULL, CURRENT_TIMESTAMP - INTERVAL '2 days');
SELECT setval('submissions_id_seq', 4);
