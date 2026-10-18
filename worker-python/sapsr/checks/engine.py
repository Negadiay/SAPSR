"""Шаг 3 цепочки: правила оформления и структуры по каталогу базы знаний.

Движок берёт активные правила профиля, вызывает зарегистрированные проверки с
параметрами из базы знаний и строит замечания по шаблонам правил. Ошибка одной
проверки не роняет остальные: правило попадает в pipeline.skipped с причиной CHECK_ERROR.
"""

from __future__ import annotations

import logging
from typing import Iterable, Optional

from pydantic import ValidationError

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import COMMON_VALUES, REGISTRY, Hit, SkipCheck
from sapsr.document.text import pages_to_range
from sapsr.kb.loader import ActiveRule, KnowledgeBase, KnowledgeBaseError, ResolvedProfile, template_fields
from sapsr.report.model import Finding, SkippedCheck, SourceRef

log = logging.getLogger("sapsr.checks")

# Проверки, без которых остальные бессмысленны: нет страниц или текстового слоя.
GATE_CHECKS = ("document.pages", "document.text_layer")
TEMPLATE_FIELDS = ("message", "expected", "found", "fix", "explanation")


class _Values(dict):
    def __missing__(self, key):
        raise KeyError(key)


def render(template: str, values: dict) -> str:
    if not template:
        return ""
    try:
        return template.format_map(_Values(values))
    except (KeyError, ValueError, IndexError) as exc:
        log.warning("шаблон не заполнен", extra={"fields": {"error": repr(exc)}})
        return template


def _legacy_page(pages: list[int]):
    pages = sorted(set(pages))
    if not pages:
        return None
    if len(pages) == 1:
        return pages[0]
    return pages_to_range(pages)


def make_finding(active: ActiveRule, hit: Hit, origin: str = "symbolic",
                 confidence: Optional[float] = None, review: Optional[bool] = None,
                 status: str = "violated", extra_values: Optional[dict] = None) -> Finding:
    rule = active.rule
    values = {**active.params, **(extra_values or {}), **hit.values, "pages": pages_to_range(hit.pages)}
    expected = render(rule.expected, values)
    page = _legacy_page(hit.pages or [e.page for e in hit.evidence if e.page])
    context = hit.context or next((e.quote for e in hit.evidence if e.quote), "")
    location = hit.location or (f"Страница {page}" if isinstance(page, int) else
                                f"Страницы: {page}" if page else "Документ")
    # Блокировать может только правило над данными извлечения (решение 5).
    blocking = active.blocking and origin == "symbolic"
    return Finding(
        id="",
        requirement_id=rule.id,
        category=rule.category,
        status=status,
        severity=active.severity,
        origin=origin,
        confidence=confidence,
        needs_teacher_review=rule.review if review is None else review,
        blocking=blocking,
        title=rule.title,
        message=render(rule.message, values),
        expected=expected or None,
        found=render(rule.found, values) or None,
        evidence=hit.evidence,
        source=SourceRef(doc=rule.source.doc, clause=rule.source.clause),
        fix=render(rule.fix, values) or None,
        page=page,
        location=location,
        rule=expected or rule.title,
        context=context or None,
    )


def applicable(rules: Iterable[ActiveRule], ctx: CheckContext) -> list[ActiveRule]:
    profile = ctx.task.profile
    return [r for r in rules
            if r.rule.applies_to.matches(profile.work_type, profile.semester, profile.stage,
                                         ctx.task.file.source_format)]


def run_rule(active: ActiveRule, ctx: CheckContext, skipped: list[SkippedCheck]) -> list[Finding]:
    """Одно правило оформления: замечания либо запись в pipeline.skipped."""
    spec = REGISTRY[active.rule.check]
    try:
        params = spec.params(**active.params)
        hits = spec.func(ctx, params)
    except SkipCheck as skip:
        skipped.append(SkippedCheck(id=active.id, stage="format", reason=skip.reason, message=skip.message))
        return []
    except Exception:
        log.exception("ошибка проверки", extra={"fields": {"rule": active.id}})
        skipped.append(SkippedCheck(id=active.id, stage="format", reason="CHECK_ERROR",
                                    message="Проверка завершилась с ошибкой и пропущена."))
        return []
    return [make_finding(active, hit) for hit in hits]


def run_format_rules(profile: ResolvedProfile, ctx: CheckContext) -> tuple[list[Finding], list[SkippedCheck]]:
    rules = applicable(profile.rules_for("format"), ctx)
    skipped: list[SkippedCheck] = []
    findings: list[Finding] = []
    for active in (r for r in rules if r.rule.check in GATE_CHECKS):
        findings.extend(run_rule(active, ctx, skipped))
    if findings:
        skipped.append(SkippedCheck(id="*", stage="format", reason="NO_TEXT_LAYER",
                                    message="Остальные проверки не выполнялись: в документе нет текста."))
        return findings, skipped
    for active in (r for r in rules if r.rule.check not in GATE_CHECKS):
        findings.extend(run_rule(active, ctx, skipped))
    return findings, skipped


def validate_rules(kb: KnowledgeBase) -> None:
    """Проверка каталога против реестра проверок — вызывается при загрузке базы знаний."""
    import sapsr.checks  # noqa: F401 — регистрирует проверки
    from sapsr.content.reasoning import CONTENT_RULES

    errors: list[str] = []
    for rule in kb.rules.values():
        if rule.stage == "format":
            spec = REGISTRY.get(rule.check)
            if spec is None:
                errors.append(f"{rule.id}: нет проверки {rule.check!r}")
                continue
            try:
                spec.params(**rule.params)
            except ValidationError as exc:
                errors.append(f"{rule.id}: параметры не подходят проверке {rule.check}: {exc}")
                continue
            allowed = set(spec.params.model_fields) | spec.provides | COMMON_VALUES
        else:
            provides = CONTENT_RULES.get(rule.check)
            if provides is None:
                errors.append(f"{rule.id}: нет содержательного правила {rule.check!r}")
                continue
            allowed = set(provides) | set(rule.params) | COMMON_VALUES
        for name in TEMPLATE_FIELDS:
            unknown = template_fields(getattr(rule, name)) - allowed
            if unknown:
                errors.append(f"{rule.id}.{name}: неизвестные поля шаблона {sorted(unknown)}")
    for profile_id in kb.profiles:
        for active in kb.build_profile(profile_id).rules:
            spec = REGISTRY.get(active.rule.check)
            if spec is None or active.rule.stage != "format":
                continue
            try:
                spec.params(**active.params)
            except ValidationError as exc:
                errors.append(f"профиль {profile_id}, {active.id}: параметры: {exc}")
    if errors:
        raise KnowledgeBaseError("каталог правил:\n  " + "\n  ".join(errors))

