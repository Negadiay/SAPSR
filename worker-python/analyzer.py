"""Совместимый вход прежнего анализатора и запуск проверки из командной строки.

    analyze_pdf(path) -> {"status": "SUCCESS" | "FAIL", "errors": [...]}   — формат v1, как раньше
    check_pdf(path, ...) -> отчёт v2 (dict)

Командная строка:
    python analyzer.py работа.pdf                       # отчёт v1, как раньше
    python analyzer.py работа.pdf --report v2           # отчёт v2
    python analyzer.py работа.pdf --mode symbolic       # symbolic | hybrid | neural
    python analyzer.py работа.pdf --semester 7 --stage 2 --work-type course_project
    python analyzer.py работа.pdf --dump-document doc.json   # модель документа в JSON
    python analyzer.py работа.pdf --llm off --iis off   # без модели и без ИИС

Логика проверок — в пакете sapsr, параметры и тексты правил — в kb/.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional

from sapsr.checks.engine import validate_rules
from sapsr.directories import Directory, make_directory
from sapsr.document.build import build_document
from sapsr.document.extract import extract
from sapsr.document.labeling import RuleLabeler
from sapsr.errors import CheckFailure
from sapsr.kb.loader import KnowledgeBase, load_kb
from sapsr.llm.client import LlmClient
from sapsr.pipeline import CheckService
from sapsr.report.legacy import failure_v1, to_v1
from sapsr.settings import Settings
from sapsr.task import Task


def build_service(settings: Optional[Settings] = None, kb: Optional[KnowledgeBase] = None,
                  directory: Optional[Directory] = None, llm: Optional[LlmClient] = None) -> CheckService:
    settings = settings or Settings.from_env()
    kb = kb or load_kb(settings.kb_dir, validate_rules=validate_rules)
    if llm is None and settings.llm.mode != "off":
        llm = LlmClient(settings.llm)
    return CheckService(kb, llm, directory or make_directory(settings.iis_mode), mode=settings.mode,
                        max_pages=settings.max_pages, task_timeout_s=settings.task_timeout_s)


def _task_for(path: str, work_type=None, semester=None, stage=None, source_format="pdf",
              last_name=None) -> Task:
    return Task.model_validate({
        "schema": 2, "task_id": 0, "file_path": path,
        "file": {"source_format": source_format},
        "profile": {"work_type": work_type, "semester": semester, "stage": stage},
        "student": {"last_name": last_name},
    })


def check_pdf(path: str, service: Optional[CheckService] = None, **task_fields) -> dict:
    """Полный отчёт v2 (последняя фаза)."""
    service = service or build_service()
    mode = task_fields.pop("mode", None)
    profile_id = task_fields.pop("profile_id", None)
    report = None
    for phase in service.check(_task_for(path, **task_fields), Path(path), mode=mode, profile_id=profile_id):
        report = phase.report
    return report.to_json_dict()


def analyze_pdf(path: str, service: Optional[CheckService] = None) -> dict:
    """Прежний вход: результат в формате v1. Без модели — только оформление, как раньше."""
    service = service or build_service()
    try:
        report = None
        for phase in service.check(_task_for(path), Path(path)):
            report = phase.report
        return to_v1(report)
    except CheckFailure as failure:
        return failure_v1(failure.user_message, failure.code)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Проверка пояснительной записки SAPSR")
    parser.add_argument("pdf")
    parser.add_argument("--report", choices=("v1", "v2"), default="v1")
    parser.add_argument("--mode", choices=("symbolic", "hybrid", "neural"))
    parser.add_argument("--profile", dest="profile_id")
    parser.add_argument("--work-type", choices=("course_work", "course_project", "diploma"))
    parser.add_argument("--semester", type=int)
    parser.add_argument("--stage", type=int)
    parser.add_argument("--source-format", choices=("pdf", "docx"), default="pdf")
    parser.add_argument("--last-name", help="фамилия студента для сверки с титульным листом")
    parser.add_argument("--llm", choices=("live", "record", "replay", "stub", "off"))
    parser.add_argument("--iis", choices=("http", "off"))
    parser.add_argument("--dump-document", metavar="JSON")
    parser.add_argument("--log", choices=("json", "text", "none"), default="none")
    args = parser.parse_args(argv)

    import os
    if args.llm:
        os.environ["SAPSR_LLM_MODE"] = args.llm
    if args.iis:
        os.environ["SAPSR_IIS_MODE"] = args.iis
    if args.log != "none":
        from sapsr.log import setup_logging
        setup_logging(args.log, "INFO")

    settings = Settings.from_env()
    kb = load_kb(settings.kb_dir, validate_rules=validate_rules)
    if args.dump_document:
        raw = extract(args.pdf, kb.layout, settings.max_pages)
        doc = build_document(raw, RuleLabeler(kb.layout, kb.concepts), kb.concepts, args.source_format)
        Path(args.dump_document).write_text(doc.model_dump_json(indent=1), encoding="utf-8")
    service = build_service(settings, kb)
    try:
        if args.report == "v1" and not any((args.mode, args.profile_id, args.semester, args.stage, args.work_type)):
            result = analyze_pdf(args.pdf, service)
        else:
            result = check_pdf(args.pdf, service, mode=args.mode, profile_id=args.profile_id,
                               work_type=args.work_type, semester=args.semester, stage=args.stage,
                               source_format=args.source_format, last_name=args.last_name)
            if args.report == "v1":
                from sapsr.report.legacy import to_v1
                from sapsr.report.model import Report
                result = to_v1(Report.model_validate(result))
    except CheckFailure as failure:
        print(json.dumps({"error": {"code": failure.code, "message": failure.user_message}},
                         ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
