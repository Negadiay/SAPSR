"""Замечания по срабатываниям содержательных правил.

Происхождение — hybrid: факты дала модель, решение вынесло правило. Такие замечания
не блокируют сдачу (решение 5). Уверенность — наименьшая из уверенностей утверждений;
ниже порога профиля — «требует проверки преподавателем».
"""

from __future__ import annotations

from collections import defaultdict
from typing import Optional

from sapsr.checks.engine import make_finding
from sapsr.checks.registry import Hit
from sapsr.content.claims import Claim
from sapsr.content.reasoning import Firing
from sapsr.kb.loader import ResolvedProfile
from sapsr.kb.schema import Concepts
from sapsr.document.model import section_phrase
from sapsr.report.model import Evidence, Finding

# Срабатывание CLIPS → правило каталога.
FIRING_TO_RULE = {
    "conflict_single": "CNT-CONSIST-001",
    "conflict_multi": "CNT-CONSIST-002",
    "claim_without_support": "CNT-SUPPORT-001",
}
MAX_SUPPORT_EVIDENCE = 3


def _evidence(c: Claim) -> Evidence:
    return Evidence(page=c.page, quote=c.quote, block_id=c.block_id, section=c.section_label)


def _values(c: Claim, concepts: Concepts) -> str:
    return ", ".join(concepts.value_title(c.attribute, v) for v in c.values)


def claim_findings(firings: list[Firing], claims: list[Claim], profile: ResolvedProfile,
                   concepts: Concepts) -> list[Finding]:
    by_id = {c.id: c for c in claims}
    threshold = profile.content.review_threshold
    findings: list[Finding] = []

    pairs: dict[tuple, list[tuple[Claim, Claim]]] = defaultdict(list)
    support: dict[str, list[Claim]] = defaultdict(list)
    for firing in firings:
        rule_id = FIRING_TO_RULE.get(firing.rule)
        if rule_id is None or profile.rule(rule_id) is None:
            continue
        involved = [by_id[c] for c in firing.claims if c in by_id]
        if firing.rule == "claim_without_support":
            support[firing.attribute].extend(involved)
            continue
        if len(involved) != 2:
            continue
        a, b = sorted(involved, key=lambda c: (c.page, c.id))
        # Одно замечание на пару расходящихся вариантов, а не на каждую пару цитат.
        key = (rule_id, firing.attribute, frozenset({a.values, b.values}))
        pairs[key].append((a, b))

    for (rule_id, attr_id, _), variants in sorted(pairs.items(), key=lambda kv: (kv[0][0], kv[0][1],
                                                                                  min(a.page for a, _ in kv[1]))):
        a, b = min(variants, key=lambda ab: (ab[0].page, ab[1].page, ab[0].id))
        attr = concepts.attribute(attr_id)
        confidence = round(min(a.confidence, b.confidence), 2)
        hit = Hit(
            values={"attribute_title": attr.title if attr else attr_id,
                    "section_a": section_phrase(a.section_label), "section_b": section_phrase(b.section_label),
                    "values_a": _values(a, concepts), "values_b": _values(b, concepts)},
            evidence=[_evidence(a), _evidence(b)],
            pages=[a.page, b.page],
            context=f"{a.quote} | {b.quote}",
        )
        findings.append(_finding(profile, rule_id, hit, confidence, threshold))

    for attr_id, involved in sorted(support.items()):
        unique = sorted({c.id: c for c in involved}.values(), key=lambda c: (c.page, c.id))
        attr = concepts.attribute(attr_id)
        parts = [concepts.part(p).title if concepts.part(p) else p for p in (attr.must_be_described_in if attr else [])]
        first = unique[0]
        values = sorted({concepts.value_title(attr_id, v) for c in unique for v in c.values})
        hit = Hit(
            values={"attribute_title": attr.title if attr else attr_id,
                    "section_a": section_phrase(first.section_label),
                    "values_a": ", ".join(values), "required_parts": ", ".join(f"«{p}»" for p in parts)},
            evidence=[_evidence(c) for c in unique[:MAX_SUPPORT_EVIDENCE]],
            pages=[c.page for c in unique[:MAX_SUPPORT_EVIDENCE]],
            context=first.quote,
        )
        confidence = round(min(c.confidence for c in unique), 2)
        findings.append(_finding(profile, "CNT-SUPPORT-001", hit, confidence, threshold))
    return findings


def _finding(profile: ResolvedProfile, rule_id: str, hit: Hit, confidence: float,
             threshold: Optional[float]) -> Finding:
    active = profile.rule(rule_id)
    review = active.rule.review or (threshold is not None and confidence < threshold)
    return make_finding(active, hit, origin="hybrid", confidence=confidence, review=review)
