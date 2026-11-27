"""Общий тайм-аут проверки: зависшая модель не должна держать очередь (план, З9; Ж4)."""

from __future__ import annotations

import time
from typing import Optional


class Deadline:
    def __init__(self, seconds: Optional[float]):
        self._end = time.monotonic() + seconds if seconds else None

    def remaining(self) -> Optional[float]:
        if self._end is None:
            return None
        return max(0.0, self._end - time.monotonic())

    @property
    def expired(self) -> bool:
        remaining = self.remaining()
        return remaining is not None and remaining <= 0
