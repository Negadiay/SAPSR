"""Чисто нейросетевой вариант для сравнения (план, З10): замечания пишет сама модель.

Цитаты не фильтруются — их проверка нужна только для метрики «доля выдуманных цитат»;
у каждого доказательства это видно по полю метрик и по отсутствию block_id у непроверенных.
Замечания такого режима никогда не блокируют сдачу и всегда требуют проверки преподавателем.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

from sapsr.checks.engine import make_finding
from sapsr.checks.registry import Hit
from sapsr.content.fragments import Fragment, LooseBlockId
from sapsr.content.verify import locate_quote
from sapsr.deadline import Deadline
from sapsr.document.model import Document
from sapsr.kb.loader import ActiveRule, Prompt, ResolvedProfile
from sapsr.llm.client import LlmClient, LlmError
from sapsr.report.model import Evidence, Finding, SkippedCheck


class NeuralFindingOut(BaseModel):
    model_config = ConfigDict(extra="ignore")
    message: str = Field(min_length=1, max_length=500)
    found: str = Field("", max_length=500)
    quote: str = Field("", max_length=600)
    block_id: LooseBlockId = ""
    quote_2: str = Field("", max_length=600)
    block_id_2: LooseBlockId = ""


class NeuralOut(BaseModel):
    model_config = ConfigDict(extra="ignore")
    findings: list[NeuralFindingOut] = Field(default_factory=list, max_length=30)


def windows(fragments: list[Fragment], max_chars: int) -> list[list[Fragment]]:
    """Несколько фрагментов в одном запросе — чтобы модель видела оба места противоречия."""
    result: list[list[Fragment]] = []
    current: list[Fragment] = []
    size = 0
    for fragment in fragments:
        if current and size + fragment.chars > max_chars:
            result.append(current)
            current, size = [], 0
        current.append(fragment)
        size += fragment.chars
    if current:
        result.append(current)
    return result


def neural_findings(doc: Document, fragments: list[Fragment], llm: LlmClient, prompt: Prompt,
                    profile: ResolvedProfile, active: ActiveRule, deadline: Deadline,
                    skipped: list[SkippedCheck]) -> tuple[list[Finding], dict]:
    settings = profile.content
    findings: list[Finding] = []
    quotes = unverified = 0
    for n, group in enumerate(windows(fragments, settings.fragment_max_chars * 2), start=1):
        if deadline.expired:
            skipped.append(SkippedCheck(id=f"window-{n}", stage="claims", reason="TIMEOUT",
                                        message="Часть текста не проверена: истекло время."))
            continue
        blocks = [b for f in group for b in f.blocks]
        data = "\n".join(f.render() for f in group)
        system, user = prompt.render(data=data)
        try:
            result = llm.complete_json(prompt.ref, system, user, NeuralOut, timeout_s=deadline.remaining())
        except LlmError as exc:
            skipped.append(SkippedCheck(id=f"window-{n}", stage="claims", reason="LLM_UNAVAILABLE",
                                        message=f"Часть текста не проверена: {exc}"))
            continue
        for out in result.data.findings:
            evidence = []
            for quote, block_id in ((out.quote, out.block_id), (out.quote_2, out.block_id_2)):
                if not quote:
                    continue
                quotes += 1
                block, _ = locate_quote(quote, doc.block(block_id), blocks,
                                        settings.quote_min_score, settings.quote_min_chars)
                if block is None:
                    unverified += 1
                    evidence.append(Evidence(quote=quote))
                else:
                    evidence.append(Evidence(page=block.page, quote=quote, block_id=block.id,
                                             section=doc.section_label(block.section_id)))
            hit = Hit(values={"model_message": out.message, "model_found": out.found or out.message},
                      evidence=evidence, pages=[e.page for e in evidence if e.page],
                      context=evidence[0].quote if evidence else "")
            findings.append(make_finding(active, hit, origin="neural", review=True))
    return findings, {"neural_findings": len(findings), "neural_quotes": quotes,
                      "neural_quotes_unverified": unverified}
