"""Скрипт оценки качества tools/evaluate.py (план 3-й опроцентовки, Т3) — на заглушке модели."""

from __future__ import annotations

import csv
import sys

import pytest
from conftest import WORKER

sys.path.insert(0, str(WORKER / "tools"))

import evaluate as ev


@pytest.fixture(scope="module")
def run():
    import os
    saved = dict(os.environ)  # скрипт настраивает окружение под себя — после него возвращаем как было
    try:
        yield ev.evaluate(["content-formats.pdf", "content-clean.pdf", "encrypted.pdf"], llm_mode="stub", repeat=2)
    finally:
        os.environ.clear()
        os.environ.update(saved)


def _result(run, name, mode):
    return next(r for r in run["results"] if r.name == name and r.mode == mode)


@pytest.mark.typical
def test_hybrid_finds_contradiction_and_symbolic_misses_it(run):
    hybrid = _result(run, "content-formats.pdf", "hybrid")
    assert hybrid.ok and hybrid.counts["content"].tp == 1
    symbolic = _result(run, "content-formats.pdf", "symbolic")
    assert symbolic.missed == ["CNT-CONSIST-002"] and symbolic.counts["content"].fn == 1
    # Чисто нейросетевой режим: одно замечание засчитано, второе (выдуманное) — ложное срабатывание.
    neural = _result(run, "content-formats.pdf", "neural")
    assert (neural.counts["content"].tp, neural.counts["content"].fp) == (1, 1)


@pytest.mark.typical
def test_summary_and_reproducibility(run):
    summary = ev.summarize(run)
    assert summary["hybrid"]["overall"].f1 == 1.0
    assert summary["hybrid"]["clean_with_extra"] == 0 and summary["hybrid"]["llm_blocking"] == 0
    assert summary["neural"]["clean_with_extra"] == 1, "на корректной записке модель без правил даёт лишнее"
    assert summary["symbolic"]["overall"].recall == 0.0
    assert 0 < summary["hybrid"]["hallucinated"] < 1
    assert run["reproducible"] == {"symbolic": True, "neural": True, "hybrid": True}


@pytest.mark.negative
def test_expected_failure_is_not_a_finding(run):
    encrypted = _result(run, "encrypted.pdf", "hybrid")
    assert encrypted.failure == "PDF_ENCRYPTED" and encrypted.ok


@pytest.mark.typical
def test_outputs(run, tmp_path):
    text = ev.to_markdown(run, "python tools/evaluate.py --llm stub")
    assert "## Среда" in text and "| Точность (все категории) |" in text and "`content-formats.pdf`" in text
    ev.write_csv(run, tmp_path / "quality.csv")
    with open(tmp_path / "quality.csv", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    assert {r["mode"] for r in rows} == {"symbolic", "neural", "hybrid"}
    assert any(r["document"] == "content-formats.pdf" and r["category"] == "content" and r["tp"] == "1" for r in rows)


@pytest.mark.boundary
def test_compare_rules(kb):
    entry = {"truth": {"findings": ["FMT-MARGIN-LEFT"], "allowed": ["CNT-CONSIST-001"],
                       "requirements_missing": ["REQ5-EFFICIENCY"]}}
    findings = [{"requirement_id": rid, "category": cat, "origin": origin, "blocking": False}
                for rid, cat, origin in (("FMT-MARGIN-LEFT", "format", "symbolic"),
                                         ("CNT-CONSIST-001", "content", "hybrid"),      # допустимо — не считается
                                         ("REQ5-EFFICIENCY", "requirements", "hybrid"),
                                         ("REQ5-ALGORITHM", "requirements", "hybrid"),   # лишнее
                                         ("STR-TOC", "structure", "symbolic"))]         # лишнее
    counts, extra, missed = ev.compare(kb, entry, findings, "hybrid")
    assert (counts["format"].tp, counts["requirements"].tp, counts["requirements"].fp) == (1, 1, 1)
    assert counts["content"].fp == 0 and counts["structure"].fp == 1
    assert extra == ["STR-TOC", "REQ5-ALGORITHM"] and missed == []
    # Без разметки требований замечания по ним в оценку не входят.
    counts, extra, _ = ev.compare(kb, {"truth": {"findings": []}}, findings[2:4], "hybrid")
    assert counts["requirements"].fp == 0 and extra == []
