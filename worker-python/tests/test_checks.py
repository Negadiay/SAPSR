"""Правила оформления и структуры по каталогу базы знаний (план, З3, З8, З9)."""

from __future__ import annotations

import ast
from datetime import datetime
from pathlib import Path

import pytest

from conftest import FIXTURES, HYBRID, NOW, final_report, ids, make_task, run
from rawdoc import INDENT, LEFT, PageBuilder, raw_document, title_page
from sapsr.checks import registry
from sapsr.checks.context import CheckContext
from sapsr.checks.engine import run_format_rules
from sapsr.directories import NullDirectory, StaticDirectory
from sapsr.document.build import build_document
from sapsr.document.labeling import RuleLabeler
from sapsr.errors import CheckFailure
from sapsr.pipeline import CheckService


def check_raw(kb, raw, profile="base", **task):
    doc = build_document(raw, RuleLabeler(kb.layout, kb.concepts), kb.concepts, task.get("source_format", "pdf"))
    ctx = CheckContext(doc=doc, raw=raw, layout=kb.layout, concepts=kb.concepts, task=make_task(**task),
                       directory=NullDirectory(), now=NOW)
    return run_format_rules(kb.build_profile(profile), ctx)


@pytest.mark.typical
def test_valid_sample_passes(service_factory):
    report = final_report(service_factory(llm=None), FIXTURES / "valid-format-sample.pdf")
    assert report.findings == []
    assert report.summary.passed


@pytest.mark.typical
def test_hybrid_notes_pass_format(service_factory):
    for name in ("content-clean", "content-formats", "content-kb", "content-neural"):
        phases = run(service_factory(llm=None), HYBRID / f"{name}.pdf")
        assert phases[0][3].findings == [], name


@pytest.mark.typical
def test_narrow_margin(service_factory):
    report = final_report(service_factory(llm=None), HYBRID / "narrow-margin.pdf")
    f = next(f for f in report.findings if f.requirement_id == "FMT-MARGIN-LEFT")
    assert f.page == "2–8" and not f.blocking and f.severity == "minor"
    assert f.message == "Левое поле 15 мм вместо 30." and [e.page for e in f.evidence] == list(range(2, 9))
    assert f.found == "Так на 7 стр." and f.expected == "Левое поле — 30 мм."
    assert f.fix.startswith("Установите левое поле 30 мм. В Word") and "\\geometry{left=3cm}" in f.fix


@pytest.mark.negative
def test_scan_without_text(service_factory):
    phases = run(service_factory(), HYBRID / "scan-no-text.pdf")
    assert len(phases) == 1
    phase, passed, final, report = phases[0]
    assert (phase, passed, final) == ("format", False, True)
    assert ids(report) == ["DOC-NO-TEXT"]
    assert "NO_TEXT_LAYER" in {s.reason for s in report.pipeline.skipped}


@pytest.mark.negative
def test_encrypted_pdf(service_factory):
    with pytest.raises(CheckFailure) as exc:
        run(service_factory(), HYBRID / "encrypted.pdf")
    assert exc.value.code == "PDF_ENCRYPTED"
    assert "паролем" in exc.value.user_message


@pytest.mark.negative
def test_not_a_pdf(service_factory, tmp_path):
    fake = tmp_path / "work.pdf"
    fake.write_bytes(b"PK\x03\x04 this is a zip, not a pdf")
    with pytest.raises(CheckFailure) as exc:
        run(service_factory(), fake)
    assert exc.value.code == "PDF_UNREADABLE"


@pytest.mark.negative
def test_missing_file(service_factory, tmp_path):
    with pytest.raises(CheckFailure) as exc:
        run(service_factory(), tmp_path / "nope.pdf")
    assert exc.value.code == "FILE_NOT_FOUND"


@pytest.mark.boundary
def test_too_many_pages(service_factory):
    with pytest.raises(CheckFailure) as exc:
        run(service_factory(max_pages=3), HYBRID / "content-clean.pdf")
    assert exc.value.code == "TOO_LARGE" and "3 страниц" in exc.value.user_message


@pytest.mark.typical
def test_iis_unavailable_is_skipped_not_failed(service_factory):
    report = final_report(service_factory(llm=None, directory_=NullDirectory()), HYBRID / "content-clean.pdf")
    assert report.summary.passed
    skipped = {s.id for s in report.pipeline.skipped if s.reason == "IIS_UNAVAILABLE"}
    assert skipped == {"TTL-SPECIALTY", "TTL-HEAD-IIS", "TTL-REVIEWER-IIS"}


@pytest.mark.negative
def test_unknown_reviewer_in_iis(service_factory):
    directory = StaticDirectory(["6-05-0611-03"], ["Сидоренко Семён Семёнович"])
    report = final_report(service_factory(llm=None, directory_=directory), HYBRID / "content-clean.pdf")
    f = next(f for f in report.findings if f.requirement_id == "TTL-REVIEWER-IIS")
    assert f.blocking and f.message == "Проверяющий «П.П. Петров» не найден в ИИС БГУИР."
    assert not report.summary.passed


@pytest.mark.negative
def test_unknown_specialty(service_factory):
    directory = StaticDirectory(["1-40-01-01"], ["Сидоренко Семён Семёнович", "Петров Пётр Петрович"])
    report = final_report(service_factory(llm=None, directory_=directory), HYBRID / "content-clean.pdf")
    assert "TTL-SPECIALTY" in ids(report)


@pytest.mark.boundary
def test_title_year_must_be_current(kb, directory):
    service = CheckService(kb, None, directory, clock=lambda: datetime(2027, 1, 10))
    report = final_report(service, HYBRID / "content-clean.pdf")
    f = next(f for f in report.findings if f.requirement_id == "TTL-YEAR-CURRENT")
    assert f.message == "На титульном листе указан год 2026, но сейчас 2027." and f.blocking


@pytest.mark.typical
def test_student_name_on_title(service_factory):
    ok = final_report(service_factory(llm=None), HYBRID / "content-clean.pdf", student={"last_name": "Иванов"})
    assert ok.document.title_page.student_name == "И.И. Иванов"
    assert ok.document.title_page.matches_registration is True
    other = final_report(service_factory(llm=None), HYBRID / "content-clean.pdf", student={"last_name": "Петренко"})
    f = next(f for f in other.findings if f.requirement_id == "TTL-STUDENT-MATCH")
    assert f.needs_teacher_review and not f.blocking
    assert other.document.title_page.matches_registration is False
    assert other.summary.passed


@pytest.mark.typical
def test_missing_intro_only_in_toc(kb):
    """«Введение» есть только в оглавлении — раздела нет (прежний анализатор его «находил»)."""
    toc = PageBuilder(2).add("СОДЕРЖАНИЕ", x0=250, bold=True).add(f"Введение {'. ' * 20} 3")
    body = PageBuilder(3).add("ЗАКЛЮЧЕНИЕ", x0=250, bold=True).para("Текст.")
    findings, _ = check_raw(kb, raw_document(title_page(), toc, body))
    found = {f.requirement_id for f in findings}
    assert "STR-INTRO" in found and "STR-CONCLUSION" not in found and "STR-TOC" not in found
    intro = next(f for f in findings if f.requirement_id == "STR-INTRO")
    assert intro.blocking and intro.severity == "critical"


@pytest.mark.typical
def test_docx_font_substitution_is_allowed(kb):
    def pages():
        result = [title_page()]
        for n in range(2, 6):
            p = PageBuilder(n)
            for _ in range(6):
                p.add("Текст основной части записки набран шрифтом", font="LiberationSerif")
            result.append(p)
        return result
    as_pdf, _ = check_raw(kb, raw_document(*pages()))
    as_docx, _ = check_raw(kb, raw_document(*pages()), source_format="docx")
    assert "FMT-FONT-FAMILY" in {f.requirement_id for f in as_pdf}
    assert "FMT-FONT-FAMILY" not in {f.requirement_id for f in as_docx}


@pytest.mark.typical
def test_simple_list_rules(kb):
    p = PageBuilder(2).para("Первый абзац основного текста, который занимает", "три строки и задаёт левый край",
                            "текста на странице.")
    p.para("Задачи:")
    for item in ("– провести анализ;", "– разработать архитектуру", "– оценить результат."):
        p.add(item, x0=LEFT + INDENT)
    p.para("Ограничения:")
    for item in ("– Система работает только с PDF.", "– Проверка занимает до минуты.", "– Отчёт хранится полгода."):
        p.add(item, x0=LEFT + INDENT)
    findings, _ = check_raw(kb, raw_document(title_page(), p))
    lists = next(f for f in findings if f.requirement_id == "LST-SIMPLE")
    assert lists.found == "Пунктов с ошибкой: 1 из 3 (списков: 1)."


@pytest.mark.boundary
def test_failing_check_is_isolated(kb, monkeypatch, service_factory):
    spec = registry.REGISTRY["captions.figure_mentions"]

    def boom(ctx, params):
        raise RuntimeError("сбой проверки")

    monkeypatch.setitem(registry.REGISTRY, "captions.figure_mentions",
                        registry.CheckSpec(spec.name, boom, spec.params, spec.provides))
    report = final_report(service_factory(llm=None), HYBRID / "content-clean.pdf")
    assert ("CAP-FIG-REF", "CHECK_ERROR") in {(s.id, s.reason) for s in report.pipeline.skipped}
    assert report.summary.passed


def test_no_numeric_parameters_in_checks():
    """Критерий готовности: ни одно правило не содержит числовых параметров в коде.
    Допустимы 0, 1, 2 (индексы, медиана), 100 (проценты) и перевод пунктов в миллиметры."""
    allowed = {0, 1, 2, 100}
    unit_conversion = {72, 25.4}
    checks_dir = Path(__file__).resolve().parents[1] / "sapsr" / "checks"
    offenders = []
    for path in checks_dir.glob("*.py"):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)) \
                    and not isinstance(node.value, bool):
                if node.value in allowed or (path.name == "margins.py" and node.value in unit_conversion):
                    continue
                offenders.append(f"{path.name}:{node.lineno}: {node.value}")
    assert offenders == []
