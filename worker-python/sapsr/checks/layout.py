"""Номера страниц, межстрочный интервал, абзацный отступ."""

from __future__ import annotations

from collections import defaultdict

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, Params, check
from sapsr.document.extract import Char


class PageNumberParams(Params):
    start_page: int
    min_coverage: float
    min_checkable_pages: int


def has_page_number(ctx: CheckContext, chars: list[Char]) -> bool:
    """Цифры внизу страницы, сгруппированные по соседству (работает и для номеров 10+)."""
    zones = ctx.layout.zones
    bottom = sorted((c for c in chars if c.top >= zones.page_number_top_pt), key=lambda c: c.x0)
    if not bottom:
        return False
    groups: list[list[Char]] = [[bottom[0]]]
    for c in bottom[1:]:
        if c.x0 - groups[-1][-1].x1 < zones.page_number_char_gap_pt:
            groups[-1].append(c)
        else:
            groups.append([c])
    for group in groups:
        text = "".join(c.text for c in group).strip()
        if text.isdigit() and 1 <= int(text) <= zones.page_number_max:
            if min(c.x0 for c in group) >= zones.page_number_x_min_pt:
                return True
    return False


@check("layout.page_numbers", PageNumberParams, provides=("found", "checkable", "start_page"))
def page_numbers(ctx: CheckContext, params: PageNumberParams) -> list[Hit]:
    checkable = max(0, ctx.raw.page_count - (params.start_page - 1))
    if checkable <= params.min_checkable_pages:
        return []
    found = sum(1 for p in ctx.raw.pages
                if p.number >= params.start_page and p.chars and has_page_number(ctx, p.chars))
    if found >= checkable * params.min_coverage:
        return []
    return [Hit(values={"found": found, "checkable": checkable, "start_page": params.start_page},
                location=f"Нижний колонтитул страниц {params.start_page}+")]


class SpacingParams(Params):
    min_font_pt: float
    size_tolerance_pt: float
    min_lines: int
    # Промежутки между строками вне [size×gap_min_ratio; size×gap_max_ratio] не учитываются.
    gap_min_ratio: float
    gap_max_ratio: float
    min_gaps: int
    allowed_min_ratio: float
    allowed_max_ratio: float


@check("layout.line_spacing", SpacingParams, provides=("ratio", "gap_pt", "size_pt"))
def line_spacing(ctx: CheckContext, params: SpacingParams) -> list[Hit]:
    """Межстрочный интервал по медиане расстояний между строками основного текста.

    Для LaTeX с \\linespread{1.5} и 14 пт расстояние ≈ 25 пт — отношение ≈ 1,82.
    """
    size = ctx.dominant_main_size()
    chars = ctx.main_body_chars()
    if not chars or size < params.min_font_pt:
        return []
    lines: dict[int, int] = defaultdict(int)
    for c in chars:
        if abs(c.size - size) <= params.size_tolerance_pt:
            lines[round(c.top)] += 1
    baselines = sorted(lines)
    if len(baselines) < params.min_lines:
        return []
    gaps = [baselines[i + 1] - baselines[i] for i in range(len(baselines) - 1)]
    lo, hi = size * params.gap_min_ratio, size * params.gap_max_ratio
    plausible = sorted(g for g in gaps if lo <= g <= hi)
    if len(plausible) < params.min_gaps:
        return []
    median_gap = plausible[len(plausible) // 2]
    ratio = median_gap / size
    if params.allowed_min_ratio <= ratio <= params.allowed_max_ratio:
        return []
    return [Hit(values={"ratio": ratio, "gap_pt": median_gap, "size_pt": size},
                location="Межстрочный интервал")]


class IndentParams(Params):
    min_font_pt: float
    size_tolerance_pt: float
    min_lines: int
    # Красная строка — начало строки правее левого края текста на [indent_min_pt; indent_max_pt].
    indent_min_pt: float
    indent_max_pt: float
    flush_tolerance_pt: float
    # Замечание, только если строк с отступом меньше этой доли, а строк без отступа — больше той.
    indented_share_max: float
    flush_share_min: float
    # Левый край текста — норма левого поля 30 мм, как в прежнем анализаторе.
    left_edge_pt: float


@check("layout.paragraph_indent", IndentParams, provides=("indented", "total"))
def paragraph_indent(ctx: CheckContext, params: IndentParams) -> list[Hit]:
    """Нет ни одной красной строки — абзацы набраны без отступа."""
    size = ctx.dominant_main_size()
    chars = ctx.main_body_chars()
    if not chars or size < params.min_font_pt:
        return []
    by_line: dict[int, list[float]] = defaultdict(list)
    for c in chars:
        if abs(c.size - size) <= params.size_tolerance_pt:
            by_line[round(c.top)].append(c.x0)
    if len(by_line) < params.min_lines:
        return []
    left = params.left_edge_pt
    starts = [min(xs) for xs in by_line.values()]
    total = len(starts)
    indented = sum(1 for x in starts if left + params.indent_min_pt <= x <= left + params.indent_max_pt)
    flush = sum(1 for x in starts if abs(x - left) < params.flush_tolerance_pt)
    if indented < total * params.indented_share_max and flush > total * params.flush_share_min:
        return [Hit(values={"indented": indented, "total": total}, location="Абзацный отступ")]
    return []
