from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas


ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "valid-format-sample.pdf"
FONT_NAME = "SAPSRTestSerif"
FONT_CANDIDATES = (
    Path("C:/Windows/Fonts/times.ttf"),
    Path("C:/Windows/Fonts/timesbd.ttf"),
    Path("/usr/share/fonts/truetype/msttcorefonts/Times_New_Roman.ttf"),
    Path("/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"),
)


def register_font():
    for path in FONT_CANDIDATES:
        if path.exists():
            pdfmetrics.registerFont(TTFont(FONT_NAME, str(path)))
            return
    raise RuntimeError("No suitable serif font found for generating the sample PDF")


def draw_lines(pdf, lines, start_y=760, leading=24):
    pdf.setFont(FONT_NAME, 14)
    y = start_y
    for line in lines:
        pdf.drawString(90, y, line)
        y -= leading


def main():
    register_font()
    pdf = canvas.Canvas(str(OUTPUT), pagesize=A4)

    draw_lines(pdf, [
        "МИНИСТЕРСТВО ОБРАЗОВАНИЯ РЕСПУБЛИКИ БЕЛАРУСЬ",
        "БЕЛОРУССКИЙ ГОСУДАРСТВЕННЫЙ УНИВЕРСИТЕТ",
        "ИНФОРМАТИКИ И РАДИОЭЛЕКТРОНИКИ",
        "КАФЕДРА ПРОГРАММНОГО ОБЕСПЕЧЕНИЯ",
        "",
        "КУРСОВАЯ РАБОТА",
        "по дисциплине Проектирование программных систем",
        "на тему Система проверки студенческих работ",
        "",
        "Студент Иванов И.И.",
        "Руководитель Петров П.П.",
        "",
        "Минск 2026",
    ])
    pdf.showPage()

    draw_lines(pdf, [
        "СОДЕРЖАНИЕ",
        "",
        "ВВЕДЕНИЕ ........................................ 3",
        "1 Анализ предметной области ...................... 4",
        "2 Проектирование системы ......................... 8",
        "ЗАКЛЮЧЕНИЕ ...................................... 15",
        "СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ ............... 16",
        "",
        "ВВЕДЕНИЕ",
        "Цель работы состоит в создании удобной системы",
        "автоматической проверки оформления студенческих работ.",
        "Документ содержит текстовый слой и оформлен единым",
        "шрифтом допустимого размера.",
    ])
    pdf.showPage()

    draw_lines(pdf, [
        "ЗАКЛЮЧЕНИЕ",
        "В результате работы подготовлен тестовый документ,",
        "который используется для проверки функционала SAPSR.",
        "Структура документа включает обязательные разделы.",
        "",
        "СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ",
        "1 Иванов И.И. Основы программной инженерии.",
        "2 Петров П.П. Проектирование информационных систем.",
        "3 Сидоров С.С. Тестирование программного обеспечения.",
        "4 Кузнецов А.А. Архитектура веб-приложений.",
        "5 Смирнов В.В. Базы данных и приложения.",
        "6 Орлов Д.Д. Методология разработки ПО.",
        "7 Васильев Е.Е. Анализ требований.",
        "8 Федоров М.М. Документирование проектов.",
        "9 Морозов Н.Н. Качество программных систем.",
        "10 Новиков Р.Р. Практика нормоконтроля.",
    ], leading=22)
    pdf.save()
    print(OUTPUT)


if __name__ == "__main__":
    main()
