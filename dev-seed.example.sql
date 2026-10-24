-- Образец тестовых данных для локальной разработки. В рабочую базу не загружать.
--
-- Скопируй в dev-seed.sql (он в .gitignore, в репозиторий не попадает) и впиши свои данные:
-- например, свой Telegram ID, чтобы получать уведомления преподавателя.
--
-- Схему создаёт бэкенд миграциями Flyway при первом запуске, поэтому файл загружается вручную,
-- когда бэкенд уже хотя бы раз стартовал (команды одинаковы в PowerShell, cmd и bash):
--
--   docker compose cp dev-seed.sql postgres-db:/tmp/dev-seed.sql
--   docker compose exec postgres-db psql -U sapsr_user -d sapsr_db -f /tmp/dev-seed.sql
--
-- Повторная загрузка ничего не ломает: существующие записи не трогаются.

-- Тестовый преподаватель: Telegram-уведомления идут на указанный ID, почта для рассылки не используется.
INSERT INTO users (telegram_id, role, full_name, email, last_name, first_name, middle_name)
VALUES (100000001, 'TEACHER', 'Тестовый Преподаватель Иванович', 'teacher@example.test',
        'Тестовый', 'Преподаватель', 'Иванович')
ON CONFLICT (telegram_id) DO NOTHING;

-- Администратор (решение 11): роль ADMIN регистрацией не получить, только записью в базу.
-- Нужен отдельный аккаунт Telegram — у одного аккаунта одна роль.
INSERT INTO users (telegram_id, role, full_name)
VALUES (100000009, 'ADMIN', 'Тестовый Администратор')
ON CONFLICT (telegram_id) DO NOTHING;

-- Сроки осени 2026 года для всех курсов: 2-я опроцентовка (stage 2) и сдача (stage 4). По ним сервер
-- определяет этап; у каждого курса свои, меняются на экране «Управление».
INSERT INTO procentovka_dates (academic_year, half, course, stage, held_on, updated_by)
SELECT 2026, 'AUTUMN', c, s.stage, s.held_on, 100000009
FROM generate_series(1, 4) AS c
CROSS JOIN (VALUES (2, DATE '2026-10-29'), (4, DATE '2026-12-26')) AS s (stage, held_on)
ON CONFLICT DO NOTHING;
