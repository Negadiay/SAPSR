"""Нечитаемый текстовый слой и растровые шрифты TeX (решение 15, задача Б1)."""

from __future__ import annotations

import pytest
from conftest import FIXTURES, HYBRID, final_report, ids
from pdfminer.pdffont import PDFType3Font, PDFUnicodeNotDefined
from pdfminer.psparser import LIT
from rawdoc import PageBuilder, raw_document, title_page

from sapsr.document import t2a
from sapsr.document.extract import _font_name
from sapsr.document.quality import text_quality
from sapsr.document.text import font_display_name

CURATOR = FIXTURES / "curator" / "curator-sample.pdf"


def _encode(text: str) -> bytes:
    reverse = {char: code for code, char in t2a.T2A.items()}
    return bytes(reverse[ch] for ch in text)


# ── таблица T2A ─────────────────────────────────────────────────────────────

@pytest.mark.typical
def test_t2a_table():
    assert t2a.decode(_encode("Министерство образования")) == "Министерство образования"
    assert t2a.T2A[0xBE] == "«" and t2a.T2A[0xBF] == "»"
    assert t2a.T2A[0x16] == "—" and t2a.T2A[0x15] == "–"
    assert t2a.T2A[0xBC] == "ё" and t2a.T2A[0x9C] == "Ё"
    assert t2a.T2A[0xC0] == "А" and t2a.T2A[0xFF] == "я"
    assert t2a.T2A[0x1C] == "fi"


@pytest.mark.boundary
def test_t2a_unknown_code_stays_cid():
    assert t2a.decode(bytes([0x80])) == "(cid:128)"


def _type3_spec(names: dict[int, str], **extra) -> dict:
    differences: list = []
    for code, name in sorted(names.items()):
        differences += [code, LIT(name)]
    first, last = min(names), max(names)
    return {"Subtype": LIT("Type3"), "FirstChar": first, "LastChar": last,
            "Widths": [500] * (last - first + 1), "FontBBox": [0, 0, 500, 700],
            "FontMatrix": [0.001, 0, 0, 0.001, 0, 0],
            "Encoding": {"Type": LIT("Encoding"), "Differences": differences}, **extra}


@pytest.mark.typical
def test_tex_type3_font_is_decoded_by_t2a():
    codes = _encode("Минск")
    font = PDFType3Font(None, _type3_spec({c: f"a{c}" for c in codes}))
    assert "".join(font.to_unichr(c) for c in codes) == "Минск"
    assert font.fontname == t2a.TEX_TYPE3_FONT


@pytest.mark.boundary
def test_tex_type3_code_outside_table_is_not_guessed():
    # 0xE9 в стандартной кодировке — «Ø»; для шрифта TeX это не так, символ остаётся без расшифровки.
    font = PDFType3Font(None, _type3_spec({0x80: "a128", 0xC0: "a192"}))
    assert font.to_unichr(0xC0) == "А"
    with pytest.raises(PDFUnicodeNotDefined):
        font.to_unichr(0x80)


@pytest.mark.negative
def test_other_type3_fonts_are_left_alone():
    font = PDFType3Font(None, _type3_spec({0xC0: "g192", 0x41: "A"}))
    assert font.fontname != t2a.TEX_TYPE3_FONT
    assert font.to_unichr(0x41) == "A"
    assert t2a.tex_glyph_codes(_type3_spec({0xC0: "a192"}, ToUnicode=object())) is None


# ── имена шрифтов ───────────────────────────────────────────────────────────

@pytest.mark.typical
@pytest.mark.parametrize("raw, shown", [
    ("ABCDEF+TimesNewRomanPSMT", "Times New Roman"),
    ("TimesNewRomanPS-BoldMT", "Times New Roman"),
    ("Arial,Bold", "Arial"),
    ("VOAZZS+ArialMT", "Arial"),
    ("LiberationSerif-Italic", "Liberation Serif"),
    ("TeX Type 3", "TeX Type 3"),
])
def test_font_display_name(raw, shown):
    assert font_display_name(raw) == shown


@pytest.mark.boundary
def test_font_without_name_is_empty():
    assert _font_name("unknown") == "" and _font_name(None) == ""
    assert _font_name("ABCDEF+Arial") == "ABCDEF+Arial"


# ── качество текста ─────────────────────────────────────────────────────────

def _doc(*lines: str):
    page = PageBuilder(2)
    for text in lines:
        page.add(text)
    return raw_document(title_page(), page)


@pytest.mark.typical
def test_text_quality_of_normal_text(kb):
    quality = text_quality(_doc(*["Система принимает файлы в формате PDF (до 300 стр.) — № 1, §2."] * 10),
                           kb.layout.text_quality)
    assert quality.readable_share == 1.0 and not quality.unreadable


@pytest.mark.negative
def test_text_quality_of_garbage(kb):
    quality = text_quality(_doc(*["ŁæøœßŁæøœß ˆ˜¯˘˙ Łæøœß"] * 20), kb.layout.text_quality)
    assert quality.readable_share < 0.85 and quality.unreadable


@pytest.mark.boundary
def test_short_document_is_not_judged(kb):
    assert not text_quality(_doc("Łæøœß"), kb.layout.text_quality).unreadable


# ── документы ───────────────────────────────────────────────────────────────

@pytest.mark.typical
def test_tex_type3_document(service_factory):
    report = final_report(service_factory(mode="symbolic"), HYBRID / "tex-type3.pdf")
    assert ids(report) == ["FMT-FONT-BITMAP"]
    assert report.document.main_font == t2a.TEX_TYPE3_FONT
    assert any(s.id == "FMT-FONT-FAMILY" and s.reason == "FONT_UNKNOWN" for s in report.pipeline.skipped)


@pytest.mark.negative
def test_unreadable_document(service_factory):
    report = final_report(service_factory(), HYBRID / "unreadable-text.pdf")
    assert ids(report) == ["DOC-TEXT-UNREADABLE"]
    assert report.summary.passed is False and report.summary.content_checked is False
    stages = {s.stage for s in report.pipeline.skipped if s.reason == "TEXT_UNREADABLE"}
    assert stages == {"format", "claims"}


@pytest.mark.typical
@pytest.mark.skipif(not CURATOR.exists(), reason="файла куратора нет (не хранится в git)")
def test_curator_file(service_factory, null_directory):
    # Справочник ИИС выключен: в тестовом справочнике нет настоящих сотрудников кафедры.
    report = final_report(service_factory(mode="symbolic", directory_=null_directory), CURATOR,
                          semester=5, work_type="course_project")
    found = ids(report)
    assert "FMT-FONT-BITMAP" in found
    assert "STR-TITLE-PAGE" not in found and "DOC-TEXT-UNREADABLE" not in found
    assert not any("unknown" in (f.message + (f.found or "")).lower() for f in report.findings)
    assert report.summary.passed is True
