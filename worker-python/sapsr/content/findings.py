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
# Цитат у противоречия: по одной на каждый вариант, остальные — пока есть место.
MAX_CONFLICT_EVIDENCE = 6


def _evidence(c: Claim) -> Evidence:
    return Evidence(page=c.page, quote=c.quote, block_id=c.block_id, section=c.section_label)


def _values(c: Claim, concepts: Concepts) -> str:
    return ", ".join(concepts.value_title(c.attribute, v) for v in c.values)


def _variants(claims: list[Claim]) -> list[list[Claim]]:
    """Утверждения с одинаковыми значениями — один вариант; варианты по первой странице."""
    by_values: dict[tuple[str, ...], list[Claim]] = {}
    for c in claims:
        by_values.setdefault(c.values, []).append(c)
    return list(by_values.values())


def _places(claims: list[Claim]) -> str:
    """«введение», «введение и подраздел 2.1», «введение, раздел 2 и подраздел 3.1»."""
    places = list(dict.fromkeys(section_phrase(c.section_label) for c in claims))
    return places[0] if len(places) == 1 else ", ".join(places[:-1]) + " и " + places[-1]


def _variants_text(variants: list[list[Claim]], concepts: Concepts) -> str:
    """«Введение: DOCX, PDF; подраздел 1.2: PDF» — где какой вариант назван."""
    text = "; ".join(f"{_places(group)}: {_values(group[0], concepts)}" for group in variants)
    return text[:1].upper() + text[1:]


def _conflict_evidence(variants: list[list[Claim]]) -> list[Claim]:
    chosen = [group[0] for group in variants]
    rest = sorted((c for group in variants for c in group[1:]), key=lambda c: (c.page, c.id))
    chosen += rest[:max(0, MAX_CONFLICT_EVIDENCE - len(chosen))]
    return sorted(chosen, key=lambda c: (c.page, c.id))


def claim_findings(firings: list[Firing], claims: list[Claim], profile: ResolvedProfile,
                   concepts: Concepts) -> list[Finding]:
    by_id = {c.id: c for c in claims}
    threshold = profile.content.review_threshold
    findings: list[Finding] = []

    conflicts: dict[tuple[str, str, bool], dict[str, Claim]] = defaultdict(dict)
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
        # Одно замечание на атрибут: CLIPS находит расхождения попарно, а преподавателю нужен
        # один пункт со всеми вариантами, а не по замечанию на каждую пару цитат. Пары, где значение
        # не нашлось в словаре, — отдельным замечанием: их уверенность ниже, и они не должны утянуть
        # на проверку преподавателю противоречие, установленное по словарю.
        weak = not all(c.from_dictionary for c in involved)
        for c in involved:
            conflicts[(rule_id, firing.attribute, weak)][c.id] = c

    for (rule_id, attr_id, _), group in sorted(conflicts.items(), key=lambda kv: (
            kv[0][0], kv[0][1], kv[0][2], min(c.page for c in kv[1].values()))):
        variants = _variants(sorted(group.values(), key=lambda c: (c.page, c.id)))
        if len(variants) < 2:
            continue
        a, b = variants[0][0], variants[1][0]
        chosen = _conflict_evidence(variants)
        attr = concepts.attribute(attr_id)
        confidence = round(min(c.confidence for c in group.values()), 2)
        hit = Hit(
            values={"attribute_title": attr.title if attr else attr_id,
                    "section_a": section_phrase(a.section_label), "section_b": section_phrase(b.section_label),
                    "values_a": _values(a, concepts), "values_b": _values(b, concepts),
                    "variants": _variants_text(variants, concepts)},
            evidence=[_evidence(c) for c in chosen],
            pages=[c.page for c in chosen],
            context=" | ".join(c.quote for c in chosen),
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
