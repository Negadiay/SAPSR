"""Проверка требований семестра (план, З5).

1. Требования оцифрованы в kb/requirements/<семестр>.yaml.
2. Для каждого требования отбираются фрагменты-кандидаты: BM25 по блокам частей записки,
   где ожидается свидетельство (поиск по эмбеддингам добавится вместе с индексом нормативов, З6).
3. Языковая модель оценивает каждый кандидат: supports | contradicts | irrelevant, цитата, уверенность.
4. Цитаты проверяются символьно; статус требования выводят правила CLIPS (kb/reasoning/requirements.clp).
5. Итог — карта покрытия требований в отчёте и замечания по нарушенным и неустановленным требованиям.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Literal, Optional

import clips
from pydantic import BaseModel, ConfigDict, Field
from rank_bm25 import BM25Okapi

from sapsr.checks.engine import make_finding
from sapsr.checks.registry import Hit
from sapsr.content.reasoning import Reasoner
from sapsr.content.verify import locate_quote
from sapsr.deadline import Deadline
from sapsr.document.model import Block, Document
from sapsr.document.text import fold
from sapsr.kb.loader import KnowledgeBase, ResolvedProfile
from sapsr.kb.schema import SemesterRequirement
from sapsr.llm.client import LlmClient, LlmError, LlmSchemaError, LlmTimeout
from sapsr.report.model import Evidence, Finding, RequirementStatus, SkippedCheck, SourceRef
from sapsr.task import Task

STATUS_PRIORITY = ("disputed", "violated", "not_established", "satisfied", "not_applicable")
STATUS_RULE = {"violated": "REQ-VIOLATED", "disputed": "REQ-DISPUTED", "not_established": "REQ-NOT-ESTABLISHED"}
_RE_TOKEN = re.compile(r"[a-zа-я0-9]+")
# Длина основы слова для поиска: грубая замена стеммера для русских окончаний.
STEM_CHARS = 6


class Judgement(BaseModel):
    model_config = ConfigDict(extra="ignore")
    block_id: str = Field(pattern=r"^b-\d{4,}$")
    verdict: Literal["supports", "contradicts", "irrelevant"]
    quote: str = Field("", max_length=600)
    confidence: float = Field(ge=0, le=1)


class Judgements(BaseModel):
    model_config = ConfigDict(extra="ignore")
    judgements: list[Judgement] = Field(default_factory=list, max_length=20)


@dataclass
class Verdict:
    requirement: str
    block: Block
    verdict: str
    quote: str
    confidence: float
    verified: bool


def tokens(text: str) -> list[str]:
    return [t[:STEM_CHARS] for t in _RE_TOKEN.findall(fold(text)) if len(t) > 2]


def candidates(doc: Document, req: SemesterRequirement, k: int, min_score: float) -> tuple[list[Block], bool]:
    """Кандидаты из ожидаемых частей записки; если таких частей нет — из всего текста.
    Второе значение — есть ли в записке ожидаемая часть."""
    allowed = set(req.expected_in)
    in_parts = [b for b in doc.blocks if b.type in ("paragraph", "list_item")
                and (s := doc.section(b.section_id)) is not None and s.effective_kind in allowed]
    part_present = bool(in_parts)
    pool = in_parts or [b for b in doc.blocks if b.type in ("paragraph", "list_item")]
    if not pool:
        return [], part_present
    corpus = [tokens(b.text) for b in pool]
    if not any(corpus):
        return [], part_present
    bm25 = BM25Okapi(corpus)
    query = tokens(" ".join([req.title, req.text, *req.keywords]))
    scores = bm25.get_scores(query)
    ranked = sorted(range(len(pool)), key=lambda i: (-scores[i], doc.block_pos(pool[i].id)))
    chosen = [pool[i] for i in ranked[:k] if scores[i] >= min_score]
    chosen.sort(key=lambda b: doc.block_pos(b.id))
    return chosen, part_present


def check_requirements(doc: Document, kb: KnowledgeBase, profile: ResolvedProfile, task: Task,
                       llm: Optional[LlmClient], deadline: Deadline, skipped: list[SkippedCheck],
                       ) -> tuple[list[RequirementStatus], list[Finding], dict]:
    settings = profile.content
    prompt = kb.prompt("requirements")
    reqs: list[SemesterRequirement] = [r for set_id in (settings.requirement_sets or [])
                                       for r in kb.requirement_sets[set_id].requirements]
    if not reqs:
        return [], [], {}
    stage = task.profile.stage
    facts_req, facts_judge = [], []
    verdicts: dict[str, list[Verdict]] = {}
    unevaluated: dict[str, str] = {}
    llm_ok = llm is not None and llm.available()
    metrics = {"requirements_checked": 0, "requirements_quotes_rejected": 0}

    for req in reqs:
        applicable = stage is None or stage in req.stages
        chosen, part_present = candidates(doc, req, settings.requirement_candidates, settings.requirement_min_bm25)
        facts_req.append({"id": req.id, "applicable": _sym(applicable), "part-present": _sym(part_present)})
        if not applicable or not chosen:
            continue
        if not llm_ok:
            unevaluated[req.id] = "Не оценивалось: языковая модель недоступна."
            skipped.append(SkippedCheck(id=req.id, stage="reasoning", reason="LLM_UNAVAILABLE",
                                        message=f"Требование «{req.title}» не оценивалось: языковая модель недоступна."))
            continue
        if deadline.expired:
            unevaluated[req.id] = "Не оценивалось: истекло время проверки."
            skipped.append(SkippedCheck(id=req.id, stage="reasoning", reason="TIMEOUT",
                                        message=f"Требование «{req.title}» не оценивалось: истекло время."))
            continue
        data = "\n".join(f"[{b.id}] {b.text}" for b in chosen)
        system, user = prompt.render(title=req.title, text=req.text, sufficient=req.sufficient, data=data)
        try:
            result = llm.complete_json(prompt.ref, system, user, Judgements, timeout_s=deadline.remaining())
        except (LlmSchemaError, LlmTimeout, LlmError) as exc:
            reason = ("LLM_SCHEMA" if isinstance(exc, LlmSchemaError)
                      else "TIMEOUT" if isinstance(exc, LlmTimeout) else "LLM_UNAVAILABLE")
            unevaluated[req.id] = "Не оценивалось: ответ модели не получен."
            skipped.append(SkippedCheck(id=req.id, stage="reasoning", reason=reason,
                                        message=f"Требование «{req.title}» не оценивалось: {exc}"))
            continue
        metrics["requirements_checked"] += 1
        by_id = {b.id: b for b in chosen}
        for j in result.data.judgements:
            block = by_id.get(j.block_id)
            if block is None:
                continue
            verified = True
            if j.verdict != "irrelevant":
                found, _ = locate_quote(j.quote, block, [block], settings.quote_min_score, settings.quote_min_chars)
                verified = found is not None
                if not verified:
                    metrics["requirements_quotes_rejected"] += 1
            verdicts.setdefault(req.id, []).append(
                Verdict(req.id, block, j.verdict, j.quote.strip(), j.confidence, verified))
            facts_judge.append({"req": req.id, "block": block.id, "verdict": clips.Symbol(j.verdict),
                                "confidence": float(j.confidence), "verified": _sym(verified)})

    reasoner = Reasoner(kb.reasoning)
    rows = reasoner.run("requirements.clp",
                        {"requirement": facts_req, "judgement": facts_judge,
                         "threshold": [{"value": float(settings.review_threshold)}]},
                        output="status")
    best: dict[str, dict] = {}
    for row in rows:
        current = best.get(row["req"])
        if current is None or STATUS_PRIORITY.index(row["value"]) < STATUS_PRIORITY.index(current["value"]):
            best[row["req"]] = row

    statuses: list[RequirementStatus] = []
    findings: list[Finding] = []
    for req in reqs:
        if req.id in unevaluated:
            # Модель не отвечала — это пропущенная проверка, а не замечание (план, З8).
            statuses.append(RequirementStatus(id=req.id, title=req.title, status="not_established",
                                              note=unevaluated[req.id],
                                              source=SourceRef(doc=req.source.doc, clause=req.source.clause)))
            continue
        row = best.get(req.id, {"value": "not_established", "review": "TRUE", "reason": "no-evidence"})
        relevant = [v for v in verdicts.get(req.id, []) if v.verified and v.verdict != "irrelevant"]
        relevant.sort(key=lambda v: (-v.confidence, doc.block_pos(v.block.id)))
        evidence = [Evidence(page=v.block.page, quote=v.quote or v.block.text[:300], block_id=v.block.id,
                             section=doc.section_label(v.block.section_id)) for v in relevant[:3]]
        confidence = round(max((v.confidence for v in relevant), default=0.0), 2) if relevant else None
        note = _note(row["reason"])
        status = RequirementStatus(
            id=req.id, title=req.title, status=row["value"], confidence=confidence,
            needs_teacher_review=row["review"] == "TRUE", evidence=evidence, note=note,
            source=SourceRef(doc=req.source.doc, clause=req.source.clause),
        )
        statuses.append(status)
        rule_id = STATUS_RULE.get(status.status)
        active = profile.rule(rule_id) if rule_id else None
        if active is None:
            continue
        parts = [kb.concepts.part(p).title for p in req.expected_in if kb.concepts.part(p)]
        hit = Hit(values={"requirement_title": req.title, "requirement_text": req.text,
                          "sufficient": req.sufficient, "expected_parts": ", ".join(f"«{p}»" for p in parts)},
                  evidence=evidence, pages=[e.page for e in evidence if e.page],
                  context=evidence[0].quote if evidence else "")
        finding = make_finding(active, hit, origin="hybrid", confidence=confidence,
                               review=status.needs_teacher_review or active.rule.review,
                               status="violated" if status.status == "violated" else status.status)
        finding.requirement_id = req.id
        finding.source = status.source
        findings.append(finding)
    return statuses, findings, metrics


def _note(reason: str) -> Optional[str]:
    return {
        "stage": "Не применимо на этом этапе опроцентовки.",
        "low-confidence": "Модель высказалась неуверенно — требует проверки преподавателем.",
        "no-evidence": "Подходящих фрагментов не найдено.",
        "structure": "Раздела, где ожидается свидетельство, нет, но подтверждение найдено в другом месте.",
    }.get(reason)


def _sym(value: bool) -> clips.Symbol:
    return clips.Symbol("TRUE" if value else "FALSE")
