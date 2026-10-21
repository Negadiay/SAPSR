-- Версии работы (решение 9 в docs/decisions.md): новая загрузка студента тому же преподавателю
-- связывается с предыдущей и заменяет её в очереди преподавателя. Срок сдачи считается
-- по first_submitted_at — когда пришла первая версия; у первой версии он пуст и равен created_at.

ALTER TABLE submissions ADD COLUMN parent_id          INTEGER REFERENCES submissions (id) ON DELETE SET NULL;
ALTER TABLE submissions ADD COLUMN first_submitted_at TIMESTAMP;

CREATE INDEX idx_submissions_parent_id ON submissions (parent_id);

-- Размеченный файл преподавателя (docs/contracts/api-v1.md, разделы 8–11): один на работу,
-- повторная загрузка заменяет прежний. Предупреждения разбора — как их вернул метод загрузки.
CREATE TABLE review_files (
    id                 SERIAL PRIMARY KEY,
    submission_id      INTEGER      NOT NULL UNIQUE REFERENCES submissions (id) ON DELETE CASCADE,
    storage_key        VARCHAR(255) NOT NULL,
    file_sha256        VARCHAR(64),
    original_file_name VARCHAR(255),
    uploaded_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    warnings           JSONB
);

-- Комментарии, извлечённые из аннотаций PDF. position — порядок показа: по страницам,
-- на странице сверху вниз, ответы сразу после комментария, на который отвечают.
CREATE TABLE annotations (
    id             SERIAL PRIMARY KEY,
    review_file_id INTEGER     NOT NULL REFERENCES review_files (id) ON DELETE CASCADE,
    position       INTEGER     NOT NULL,
    page           INTEGER     NOT NULL,
    kind           VARCHAR(20) NOT NULL,
    author         VARCHAR(255),
    created_at     TIMESTAMP,
    comment        TEXT,
    quoted_text    TEXT,
    rect           JSONB,
    reply_to_id    INTEGER REFERENCES annotations (id) ON DELETE SET NULL
);

CREATE INDEX idx_annotations_review_file_id ON annotations (review_file_id);
