"""Сборка отчёта v2 (план, З8): порядок и номера замечаний, сводка, раздел pipeline."""

from __future__ import annotations

from collections import Counter
from typing import Optional

from sapsr import __version__
from sapsr.kb.loader import KnowledgeBase, ResolvedProfile
from sapsr.report.model import (
    DocumentInfo, Finding, ModelInfo, Pipeline, PipelineWarning, ProfileInfo, PromptInfo, Report,
    RequirementStatus, SkippedCheck, StageInfo, Summary, TitlePageInfo,
)
from sapsr.task import Task

CATEGORY_ORDER = ("structure", "integrity", "format", "content", "requirements")
SEVERITY_ORDER = ("critical", "major", "minor", "info")


def order_findings(findings: list[Finding], kb: KnowledgeBase) -> list[Finding]:
    """Порядок не зависит от порядка вычисления — повторная проверка даёт те же номера."""
    rule_pos = {rule_id: i for i, rule_id in enumerate(kb.rules)}

    def key(f: Finding):
        first_page = min((e.page for e in f.evidence if e.page), default=0)
        return (
            0 if f.blocking else 1,
            CATEGORY_ORDER.index(f.category),
            SEVERITY_ORDER.index(f.severity),
            rule_pos.get(f.requirement_id, len(rule_pos)),
            f.requirement_id,
            first_page,
            f.message,
        )

    ordered = sorted(findings, key=key)
    for n, finding in enumerate(ordered, start=1):
        finding.id = f"f-{n:03d}"
    return ordered


def summarize(findings: list[Finding], content_checked: bool) -> Summary:
    blocking = sum(1 for f in findings if f.blocking)
    return Summary(
        passed=blocking == 0,
        blocking=blocking,
        findings=len(findings),
        by_category=dict(sorted(Counter(f.category for f in findings).items())),
        by_severity=dict(sorted(Counter(f.severity for f in findings).items())),
        needs_teacher_review=sum(1 for f in findings if f.needs_teacher_review),
        content_checked=content_checked,
    )


def build_report(*, phase: str, kb: KnowledgeBase, profile: ResolvedProfile, task: Task, mode: str,
                 findings: list[Finding], document: DocumentInfo, stages: list[StageInfo],
                 skipped: list[SkippedCheck], warnings: list[PipelineWarning], models: list[ModelInfo],
                 prompts: list[PromptInfo], metrics: dict, requirements: Optional[list[RequirementStatus]] = None,
                 content_checked: bool = False) -> Report:
    ordered = order_findings(list(findings), kb)
    return Report(
        phase=phase,
        profile=ProfileInfo(id=profile.id, title=profile.title, work_type=task.profile.work_type,
                            semester=task.profile.semester, stage=task.profile.stage,
                            is_default=profile.is_default),
        summary=summarize(ordered, content_checked),
        findings=ordered,
        requirements=requirements or [],
        document=document,
        pipeline=Pipeline(
            mode=mode, worker_version=__version__, kb_version=kb.version, models=models,
            prompts=prompts, stages=stages, skipped=skipped, warnings=warnings,
            metrics=dict(sorted(metrics.items())),
        ),
    )


def title_page_info(student_name: Optional[str], last_name: Optional[str], matches: Optional[bool],
                    work_type: Optional[str] = None) -> TitlePageInfo:
    return TitlePageInfo(student_name=student_name, matches_registration=matches if last_name else None,
                         work_type=work_type)
