"""Знаки препинания в перечислениях.

Элемент перечисления — блок list_item целиком, а не строка: перенос внутри элемента
больше не считается нарушением (прежняя проверка смотрела конец каждой строки).
"""

from __future__ import annotations

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, Params, check
from sapsr.document.model import Block
from sapsr.report.model import Evidence


def find_lists(ctx: CheckContext, marker: str) -> list[list[Block]]:
    """Подряд идущие элементы с одним видом маркера; колонтитулы между ними не разрывают список.

    Нумерованный список не разрывается вложенными перечислениями с тире.
    """
    lists: list[list[Block]] = []
    current: list[Block] = []
    for block in ctx.doc.blocks:
        if block.type in ("page_number", "header_footer"):
            continue
        if block.type == "list_item" and block.attrs.get("marker") == marker:
            current.append(block)
            continue
        if current and marker == "number" and block.type == "list_item":
            continue
        if current:
            lists.append(current)
            current = []
    if current:
        lists.append(current)
    return lists


def _next_content(ctx: CheckContext, block: Block):
    pos = ctx.doc.block_pos(block.id) + 1
    while pos < len(ctx.doc.blocks):
        nxt = ctx.doc.blocks[pos]
        if nxt.type not in ("page_number", "header_footer"):
            return nxt
        pos += 1
    return None


class SimpleListParams(Params):
    min_items: int
    item_end: str
    last_end: str
    # Элемент с прописной буквы — законченное предложение и заканчивается этим знаком.
    sentence_end: str
    # Элемент, за которым идёт вложенный список, может заканчиваться двоеточием.
    sublist_end: str
    max_examples: int
    quote_chars: int


_MARKER_CHARS = "–—-•·▪◦* 	"


def starts_capitalized(text: str) -> bool:
    """Элемент начинается с прописной буквы (а не с цифры, кода или строчной)."""
    body = text.lstrip(_MARKER_CHARS)
    return bool(body) and body[0].isalpha() and body[0].isupper()


def introduces_sublist(ctx: CheckContext, block: Block, sublist_end: str) -> bool:
    """«Задачи:» перед вложенным перечислением — двоеточие уместно."""
    nxt = _next_content(ctx, block)
    return block.text.rstrip().endswith(sublist_end) and nxt is not None and nxt.type == "list_item"


@check("lists.simple_punctuation", SimpleListParams, provides=("lists", "violations", "items"))
def simple_punctuation(ctx: CheckContext, params: SimpleListParams) -> list[Hit]:
    """Простой список: элементы со строчной буквы заканчиваются «;», последний — «.»;
    элементы-предложения с прописной буквы заканчиваются «.»."""
    bad_lists = []
    violations = items = 0
    for items_ in find_lists(ctx, "dash"):
        if len(items_) < params.min_items:
            continue
        bad = 0
        for i, block in enumerate(items_):
            text = block.text.rstrip()
            if starts_capitalized(text):
                expected = params.sentence_end
            else:
                expected = params.last_end if i == len(items_) - 1 else params.item_end
            ok = text.endswith(expected) or introduces_sublist(ctx, block, params.sublist_end)
            bad += 0 if ok else 1
        if bad:
            bad_lists.append(items_)
            violations += bad
            items += len(items_)
    if not bad_lists:
        return []
    return [Hit(
        values={"lists": len(bad_lists), "violations": violations, "items": items},
        evidence=[Evidence(page=lst[0].page, block_id=lst[0].id, block_end=lst[-1].id,
                           quote=lst[0].text[:params.quote_chars])
                  for lst in bad_lists],
        pages=sorted({lst[0].page for lst in bad_lists}),
        context=" / ".join(b.text[:params.quote_chars] for b in bad_lists[0][:params.max_examples]),
        location="Простой список",
    )]


class NumberedListParams(Params):
    min_items: int
    item_end: str
    # Элемент, за которым идёт вложенный список, может заканчиваться двоеточием.
    sublist_end: str
    max_examples: int
    quote_chars: int


@check("lists.numbered_punctuation", NumberedListParams, provides=("lists", "violations", "items"))
def numbered_punctuation(ctx: CheckContext, params: NumberedListParams) -> list[Hit]:
    """Нумерованный список: каждый элемент заканчивается точкой."""
    bad_lists = []
    violations = items = 0
    for items_ in find_lists(ctx, "number"):
        if len(items_) < params.min_items:
            continue
        bad = 0
        for block in items_:
            text = block.text.rstrip()
            if text.endswith(params.item_end):
                continue
            if introduces_sublist(ctx, block, params.sublist_end):
                continue
            bad += 1
        if bad:
            bad_lists.append(items_)
            violations += bad
            items += len(items_)
    if not bad_lists:
        return []
    return [Hit(
        values={"lists": len(bad_lists), "violations": violations, "items": items},
        evidence=[Evidence(page=lst[0].page, block_id=lst[0].id, block_end=lst[-1].id,
                           quote=lst[0].text[:params.quote_chars])
                  for lst in bad_lists],
        pages=sorted({lst[0].page for lst in bad_lists}),
        context=" / ".join(b.text[:params.quote_chars] for b in bad_lists[0][:params.max_examples]),
        location="Нумерованный список",
    )]
