"""Контракт «Отчёт v2»: схема, примеры и то, что реально выдаёт обработчик, — одно и то же."""

from __future__ import annotations

import json
import subprocess
import sys

import jsonschema
import pytest

from conftest import CONTRACTS, HYBRID, WORKER, final_report
from sapsr.report.legacy import legacy_severity, to_v1
from sapsr.report.model import Report

SCHEMA = json.loads((CONTRACTS / "report-v2.schema.json").read_text(encoding="utf-8"))


def validate(report: dict) -> None:
    jsonschema.Draft202012Validator(SCHEMA).validate(report)


def test_schema_file_is_up_to_date():
    result = subprocess.run([sys.executable, str(WORKER / "tools" / "export_contract.py"), "--check"],
                            capture_output=True, text=True, encoding="utf-8")
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize("name", ["format.json", "contradiction.json", "requirements.json"])
def test_report_examples_are_valid(name):
    report = json.loads((CONTRACTS / "examples" / "report-v2" / name).read_text(encoding="utf-8"))
    validate(report)
    Report.model_validate(report)


@pytest.mark.parametrize("name", ["result-format.json", "result-content.json"])
def test_queue_examples_carry_valid_findings(name):
    """В примерах протокола очередей отчёт сокращён, но каждое замечание — по схеме v2."""
    message = json.loads((CONTRACTS / "examples" / "queue-v2" / name).read_text(encoding="utf-8"))
    finding_schema = {"$defs": SCHEMA["$defs"], "$ref": "#/$defs/Finding"}
    for finding in message["report"]["findings"]:
        jsonschema.Draft202012Validator(finding_schema).validate(finding)


def test_produced_reports_are_valid(service_factory):
    for name in ("content-formats.pdf", "narrow-margin.pdf", "scan-no-text.pdf", "hidden-text.pdf"):
        report = final_report(service_factory(), HYBRID / name, semester=7, stage=2)
        validate(report.to_json_dict())


def test_only_symbolic_findings_block(service_factory):
    for name in ("content-formats.pdf", "content-kb.pdf", "content-neural.pdf", "hidden-text.pdf"):
        report = final_report(service_factory(), HYBRID / name, semester=7, stage=2)
        assert all(f.origin == "symbolic" for f in report.findings if f.blocking)


def test_legacy_fields_kept(service_factory):
    report = final_report(service_factory(), HYBRID / "narrow-margin.pdf")
    v1 = to_v1(report)
    assert v1["status"] == "SUCCESS"
    error = v1["errors"][0]
    for key in ("severity", "page", "location", "message", "rule", "found", "fix", "context"):
        assert key in error
    assert error["page"] == "2–8" and error["location"] == "Страницы: 2–8"


@pytest.mark.parametrize("severity, blocking, legacy", [
    ("critical", True, "critical"), ("major", True, "major"), ("major", False, "warning"),
    ("minor", False, "minor"), ("info", False, "warning"),
])
def test_legacy_severity(severity, blocking, legacy):
    from sapsr.report.model import Finding
    f = Finding(id="f-001", requirement_id="X-1", category="format", severity=severity, origin="symbolic",
                blocking=blocking, message="m")
    assert legacy_severity(f) == legacy
