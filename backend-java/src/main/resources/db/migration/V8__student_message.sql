-- Одно сообщение студенту на работу: итог оформления приходит сразу, итог содержания бот дописывает
-- в то же сообщение (editMessageText), а не присылает вторым.
ALTER TABLE submissions ADD COLUMN student_message_id INTEGER;
