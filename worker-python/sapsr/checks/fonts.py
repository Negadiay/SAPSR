"""Шрифт основного текста: размер, семейство, шрифт списка источников."""

from __future__ import annotations

from typing import Optional

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, NoParams, Params, check
from sapsr.document.text import (
    font_display_name,
    font_family_key,
    font_style_key,
    pages_to_range,
)
from sapsr.report.model import Evidence


class SizeParams(Params):
    min_pt: float
    max_pt: float
    # Правило срабатывает, если доля страниц с неверным размером в (share_over_pct; share_upto_pct].
    share_over_pct: float
    share_upto_pct: float


@check("fonts.main_size", SizeParams, provides=("dominant_pt", "affected", "pct"))
def main_size(ctx: CheckContext, params: SizeParams) -> list[Hit]:
    main = ctx.main_metrics()
    bad = [m.page for m in main if m.size and not (params.min_pt <= m.size <= params.max_pt)]
    if not bad:
        return []
    pct = len(bad) / max(1, len(main)) * 100
    if not (params.share_over_pct < pct <= params.share_upto_pct):
        return []
    return [Hit(
        values={"dominant_pt": ctx.dominant_main_size(), "affected": len(bad), "pct": pct},
        evidence=[Evidence(page=p) for p in bad],
        pages=bad,
        location=f"Страницы: {pages_to_range(bad)}",
    )]


class FamilyParams(Params):
    allowed: list[str]
    # Метрически совместимые замены, которые подставляет конвертер DOCX → PDF (решение 3, задача 3.2).
    docx_substitutes: list[str]
    # Срабатывает, если доля страниц с недопустимым шрифтом больше (0 — любая страница).
    min_share_pct: float


def _allowed(font: str, families: list[str]) -> bool:
    if not font:
        return True
    key = font_family_key(font)
    return any(family in key for family in families)


@check("fonts.family", FamilyParams, provides=("font", "affected", "pct"))
def family(ctx: CheckContext, params: FamilyParams) -> list[Hit]:
    families = list(params.allowed)
    if ctx.task.file.source_format == "docx":
        families += params.docx_substitutes
    families = [font_family_key(f) for f in families]
    main = ctx.main_metrics()
    bad = [m.page for m in main if m.font and not _allowed(m.font, families)]
    if not bad:
        return []
    pct = len(bad) / max(1, len(main)) * 100
    if pct <= params.min_share_pct:
        return []
    return [Hit(
        values={"font": font_display_name(ctx.dominant_main_font()), "affected": len(bad), "pct": pct},
        evidence=[Evidence(page=p) for p in bad],
        pages=bad,
        location=f"Страницы: {pages_to_range(bad)}",
    )]


def _dominant(chars) -> Optional[str]:
    from collections import Counter
    counts = Counter(c.font for c in chars if c.font)
    return counts.most_common(1)[0][0] if counts else None


@check("fonts.reference_font", NoParams, provides=("main_font", "ref_font"))
def reference_font(ctx: CheckContext, params: NoParams) -> list[Hit]:
    metrics = ctx.reference_metrics()
    main_font = ctx.dominant_main_font()
    chars = ctx.reference_body_chars()
    if not metrics or not chars or not main_font:
        return []
    ref_font = _dominant(chars)
    if not ref_font or font_style_key(ref_font) == font_style_key(main_font):
        return []
    pages = [m.page for m in metrics]
    return [Hit(values={"main_font": font_display_name(main_font), "ref_font": font_display_name(ref_font)},
                evidence=[Evidence(page=pages[0])], pages=pages,
                location=f"Страницы: {pages_to_range(pages)}")]
