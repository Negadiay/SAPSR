"""Защита персональных данных при обращении к облачной модели (решение 13).

Облачному сервису уходят только фрагменты разделов записки (титульный лист в модель не идёт вовсе),
и перед отправкой из них убирается всё, что указывает на человека:

  - ФИО студента из задания — в любом падеже, и любые «Фамилия И. О.» / «И. О. Фамилия»;
  - адреса почты и номера телефонов;
  - номер группы студента и номера групп вида «гр. 321702».

Каждое значение заменяется меткой («[ФИО-1]», «[ПОЧТА-1]»): одно и то же значение — одной меткой, чтобы
модель видела, что речь об одном человеке. В ответе модели метки возвращаются на место до проверки
цитат — цитата сверяется с настоящим текстом работы. Соответствие меток и значений живёт только в памяти
на время одной проверки и никуда не пишется.
"""

from __future__ import annotations

import json
import re
from collections import Counter
from typing import Any, Iterable, Optional

from sapsr.llm.client import LlmResult, _strip_fences

_NAME = r"[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?"
_INITIALS = r"[А-ЯЁ]\.\s?[А-ЯЁ]\."

# Порядок важен: сначала почта (в ней бывают цифры, похожие на телефон), потом телефоны, ФИО, группы.
_PATTERNS: tuple[tuple[str, re.Pattern], ...] = (
    ("ПОЧТА", re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")),
    ("ТЕЛЕФОН", re.compile(r"(?<![\w+])\+\d{1,3}[\s(-]*\d{2,3}[\s)-]*\d{3}[\s-]*\d{2}[\s-]*\d{2}(?!\w)")),
    ("ТЕЛЕФОН", re.compile(r"(?<!\w)8\s?\(?0\d{2}\)?[\s-]*\d{3}[\s-]*\d{2}[\s-]*\d{2}(?!\w)")),
    ("ФИО", re.compile(rf"(?<![\w.]){_NAME}\s+{_INITIALS}")),
    ("ФИО", re.compile(rf"(?<![\w.]){_INITIALS}\s?{_NAME}")),
    ("ГРУППА", re.compile(r"(?<=гр\. )\d{6}\b|(?<=групп[аыеу] )\d{6}\b|(?<=группой )\d{6}\b", re.IGNORECASE)),
)

_PLACEHOLDER = re.compile(r"\[(?:ФИО|ПОЧТА|ТЕЛЕФОН|ГРУППА)-\d+\]")


def _name_pattern(name: str) -> Optional[re.Pattern]:
    """Фамилия или имя в любом падеже: Терешкова, Терешковой, Терешкову. Короткие — только как есть."""
    name = (name or "").strip()
    if len(name) < 2 or not re.fullmatch(r"[А-ЯЁа-яё-]+", name):
        return None
    word = name[0].upper() + name[1:].lower()
    stem = word[:-1] if len(word) > 4 and word[-1] in "аеёийоуыэюяь" else word
    return re.compile(rf"(?<![\w-]){re.escape(stem)}[а-яё]{{0,3}}(?![\w-])")


class Redactor:
    """Заменяет персональные данные метками и возвращает их обратно. Один экземпляр — на одну проверку."""

    def __init__(self, names: Iterable[str] = (), groups: Iterable[str] = ()):
        self._own: list[tuple[str, re.Pattern]] = []
        for name in names:
            pattern = _name_pattern(name)
            if pattern is not None:
                self._own.append(("ФИО", pattern))
        for group in groups:
            if group and re.fullmatch(r"\d{6}", group):
                self._own.append(("ГРУППА", re.compile(rf"(?<!\d){group}(?!\d)")))
        self._to_label: dict[str, str] = {}
        self._to_value: dict[str, str] = {}
        self.kinds: Counter = Counter()

    @classmethod
    def for_task(cls, task) -> "Redactor":
        student = task.student
        return cls(names=[student.last_name or "", student.first_name or ""], groups=[student.group or ""])

    @property
    def replaced(self) -> int:
        """Сколько разных значений заменено за проверку."""
        return len(self._to_label)

    def _label(self, kind: str, value: str) -> str:
        label = self._to_label.get(value)
        if label is None:
            self.kinds[kind] += 1
            label = f"[{kind}-{self.kinds[kind]}]"
            self._to_label[value] = label
            self._to_value[label] = value
        return label

    def redact(self, text: str) -> str:
        # Сначала ФИО «Фамилия И. О.» целиком, потом отдельные слова из задания — иначе от «Терешкова А. В.»
        # осталась бы метка с инициалами.
        for kind, pattern in _PATTERNS[:3]:
            text = pattern.sub(lambda m, k=kind: self._label(k, m.group(0)), text)
        for kind, pattern in _PATTERNS[3:5]:
            text = pattern.sub(lambda m, k=kind: self._label(k, m.group(0)), text)
        for kind, pattern in self._own:
            text = pattern.sub(lambda m, k=kind: self._label(k, m.group(0)), text)
        kind, pattern = _PATTERNS[5]
        return pattern.sub(lambda m: self._label(kind, m.group(0)), text)

    def restore(self, text: str) -> str:
        return _PLACEHOLDER.sub(lambda m: self._to_value.get(m.group(0), m.group(0)), text)

    def restore_json(self, value: Any) -> Any:
        if isinstance(value, str):
            return self.restore(value)
        if isinstance(value, list):
            return [self.restore_json(v) for v in value]
        if isinstance(value, dict):
            return {k: self.restore_json(v) for k, v in value.items()}
        return value


class PrivateLlm:
    """Клиент облачной модели на одну проверку: текст уходит без персональных данных, ответ — с ними.

    Остальное (доступность, имя модели, счётчики) — как у обёрнутого клиента.
    """

    def __init__(self, client, redactor: Redactor):
        self._client = client
        self.redactor = redactor

    def __getattr__(self, name):
        return getattr(self._client, name)

    def complete_json(self, prompt_ref: str, system: str, user: str, schema, timeout_s=None) -> LlmResult:
        result = self._client.complete_json(prompt_ref, system, self.redactor.redact(user), schema,
                                            timeout_s=timeout_s)
        try:
            restored = self.redactor.restore_json(json.loads(_strip_fences(result.raw)))
            data = schema.model_validate(restored)
        except (ValueError, TypeError):
            # Сырой ответ не разобрался (схему он прошёл после очистки) — возвращаем через разобранные данные.
            dumped = result.data.model_dump(mode="json", by_alias=True)
            data = schema.model_validate(self.redactor.restore_json(dumped))
        return LlmResult(data=data, raw=self.redactor.restore(result.raw), cached=result.cached, ms=result.ms,
                         attempts=result.attempts)
