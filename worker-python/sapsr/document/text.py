"""Нормализация текста, извлечённого из PDF.

Перенесено из прежнего analyzer.py без изменения поведения: правила оформления,
которые раньше работали по сплошному тексту, получают текст в том же виде.
"""

from __future__ import annotations

import re
import unicodedata

_RE_SOFT_HYPHEN = re.compile("­")
_RE_HARD_BREAK = re.compile(r"-[ \t]*\n[ \t]*")
_RE_SPACES = re.compile(r"\s+")
_RE_NON_ALNUM = re.compile(r"[^a-zа-яё0-9]")

# Латинские буквы, которые pdfplumber иногда возвращает вместо кириллических в PDF из LaTeX.
_LATIN_TO_CYR = str.maketrans({
    "A": "А", "B": "В", "C": "С", "E": "Е", "H": "Н", "I": "І",
    "K": "К", "M": "М", "O": "О", "P": "Р", "T": "Т", "X": "Х",
    "a": "а", "c": "с", "e": "е", "i": "і", "o": "о", "p": "р",
    "x": "х", "y": "у",
})
_RE_CYR_OR_LOOKALIKE = re.compile(r"[А-ЯЁа-яёACBEHIKMOPTXaceiopyxАВСЕНІКМОРТХасеіорх]+")
_RE_HAS_CYR = re.compile(r"[А-ЯЁа-яё]")


def fix_lookalikes(text: str) -> str:
    """Заменяет латинские двойники на кириллицу в словах, где есть настоящая кириллица."""

    def _replace(match: re.Match) -> str:
        word = match.group()
        return word.translate(_LATIN_TO_CYR) if _RE_HAS_CYR.search(word) else word

    return _RE_CYR_OR_LOOKALIKE.sub(_replace, text)


def clean_text(raw: str) -> str:
    """NFC, кириллица вместо двойников, без мягких переносов, слова с переносом склеены."""
    text = unicodedata.normalize("NFC", raw or "")
    text = fix_lookalikes(text)
    text = _RE_SOFT_HYPHEN.sub("", text)
    return _RE_HARD_BREAK.sub("", text)


def clean_line(raw: str) -> str:
    """То же для одной строки: без склейки переносов, она делается при сборке блоков."""
    text = unicodedata.normalize("NFC", raw or "")
    return _RE_SOFT_HYPHEN.sub("", fix_lookalikes(text)).strip()


def collapse_spaces(text: str) -> str:
    return _RE_SPACES.sub(" ", text or "").strip()


def fold(text: str) -> str:
    """Ключ для нечёткого сравнения: регистр, «ё», пробелы, кавычки и тире."""
    text = clean_line(text).lower().replace("ё", "е")
    text = re.sub(r"[«»\"“”„'’`]", "", text)
    text = re.sub(r"[‐‑‒–—―]", "-", text)
    return collapse_spaces(text)


def normalize_code(value: str) -> str:
    return re.sub(r"[\s\-]", "", value or "").lower()


def normalize_person_name(value: str) -> str:
    text = fix_lookalikes(value or "").replace("ё", "е").replace("Ё", "Е")
    text = re.sub(r"[^А-Яа-я.\-\s]", " ", text)
    text = re.sub(r"\s+", " ", text).strip().lower()
    # «П. П. Петров» и «П.П. Петров» — одно и то же.
    return re.sub(r"(?<=\b[а-я])\.\s+(?=[а-я]\.)", ".", text)


def norm_num(value: str) -> str:
    """'2 . 2' → '2.2'"""
    return re.sub(r"\s", "", value or "")


def font_family_key(font_name: str) -> str:
    """Имя шрифта без префикса подмножества «ABCDEF+», только буквы и цифры в нижнем регистре."""
    base = (font_name or "").split("+", 1)[-1]
    return _RE_NON_ALNUM.sub("", base.lower())


def font_style_key(font_name: str) -> str:
    """Ключ семейства без начертания — для сравнения участков одного документа."""
    key = font_family_key(font_name)
    for suffix in ("regular", "roman", "normal", "psmt", "mt", "bold", "italic", "oblique"):
        key = key.replace(suffix, "")
    return key


_RE_BOLD_FONT = re.compile(r"bold|black|heavy|semibold|demi|[^a-z]bx|^bx|sfbx|cmbx|lmroman\d*bold|-b$", re.IGNORECASE)
_RE_ITALIC_FONT = re.compile(r"italic|oblique|sfti|sfit|cmti|-i$|-it$", re.IGNORECASE)


def is_bold_font(font_name: str) -> bool:
    base = (font_name or "").split("+", 1)[-1]
    return bool(_RE_BOLD_FONT.search(base))


def is_italic_font(font_name: str) -> bool:
    base = (font_name or "").split("+", 1)[-1]
    return bool(_RE_ITALIC_FONT.search(base))


def pages_to_range(pages) -> str:
    """[3, 4, 5, 9] → '3–5, 9'"""
    if not pages:
        return ""
    ordered = sorted(set(pages))
    ranges, start, end = [], ordered[0], ordered[0]
    for page in ordered[1:]:
        if page == end + 1:
            end = page
        else:
            ranges.append(str(start) if start == end else f"{start}–{end}")
            start = end = page
    ranges.append(str(start) if start == end else f"{start}–{end}")
    return ", ".join(ranges)


def percentile(values: list, pct: float) -> float:
    """p-й процентиль без интерполяции, как в прежнем анализаторе."""
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, min(int(len(ordered) * pct / 100), len(ordered) - 1))
    return ordered[idx]


def extract_context(text: str, pattern: re.Pattern, before: int, after: int, max_len: int) -> str:
    """Фрагмент вокруг первого совпадения: «...до [совпадение] после...»."""
    match = pattern.search(text or "")
    if not match:
        return ""
    start = max(0, match.start() - before)
    end = min(len(text), match.end() + after)
    snippet = text[start:end].strip().replace("\n", " ")
    if start > 0:
        snippet = "..." + snippet
    if end < len(text):
        snippet = snippet + "..."
    return snippet[:max_len]
