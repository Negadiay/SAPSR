"""Записанные ответы живой модели (test-fixtures/llm-replay, план 3-й опроцентовки, Т1).

Режим replay воспроизводит отчёт настоящей модели qwen3.5:4b без Ollama: по этим записям
идут оценка качества (tools/evaluate.py), сквозной прогон и запасной показ.
Записи устаревают при смене подсказок, словаря понятий, требований или документа —
тогда: python tools/record_replay.py.
"""

from __future__ import annotations

import pytest
from conftest import FIXTURES, HYBRID

from analyzer import build_service, check_pdf

REPLAY = FIXTURES / "llm-replay"
LLM_SKIPS = {"LLM_UNAVAILABLE", "LLM_SCHEMA", "TIMEOUT"}


@pytest.fixture
def replay_service(monkeypatch):
    monkeypatch.setenv("SAPSR_LLM_MODE", "replay")
    monkeypatch.setenv("SAPSR_LLM_REPLAY_DIR", str(REPLAY))
    monkeypatch.setenv("SAPSR_IIS_MODE", "off")
    return build_service()


def _stable(report: dict) -> dict:
    """Отчёт без времени и счётчиков обращений к модели."""
    pipeline = {k: v for k, v in report["pipeline"].items() if k not in ("stages", "metrics")}
    return {**{k: v for k, v in report.items() if k not in ("generated_at", "pipeline")}, "pipeline": pipeline}


@pytest.mark.typical
def test_replay_gives_live_model_report_without_ollama(replay_service):
    report = check_pdf(str(HYBRID / "content-formats.pdf"), replay_service, mode="hybrid", semester=7, stage=3)

    assert report["summary"]["content_checked"] is True
    assert not [s for s in report["pipeline"]["skipped"] if s["reason"] in LLM_SKIPS]
    found = [f["requirement_id"] for f in report["findings"]]
    assert "CNT-CONSIST-002" in found, "дефект 1 из записки найден настоящей моделью"
    assert not any(f["blocking"] for f in report["findings"] if f["origin"] != "symbolic")
    model = next(m for m in report["pipeline"]["models"] if m["role"] == "llm")
    assert model["name"] == "qwen3.5:4b"
    assert report["requirements"], "карта покрытия требований 7 семестра заполнена"


@pytest.mark.typical
def test_replay_is_reproducible(replay_service):
    first = check_pdf(str(HYBRID / "content-kb.pdf"), replay_service, mode="hybrid", semester=7, stage=3)
    second = check_pdf(str(HYBRID / "content-kb.pdf"), replay_service, mode="hybrid", semester=7, stage=3)
    assert _stable(first) == _stable(second)


@pytest.mark.typical
def test_replay_covers_neural_mode(replay_service):
    report = check_pdf(str(HYBRID / "content-neural.pdf"), replay_service, mode="neural", semester=7, stage=3)
    assert not [s for s in report["pipeline"]["skipped"] if s["reason"] in LLM_SKIPS]
    assert all(not f["blocking"] for f in report["findings"])


@pytest.mark.negative
def test_replay_miss_is_reported_not_invented(replay_service, tmp_path, monkeypatch):
    """Записи нет — содержание помечается непроверенным, а не выдумывается."""
    monkeypatch.setenv("SAPSR_LLM_REPLAY_DIR", str(tmp_path))
    service = build_service()
    report = check_pdf(str(HYBRID / "content-formats.pdf"), service, mode="hybrid", semester=7, stage=3)
    assert [s for s in report["pipeline"]["skipped"] if s["reason"] in LLM_SKIPS]
    assert not [f for f in report["findings"] if f["category"] == "content"]
