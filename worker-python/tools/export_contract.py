"""Выгружает контракт «Отчёт v2»: JSON-схему и примеры отчётов.

    python tools/export_contract.py            # перезаписать docs/contracts/report-v2.schema.json и примеры
    python tools/export_contract.py --check    # только сверить (так делает тест test_contract.py)

Схема строится из pydantic-классов sapsr/report/model.py — она не может отстать от кода.
Примеры собираются настоящей цепочкой на документах test-fixtures/hybrid; ответы модели —
сценарная заглушка из tests/scripted_llm.py, поэтому в pipeline.models подставлены модель
и квантование из решения 1, а время этапов — как на машине, где запускался скрипт.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
REPO = WORKER.parent
sys.path.insert(0, str(WORKER))
sys.path.insert(0, str(WORKER / "tests"))

from sapsr.report.model import Report  # noqa: E402

SCHEMA_PATH = REPO / "docs" / "contracts" / "report-v2.schema.json"
EXAMPLES = REPO / "docs" / "contracts" / "examples" / "report-v2"


def schema() -> dict:
    body = Report.model_json_schema(by_alias=True, mode="validation")
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://github.com/Negadiay/SAPSR/docs/contracts/report-v2.schema.json",
        "title": "Отчёт v2 SAPSR",
        "description": "Отчёт о проверке пояснительной записки. Описание полей — docs/contracts/report-v2.md.",
        **{k: v for k, v in body.items() if k not in ("title",)},
    }


def dumps(data) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def examples() -> dict[str, dict]:
    from sapsr.checks.engine import validate_rules
    from sapsr.directories import StaticDirectory
    from sapsr.kb.loader import load_kb
    from sapsr.llm.client import LlmClient, LlmConfig
    from sapsr.pipeline import CheckService
    from sapsr.task import Task
    from scripted_llm import ScriptedModel

    kb = load_kb(validate_rules=validate_rules)
    directory = StaticDirectory(["6-05-0611-03"], ["Сидоренко Семён Семёнович", "Петров Пётр Петрович"])
    service = CheckService(kb, LlmClient(LlmConfig(mode="stub", model="scripted"), stub=ScriptedModel()),
                           directory, clock=lambda: datetime(2026, 10, 18, 12, 0))

    def phases(name: str, **profile):
        pdf = REPO / "test-fixtures" / "hybrid" / name
        task = Task.model_validate({"schema": 2, "task_id": 153, "file_path": str(pdf),
                                    "profile": profile or None, "student": {"last_name": "Иванов"}})
        return [p.report.to_json_dict() for p in service.check(task, pdf)]

    def tidy(report: dict) -> dict:
        for model in report["pipeline"]["models"]:
            if model["role"] == "llm":
                model.update({"name": "qwen3.5:4b", "quantization": "Q4_K_M", "provider": "ollama"})
        # Время этапов зависит от машины — в примерах его нет.
        for stage in report["pipeline"]["stages"]:
            stage.pop("ms", None)
        report["pipeline"]["metrics"].pop("llm_ms", None)
        report["document"]["sha256"] = "…"
        return report

    sem7 = {"work_type": "course_project", "semester": 7, "stage": 2}
    return {
        "format.json": tidy(phases("narrow-margin.pdf", **sem7)[0]),
        "contradiction.json": tidy(phases("content-formats.pdf")[-1]),
        "requirements.json": tidy(phases("content-kb.pdf", **sem7)[-1]),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    wanted = {SCHEMA_PATH: dumps(schema())}
    if not args.check:
        EXAMPLES.mkdir(parents=True, exist_ok=True)
        for name, report in examples().items():
            wanted[EXAMPLES / name] = dumps(report)
    stale = [p for p, text in wanted.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
    if args.check:
        for path in stale:
            print(f"устарел: {path.relative_to(REPO)} — запустите python tools/export_contract.py")
        return 1 if stale else 0
    for path, text in wanted.items():
        # newline: на Windows write_text иначе пишет CRLF, а файлы в репозитории — с LF
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"записан: {path.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
