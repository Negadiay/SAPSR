-- Тёзки (решение 4): студенты одной группы с одинаковым ФИО получают постоянные номера 1, 2, 3…
-- name_key — ФИО для сравнения: нижний регистр, «ё» = «е», без дефисов, пробелы схлопнуты
-- (та же нормализация — в Java, entity/NameKey). namesake_number выдаётся при регистрации —
-- на единицу больше наибольшего среди тёзок группы — и потом не меняется.
ALTER TABLE users ADD COLUMN name_key VARCHAR(320);
ALTER TABLE users ADD COLUMN namesake_number INT;

-- Уже зарегистрированные студенты. У аккаунтов по старой форме имени нет — ключ строится по строке
-- «Иванов И.И.» без группы. Даты регистрации в таблице нет, поэтому порядок номеров — по telegram_id.
UPDATE users
SET name_key = NULLIF(btrim(regexp_replace(replace(replace(lower(
        CASE WHEN first_name IS NOT NULL
             THEN concat_ws(' ', last_name, first_name, middle_name)
             ELSE regexp_replace(coalesce(full_name, last_name, ''), '\s*\(гр\.[^)]*\)\s*$', '')
        END), 'ё', 'е'), '-', ''), '\s+', ' ', 'g')), '')
WHERE role = 'STUDENT';

UPDATE users u
SET namesake_number = numbered.n
FROM (SELECT telegram_id,
             row_number() OVER (PARTITION BY group_number, name_key ORDER BY telegram_id) AS n
      FROM users
      WHERE role = 'STUDENT' AND name_key IS NOT NULL AND group_number IS NOT NULL) numbered
WHERE u.telegram_id = numbered.telegram_id;

CREATE UNIQUE INDEX uq_users_namesake ON users (group_number, name_key, namesake_number) WHERE role = 'STUDENT';