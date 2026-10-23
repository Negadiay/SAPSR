"""Шаг 1 содержательной цепочки (нейросетевой): извлечение утверждений (план, З4).

Для каждого фрагмента значимых разделов языковая модель возвращает утверждения строго
по JSON-схеме; список допустимых атрибутов берётся из словаря понятий, модель не
придумывает свои. Дальше каждое утверждение проходит символьные шаги:
  · цитата есть в тексте блока (verify.py) — иначе отбрасывается как выдуманная;
  · значения сводятся к словарю (normalize.py) и должны следовать из самой цитаты.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, create_model

from sapsr.content.fragments import BlockId, Fragment
from sapsr.content.normalize import Normalizer
from sapsr.content.verify import locate_quote
from sapsr.deadline import Deadline
from sapsr.document.model import Document, readable_title
from sapsr.kb.loader import Prompt
from sapsr.kb.schema import Concepts, ContentSettings
from sapsr.llm.client import LlmClient, LlmError, LlmSchemaError, LlmTimeout
from sapsr.report.model import SkippedCheck

log = logging.getLogger("sapsr.claims")


@dataclass
class Claim:
    id: str
    attribute: str
    values: tuple[str, ...]
    exhaustive: bool
    quote: str
    block_id: str
    page: int
    section_id: str
    section_label: str
    kind: str
    confidence: float
    from_dictionary: bool
    raw_values: list[str] = field(default_factory=list)


@dataclass
class ClaimStats:
    fragments: int = 0
    fragments_failed: int = 0
    extracted: int = 0
    accepted: int = 0
    rejected_quote: int = 0
    rejected_value: int = 0
    duplicates: int = 0

    def as_metrics(self) -> dict:
        return {
            "claims_fragments": self.fragments,
            "claims_fragments_failed": self.fragments_failed,
            "claims_extracted": self.extracted,
            "claims_accepted": self.accepted,
            "claims_rejected_quote": self.rejected_quote,
            "claims_rejected_value": self.rejected_value,
            "claims_hallucinated_quote_share": round(self.rejected_quote / self.extracted, 3)
            if self.extracted else 0.0,
        }


def claims_schema(concepts: Concepts, max_claims: int) -> type[BaseModel]:
    """Схема ответа: attribute — только из словаря понятий."""
    attr_ids = tuple(a.id for a in concepts.attributes)
    ClaimOut = create_model(
        "ClaimOut",
        __config__=ConfigDict(extra="ignore"),
        attribute=(Literal[attr_ids], ...),  # type: ignore[valid-type]
        value=(list[str], Field(min_length=1, max_length=12)),
        exhaustive=(bool, False),
        quote=(str, Field(min_length=1, max_length=600)),
        block_id=(BlockId, ...),
    )
    return create_model("ClaimsOut", __config__=ConfigDict(extra="ignore"),
                        claims=(list[ClaimOut], Field(default_factory=list, max_length=max_claims)))


def describe_attributes(concepts: Concepts) -> str:
    lines = []
    for attr in concepts.attributes:
        line = f"- {attr.id} — {attr.description}"
        if attr.values:
            line += ". Значения: " + ", ".join(v.id for v in attr.values)
        if attr.cardinality == "single":
            line += ". Одно значение."
        elif attr.cardinality == "text":
            line += ". Свободный текст — короткая формулировка из записки."
        lines.append(line)
    return "\n".join(lines)


def extract_claims(doc: Document, fragments: list[Fragment], llm: LlmClient, prompt: Prompt,
                   concepts: Concepts, settings: ContentSettings, deadline: Deadline,
                   skipped: list[SkippedCheck]) -> tuple[list[Claim], ClaimStats]:
    schema = claims_schema(concepts, settings.max_claims_per_fragment)
    normalizer = Normalizer(concepts)
    attributes = describe_attributes(concepts)
    stats = ClaimStats()
    claims: list[Claim] = []
    seen: set[tuple] = set()

    for fragment in fragments:
        if deadline.expired:
            skipped.append(SkippedCheck(id=fragment.id, stage="claims", reason="TIMEOUT",
                                        message=f"Раздел «{fragment.section_label}» не проверен: истекло время."))
            continue
        stats.fragments += 1
        system, user = prompt.render(attributes=attributes, section=fragment.section_title,
                                     data=fragment.render())
        try:
            result = llm.complete_json(prompt.ref, system, user, schema, timeout_s=deadline.remaining())
        except LlmSchemaError:
            stats.fragments_failed += 1
            skipped.append(SkippedCheck(id=fragment.id, stage="claims", reason="LLM_SCHEMA",
                                        message=f"Раздел «{fragment.section_label}»: ответ модели дважды "
                                                "не прошёл JSON-схему, раздел пропущен."))
            continue
        except LlmTimeout:
            stats.fragments_failed += 1
            skipped.append(SkippedCheck(id=fragment.id, stage="claims", reason="TIMEOUT",
                                        message=f"Раздел «{fragment.section_label}»: модель не ответила вовремя."))
            continue
        except LlmError as exc:
            stats.fragments_failed += 1
            skipped.append(SkippedCheck(id=fragment.id, stage="claims", reason="LLM_UNAVAILABLE",
                                        message=f"Раздел «{fragment.section_label}»: {exc}"))
            continue

        for out in result.data.claims:
            stats.extracted += 1
            claim = _accept(out, fragment, doc, normalizer, settings, stats)
            if claim is None:
                continue
            key = (claim.attribute, claim.values, claim.block_id)
            if key in seen:
                stats.duplicates += 1
                continue
            seen.add(key)
            claim.id = f"c-{len(claims) + 1:03d}"
            claims.append(claim)
            stats.accepted += 1
    return claims, stats


def _accept(out, fragment: Fragment, doc: Document, normalizer: Normalizer,
            settings: ContentSettings, stats: ClaimStats) -> Optional[Claim]:
    preferred = doc.block(out.block_id)
    if preferred is not None and preferred.id not in {b.id for b in fragment.blocks}:
        preferred = None
    block, _ = locate_quote(out.quote, preferred, fragment.blocks,
                            settings.quote_min_score, settings.quote_min_chars)
    if block is None:
        stats.rejected_quote += 1
        return None

    attr = out.attribute
    raw_values = [v for v in out.value if v and v.strip()]
    if normalizer.has_dictionary(attr):
        in_quote = normalizer.values_in_text(attr, out.quote)
        values: set[str] = set()
        from_dict = True
        for raw in raw_values:
            canonical = normalizer.canonical(attr, raw)
            if canonical is None:
                from_dict = False
                values.add(normalizer.raw_value(raw))
            elif canonical in in_quote:
                values.add(canonical)
            # Значение из словаря, которого нет в цитате, не следует из текста — отбрасывается.
        if not values:
            stats.rejected_value += 1
            return None
        confidence = 1.0 if from_dict else settings.raw_value_confidence
    else:
        values = {normalizer.raw_value(v) for v in raw_values}
        from_dict = False
        confidence = settings.raw_value_confidence
        if not values:
            stats.rejected_value += 1
            return None

    section = doc.section(block.section_id)
    return Claim(
        id="",
        attribute=attr,
        values=tuple(sorted(values)),
        exhaustive=bool(out.exhaustive),
        quote=out.quote.strip(),
        block_id=block.id,
        page=block.page,
        section_id=block.section_id or fragment.section_id,
        section_label=(section.number or readable_title(section.title)) if section else fragment.section_label,
        kind=(section.effective_kind if section else fragment.kind) or "",
        confidence=confidence,
        from_dictionary=from_dict,
        raw_values=raw_values,
    )
