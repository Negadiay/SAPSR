"""Отчёт v2 — контракт между обработчиком, бэкендом и интерфейсом.

Описание полей — docs/contracts/report-v2.md, JSON-схема — docs/contracts/report-v2.schema.json
(генерируется из этих классов: python tools/export_schema.py; тест сверяет, что файл не отстал).
"""

from __future__ import annotations

from typing import Literal, Optional, Union

from pydantic import BaseModel, ConfigDict, Field

Severity = Literal["critical", "major", "minor", "info"]
Category = Literal["format", "structure", "content", "requirements", "integrity"]
Origin = Literal["symbolic", "neural", "hybrid"]
FindingStatus = Literal["violated", "not_established", "disputed"]
RequirementState = Literal["satisfied", "violated", "not_applicable", "not_established", "disputed"]
Mode = Literal["symbolic", "hybrid", "neural"]
Phase = Literal["format", "content"]


class _Model(BaseModel):
    model_config = ConfigDict(populate_by_name=True)


class Evidence(_Model):
    page: Optional[int] = Field(None, description="Страница PDF-версии работы, с 1")
    quote: Optional[str] = Field(None, description="Дословный фрагмент документа")
    block_id: Optional[str] = Field(None, description="Блок модели документа, b-NNNN")
    section: Optional[str] = Field(None, description="Номер или заголовок раздела")
    bbox: Optional[tuple[float, float, float, float]] = Field(
        None, description="Прямоугольник на странице, пт: x0, top, x1, bottom; начало — левый верхний угол")


class SourceRef(_Model):
    doc: str = Field(description="Нормативный документ")
    clause: Optional[str] = Field(None, description="Пункт; null — ещё не сверен с документом")


class Finding(_Model):
    id: str = Field(description="Номер замечания в отчёте: f-001…; стабилен при повторной проверке того же файла")
    requirement_id: str = Field(description="Идентификатор правила или требования из базы знаний")
    category: Category
    status: FindingStatus = "violated"
    severity: Severity
    origin: Origin = Field(description="symbolic — правило; neural — модель; hybrid — правило над фактами модели")
    confidence: Optional[float] = Field(None, ge=0, le=1, description="Только у neural и hybrid")
    needs_teacher_review: bool = False
    blocking: bool = Field(description="Блокирует сдачу. Бывает только у origin: symbolic")
    title: Optional[str] = Field(None, description="Краткое название правила")
    message: str
    expected: Optional[str] = None
    found: Optional[str] = None
    evidence: list[Evidence] = Field(default_factory=list)
    source: Optional[SourceRef] = None
    fix: Optional[str] = None
    # ── поля отчёта v1, оставлены на переходный период ──────────────────────
    page: Optional[Union[int, str]] = Field(None, description="v1: страница или диапазон «3–5, 9»")
    location: Optional[str] = Field(None, description="v1: место в документе словами")
    rule: Optional[str] = Field(None, description="v1: требование словами (то же, что expected)")
    context: Optional[str] = Field(None, description="v1: фрагмент текста")


class RequirementStatus(_Model):
    id: str
    title: str
    status: RequirementState
    confidence: Optional[float] = Field(None, ge=0, le=1)
    needs_teacher_review: bool = False
    evidence: list[Evidence] = Field(default_factory=list)
    note: Optional[str] = None
    source: Optional[SourceRef] = None


class TitlePageInfo(_Model):
    student_name: Optional[str] = Field(None, description="ФИО студента с титульного листа, как написано")
    matches_registration: Optional[bool] = Field(
        None, description="Совпадает ли фамилия с зарегистрированной; null — сравнить не с чем")
    work_type: Optional[str] = Field(
        None, description="Вид работы по титульному листу: course_work, course_project, diploma; null — не распознан")


class DocumentInfo(_Model):
    pages: int
    sha256: Optional[str] = None
    source_format: str = "pdf"
    main_font: Optional[str] = None
    main_size: Optional[float] = None
    title_page: Optional[TitlePageInfo] = None


class ProfileInfo(_Model):
    id: str
    title: str
    work_type: Optional[str] = None
    semester: Optional[int] = None
    stage: Optional[int] = None
    is_default: bool = False


class ModelInfo(_Model):
    role: Literal["layout", "llm", "embeddings", "nli"]
    name: str
    version: Optional[str] = None
    quantization: Optional[str] = None
    provider: Optional[str] = None
    cloud: bool = False


class PromptInfo(_Model):
    id: str
    version: str


class StageInfo(_Model):
    stage: Literal["extract", "structure", "format", "claims", "reasoning", "report"]
    status: Literal["done", "skipped", "failed"]
    ms: Optional[int] = None


class SkippedCheck(_Model):
    id: str = Field(description="Правило, требование или этап, который не выполнялся")
    stage: str
    reason: str = Field(description="Код причины: IIS_UNAVAILABLE, LLM_UNAVAILABLE, LLM_SCHEMA, TIMEOUT…")
    message: str


class PipelineWarning(_Model):
    code: str
    message: str


class Pipeline(_Model):
    mode: Mode
    worker_version: str
    kb_version: str
    models: list[ModelInfo] = Field(default_factory=list)
    prompts: list[PromptInfo] = Field(default_factory=list)
    stages: list[StageInfo] = Field(default_factory=list)
    skipped: list[SkippedCheck] = Field(default_factory=list)
    warnings: list[PipelineWarning] = Field(default_factory=list)
    metrics: dict[str, Union[int, float]] = Field(default_factory=dict)


class Summary(_Model):
    passed: bool
    blocking: int
    findings: int
    by_category: dict[str, int] = Field(default_factory=dict)
    by_severity: dict[str, int] = Field(default_factory=dict)
    needs_teacher_review: int = 0
    content_checked: bool = False


class Report(_Model):
    schema_: Literal[2] = Field(2, alias="schema")
    phase: Phase
    profile: ProfileInfo
    summary: Summary
    findings: list[Finding] = Field(default_factory=list)
    requirements: list[RequirementStatus] = Field(default_factory=list)
    document: DocumentInfo
    pipeline: Pipeline

    def to_json_dict(self) -> dict:
        return self.model_dump(mode="json", by_alias=True, exclude_none=True)
