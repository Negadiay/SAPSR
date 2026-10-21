"""Цепочка проверки (план, раздел 0, «Целевая цепочка обработки»).

   1. extract    — pdfplumber: символы, строки, координаты; скрытый текст отделён
   2. structure  — разметка блоков → модель документа
   3. format     — правила оформления и структуры по каталогу        ← фаза format
   4. claims     — языковая модель: утверждения (hybrid) или замечания (neural)
   5. reasoning  — CLIPS: противоречия; требования семестра
   6. report     — полный отчёт v2                                    ← фаза content

Точки интеграции (что и в каком формате передаётся между компонентами):
   extract → structure : RawDocument (строки и символы с координатами)
   structure → правила : Document (sapsr/document/model.py, сохраняется в JSON)
   Document → модель   : фрагменты «[b-NNNN] текст» с границами данных (kb/prompts)
   модель → правила    : JSON по схеме (claims.py, requirements.py) → факты CLIPS
   правила → отчёт     : Finding (sapsr/report/model.py) → отчёт v2

Режимы (план, З10): symbolic — шаги 1–3; hybrid — все шаги; neural — модель сама пишет
замечания о содержании, правила оформления не применяются (только для сравнения).
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterator, Optional

from sapsr.checks.context import CheckContext
from sapsr.checks.engine import GATE_CHECKS, run_format_rules, run_rule
from sapsr.checks.title_page import StudentParams, student_on_title, surname_matches
from sapsr.content.claims import extract_claims
from sapsr.content.findings import claim_findings
from sapsr.content.fragments import build_fragments
from sapsr.content.neural import neural_findings
from sapsr.content.reasoning import Reasoner
from sapsr.content.requirements import check_requirements
from sapsr.deadline import Deadline
from sapsr.directories import Directory
from sapsr.document.build import build_document
from sapsr.document.extract import extract
from sapsr.document.labeling import Labeler, RuleLabeler
from sapsr.document.model import Document
from sapsr.errors import CheckFailure
from sapsr.kb.loader import ActiveRule, KnowledgeBase, ResolvedProfile
from sapsr.llm.client import LlmClient
from sapsr.report.build import build_report, title_page_info
from sapsr.report.model import (
    DocumentInfo, Finding, ModelInfo, PipelineWarning, PromptInfo, Report, RequirementStatus,
    SkippedCheck, StageInfo,
)
from sapsr.task import Task

log = logging.getLogger("sapsr.pipeline")

STAGES = ("extract", "structure", "format", "claims", "reasoning", "report")

ProgressCallback = Callable[[str, int], None]


@dataclass
class PhaseResult:
    phase: str
    report: Report
    passed: bool
    final: bool


@dataclass
class _Run:
    """Состояние одной проверки: время этапов, пропуски, предупреждения, метрики."""

    task: Task
    profile: ResolvedProfile
    mode: str
    deadline: Deadline
    stages: list[StageInfo] = field(default_factory=list)
    skipped: list[SkippedCheck] = field(default_factory=list)
    warnings: list[PipelineWarning] = field(default_factory=list)
    metrics: dict = field(default_factory=dict)
    prompts: list[PromptInfo] = field(default_factory=list)
    llm_used: bool = False


class CheckService:
    def __init__(self, kb: KnowledgeBase, llm: Optional[LlmClient], directory: Directory, *,
                 mode: str = "hybrid", max_pages: int = 300, task_timeout_s: Optional[float] = 540,
                 labeler: Optional[Labeler] = None, clock: Callable[[], datetime] = datetime.now):
        self.kb = kb
        self.llm = llm
        self.directory = directory
        self.mode = mode
        self.max_pages = max_pages
        self.task_timeout_s = task_timeout_s
        self.labeler = labeler or RuleLabeler(kb.layout, kb.concepts)
        self.clock = clock
        self.reasoner = Reasoner(kb.reasoning)

    # ── публичный вход ──────────────────────────────────────────────────────

    def check(self, task: Task, path: Path, progress: Optional[ProgressCallback] = None,
              mode: Optional[str] = None, profile_id: Optional[str] = None) -> Iterator[PhaseResult]:
        """Проверяет работу; выдаёт фазу format, затем (если нужна) фазу content.
        Сбои чтения файла — CheckFailure с кодом из протокола."""
        mode = mode or self.mode
        notify = progress or (lambda stage, index: None)
        profile = self.kb.resolve_profile(task.profile.work_type, task.profile.semester,
                                          task.profile.stage, explicit=profile_id)
        run = _Run(task=task, profile=profile, mode=mode, deadline=Deadline(self.task_timeout_s))
        llm_before = self.llm.stats.snapshot() if self.llm is not None else {}
        if profile.warning:
            run.warnings.append(PipelineWarning(code=profile.warning[0], message=profile.warning[1]))
        if task.file.source_format == "docx":
            run.warnings.append(PipelineWarning(
                code="CONVERTED_FROM_DOCX",
                message="Работа сдана в DOCX и проверена PDF-версия; номера страниц относятся к ней."))

        notify("extract", 1)
        with self._stage(run, "extract"):
            raw = extract(path, self.kb.layout, self.max_pages)
        if task.file.sha256 and task.file.sha256.lower() != raw.sha256:
            log.warning("хэш файла не совпадает с заданием", extra={"fields": {"sha256_task": task.file.sha256[:12],
                                                                              "sha256_file": raw.sha256[:12]}})
        notify("structure", 2)
        with self._stage(run, "structure"):
            doc = build_document(raw, self.labeler, self.kb.concepts, task.file.source_format)
        run.metrics.update({"pages": raw.page_count, "blocks": len(doc.blocks), "sections": len(doc.sections)})

        notify("format", 3)
        ctx = CheckContext(doc=doc, raw=raw, layout=self.kb.layout, concepts=self.kb.concepts, task=task,
                           directory=self.directory, now=self.clock())
        with self._stage(run, "format"):
            findings = self._format_findings(run, ctx)
        document_info = self._document_info(ctx, profile)
        passed = not any(f.blocking for f in findings)
        # Нет текста или страниц — содержательно проверять нечего, даже если профиль это разрешает.
        unreadable = any(f.blocking and self._is_gate(run, f) for f in findings)

        content_planned = self._plan_content(run, passed, unreadable)
        format_report = self._report(run, "format", findings, document_info, [], content_checked=False)
        yield PhaseResult("format", format_report, passed, final=not content_planned)
        if not content_planned:
            return

        all_findings = list(findings)
        requirements: list[RequirementStatus] = []
        notify("claims", 4)
        with self._stage(run, "claims"):
            fragments, dropped = build_fragments(doc, profile.content.sections or [],
                                                 profile.content.fragment_max_chars,
                                                 profile.content.max_chars_total)
            run.metrics.update({"content_fragments": len(fragments), "content_chars_dropped": dropped})
            if dropped:
                run.skipped.append(SkippedCheck(id="*", stage="claims", reason="BUDGET",
                                                message=f"В модель не поместилось {dropped} символов текста; "
                                                        "проверены разделы с наибольшим приоритетом."))
            if not fragments:
                run.skipped.append(SkippedCheck(id="*", stage="claims", reason="NO_SECTIONS",
                                                message="Не найдены разделы для содержательной проверки."))
            claims = []
            if mode == "neural":
                prompt = self.kb.prompt("neural")
                run.prompts.append(PromptInfo(id=prompt.id, version=prompt.version))
                neural, metrics = neural_findings(doc, fragments, self.llm, prompt, profile,
                                                  self._neural_rule(), run.deadline, run.skipped)
                all_findings.extend(neural)
                run.metrics.update(metrics)
            elif fragments:
                prompt = self.kb.prompt("claims")
                run.prompts.append(PromptInfo(id=prompt.id, version=prompt.version))
                claims, stats = extract_claims(doc, fragments, self.llm, prompt, self.kb.concepts,
                                               profile.content, run.deadline, run.skipped)
                run.metrics.update(stats.as_metrics())

        if mode == "hybrid":
            notify("reasoning", 5)
            with self._stage(run, "reasoning"):
                firings = self.reasoner.run_claims(claims, self.kb.concepts)
                run.metrics["reasoning_firings"] = len(firings)
                all_findings.extend(claim_findings(firings, claims, profile, self.kb.concepts))
                if profile.content.requirement_sets:
                    prompt = self.kb.prompt("requirements")
                    run.prompts.append(PromptInfo(id=prompt.id, version=prompt.version))
                    requirements, req_findings, req_metrics = check_requirements(
                        doc, self.kb, profile, task, self.llm, run.deadline, run.skipped)
                    all_findings.extend(req_findings)
                    run.metrics.update(req_metrics)
        if self.llm is not None:
            run.metrics.update(self.llm.stats.since(llm_before))

        notify("report", 6)
        started = time.perf_counter()
        content_report = self._report(run, "content", all_findings, document_info, requirements,
                                      content_checked=True)
        # Время сборки отчёта дописывается в уже собранный отчёт.
        stage = StageInfo(stage="report", status="done", ms=int((time.perf_counter() - started) * 1000))
        run.stages.append(stage)
        content_report.pipeline.stages.append(stage)
        yield PhaseResult("content", content_report, passed, final=True)

    # ── шаги ────────────────────────────────────────────────────────────────

    def _format_findings(self, run: _Run, ctx: CheckContext) -> list[Finding]:
        if run.mode != "neural":
            findings, skipped = run_format_rules(run.profile, ctx)
            run.skipped.extend(skipped)
            return findings
        # Режим сравнения: из правил — только проверка, что в документе есть текст.
        findings: list[Finding] = []
        for active in run.profile.rules_for("format"):
            if active.rule.check in GATE_CHECKS:
                findings.extend(run_rule(active, ctx, run.skipped))
        run.skipped.append(SkippedCheck(id="*", stage="format", reason="MODE_NEURAL",
                                        message="Режим сравнения neural: правила оформления не применялись."))
        return findings

    @staticmethod
    def _is_gate(run: _Run, finding: Finding) -> bool:
        active = run.profile.rule(finding.requirement_id)
        return active is not None and active.rule.check in GATE_CHECKS

    def _plan_content(self, run: _Run, passed: bool, unreadable: bool) -> bool:
        if unreadable:
            return False
        # Решение 9 (queue-v2.md, вопрос 3): отклонённая по оформлению работа проверяется содержательно,
        # если профиль не запрещает это явно.
        if not passed and run.profile.content.run_when_rejected is False:
            run.skipped.append(SkippedCheck(id="*", stage="claims", reason="REJECTED_BY_FORMAT",
                                            message="Работа отклонена по оформлению; содержание по профилю не проверялось."))
            return False
        if run.mode == "symbolic":
            run.skipped.append(SkippedCheck(id="*", stage="claims", reason="MODE_SYMBOLIC",
                                            message="Режим symbolic: содержательная проверка выключена."))
            return False
        if not run.profile.content.enabled:
            run.skipped.append(SkippedCheck(id="*", stage="claims", reason="DISABLED",
                                            message="Содержательная проверка выключена в профиле."))
            return False
        if self.llm is None or not self.llm.available():
            reason = self.llm.unavailable_reason() if self.llm else "Языковая модель не настроена."
            run.skipped.append(SkippedCheck(id="*", stage="claims", reason="LLM_UNAVAILABLE",
                                            message=reason + " Содержательная проверка не выполнялась."))
            return False
        run.llm_used = True
        if self.llm.is_cloud:
            run.warnings.append(PipelineWarning(
                code="CLOUD_LLM", message="Текст работы обрабатывался облачной моделью (включено настройкой)."))
            log.warning("используется облачная языковая модель")
        return True

    def _neural_rule(self) -> ActiveRule:
        rule = self.kb.rules["NEURAL-FREEFORM"]
        return ActiveRule(rule=rule, severity=rule.severity, blocking=False, params=dict(rule.params))

    def _document_info(self, ctx: CheckContext, profile: ResolvedProfile) -> DocumentInfo:
        student = None
        rule = self.kb.rules.get("TTL-STUDENT-MATCH")
        last_name = ctx.task.student.last_name
        matches = None
        if rule is not None and ctx.raw.pages:
            params = StudentParams(**rule.params)
            student = student_on_title(ctx, params)
            if student and last_name:
                matches = surname_matches(student, last_name)
        doc: Document = ctx.doc
        return DocumentInfo(
            pages=doc.meta.pages, sha256=doc.meta.sha256, source_format=doc.meta.source_format,
            main_font=doc.meta.main_font or None, main_size=doc.meta.main_size or None,
            title_page=title_page_info(student, last_name, matches),
        )

    def _models(self, run: _Run) -> list[ModelInfo]:
        labeler = ModelInfo(role="layout", name=self.labeler.name, version=self.labeler.version)
        models = [labeler]
        if run.llm_used and self.llm is not None:
            details = self.llm.model_details()
            models.append(ModelInfo(role="llm", name=self.llm.model_name, provider=self.llm.config.provider,
                                    quantization=details.get("quantization"), cloud=self.llm.is_cloud))
        return models

    def _report(self, run: _Run, phase: str, findings: list[Finding], document: DocumentInfo,
                requirements: list[RequirementStatus], content_checked: bool) -> Report:
        return build_report(
            phase=phase, kb=self.kb, profile=run.profile, task=run.task, mode=run.mode,
            findings=[f.model_copy(deep=True) for f in findings], document=document,
            stages=list(run.stages), skipped=list(run.skipped), warnings=list(run.warnings),
            models=self._models(run), prompts=list(run.prompts), metrics=dict(run.metrics),
            requirements=requirements, content_checked=content_checked,
        )

    class _StageTimer:
        def __init__(self, run: "_Run", name: str):
            self.run, self.name = run, name

        def __enter__(self):
            self.started = time.perf_counter()
            # Содержательные этапы сами пропускают оставшееся по тайм-ауту; без структуры и
            # оформления отчёта нет — это сбой проверки.
            if self.name in ("structure", "format") and self.run.deadline.expired:
                raise CheckFailure("TIMEOUT", f"этап {self.name}")
            return self

        def __exit__(self, exc_type, exc, tb):
            ms = int((time.perf_counter() - self.started) * 1000)
            status = "done" if exc_type is None else "failed"
            self.run.stages.append(StageInfo(stage=self.name, status=status, ms=ms))
            log.info("этап завершён", extra={"fields": {"stage": self.name, "status": status, "ms": ms}})
            return False

    def _stage(self, run: _Run, name: str) -> "_StageTimer":
        return self._StageTimer(run, name)
