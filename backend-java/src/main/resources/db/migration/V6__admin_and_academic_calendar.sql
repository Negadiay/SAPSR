-- Учебный календарь и администратор (решение 11 в docs/decisions.md).
-- Курс, семестр и этап опроцентовки определяет сервер: курс — по первой цифре номера группы
-- (год поступления), половина года — по дате, этап — по датам опроцентовок. Студент их не выбирает.
-- Администратор (users.role = 'ADMIN') исправляет то, что определилось неверно. Роль ADMIN нельзя
-- получить регистрацией: её записывают в базу вручную по Telegram ID (README, «Администратор»).

-- Настройки «ключ — значение»: пока только half — половина учебного года вместо определённой по дате.
-- Ручная половина года сама снимается, когда дата её догоняет (AcademicCalendar.half).
CREATE TABLE academic_settings (
    key        VARCHAR(40)  PRIMARY KEY,
    value      VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT
);

-- Курс группы, если по номеру он определяется неверно. В БГУИР курсов не больше четырёх.
-- Исправление действует один учебный год: через год группа на следующем курсе.
CREATE TABLE group_course_overrides (
    group_number  VARCHAR(6) NOT NULL,
    academic_year INTEGER    NOT NULL,
    course        INTEGER    NOT NULL CHECK (course BETWEEN 1 AND 4),
    updated_at    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    BIGINT,
    PRIMARY KEY (group_number, academic_year)
);

-- Даты опроцентовок: учебный год (год начала, 2026 — это 2026/2027), половина года, номер.
CREATE TABLE procentovka_dates (
    academic_year INTEGER     NOT NULL,
    half          VARCHAR(10) NOT NULL CHECK (half IN ('AUTUMN', 'SPRING')),
    stage         INTEGER     NOT NULL CHECK (stage BETWEEN 1 AND 3),
    held_on       DATE        NOT NULL,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    BIGINT,
    PRIMARY KEY (academic_year, half, stage)
);
