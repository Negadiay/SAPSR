-- Приём DOCX (решение 3, правка 15.11.2026): работа проверяется по PDF-версии (storage_key), исходный DOCX
-- хранится рядом. source_format — в каком виде работу сдал студент: 'pdf' или 'docx'.
ALTER TABLE submissions ADD COLUMN source_storage_key VARCHAR(255);
ALTER TABLE submissions ADD COLUMN source_format VARCHAR(8) NOT NULL DEFAULT 'pdf';
