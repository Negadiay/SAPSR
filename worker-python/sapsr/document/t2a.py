"""Кодировка T2A и восстановление текста растровых шрифтов TeX (решение 15, задача Б1).

Если LaTeX не нашёл векторные кириллические шрифты (нет пакета cm-super), pdfTeX вставляет
в PDF растровые шрифты Type 3: у них нет BaseFont (pdfminer отдаёт имя «unknown») и нет
ToUnicode, а глифы названы /a0…/a255, где число — код символа в кодировке T2A. pdfminer
таких имён не понимает и выдаёт «(cid:N)» или латинские символы стандартной кодировки
(«Ł» вместо «и»): титульный лист не находится, текст для модели — мусор.

Здесь — таблица T2A и правка шрифта после его создания pdfminer. Таблица неполная
намеренно: коды, которых в ней нет, остаются «(cid:N)» и видны проверке качества текста.
"""

from __future__ import annotations

import logging
import re
from collections.abc import Mapping
from typing import Any

# Имя, под которым такие шрифты видны в статистике и правилу FMT-FONT-BITMAP.
TEX_TYPE3_FONT = "TeX Type 3"

_RE_GLYPH = re.compile(r"^a(\d{1,3})$")


def _build_table() -> dict[int, str]:
    table = {code: chr(code) for code in range(0x20, 0x7F)}
    table[0x27] = "’"
    table[0x60] = "‘"
    table.update({0x10: "“", 0x11: "”", 0x15: "–", 0x16: "—",
                  0x1B: "ff", 0x1C: "fi", 0x1D: "fl", 0x1E: "ffi", 0x1F: "ffl"})
    table.update({code: chr(0x0410 + code - 0xC0) for code in range(0xC0, 0x100)})
    table.update({0x9C: "Ё", 0xBC: "ё", 0x9D: "№", 0x9F: "§", 0xBE: "«", 0xBF: "»"})
    return table


T2A: dict[int, str] = _build_table()


def decode(codes: bytes) -> str:
    """Текст по кодам T2A; неизвестные коды — «(cid:N)», как их показывает pdfminer."""
    return "".join(T2A.get(code, f"(cid:{code})") for code in codes)


def tex_glyph_codes(spec: Mapping[str, Any]) -> dict[int, int] | None:
    """Код символа в PDF → код T2A для шрифта Type 3 из TeX, иначе None.

    Шрифт подходит, если у него нет ToUnicode и все имена глифов в Differences
    (кроме .notdef) имеют вид a<число>.
    """
    from pdfminer.pdftypes import resolve1
    from pdfminer.psparser import PSLiteral

    if "ToUnicode" in spec:
        return None
    encoding = resolve1(spec.get("Encoding"))
    if not isinstance(encoding, dict):
        return None
    differences = resolve1(encoding.get("Differences")) or []
    codes: dict[int, int] = {}
    position = 0
    for item in differences:
        item = resolve1(item)
        if isinstance(item, int):
            position = item
            continue
        name = item.name if isinstance(item, PSLiteral) else str(item)
        if isinstance(name, bytes):
            name = name.decode("latin1")
        if name != ".notdef":
            match = _RE_GLYPH.match(name)
            if not match:
                return None
            codes[position] = int(match.group(1))
        position += 1
    return codes or None


def patch_type3_font(font, spec: Mapping[str, Any]) -> bool:
    """Подставляет расшифровку T2A в созданный pdfminer шрифт. True — шрифт был растровым из TeX."""
    codes = tex_glyph_codes(spec)
    if codes is None:
        return False
    cid2unicode = dict(font.cid2unicode)
    for cid, code in codes.items():
        if code in T2A:
            cid2unicode[cid] = T2A[code]
        else:
            # Стандартная кодировка дала бы здесь случайный латинский символ.
            cid2unicode.pop(cid, None)
    font.cid2unicode = cid2unicode
    font.fontname = TEX_TYPE3_FONT
    return True


_installed = False


def install() -> None:
    """Один раз оборачивает PDFType3Font.__init__: pdfplumber создаёт шрифты через pdfminer,
    другого места вмешаться в расшифровку символов нет."""
    global _installed
    if _installed:
        return
    from pdfminer.pdffont import PDFType3Font

    original = PDFType3Font.__init__

    def __init__(self, rsrcmgr, spec):
        original(self, rsrcmgr, spec)
        try:
            patch_type3_font(self, spec)
        except Exception:  # расшифровка — улучшение, а не условие чтения файла
            logging.getLogger("sapsr.document").warning("не удалось расшифровать шрифт Type 3", exc_info=True)

    PDFType3Font.__init__ = __init__
    _installed = True
