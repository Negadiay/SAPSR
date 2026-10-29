"""Где в файле работы выделить место замечания — для документа с комментариями (api-v1.md, раздел 17).

Правила и модель указывают место блоком модели документа (block_id, у списка — ещё block_end). Здесь блоки
превращаются в прямоугольники на страницах: по одному на блок и страницу, — их бэкенд и выделяет в PDF.
Абзац, перешедший на следующую страницу, даёт два прямоугольника. Место без блока (страница целиком,
весь документ) прямоугольников не получает — комментарий ставится заметкой на полях страницы.
"""

from __future__ import annotations

from sapsr.document.model import Block, Document
from sapsr.report.model import Area, Finding


def _round(bbox) -> tuple[float, float, float, float]:
    return tuple(round(v, 1) for v in bbox)  # type: ignore[return-value]


def block_areas(block: Block) -> list[Area]:
    """Прямоугольник блока на каждой его странице — объединение его строк."""
    by_page: dict[int, list[float]] = {}
    for line in block.lines:
        x0, top, x1, bottom = line.bbox
        box = by_page.get(line.page)
        by_page[line.page] = ([x0, top, x1, bottom] if box is None
                              else [min(box[0], x0), min(box[1], top), max(box[2], x1), max(box[3], bottom)])
    if not by_page:
        return [Area(page=block.page, bbox=_round(block.bbox))]
    return [Area(page=page, bbox=_round(box)) for page, box in sorted(by_page.items())]


# Колонтитулы и номер страницы, попавшие внутрь диапазона (список перешёл на следующую страницу), не выделяются.
_PAGE_FURNITURE = ("page_number", "header_footer")


def locate(findings: list[Finding], doc: Document) -> None:
    """Дописывает areas в места замечаний. Уже найденные не трогает: проход повторяется перед каждой фазой."""
    for finding in findings:
        for ev in finding.evidence:
            if ev.areas:
                continue
            if ev.bbox is not None and ev.page:
                ev.areas = [Area(page=ev.page, bbox=_round(ev.bbox))]
                continue
            if not ev.block_id or doc.block(ev.block_id) is None:
                continue
            start = doc.block_pos(ev.block_id)
            end = doc.block_pos(ev.block_end) if ev.block_end and doc.block(ev.block_end) else start
            blocks = [b for b in doc.blocks[start:max(start, end) + 1]
                      if b.id == ev.block_id or b.type not in _PAGE_FURNITURE]
            areas = [a for block in blocks for a in block_areas(block)]
            ev.areas = areas or None
