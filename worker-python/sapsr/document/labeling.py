"""Шаг 2 цепочки: разметка строк на логические блоки (план, З1а и З2).

Разметчик — заменяемый компонент с интерфейсом Labeler: на вход RawDocument,
на выход — тип каждой строки и признак «с неё начинается новый блок».

RuleLabeler — разметка по признакам строки (размер шрифта относительно основного,
полужирность, отступ, центрирование, номер раздела, отточие и номер страницы,
соседние строки). Работает за миллисекунды и детерминированно; это базовый вариант
для каркаса и эталон для сравнения. Обученный классификатор строк или Docling
подключаются тем же интерфейсом — выбор по замерам на 9-й неделе (план, З1а).
"""

from __future__ import annotations

import re
from collections import Counter
from dataclasses import dataclass, field
from typing import Optional, Protocol

from sapsr.document.extract import RawDocument, RawLine, RawPage
from sapsr.document.text import fold
from sapsr.kb.schema import Concepts, LayoutParams

_RE_NUMBERED_HEADING = re.compile(r"^(\d{1,2}(?:\.\d{1,2}){0,3})\s+(\S.*)$")
_RE_CAPTION_FIG = re.compile(r"^(?:рисунок|рис\.)\s*(\d+(?:\s*\.\s*\d+)*)\s*(?:[–—-]\s*(.*))?$", re.IGNORECASE)
_RE_CAPTION_TBL = re.compile(r"^таблица\s*(\d+(?:\s*\.\s*\d+)*)\s*(?:[–—-]\s*(.*))?$", re.IGNORECASE)
_RE_CAPTION_TBL_CONT = re.compile(r"^(?:продолжение|окончание)\s+таблицы\s*(\d+(?:\.\d+)*)", re.IGNORECASE)
_RE_BIB_START = re.compile(r"^\s*(?:\[(\d{1,3})\]|(\d{1,3})[.)]?)\s+\S")
_RE_NUM_LIST = re.compile(r"^\s*\d{1,2}[.)]\s+\S")
_RE_LETTER_LIST = re.compile(r"^\s*[а-яa-z]\)\s+\S")
_RE_EQ_NUMBER = re.compile(r"\(\d+(?:\.\d+)*\)\s*$")
_RE_TRAILING_NUMBER = re.compile(r"(\d+)\s*$")
_RE_SENTENCE_END = re.compile(r"[.!?:;]\s*$")
# Конец элемента перечисления: двоеточие («Тело:») элемент не завершает.
_RE_ITEM_END = re.compile(r"[.!?;]\s*$")
_RE_LETTERS = re.compile(r"[A-Za-zА-Яа-яЁё]")


@dataclass
class LineLabel:
    line: RawLine
    type: str
    starts_block: bool = True
    attrs: dict[str, str] = field(default_factory=dict)


@dataclass
class DocStats:
    main_size: float
    main_font: str
    text_left: float
    text_right: float
    # Самый частый отступ красной строки в документе; None — красных строк не нашлось.
    para_indent: Optional[float] = None

    @property
    def center(self) -> float:
        return (self.text_left + self.text_right) / 2


class Labeler(Protocol):
    name: str
    version: str

    def label(self, raw: RawDocument) -> tuple[list[LineLabel], DocStats]:
        ...


class StructuralMatcher:
    """Заголовки структурных элементов и признаки титульного листа из словаря понятий."""

    def __init__(self, concepts: Concepts):
        self.structural = [
            (part.id, re.compile(pattern))
            for part in concepts.doc_parts for pattern in part.structural_titles
        ]
        title = concepts.part("part.title_page")
        self.title_page = [re.compile(p) for p in (title.page_patterns if title else [])]

    def structural_part(self, text: str) -> Optional[str]:
        key = fold(text).rstrip(" .:")
        for part_id, pattern in self.structural:
            if pattern.fullmatch(key):
                return part_id
        return None

    def is_title_page(self, page_text: str) -> bool:
        key = fold(page_text)
        return any(p.search(key) for p in self.title_page)


def compute_stats(raw: RawDocument, params: LayoutParams) -> DocStats:
    """Основной размер, шрифт и левый край текста — по страницам основного текста."""
    zones, start = params.zones, params.main_font.start_page
    size_w: Counter = Counter()
    font_w: Counter = Counter()
    lefts: list[int] = []
    rights: list[float] = []
    # Короткий документ: хотя бы без титульного листа, он свёрстан по центру.
    pages = ([p for p in raw.pages if p.number >= start] or [p for p in raw.pages if p.number > 1]
             or raw.pages)
    for page in pages:
        for line in page.lines:
            if line.top < zones.body_top_pt or line.top > zones.body_bottom_pt:
                continue
            weight = len(line.chars)
            size_w[line.size] += weight
            font_w[line.font] += weight
    main_size = size_w.most_common(1)[0][0] if size_w else 0.0
    for page in pages:
        for line in page.lines:
            if abs(line.size - main_size) <= 0.5 and zones.body_top_pt <= line.top <= zones.body_bottom_pt:
                lefts.append(round(line.x0))
                rights.append(line.x1)
    # Левый край — нижний процентиль начал строк: продолжения абзацев начинаются у края,
    # первые строки и элементы списков — правее, редкие выступы («(ИС)») — левее.
    lefts.sort()
    rights.sort()
    lp = params.labeler
    text_left = float(lefts[len(lefts) * lp.text_edge_percentile // 100]) if lefts else 0.0
    text_right = rights[len(rights) * (100 - lp.text_edge_percentile) // 100] if rights else 0.0
    indents: Counter = Counter()
    for page in pages:
        for line in page.lines:
            indent = line.x0 - text_left
            if (abs(line.size - main_size) <= 0.5 and not line.bold
                    and lp.paragraph_indent_min_pt <= indent <= lp.paragraph_indent_max_pt):
                indents[round(indent)] += 1
    return DocStats(
        main_size=main_size,
        main_font=font_w.most_common(1)[0][0] if font_w else "",
        text_left=text_left,
        text_right=text_right,
        para_indent=float(indents.most_common(1)[0][0]) if indents else None,
    )


class RuleLabeler:
    name = "rule-labeler"
    version = "1"

    def __init__(self, params: LayoutParams, concepts: Concepts):
        self.params = params
        self.lp = params.labeler
        self.matcher = StructuralMatcher(concepts)
        dots = self.lp.toc_leader_min_dots
        self._re_leader = re.compile(r"(?:\.\s?){%d,}\s*\d+\s*$" % dots)
        markers = "".join(re.escape(m) for m in self.lp.list_markers)
        self._re_marker = re.compile(r"^\s*[%s]\s+\S" % markers)

    # ── признаки строки ─────────────────────────────────────────────────────

    def is_toc_like(self, line: RawLine) -> bool:
        if self._re_leader.search(line.text):
            return True
        match = _RE_TRAILING_NUMBER.search(line.text)
        if not match or len(line.chars) <= len(match.group(1)):
            return False
        digits = len(match.group(1))
        tail = line.chars[-digits:]
        if not all(c.text.isdigit() for c in tail):
            return False
        gap = tail[0].x0 - line.chars[-digits - 1].x1
        return gap >= self.lp.toc_number_gap_pt

    def _is_centered(self, line: RawLine, stats: DocStats) -> bool:
        middle = (line.x0 + line.x1) / 2
        return (abs(middle - stats.center) <= self.lp.centered_tolerance_pt
                and line.x0 > stats.text_left + self.lp.paragraph_indent_min_pt)

    def _indent(self, line: RawLine, stats: DocStats) -> float:
        return line.x0 - stats.text_left

    def _is_paragraph_start(self, line: RawLine, stats: DocStats) -> bool:
        indent = self._indent(line, stats)
        if stats.para_indent is not None:
            return abs(indent - stats.para_indent) <= self.lp.paragraph_indent_tolerance_pt
        return self.lp.paragraph_indent_min_pt <= indent <= self.lp.paragraph_indent_max_pt

    def _is_math(self, line: RawLine) -> bool:
        fonts = self.lp.formula_math_fonts
        math = sum(1 for c in line.chars if any(f in c.font.lower() for f in fonts))
        return math / max(len(line.chars), 1) >= self.lp.formula_math_font_share

    def _heading_score(self, line: RawLine, prev: Optional[RawLine], stats: DocStats) -> int:
        letters = _RE_LETTERS.findall(line.text)
        score = 0
        if line.bold:
            score += 1
        if stats.main_size and line.size >= stats.main_size * self.lp.heading_size_ratio:
            score += 1
        if letters and all(ch.isupper() for ch in letters):
            score += 1
        if prev is not None and not self._close_below(prev, line):
            score += 1
        return score

    def _numbered_heading(self, line: RawLine, prev: Optional[RawLine], stats: DocStats) -> Optional[str]:
        match = _RE_NUMBERED_HEADING.match(line.text)
        if not match:
            return None
        title = match.group(2)
        if (len(line.text) > self.lp.heading_max_chars or not title[0].isupper()
                or line.text.rstrip().endswith((".", ";", ",", ":"))):
            return None
        if self._heading_score(line, prev, stats) < self.lp.heading_min_score:
            return None
        return match.group(1)

    def _same_style(self, a: RawLine, b: RawLine) -> bool:
        return abs(a.size - b.size) < 0.2 and a.bold == b.bold

    @staticmethod
    def _wraps(prev: RawLine, line: RawLine, stats: DocStats) -> bool:
        """Строка — перенос предыдущей: её первое слово не поместилось бы в предыдущую строку."""
        if line.text[:1].islower():
            return True
        chars = line.chars
        if not chars:
            return False
        space = line.size * 0.25
        end = 0
        while end + 1 < len(chars) and chars[end + 1].x0 - chars[end].x1 < space:
            end += 1
        first_word = chars[end].x1 - chars[0].x0
        return prev.x1 + space + first_word > stats.text_right

    def _close_below(self, prev: RawLine, line: RawLine) -> bool:
        if prev.page != line.page:
            return False
        return line.top - prev.top <= max(prev.size, line.size) * self.lp.line_pitch_max_ratio

    # ── разметка ────────────────────────────────────────────────────────────

    def label(self, raw: RawDocument) -> tuple[list[LineLabel], DocStats]:
        stats = compute_stats(raw, self.params)
        labels: list[LineLabel] = []
        in_toc = False
        in_refs = False
        bib_next: Optional[int] = None
        for page in raw.pages:
            title_page = page.number == 1 and self.matcher.is_title_page(page.text)
            lines = page.lines
            continue_heading = False
            for i, line in enumerate(lines):
                if title_page:
                    labels.append(LineLabel(line, "title_page"))
                    continue
                zone = self._zone_label(line, page)
                if zone:
                    labels.append(LineLabel(line, zone))
                    continue
                if continue_heading:
                    continue_heading = False
                    labels.append(LineLabel(line, "heading", starts_block=False))
                    continue
                prev = self._prev_content(labels)
                next_line = lines[i + 1] if i + 1 < len(lines) else None

                # Заголовок структурного элемента в две строки: «СПИСОК ИСПОЛЬЗОВАННЫХ / ИСТОЧНИКОВ».
                if (next_line is not None and not in_toc and self._same_style(line, next_line)
                        and self._close_below(line, next_line)
                        and not self.matcher.structural_part(line.text)):
                    joined = self.matcher.structural_part(f"{line.text} {next_line.text}")
                    if joined:
                        labels.append(LineLabel(line, "heading", attrs={"part": joined}))
                        in_toc = joined == "part.toc"
                        in_refs = joined == "part.references"
                        continue_heading = True
                        continue

                if in_toc:
                    if self.is_toc_like(line):
                        labels.append(self._toc_entry(line, prev))
                        continue
                    if (next_line is not None and self.is_toc_like(next_line)
                            and not self.matcher.structural_part(line.text)):
                        labels.append(self._toc_entry(line, prev))
                        continue
                    in_toc = False

                part = self.matcher.structural_part(line.text)
                if part:
                    labels.append(LineLabel(line, "heading", attrs={"part": part}))
                    in_toc = part == "part.toc"
                    in_refs = part == "part.references"
                    bib_next = None
                    continue

                number = self._numbered_heading(line, prev.line if prev else None, stats)
                if number and not in_refs:
                    labels.append(LineLabel(line, "heading", attrs={"number": number}))
                    continue

                if prev is not None and prev.type == "heading" and "number" in self._block_attrs(labels):
                    if (self._same_style(prev.line, line) and self._close_below(prev.line, line)
                            and not _RE_NUMBERED_HEADING.match(line.text)
                            and len(line.text) <= self.lp.heading_max_chars
                            and (line.bold or line.size > stats.main_size)
                            and self._wraps(prev.line, line, stats)):
                        labels.append(LineLabel(line, "heading", starts_block=False))
                        continue

                if in_refs:
                    match = _RE_BIB_START.match(line.text)
                    # Номера записей идут подряд: «120 с.» в начале строки — продолжение, а не запись № 120.
                    if match and (bib_next is None or int(match.group(1) or match.group(2)) == bib_next):
                        bib_next = int(match.group(1) or match.group(2)) + 1
                        labels.append(LineLabel(line, "bibliography_entry"))
                        continue
                    if prev is not None and prev.type == "bibliography_entry":
                        labels.append(LineLabel(line, "bibliography_entry", starts_block=False))
                        continue

                caption = self._caption(line)
                if caption:
                    labels.append(caption)
                    continue
                if (prev is not None and prev.type in ("caption_figure", "caption_table")
                        and self._close_below(prev.line, line) and self._is_centered(line, stats)
                        and self._same_style(prev.line, line)):
                    labels.append(LineLabel(line, prev.type, starts_block=False))
                    continue

                if self._is_math(line) or (_RE_EQ_NUMBER.search(line.text)
                                           and self._is_centered(line, stats)):
                    labels.append(LineLabel(line, "formula"))
                    continue

                list_kind = self._list_marker(line)
                if (list_kind == "dash" and prev is not None and prev.type in ("paragraph", "list_item")
                        and prev.line.page == line.page and not _RE_SENTENCE_END.search(prev.line.text)
                        and self._indent(line, stats) < self.lp.paragraph_indent_min_pt):
                    list_kind = None  # тире в начале строки посреди предложения, а не маркер
                if list_kind:
                    labels.append(LineLabel(line, "list_item", attrs={"marker": list_kind}))
                    continue
                if prev is not None and prev.type == "list_item":
                    ended = _RE_ITEM_END.search(prev.line.text)
                    indented = self._indent(line, stats) >= self.lp.paragraph_indent_min_pt
                    if not (ended and indented):
                        labels.append(LineLabel(line, "list_item", starts_block=False))
                        continue

                labels.append(self._paragraph(line, prev, stats))
        return labels, stats

    # ── помощники ───────────────────────────────────────────────────────────

    @staticmethod
    def _prev_content(labels: list[LineLabel]) -> Optional[LineLabel]:
        for lab in reversed(labels):
            if lab.type not in ("page_number", "header_footer"):
                return lab
        return None

    @staticmethod
    def _block_attrs(labels: list[LineLabel]) -> dict[str, str]:
        for lab in reversed(labels):
            if lab.starts_block and lab.type not in ("page_number", "header_footer"):
                return lab.attrs
        return {}

    def _zone_label(self, line: RawLine, page: RawPage) -> Optional[str]:
        zones = self.params.zones
        text = line.text.strip()
        if (line.top >= zones.page_number_top_pt and text.isdigit()
                and int(text) <= zones.page_number_max and line.x0 >= zones.page_number_x_min_pt):
            return "page_number"
        if line.top > zones.body_bottom_pt or line.bottom < zones.body_top_pt:
            return "header_footer"
        return None

    def _toc_entry(self, line: RawLine, prev: Optional[LineLabel]) -> LineLabel:
        continues = (prev is not None and prev.type == "toc_entry"
                     and not self.is_toc_like(prev.line))
        return LineLabel(line, "toc_entry", starts_block=not continues)

    def _caption(self, line: RawLine) -> Optional[LineLabel]:
        match = _RE_CAPTION_FIG.match(line.text)
        if match:
            return LineLabel(line, "caption_figure", attrs={"number": re.sub(r"\s", "", match.group(1))})
        match = _RE_CAPTION_TBL_CONT.match(line.text)
        if match:
            return LineLabel(line, "caption_table", attrs={"number": match.group(1), "continued": "1"})
        match = _RE_CAPTION_TBL.match(line.text)
        if match:
            return LineLabel(line, "caption_table", attrs={"number": re.sub(r"\s", "", match.group(1))})
        return None

    def _list_marker(self, line: RawLine) -> Optional[str]:
        if self._re_marker.match(line.text):
            return "dash"
        if _RE_NUM_LIST.match(line.text):
            return "number"
        if _RE_LETTER_LIST.match(line.text):
            return "letter"
        return None

    def _paragraph(self, line: RawLine, prev: Optional[LineLabel], stats: DocStats) -> LineLabel:
        if prev is None or prev.type != "paragraph":
            return LineLabel(line, "paragraph")
        if self._is_paragraph_start(line, stats):
            return LineLabel(line, "paragraph")
        if prev.line.page == line.page and not self._close_below(prev.line, line):
            return LineLabel(line, "paragraph")
        return LineLabel(line, "paragraph", starts_block=False)
