"""Каталог языковых моделей и выбор модели на каждую проверку (решение 13).

Какие модели есть и куда они ходят, задаёт только тот, кто разворачивает систему: файл llm-models.yaml
и переменные окружения. Администратор в приложении выбирает модель из этого списка по id — бэкенд
передаёт id в задании (поле llm.model, queue-v2.md, раздел 3). Адрес из задания не принимается никогда:
даже тот, кто может писать в очередь, не направит текст работ на свой сервер.

Правила выбора:
  - id не указан — модель по умолчанию;
  - id не из каталога или модель недоступна — модель по умолчанию с предупреждением в отчёте;
  - к облачной модели обработчик сам не переходит никогда: только если её выбрал администратор.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Mapping, Optional

import yaml

from sapsr.llm.client import LlmClient, LlmConfig, StubResponder

TIERS = ("light", "main", "heavy", "cloud")
_ENV = re.compile(r"\$\{([A-Z0-9_]+)(?::-([^}]*))?\}")


@dataclass(frozen=True)
class ModelSpec:
    id: str
    title: str
    tier: str
    model: str
    provider: str = "ollama"
    base_url: Optional[str] = None
    api_key_env: Optional[str] = None
    timeout_s: Optional[float] = None
    num_ctx: Optional[int] = None
    headers: dict = field(default_factory=dict)
    extra_body: dict = field(default_factory=dict)
    note: str = ""


@dataclass(frozen=True)
class Catalog:
    default_id: str
    models: dict[str, ModelSpec]


def _expand(value, env: Mapping[str, str]):
    if isinstance(value, str):
        return _ENV.sub(lambda m: env.get(m.group(1)) or (m.group(2) or ""), value)
    if isinstance(value, list):
        return [_expand(v, env) for v in value]
    if isinstance(value, dict):
        return {k: _expand(v, env) for k, v in value.items()}
    return value


def _mapping(value, name: str) -> dict:
    """headers и extra_body: словарь в файле или JSON-строка из переменной окружения."""
    if value in (None, ""):
        return {}
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError as exc:
            raise ValueError(f"{name}: ожидается JSON-объект") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{name}: ожидается объект")
    return value


def load_catalog(path: Optional[Path], base: LlmConfig, env: Mapping[str, str] = os.environ) -> Catalog:
    """Каталог из файла. Файла нет — одна модель из прежних настроек (SAPSR_LLM_MODEL и соседних)."""
    if path is None or not Path(path).is_file():
        spec = ModelSpec(id="main", title="Основная", tier="main", model=base.model, provider=base.provider,
                         base_url=base.base_url)
        return Catalog(default_id="main", models={"main": spec})
    data = _expand(yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}, env)
    models: dict[str, ModelSpec] = {}
    for raw in data.get("models") or []:
        spec = ModelSpec(
            id=str(raw["id"]), title=str(raw.get("title") or raw["id"]), tier=str(raw.get("tier") or "main"),
            model=str(raw.get("model") or ""), provider=str(raw.get("provider") or "ollama"),
            base_url=raw.get("base_url") or None, api_key_env=raw.get("api_key_env") or None,
            timeout_s=float(raw["timeout_s"]) if raw.get("timeout_s") else None,
            num_ctx=int(raw["num_ctx"]) if raw.get("num_ctx") else None,
            headers=_mapping(raw.get("headers"), f"{raw['id']}.headers"),
            extra_body=_mapping(raw.get("extra_body"), f"{raw['id']}.extra_body"),
            note=str(raw.get("note") or ""),
        )
        if spec.tier not in TIERS:
            raise ValueError(f"модель {spec.id}: tier — одно из {TIERS}")
        if not re.fullmatch(r"[a-z0-9_-]{1,32}", spec.id):
            raise ValueError(f"модель {spec.id}: id — строчные латинские буквы, цифры, - и _")
        if spec.id in models:
            raise ValueError(f"модель {spec.id} указана дважды")
        models[spec.id] = spec
    if not models:
        raise ValueError(f"{path}: в каталоге нет моделей")
    default_id = str(env.get("SAPSR_LLM_DEFAULT") or data.get("default") or next(iter(models)))
    if default_id not in models:
        raise ValueError(f"модель по умолчанию {default_id!r} не найдена в каталоге")
    return Catalog(default_id=default_id, models=models)


@dataclass
class Choice:
    """Модель для одной проверки; warning — почему проверяет не та, что выбрана."""

    client: Optional[LlmClient]
    model_id: Optional[str]
    warning: Optional[str] = None


class LlmRegistry:
    """Клиенты моделей каталога; клиент создаётся при первом обращении и дальше переиспользуется."""

    def __init__(self, catalog: Catalog, base: LlmConfig, env: Mapping[str, str] = os.environ,
                 stub: Optional[StubResponder] = None):
        self.catalog = catalog
        self.base = base
        self._env = env
        self._stub = stub
        self._clients: dict[str, LlmClient] = {}

    @classmethod
    def single(cls, client: LlmClient) -> "LlmRegistry":
        """Одна готовая модель — так CheckService создают тесты и анализатор из командной строки."""
        spec = ModelSpec(id="main", title="Основная", tier="main", model=client.config.model,
                         provider=client.config.provider, base_url=client.config.base_url)
        registry = cls(Catalog(default_id="main", models={"main": spec}), client.config)
        registry._clients["main"] = client
        return registry

    @property
    def default_id(self) -> str:
        return self.catalog.default_id

    def spec(self, model_id: str) -> ModelSpec:
        return self.catalog.models[model_id]

    def client(self, model_id: str) -> LlmClient:
        if model_id not in self._clients:
            spec = self.spec(model_id)
            config = replace(
                self.base,
                provider=spec.provider,
                # Облачной без адреса нет: локальный адрес по умолчанию ей не подставляется.
                base_url=spec.base_url or ("" if spec.tier == "cloud" else self.base.base_url),
                model=spec.model,
                # Ключ — только из окружения: в файле каталога лишь имя переменной.
                api_key=self._env.get(spec.api_key_env) if spec.api_key_env else self.base.api_key,
                timeout_s=spec.timeout_s or self.base.timeout_s,
                num_ctx=spec.num_ctx or self.base.num_ctx,
                headers=dict(spec.headers),
                extra_body=dict(spec.extra_body),
            )
            self._clients[model_id] = LlmClient(config, stub=self._stub)
        return self._clients[model_id]

    def _mode_problem(self, model_id: str) -> Optional[str]:
        # Записанные ответы и заглушка — только для модели по умолчанию: у другой модели другие ответы.
        if self.base.mode in ("replay", "stub") and model_id != self.default_id:
            return f"Режим {self.base.mode}: доступна только модель по умолчанию."
        if self.base.mode == "off":
            return "Языковая модель выключена настройкой."
        return None

    def available(self, model_id: str) -> tuple[bool, Optional[str]]:
        problem = self._mode_problem(model_id)
        if problem:
            return False, problem
        spec = self.spec(model_id)
        if spec.tier == "cloud" and not (spec.base_url and spec.model):
            return False, "Облачная модель не настроена: адрес, имя модели и ключ задаются в .env."
        client = self.client(model_id)
        if client.available():
            return True, None
        if client.config.provider == "ollama" and not client.is_cloud:
            return False, f"Модель {client.config.model} не загружена в Ollama или Ollama не запущена."
        return False, client.unavailable_reason()

    def choose(self, requested: Optional[str]) -> Choice:
        default = self.default_id
        if not requested or requested == default:
            return Choice(self.client(default), default)
        if requested not in self.catalog.models:
            return Choice(self.client(default), default,
                          f"Модели «{requested}» нет в каталоге обработчика — проверено моделью по умолчанию.")
        ok, reason = self.available(requested)
        if ok:
            return Choice(self.client(requested), requested)
        title = self.spec(requested).title
        if self.client(default).is_cloud:
            # Взамен недоступной локальной текст в облако не уходит: содержание просто не проверяется.
            return Choice(self.client(requested), requested)
        return Choice(self.client(default), default,
                      f"Выбранная модель «{title}» недоступна ({reason.rstrip('.')}) — проверено моделью "
                      f"«{self.spec(default).title}».")

    def status(self) -> list[dict]:
        """Модели для экрана администратора: без адресов и ключей — только то, что нужно для выбора."""
        rows = []
        for model_id, spec in self.catalog.models.items():
            ok, reason = self.available(model_id)
            rows.append({
                "id": model_id, "title": spec.title, "tier": spec.tier, "model": spec.model or None,
                "cloud": self.client(model_id).is_cloud or spec.tier == "cloud",
                "available": ok, "reason": reason, "note": spec.note or None,
            })
        return rows
