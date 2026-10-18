-- Файл работы хранится под сгенерированным ключом, исходное имя — только здесь
-- (docs/contracts/queue-v2.md, раздел 3). Плюс профиль проверки и сквозной идентификатор.

ALTER TABLE submissions ADD COLUMN original_file_name VARCHAR(255);
ALTER TABLE submissions ADD COLUMN storage_key        VARCHAR(255);
ALTER TABLE submissions ADD COLUMN file_sha256        VARCHAR(64);
ALTER TABLE submissions ADD COLUMN work_type          VARCHAR(32);
ALTER TABLE submissions ADD COLUMN semester           INTEGER;
ALTER TABLE submissions ADD COLUMN stage              INTEGER;
ALTER TABLE submissions ADD COLUMN trace_id           UUID;

-- Старые работы лежат в корне хранилища под именем «<время>_<исходное имя>»,
-- а в file_path записан абсолютный путь к ним (с прямыми или обратными косыми чертами).
-- Ключ — имя файла, исходное имя — оно же без числового префикса.
UPDATE submissions
SET storage_key        = regexp_replace(file_path, '^.*[/\\]', ''),
    original_file_name = regexp_replace(regexp_replace(file_path, '^.*[/\\]', ''), '^\d+_', '')
WHERE file_path IS NOT NULL;

-- file_path больше не заполняется. Столбец оставлен на переходный период;
-- удалить отдельной миграцией, когда обработчик перейдёт на storage_key.
