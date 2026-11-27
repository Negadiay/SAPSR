-- Приём двух фаз результата (задача 5.2; docs/contracts/queue-v2.md, раздел 6).
-- report — отчёт v2 целиком: фаза content заменяет отчёт фазы format. format_errors остаются —
-- в них те же замечания в форме v1, по ним работают PDF-отчёт, уведомления и интерфейс.
ALTER TABLE submissions ADD COLUMN report                JSONB;
-- Текущий этап проверки для индикатора: extract, structure, format, claims, reasoning, report.
ALTER TABLE submissions ADD COLUMN pipeline_stage        VARCHAR(20);
-- Первое сообщение о ходе проверки — от него сторож считает зависание (stuck-timeout).
ALTER TABLE submissions ADD COLUMN processing_started_at TIMESTAMP;
-- Пришла фаза format — от неё отсчитывается тайм-аут содержательной проверки (content-timeout).
ALTER TABLE submissions ADD COLUMN format_received_at    TIMESTAMP;
-- Содержательная проверка: PENDING — идёт, DONE — пришла, TIMEOUT — не успела, FAILED — сбой,
-- NONE — не выполнялась (модель выключена, в документе нет текста).
ALTER TABLE submissions ADD COLUMN content_status        VARCHAR(20);
-- Код сбоя из фазы failed (queue-v2.md, раздел 5) или STUCK — работу закрыл сторож.
ALTER TABLE submissions ADD COLUMN failure_code          VARCHAR(32);
-- Преподаватель уведомлён — один раз, когда отчёт окончательный.
ALTER TABLE submissions ADD COLUMN teacher_notified_at   TIMESTAMP;

CREATE INDEX idx_submissions_status ON submissions (status);

-- Работы, по которым результат v1 уже пришёл, завершены: отчёт окончательный, преподаватель уведомлён.
UPDATE submissions
SET content_status      = 'DONE',
    teacher_notified_at = coalesce(created_at, CURRENT_TIMESTAMP)
WHERE status IN ('SUCCESS', 'REJECTED');

-- Прежний обработчик ронял работы в PROCESSING навсегда. Всё, что висит дольше часа, точно не
-- проверится (общий тайм-аут проверки — 9 минут): закрываем без уведомлений, студент видит
-- «Проверка не удалась — загрузите работу снова». Свежие работы закроет сторож.
UPDATE submissions
SET status       = 'FAILED',
    failure_code = 'STUCK'
WHERE status = 'PROCESSING'
  AND created_at < CURRENT_TIMESTAMP - INTERVAL '1 hour';

UPDATE submissions
SET processing_started_at = created_at
WHERE status = 'PROCESSING';
