"""Общие настройки pytest для обработчика.

Запуск из папки worker-python:  python -m pytest
Группы тестов (методичка, план Жерко Ж1) — маркеры typical / boundary / negative, см. pytest.ini.
"""

import sys
from pathlib import Path

import pytest

WORKER_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = WORKER_DIR.parent
FIXTURES = REPO_ROOT / "test-fixtures"

sys.path.insert(0, str(WORKER_DIR))
sys.path.insert(0, str(Path(__file__).resolve().parent))


@pytest.fixture(autouse=True)
def offline_bsuir_api(monkeypatch):
    """Без сети: справочники ИИС БГУИР «недоступны», проверки по ним пропускаются.
    Тесты не должны зависеть от внешнего сервиса и его данных."""
    import analyzer

    monkeypatch.setattr(analyzer, "_get_known_specialties", lambda: None)
    monkeypatch.setattr(analyzer, "_get_known_employee_names", lambda: None)


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES
