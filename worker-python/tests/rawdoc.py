"""Синтетические страницы для модульных тестов разметки и правил — без PDF."""

from __future__ import annotations

from sapsr.document.extract import Char, RawDocument, RawLine, RawPage

REGULAR, BOLD = "TimesNewRomanPSMT", "TimesNewRomanPS-BoldMT"
LEFT, INDENT, PITCH, SIZE = 85.0, 35.4, 21.0, 14.0


def line(page: int, text: str, x0: float = LEFT, top: float = 60.0, size: float = SIZE,
         bold: bool = False, font: str | None = None) -> RawLine:
    font = font or (BOLD if bold else REGULAR)
    width = size * 0.5
    chars, x = [], x0
    for ch in text:
        if ch != " ":
            chars.append(Char(ch, font, size, x, x + width, top, top + size))
        x += width
    return RawLine(page=page, text=text, x0=x0, top=top, x1=x, bottom=top + size, chars=chars,
                   font=font, size=size, bold=bold, italic=False)


class PageBuilder:
    def __init__(self, number: int, top: float = 60.0):
        self.number = number
        self.top = top
        self.lines: list[RawLine] = []

    def add(self, text: str, x0: float = LEFT, bold: bool = False, size: float = SIZE,
            gap: float = 0.0, font: str | None = None) -> "PageBuilder":
        self.top += gap
        self.lines.append(line(self.number, text, x0=x0, top=self.top, size=size, bold=bold, font=font))
        self.top += PITCH
        return self

    def para(self, *lines: str) -> "PageBuilder":
        for i, text in enumerate(lines):
            self.add(text, x0=LEFT + INDENT if i == 0 else LEFT)
        return self

    def page_number(self) -> "PageBuilder":
        self.lines.append(line(self.number, str(self.number), x0=540, top=790, size=12))
        return self

    def build(self) -> RawPage:
        chars = [c for ln in self.lines for c in ln.chars]
        return RawPage(number=self.number, width=595.28, height=841.89, chars=chars, lines=self.lines,
                       text="\n".join(ln.text for ln in self.lines))


def raw_document(*pages: PageBuilder) -> RawDocument:
    return RawDocument(path="synthetic.pdf", sha256="0" * 64, pages=[p.build() for p in pages])


def title_page() -> PageBuilder:
    p = PageBuilder(1)
    for text in ("Министерство образования Республики Беларусь", "БЕЛОРУССКИЙ ГОСУДАРСТВЕННЫЙ УНИВЕРСИТЕТ",
                 "Кафедра интеллектуальных информационных технологий", "Минск 2026"):
        p.add(text, x0=150)
    return p
