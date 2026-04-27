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
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime
from typing import Optional

import pdfplumber
import requests

from check_config import (
    ALLOWED_FONT_FAMILIES,
    BSUIR_EMPLOYEES_ALL_URL,
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
    r"(?:с|c)писок\s+(?:(?:использованн(?:ых|ой)|используемых)\s+(?:источников|литературы)|литературы)",
    re.IGNORECASE,
)
_RE_REF_ENTRY_A = re.compile(r"^\s*\[?\d+\]?[\.\)]?\s+\S", re.MULTILINE)
_RE_REF_ENTRY_B = re.compile(r"^\s*\d+\s+\S", re.MULTILINE)
_RE_REF_ENTRY_INLINE = re.compile(r"(?=(?:^|\s)(?:\[\d{1,3}\]|\d{1,3}[\.\)])\s+\S)")
_RE_REF_YEAR    = re.compile(r"\b(19|20)\d{2}\b")
_RE_REF_URL     = re.compile(r"https?://")
_RE_AFTER_REFS_HEADING = re.compile(r"^\s*(?:приложени[ея]|appendix)\b", re.IGNORECASE | re.MULTILINE)

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
_RE_TITLE_YEAR = re.compile(r"\b(20\d{2})\b")
_RE_BSUIR_ID  = re.compile(
    r"БГУИР\s+КП[3-7]\s+((?:\d[\d\s\-]{3,40}\d))\s+(\d{3})\s+ПЗ\b"
)
_RE_DEPARTMENT_HEAD_LINE = re.compile(
    r"(?:заведующ(?:ий|ая|его|ей)\s+кафедр(?:ой|ы)|зав\.\s*кафедр(?:ой|ы)?|завкафедр(?:ой|ы)?)",
    re.IGNORECASE,
)
_RE_REVIEWER_LINE = re.compile(
    r"(?:провер(?:ил|ила|яющий|яющая|яющего|яющей)|руководител(?:ь|я|ем)|преподавател(?:ь|я|ем))",
    re.IGNORECASE,
)
_RE_PERSON_SURNAME_INITIALS = re.compile(r"\b[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?\s+[А-ЯЁ]\.\s*[А-ЯЁ]\.")
_RE_PERSON_INITIALS_SURNAME = re.compile(r"\b[А-ЯЁ]\.\s*[А-ЯЁ]\.\s*[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?")
_RE_PERSON_FULL_NAME = re.compile(r"\b[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?\s+[А-ЯЁ][а-яё]+\s+[А-ЯЁ][а-яё]+")
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
_employees_cache: Optional[frozenset]   = None
_employees_cache_ts: float              = 0.0
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


def _employee_name_variants(employee: dict) -> set:
    variants = set()

    fio = employee.get("fio")
    if fio:
        variants.add(_normalize_person_name(fio))

    last_name = employee.get("lastName") or employee.get("last_name")
    first_name = employee.get("firstName") or employee.get("first_name")
    middle_name = employee.get("middleName") or employee.get("middle_name")
    if last_name and first_name:
        first_initial = str(first_name).strip()[:1]
        middle_initial = str(middle_name).strip()[:1] if middle_name else ""
        variants.add(_normalize_person_name(f"{last_name} {first_initial}.{middle_initial}."))
        variants.add(_normalize_person_name(f"{first_initial}.{middle_initial}. {last_name}"))
        if middle_name:
            variants.add(_normalize_person_name(f"{last_name} {first_name} {middle_name}"))

    return {v for v in variants if v}


def _get_known_employee_names() -> Optional[frozenset]:
    global _employees_cache, _employees_cache_ts
    now = time.monotonic()
    if _employees_cache is not None and (now - _employees_cache_ts) < _SPECIALTIES_TTL:
        return _employees_cache
    try:
        resp = requests.get(BSUIR_EMPLOYEES_ALL_URL, timeout=8)
        if resp.status_code == 200:
            payload = resp.json()
            employees = payload if isinstance(payload, list) else payload.get("employees", [])
            names = set()
            for employee in employees:
                if isinstance(employee, dict):
                    names.update(_employee_name_variants(employee))
            _employees_cache = frozenset(names)
            _employees_cache_ts = now
            return _employees_cache
    except Exception as exc:
        print(f"[ANALYZER] BSUIR employees API недоступен: {exc}")
    return _employees_cache  # устаревший кэш или None


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
    context: str = "",
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
        "context":  context,
    }


def _extract_context(text: str, pattern: re.Pattern, max_len: int = 120) -> str:
    """Извлекает ~max_len символов вокруг первого совпадения паттерна в тексте.

    Возвращает строку вида «...предшествующий текст [СОВПАДЕНИЕ] следующий...»
    """
    m = pattern.search(text)
    if not m:
        return ""
    start = max(0, m.start() - 40)
    end   = min(len(text), m.end() + 60)
    snippet = text[start:end].strip().replace("\n", " ")
    if start > 0:
        snippet = "..." + snippet
    if end < len(text):
        snippet = snippet + "..."
    return snippet[:max_len]


# Таблица замены: визуально схожие латинские символы → кириллические.
# pdfplumber при парсинге LaTeX иногда возвращает latin C вместо кирилл. С и т.п.
_LATIN_TO_CYR = str.maketrans({
    'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е', 'H': 'Н', 'I': 'І',
    'K': 'К', 'M': 'М', 'O': 'О', 'P': 'Р', 'T': 'Т', 'X': 'Х',
    'a': 'а', 'c': 'с', 'e': 'е', 'i': 'і', 'o': 'о', 'p': 'р',
    'x': 'х', 'y': 'у',
})


def _fix_lookalikes(text: str) -> str:
    """Заменяет Latin-буквы, визуально идентичные кириллическим, на настоящие
    кириллические.  Применяется только к словам, которые целиком написаны
    кириллицей ± эти «ложные» латиницы (иначе обычный Latin текст не трогаем).
    """
    # Заменяем только в словах, где все символы кириллические или похожие Latin
    _CYR_OR_LOOKALIKE = re.compile(
        r"[А-ЯЁа-яёACBEHIKMOPTXaceiopyxАВСЕНІКМОРТХасеіорх]+"
    )
    def _replace(m):
        word = m.group()
        # Если в слове есть хотя бы одна настоящая кириллица — заменяем lookalikes
        if re.search(r'[А-ЯЁа-яё]', word):
            return word.translate(_LATIN_TO_CYR)
        return word
    return _CYR_OR_LOOKALIKE.sub(_replace, text)


def _clean_text(raw: str) -> str:
    """Нормализует текст, извлечённый pdfplumber из LaTeX-PDF:
    1. Unicode NFC нормализация
    2. Замена lookalike латинских символов на кириллицу
    3. Удаление мягких переносов
    4. Объединение слов с жёстким дефисом-переносом
    """
    text = unicodedata.normalize("NFC", raw)
    text = _fix_lookalikes(text)
    text = _RE_SOFT_HYPHEN.sub("", text)
    text = _RE_HARD_BREAK.sub("", text)
    return text


def _normalize_code(s: str) -> str:
    return re.sub(r"[\s\-]", "", (s or "")).lower()


def _normalize_person_name(s: str) -> str:
    text = _fix_lookalikes(s or "")
    text = text.replace("ё", "е").replace("Ё", "Е")
    text = re.sub(r"[^А-Яа-я.\-\s]", " ", text)
    text = re.sub(r"\s+", " ", text).strip().lower()
    return text


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


def _font_key(font_name: str) -> str:
    """Нормализует имя шрифта для сравнения участков одного документа."""
    if not font_name:
        return ""
    base = font_name.split("+", 1)[-1]
    normalized = _RE_NON_ALNUM.sub("", base.lower())
    for suffix in ("regular", "roman", "normal", "psmt", "mt", "bold", "italic", "oblique"):
        normalized = normalized.replace(suffix, "")
    return normalized


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


def _extract_reference_section(full_text: str) -> str:
    """Возвращает текст фактического раздела источников, без оглавления."""
    matches = list(_RE_REF_HEADING.finditer(full_text))
    if not matches:
        return ""

    section = full_text[matches[-1].end():]
    next_heading = _RE_AFTER_REFS_HEADING.search(section)
    if next_heading:
        section = section[:next_heading.start()]
    return section


def _extract_reference_entries(full_text: str) -> list[str]:
    """Собирает источники целиком, включая строки-продолжения."""
    section = _extract_reference_section(full_text)
    if not section:
        return []

    entries: list[str] = []
    current: list[str] = []
    for raw_line in section.splitlines():
        line = raw_line.strip()
        if not line or _is_toc_line(line):
            continue

        if _RE_REF_ENTRY_A.match(line) or _RE_REF_ENTRY_B.match(line):
            if current:
                entries.append(" ".join(current))
            current = [line]
        elif current:
            current.append(line)

    if current:
        entries.append(" ".join(current))

    # Некоторые PDF извлекаются одной длинной строкой: пробуем разделить по маркерам.
    if len(entries) <= 1:
        one_line = re.sub(r"\s+", " ", section).strip()
        split_entries = [
            part.strip()
            for part in _RE_REF_ENTRY_INLINE.split(one_line)
            if part.strip() and (_RE_REF_ENTRY_A.match(part) or _RE_REF_ENTRY_B.match(part))
        ]
        if len(split_entries) > len(entries):
            entries = split_entries

    return entries


def _find_reference_start_page(page_texts: list[str]) -> Optional[int]:
    """Находит страницу реального списка источников; первое вхождение часто в оглавлении."""
    start_page = None
    for page_num, page_text in enumerate(page_texts, start=1):
        if _RE_REF_HEADING.search(_clean_text(page_text or "")):
            start_page = page_num
    return start_page


def _check_reference_font(reference_chars: list, main_font: str, pages: list[int]) -> list:
    if not reference_chars or not main_font:
        return []

    ref_font = _dominant_font(reference_chars)
    if not ref_font or _font_key(ref_font) == _font_key(main_font):
        return []

    p_range = _pages_to_range(pages)
    return [_make_error(
        "minor", p_range,
        "Шрифт списка использованных источников отличается от основного текста.",
        "Список источников должен быть оформлен тем же основным шрифтом, что и документ.",
        f"Основной шрифт: «{main_font}»; в списке источников: «{ref_font}».",
        "Приведите список источников к общему стилю документа.",
        f"Страницы: {p_range}", "warning",
    )]


def _check_references_count(full_text: str) -> list:
    errors  = []
    entries = _extract_reference_entries(full_text)
    if not entries and not _extract_reference_section(full_text):
        return errors
    count = len(entries)
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
    entries = _extract_reference_entries(full_text)
    if not entries:
        return []
    suspicious = [
        entry for entry in entries
        if not _RE_REF_YEAR.search(entry) and not _RE_REF_URL.search(entry)
    ]
    if not suspicious:
        return []
    snippet = " | ".join(s[:60] for s in suspicious[:2])
    return [_make_error(
        "minor", None,
        f"{len(suspicious)} источник(ов) не содержат года или URL.",
        "Каждый источник должен содержать год публикации или гиперссылку.",
        f"Подозрительных строк: {len(suspicious)}.",
        "Оформите источники по ГОСТ 7.0.5-2008.",
        "Список использованных источников", "warning",
        context=snippet,
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
            snippet = " / ".join(l.strip()[:50] for l in lines[:3])
            errors.append(_make_error(
                "minor", None,
                "Нарушено оформление простого списка.",
                "Промежуточные элементы — «;», последний элемент — «.».",
                f"Нарушено в {violations} из {len(lines)} элементов.",
                "Проверьте знаки препинания в конце пунктов списка.",
                "Простой список", "warning",
                context=snippet,
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
            snippet = " / ".join(l.strip()[:50] for l in lines[:3])
            errors.append(_make_error(
                "minor", None,
                "Нарушено оформление нумерованного списка.",
                "Каждый элемент нумерованного списка должен заканчиваться точкой.",
                f"Нарушено в {violations} из {len(lines)} элементах.",
                "Поставьте точку в конце каждого пункта.",
                "Нумерованный список", "warning",
                context=snippet,
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
    # Показываем подпись первого «беспризорного» рисунка как контекст
    first_miss = sorted(missing, key=_sort_key)[0]
    ctx_pat    = re.compile(r"рисунок\s*" + re.escape(first_miss), re.IGNORECASE)
    ctx        = _extract_context(full_text, ctx_pat)
    return [_make_error(
        "minor", None,
        f"Для рисунков {nums} нет ссылки в тексте.",
        "На каждый рисунок должна быть ссылка в тексте работы.",
        f"Не найдены ссылки: {nums}.",
        "Добавьте ссылку «см. рис. N» или «рисунок N» в текст.",
        "Ссылки на рисунки", "warning",
        context=ctx,
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
        ctx = _extract_context(full_text, _RE_BSUIR_ID if _RE_BSUIR_ID.search(full_text) else _RE_BSUIR_FB)
        return [_make_error(
            "critical", 1,
            f"Код специальности «{specialty_raw}» не найден в реестре БГУИР.",
            "Код специальности должен существовать в реестре БГУИР.",
            f"Найден код: {specialty_raw}.",
            "Проверьте код специальности на титульном листе.",
            "Страница 1, обозначение работы", "critical",
            context=ctx,
        )]
    return []


def _check_minsk_year(first_page_text: str) -> list:
    current_year = datetime.now().year
    m = _RE_MINSK_YEAR.search(first_page_text)
    if not m:
        return [_make_error(
            "major", 1,
            "На титульном листе не найден год рядом со словом «Минск».",
            f"Должна быть строка вида «Минск {current_year}».",
            "Не обнаружено.",
            f"Добавьте строку «Минск {current_year}» на титульный лист.",
            "Страница 1", "major",
        )]
    year = int(m.group(1) or m.group(2))
    if year != current_year:
        return [_make_error(
            "major", 1,
            f"На титульном листе указан год {year}, но сейчас {current_year}.",
            "Год на титульном листе должен совпадать с текущим системным годом.",
            f"Год: {year}.",
            f"Исправьте год на {current_year}.",
            "Страница 1", "major",
            context=_extract_context(first_page_text, _RE_MINSK_YEAR),
        )]
    title_years = {int(y) for y in _RE_TITLE_YEAR.findall(first_page_text)}
    mismatched_years = sorted(y for y in title_years if y != year)
    if mismatched_years:
        return [_make_error(
            "major", 1,
            "На титульном листе указаны разные годы.",
            "Все годы на титульном листе должны совпадать со строкой «Минск <год>».",
            f"Минск: {year}; другие годы: {', '.join(map(str, mismatched_years))}.",
            f"Приведите все годы на титульном листе к {year}.",
            "Страница 1", "major",
            context=_extract_context(first_page_text, _RE_TITLE_YEAR),
        )]
    return []


def _extract_person_near_label(first_page_text: str, label_re: re.Pattern) -> Optional[str]:
    lines = [line.strip() for line in first_page_text.splitlines()]
    for idx, line in enumerate(lines):
        if not label_re.search(line):
            continue

        candidates = [line]
        candidates.extend(lines[idx + offset] for offset in range(1, 3) if idx + offset < len(lines))
        for candidate in candidates:
            for pattern in (_RE_PERSON_FULL_NAME, _RE_PERSON_SURNAME_INITIALS, _RE_PERSON_INITIALS_SURNAME):
                match = pattern.search(candidate)
                if match:
                    return match.group(0)
    return None


def _check_title_employee(first_page_text: str, label_re: re.Pattern, role_name: str, role_name_genitive: str) -> list:
    if not label_re.search(first_page_text):
        return [_make_error(
            "major", 1,
            f"На титульном листе не найден {role_name}.",
            f"На титульном листе должен быть указан {role_name}.",
            f"Не обнаружена строка с ролью «{role_name}».",
            f"Добавьте ФИО {role_name_genitive} на титульный лист.",
            "Страница 1", "major",
        )]

    employee_name = _extract_person_near_label(first_page_text, label_re)
    if not employee_name:
        return [_make_error(
            "major", 1,
            f"Не удалось распознать ФИО {role_name_genitive}.",
            f"ФИО {role_name_genitive} должно быть указано в формате «Фамилия И.О.» или «И.О. Фамилия».",
            f"Строка с ролью «{role_name}» найдена, ФИО не распознано.",
            f"Проверьте написание ФИО {role_name_genitive}.",
            "Страница 1", "major",
            context=_extract_context(first_page_text, label_re),
        )]

    known_names = _get_known_employee_names()
    if known_names is None:
        return []  # IIS недоступен — не блокируем проверку документа.

    normalized = _normalize_person_name(employee_name)
    if normalized not in known_names:
        return [_make_error(
            "major", 1,
            f"{role_name.capitalize()} «{employee_name}» не найден в IIS БГУИР.",
            f"ФИО {role_name_genitive} должно соответствовать преподавателю из IIS БГУИР.",
            f"Найдено ФИО: {employee_name}.",
            f"Проверьте ФИО {role_name_genitive} на титульном листе.",
            "Страница 1", "major",
            context=_extract_context(first_page_text, label_re),
        )]
    return []


def _check_department_head(first_page_text: str) -> list:
    return _check_title_employee(
        first_page_text,
        _RE_DEPARTMENT_HEAD_LINE,
        "заведующий кафедрой",
        "заведующего кафедрой",
    )


def _check_reviewer(first_page_text: str) -> list:
    return _check_title_employee(
        first_page_text,
        _RE_REVIEWER_LINE,
        "проверяющий",
        "проверяющего",
    )


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
            page_font_metrics:   list[dict] = []
            body_chars_by_page:  list[tuple[int, list]] = []

            # Счётчики для доминирующих размера/шрифта по документу
            size_counter: Counter = Counter()
            font_counter: Counter = Counter()

            for page_idx, page in enumerate(pdf.pages, start=1):
                chars      = page.chars or []
                body       = _body_chars(chars)
                page_text  = page.extract_text() or ""

                text_parts.append(page_text)
                if page_idx == 1:
                    first_page_text = page_text

                page_margin_errors.extend(_check_margins(page, page_idx))

                body_chars_by_page.append((page_idx, body))

                # Шрифт и размер проверяем начиная со страницы 3
                # (титул и оглавление имеют другие размеры — не являются нормой тела)
                if page_idx > 2:
                    effective = body if len(body) >= 5 else chars
                    psize     = _dominant_size(effective)
                    pfont     = _dominant_font(effective)
                    page_font_metrics.append({
                        "page": page_idx,
                        "size": psize,
                        "font": pfont,
                    })

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

            reference_start_page = _find_reference_start_page(text_parts)
            reference_pages = (
                set(range(reference_start_page, total_pages + 1))
                if reference_start_page else set()
            )

            main_metrics = [
                m for m in page_font_metrics
                if m["page"] not in reference_pages
            ] or page_font_metrics
            reference_metrics = [
                m for m in page_font_metrics
                if m["page"] in reference_pages
            ]

            for metric in main_metrics:
                psize = metric["size"]
                pfont = metric["font"]
                if psize:
                    size_counter[psize] += 1
                if pfont:
                    font_counter[pfont] += 1

                if psize and not (FONT_SIZE_MIN <= psize <= FONT_SIZE_MAX):
                    pages_with_size_err.append(metric["page"])
                if pfont and not _is_allowed_font(pfont):
                    pages_with_font_err.append(metric["page"])

            main_body_chars = [
                c
                for page_num, page_chars in body_chars_by_page
                if page_num not in reference_pages
                for c in page_chars
            ] or [
                c
                for _, page_chars in body_chars_by_page
                for c in page_chars
            ]
            reference_chars = [
                c
                for page_num, page_chars in body_chars_by_page
                if page_num in reference_pages
                for c in page_chars
            ]

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

            # 3. Размер основного шрифта
            if pages_with_size_err:
                check_pages = max(1, len(main_metrics))
                pct      = len(pages_with_size_err) / check_pages * 100
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
                check_pages = max(1, len(main_metrics))
                pct     = len(pages_with_font_err) / check_pages
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

            if reference_metrics:
                ref_pages = [m["page"] for m in reference_metrics]
                errors.extend(_check_reference_font(reference_chars, dominant_font, ref_pages))

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
            errors.extend(_check_line_spacing(main_body_chars, dominant_size))

            # 8. Абзацный отступ (красная строка)
            errors.extend(_check_paragraph_indent(main_body_chars, dominant_size))

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

            # 17. Заведующий кафедрой на титульном листе
            errors.extend(_check_department_head(first_page_text))

            # 18. Проверяющий на титульном листе
            errors.extend(_check_reviewer(first_page_text))

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
