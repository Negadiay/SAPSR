"""Ссылки в тексте на рисунки и таблицы.

Подпись («Рисунок 2.1 – …») и упоминание («(рисунок 2.1)») — разные типы блоков модели
документа, поэтому упоминание больше не путается с подписью (дефект прежнего _RE_FIG_CAPTION).
"""

from __future__ import annotations

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, Params, check
from sapsr.document.model import Captioned
from sapsr.report.model import Evidence


class MentionParams(Params):
    quote_chars: int


def _sort_key(number: str):
    return [int(p) for p in number.split(".") if p.isdigit()]


def _missing(ctx: CheckContext, items: list[Captioned], location: str, quote_chars: int) -> list[Hit]:
    missing = sorted((item for item in items if not item.mentions), key=lambda i: _sort_key(i.number))
    if not missing:
        return []
    evidence = []
    for item in missing:
        block = ctx.doc.block(item.caption_block)
        evidence.append(Evidence(page=item.page, block_id=item.caption_block,
                                 quote=block.text[:quote_chars] if block else None))
    first = ctx.doc.block(missing[0].caption_block)
    return [Hit(
        values={"numbers": ", ".join(i.number for i in missing)},
        evidence=evidence,
        pages=sorted({i.page for i in missing}),
        context=first.text[:quote_chars] if first else "",
        location=location,
    )]


@check("captions.figure_mentions", MentionParams, provides=("numbers",))
def figure_mentions(ctx: CheckContext, params: MentionParams) -> list[Hit]:
    return _missing(ctx, ctx.doc.figures, "Ссылки на рисунки", params.quote_chars)


@check("captions.table_mentions", MentionParams, provides=("numbers",))
def table_mentions(ctx: CheckContext, params: MentionParams) -> list[Hit]:
    return _missing(ctx, ctx.doc.tables, "Ссылки на таблицы", params.quote_chars)
