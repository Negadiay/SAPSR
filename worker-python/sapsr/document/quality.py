"""Качество текстового слоя (решение 15, задача Б1).

PDF может содержать текст, который нельзя прочитать: шрифты сохранены без таблицы
соответствия символам (виртуальный принтер, нестандартный генератор). Тогда pdfminer
отдаёт «(cid:N)» и случайные латинские символы, а проверки оформления и содержания
дают ложные замечания. Здесь считается доля читаемых символов; пороги — в kb/layout.yaml.
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass

from sapsr.document.extract import RawDocument
from sapsr.kb.schema import TextQualityParams

_READABLE_CATEGORIES = ("P", "Z", "Nd", "No", "Sm", "Sc", "So")


def _readable(text: str) -> bool:
    """Буквы кириллицы, латиницы и греческие, цифры, пунктуация, пробелы, знаки (№, §, ±)."""
    for ch in text:
        code = ord(ch)
        if 0x20 <= code < 0x7F or 0x0400 <= code <= 0x04FF or 0x0370 <= code <= 0x03FF:
            continue
        if ch in "\t\n\r":
            continue
        if not unicodedata.category(ch).startswith(_READABLE_CATEGORIES):
            return False
    return True


@dataclass(frozen=True)
class TextQuality:
    chars: int
    readable_share: float
    cid_share: float
    unreadable: bool


def text_quality(raw: RawDocument, params: TextQualityParams) -> TextQuality:
    total = readable = cid = 0
    for page in raw.pages:
        for char in page.chars:
            total += 1
            if char.text.startswith("(cid:"):
                cid += 1
            elif _readable(char.text):
                readable += 1
    if total == 0:
        return TextQuality(0, 1.0, 0.0, False)
    readable_share, cid_share = readable / total, cid / total
    unreadable = total >= params.min_chars and (
        readable_share < params.min_readable_share or cid_share > params.max_cid_share)
    return TextQuality(total, readable_share, cid_share, unreadable)
