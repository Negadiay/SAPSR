import re
import sys
import json
from collections import Counter
from datetime import datetime
import pdfplumber
import requests

# --- Константы BSUIR (из preamble.tex эталонной курсовой) ---
PAGE_WIDTH  = 595.28
PAGE_HEIGHT = 841.89

MARGIN_LEFT      = 85.0   # 3 cm
MARGIN_RIGHT     = 553.0  # page - 1.5 cm
MARGIN_TOP       = 57.0   # 2 cm
MARGIN_BOTTOM    = 765.0  # page - 2.7 cm
MARGIN_TOLERANCE = 10.0

REQUIRED_FONT_SIZE    = 14.0
FONT_SIZE_TOLERANCE   = 1.0

PAGE_NUM_X_MIN = 480.0
PAGE_NUM_Y_MIN = 790.0

BSUIR_SPECIALITIES_URL = "https://iis.bsuir.by/api/v1/specialities"

REQUIRED_SECTIONS = {
    "title_page":  {"patterns": [r"министерство", r"белорусский", r"кафедра"], "name": "Титульный лист"},
    "toc":         {"patterns": [r"содержание", r"оглавление"],                "name": "Содержание/Оглавление"},
    "intro":       {"patterns": [r"введение"],                                  "name": "Введение"},
    "conclusion":  {"patterns": [r"заключение"],                               "name": "Заключение"},
    "references":  {"patterns": [r"список использованных", r"список литературы", r"библиограф"], "name": "Список источников"},
}


# ---------- Утилиты ----------

def _dominant_size(chars):
    size_counts = Counter()
    for c in chars:
        text = c.get("text", "").strip()
        if text and text not in (" ", "\n"):
            size_counts[round(c.get("size", 0), 1)] += 1
    return size_counts.most_common(1)[0][0] if size_counts else 0.0


def _pages_to_range(pages):
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


def _normalize_code(s):
    return re.sub(r"[\s\-]", "", s).lower()


# ---------- Структурные проверки ----------

def _check_structure(full_text):
    errors = []
    for key, section in REQUIRED_SECTIONS.items():
        if not any(re.search(p, full_text, re.IGNORECASE) for p in section["patterns"]):
            errors.append({
                "severity": "critical",
                "page": None,
                "message": f"Отсутствует обязательный раздел: «{section['name']}»",
            })
    return errors


def _check_references_count(full_text):
    """Находит раздел списка источников и считает количество пронумерованных строк."""
    errors = []
    pattern = r"(?:список использованных источников|список литературы|библиографи)(.*?)(?:\n[А-ЯA-Z]{3,}|\Z)"
    match = re.search(pattern, full_text, re.IGNORECASE | re.DOTALL)
    if not match:
        return errors
    section_text = match.group(1)
    count = len(re.findall(r"^\s*\d+[\.\)]", section_text, re.MULTILINE))
    if count < 5:
        errors.append({
            "severity": "critical",
            "page": None,
            "message": f"Список источников содержит менее 5 источников (найдено: {count})",
        })
    elif count < 10:
        errors.append({
            "severity": "warning",
            "page": None,
            "message": f"Рекомендуется не менее 10 источников в списке (найдено: {count})",
        })
    return errors


def _check_simple_lists(full_text):
    """Проверяет оформление простых списков (тире + точка с запятой)."""
    errors = []
    # Находим блоки строк начинающихся с тире
    blocks = re.findall(
        r"((?:^[ \t]*[—–\-]\s+.+\n?)+)",
        full_text, re.MULTILINE
    )
    for block in blocks:
        lines = [l.rstrip() for l in block.strip().splitlines() if l.strip()]
        if len(lines) < 2:
            continue
        for i, line in enumerate(lines[:-1]):
            if not line.endswith(";"):
                errors.append({
                    "severity": "minor",
                    "page": None,
                    "message": f"Элемент простого списка должен заканчиваться «;»: «{line[:60].strip()}»",
                })
                break
        last = lines[-1]
        if not last.endswith("."):
            errors.append({
                "severity": "minor",
                "page": None,
                "message": f"Последний элемент простого списка должен заканчиваться «.»: «{last[:60].strip()}»",
            })
    return errors


def _check_numbered_lists(full_text):
    """Проверяет оформление сложных нумерованных списков."""
    errors = []
    blocks = re.findall(
        r"((?:^[ \t]*\d+\s+[А-ЯA-Z].+\n?)+)",
        full_text, re.MULTILINE
    )
    for block in blocks:
        lines = [l.rstrip() for l in block.strip().splitlines() if l.strip()]
        for line in lines:
            text = re.sub(r"^\s*\d+\s+", "", line)
            if not text[0].isupper():
                errors.append({
                    "severity": "minor",
                    "page": None,
                    "message": f"Элемент нумерованного списка должен начинаться с заглавной буквы: «{line[:60].strip()}»",
                })
                break
            if not line.rstrip().endswith("."):
                errors.append({
                    "severity": "minor",
                    "page": None,
                    "message": f"Элемент нумерованного списка должен заканчиваться «.»: «{line[:60].strip()}»",
                })
                break
    return errors


def _check_figures(full_text):
    """Проверяет наличие подписей к рисункам и ссылок на них в тексте."""
    errors = []
    caption_pattern = re.compile(r"[Рр]ис(?:унок)?\.?\s*(\d+[\.\d]*)", re.IGNORECASE)
    ref_pattern     = re.compile(r"(?:на\s+)?рис(?:унке?|\.)\s*(\d+[\.\d]*)", re.IGNORECASE)

    captions = set(caption_pattern.findall(full_text))
    refs     = set(ref_pattern.findall(full_text))

    for fig_num in captions:
        if fig_num not in refs:
            errors.append({
                "severity": "minor",
                "page": None,
                "message": f"Рисунок {fig_num} имеет подпись, но на него нет ссылки в тексте",
            })
    return errors


def _check_student_id(full_text):
    """Проверяет код специальности в студенческом номере через BSUIR API."""
    errors = []
    # Паттерн: блок цифр/тире/пробелов + пробел + 3 цифры в конце
    match = re.search(r"([\d][\d\s\-]{3,}\s+\d{3})\b", full_text)
    if not match:
        return errors

    raw = match.group(1).strip()
    parts = raw.rsplit(None, 1)
    if len(parts) < 2:
        return errors
    specialty_raw = parts[0]
    specialty_code = _normalize_code(specialty_raw)

    try:
        resp = requests.get(BSUIR_SPECIALITIES_URL, timeout=8)
        if resp.status_code != 200:
            return errors
        specialities = resp.json()
        known_codes = {_normalize_code(s.get("code", "")) for s in specialities if s.get("code")}
        if specialty_code not in known_codes:
            errors.append({
                "severity": "major",
                "page": 1,
                "message": f"Код специальности «{specialty_raw.strip()}» из студенческого номера не найден в реестре BSUIR",
            })
    except Exception as e:
        print(f"[ANALYZER] BSUIR API недоступен при проверке специальности: {e}")
    return errors


def _check_minsk_year(first_page_text):
    """Проверяет наличие текущего года рядом со словом 'Минск' на титульном листе."""
    errors = []
    match = re.search(r"минск[^\n]*?(\d{4})|(\d{4})[^\n]*?минск", first_page_text, re.IGNORECASE)
    if not match:
        errors.append({
            "severity": "minor",
            "page": 1,
            "message": "На титульном листе не найден год рядом со словом «Минск»",
        })
        return errors
    year_str = match.group(1) or match.group(2)
    year = int(year_str)
    current_year = datetime.now().year
    if abs(year - current_year) > 1:
        errors.append({
            "severity": "minor",
            "page": 1,
            "message": f"На титульном листе указан некорректный год: {year} (ожидается {current_year})",
        })
    return errors


def _check_margins(page, page_num):
    errors = []
    chars = [c for c in (page.chars or []) if c.get("text", "").strip()]
    if not chars:
        return errors
    xs  = [c["x0"]     for c in chars]
    x1s = [c["x1"]     for c in chars]
    ys  = [c["top"]    for c in chars]
    y1s = [c["bottom"] for c in chars]
    if min(xs)  < MARGIN_LEFT   - MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено левое поле (x={min(xs):.0f}pt, норма ≥{MARGIN_LEFT:.0f}pt)"})
    if max(x1s) > MARGIN_RIGHT  + MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено правое поле (x={max(x1s):.0f}pt, норма ≤{MARGIN_RIGHT:.0f}pt)"})
    if min(ys)  < MARGIN_TOP    - MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено верхнее поле (y={min(ys):.0f}pt, норма ≥{MARGIN_TOP:.0f}pt)"})
    if max(y1s) > MARGIN_BOTTOM + MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено нижнее поле (y={max(y1s):.0f}pt, норма ≤{MARGIN_BOTTOM:.0f}pt)"})
    return errors


# ---------- Главная функция ----------

def analyze_pdf(path: str) -> dict:
    try:
        with pdfplumber.open(path) as pdf:
            if not pdf.pages:
                return {"status": "FAIL", "errors": [
                    {"severity": "critical", "page": None, "message": "Документ пустой"}
                ]}

            all_chars = []
            page_margin_errors = []
            full_text = ""
            pages_with_size_err = []
            pages_with_page_num = 0
            total_pages = len(pdf.pages)
            first_page_text = ""

            for i, page in enumerate(pdf.pages, start=1):
                chars = page.chars or []
                all_chars.extend(chars)
                page_text = page.extract_text() or ""
                full_text += page_text + "\n"
                if i == 1:
                    first_page_text = page_text
                page_margin_errors.extend(_check_margins(page, i))

                psize = _dominant_size(chars)
                if psize and abs(psize - REQUIRED_FONT_SIZE) > FONT_SIZE_TOLERANCE:
                    pages_with_size_err.append(i)

                if i > 2 and chars:
                    if any(
                        c.get("text", "").strip().isdigit()
                        and c.get("x0", 0) >= PAGE_NUM_X_MIN
                        and c.get("top", 0) >= PAGE_NUM_Y_MIN
                        for c in chars
                    ):
                        pages_with_page_num += 1

            if not all_chars:
                return {"status": "FAIL", "errors": [
                    {"severity": "critical", "page": None,
                     "message": "Документ не содержит текста (возможно, отсканированный PDF)"}
                ]}

            errors = []

            # 1. Структура документа
            errors.extend(_check_structure(full_text))

            # 2. Размер шрифта
            size_err_pct = len(pages_with_size_err) / total_pages * 100 if total_pages > 0 else 0
            if pages_with_size_err:
                page_range = _pages_to_range(pages_with_size_err)
                dominant_size = _dominant_size(all_chars)
                severity = "major" if size_err_pct > 30 else "minor"
                errors.append({
                    "severity": severity,
                    "page": page_range,
                    "message": (
                        f"Нарушение размера шрифта на {len(pages_with_size_err)} стр. "
                        f"({size_err_pct:.0f}%): стр. {page_range}. "
                        f"Обнаружен {dominant_size}pt вместо 14pt."
                    ),
                })

            # 3. Поля
            if page_margin_errors:
                margin_pages = sorted(set(e["page"] for e in page_margin_errors if e["page"]))
                if len(margin_pages) <= 3:
                    errors.extend(page_margin_errors)
                else:
                    errors.append({
                        "severity": "minor",
                        "page": _pages_to_range(margin_pages),
                        "message": f"Нарушение полей на {len(margin_pages)} стр.: {_pages_to_range(margin_pages)}",
                    })

            # 4. Нумерация страниц
            checkable = total_pages - 2
            if checkable > 0 and pages_with_page_num < checkable * 0.7:
                errors.append({
                    "severity": "minor",
                    "page": None,
                    "message": f"Номера страниц отсутствуют или не на всех страницах ({pages_with_page_num}/{checkable})",
                })

            # 5. Список источников
            errors.extend(_check_references_count(full_text))

            # 6. Оформление простых списков
            errors.extend(_check_simple_lists(full_text))

            # 7. Оформление нумерованных списков
            errors.extend(_check_numbered_lists(full_text))

            # 8. Подписи к рисункам и ссылки на них
            errors.extend(_check_figures(full_text))

            # 9. Номер студента / код специальности
            errors.extend(_check_student_id(full_text))

            # 10. Год рядом с «Минск»
            errors.extend(_check_minsk_year(first_page_text))

            # Статус: FAIL если есть critical или major
            has_blocking = any(e["severity"] in ("critical", "major") for e in errors)
            status = "FAIL" if has_blocking else "SUCCESS"

            print(f"[ANALYZER] {path}: status={status}, errors={len(errors)}")
            return {"status": status, "errors": errors}

    except FileNotFoundError:
        return {"status": "FAIL", "errors": [
            {"severity": "critical", "page": None, "message": f"Файл не найден: {path}"}
        ]}
    except Exception as e:
        return {"status": "FAIL", "errors": [
            {"severity": "critical", "page": None, "message": f"Ошибка при анализе: {str(e)}"}
        ]}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Использование: python analyzer.py <путь_к_pdf>")
        sys.exit(1)
    result = analyze_pdf(sys.argv[1])
    print(json.dumps(result, ensure_ascii=False, indent=2))
