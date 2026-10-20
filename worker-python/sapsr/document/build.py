"""Сборка модели документа из размеченных строк: блоки, дерево разделов,
подписи рисунков и таблиц с их упоминаниями (план, З2, шаги 2–5)."""

from __future__ import annotations

import re
from collections import Counter
from typing import Optional

from sapsr.document.extract import RawDocument
from sapsr.document.labeling import DocStats, Labeler, LineLabel
from sapsr.document.model import (
    Block, Captioned, DocMeta, Document, LabelerInfo, LineGeom, Mention, Section, Style,
)
from sapsr.document.text import fold
from sapsr.kb.schema import Concepts

_RE_NUMBERED = re.compile(r"^(\d{1,2}(?:\.\d{1,2}){0,3})\s+(.+)$")
_RE_HYPHEN_END = re.compile(r"[A-Za-zА-Яа-яЁё]-$")
_NUM = r"(\d+(?:\.\d+)*)"
_SEP = r"\s*(?:,|и|[–—-])\s*"
_RE_FIG_MENTION = re.compile(
    r"(?<![а-яё])рис(?:ун(?:ок|ка|ку|ке|ком|ки|ков|кам|ками|ках))?\.?\s*" + _NUM + r"((?:" + _SEP + _NUM + r")*)",
    re.IGNORECASE,
)
_RE_TBL_MENTION = re.compile(
    r"(?<![а-яё])табл(?:иц(?:а|ы|е|у|ей|ой|ам|ами|ах))?\.?\s*" + _NUM + r"((?:" + _SEP + _NUM + r")*)",
    re.IGNORECASE,
)
_RE_SEP_NUM = re.compile(r"(,|и|[–—-])\s*" + _NUM)
_MENTION_SOURCES = ("paragraph", "list_item", "other", "formula")


def join_lines(texts: list[str]) -> str:
    """Склеивает строки блока; перенос по слогам («си-» + «стемы») убирается."""
    out = ""
    for text in texts:
        text = text.strip()
        if not text:
            continue
        if not out:
            out = text
        elif _RE_HYPHEN_END.search(out) and text[:1].islower():
            out = out[:-1] + text
        else:
            out = f"{out} {text}"
    return out


def _style(labels: list[LineLabel]) -> Style:
    fonts: Counter = Counter()
    sizes: Counter = Counter()
    bold = italic = total = 0
    for lab in labels:
        n = len(lab.line.chars)
        fonts[lab.line.font] += n
        sizes[lab.line.size] += n
        bold += n if lab.line.bold else 0
        italic += n if lab.line.italic else 0
        total += n
    return Style(
        font=fonts.most_common(1)[0][0] if fonts else "",
        size=sizes.most_common(1)[0][0] if sizes else 0.0,
        bold=total > 0 and bold * 2 > total,
        italic=total > 0 and italic * 2 > total,
    )


def _make_block(index: int, labels: list[LineLabel]) -> Block:
    first = labels[0]
    page = first.line.page
    same_page = [lab.line for lab in labels if lab.line.page == page]
    bbox = (
        round(min(ln.x0 for ln in same_page), 2), round(min(ln.top for ln in same_page), 2),
        round(max(ln.x1 for ln in same_page), 2), round(max(ln.bottom for ln in same_page), 2),
    )
    last_page = labels[-1].line.page
    return Block(
        id=f"b-{index:04d}",
        type=first.type,
        page=page,
        page_end=last_page if last_page != page else None,
        bbox=bbox,
        text=join_lines([lab.line.text for lab in labels]),
        style=_style(labels),
        lines=[LineGeom(page=lab.line.page,
                        bbox=(round(lab.line.x0, 2), round(lab.line.top, 2),
                              round(lab.line.x1, 2), round(lab.line.bottom, 2)))
               for lab in labels],
        attrs=dict(first.attrs),
    )


_ZONE_TYPES = ("page_number", "header_footer")


def group_blocks(labels: list[LineLabel]) -> list[Block]:
    """Строки → блоки. Колонтитул не разрывает абзац или элемент списка, переходящий
    на следующую страницу: он становится отдельным блоком сразу после него."""
    groups: list[list[LineLabel]] = []
    current: list[LineLabel] = []
    zones: list[LineLabel] = []

    def flush():
        nonlocal current, zones
        if current:
            groups.append(current)
        groups.extend([z] for z in zones)
        current, zones = [], []

    for lab in labels:
        if lab.type in _ZONE_TYPES:
            if current:
                zones.append(lab)
            else:
                groups.append([lab])
            continue
        if current and (lab.starts_block or lab.type != current[0].type):
            flush()
        current.append(lab)
    flush()
    return [_make_block(n, group) for n, group in enumerate(groups, start=1)]


class PartClassifier:
    def __init__(self, concepts: Concepts):
        self.parts = [
            (part.id, [re.compile(p) for p in part.heading_patterns])
            for part in concepts.doc_parts if part.heading_patterns
        ]

    def classify(self, title: str) -> Optional[str]:
        key = fold(title).rstrip(" .:")
        for part_id, patterns in self.parts:
            if any(p.search(key) for p in patterns):
                return part_id
        return None


def build_sections(blocks: list[Block], classifier: PartClassifier) -> list[Section]:
    headings = [b for b in blocks if b.type == "heading"]
    sections: list[Section] = []
    for n, hb in enumerate(headings, start=1):
        match = _RE_NUMBERED.match(hb.text)
        number, title = (match.group(1), match.group(2)) if match else (None, hb.text)
        level = number.count(".") + 1 if number else 1
        kind = hb.attrs.get("part") or classifier.classify(title)
        parent = next((s for s in reversed(sections) if s.level < level), None)
        sections.append(Section(
            id=f"s-{n:03d}", level=level, number=number, title=title.strip(), kind=kind,
            effective_kind=kind or (parent.effective_kind if parent else None),
            parent_id=parent.id if parent else None,
            heading_block=hb.id, first_block=hb.id, last_block=hb.id,
            page_start=hb.page, page_end=hb.page,
        ))

    by_heading = {s.heading_block: s for s in sections}
    current: Optional[Section] = None
    for block in blocks:
        if block.id in by_heading:
            current = by_heading[block.id]
        if current is not None:
            block.section_id = current.id
            current.last_block = block.id
            current.page_end = max(current.page_end, block.page_end or block.page)
    return sections


def _expand(first: str, tail: str) -> list[str]:
    """«2.1» + «–2.3» → 2.1, 2.2, 2.3; «1.1» + «и 1.2» → 1.1, 1.2."""
    numbers = [first]
    for sep, num in _RE_SEP_NUM.findall(tail or ""):
        prev = numbers[-1]
        if sep in "–—-":
            a, b = prev.split("."), num.split(".")
            if len(a) == len(b) and a[:-1] == b[:-1] and a[-1].isdigit() and b[-1].isdigit():
                lo, hi = int(a[-1]), int(b[-1])
                if 0 < hi - lo <= 50:
                    prefix = ".".join(a[:-1])
                    numbers.extend(f"{prefix}.{k}" if prefix else str(k) for k in range(lo + 1, hi + 1))
                    continue
        numbers.append(num)
    return numbers


def link_captions(blocks: list[Block]) -> tuple[list[Captioned], list[Captioned], list[Mention]]:
    figures: dict[str, Captioned] = {}
    tables: dict[str, Captioned] = {}
    for block in blocks:
        if block.type not in ("caption_figure", "caption_table") or block.attrs.get("continued"):
            continue
        number = block.attrs.get("number", "")
        target = figures if block.type == "caption_figure" else tables
        if number and number not in target:
            title = re.split(r"\s[–—-]\s", block.text, maxsplit=1)
            target[number] = Captioned(
                kind="figure" if block.type == "caption_figure" else "table",
                number=number, title=title[1].strip() if len(title) > 1 else "",
                caption_block=block.id, page=block.page,
            )

    unresolved: list[Mention] = []
    for block in blocks:
        if block.type not in _MENTION_SOURCES:
            continue
        for kind, pattern, target in (("figure", _RE_FIG_MENTION, figures), ("table", _RE_TBL_MENTION, tables)):
            for match in pattern.finditer(block.text):
                for number in _expand(match.group(1), match.group(2)):
                    item = target.get(number)
                    if item is not None:
                        if block.id not in item.mentions:
                            item.mentions.append(block.id)
                    else:
                        unresolved.append(Mention(kind=kind, number=number, block_id=block.id, page=block.page))
    return list(figures.values()), list(tables.values()), unresolved


def build_document(raw: RawDocument, labeler: Labeler, concepts: Concepts,
                   source_format: str = "pdf") -> Document:
    labels, stats = labeler.label(raw)
    blocks = group_blocks(labels)
    sections = build_sections(blocks, PartClassifier(concepts))
    figures, tables, unresolved = link_captions(blocks)
    hidden = [frag for page in raw.pages for frag in page.hidden]
    return Document(
        meta=_meta(raw, stats, labeler, source_format),
        blocks=blocks,
        sections=sections,
        references=[b.id for b in blocks if b.type == "bibliography_entry"],
        figures=figures,
        tables=tables,
        unresolved_mentions=unresolved,
        hidden=hidden,
    )


def _meta(raw: RawDocument, stats: DocStats, labeler: Labeler, source_format: str) -> DocMeta:
    return DocMeta(
        pages=raw.page_count,
        page_sizes=[(round(p.width, 2), round(p.height, 2)) for p in raw.pages],
        sha256=raw.sha256,
        source_format=source_format,
        main_font=stats.main_font,
        main_size=stats.main_size,
        text_left=stats.text_left,
        has_text=raw.has_text,
        labeler=LabelerInfo(name=labeler.name, version=labeler.version),
    )
