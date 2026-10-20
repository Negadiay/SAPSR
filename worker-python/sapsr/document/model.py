"""Модель документа — единая структура для правил и языковой модели (план, З2).

JSON этой модели — точка интеграции «разметка → правила»: его можно сохранить
(`python analyzer.py работа.pdf --dump-document doc.json`) и проверять правила
на нём без повторного разбора PDF.

Координаты — в пунктах PDF, начало в левом верхнем углу страницы, как у pdfplumber:
bbox = [x0, top, x1, bottom].
"""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, PrivateAttr

BBox = tuple[float, float, float, float]

BlockType = Literal[
    "title_page",          # текст титульного листа
    "heading",             # заголовок раздела или структурного элемента
    "paragraph",           # абзац основного текста
    "list_item",           # элемент перечисления
    "caption_figure",      # подпись рисунка: «Рисунок 2.1 – …»
    "caption_table",       # подпись таблицы: «Таблица 1.1 – …», «Продолжение таблицы 1.1»
    "bibliography_entry",  # запись списка источников
    "toc_entry",           # строка оглавления — в дерево разделов не попадает
    "formula",             # формула или строка с номером формулы
    "page_number",         # номер страницы в колонтитуле
    "header_footer",       # прочий текст колонтитула
    "other",
]

BLOCK_TYPES: tuple[str, ...] = BlockType.__args__  # type: ignore[attr-defined]


def readable_title(title: str) -> str:
    """«ВВЕДЕНИЕ» → «Введение»: заголовки прописными в отчёте читаются хуже."""
    letters = [ch for ch in title if ch.isalpha()]
    if letters and all(ch.isupper() for ch in letters):
        return title[:1] + title[1:].lower()
    return title


def section_phrase(label: str) -> str:
    """Подпись раздела в тексте замечания: «1.2» → «подраздел 1.2», «3» → «раздел 3»."""
    if label and label[0].isdigit():
        return f"подраздел {label}" if "." in label else f"раздел {label}"
    return label


class Style(BaseModel):
    model_config = ConfigDict(frozen=True)

    font: str = ""
    size: float = 0.0
    bold: bool = False
    italic: bool = False


class LineGeom(BaseModel):
    """Строка блока: страница и прямоугольник. Нужны правилам абзацев и интервалов."""

    model_config = ConfigDict(frozen=True)

    page: int
    bbox: BBox


class Block(BaseModel):
    id: str
    type: BlockType
    page: int
    page_end: Optional[int] = None
    bbox: BBox
    text: str
    style: Style = Style()
    section_id: Optional[str] = None
    lines: list[LineGeom] = Field(default_factory=list)
    # Признаки, которые разметчик вычислил для блока (номер раздела, маркер списка и т. п.).
    attrs: dict[str, str] = Field(default_factory=dict)
    # Уверенность разметчика: у правил разметки — null, у обученной модели — вероятность класса.
    confidence: Optional[float] = None


class Section(BaseModel):
    id: str
    level: int
    number: Optional[str] = None
    title: str
    # Понятие из словаря базы знаний (kb/concepts.yaml, doc_parts): part.intro, part.conclusion…
    kind: Optional[str] = None
    # Понятие с учётом родителя: у «1.2 Ограничения» внутри «1 Анализ…» это part.analysis.
    effective_kind: Optional[str] = None
    parent_id: Optional[str] = None
    heading_block: str
    first_block: str
    last_block: str
    page_start: int
    page_end: int


class Captioned(BaseModel):
    """Рисунок или таблица: подпись и упоминания в тексте."""

    kind: Literal["figure", "table"]
    number: str
    title: str = ""
    caption_block: str
    page: int
    mentions: list[str] = Field(default_factory=list)


class Mention(BaseModel):
    kind: Literal["figure", "table"]
    number: str
    block_id: str
    page: int


class HiddenFragment(BaseModel):
    page: int
    reason: Literal["white", "tiny", "offpage"]
    text: str
    bbox: BBox


class LabelerInfo(BaseModel):
    name: str
    version: str


class DocMeta(BaseModel):
    pages: int
    page_sizes: list[tuple[float, float]] = Field(default_factory=list)
    sha256: Optional[str] = None
    source_format: str = "pdf"
    main_font: str = ""
    main_size: float = 0.0
    text_left: float = 0.0
    has_text: bool = True
    labeler: Optional[LabelerInfo] = None


class Document(BaseModel):
    schema_version: int = 1
    meta: DocMeta
    blocks: list[Block] = Field(default_factory=list)
    sections: list[Section] = Field(default_factory=list)
    references: list[str] = Field(default_factory=list)
    figures: list[Captioned] = Field(default_factory=list)
    tables: list[Captioned] = Field(default_factory=list)
    unresolved_mentions: list[Mention] = Field(default_factory=list)
    hidden: list[HiddenFragment] = Field(default_factory=list)

    _block_index: dict[str, int] = PrivateAttr(default_factory=dict)
    _section_index: dict[str, int] = PrivateAttr(default_factory=dict)

    def model_post_init(self, __context) -> None:
        self.reindex()

    def reindex(self) -> None:
        self._block_index = {b.id: i for i, b in enumerate(self.blocks)}
        self._section_index = {s.id: i for i, s in enumerate(self.sections)}

    # ── поиск ────────────────────────────────────────────────────────────────

    def block(self, block_id: str) -> Optional[Block]:
        idx = self._block_index.get(block_id)
        return self.blocks[idx] if idx is not None else None

    def block_pos(self, block_id: str) -> int:
        return self._block_index[block_id]

    def section(self, section_id: Optional[str]) -> Optional[Section]:
        if section_id is None:
            return None
        idx = self._section_index.get(section_id)
        return self.sections[idx] if idx is not None else None

    def blocks_of(self, *types: str) -> list[Block]:
        return [b for b in self.blocks if b.type in types]

    def sections_of_kind(self, kind: str) -> list[Section]:
        return [s for s in self.sections if s.kind == kind]

    def section_blocks(self, section: Section, include_subsections: bool = True) -> list[Block]:
        """Блоки раздела после заголовка; с подразделами — до следующего заголовка того же или старшего уровня."""
        start = self.block_pos(section.heading_block) + 1
        if include_subsections:
            end = len(self.blocks)
            for other in self.sections:
                pos = self.block_pos(other.heading_block)
                if pos >= start and other.level <= section.level:
                    end = pos
                    break
        else:
            end = self.block_pos(section.last_block) + 1
        return self.blocks[start:end]

    def section_label(self, section_id: Optional[str]) -> str:
        """Короткая подпись раздела для отчёта: номер, если есть, иначе заголовок («Введение»)."""
        section = self.section(section_id)
        if section is None:
            return ""
        return section.number or readable_title(section.title)

    def body_text(self, types: tuple[str, ...] = ("paragraph", "list_item")) -> str:
        return "\n".join(b.text for b in self.blocks if b.type in types)
