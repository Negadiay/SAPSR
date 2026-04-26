import re
import sys
import json
from collections import Counter
from datetime import datetime
import pdfplumber
import requests
from check_config import (
    ALLOWED_FONT_FAMILIES,
    BSUIR_SPECIALITIES_URL,
    CRITICAL_REFERENCES_THRESHOLD,
    FONT_FAMILY_VIOLATION_THRESHOLD,
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

# --- Утилиты ---

def _make_error(severity, page, message, rule, found, fix, location=None, category=None):
    return {
        "severity": severity,
        "category": category or severity,
        "page": page,
        "location": location or (f"Страница {page}" if page else "Документ"),
        "message": message,
        "rule": rule,
        "found": found,
        "fix": fix,
    }


def _dominant_size(chars):
    size_counts = Counter()
    for c in chars:
        text = c.get("text", "").strip()
        if text and text not in (" ", "\n"):
            size_counts[round(c.get("size", 0), 1)] += 1
    return size_counts.most_common(1)[0][0] if size_counts else 0.0


def _normalize_font_family(font_name):
    name = (font_name or "").lower()
    if "+" in name:
        name = name.split("+", 1)[1]
    return re.sub(r"[^a-zа-яё0-9]", "", name)


def _is_allowed_font_family(font_name):
    if not font_name:
        return True
    # LaTeX embeds all fonts as subsets with a random 6-letter uppercase prefix before "+".
    # These are always properly embedded fonts — check only the base name after "+".
    base = font_name
    if "+" in font_name:
        base = font_name.split("+", 1)[1]
    normalized_base = re.sub(r"[^a-zа-яё0-9]", "", base.lower())
    return any(
        re.sub(r"[^a-zа-яё0-9]", "", family.lower()) in normalized_base
        for family in ALLOWED_FONT_FAMILIES
    )


def _dominant_font(chars):
    font_counts = Counter()
    for c in chars:
        text = c.get("text", "").strip()
        font_name = c.get("fontname")
        if text and font_name:
            font_counts[font_name] += 1
    return font_counts.most_common(1)[0][0] if font_counts else ""


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
            errors.append(_make_error(
                "critical",
                None,
                f"Отсутствует обязательный раздел: «{section['name']}».",
                f"В документе должен быть раздел «{section['name']}».",
                "Раздел не найден по ключевому заголовку.",
                f"Добавьте раздел «{section['name']}» с отдельным заголовком.",
                "Структура документа",
                "critical",
            ))
    return errors


def _check_references_count(full_text):
    errors = []
    heading_pattern = r"список использованных источников"
    matches = list(re.finditer(heading_pattern, full_text, re.IGNORECASE))
    if not matches:
        return errors

    # The same heading usually appears in the table of contents. Use the last
    # occurrence to inspect the actual references section.
    section_text = full_text[matches[-1].end():]
    count = len(re.findall(r"^\s*\[?\d+\]?[\.\)]?\s+\S", section_text, re.MULTILINE))
    if count == 0:
        count = len(re.findall(r"^\s*\d+\s+\S", section_text, re.MULTILINE))
    if count < CRITICAL_REFERENCES_THRESHOLD:
        errors.append(_make_error(
            "critical",
            None,
            f"Список использованных источников содержит менее {CRITICAL_REFERENCES_THRESHOLD} источников.",
            f"В разделе «Список использованных источников» должно быть не менее {MIN_REFERENCES} источников.",
            f"Найдено источников: {count}.",
            "Добавьте недостающие источники и оформите каждый пункт отдельной пронумерованной строкой.",
            "Раздел «Список использованных источников»",
            "critical",
        ))
    elif count < MIN_REFERENCES:
        errors.append(_make_error(
            "warning",
            None,
            f"Рекомендуется не менее {MIN_REFERENCES} источников.",
            f"В разделе «Список использованных источников» рекомендуется не менее {MIN_REFERENCES} источников.",
            f"Найдено источников: {count}.",
            "Добавьте источники или уточните нумерацию списка.",
            "Раздел «Список использованных источников»",
            "warning",
        ))
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
            errors.append(_make_error(
                "minor",
                None,
                "Нарушено оформление простого списка.",
                "Промежуточные элементы простого списка должны заканчиваться «;», последний элемент — «.».",
                f"Нарушено в {violations} из {len(lines)} элементов.",
                "Проверьте знаки препинания в конце пунктов списка.",
                "Блок простого списка",
                "warning",
            ))
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
            errors.append(_make_error(
                "minor",
                None,
                "Нарушено оформление нумерованного списка.",
                "Каждый элемент нумерованного списка должен заканчиваться точкой.",
                f"Нарушено в {violations} из {len(lines)} элементах.",
                "Поставьте точку в конце каждого пункта нумерованного списка.",
                "Блок нумерованного списка",
                "warning",
            ))
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
        errors.append(_make_error(
            "minor",
            None,
            f"Для рисунков {nums} есть подпись, но нет ссылки в тексте.",
            "На каждый рисунок должна быть ссылка в тексте работы.",
            f"Нет ссылок на рисунки: {nums}.",
            "Добавьте ссылку в тексте, например «см. рис. N».",
            "Подписи и ссылки на рисунки",
            "warning",
        ))
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
            errors.append(_make_error(
                "warning",
                1,
                f"Код специальности «{specialty_raw}» не найден в реестре специальностей БГУИР.",
                "Код специальности в обозначении работы должен существовать в реестре БГУИР.",
                f"Найден код: {specialty_raw}.",
                "Проверьте правильность кода специальности на титульном листе.",
                "Страница 1, обозначение работы",
                "warning",
            ))
    except Exception as e:
        print(f"[ANALYZER] BSUIR API недоступен: {e}")
    return errors


def _check_minsk_year(first_page_text):
    errors = []
    match = re.search(r"минск[^\n]*?(\d{4})|(\d{4})[^\n]*?минск", first_page_text, re.IGNORECASE)
    if not match:
        errors.append(_make_error(
            "minor",
            1,
            "На титульном листе не найден год рядом со словом «Минск».",
            "На титульном листе должен быть указан город Минск и год выполнения работы.",
            "Год рядом со словом «Минск» не обнаружен.",
            "Добавьте строку вида «Минск 2026» на титульный лист.",
            "Страница 1, нижняя часть титульного листа",
            "warning",
        ))
        return errors
    year = int(match.group(1) or match.group(2))
    current_year = datetime.now().year
    if abs(year - current_year) > 1:
        errors.append(_make_error(
            "minor",
            1,
            f"На титульном листе указан год {year}, ожидается {current_year}.",
            "Год на титульном листе должен соответствовать текущему учебному периоду.",
            f"Найден год: {year}.",
            f"Проверьте год и при необходимости замените его на {current_year}.",
            "Страница 1, строка с городом и годом",
            "warning",
        ))
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
        errors.append(_make_error(
            "minor",
            page_num,
            "Нарушено левое поле страницы.",
            "Левое поле должно быть 3 см.",
            f"Текст начинается с x={min(xs):.0f}pt, норма ≥{MARGIN_LEFT:.0f}pt.",
            r"В LaTeX проверьте \geometry{left=3cm}; в Word настройте левое поле 3 см.",
            f"Страница {page_num}, левый край текста",
            "warning",
        ))
    if max(x1s) > MARGIN_RIGHT  + MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor",
            page_num,
            "Нарушено правое поле страницы.",
            "Правое поле должно быть 1.5 см.",
            f"Текст уходит до x={max(x1s):.0f}pt, норма ≤{MARGIN_RIGHT:.0f}pt.",
            r"В LaTeX проверьте \geometry{right=1.5cm}; в Word настройте правое поле 1.5 см.",
            f"Страница {page_num}, правый край текста",
            "warning",
        ))
    if min(ys)  < MARGIN_TOP    - MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor",
            page_num,
            "Нарушено верхнее поле страницы.",
            "Верхнее поле должно быть 2 см.",
            f"Текст начинается с y={min(ys):.0f}pt, норма ≥{MARGIN_TOP:.0f}pt.",
            r"В LaTeX проверьте \geometry{top=2cm}; в Word настройте верхнее поле 2 см.",
            f"Страница {page_num}, верхний край текста",
            "warning",
        ))
    if max(y1s) > MARGIN_BOTTOM + MARGIN_TOLERANCE:
        errors.append(_make_error(
            "minor",
            page_num,
            "Нарушено нижнее поле страницы.",
            "Нижнее поле должно быть 2.7 см.",
            f"Текст уходит до y={max(y1s):.0f}pt, норма ≤{MARGIN_BOTTOM:.0f}pt.",
            r"В LaTeX проверьте \geometry{bottom=2.7cm}; в Word настройте нижнее поле 2.7 см.",
            f"Страница {page_num}, нижний край текста",
            "warning",
        ))
    return errors


# --- Главная функция ---

def analyze_pdf(path: str) -> dict:
    try:
        with pdfplumber.open(path) as pdf:
            if not pdf.pages:
                return {"status": "FAIL", "errors": [
                    _make_error(
                        "critical",
                        None,
                        "Документ пустой.",
                        "PDF должен содержать страницы с текстовым слоем.",
                        "Страницы не найдены.",
                        "Экспортируйте работу в PDF повторно и убедитесь, что файл не пустой.",
                        "Документ",
                        "critical",
                    )
                ]}

            all_chars = []
            page_margin_errors = []
            full_text = ""
            pages_with_size_err = []
            pages_with_font_family_err = []
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
                if psize and not (FONT_SIZE_MIN <= psize <= FONT_SIZE_MAX):
                    pages_with_size_err.append(i)

                font_name = _dominant_font(chars)
                if font_name and not _is_allowed_font_family(font_name):
                    pages_with_font_family_err.append(i)

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
                    _make_error(
                        "critical",
                        None,
                        "Документ не содержит текста.",
                        "PDF должен содержать текстовый слой для автоматической проверки.",
                        "Текстовый слой не обнаружен; возможно, это скан.",
                        "Экспортируйте документ как текстовый PDF, а не как изображение или скан.",
                        "Документ",
                        "critical",
                    )
                ]}

            errors = []

            # 1. Обязательные разделы
            errors.extend(_check_structure(full_text))

            # 2. Размер шрифта
            size_err_pct = len(pages_with_size_err) / total_pages * 100 if total_pages else 0
            if pages_with_size_err:
                dominant_size = _dominant_size(all_chars)
                page_range = _pages_to_range(pages_with_size_err)
                severity = "major" if size_err_pct > FONT_SIZE_MAJOR_THRESHOLD_PCT else "minor"
                errors.append({
                    **_make_error(
                        severity,
                        page_range,
                        "Размер основного шрифта вне допустимого диапазона.",
                        f"Основной шрифт должен быть от {FONT_SIZE_MIN:g} до {FONT_SIZE_MAX:g} pt.",
                        f"Доминирующий размер: {dominant_size}pt; затронуто {len(pages_with_size_err)} стр. "
                        f"({size_err_pct:.0f}%): {page_range}.",
                        "Измените размер основного текста на 14 pt или значение в допустимом диапазоне 13-15 pt.",
                        f"Страницы: {page_range}",
                        severity,
                    )
                })

            # 2b. Семейство шрифта (никогда не блокирует — только предупреждение)
            if pages_with_font_family_err:
                font_err_pct = len(pages_with_font_family_err) / total_pages
                dominant_font = _dominant_font(all_chars)
                page_range = _pages_to_range(pages_with_font_family_err)
                severity = "warning"
                errors.append({
                    **_make_error(
                        severity,
                        page_range,
                        "Обнаружен нестандартный шрифт в документе.",
                        "Рекомендуется Times New Roman 14 pt. Для LaTeX-документов допустимы Computer Modern и Latin Modern.",
                        f"Обнаружен шрифт: «{dominant_font}» на {len(pages_with_font_family_err)} стр. ({font_err_pct:.0%}).",
                        "Убедитесь, что шрифт встроен в PDF и соответствует требованиям кафедры.",
                        f"Страницы: {page_range}",
                        severity,
                    )
                })

            # 3. Поля страниц — группируем если много
            if page_margin_errors:
                margin_pages = sorted(set(e["page"] for e in page_margin_errors if e["page"]))
                if len(margin_pages) <= 3:
                    errors.extend(page_margin_errors)
                else:
                    page_range = _pages_to_range(margin_pages)
                    errors.append(_make_error(
                        "minor",
                        page_range,
                        "Нарушены поля страниц.",
                        "Поля должны соответствовать нормам: левое 3 см, правое 1.5 см, верхнее 2 см, нижнее 2.7 см.",
                        f"Нарушение найдено на {len(margin_pages)} страницах: {page_range}.",
                        r"Проверьте настройки полей: \geometry{left=3cm,right=1.5cm,top=2cm,bottom=2.7cm}.",
                        f"Страницы: {page_range}",
                        "warning",
                    ))

            # 4. Нумерация страниц
            checkable = max(0, total_pages - 2)
            if checkable > PAGE_NUMBER_MIN_CHECKABLE_PAGES and pages_with_page_num < checkable * PAGE_NUMBER_MIN_COVERAGE:
                errors.append(_make_error(
                    "minor",
                    None,
                    "Номера страниц не обнаружены или расположены не в нижнем правом углу.",
                    "Нумерация должна находиться в нижнем правом углу, начиная с проверяемых страниц.",
                    f"Найдено на {pages_with_page_num} из {checkable} проверяемых страниц.",
                    "Проверьте колонтитулы и расположение номера страницы.",
                    "Нижний правый угол страниц 3+",
                    "warning",
                ))

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
            _make_error(
                "critical",
                None,
                f"Файл не найден: {path}",
                "Файл должен быть доступен анализатору по переданному пути.",
                f"Путь недоступен: {path}.",
                "Повторите загрузку файла или проверьте хранилище.",
                "Файловое хранилище",
                "critical",
            )
        ]}
    except Exception as e:
        return {"status": "FAIL", "errors": [
            _make_error(
                "critical",
                None,
                f"Ошибка при анализе: {str(e)}",
                "Анализатор должен корректно прочитать PDF и извлечь текстовые данные.",
                str(e),
                "Проверьте, что PDF не повреждён, и попробуйте экспортировать его заново.",
                "Анализ PDF",
                "critical",
            )
        ]}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Использование: python analyzer.py <путь_к_pdf>")
        sys.exit(1)
    result = analyze_pdf(sys.argv[1])
    print(json.dumps(result, ensure_ascii=False, indent=2))
