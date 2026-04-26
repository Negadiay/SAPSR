"""SAPSR PDF Analyzer — интеллектуальный анализ пояснительной записки БГУИР.

Принципы:
  · Все regex компилируются один раз при загрузке модуля.
  · Текст строится через join(), не через +=.
  · Пороги мягкие — правильно оформленный LaTeX-документ должен проходить.
  · Специальности БГУИР кэшируются в памяти процесса (TTL 1 час).
  · Поля измеряются только по основному тексту (без колонтитулов).
  · Номера страниц определяются группировкой символов (работает для стр. 10+).
  · Новые проверки: межстрочный интервал, абзацный отступ, таблицы,
    нумерация разделов, формат библиографии, тип документа.
"""

import re
import sys
import json
import time
from collections import Counter, defaultdict
from datetime import datetime
from typing import Optional

import pdfplumber
import requests

from check_config import (
    ALLOWED_FONT_FAMILIES,
    BSUIR_SPECIALITIES_URL,
    CRITICAL_REFERENCES_THRESHOLD,
    FONT_SIZE_MAJOR_THRESHOLD_PCT,
    FONT_SIZE_MAX,
    FONT_SIZE_MIN,
    MARGIN_BOTTOM,
    MARGIN_LEFT,
    MARGIN_RIGHT,
    MARGIN_TOLERANCE,
    MARGIN_TOP,
    MIN_REFERENCES,
    PAGE_NUMBER_MIN_CHECKABLE_PAGES,
    PAGE_NUMBER_MIN_COVERAGE,
    PAGE_NUM_X_MIN,
    PAGE_NUM_Y_MIN,
    REQUIRED_SECTIONS,
)

# ---------------------------------------------------------------------------
# Предкомпилированные regex (один раз при старте процесса)
# ---------------------------------------------------------------------------

_RE_NON_ALNUM   = re.compile(r"[^a-zа-яё0-9]")
_RE_SOFT_HYPHEN = re.compile(r"­")
_RE_HARD_BREAK  = re.compile(r"-[ \t]*\n[ \t]*")

_RE_TOC_DOTS  = re.compile(r"\.{4,}")
_RE_TOC_TRAIL = re.compile(r".*\s{3,}\d+\s*$")

# Нумерованные заголовки (раздел / подраздел)
_RE_SECTION_NUM = re.compile(r"^\s*(\d+(?:\.\d+)?)\s+\S", re.MULTILINE)

# Список источников
_RE_REF_HEADING = re.compile(
    r"список\s+(?:использованных\s+источников|литературы)", re.IGNORECASE
)
_RE_REF_ENTRY_A = re.compile(r"^\s*\[?\d+\]?[\.\)]?\s+\S", re.MULTILINE)
_RE_REF_ENTRY_B = re.compile(r"^\s*\d+\s+\S", re.MULTILINE)
_RE_REF_YEAR    = re.compile(r"\b(19|20)\d{2}\b")
_RE_REF_URL     = re.compile(r"https?://")

# Рисунки — все падежные формы + сокращение «рис.»
_NUM_PAT = r"(\d+(?:[\s.]\d+)*)"

_RE_FIG_CAPTION = re.compile(r"рисунок\s*" + _NUM_PAT, re.IGNORECASE)
_RE_FIG_ANY     = re.compile(
    r"рис(?:ун(?:ок|ка|ку|ке|ком|ки|ков))?\.?\s*" + _NUM_PAT,
    re.IGNORECASE,
)

# Таблицы — все падежные формы + сокращение «табл.»
_RE_TBL_CAPTION = re.compile(r"таблица\s*" + _NUM_PAT, re.IGNORECASE)
_RE_TBL_REF     = re.compile(
    r"(?:таблиц[ауеы]?|табл\.?)\s*" + _NUM_PAT,
    re.IGNORECASE,
)

# Простые и нумерованные списки
_RE_SIMPLE_LIST   = re.compile(r"((?:^[ \t]*[—–\-]\s+.{10,}\n?){3,})", re.MULTILINE)
_RE_NUMBERED_LIST = re.compile(
    r"((?:^[ \t]*\d+[\.\)]?\s+[а-яёА-ЯЁa-zA-Z].{5,}\n?){3,})",
    re.MULTILINE,
)

# Титульный лист
_RE_MINSK_YEAR = re.compile(
    r"минск[^\n]*?(\d{4})|(\d{4})[^\n]*?минск", re.IGNORECASE
)
_RE_BSUIR_ID  = re.compile(
    r"БГУИР\s+КП\d+\s+((?:\d[\d\s\-]{3,20}\d))\s+(\d{3})\b"
)
_RE_BSUIR_FB  = re.compile(
    r"(\d-\d{2}[\s\-]\d{4}-\d{2}|\d-\d{2}[\s\-]\d{2}[\s\-]\d{2})\s+(\d{3})\b"
)

# Тип документа
_RE_DTYPE_EXPLANATORY = re.compile(r"пояснительная\s+записка", re.IGNORECASE)
_RE_DTYPE_COURSEWORK  = re.compile(r"курсов(?:ой|ого|ому|ым|ом)", re.IGNORECASE)
_RE_DTYPE_THESIS      = re.compile(r"дипломн|выпускной|квалификацион", re.IGNORECASE)

# Предкомпилированные паттерны разделов из конфига
_COMPILED_SECTIONS = {
    key: [re.compile(p, re.IGNORECASE) for p in data["patterns"]]
    for key, data in REQUIRED_SECTIONS.items()
}

# ---------------------------------------------------------------------------
# Преднормализованные семейства шрифтов — O(1) поиск
# ---------------------------------------------------------------------------

_ALLOWED_FONTS_NORMALIZED: frozenset[str] = frozenset(
    _RE_NON_ALNUM.sub("", f.lower()) for f in ALLOWED_FONT_FAMILIES
)

# ---------------------------------------------------------------------------
# Кэш специальностей (уровень процесса, TTL 1 час)
# ---------------------------------------------------------------------------

_specialties_cache: Optional[frozenset] = None
_specialties_cache_ts: float            = 0.0
_SPECIALTIES_TTL: float                 = 3600.0


def _get_known_specialties() -> Optional[frozenset]:
    global _specialties_cache, _specialties_cache_ts
    now = time.monotonic()
    if _specialties_cache is not None and (now - _specialties_cache_ts) < _SPECIALTIES_TTL:
        return _specialties_cache
    try:
        resp = requests.get(BSUIR_SPECIALITIES_URL, timeout=8)
        if resp.status_code == 200:
            codes = frozenset(
                _normalize_code(s.get("code", ""))
                for s in resp.json()
                if s.get("code")
            )
            _specialties_cache    = codes
            _specialties_cache_ts = now
            return codes
    except Exception as exc:
        print(f"[ANALYZER] BSUIR specialties API недоступен: {exc}")
    return _specialties_cache  # устаревший кэш или None


# ---------------------------------------------------------------------------
# Утилиты
# ---------------------------------------------------------------------------

def _make_error(
    severity: str,
    page,
    message: str,
    rule: str,
    found: str,
    fix: str,
    location: Optional[str] = None,
    category: Optional[str] = None,
) -> dict:
    return {
        "severity": severity,
        "category": category or severity,
        "page":     page,
        "location": location or (f"Страница {page}" if page else "Документ"),
        "message":  message,
        "rule":     rule,
        "found":    found,
        "fix":      fix,
    }


def _clean_text(raw: str) -> str:
    """Убирает мягкие переносы и объединяет слова с жёстким дефисом-переносом."""
    text = _RE_SOFT_HYPHEN.sub("", raw)
    text = _RE_HARD_BREAK.sub("", text)
    return text


def _normalize_code(s: str) -> str:
    return re.sub(r"[\s\-]", "", (s or "")).lower()


def _norm_num(n: str) -> str:
    """'2 . 2' → '2.2'"""
    return re.sub(r"\s", "", n)


def _is_toc_line(line: str) -> bool:
    return bool(_RE_TOC_DOTS.search(line)) or bool(_RE_TOC_TRAIL.match(line))


def _pages_to_range(pages) -> str:
    if not pages:
        return ""
    pages = sorted(set(pages))
    ranges, start, end = [], pages[0], pages[0]
    for p in pages[1:]:
        if p == end + 1:
            end = p
        else:
            ranges.append(str(start) if start == end else f"{start}–{end}")
            start = end = p
    ranges.append(str(start) if start == end else f"{start}–{end}")
    return ", ".join(ranges)


def _percentile(lst: list, pct: float) -> float:
    """p-й процентиль отсортированного списка."""
    if not lst:
        return 0.0
    s   = sorted(lst)
    idx = max(0, min(int(len(s) * pct / 100), len(s) - 1))
    return s[idx]


def _dominant_size(chars: list) -> float:
    counts = Counter(
        round(c.get("size", 0), 1)
        for c in chars
        if c.get("text", "").strip()
    )
    return counts.most_common(1)[0][0] if counts else 0.0


def _dominant_font(chars: list) -> str:
    counts = Counter(
        c.get("fontname")
        for c in chars
        if c.get("text", "").strip() and c.get("fontname")
    )
    return counts.most_common(1)[0][0] if counts else ""


def _is_allowed_font(font_name: str) -> bool:
    """Проверяет семейство шрифта; всегда принимает встроенные LaTeX-шрифты."""
    if not font_name:
        return True
    # LaTeX встраивает шрифты с 6-буквенным prefix до "+": отбрасываем его
    base       = font_name.split("+", 1)[-1]
    normalized = _RE_NON_ALNUM.sub("", base.lower())
    return any(fam in normalized for fam in _ALLOWED_FONTS_NORMALIZED)


def _body_chars(chars: list) -> list:
    """Возвращает символы основного текста (без колонтитулов)."""
    lo = MARGIN_TOP    - MARGIN_TOLERANCE
    hi = MARGIN_BOTTOM + MARGIN_TOLERANCE
    return [
        c for c in chars
        if c.get("text", "").strip()
        and lo <= c.get("top", 0) <= hi
    ]


def _detect_page_number(chars: list) -> bool:
    """Определяет наличие номера страницы, группируя соседние символы-цифры.

    Исправляет баг исходной версии, где '.isdigit()' работал только для
    однозначных чисел — для страниц 10+ символы разные и нужна группировка.
    """
    bottom = [
        c for c in chars
        if c.get("top", 0) >= PAGE_NUM_Y_MIN and c.get("text", "").strip()
    ]
    if not bottom:
        return False

    bottom.sort(key=lambda c: c.get("x0", 0))

    # Группируем символы, находящиеся в пределах 3pt по x
    groups: list[list] = []
    current            = [bottom[0]]
    for c in bottom[1:]:
        gap = c.get("x0", 0) - current[-1].get("x1", current[-1].get("x0", 0))
        if gap < 3.0:
            current.append(c)
        else:
            groups.append(current)
            current = [c]
    groups.append(current)

    for group in groups:
        text = "".join(c.get("text", "") for c in group).strip()
        if text.isdigit() and 1 <= int(text) <= 9999:
            x0 = min(c.get("x0", 0) for c in group)
            if x0 >= PAGE_NUM_X_MIN:
                return True
    return False


def _detect_document_type(first_page_text: str) -> str:
    if _RE_DTYPE_THESIS.search(first_page_text):
        return "thesis"
    if (
        _RE_DTYPE_COURSEWORK.search(first_page_text)
        or _RE_DTYPE_EXPLANATORY.search(first_page_text)
    ):
        return "coursework"
    return "unknown"


# ---------------------------------------------------------------------------
# Проверки
# ---------------------------------------------------------------------------

def _check_structure(full_text: str) -> list:
    errors = []
    for key, patterns in _COMPILED_SECTIONS.items():
        if not any(p.search(full_text) for p in patterns):
            name = REQUIRED_SECTIONS[key]["name"]
            errors.append(_make_error(
                "critical", None,
                f"Отсутствует обязательный раздел: «{name}».",
                f"В документе должен быть раздел «{name}».",
                "Раздел не найден по ключевому заголовку.",
                f"Добавьте раздел «{name}» с отдельным заголовком.",
                "Структура документа", "critical",
            ))
    return errors


def _check_section_numbering(full_text: str) -> list:
    """Мягкая проверка: разделы нумеруются последовательно без пропусков."""
    matches   = _RE_SECTION_NUM.findall(full_text)
    top_level = sorted({
        int(m.split(".")[0])
        for m in matches
        if m.split(".")[0].isdigit()
    })
    if len(top_level) < 2:
        return []
    gaps = [
        top_level[i]
        for i in range(1, len(top_level))
        if top_level[i] != top_level[i - 1] + 1
    ]
    if not gaps:
        return []
    return [_make_error(
        "minor", None,
        "Нарушена последовательность номеров разделов.",
        "Разделы должны нумероваться последовательно: 1, 2, 3...",
        f"Пропуски перед разделами: {', '.join(map(str, gaps))}.",
        "Проверьте нумерацию разделов в документе.",
        "Нумерация разделов", "warning",
    )]


def _check_references_count(full_text: str) -> list:
    errors  = []
    matches = list(_RE_REF_HEADING.finditer(full_text))
    if not matches:
        return errors
    # Последнее вхождение заголовка = фактический раздел (первое — в оглавлении)
    section = full_text[matches[-1].end():]
    count   = len(_RE_REF_ENTRY_A.findall(section))
    if count == 0:
        count = len(_RE_REF_ENTRY_B.findall(section))
    if count < CRITICAL_REFERENCES_THRESHOLD:
        errors.append(_make_error(
            "critical", None,
            f"Список источников содержит менее {CRITICAL_REFERENCES_THRESHOLD} источников.",
            f"Должно быть не менее {MIN_REFERENCES} источников.",
            f"Найдено: {count}.",
            "Добавьте источники и оформите каждый отдельной строкой.",
            "Список использованных источников", "critical",
        ))
    elif count < MIN_REFERENCES:
        errors.append(_make_error(
            "warning", None,
            f"Рекомендуется не менее {MIN_REFERENCES} источников.",
            f"Рекомендуется не менее {MIN_REFERENCES} источников.",
            f"Найдено: {count}.",
            "Добавьте недостающие источники.",
            "Список использованных источников", "warning",
        ))
    return errors


def _check_bibliography_format(full_text: str) -> list:
    """Мягкая проверка: каждый источник должен содержать год или URL."""
    matches = list(_RE_REF_HEADING.finditer(full_text))
    if not matches:
        return []
    section = full_text[matches[-1].end():]
    lines   = [
        l.strip()
        for l in section.splitlines()
        if _RE_REF_ENTRY_A.match(l) or _RE_REF_ENTRY_B.match(l)
    ]
    if not lines:
        return []
    suspicious = [
        l for l in lines
        if not _RE_REF_YEAR.search(l) and not _RE_REF_URL.search(l)
    ]
    if not suspicious:
        return []
    return [_make_error(
        "minor", None,
        f"{len(suspicious)} источник(ов) не содержат года или URL.",
        "Каждый источник должен содержать год публикации или гиперссылку.",
        f"Подозрительных строк: {len(suspicious)}.",
        "Оформите источники по ГОСТ 7.0.5-2008.",
        "Список использованных источников", "warning",
    )]


def _check_simple_lists(full_text: str) -> list:
    """Знаки препинания в простых списках (тире)."""
    errors = []
    for block in _RE_SIMPLE_LIST.findall(full_text):
        lines = [
            l.rstrip()
            for l in block.strip().splitlines()
            if l.strip() and not _is_toc_line(l)
        ]
        if len(lines) < 3:
            continue
        violations = sum(1 for l in lines[:-1] if not l.endswith(";"))
        if not lines[-1].endswith("."):
            violations += 1
        if violations:
            errors.append(_make_error(
                "minor", None,
                "Нарушено оформление простого списка.",
                "Промежуточные элементы — «;», последний элемент — «.».",
                f"Нарушено в {violations} из {len(lines)} элементов.",
                "Проверьте знаки препинания в конце пунктов списка.",
                "Простой список", "warning",
            ))
    return errors


def _check_numbered_lists(full_text: str) -> list:
    """Знаки препинания в нумерованных списках."""
    errors = []
    for block in _RE_NUMBERED_LIST.findall(full_text):
        lines = [
            l.rstrip()
            for l in block.strip().splitlines()
            if l.strip() and not _is_toc_line(l)
        ]
        if len(lines) < 3:
            continue
        stripped     = [re.sub(r"^\s*\d+[\.\)]?\s+", "", l) for l in lines]
        upper_count  = sum(1 for s in stripped if s and s == s.upper())
        if upper_count > len(lines) / 2:
            continue  # скорее всего оглавление или заголовки разделов
        violations = sum(1 for l in lines if not l.rstrip().endswith("."))
        if violations:
            errors.append(_make_error(
                "minor", None,
                "Нарушено оформление нумерованного списка.",
                "Каждый элемент нумерованного списка должен заканчиваться точкой.",
                f"Нарушено в {violations} из {len(lines)} элементах.",
                "Поставьте точку в конце каждого пункта.",
                "Нумерованный список", "warning",
            ))
    return errors


def _check_figures(full_text: str) -> list:
    """Каждый рисунок с подписью должен иметь ссылку в тексте.

    Логика: номер рисунка появляется в подписи («Рисунок 2.2 — ...»)
    и должен встретиться ещё хотя бы раз в виде любой формы слова
    «рисунок» или «рис.» перед тем же номером.
    """
    caption_nums = {_norm_num(m) for m in _RE_FIG_CAPTION.findall(full_text)}
    if not caption_nums:
        return []

    occurrence_count = Counter(_norm_num(m) for m in _RE_FIG_ANY.findall(full_text))

    # Считаем ссылку найденной, если число встречается ≥ 2 раз
    # (1 раз — сама подпись, 2+ раза — есть хотя бы одна ссылка в тексте)
    missing = {n for n in caption_nums if occurrence_count.get(n, 0) < 2}
    if not missing:
        return []

    def _sort_key(n):
        return [int(p) for p in n.split(".") if p.isdigit()]

    nums = ", ".join(sorted(missing, key=_sort_key))
    return [_make_error(
        "minor", None,
        f"Для рисунков {nums} нет ссылки в тексте.",
        "На каждый рисунок должна быть ссылка в тексте работы.",
        f"Не найдены ссылки: {nums}.",
        "Добавьте ссылку «см. рис. N» или «рисунок N» в текст.",
        "Ссылки на рисунки", "warning",
    )]


def _check_tables(full_text: str) -> list:
    """Каждая таблица с подписью должна иметь ссылку в тексте."""
    caption_nums = {_norm_num(m) for m in _RE_TBL_CAPTION.findall(full_text)}
    if not caption_nums:
        return []

    occurrence_count = Counter(_norm_num(m) for m in _RE_TBL_REF.findall(full_text))

    missing = {n for n in caption_nums if occurrence_count.get(n, 0) < 2}
    if not missing:
        return []

    def _sort_key(n):
        return [int(p) for p in n.split(".") if p.isdigit()]

    nums = ", ".join(sorted(missing, key=_sort_key))
    return [_make_error(
        "minor", None,
        f"Для таблиц {nums} нет ссылки в тексте.",
        "На каждую таблицу должна быть ссылка в тексте работы.",
        f"Не найдены ссылки: {nums}.",
        "Добавьте ссылку «см. таблицу N» или «таблица N» в текст.",
        "Ссылки на таблицы", "warning",
    )]


def _check_student_id(full_text: str) -> list:
    """Проверяет код специальности через кэшированный реестр БГУИР."""
    m = _RE_BSUIR_ID.search(full_text) or _RE_BSUIR_FB.search(full_text)
    if not m:
        return []
    specialty_raw  = m.group(1).strip()
    specialty_code = _normalize_code(specialty_raw)
    known          = _get_known_specialties()
    if known is None:
        return []   # API недоступен — не блокируем
    if specialty_code not in known:
        return [_make_error(
            "warning", 1,
            f"Код специальности «{specialty_raw}» не найден в реестре БГУИР.",
            "Код специальности должен существовать в реестре БГУИР.",
            f"Найден код: {specialty_raw}.",
            "Проверьте код специальности на титульном листе.",
            "Страница 1, обозначение работы", "warning",
        )]
    return []


def _check_minsk_year(first_page_text: str) -> list:
    m = _RE_MINSK_YEAR.search(first_page_text)
    if not m:
        return [_make_error(
            "minor", 1,
            "На титульном листе не найден год рядом со словом «Минск».",
            "Должна быть строка вида «Минск 2026».",
            "Не обнаружено.",
            "Добавьте строку «Минск <год>» на титульный лист.",
            "Страница 1", "warning",
        )]
    year = int(m.group(1) or m.group(2))
    if abs(year - datetime.now().year) > 1:
        return [_make_error(
            "minor", 1,
            f"На титульном листе указан год {year}.",
            f"Ожидается {datetime.now().year} (допустимо ±1).",
            f"Год: {year}.",
            f"Исправьте год на {datetime.now().year}.",
            "Страница 1", "warning",
        )]
    return []


def _check_margins(page, page_num: int) -> list:
    """Проверяет поля страницы по основному тексту с фильтрацией выбросов.

    Использует 2-й/98-й процентиль вместо min/max, чтобы единичные
    декорации или выступающие элементы не давали ложных срабатываний.
    Колонтитулы исключаются через _body_chars().
    """
    all_chars = [c for c in (page.chars or []) if c.get("text", "").strip()]
    if not all_chars:
        return []

    chars = _body_chars(all_chars)
    if len(chars) < 5:
        return []   # слишком мало символов основного текста — не оцениваем

    xs   = [c["x0"]     for c in chars]
    x1s  = [c["x1"]     for c in chars]
    ys   = [c["top"]    for c in chars]
    y1s  = [c["bottom"] for c in chars]

    left   = _percentile(xs,   2)
    right  = _percentile(x1s, 98)
    top    = _percentile(ys,   2)
    bottom = _percentile(y1s, 98)

    errors = []
    if left < MARGIN_LEFT - MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor", page_num,
            "Нарушено левое поле страницы.",
            "Левое поле должно быть 3 см.",
            f"Текст начинается с x={left:.0f}pt, норма ≥{MARGIN_LEFT:.0f}pt.",
            r"Проверьте \geometry{left=3cm} или поле в Word.",
            f"Страница {page_num}", "warning",
        ))
    if right > MARGIN_RIGHT + MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor", page_num,
            "Нарушено правое поле страницы.",
            "Правое поле должно быть 1.5 см.",
            f"Текст уходит до x={right:.0f}pt, норма ≤{MARGIN_RIGHT:.0f}pt.",
            r"Проверьте \geometry{right=1.5cm}.",
            f"Страница {page_num}", "warning",
        ))
    if top < MARGIN_TOP - MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor", page_num,
            "Нарушено верхнее поле страницы.",
            "Верхнее поле должно быть 2 см.",
            f"Текст начинается с y={top:.0f}pt, норма ≥{MARGIN_TOP:.0f}pt.",
            r"Проверьте \geometry{top=2cm}.",
            f"Страница {page_num}", "warning",
        ))
    if bottom > MARGIN_BOTTOM + MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor", page_num,
            "Нарушено нижнее поле страницы.",
            "Нижнее поле должно быть 2.7 см.",
            f"Текст уходит до y={bottom:.0f}pt, норма ≤{MARGIN_BOTTOM:.0f}pt.",
            r"Проверьте \geometry{bottom=2.7cm}.",
            f"Страница {page_num}", "warning",
        ))
    return errors


def _check_line_spacing(body_chars: list, dominant_size: float) -> list:
    """Мягкая проверка межстрочного интервала.

    Для LaTeX с \\linespread{1.5} и 14pt шрифтом типичный baseline-gap ≈ 25pt,
    что даёт ratio ≈ 1.82 — хорошо укладывается в допустимый диапазон [1.1, 2.2].
    """
    if not body_chars or dominant_size < 8:
        return []

    # Группируем символы в строки по близким значениям top
    lines: dict[int, int] = defaultdict(int)
    for c in body_chars:
        if abs(c.get("size", 0) - dominant_size) <= 1.5:
            lines[round(c.get("top", 0))] += 1

    baselines = sorted(lines)
    if len(baselines) < 8:
        return []

    gaps = [baselines[i + 1] - baselines[i] for i in range(len(baselines) - 1)]

    # Оставляем только «одинарные» межстрочные промежутки
    lo = dominant_size * 1.1
    hi = dominant_size * 2.5
    plausible = sorted(g for g in gaps if lo <= g <= hi)
    if len(plausible) < 5:
        return []

    median_gap = plausible[len(plausible) // 2]
    ratio      = median_gap / dominant_size

    if 1.1 <= ratio <= 2.2:
        return []

    return [_make_error(
        "minor", None,
        f"Межстрочный интервал (~{ratio:.2f}×) отличается от рекомендованного 1.5.",
        "Основной текст должен быть набран с полуторным межстрочным интервалом.",
        f"Медианный gap: {median_gap:.1f}pt при размере шрифта {dominant_size:.1f}pt.",
        r"Установите \linespread{1.5} в LaTeX или интервал 1.5 в Word.",
        "Межстрочный интервал", "warning",
    )]


def _check_paragraph_indent(body_chars: list, dominant_size: float) -> list:
    """Мягкая проверка наличия красной строки.

    Срабатывает только если в документе нет ни одной строки с отступом —
    то есть у всех абзацев отступ полностью отсутствует.
    """
    if not body_chars or dominant_size < 8:
        return []

    by_line: dict[int, list] = defaultdict(list)
    for c in body_chars:
        if abs(c.get("size", 0) - dominant_size) <= 1.5:
            by_line[round(c.get("top", 0))].append(c.get("x0", 0.0))

    if len(by_line) < 10:
        return []

    line_starts = [min(xs) for xs in by_line.values()]
    total       = len(line_starts)

    # Отступ считается валидным, если первый символ строки правее MARGIN_LEFT на 15–60pt
    indent_min = MARGIN_LEFT + 15
    indent_max = MARGIN_LEFT + 60
    indented   = sum(1 for x in line_starts if indent_min <= x <= indent_max)
    flush      = sum(1 for x in line_starts if abs(x - MARGIN_LEFT) < 5)

    # Предупреждаем только если отступов совсем нет
    if indented < total * 0.03 and flush > total * 0.4:
        return [_make_error(
            "minor", None,
            "Не обнаружены красные строки (отступ первого абзаца).",
            "Первая строка каждого абзаца должна иметь отступ 1.25 см.",
            f"Строк с отступом: {indented}/{total}.",
            r"Добавьте \setlength{\parindent}{1.25cm} в LaTeX или настройте отступы в Word.",
            "Абзацный отступ", "warning",
        )]
    return []


# ---------------------------------------------------------------------------
# Главная функция
# ---------------------------------------------------------------------------

def analyze_pdf(path: str) -> dict:
    try:
        with pdfplumber.open(path) as pdf:
            if not pdf.pages:
                return {"status": "FAIL", "errors": [_make_error(
                    "critical", None, "Документ пустой.",
                    "PDF должен содержать страницы с текстовым слоем.",
                    "Страниц не найдено.",
                    "Экспортируйте работу в PDF повторно.",
                    "Документ", "critical",
                )]}

            total_pages = len(pdf.pages)

            # ── Единственный проход по страницам ─────────────────────────
            text_parts:          list[str] = []
            first_page_text:     str       = ""
            page_margin_errors:  list      = []
            pages_with_size_err: list[int] = []
            pages_with_font_err: list[int] = []
            pages_with_page_num: int       = 0

            # Счётчики для доминирующих размера/шрифта по документу
            size_counter: Counter = Counter()
            font_counter: Counter = Counter()

            # Символы основного текста для проверок интервала и отступов
            all_body_chars: list = []

            for page_idx, page in enumerate(pdf.pages, start=1):
                chars      = page.chars or []
                body       = _body_chars(chars)
                page_text  = page.extract_text() or ""

                text_parts.append(page_text)
                if page_idx == 1:
                    first_page_text = page_text

                page_margin_errors.extend(_check_margins(page, page_idx))

                all_body_chars.extend(body)

                # Шрифт и размер проверяем начиная со страницы 3
                # (титул и оглавление имеют другие размеры — не являются нормой тела)
                if page_idx > 2:
                    effective = body if len(body) >= 5 else chars
                    psize     = _dominant_size(effective)
                    pfont     = _dominant_font(effective)

                    if psize:
                        size_counter[psize] += 1
                    if pfont:
                        font_counter[pfont] += 1

                    if psize and not (FONT_SIZE_MIN <= psize <= FONT_SIZE_MAX):
                        pages_with_size_err.append(page_idx)
                    if pfont and not _is_allowed_font(pfont):
                        pages_with_font_err.append(page_idx)

                # Нумерация страниц: проверяем начиная с 3-й
                if page_idx > 2 and chars:
                    if _detect_page_number(chars):
                        pages_with_page_num += 1

            if not any(text_parts):
                return {"status": "FAIL", "errors": [_make_error(
                    "critical", None, "Документ не содержит текста.",
                    "PDF должен иметь текстовый слой для автоматической проверки.",
                    "Текстовый слой не обнаружен — возможно, это скан.",
                    "Экспортируйте документ как текстовый PDF.",
                    "Документ", "critical",
                )]}

            # Строим full_text через join — O(n), а не O(n²)
            full_text  = "\n".join(text_parts)
            clean_text = _clean_text(full_text)

            dominant_size = (
                size_counter.most_common(1)[0][0] if size_counter else 0.0
            )
            dominant_font = (
                font_counter.most_common(1)[0][0] if font_counter else ""
            )
            doc_type = _detect_document_type(first_page_text)

            print(
                f"[ANALYZER] Тип: {doc_type} | Стр: {total_pages} | "
                f"Шрифт: {dominant_font} | Размер: {dominant_size}pt"
            )

            errors: list = []

            # 1. Обязательные разделы
            errors.extend(_check_structure(clean_text))

            # 2. Нумерация разделов
            errors.extend(_check_section_numbering(clean_text))

            # 3. Размер основного шрифта
            if pages_with_size_err:
                pct      = len(pages_with_size_err) / total_pages * 100
                severity = "major" if pct > FONT_SIZE_MAJOR_THRESHOLD_PCT else "minor"
                p_range  = _pages_to_range(pages_with_size_err)
                errors.append(_make_error(
                    severity, p_range,
                    "Размер основного шрифта вне допустимого диапазона.",
                    f"Допустимый диапазон: {FONT_SIZE_MIN:g}–{FONT_SIZE_MAX:g} pt.",
                    (
                        f"Доминирующий размер: {dominant_size}pt; "
                        f"затронуто {len(pages_with_size_err)} стр. ({pct:.0f}%)."
                    ),
                    "Измените размер основного текста на 14 pt.",
                    f"Страницы: {p_range}", severity,
                ))

            # 4. Семейство шрифта (только предупреждение, не блокирует)
            if pages_with_font_err:
                pct     = len(pages_with_font_err) / total_pages
                p_range = _pages_to_range(pages_with_font_err)
                errors.append(_make_error(
                    "warning", p_range,
                    "Обнаружен нестандартный шрифт.",
                    "Рекомендуется Times New Roman 14 pt (или Computer Modern для LaTeX).",
                    (
                        f"Шрифт «{dominant_font}» на "
                        f"{len(pages_with_font_err)} стр. ({pct:.0%})."
                    ),
                    "Убедитесь, что шрифт встроен и соответствует требованиям кафедры.",
                    f"Страницы: {p_range}", "warning",
                ))

            # 5. Поля страниц — группируем если нарушений много
            if page_margin_errors:
                m_pages = sorted({e["page"] for e in page_margin_errors if e["page"]})
                if len(m_pages) <= 3:
                    errors.extend(page_margin_errors)
                else:
                    p_range = _pages_to_range(m_pages)
                    errors.append(_make_error(
                        "minor", p_range,
                        "Нарушены поля страниц.",
                        "Нормы: левое 3 см, правое 1.5 см, верхнее 2 см, нижнее 2.7 см.",
                        f"Нарушение на {len(m_pages)} страницах.",
                        r"Проверьте \geometry{left=3cm,right=1.5cm,top=2cm,bottom=2.7cm}.",
                        f"Страницы: {p_range}", "warning",
                    ))

            # 6. Нумерация страниц
            checkable = max(0, total_pages - 2)
            if (
                checkable > PAGE_NUMBER_MIN_CHECKABLE_PAGES
                and pages_with_page_num < checkable * PAGE_NUMBER_MIN_COVERAGE
            ):
                errors.append(_make_error(
                    "minor", None,
                    "Номера страниц не обнаружены.",
                    "Нумерация должна быть в нижней части страниц начиная со стр. 3.",
                    f"Найдено на {pages_with_page_num} из {checkable} проверяемых страниц.",
                    "Проверьте настройки колонтитулов.",
                    "Нижний колонтитул страниц 3+", "warning",
                ))

            # 7. Межстрочный интервал
            errors.extend(_check_line_spacing(all_body_chars, dominant_size))

            # 8. Абзацный отступ (красная строка)
            errors.extend(_check_paragraph_indent(all_body_chars, dominant_size))

            # 9. Количество источников
            errors.extend(_check_references_count(clean_text))

            # 10. Формат библиографии
            errors.extend(_check_bibliography_format(clean_text))

            # 11. Оформление простых списков
            errors.extend(_check_simple_lists(clean_text))

            # 12. Оформление нумерованных списков
            errors.extend(_check_numbered_lists(clean_text))

            # 13. Ссылки на рисунки
            errors.extend(_check_figures(clean_text))

            # 14. Ссылки на таблицы
            errors.extend(_check_tables(clean_text))

            # 15. Код специальности (использует кэш)
            errors.extend(_check_student_id(clean_text))

            # 16. Год на титульном листе
            errors.extend(_check_minsk_year(first_page_text))

            has_blocking = any(e["severity"] in ("critical", "major") for e in errors)
            status       = "FAIL" if has_blocking else "SUCCESS"
            print(f"[ANALYZER] {path}: {status}, ошибок: {len(errors)}")
            return {"status": status, "errors": errors}

    except FileNotFoundError:
        return {"status": "FAIL", "errors": [_make_error(
            "critical", None, f"Файл не найден: {path}",
            "Файл должен быть доступен анализатору по переданному пути.",
            f"Путь: {path}.",
            "Повторите загрузку файла.",
            "Файловое хранилище", "critical",
        )]}
    except Exception as exc:
        return {"status": "FAIL", "errors": [_make_error(
            "critical", None, f"Ошибка при анализе: {exc}",
            "Анализатор должен корректно обработать PDF.",
            str(exc),
            "Проверьте, что PDF не повреждён, и экспортируйте заново.",
            "Анализ PDF", "critical",
        )]}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Использование: python analyzer.py <путь_к_pdf>")
        sys.exit(1)
    result = analyze_pdf(sys.argv[1])
    print(json.dumps(result, ensure_ascii=False, indent=2))
