CREATE TABLE teachers (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    telegram_id BIGINT UNIQUE
);

CREATE TABLE students (
    telegram_id BIGINT PRIMARY KEY,
    full_name VARCHAR(255) NOT NULL,
    group_number VARCHAR(10) NOT NULL
);

CREATE TABLE submissions (
    id SERIAL PRIMARY KEY,
    student_telegram_id BIGINT REFERENCES students(telegram_id),
    teacher_id INTEGER REFERENCES teachers(id),
    status VARCHAR(50) NOT NULL,
    file_path VARCHAR(500),
    format_errors JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Предзаполнение преподавателей (замените на реальные данные из ИИАС БГУИР)
INSERT INTO teachers (email, full_name) VALUES
    ('example.teacher1@bsuir.by', 'Иванов И.И.'),
    ('example.teacher2@bsuir.by', 'Петрова А.С.');
