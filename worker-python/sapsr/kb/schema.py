"""Схемы базы знаний (план, З3). Файлы лежат в worker-python/kb/ и проверяются при старте."""

from __future__ import annotations

import re
from typing import Literal, Optional, Union

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Severity = Literal["critical", "major", "minor", "info"]
Category = Literal["format", "structure", "content", "requirements", "integrity"]
RuleStage = Literal["format", "content"]
Cardinality = Literal["single", "multi", "text"]
WorkType = Literal["course_work", "course_project", "diploma"]

RULE_ID_RE = re.compile(r"^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+$")

AllOr = Union[Literal["all"], list]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ── Параметры извлечения и разметки (kb/layout.yaml) ────────────────────────

class HiddenTextParams(Strict):
    white_threshold: float
    min_size_pt: float
    page_overflow_pt: float
    min_chars: int


class ZoneParams(Strict):
    body_top_pt: float
    body_bottom_pt: float
    page_number_top_pt: float
    page_number_x_min_pt: float
    page_number_char_gap_pt: float
    page_number_max: int


class MainFontParams(Strict):
    start_page: int
    min_body_chars: int


class LabelerParams(Strict):
    line_y_tolerance_pt: float
    text_edge_percentile: int
    heading_max_chars: int
    heading_size_ratio: float
    heading_min_score: int
    centered_tolerance_pt: float
    paragraph_indent_min_pt: float
    paragraph_indent_max_pt: float
    continuation_tolerance_pt: float
    line_pitch_max_ratio: float
    paragraph_indent_tolerance_pt: float
    toc_number_gap_pt: float
    toc_leader_min_dots: int
    formula_math_font_share: float
    formula_math_fonts: list[str]
    list_markers: list[str]


class ContextParams(Strict):
    before: int
    after: int
    max_len: int


class LayoutParams(Strict):
    hidden_text: HiddenTextParams
    zones: ZoneParams
    main_font: MainFontParams
    labeler: LabelerParams
    context: ContextParams


# ── Правила (kb/rules/*.yaml) ───────────────────────────────────────────────

class Source(Strict):
    doc: str
    clause: Optional[str] = None
    note: Optional[str] = None


class AppliesTo(Strict):
    work_types: AllOr = "all"
    semesters: AllOr = "all"
    stages: AllOr = "all"
    source_formats: AllOr = "all"

    def matches(self, work_type: Optional[str], semester: Optional[int], stage: Optional[int],
                source_format: str) -> bool:
        def ok(allowed, value) -> bool:
            return allowed == "all" or value is None or value in allowed

        return (ok(self.work_types, work_type) and ok(self.semesters, semester)
                and ok(self.stages, stage) and ok(self.source_formats, source_format))


class Rule(Strict):
    id: str
    title: str
    category: Category
    stage: RuleStage = "format"
    source: Source
    applies_to: AppliesTo = AppliesTo()
    severity: Severity
    blocking: bool = False
    # Требует проверки преподавателем, даже если сработало правило (скрытый текст, ФИО на титуле).
    review: bool = False
    # Имя проверки в реестре (stage: format) или правила CLIPS (stage: content).
    check: str
    params: dict = Field(default_factory=dict)
    message: str
    expected: str = ""
    found: str = ""
    fix: str = ""
    # Шаблонное объяснение — показывается, если объяснение модели не прошло проверку (З6).
    explanation: str = ""

    @field_validator("id")
    @classmethod
    def _id_format(cls, value: str) -> str:
        if not RULE_ID_RE.match(value):
            raise ValueError(f"идентификатор правила {value!r} не по формату AAA-BBB")
        return value

    @model_validator(mode="after")
    def _only_symbolic_blocks(self) -> "Rule":
        if self.blocking and self.stage != "format":
            raise ValueError(f"{self.id}: блокирующим может быть только правило оформления (решение 5)")
        return self


# ── Профили (kb/profiles/*.yaml) ────────────────────────────────────────────

class ProfileMatch(Strict):
    work_types: AllOr = "all"
    semesters: AllOr = "all"
    stages: AllOr = "all"


class RuleOverride(Strict):
    enabled: Optional[bool] = None
    severity: Optional[Severity] = None
    blocking: Optional[bool] = None
    params: dict = Field(default_factory=dict)


class ContentSettings(Strict):
    enabled: Optional[bool] = None
    # Проверять ли содержание работы, отклонённой по оформлению (решение 9: преподаватель получает
    # и такие работы). Не проверяется в любом случае, если документ не читается или в нём нет текста.
    run_when_rejected: Optional[bool] = None
    # Части записки, из которых модель извлекает утверждения (понятия из concepts.yaml).
    sections: Optional[list[str]] = None
    # Наборы требований семестра (kb/requirements/<id>.yaml).
    requirement_sets: Optional[list[str]] = None
    fragment_max_chars: Optional[int] = None
    max_chars_total: Optional[int] = None
    max_claims_per_fragment: Optional[int] = None
    quote_min_score: Optional[float] = None
    quote_min_chars: Optional[int] = None
    raw_value_confidence: Optional[float] = None
    review_threshold: Optional[float] = None
    requirement_candidates: Optional[int] = None
    requirement_min_bm25: Optional[float] = None


class RuleSelection(Strict):
    include: AllOr = "all"
    exclude: list[str] = Field(default_factory=list)


class Profile(Strict):
    id: str
    title: str
    extends: Optional[str] = None
    # Без match профиль выбирается только явно или как базовый.
    match: Optional[ProfileMatch] = None
    rules: RuleSelection = RuleSelection()
    overrides: dict[str, RuleOverride] = Field(default_factory=dict)
    content: ContentSettings = ContentSettings()


# ── Словарь понятий (kb/concepts.yaml) ──────────────────────────────────────

class DocPart(Strict):
    id: str
    title: str
    # Регулярные выражения по заголовку раздела (нижний регистр, «ё» → «е»).
    heading_patterns: list[str] = Field(default_factory=list)
    # Заголовок структурного элемента («ВВЕДЕНИЕ»): регулярное выражение на всю строку
    # (нижний регистр, «ё» → «е»). Такая строка — заголовок при любом начертании.
    structural_titles: list[str] = Field(default_factory=list)
    # Признаки на странице (титульный лист узнаётся по тексту первой страницы).
    page_patterns: list[str] = Field(default_factory=list)


class AttrValue(Strict):
    id: str
    # Как значение называть в отчёте; по умолчанию — идентификатор.
    title: Optional[str] = None
    # Подстроки или регулярные выражения (нижний регистр, «ё» → «е»), по которым узнаётся значение.
    synonyms: list[str]


class Attribute(Strict):
    id: str
    title: str
    description: str
    cardinality: Cardinality
    values: list[AttrValue] = Field(default_factory=list)
    # Где утверждение обязано подтверждаться: заявлено во введении — должно быть описано здесь.
    must_be_described_in: list[str] = Field(default_factory=list)


class Concepts(Strict):
    version: str
    doc_parts: list[DocPart]
    attributes: list[Attribute]

    def part(self, part_id: str) -> Optional[DocPart]:
        return next((p for p in self.doc_parts if p.id == part_id), None)

    def attribute(self, attr_id: str) -> Optional[Attribute]:
        return next((a for a in self.attributes if a.id == attr_id), None)

    def value_title(self, attr_id: str, value_id: str) -> str:
        attr = self.attribute(attr_id)
        value = next((v for v in attr.values if v.id == value_id), None) if attr else None
        return (value.title or value.id) if value else value_id


# ── Требования семестра (kb/requirements/*.yaml) ────────────────────────────

class SemesterRequirement(Strict):
    id: str
    title: str
    text: str
    stages: list[int]
    expected_in: list[str] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)
    sufficient: str
    source: Source


class RequirementSet(Strict):
    id: str
    title: str
    semester: int
    draft: bool = False
    requirements: list[SemesterRequirement]


# ── Каталог (kb/catalog.yaml) ───────────────────────────────────────────────

class PromptRef(Strict):
    claims: str
    requirements: str
    neural: str


class Catalog(Strict):
    version: str
    title: str
    base_profile: str
    prompts: PromptRef
    reasoning: list[str]
