"""Контекст проверки и общие вычисления, которыми пользуются несколько проверок."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Optional

from sapsr.directories import Directory
from sapsr.document.extract import Char, RawDocument
from sapsr.document.model import Document, Section
from sapsr.kb.schema import Concepts, LayoutParams
from sapsr.task import Task


@dataclass
class PageMetric:
    page: int
    size: float
    font: str


@dataclass
class CheckContext:
    doc: Document
    raw: RawDocument
    layout: LayoutParams
    concepts: Concepts
    task: Task
    directory: Directory
    now: datetime
    _memo: dict = field(default_factory=dict)

    def memo(self, key: str, factory: Callable):
        if key not in self._memo:
            self._memo[key] = factory()
        return self._memo[key]

    # ── основной текст ──────────────────────────────────────────────────────

    def body_chars(self, page_no: int) -> list[Char]:
        """Символы основного текста страницы (без колонтитулов)."""
        def compute():
            zones = self.layout.zones
            page = self.raw.pages[page_no - 1]
            return [c for c in page.chars if zones.body_top_pt <= c.top <= zones.body_bottom_pt]
        return self.memo(f"body:{page_no}", compute)

    def references_section(self) -> Optional[Section]:
        sections = self.doc.sections_of_kind("part.references")
        return sections[-1] if sections else None

    def reference_pages(self) -> set[int]:
        """Страницы списка источников и всё после него — как в прежнем анализаторе."""
        def compute():
            section = self.references_section()
            if section is None:
                return set()
            return set(range(section.page_start, self.raw.page_count + 1))
        return self.memo("reference_pages", compute)

    def page_metrics(self) -> list[PageMetric]:
        """Преобладающие размер и шрифт каждой страницы основного текста."""
        def compute():
            mf = self.layout.main_font
            result = []
            for page in self.raw.pages:
                if page.number < mf.start_page:
                    continue
                body = self.body_chars(page.number)
                effective = body if len(body) >= mf.min_body_chars else page.chars
                result.append(PageMetric(page.number, dominant_size(effective), dominant_font(effective)))
            return result
        return self.memo("page_metrics", compute)

    def main_metrics(self) -> list[PageMetric]:
        refs = self.reference_pages()
        metrics = self.page_metrics()
        return [m for m in metrics if m.page not in refs] or metrics

    def reference_metrics(self) -> list[PageMetric]:
        refs = self.reference_pages()
        return [m for m in self.page_metrics() if m.page in refs]

    def dominant_main_size(self) -> float:
        counts = Counter(m.size for m in self.main_metrics() if m.size)
        return counts.most_common(1)[0][0] if counts else 0.0

    def dominant_main_font(self) -> str:
        counts = Counter(m.font for m in self.main_metrics() if m.font)
        return counts.most_common(1)[0][0] if counts else ""

    def main_body_chars(self) -> list[Char]:
        refs = self.reference_pages()
        pages = [p.number for p in self.raw.pages]
        chars = [c for n in pages if n not in refs for c in self.body_chars(n)]
        return chars or [c for n in pages for c in self.body_chars(n)]

    def reference_body_chars(self) -> list[Char]:
        return [c for n in sorted(self.reference_pages()) for c in self.body_chars(n)]


def dominant_size(chars: list[Char]) -> float:
    counts = Counter(round(c.size, 1) for c in chars)
    return counts.most_common(1)[0][0] if counts else 0.0


def dominant_font(chars: list[Char]) -> str:
    counts = Counter(c.font for c in chars if c.font)
    return counts.most_common(1)[0][0] if counts else ""
