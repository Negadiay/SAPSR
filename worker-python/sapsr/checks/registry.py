"""Реестр проверок оформления (план, З3, шаг 1).

Проверка — функция (контекст, параметры) → список срабатываний, зарегистрированная
под именем, на которое ссылается поле `check` правила в kb/rules/*.yaml. Числа
(допуски, пороги, нормы) приходят в параметрах из базы знаний; у каждой проверки
своя pydantic-схема параметров, её проходит каталог при загрузке.

Сообщение замечания строится не здесь, а по шаблонам правила: проверка отдаёт
значения (`provides`), правило решает, как о них сказать.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Optional

from pydantic import BaseModel, ConfigDict

from sapsr.report.model import Evidence


class Params(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class NoParams(Params):
    pass


@dataclass
class Hit:
    """Одно срабатывание правила."""

    values: dict = field(default_factory=dict)
    evidence: list[Evidence] = field(default_factory=list)
    pages: list[int] = field(default_factory=list)
    context: str = ""
    location: str = ""


class SkipCheck(Exception):
    """Проверку выполнить нельзя (нет справочника, нет данных) — попадёт в pipeline.skipped."""

    def __init__(self, reason: str, message: str):
        super().__init__(f"{reason}: {message}")
        self.reason = reason
        self.message = message


@dataclass(frozen=True)
class CheckSpec:
    name: str
    func: Callable
    params: type[Params]
    provides: frozenset[str]


REGISTRY: dict[str, CheckSpec] = {}

# Значения, которые движок добавляет к каждому срабатыванию сам.
COMMON_VALUES = frozenset({"pages"})


def check(name: str, params: type[Params] = NoParams, provides: tuple[str, ...] = ()):
    def decorator(func: Callable) -> Callable:
        if name in REGISTRY:
            raise RuntimeError(f"проверка {name} зарегистрирована дважды")
        REGISTRY[name] = CheckSpec(name=name, func=func, params=params, provides=frozenset(provides))
        return func
    return decorator


def get(name: str) -> Optional[CheckSpec]:
    return REGISTRY.get(name)
