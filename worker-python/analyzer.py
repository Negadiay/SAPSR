import re
import sys
from collections import Counter
import pdfplumber

# --- Константы по правилам BSUIR (из preamble.tex эталонной курсовой) ---
# A4 в PDF-координатах: 595.28 x 841.89 pt
PAGE_WIDTH = 595.28
PAGE_HEIGHT = 841.89

# Поля: left=3cm, top=2cm, right=1.5cm, bottom=2.7cm (1 cm = 28.35 pt)
MARGIN_LEFT = 85.0      # 3 * 28.35
MARGIN_RIGHT = 553.0    # PAGE_WIDTH - 1.5*28.35
MARGIN_TOP = 57.0       # 2 * 28.35
MARGIN_BOTTOM = 765.0   # PAGE_HEIGHT - 2.7*28.35
MARGIN_TOLERANCE = 10.0

# Шрифт и размер
REQUIRED_FONT_SIZE = 14.0
FONT_SIZE_TOLERANCE = 1.0
# T2A/pdflatex шрифты — Times-подобные семейства
ALLOWED_FONT_SUBSTRINGS = ["times", "ftm", "nimbus", "palatino"]

# Страничный номер: нижний правый угол
PAGE_NUM_X_MIN = 480.0
PAGE_NUM_Y_MIN = 790.0

# Весовая таблица нарушений
WEIGHTS = {
    "missing_section":  25,
    "font_major":       20,
    "font_minor":        5,
    "size_major":       15,
    "size_minor":        4,
    "margin_violation": 10,
    "no_page_numbers":   8,
}

# Обязательные разделы (ищем в тексте нечувствительно к регистру)
REQUIRED_SECTIONS = {
    "title_page": {
        "patterns": [r"министерство", r"белорусский", r"кафедра"],
        "name": "Титульный лист",
    },
    "toc": {
        "patterns": [r"содержание", r"оглавление"],
        "name": "Содержание/Оглавление",
    },
    "intro": {
        "patterns": [r"введение"],
        "name": "Введение",
    },
    "conclusion": {
        "patterns": [r"заключение"],
        "name": "Заключение",
    },
    "references": {
        "patterns": [r"список использованных", r"список литературы", r"библиограф"],
        "name": "Список использованных источников",
    },
}


def _is_times_font(font_name: str) -> bool:
    name = (font_name or "").lower()
    return any(s in name for s in ALLOWED_FONT_SUBSTRINGS)


def _dominant_font_and_size(chars):
    font_counts = Counter()
    size_counts = Counter()
    for c in chars:
        fname = c.get("fontname", "")
        fsize = round(c.get("size", 0), 1)
        text = c.get("text", "").strip()
        if text and text not in (" ", "\n"):
            font_counts[fname] += 1
            size_counts[fsize] += 1
    dominant_font = font_counts.most_common(1)[0][0] if font_counts else ""
    dominant_size = size_counts.most_common(1)[0][0] if size_counts else 0.0
    return dominant_font, dominant_size


def _check_structure(full_text: str) -> list:
    errors = []
    for key, section in REQUIRED_SECTIONS.items():
        found = any(re.search(p, full_text, re.IGNORECASE) for p in section["patterns"])
        if not found:
            errors.append({
                "severity": "critical",
                "page": None,
                "message": f"Отсутствует обязательный раздел: «{section['name']}»",
            })
    return errors


def _check_margins(page, page_num: int) -> list:
    errors = []
    chars = [c for c in (page.chars or []) if c.get("text", "").strip()]
    if not chars:
        return errors

    xs = [c["x0"] for c in chars]
    x1s = [c["x1"] for c in chars]
    ys = [c["top"] for c in chars]
    y1s = [c["bottom"] for c in chars]

    if min(xs) < MARGIN_LEFT - MARGIN_TOLERANCE:
        errors.append({
            "severity": "minor", "page": page_num,
            "message": f"Нарушено левое поле (x={min(xs):.0f}pt, норма ≥{MARGIN_LEFT:.0f}pt)",
        })
    if max(x1s) > MARGIN_RIGHT + MARGIN_TOLERANCE:
        errors.append({
            "severity": "minor", "page": page_num,
            "message": f"Нарушено правое поле (x={max(x1s):.0f}pt, норма ≤{MARGIN_RIGHT:.0f}pt)",
        })
    if min(ys) < MARGIN_TOP - MARGIN_TOLERANCE:
        errors.append({
            "severity": "minor", "page": page_num,
            "message": f"Нарушено верхнее поле (y={min(ys):.0f}pt, норма ≥{MARGIN_TOP:.0f}pt)",
        })
    if max(y1s) > MARGIN_BOTTOM + MARGIN_TOLERANCE:
        errors.append({
            "severity": "minor", "page": page_num,
            "message": f"Нарушено нижнее поле (y={max(y1s):.0f}pt, норма ≤{MARGIN_BOTTOM:.0f}pt)",
        })
    return errors


def _pages_to_range(pages: list) -> str:
    if not pages:
        return ""
    pages = sorted(set(pages))
    ranges = []
    start = end = pages[0]
    for p in pages[1:]:
        if p == end + 1:
            end = p
        else:
            ranges.append(str(start) if start == end else f"{start}–{end}")
            start = end = p
    ranges.append(str(start) if start == end else f"{start}–{end}")
    return ", ".join(ranges)


def _calculate_score(errors: list) -> int:
    penalty = 0
    missing = sum(1 for e in errors if "обязательный раздел" in e.get("message", "").lower())
    font_major = any(
        e.get("severity") == "major" and ("шрифт" in e.get("message", "").lower() or "font" in e.get("message", "").lower())
        for e in errors
    )
    font_minor = any(
        e.get("severity") != "major" and ("шрифт" in e.get("message", "").lower() or "font" in e.get("message", "").lower())
        for e in errors
    )
    size_major = any(
        e.get("severity") == "major" and "размер" in e.get("message", "").lower()
        for e in errors
    )
    size_minor = any(
        e.get("severity") != "major" and "размер" in e.get("message", "").lower()
        for e in errors
    )
    margin_count = sum(1 for e in errors if "поле" in e.get("message", "").lower())
    no_page_num = any("номера страниц" in e.get("message", "").lower() for e in errors)

    penalty += missing * WEIGHTS["missing_section"]
    penalty += WEIGHTS["font_major"] if font_major else (WEIGHTS["font_minor"] if font_minor else 0)
    penalty += WEIGHTS["size_major"] if size_major else (WEIGHTS["size_minor"] if size_minor else 0)
    penalty += min(margin_count * 3, WEIGHTS["margin_violation"])
    if no_page_num:
        penalty += WEIGHTS["no_page_numbers"]

    return max(0, 100 - penalty)


def analyze_pdf(path: str) -> dict:
    try:
        with pdfplumber.open(path) as pdf:
            if not pdf.pages:
                return {"status": "FAIL", "score": 0, "errors": [
                    {"severity": "critical", "page": None, "message": "Документ пустой"},
                ]}

            all_chars = []
            page_margin_errors = []
            full_text = ""
            pages_with_page_num = 0
            total_pages = len(pdf.pages)
            pages_with_font_err = []
            pages_with_size_err = []

            for i, page in enumerate(pdf.pages, start=1):
                chars = page.chars or []
                all_chars.extend(chars)
                full_text += (page.extract_text() or "") + "\n"
                page_margin_errors.extend(_check_margins(page, i))

                pfont, psize = _dominant_font_and_size(chars)
                if pfont and not _is_times_font(pfont):
                    pages_with_font_err.append(i)
                if psize and abs(psize - REQUIRED_FONT_SIZE) > FONT_SIZE_TOLERANCE:
                    pages_with_size_err.append(i)

                # Номера страниц ищем начиная с 3-й страницы (после титула и содержания)
                if i > 2 and chars:
                    has_num = any(
                        c.get("text", "").strip().isdigit()
                        and c.get("x0", 0) >= PAGE_NUM_X_MIN
                        and c.get("top", 0) >= PAGE_NUM_Y_MIN
                        for c in chars
                    )
                    if has_num:
                        pages_with_page_num += 1

            if not all_chars:
                return {"status": "FAIL", "score": 0, "errors": [
                    {"severity": "critical", "page": None,
                     "message": "Документ не содержит текста (возможно, отсканированный PDF)"},
                ]}

            errors = []

            # --- Структура документа ---
            errors.extend(_check_structure(full_text))

            # --- Шрифт по документу ---
            dominant_font, dominant_size = _dominant_font_and_size(all_chars)
            font_err_pct = len(pages_with_font_err) / total_pages * 100 if total_pages > 0 else 0
            size_err_pct = len(pages_with_size_err) / total_pages * 100 if total_pages > 0 else 0

            if pages_with_font_err:
                page_range = _pages_to_range(pages_with_font_err)
                severity = "major" if font_err_pct > 30 else "minor"
                errors.append({
                    "severity": severity,
                    "page": page_range,
                    "message": (
                        f"Нарушение шрифта на {len(pages_with_font_err)} стр. "
                        f"({font_err_pct:.0f}% документа), стр. {page_range}. "
                        f"Используется «{dominant_font}» вместо Times New Roman."
                    ),
                })

            if pages_with_size_err:
                page_range = _pages_to_range(pages_with_size_err)
                severity = "major" if size_err_pct > 30 else "minor"
                errors.append({
                    "severity": severity,
                    "page": page_range,
                    "message": (
                        f"Нарушение размера шрифта на {len(pages_with_size_err)} стр. "
                        f"({size_err_pct:.0f}% документа), стр. {page_range}. "
                        f"Обнаружен размер {dominant_size}pt вместо 14pt."
                    ),
                })

            # --- Ошибки полей ---
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

            # --- Нумерация страниц ---
            checkable = total_pages - 2
            if checkable > 0 and pages_with_page_num < checkable * 0.7:
                errors.append({
                    "severity": "minor",
                    "page": None,
                    "message": (
                        f"Номера страниц не найдены или присутствуют не на всех страницах "
                        f"({pages_with_page_num} из {checkable} проверено)."
                    ),
                })

            score = _calculate_score(errors)
            status = "SUCCESS" if score >= 60 else "FAIL"
            print(f"[ANALYZER] {path}: score={score}, status={status}, errors={len(errors)}")
            return {"status": status, "score": score, "errors": errors}

    except FileNotFoundError:
        return {"status": "FAIL", "score": 0, "errors": [
            {"severity": "critical", "page": None, "message": f"Файл не найден: {path}"},
        ]}
    except Exception as e:
        return {"status": "FAIL", "score": 0, "errors": [
            {"severity": "critical", "page": None, "message": f"Ошибка при анализе: {str(e)}"},
        ]}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Использование: python analyzer.py <путь_к_pdf>")
        sys.exit(1)

    import json
    result = analyze_pdf(sys.argv[1])
    print(json.dumps(result, ensure_ascii=False, indent=2))
