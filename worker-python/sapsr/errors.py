"""Сбои проверки с кодами из протокола очередей (docs/contracts/queue-v2.md, раздел 5)."""

from __future__ import annotations

FAILURE_MESSAGES = {
    "FILE_NOT_FOUND": "Файл работы не найден.",
    "PDF_UNREADABLE": "Файл не открывается: он повреждён или это не PDF. Сохраните работу в PDF заново.",
    "PDF_ENCRYPTED": "Файл защищён паролем. Сохраните его без пароля.",
    "TOO_LARGE": "В работе больше {max_pages} страниц — это слишком много для проверки.",
    "TIMEOUT": "Проверка не уложилась по времени. Попробуйте позже.",
    "INTERNAL": "Сбой проверки. Если повторится, сообщите администратору.",
}


class CheckFailure(Exception):
    """Проверку нельзя выполнить. `detail` — для лога (без путей и персональных данных),
    `user_message` — понятный студенту текст для поля error.message."""

    def __init__(self, code: str, detail: str = "", **values):
        if code not in FAILURE_MESSAGES:
            raise ValueError(f"неизвестный код сбоя {code}")
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code
        self.detail = detail
        self.values = values

    @property
    def user_message(self) -> str:
        try:
            return FAILURE_MESSAGES[self.code].format(**self.values)
        except (KeyError, IndexError):
            return FAILURE_MESSAGES[self.code].split(":")[0] + "."
