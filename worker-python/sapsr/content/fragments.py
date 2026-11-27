"""Отбор текста для языковой модели (план, З1б, оценка бюджета).

В модель подаётся не весь документ, а только значимые части записки — введение,
требования, архитектура, реализация, заключение — нарезанные по разделам на фрагменты
ограниченного размера. Каждый блок фрагмента помечен идентификатором, по которому модель
ссылается на цитату, а правила затем находят страницу и раздел.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Annotated

from pydantic import BeforeValidator, Field

from sapsr.document.model import Block, Document, readable_title

TEXT_BLOCKS = ("paragraph", "list_item")


def clean_block_id(value):
    """Модель видит блоки как «[b-0042] текст» и иногда возвращает идентификатор вместе со скобками —
    на живой модели так отвечала каждая оценка требований семестра (23.10.2026), и каждый ответ шёл
    на повтор. Скобки снимаются до проверки схемой: идентификатор тот же."""
    return value.strip().strip("[]").strip() if isinstance(value, str) else value


# Ссылка модели на блок фрагмента: в ответах по JSON-схеме и в ответах без строгого шаблона.
BlockId = Annotated[str, BeforeValidator(clean_block_id), Field(pattern=r"^b-\d{4,}$")]
LooseBlockId = Annotated[str, BeforeValidator(clean_block_id)]


@dataclass
class Fragment:
    id: str
    section_id: str
    section_label: str
    section_title: str
    kind: str
    blocks: list[Block] = field(default_factory=list)

    @property
    def chars(self) -> int:
        return sum(len(b.text) for b in self.blocks)

    def render(self) -> str:
        return "\n".join(f"[{b.id}] {b.text}" for b in self.blocks)


def build_fragments(doc: Document, parts: list[str], max_chars: int, budget: int) -> tuple[list[Fragment], int]:
    """Фрагменты из разделов нужных частей записки, по приоритету частей. Возвращает
    фрагменты в порядке документа и сколько символов не поместилось в бюджет."""
    by_section: dict[str, list[Block]] = {}
    for block in doc.blocks:
        if block.type in TEXT_BLOCKS and block.section_id and block.text.strip():
            by_section.setdefault(block.section_id, []).append(block)

    chunks: list[Fragment] = []
    for section in doc.sections:
        kind = section.effective_kind
        if kind not in parts:
            continue
        current = Fragment(id="", section_id=section.id, section_label=section.number or readable_title(section.title),
                           section_title=section.title, kind=kind)
        for block in by_section.get(section.id, []):
            if current.blocks and current.chars + len(block.text) > max_chars:
                chunks.append(current)
                current = Fragment(id="", section_id=section.id, section_label=current.section_label,
                                   section_title=section.title, kind=kind)
            current.blocks.append(block)
        if current.blocks:
            chunks.append(current)

    priority = {part: i for i, part in enumerate(parts)}
    selected: list[Fragment] = []
    used = dropped = 0
    for chunk in sorted(chunks, key=lambda c: (priority[c.kind], doc.block_pos(c.blocks[0].id))):
        if used + chunk.chars > budget:
            dropped += chunk.chars
            continue
        used += chunk.chars
        selected.append(chunk)
    selected.sort(key=lambda c: doc.block_pos(c.blocks[0].id))
    for n, chunk in enumerate(selected, start=1):
        chunk.id = f"fr-{n:02d}"
    return selected, dropped
