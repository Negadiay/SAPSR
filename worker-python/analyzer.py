import pdfplumber
from collections import Counter
from typing import List, Dict, Any


REQUIRED_FONT = "TimesNewRomanPSMT"
ALLOWED_FONTS = {"TimesNewRomanPSMT", "TimesNewRomanPS-BoldMT", "TimesNewRomanPS-ItalicMT", "TimesNewRomanPS-BoldItalicMT"}
REQUIRED_SIZE = 14.0
INDENT_THRESHOLD = 50


def analyze_pdf(file_path: str) -> Dict[str, Any]:
    """
    Открывает PDF и анализирует каждую страницу:
    - определяет самый частый шрифт и его размер
    - проверяет наличие абзацного отступа
    - формирует список ошибок, если шрифт не TNR или размер не 14
    """
    errors: List[Dict[str, Any]] = []

    try:
        with pdfplumber.open(file_path) as pdf:
            if len(pdf.pages) == 0:
                return {
                    "status": "FAIL",
                    "errors": [{"page": 0, "message": "PDF не содержит страниц"}],
                }

            for page_num, page in enumerate(pdf.pages, start=1):
                chars = page.chars
                if not chars:
                    errors.append({
                        "page": page_num,
                        "message": "Страница пуста — нет текстовых символов",
                    })
                    continue

                font_counter: Counter = Counter()
                size_counter: Counter = Counter()
                has_indent = False

                for ch in chars:
                    font_name = ch.get("fontname", "Unknown")
                    font_size = round(ch.get("size", 0), 1)
                    font_counter[font_name] += 1
                    size_counter[font_size] += 1

                    x0 = ch.get("x0", 0)
                    if x0 >= INDENT_THRESHOLD:
                        has_indent = True

                most_common_font = font_counter.most_common(1)[0][0]
                most_common_size = size_counter.most_common(1)[0][0]

                page_info = (
                    f"Стр. {page_num}: шрифт='{most_common_font}', "
                    f"кегль={most_common_size}, "
                    f"абзацный отступ={'да' if has_indent else 'нет'}"
                )
                print(f"   [ANALYZER] {page_info}")

                if most_common_font not in ALLOWED_FONTS:
                    errors.append({
                        "page": page_num,
                        "message": (
                            f"Неверный шрифт: '{most_common_font}' "
                            f"(ожидается Times New Roman)"
                        ),
                    })

                if most_common_size != REQUIRED_SIZE:
                    errors.append({
                        "page": page_num,
                        "message": (
                            f"Неверный размер шрифта: {most_common_size} пт "
                            f"(ожидается {REQUIRED_SIZE} пт)"
                        ),
                    })

    except FileNotFoundError:
        return {
            "status": "FAIL",
            "errors": [{"page": 0, "message": f"Файл не найден: {file_path}"}],
        }
    except Exception as e:
        return {
            "status": "FAIL",
            "errors": [{"page": 0, "message": f"Ошибка при чтении PDF: {str(e)}"}],
        }

    if errors:
        return {"status": "FAIL", "errors": errors}

    return {"status": "SUCCESS", "errors": []}


if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Использование: python analyzer.py <путь_к_PDF>")
        sys.exit(1)

    result = analyze_pdf(sys.argv[1])
    print(f"\nРезультат: {result['status']}")
    for err in result["errors"]:
        print(f"  - Стр. {err['page']}: {err['message']}")
