"""Образец записки в DOCX для проверки приёма Word-файлов (решение 3; план 3-й опроцентовки, Ф1 и Т2).

Собирает sample.docx без сторонних библиотек: DOCX — это ZIP с XML. Текст — та же вымышленная
записка, что в test-fixtures/hybrid (content-clean): поля 30/15/20/27 мм, Times New Roman 14 пт,
полуторный интервал, красная строка 1,25 см, номера страниц внизу справа.

    python generate.py        # пишет sample.docx рядом
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "hybrid"))

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
FONT = '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/>'


def paragraph(text: str = "", *, bold: bool = False, center: bool = False, indent: bool = True,
              page_break: bool = False, left_mm: float = 0) -> str:
    props = ['<w:spacing w:line="360" w:lineRule="auto" w:after="0"/>']
    if center:
        props.append('<w:jc w:val="center"/>')
    else:
        props.append('<w:jc w:val="both"/>')
        first = ' w:firstLine="709"' if indent else ""
        left = f' w:left="{int(left_mm * 56.7)}"' if left_mm else ""
        if first or left:
            props.append(f"<w:ind{left}{first}/>")
    if page_break:
        props.append("<w:pageBreakBefore/>")
    run_props = f'<w:rPr>{FONT}{"<w:b/>" if bold else ""}<w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr>'
    run = f'<w:r>{run_props}<w:t xml:space="preserve">{escape(text)}</w:t></w:r>' if text else ""
    return f'<w:p><w:pPr>{"".join(props)}</w:pPr>{run}</w:p>'


def body() -> str:
    import generate as corpus  # тексты общего корпуса

    p: list[str] = []
    for text in ("Министерство образования Республики Беларусь", "Учреждение образования",
                 "БЕЛОРУССКИЙ ГОСУДАРСТВЕННЫЙ УНИВЕРСИТЕТ", "ИНФОРМАТИКИ И РАДИОЭЛЕКТРОНИКИ"):
        p.append(paragraph(text, center=True))
    p += [paragraph(), paragraph("Кафедра интеллектуальных информационных технологий", indent=False), paragraph()]
    for text in ("К защите допустить:", "Заведующий кафедрой", "С.С. Сидоренко"):
        p.append(paragraph(text, indent=False, left_mm=85))
    p.append(paragraph())
    for text in ("ПОЯСНИТЕЛЬНАЯ ЗАПИСКА", "к курсовому проекту", "на тему «Система проверки студенческих работ»"):
        p.append(paragraph(text, center=True))
    p += [paragraph(), paragraph("БГУИР КП7 6-05-0611-03 001 ПЗ", center=True), paragraph(), paragraph()]
    p.append(paragraph("Студент гр. 321702 И.И. Иванов", indent=False))
    p.append(paragraph("Проверяющий П.П. Петров", indent=False))
    p += [paragraph()] * 6
    p.append(paragraph(f"Минск {corpus.YEAR}", center=True))

    p.append(paragraph("СОДЕРЖАНИЕ", bold=True, center=True, page_break=True))
    for text, page in (("ВВЕДЕНИЕ", 3), ("1 АНАЛИЗ ПРЕДМЕТНОЙ ОБЛАСТИ", 4), ("1.1 Характеристика предметной области", 4),
                       ("1.2 Требования к системе", 4), ("2 ПРОЕКТИРОВАНИЕ СИСТЕМЫ", 5),
                       ("2.1 Архитектура системы", 5), ("3 РЕАЛИЗАЦИЯ СИСТЕМЫ", 6), ("ЗАКЛЮЧЕНИЕ", 7),
                       ("СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ", 8)):
        p.append(paragraph(f"{text} {'.' * max(4, 70 - len(text))} {page}", indent=False))

    def section(title: str, texts: list[str], new_page: bool = True) -> None:
        p.append(paragraph(title, bold=True, center=new_page, page_break=new_page))
        p.append(paragraph())
        for text in texts:
            p.append(paragraph(text))

    section("ВВЕДЕНИЕ", corpus.INTRO_OK + [corpus.FILLER] * 3)
    section("1 АНАЛИЗ ПРЕДМЕТНОЙ ОБЛАСТИ", [])
    section("1.1 Характеристика предметной области", [corpus.FILLER] * 2, new_page=False)
    p.append(paragraph())
    section("1.2 Требования к системе", corpus.REQ_OK + [corpus.FILLER], new_page=False)
    section("2 ПРОЕКТИРОВАНИЕ СИСТЕМЫ", [])
    section("2.1 Архитектура системы", corpus.ARCH_OK + [corpus.FILLER] * 2, new_page=False)
    section("3 РЕАЛИЗАЦИЯ СИСТЕМЫ", corpus.IMPL_OK + [corpus.FILLER] * 2)
    section("ЗАКЛЮЧЕНИЕ", corpus.CONCL_OK + [corpus.FILLER])
    p.append(paragraph("СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ", bold=True, center=True, page_break=True))
    p.append(paragraph())
    for n, source in enumerate(corpus.SOURCES, start=1):
        p.append(paragraph(f"{n}. {source}", indent=False))

    sect = ('<w:sectPr><w:footerReference w:type="default" r:id="rId1"/>'
            '<w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1134" w:right="850" w:bottom="1531" w:left="1701" w:header="709" w:footer="680" w:gutter="0"/>'
            "<w:titlePg/></w:sectPr>")
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>{"".join(p)}{sect}</w:body></w:document>')


FOOTER = (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr xmlns:w="{W}"><w:p><w:pPr><w:jc w:val="right"/></w:pPr>'
          f'<w:r><w:rPr>{FONT}<w:sz w:val="24"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r>'
          f'<w:r><w:rPr>{FONT}<w:sz w:val="24"/></w:rPr><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>'
          f'<w:r><w:rPr>{FONT}<w:sz w:val="24"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r></w:p></w:ftr>')

CONTENT_TYPES = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                 '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                 '<Default Extension="xml" ContentType="application/xml"/>'
                 '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                 '<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>'
                 "</Types>")
RELS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        "</Relationships>")
DOC_RELS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>'
            "</Relationships>")


def main() -> None:
    target = HERE / "sample.docx"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as docx:
        # Постоянная дата записей — файл не меняется от запуска к запуску.
        for name, text in (("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", RELS),
                           ("word/document.xml", body()), ("word/_rels/document.xml.rels", DOC_RELS),
                           ("word/footer1.xml", FOOTER)):
            docx.writestr(zipfile.ZipInfo(name, date_time=(2026, 11, 15, 0, 0, 0)), text)
    print("готово:", target.name, target.stat().st_size, "байт")


if __name__ == "__main__":
    main()
