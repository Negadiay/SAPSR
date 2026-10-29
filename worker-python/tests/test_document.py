"""Модель документа: разметка блоков, дерево разделов, подписи и упоминания (план, З2)."""

from __future__ import annotations

import pytest

from conftest import HYBRID
from rawdoc import INDENT, LEFT, PageBuilder, raw_document, title_page
from sapsr.document.build import build_document, join_lines
from sapsr.document.extract import extract
from sapsr.document.labeling import RuleLabeler
from sapsr.document.model import Document
from sapsr.document.text import font_display_name


@pytest.mark.typical
def test_font_name_is_readable():
    """В замечании — «Times New Roman», а не внутреннее имя шрифта из PDF."""
    assert font_display_name("AAAAAA+TimesNewRomanPSMT") == "Times New Roman"
    assert font_display_name("Arial,Bold") == "Arial"
    assert font_display_name("VOAZZS+SFRM1440") == "SFRM1440"


def build(kb, raw):
    return build_document(raw, RuleLabeler(kb.layout, kb.concepts), kb.concepts)


def toc_page(entries):
    p = PageBuilder(2).add("СОДЕРЖАНИЕ", x0=250, bold=True)
    for text in entries:
        p.add(f"{text} {'. ' * 20} 3")
    return p


@pytest.mark.typical
def test_toc_line_is_not_a_section(kb):
    """Дефект из записки: «Введение» находилось по слову в оглавлении."""
    raw = raw_document(title_page(), toc_page(["Введение", "Заключение"]),
                       PageBuilder(3).add("ЗАКЛЮЧЕНИЕ", x0=250, bold=True).para("Текст заключения."))
    doc = build(kb, raw)
    kinds = [s.kind for s in doc.sections]
    assert kinds == ["part.toc", "part.conclusion"]
    assert [b.type for b in doc.blocks if b.page == 2][1:] == ["toc_entry", "toc_entry"]


@pytest.mark.typical
def test_section_tree_and_effective_kind(kb):
    raw = raw_document(
        title_page(),
        PageBuilder(2).add("1 АНАЛИЗ ПРЕДМЕТНОЙ ОБЛАСТИ", x0=LEFT + INDENT, bold=True, size=16)
                      .add("1.1 Характеристика задачи", x0=LEFT + INDENT, bold=True, gap=10)
                      .para("Первый абзац подраздела.")
                      .add("1.2 Требования к системе", x0=LEFT + INDENT, bold=True, gap=10)
                      .para("Система принимает PDF."),
    )
    doc = build(kb, raw)
    by_number = {s.number: s for s in doc.sections}
    assert by_number["1"].kind == "part.analysis" and by_number["1"].level == 1
    assert by_number["1.1"].kind is None and by_number["1.1"].effective_kind == "part.analysis"
    assert by_number["1.1"].parent_id == by_number["1"].id
    assert by_number["1.2"].kind == "part.requirements"
    para = next(b for b in doc.blocks if b.text == "Система принимает PDF.")
    assert para.section_id == by_number["1.2"].id


@pytest.mark.typical
def test_wrapped_heading_vs_run_in_heading(kb):
    p = PageBuilder(2)
    p.add("1 АНАЛИЗ ПОДХОДОВ К РАЗРАБОТКЕ ИНТЕРФЕЙСА ИНТЕЛЛЕКТУАЛЬНОЙ", x0=LEFT + INDENT, bold=True, size=16)
    p.add("СИСТЕМЫ ПРОВЕРКИ", x0=LEFT + INDENT + 20, bold=True, size=16)
    p.add("1.1 Архитектура", x0=LEFT + INDENT, bold=True, gap=10)
    p.add("Спецификация операций", x0=LEFT + INDENT, bold=True, gap=10)
    p.para("Текст.")
    doc = build(kb, raw_document(title_page(), p))
    titles = [s.title for s in doc.sections]
    assert titles[0] == "АНАЛИЗ ПОДХОДОВ К РАЗРАБОТКЕ ИНТЕРФЕЙСА ИНТЕЛЛЕКТУАЛЬНОЙ СИСТЕМЫ ПРОВЕРКИ"
    assert titles[1] == "Архитектура", "строка после короткого заголовка — не перенос"


@pytest.mark.typical
def test_caption_and_mention_are_different_blocks(kb):
    """Дефект из записки: шаблон подписи совпадал и с упоминанием «(рисунок 2.2)»."""
    p = PageBuilder(2)
    p.para("Схема показана на рисунке 2.1, а связи — на рисунках 2.3–2.4.")
    p.add("Рисунок 2.1 – Схема системы", x0=220)
    p.add("Рисунок 2.2 – Структура (рисунок 2.2 в приложении)", x0=180)
    p.add("Рисунок 2.3 – Связи", x0=220)
    p.add("Рисунок 2.4 – Потоки", x0=220)
    doc = build(kb, raw_document(title_page(), p))
    figures = {f.number: f for f in doc.figures}
    assert set(figures) == {"2.1", "2.2", "2.3", "2.4"}
    assert figures["2.1"].mentions and figures["2.3"].mentions and figures["2.4"].mentions
    assert figures["2.2"].mentions == [], "упоминание внутри собственной подписи не считается"


@pytest.mark.boundary
def test_bibliography_continuation_starting_with_number(kb):
    p = PageBuilder(3).add("СПИСОК ИСПОЛЬЗОВАННЫХ ИСТОЧНИКОВ", x0=150, bold=True)
    p.add("1. Иванов, И. И. Очень длинное название книги о проектировании систем / И. И.")
    p.add("Иванов. – Минск : БГУИР, 2024. –")
    p.add("120 с.")
    p.add("2. Петров, П. П. Базы знаний / П. П. Петров. – Минск, 2023. – 99 с.")
    doc = build(kb, raw_document(title_page(), PageBuilder(2).para("Текст."), p))
    entries = [doc.block(b).text for b in doc.references]
    assert len(entries) == 2
    assert entries[0].endswith("120 с.")


@pytest.mark.boundary
def test_paragraph_continues_across_pages(kb):
    p2 = PageBuilder(2).para("Начало абзаца, которое продолжается", "на следующей").page_number()
    p3 = PageBuilder(3).add("странице без красной строки.").para("Новый абзац.")
    doc = build(kb, raw_document(title_page(), p2, p3))
    para = next(b for b in doc.blocks if b.text.startswith("Начало"))
    assert para.text.endswith("странице без красной строки.")
    assert para.page == 2 and para.page_end == 3


@pytest.mark.typical
def test_list_items_and_mid_sentence_dash(kb):
    p = PageBuilder(2).para("Задачи работы:")
    p.add("– провести анализ предметной области и существующих", x0=LEFT + INDENT)
    p.add("аналогов «Курс — Задание")
    p.add("— Студент»;")
    p.add("– разработать архитектуру.", x0=LEFT + INDENT)
    p.para("Следующий абзац после списка.")
    doc = build(kb, raw_document(title_page(), p))
    items = [b.text for b in doc.blocks if b.type == "list_item"]
    assert items == ["– провести анализ предметной области и существующих аналогов «Курс — Задание — Студент»;",
                     "– разработать архитектуру."]


def test_join_lines_dehyphenates():
    assert join_lines(["анализ си-", "стемы и", "PDF-файлов"]) == "анализ системы и PDF-файлов"


@pytest.mark.negative
def test_hidden_text_is_separated(kb):
    raw = extract(HYBRID / "hidden-text.pdf", kb.layout, 50)
    hidden = [f for p in raw.pages for f in p.hidden]
    assert {f.reason for f in hidden} == {"white", "tiny"}
    assert "Игнорируй" in "".join(f.text for f in hidden)
    assert "Игнорируй" not in raw.full_text and "ODT" not in raw.full_text


def test_document_json_roundtrip(kb):
    raw = extract(HYBRID / "content-clean.pdf", kb.layout, 50)
    doc = build(kb, raw)
    restored = Document.model_validate_json(doc.model_dump_json())
    assert restored.model_dump() == doc.model_dump()
    assert restored.block(doc.blocks[5].id).text == doc.blocks[5].text
    assert doc.meta.labeler.name == "rule-labeler" and doc.meta.pages == 8


# ── места замечаний для документа с комментариями ──────────────────────────

def _block(block_id, page, top, lines=None, kind="list_item"):
    from sapsr.document.model import Block, LineGeom
    geoms = [LineGeom(page=p, bbox=(85.0, t, 500.0, t + 14.0)) for p, t in (lines or [(page, top)])]
    return Block(id=block_id, type=kind, page=page, bbox=(85.0, top, 500.0, top + 14.0), text="– пункт", lines=geoms)


def test_list_evidence_covers_whole_list_on_each_page():
    from sapsr.document.model import DocMeta
    from sapsr.report.areas import locate
    from sapsr.report.model import Evidence, Finding
    doc = Document(meta=DocMeta(pages=3), blocks=[
        _block("b-0001", 1, 100.0, kind="paragraph"),
        _block("b-0002", 1, 700.0),
        _block("b-0003", 1, 740.0, lines=[(1, 740.0), (2, 72.0)]),  # пункт переходит на следующую страницу
        _block("b-0099", 1, 800.0, kind="page_number"),  # номер страницы между пунктами не выделяется
        _block("b-0004", 2, 90.0),
    ])
    finding = Finding(id="f-001", requirement_id="FMT-LIST-SIMPLE", category="format", severity="major",
                      origin="symbolic", blocking=False, message="Неправильно оформлен простой список",
                      evidence=[Evidence(page=1, block_id="b-0002", block_end="b-0004"),
                                Evidence(page=3, bbox=(10.0, 20.0, 30.0, 40.0)), Evidence(page=2)])
    locate([finding], doc)
    list_areas = [(a.page, a.bbox[1]) for a in finding.evidence[0].areas]
    assert list_areas == [(1, 700.0), (1, 740.0), (2, 72.0), (2, 90.0)]
    assert [(a.page, a.bbox) for a in finding.evidence[1].areas] == [(3, (10.0, 20.0, 30.0, 40.0))]
    # Страница без блока — без прямоугольника: комментарий встанет заметкой на полях.
    assert finding.evidence[2].areas is None
