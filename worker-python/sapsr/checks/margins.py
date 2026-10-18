"""Поля страницы — по символам основного текста, без колонтитулов.

Край текста берётся по процентилям (не по крайним символам), чтобы единичный
выступающий элемент не давал замечания. Нормы и допуск — в миллиметрах из базы знаний.
"""

from __future__ import annotations

from typing import Literal

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, Params, check
from sapsr.document.text import pages_to_range, percentile
from sapsr.report.model import Evidence

PT_PER_MM = 72 / 25.4


class MarginParams(Params):
    side: Literal["left", "right", "top", "bottom"]
    norm_mm: float
    tolerance_mm: float
    # Процентили положения символов, задающие край текста.
    low_percentile: float
    high_percentile: float
    # Меньше символов основного текста на странице — страница не оценивается.
    min_chars: int


def measure(ctx: CheckContext, page_no: int, params: MarginParams):
    """Поле страницы в миллиметрах или None, если на странице мало текста."""
    chars = ctx.body_chars(page_no)
    if len(chars) < params.min_chars:
        return None
    page = ctx.raw.pages[page_no - 1]
    if params.side == "left":
        edge_pt = percentile([c.x0 for c in chars], params.low_percentile)
    elif params.side == "right":
        edge_pt = page.width - percentile([c.x1 for c in chars], params.high_percentile)
    elif params.side == "top":
        edge_pt = percentile([c.top for c in chars], params.low_percentile)
    else:
        edge_pt = page.height - percentile([c.bottom for c in chars], params.high_percentile)
    return edge_pt / PT_PER_MM


@check("margins.side", MarginParams, provides=("actual_mm", "affected"))
def margin_side(ctx: CheckContext, params: MarginParams) -> list[Hit]:
    limit = params.norm_mm - params.tolerance_mm
    bad: list[tuple[int, float]] = []
    for page in ctx.raw.pages:
        value = measure(ctx, page.number, params)
        if value is not None and value < limit:
            bad.append((page.number, value))
    if not bad:
        return []
    worst = min(value for _, value in bad)
    pages = [p for p, _ in bad]
    return [Hit(
        values={"actual_mm": worst, "affected": len(pages)},
        evidence=[Evidence(page=p) for p in pages],
        pages=pages,
        location=f"Страницы: {pages_to_range(pages)}",
    )]
