-- Отметка преподавателя у замечания автопроверки: «верно» или «неверно» (решение 17).
-- requirement_id и origin копируются из отчёта при сохранении: по ним считается доля ложных замечаний
-- отдельно для правил, модели и их связки. Время — по Минску, как в остальных таблицах.
CREATE TABLE finding_feedback (
    id                  BIGSERIAL PRIMARY KEY,
    submission_id       INT          NOT NULL REFERENCES submissions (id) ON DELETE CASCADE,
    finding_id          VARCHAR(32)  NOT NULL,
    requirement_id      VARCHAR(100) NOT NULL,
    origin              VARCHAR(16)  NOT NULL,
    verdict             VARCHAR(16)  NOT NULL CHECK (verdict IN ('correct', 'incorrect')),
    teacher_telegram_id BIGINT       NOT NULL REFERENCES users (telegram_id),
    created_at          TIMESTAMP    NOT NULL,
    UNIQUE (submission_id, finding_id)
);

CREATE INDEX idx_finding_feedback_submission ON finding_feedback (submission_id);