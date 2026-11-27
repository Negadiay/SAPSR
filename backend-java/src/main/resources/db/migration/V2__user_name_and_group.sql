-- ФИО и группа пользователя отдельными полями (решение 4 в docs/decisions.md).
-- Номер студенческого билета не храним: решение 4 в редакции 18.10.2026.
-- Тёзок различаем по telegram_id — он уже первичный ключ.

ALTER TABLE users ADD COLUMN last_name    VARCHAR(100);
ALTER TABLE users ADD COLUMN first_name   VARCHAR(100);
ALTER TABLE users ADD COLUMN middle_name  VARCHAR(100);
ALTER TABLE users ADD COLUMN group_number VARCHAR(6);

CREATE INDEX idx_users_group_number ON users (group_number);

-- Студенты старой формы: «Иванов И.И. (гр. 321702)». Известны фамилия и группа,
-- имя и отчество — только инициалами, поэтому first_name и middle_name остаются NULL:
-- при следующем входе интерфейс попросит дополнить ФИО.
UPDATE users
SET last_name    = substring(full_name FROM '^\s*([А-ЯЁа-яё-]+)'),
    group_number = substring(full_name FROM '\(гр\.\s*(\d{6})\)')
WHERE role = 'STUDENT'
  AND full_name IS NOT NULL;

-- Преподаватели: полное ФИО из ИИС, «Фамилия Имя Отчество».
-- Если вместо имени инициалы («Гракова Н.В.»), сохраняется только фамилия.
WITH parts AS (
    SELECT telegram_id, regexp_split_to_array(btrim(full_name), '\s+') AS p
    FROM users
    WHERE role = 'TEACHER'
      AND btrim(coalesce(full_name, '')) <> ''
)
UPDATE users u
SET last_name   = parts.p[1],
    first_name  = CASE WHEN parts.p[2] !~ '\.' THEN parts.p[2] END,
    middle_name = CASE WHEN parts.p[2] !~ '\.' THEN NULLIF(array_to_string(parts.p[3:], ' '), '') END
FROM parts
WHERE u.telegram_id = parts.telegram_id;
