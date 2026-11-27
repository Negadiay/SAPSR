"""Общие заготовки тестов обработчика.

Группы тестов (маркеры pytest, объявлены в pytest.ini; план Жерко, Ж1): typical — типичные
сценарии, boundary — граничные, negative — отрицательные. Тесты не ходят в сеть: ИИС — StaticDirectory,
языковая модель — сценарная заглушка (tests/scripted_llm.py) или поддельный сервер Ollama.
"""

from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

import pytest

WORKER = Path(__file__).resolve().parents[1]
REPO = WORKER.parent
sys.path.insert(0, str(WORKER))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from sapsr.checks.engine import validate_rules  # noqa: E402
from sapsr.directories import NullDirectory, StaticDirectory  # noqa: E402
from sapsr.kb.loader import load_kb  # noqa: E402
from sapsr.llm.client import LlmClient, LlmConfig  # noqa: E402
from sapsr.pipeline import CheckService  # noqa: E402
from sapsr.task import Task  # noqa: E402
from scripted_llm import ScriptedModel  # noqa: E402

FIXTURES = REPO / "test-fixtures"
HYBRID = FIXTURES / "hybrid"
CONTRACTS = REPO / "docs" / "contracts"
NOW = datetime(2026, 10, 18, 12, 0)


@pytest.fixture(scope="session")
def kb():
    return load_kb(validate_rules=validate_rules)


@pytest.fixture
def directory():
    return StaticDirectory(specialty_codes=["6-05-0611-03"],
                           employee_names=["Сидоренко Семён Семёнович", "Петров Пётр Петрович"])


@pytest.fixture
def model():
    return ScriptedModel()


def make_llm(model: ScriptedModel, tmp_path: Path | None = None) -> LlmClient:
    return LlmClient(LlmConfig(mode="stub", model="scripted"), stub=model)


@pytest.fixture
def service_factory(kb, directory, model):
    def factory(mode="hybrid", llm="stub", directory_=None, **kwargs) -> CheckService:
        client = make_llm(model) if llm == "stub" else llm
        return CheckService(kb, client, directory_ or directory, mode=mode, clock=lambda: NOW, **kwargs)
    return factory


def make_task(path: Path | str = "x.pdf", **profile) -> Task:
    student = profile.pop("student", {})
    source_format = profile.pop("source_format", "pdf")
    return Task.model_validate({
        "schema": 2, "task_id": 153, "trace_id": "4b1e0c7a-2f3d-4a5b-8c6d-7e8f9a0b1c2d",
        "file_path": str(path), "file": {"source_format": source_format},
        "profile": profile or None, "student": student or None,
    })


def run(service: CheckService, pdf: Path, **profile):
    """Все фазы проверки: [(phase, passed, final, report)]."""
    task = make_task(pdf, **profile)
    return [(p.phase, p.passed, p.final, p.report) for p in service.check(task, pdf)]


def final_report(service: CheckService, pdf: Path, **profile):
    return run(service, pdf, **profile)[-1][3]


def ids(report) -> list[str]:
    return [f.requirement_id for f in report.findings]


@pytest.fixture
def null_directory():
    return NullDirectory()


@pytest.fixture(autouse=True)
def offline(monkeypatch):
    """Без сети: тесты, которые собирают проверку из окружения (analyze_pdf, Settings.from_env),
    не ходят ни в ИИС, ни к модели. Остальные тесты передают ИИС и модель явно."""
    monkeypatch.setenv("SAPSR_IIS_MODE", "off")
    monkeypatch.setenv("SAPSR_LLM_MODE", "off")


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES
