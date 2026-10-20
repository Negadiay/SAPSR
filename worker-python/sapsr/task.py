"""Задание на проверку из pdf_tasks_queue (docs/contracts/queue-v2.md, раздел 3).

Понимает и задание v2, и прежнее v1 ({task_id, file_path}). Незнакомые поля пропускаются.
"""

from __future__ import annotations

import json
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator


class InvalidTask(Exception):
    """Сообщение нельзя разобрать — отклоняется без возврата в очередь."""


class _Lenient(BaseModel):
    model_config = ConfigDict(extra="ignore")


class FileRef(_Lenient):
    storage_key: Optional[str] = None
    sha256: Optional[str] = None
    source_format: str = "pdf"


class CheckProfile(_Lenient):
    work_type: Optional[str] = None
    semester: Optional[int] = None
    stage: Optional[int] = None

    @property
    def empty(self) -> bool:
        return self.work_type is None and self.semester is None and self.stage is None


class StudentInfo(_Lenient):
    # Персональные данные: в логи не пишутся.
    last_name: Optional[str] = None
    first_name: Optional[str] = None
    group: Optional[str] = None


class Task(_Lenient):
    schema_: int = Field(1, alias="schema")
    task_id: int
    type: str = "CHECK"
    file: FileRef = FileRef()
    profile: CheckProfile = CheckProfile()
    student: StudentInfo = StudentInfo()
    previous: Optional[dict] = None
    trace_id: Optional[str] = None
    # Переходное поле: абсолютный путь. Используется, только если нет storage_key.
    file_path: Optional[str] = None

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    @field_validator("file", "profile", "student", mode="before")
    @classmethod
    def _null_is_empty(cls, value):
        # Пустые объекты профиля и студента в v2 приходят как null.
        return {} if value is None else value


def parse_task(body: bytes) -> Task:
    try:
        data = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InvalidTask(f"не JSON: {type(exc).__name__}") from exc
    if not isinstance(data, dict):
        raise InvalidTask("ожидается объект JSON")
    schema = data.get("schema", 1)
    if schema not in (1, 2):
        raise InvalidTask(f"незнакомая версия протокола schema={schema!r}")
    if "task_id" not in data:
        raise InvalidTask("нет task_id")
    try:
        task = Task.model_validate(data)
    except ValidationError as exc:
        raise InvalidTask(f"поля задания: {exc.error_count()} ошибок") from exc
    if task.type != "CHECK":
        raise InvalidTask(f"незнакомый вид задания {task.type!r}")
    if not task.file.storage_key and not task.file_path:
        raise InvalidTask("нет ни file.storage_key, ни file_path")
    return task
