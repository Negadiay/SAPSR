-- Сроки по курсам (решение 11, правка 24.10.2026): у курсов разные даты опроцентовок и сдачи —
-- например, у 3 курса весной учебный год короче из-за практики и кончается в конце мая, а не июня.
-- stage 1–3 — опроцентовки, stage 4 — срок сдачи работы.
ALTER TABLE procentovka_dates DROP CONSTRAINT procentovka_dates_pkey;
ALTER TABLE procentovka_dates DROP CONSTRAINT procentovka_dates_stage_check;
ALTER TABLE procentovka_dates ADD COLUMN course INTEGER;

-- Даты, введённые до этой миграции, были общими для всех курсов — теперь они у каждого курса.
UPDATE procentovka_dates SET course = 1;
INSERT INTO procentovka_dates (academic_year, half, stage, held_on, updated_at, updated_by, course)
SELECT academic_year, half, stage, held_on, updated_at, updated_by, c
FROM procentovka_dates CROSS JOIN generate_series(2, 4) AS c
WHERE course = 1;

ALTER TABLE procentovka_dates ALTER COLUMN course SET NOT NULL;
ALTER TABLE procentovka_dates ADD CONSTRAINT procentovka_dates_course_check CHECK (course BETWEEN 1 AND 4);
ALTER TABLE procentovka_dates ADD CONSTRAINT procentovka_dates_stage_check CHECK (stage BETWEEN 1 AND 4);
ALTER TABLE procentovka_dates ADD PRIMARY KEY (academic_year, half, course, stage);
