"""Шаг 2 содержательной цепочки (символьный): проверка цитат.

Для каждого ответа модели проверяется, что цитата действительно есть в тексте
(нечёткое сравнение rapidfuzz). Не нашли — ответ отбрасывается и считается в метрике
«доля выдуманных цитат». Это простая и проверяемая защита от галлюцинаций.
"""

from __future__ import annotations

from typing import Iterable, Optional

from rapidfuzz import fuzz

from sapsr.document.model import Block
from sapsr.document.text import fold


def quote_score(quote: str, text: str) -> float:
    q, t = fold(quote), fold(text)
    if not q or not t:
        return 0.0
    if q in t:
        return 100.0
    if len(q) > len(t):
        return fuzz.ratio(q, t)
    return fuzz.partial_ratio(q, t)


def locate_quote(quote: str, preferred: Optional[Block], candidates: Iterable[Block],
                 min_score: float, min_chars: int) -> tuple[Optional[Block], float]:
    """Блок, в котором есть цитата: сначала названный моделью, потом остальные блоки фрагмента
    (модель иногда путает идентификаторы соседних блоков)."""
    if len(fold(quote)) < min_chars:
        return None, 0.0
    best: tuple[Optional[Block], float] = (None, 0.0)
    ordered = ([preferred] if preferred is not None else []) + [b for b in candidates if b is not preferred]
    for block in ordered:
        score = quote_score(quote, block.text)
        if score >= min_score:
            return block, score
        if score > best[1]:
            best = (None, score)
    return best
