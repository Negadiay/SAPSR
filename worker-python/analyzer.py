import re
import sys
import json
from collections import Counter
from datetime import datetime
import pdfplumber
import requests

# --- Константы BSUIR ---
PAGE_WIDTH  = 595.28
PAGE_HEIGHT = 841.89

MARGIN_LEFT      = 85.0
MARGIN_RIGHT     = 553.0
MARGIN_TOP       = 57.0
MARGIN_BOTTOM    = 765.0
MARGIN_TOLERANCE = 10.0

REQUIRED_FONT_SIZE  = 14.0
FONT_SIZE_TOLERANCE = 1.0

# Номера страниц — нижний правый угол (y от верха страницы)
PAGE_NUM_X_MIN = 460.0
PAGE_NUM_Y_MIN = 750.0  # 842 - 92 ≈ нижние ~90pt

BSUIR_SPECIALITIES_URL = "https://iis.bsuir.by/api/v1/specialities"

REQUIRED_SECTIONS = {
    "title_page": {"patterns": [r"министерство", r"белорусский", r"кафедра"], "name": "Титульный лист"},
    "toc":        {"patterns": [r"содержание", r"оглавление"],                "name": "Содержание"},
    "intro":      {"patterns": [r"введение"],                                  "name": "Введение"},
    "conclusion": {"patterns": [r"заключение"],                               "name": "Заключение"},
    "references": {"patterns": [r"список использованных", r"список литературы", r"библиограф"], "name": "Список источников"},
}

# --- Утилиты ---

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
    return re.sub(r"[\s\-]", "", (s or "")).lower()


def _is_toc_line(line):
    """Строка выглядит как строка оглавления: много точек или заканчивается на цифры после пробелов."""
    return bool(re.search(r"\.{4,}", line)) or bool(re.match(r".*\s{3,}\d+\s*$", line))


# --- Структурные проверки ---

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
    errors = []
    pattern = r"(?:список использованных источников|список литературы|библиограф)(.*?)(?:\n[А-ЯЁЪ][А-ЯЁЪ]{2,}|\Z)"
    match = re.search(pattern, full_text, re.IGNORECASE | re.DOTALL)
    if not match:
        return errors
    section_text = match.group(1)
    count = len(re.findall(r"^\s*\[?\d+\]?[\.\)]?\s+\S", section_text, re.MULTILINE))
    if count == 0:
        count = len(re.findall(r"^\s*\d+\s+\S", section_text, re.MULTILINE))
    if count < 5:
        errors.append({
            "severity": "critical",
            "page": None,
            "message": f"Список источников содержит менее 5 источников (найдено: {count}). Требуется не менее 10.",
        })
    elif count < 10:
        errors.append({
            "severity": "warning",
            "page": None,
            "message": f"Рекомендуется не менее 10 источников (найдено: {count}).",
        })
    return errors


def _check_simple_lists(full_text):
    """Проверяет оформление простых списков (тире + знаки препинания).
    Только настоящие списки — минимум 3 элемента, не строки TOC."""
    errors = []
    blocks = re.findall(r"((?:^[ \t]*[—–\-]\s+.{10,}\n?){3,})", full_text, re.MULTILINE)
    for block in blocks:
        lines = [l.rstrip() for l in block.strip().splitlines()
                 if l.strip() and not _is_toc_line(l)]
        if len(lines) < 3:
            continue
        violations = 0
        for i, line in enumerate(lines[:-1]):
            if not line.endswith(";"):
                violations += 1
        last = lines[-1]
        if not last.endswith("."):
            violations += 1
        if violations > 0:
            errors.append({
                "severity": "minor",
                "page": None,
                "message": f"Оформление простого списка: промежуточные элементы должны заканчиваться «;», последний — «.» "
                           f"(нарушено в {violations} из {len(lines)} элементов).",
            })
    return errors


def _check_numbered_lists(full_text):
    """Проверяет оформление сложных нумерованных списков.
    Исключает заголовки разделов (CAPS) и строки TOC."""
    errors = []
    # Ищем блоки: строки вида "1 Текст..." или "1. Текст..." — минимум 3 подряд
    blocks = re.findall(
        r"((?:^[ \t]*\d+[\.\)]?\s+[а-яёА-ЯЁa-zA-Z].{5,}\n?){3,})",
        full_text, re.MULTILINE
    )
    for block in blocks:
        lines = [l.rstrip() for l in block.strip().splitlines()
                 if l.strip() and not _is_toc_line(l)]
        # Пропускаем если это скорее всего TOC или заголовки разделов
        uppercase_count = sum(1 for l in lines if re.sub(r"^\s*\d+[\.\)]?\s+", "", l).isupper())
        if uppercase_count > len(lines) / 2:
            continue
        violations = 0
        for line in lines:
            text = re.sub(r"^\s*\d+[\.\)]?\s+", "", line)
            if text and not line.rstrip().endswith("."):
                violations += 1
        if violations > 0:
            errors.append({
                "severity": "minor",
                "page": None,
                "message": f"Оформление нумерованного списка: каждый элемент должен заканчиваться точкой "
                           f"(нарушено в {violations} из {len(lines)} элементах).",
            })
    return errors


def _check_figures(full_text):
    """Проверяет наличие ссылок на рисунки в тексте."""
    errors = []
    caption_nums = set(re.findall(
        r"(?:Рис(?:унок)?\.?\s*)(\d+[\.\d]*)", full_text, re.IGNORECASE
    ))
    ref_nums = set(re.findall(
        r"(?:на\s+)?(?:рис(?:унке?|\.)\s*)(\d+[\.\d]*)", full_text, re.IGNORECASE
    ))
    missing_refs = caption_nums - ref_nums
    if missing_refs:
        nums = ", ".join(sorted(missing_refs))
        errors.append({
            "severity": "minor",
            "page": None,
            "message": f"Рисунки {nums}: есть подпись, но нет ссылки в тексте (например, «см. рис. N»).",
        })
    return errors


def _check_student_id(full_text):
    """Проверяет код специальности в студенческом номере БГУИР.
    Формат: БГУИР КП[N] [specialty_code] [student_num] ПЗ
    Пример: БГУИР КП6 6-05-0611-03 039 ПЗ"""
    errors = []

    # Ищем блок "БГУИР КП[цифра] ..." чтобы корректно пропустить КП-префикс
    bsuir_match = re.search(
        r"БГУИР\s+КП\d+\s+((?:\d[\d\s\-]{3,20}\d))\s+(\d{3})\b",
        full_text
    )
    if not bsuir_match:
        # Fallback: общий паттерн (специальность типа X-XX-XXXX-XX)
        bsuir_match = re.search(
            r"(\d-\d{2}[\s\-]\d{4}-\d{2}|\d-\d{2}[\s\-]\d{2}[\s\-]\d{2})\s+(\d{3})\b",
            full_text
        )
        if not bsuir_match:
            return errors

    specialty_raw = bsuir_match.group(1).strip()
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
                "message": f"Код специальности «{specialty_raw}» не найден в реестре специальностей БГУИР. "
                           f"Проверьте правильность номера студента.",
            })
    except Exception as e:
        print(f"[ANALYZER] BSUIR API недоступен: {e}")
    return errors


def _check_minsk_year(first_page_text):
    errors = []
    match = re.search(r"минск[^\n]*?(\d{4})|(\d{4})[^\n]*?минск", first_page_text, re.IGNORECASE)
    if not match:
        errors.append({
            "severity": "minor",
            "page": 1,
            "message": "На титульном листе не найден год рядом со словом «Минск».",
        })
        return errors
    year = int(match.group(1) or match.group(2))
    current_year = datetime.now().year
    if abs(year - current_year) > 1:
        errors.append({
            "severity": "minor",
            "page": 1,
            "message": f"На титульном листе указан год {year}, ожидается {current_year}.",
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
            "message": f"Нарушено левое поле: текст начинается с x={min(xs):.0f}pt, норма ≥{MARGIN_LEFT:.0f}pt (3 см)."})
    if max(x1s) > MARGIN_RIGHT  + MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено правое поле: текст уходит до x={max(x1s):.0f}pt, норма ≤{MARGIN_RIGHT:.0f}pt (1.5 см)."})
    if min(ys)  < MARGIN_TOP    - MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено верхнее поле: текст начинается с y={min(ys):.0f}pt, норма ≥{MARGIN_TOP:.0f}pt (2 см)."})
    if max(y1s) > MARGIN_BOTTOM + MARGIN_TOLERANCE:
        errors.append({"severity": "minor", "page": page_num,
            "message": f"Нарушено нижнее поле: текст уходит до y={max(y1s):.0f}pt, норма ≤{MARGIN_BOTTOM:.0f}pt (2.7 см)."})
    return errors


# --- Главная функция ---

def analyze_pdf(path: str) -> dict:
    try:
        with pdfplumber.open(path) as pdf:
            if not pdf.pages:
                return {"status": "FAIL", "errors": [
                    {"severity": "critical", "page": None, "message": "Документ пустой."}
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

                # Ищем номер страницы в нижнем правом углу (со стр. 3+)
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
                     "message": "Документ не содержит текста. Возможно, это отсканированный PDF без текстового слоя."}
                ]}

            errors = []

            # 1. Обязательные разделы
            errors.extend(_check_structure(full_text))

            # 2. Размер шрифта
            size_err_pct = len(pages_with_size_err) / total_pages * 100 if total_pages else 0
            if pages_with_size_err:
                dominant_size = _dominant_size(all_chars)
                page_range = _pages_to_range(pages_with_size_err)
                severity = "major" if size_err_pct > 30 else "minor"
                errors.append({
                    "severity": severity,
                    "page": page_range,
                    "message": (
                        f"Размер основного шрифта {dominant_size}pt не соответствует норме 14pt "
                        f"(затронуто {len(pages_with_size_err)} стр. — {size_err_pct:.0f}%: стр. {page_range})."
                    ),
                })

            # 3. Поля страниц — группируем если много
            if page_margin_errors:
                margin_pages = sorted(set(e["page"] for e in page_margin_errors if e["page"]))
                if len(margin_pages) <= 3:
                    errors.extend(page_margin_errors)
                else:
                    errors.append({
                        "severity": "minor",
                        "page": _pages_to_range(margin_pages),
                        "message": f"Нарушение полей страницы на {len(margin_pages)} стр.: {_pages_to_range(margin_pages)}.",
                    })

            # 4. Нумерация страниц
            checkable = max(0, total_pages - 2)
            if checkable > 2 and pages_with_page_num < checkable * 0.6:
                errors.append({
                    "severity": "minor",
                    "page": None,
                    "message": f"Номера страниц не обнаружены или расположены не в нижнем правом углу "
                               f"(найдено на {pages_with_page_num} из {checkable} проверяемых страниц).",
                })

            # 5. Список источников
            errors.extend(_check_references_count(full_text))

            # 6. Оформление простых списков
            errors.extend(_check_simple_lists(full_text))

            # 7. Оформление нумерованных списков
            errors.extend(_check_numbered_lists(full_text))

            # 8. Подписи к рисункам
            errors.extend(_check_figures(full_text))

            # 9. Студенческий номер / специальность
            errors.extend(_check_student_id(full_text))

            # 10. Год на титульном листе
            errors.extend(_check_minsk_year(first_page_text))

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
