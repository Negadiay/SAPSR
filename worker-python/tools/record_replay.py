"""Запись ответов языковой модели для режима replay (план 3-й опроцентовки, Т1).

Прогоняет документы корпуса test-fixtures/hybrid на живой модели в режимах hybrid и neural
и складывает ответы в test-fixtures/llm-replay. После этого те же отчёты получаются без
Ollama: `analyzer.py … --llm replay`, tools/evaluate.py, сквозной прогон scripts/check.ps1.

    python tools/record_replay.py                    # весь корпус, оба режима
    python tools/record_replay.py content-kb.pdf     # один документ
    python tools/record_replay.py --modes hybrid

Профиль проверки документа — поле "profile" в expected.json (по умолчанию 7 семестр, этап 3:
на нём проверяются все требования семестра, поэтому записи подходят и для этапа 2).
Записывать только вымышленные документы корпуса: в ответах есть цитаты из текста.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(WORKER))

CORPUS = WORKER.parent / "test-fixtures" / "hybrid"
REPLAY = WORKER.parent / "test-fixtures" / "llm-replay"
DEFAULT_PROFILE = {"semester": 7, "stage": 3}


def corpus_documents(corpus: Path = CORPUS) -> dict[str, dict]:
    """Документы корпуса с ожидаемыми результатами (без служебных ключей)."""
    data = json.loads((corpus / "expected.json").read_text(encoding="utf-8"))
    return {name: entry for name, entry in data.items() if not name.startswith("_")}


def needs_model(entry: dict) -> bool:
    """До модели доходят документы, которые читаются: без сбоя чтения и с текстом."""
    blocked = {"DOC-NO-TEXT", "DOC-TEXT-UNREADABLE", "DOC-EMPTY"}
    return "failure" not in entry and not blocked & set(entry.get("findings", []))


def profile_of(entry: dict) -> dict:
    return {**DEFAULT_PROFILE, **entry.get("profile", {})}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("documents", nargs="*", help="имена файлов корпуса; по умолчанию — все")
    parser.add_argument("--modes", nargs="+", default=["hybrid", "neural"], choices=("hybrid", "neural"))
    parser.add_argument("--replay-dir", type=Path, default=REPLAY)
    args = parser.parse_args(argv)

    os.environ["SAPSR_LLM_MODE"] = "record"
    os.environ["SAPSR_LLM_REPLAY_DIR"] = str(args.replay_dir)
    os.environ.setdefault("SAPSR_IIS_MODE", "off")
    # Запись не должна обрываться по времени задания: на слабой видеокарте документ идёт несколько минут.
    os.environ.setdefault("SAPSR_TASK_TIMEOUT_S", "3600")
    from analyzer import build_service, check_pdf

    documents = corpus_documents()
    names = args.documents or [n for n, e in documents.items() if needs_model(e)]
    service = build_service()
    if service.llm is None or not service.llm.available():
        print("Модель недоступна — запустите Ollama.", file=sys.stderr)
        return 1
    failed = 0
    for name in names:
        entry = documents.get(name, {})
        for mode in args.modes:
            started = time.perf_counter()
            report = check_pdf(str(CORPUS / name), service, mode=mode,
                               source_format=entry.get("source_format", "pdf"), **profile_of(entry))
            skipped = [s["reason"] for s in report["pipeline"]["skipped"]
                       if s["reason"] in ("LLM_UNAVAILABLE", "LLM_SCHEMA", "TIMEOUT")]
            failed += bool(skipped)
            print(f"{name:28} {mode:7} {time.perf_counter() - started:6.1f} с  "
                  f"замечаний: {len(report['findings'])}  {'ПРОПУСКИ: ' + ', '.join(skipped) if skipped else ''}",
                  flush=True)
    print(f"записей в {args.replay_dir}: {len(list(args.replay_dir.rglob('*.json')))}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
