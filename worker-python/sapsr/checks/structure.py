"""Структура документа: текстовый слой, обязательные части, нумерация разделов.

Разделы ищутся по блокам-заголовкам модели документа, а не по слову где угодно в тексте:
строка оглавления «Введение ..... 3» больше не засчитывается как раздел «Введение»
(дефект прежнего _check_structure, описанный в записке).
"""

from __future__ import annotations

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, NoParams, Params, check
from sapsr.document.labeling import StructuralMatcher
from sapsr.report.model import Evidence


@check("document.pages")
def no_pages(ctx: CheckContext, params: NoParams) -> list[Hit]:
    return [] if ctx.raw.page_count else [Hit()]


@check("document.text_layer")
def no_text(ctx: CheckContext, params: NoParams) -> list[Hit]:
    return [] if ctx.raw.has_text else [Hit()]


class RequiredPartParams(Params):
    part: str


@check("structure.required_part", RequiredPartParams, provides=("part_title",))
def required_part(ctx: CheckContext, params: RequiredPartParams) -> list[Hit]:
    part = ctx.concepts.part(params.part)
    title = part.title if part else params.part
    if params.part == "part.title_page":
        matcher = StructuralMatcher(ctx.concepts)
        present = bool(ctx.raw.pages) and matcher.is_title_page(ctx.raw.pages[0].text)
    else:
        present = bool(ctx.doc.sections_of_kind(params.part))
    return [] if present else [Hit(values={"part_title": title}, location="Структура документа")]


class NumberingParams(Params):
    # Проверяемая глубина: 1 — только разделы, 2 — и подразделы.
    max_level: int


@check("structure.section_numbering", NumberingParams, provides=("gaps",))
def section_numbering(ctx: CheckContext, params: NumberingParams) -> list[Hit]:
    """Разделы нумеруются подряд: 1, 2, 3; подразделы внутри раздела — 1.1, 1.2…"""
    numbered = [s for s in ctx.doc.sections if s.number and s.level <= params.max_level]
    gaps: list[str] = []
    evidence: list[Evidence] = []
    last_by_parent: dict[str, int] = {}
    for section in numbered:
        parts = section.number.split(".")
        parent_key = ".".join(parts[:-1])
        value = int(parts[-1])
        prev = last_by_parent.get(parent_key)
        if prev is not None and value != prev + 1 and value > prev:
            gaps.append(section.number)
            block = ctx.doc.block(section.heading_block)
            evidence.append(Evidence(page=section.page_start, block_id=section.heading_block,
                                     quote=block.text if block else section.title))
        last_by_parent[parent_key] = max(value, prev or 0)
    if not gaps:
        return []
    return [Hit(values={"gaps": ", ".join(gaps)}, evidence=evidence,
                pages=[e.page for e in evidence if e.page], location="Нумерация разделов")]
