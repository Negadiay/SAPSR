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
