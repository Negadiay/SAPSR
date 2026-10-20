"""Результат в прежнем формате (v1) для бэкенда, который ещё не принимает фазы.

Бэкенд v1 считает «ошибками» замечания с severity critical или major, остальные —
предупреждениями, а статус берёт из поля status. Чтобы счётчики в уведомлении
совпадали с решением, severity в v1 отражает блокировку: неблокирующие замечания
уровня major (например, содержательные) приходят как warning.
"""

from __future__ import annotations

from sapsr.report.model import Finding, Report


def legacy_severity(finding: Finding) -> str:
    if finding.blocking:
        return finding.severity if finding.severity in ("critical", "major") else "major"
    return "minor" if finding.severity == "minor" else "warning"


def to_v1_error(finding: Finding) -> dict:
    error = {
        "severity": legacy_severity(finding),
        "category": finding.category,
        "page": finding.page,
        "location": finding.location or "Документ",
        "message": finding.message,
        "rule": finding.rule or finding.expected or "",
        "found": finding.found or "",
        "fix": finding.fix or "",
        "context": finding.context or "",
        # Поля v2 — бэкенд и интерфейс v1 их пропускают.
        "requirement_id": finding.requirement_id,
        "origin": finding.origin,
        "blocking": finding.blocking,
        "needs_teacher_review": finding.needs_teacher_review,
    }
    if finding.confidence is not None:
        error["confidence"] = finding.confidence
    return error


def to_v1(report: Report) -> dict:
    """{"status": "SUCCESS" | "FAIL", "errors": [...]} — как возвращал прежний analyze_pdf."""
    return {
        "status": "SUCCESS" if report.summary.passed else "FAIL",
        "errors": [to_v1_error(f) for f in report.findings],
    }


def failure_v1(message: str, detail: str = "") -> dict:
    """Сбой проверки в формате v1: одно критическое замечание, как раньше при исключении."""
    return {"status": "FAIL", "errors": [{
        "severity": "critical",
        "category": "structure",
        "page": None,
        "location": "Документ",
        "message": message,
        "rule": "Файл должен открываться и читаться как PDF.",
        "found": detail,
        "fix": "Сохраните работу в PDF заново и загрузите снова.",
        "context": "",
    }]}
