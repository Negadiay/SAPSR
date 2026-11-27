"""Клиент языковой модели (план, З1б; решения 1 и 2).

Модель — параметр: один класс LlmClient с base_url, model, temperature=0, seed. Локальная
модель (Ollama), сервер с OpenAI-совместимым API и заглушка для тестов взаимозаменяемы —
это и есть независимая замена компонента из методички.

Режимы (план Жерко, Ж2):
  live    — настоящая модель; ответы кэшируются по ключу «текст запроса + версия подсказки + модель»;
  record  — live + запись ответов в каталог replay;
  replay  — только записанные ответы; промах — ошибка LlmUnavailable (сквозные тесты, CI);
  stub    — заготовленные ответы из функции или JSON-файла (модульные тесты);
  off     — модель выключена: содержательные этапы пропускаются с записью в pipeline.skipped.

Облачная модель (решения 2 и 13) включается только явно: SAPSR_LLM_ALLOW_CLOUD=1. Без этого,
а также без HTTPS или без ключа доступа обращение к нелокальному адресу отклоняется ещё до отправки
текста работы. Перенаправления облачный запрос не выполняет: текст не уйдёт по чужому адресу.
Персональные данные из текста убирает sapsr/llm/privacy.py до вызова клиента.

Бесплатный тариф облака (решение 2: Groq) ограничивает запросы и токены в минуту. Ответ 429 — не сбой:
клиент ждёт, сколько просит сервис, если до конца срока проверки хватает времени.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional, TypeVar
from urllib.parse import urlparse

import requests
from pydantic import BaseModel, ValidationError

log = logging.getLogger("sapsr.llm")

T = TypeVar("T", bound=BaseModel)

MODES = ("live", "record", "replay", "stub", "off")
PROVIDERS = ("ollama", "openai")


class LlmError(Exception):
    """Ошибка вызова модели."""


class LlmUnavailable(LlmError):
    """Модель недоступна: выключена, нет соединения, нет записанного ответа."""


class LlmSchemaError(LlmError):
    """Ответ дважды не прошёл JSON-схему."""


class LlmTimeout(LlmError):
    pass


@dataclass
class LlmConfig:
    mode: str = "live"
    provider: str = "ollama"
    base_url: str = "http://localhost:11434"
    model: str = "qwen3.5:4b"
    temperature: float = 0.0
    seed: int = 42
    timeout_s: float = 120.0
    num_ctx: int = 8192
    allow_cloud: bool = False
    api_key: Optional[str] = None
    cache_dir: Optional[Path] = None
    replay_dir: Optional[Path] = None
    stub_file: Optional[Path] = None
    ping_ttl_s: float = 30.0
    # Заголовки и поля запроса, которых требует облачный сервис (каталог моделей, llm-models.yaml):
    # например, каталог Yandex Cloud или запрет хранить запросы у посредника.
    headers: dict = field(default_factory=dict)
    extra_body: dict = field(default_factory=dict)

    def __post_init__(self):
        if self.mode not in MODES:
            raise ValueError(f"режим модели {self.mode!r}: допустимо {MODES}")
        if self.provider not in PROVIDERS:
            raise ValueError(f"поставщик модели {self.provider!r}: допустимо {PROVIDERS}")


@dataclass
class LlmResult:
    data: BaseModel
    raw: str
    cached: bool
    ms: int
    attempts: int


@dataclass
class LlmStats:
    calls: int = 0
    cache_hits: int = 0
    schema_retries: int = 0
    schema_failures: int = 0
    errors: int = 0
    ms: int = 0
    # Сколько раз облачный сервис попросил подождать (лимит бесплатного тарифа).
    rate_waits: int = 0
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def add(self, **values):
        with self._lock:
            for key, value in values.items():
                setattr(self, key, getattr(self, key) + value)

    def snapshot(self) -> dict:
        return self.as_dict()

    def since(self, before: dict) -> dict:
        """Счётчики одной проверки: разница с моментом её начала (клиент общий для всех заданий)."""
        return {key: value - before.get(key, 0) for key, value in self.as_dict().items()}

    def as_dict(self) -> dict:
        return {"llm_calls": self.calls, "llm_cache_hits": self.cache_hits,
                "llm_schema_retries": self.schema_retries, "llm_schema_failures": self.schema_failures,
                "llm_errors": self.errors, "llm_ms": self.ms, "llm_rate_waits": self.rate_waits}


def is_local_url(url: str) -> bool:
    """Адрес на этом компьютере или в локальной сети/контейнере — текст работы не покидает машину."""
    host = (urlparse(url).hostname or "").lower()
    if host in ("localhost", "host.docker.internal") or "." not in host:
        return True
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return host.endswith((".local", ".internal", ".lan"))
    return ip.is_loopback or ip.is_private or ip.is_link_local


def request_key(model: str, prompt_ref: str, system: str, user: str, schema: dict) -> str:
    payload = json.dumps({"model": model, "prompt": prompt_ref, "system": system, "user": user,
                          "schema": schema}, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


class _FileStore:
    """Ответы модели по ключу: <dir>/<ab>/<ключ>.json. Сам текст работы не хранится — только ответ."""

    def __init__(self, root: Path):
        self.root = Path(root)

    def _path(self, key: str) -> Path:
        return self.root / key[:2] / f"{key}.json"

    def get(self, key: str) -> Optional[str]:
        path = self._path(key)
        if not path.is_file():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))["response"]
        except (OSError, ValueError, KeyError):
            return None

    def put(self, key: str, response: str, meta: dict) -> None:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps({**meta, "key": key, "response": response}, ensure_ascii=False, indent=1),
                       encoding="utf-8")
        tmp.replace(path)


StubResponder = Callable[[str, str, str, type], object]


class LlmClient:
    def __init__(self, config: LlmConfig, stub: Optional[StubResponder] = None,
                 session: Optional[requests.Session] = None):
        self.config = config
        self.stats = LlmStats()
        self._session = session or requests.Session()
        self._stub = stub
        self._stub_data: Optional[dict] = None
        self._cache = _FileStore(config.cache_dir) if config.cache_dir and config.mode in ("live", "record") else None
        self._replay = _FileStore(config.replay_dir) if config.replay_dir else None
        self._ping_at = 0.0
        self._ping_ok = False
        self._model_info: Optional[dict] = None
        if config.mode == "replay" and self._replay is None:
            raise ValueError("режим replay требует каталога записанных ответов (SAPSR_LLM_REPLAY_DIR)")
        if config.mode == "stub" and stub is None and config.stub_file is None:
            raise ValueError("режим stub требует функции-заглушки или SAPSR_LLM_STUB_FILE")

    # ── сведения о модели ───────────────────────────────────────────────────

    @property
    def is_cloud(self) -> bool:
        return self.config.mode in ("live", "record") and not is_local_url(self.config.base_url)

    def cloud_problem(self) -> Optional[str]:
        """Почему текст работы нельзя отправить облачной модели; None — можно."""
        if not self.config.allow_cloud:
            return "Адрес модели не локальный, а облачная модель не разрешена (SAPSR_LLM_ALLOW_CLOUD)."
        if urlparse(self.config.base_url).scheme != "https":
            return "Облачная модель — только по HTTPS: иначе текст работы и ключ уйдут открытым текстом."
        if not self.config.api_key:
            return "Для облачной модели не задан ключ доступа."
        if not self.config.model:
            return "Облачная модель не настроена: не указано имя модели."
        return None

    @property
    def model_name(self) -> str:
        if self.config.mode == "stub":
            return f"stub:{self.config.model}"
        return self.config.model

    def model_details(self) -> dict:
        """Квантование и семейство модели — для раздела pipeline отчёта."""
        if self._model_info is not None:
            return self._model_info
        info: dict = {}
        if self.config.provider == "ollama" and self.config.mode in ("live", "record") and self.available():
            try:
                resp = self._session.post(f"{self._base()}/api/show", json={"model": self.config.model},
                                          timeout=5)
                resp.raise_for_status()
                details = resp.json().get("details", {})
                info = {"quantization": details.get("quantization_level"),
                        "parameter_size": details.get("parameter_size"),
                        "family": details.get("family")}
            except Exception:
                info = {}
        self._model_info = info
        return info

    def _base(self) -> str:
        return self.config.base_url.rstrip("/")

    # ── доступность ─────────────────────────────────────────────────────────

    def available(self) -> bool:
        mode = self.config.mode
        if mode == "off":
            return False
        if mode in ("stub", "replay"):
            return True
        if self.is_cloud and self.cloud_problem():
            return False
        now = time.monotonic()
        if now - self._ping_at < self.config.ping_ttl_s:
            return self._ping_ok
        self._ping_at = now
        try:
            if self.config.provider == "ollama":
                resp = self._session.get(f"{self._base()}/api/tags", timeout=3)
                resp.raise_for_status()
                names = {m.get("name") for m in resp.json().get("models", [])}
                self._ping_ok = self.config.model in names or f"{self.config.model}:latest" in names
                if not self._ping_ok:
                    log.warning("модель не загружена в Ollama", extra={"fields": {"model": self.config.model}})
            else:
                resp = self._session.get(f"{self._openai_base()}/models", headers=self._headers(), timeout=5,
                                         allow_redirects=False)
                # 401/403 — ключ не подошёл: модель недоступна, а не «вероятно, работает».
                self._ping_ok = resp.status_code < 400 or resp.status_code == 404
        except Exception as exc:
            log.warning("языковая модель недоступна", extra={"fields": {"error": type(exc).__name__}})
            self._ping_ok = False
        return self._ping_ok

    def unavailable_reason(self) -> str:
        if self.config.mode == "off":
            return "Языковая модель выключена настройкой."
        if self.is_cloud and self.cloud_problem():
            return self.cloud_problem()
        return "Языковая модель недоступна."

    # ── вызов ───────────────────────────────────────────────────────────────

    def complete_json(self, prompt_ref: str, system: str, user: str, schema: type[T],
                      timeout_s: Optional[float] = None) -> LlmResult:
        """Ответ модели строго по JSON-схеме. Не прошёл схему — один повтор с текстом ошибки."""
        if self.config.mode == "off":
            raise LlmUnavailable(self.unavailable_reason())
        if self.is_cloud and self.cloud_problem():
            raise LlmUnavailable(self.cloud_problem())
        json_schema = schema.model_json_schema()
        key = request_key(self.model_name, prompt_ref, system, user, json_schema)
        started = time.perf_counter()
        attempts = 0
        messages = [{"role": "system", "content": system}, {"role": "user", "content": user}]
        last_error = ""
        for attempt in range(2):
            attempts += 1
            attempt_key = key if attempt == 0 else request_key(self.model_name, prompt_ref, system,
                                                                user + "\n#retry", json_schema)
            raw, cached = self._raw_response(attempt_key, prompt_ref, messages, json_schema, schema, timeout_s)
            try:
                data = schema.model_validate_json(_strip_fences(raw))
            except ValidationError as exc:
                last_error = _short_error(exc)
                self.stats.add(schema_retries=1 if attempt == 0 else 0)
                messages = messages + [
                    {"role": "assistant", "content": raw[:4000]},
                    {"role": "user", "content": "Ответ не прошёл проверку JSON-схемы: " + last_error
                                                + ". Верни только JSON строго по схеме, без пояснений."},
                ]
                continue
            ms = int((time.perf_counter() - started) * 1000)
            self.stats.add(calls=1, cache_hits=1 if cached else 0, ms=ms)
            return LlmResult(data=data, raw=raw, cached=cached, ms=ms, attempts=attempts)
        self.stats.add(calls=1, schema_failures=1, ms=int((time.perf_counter() - started) * 1000))
        raise LlmSchemaError(last_error)

    def _raw_response(self, key: str, prompt_ref: str, messages: list[dict], json_schema: dict,
                      schema: type, timeout_s: Optional[float]) -> tuple[str, bool]:
        mode = self.config.mode
        if mode == "stub":
            return self._stub_response(key, prompt_ref, messages, schema), False
        if mode == "replay":
            stored = self._replay.get(key)
            if stored is None:
                raise LlmUnavailable(f"нет записанного ответа для {prompt_ref} (ключ {key[:12]})")
            return stored, True
        if self._cache is not None:
            stored = self._cache.get(key)
            if stored is not None:
                if mode == "record" and self._replay is not None:
                    self._replay.put(key, stored, self._meta(prompt_ref))
                return stored, True
        raw = self._call(messages, json_schema, timeout_s)
        if self._cache is not None:
            self._cache.put(key, raw, self._meta(prompt_ref))
        if mode == "record" and self._replay is not None:
            self._replay.put(key, raw, self._meta(prompt_ref))
        return raw, False

    def _meta(self, prompt_ref: str) -> dict:
        return {"model": self.model_name, "prompt": prompt_ref}

    def _stub_response(self, key: str, prompt_ref: str, messages: list[dict], schema: type) -> str:
        user = "\n".join(m["content"] for m in messages if m["role"] == "user")
        if self._stub is not None:
            answer = self._stub(prompt_ref, messages[0]["content"], user, schema)
        else:
            if self._stub_data is None:
                self._stub_data = json.loads(Path(self.config.stub_file).read_text(encoding="utf-8"))
            answer = self._stub_data.get(key) or self._stub_data.get(f"default:{prompt_ref.split('.')[0]}")
            if answer is None:
                raise LlmUnavailable(f"в заглушке нет ответа для {prompt_ref}")
        return answer if isinstance(answer, str) else json.dumps(answer, ensure_ascii=False)

    def _call(self, messages: list[dict], json_schema: dict, timeout_s: Optional[float]) -> str:
        timeout = min(timeout_s or self.config.timeout_s, self.config.timeout_s)
        if timeout <= 0:
            raise LlmTimeout("не осталось времени на вызов модели")
        if self.is_cloud:
            # Объём — да, содержание — никогда: текст работы в журнал не пишется.
            log.info("запрос к облачной модели", extra={"fields": {
                "model": self.config.model, "chars": sum(len(m["content"]) for m in messages)}})
        try:
            if self.config.provider == "ollama":
                return self._call_ollama(messages, json_schema, timeout)
            return self._call_openai(messages, json_schema, timeout)
        except requests.Timeout as exc:
            self.stats.add(errors=1)
            raise LlmTimeout(f"модель не ответила за {timeout:.0f} с") from exc
        except requests.RequestException as exc:
            self.stats.add(errors=1)
            self._ping_ok, self._ping_at = False, time.monotonic()
            raise LlmUnavailable(f"ошибка соединения с моделью: {type(exc).__name__}") from exc

    def _call_ollama(self, messages: list[dict], json_schema: dict, timeout: float) -> str:
        body = {
            "model": self.config.model,
            "messages": messages,
            "stream": False,
            "format": json_schema,
            "think": False,
            "keep_alive": "10m",
            "options": {"temperature": self.config.temperature, "seed": self.config.seed,
                        "num_ctx": self.config.num_ctx},
        }
        resp = self._session.post(f"{self._base()}/api/chat", json=body, timeout=(5, timeout))
        resp.raise_for_status()
        return resp.json()["message"]["content"]

    def _openai_base(self) -> str:
        base = self._base()
        return base if base.endswith("/v1") else f"{base}/v1"

    def _headers(self) -> dict:
        headers = {str(k): str(v) for k, v in self.config.headers.items()}
        if self.config.api_key:
            headers["Authorization"] = f"Bearer {self.config.api_key}"
        return headers

    def _call_openai(self, messages: list[dict], json_schema: dict, timeout: float) -> str:
        body = {
            "model": self.config.model,
            "messages": messages,
            "temperature": self.config.temperature,
            "seed": self.config.seed,
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "answer", "schema": json_schema, "strict": False}},
        }
        # Поля сервиса не перекрывают модель, сообщения и схему ответа.
        body.update({k: v for k, v in self.config.extra_body.items() if k not in body})
        give_up = time.monotonic() + timeout
        while True:
            left = give_up - time.monotonic()
            if left <= 0:
                raise LlmTimeout(f"модель не ответила за {timeout:.0f} с")
            resp = self._session.post(f"{self._openai_base()}/chat/completions", json=body,
                                      headers=self._headers(), timeout=(5, left), allow_redirects=False)
            if resp.is_redirect:
                raise LlmUnavailable("облачный сервис ответил перенаправлением — запрос не повторяется по чужому адресу")
            if resp.status_code in RETRY_STATUSES:
                # Бесплатный тариф ограничивает запросы и токены в минуту: ждём, сколько просит сервис, —
                # проверка идёт дольше, но не теряет разделы. Не дождаться до конца срока — раздел пропускается.
                wait = retry_after(resp)
                if wait > give_up - time.monotonic():
                    self.stats.add(errors=1)
                    raise LlmUnavailable(f"облачный сервис ограничил число запросов (HTTP {resp.status_code}), "
                                         f"ждать {wait:.0f} с — дольше, чем осталось времени")
                self.stats.add(rate_waits=1)
                log.info("облачный сервис просит подождать", extra={"fields": {
                    "status": resp.status_code, "wait_s": round(wait, 1)}})
                self._sleep(wait)
                continue
            failed = failed_generation(resp)
            if failed is not None:
                # Сервис сам сверил ответ со схемой и отклонил его (у Groq — json_validate_failed): отдаём ответ
                # нашей проверке схемы — она попросит модель исправиться, как и после любого ответа не по схеме.
                return failed
            if resp.status_code >= 400:
                self.stats.add(errors=1)
                if resp.status_code in (401, 403):
                    self._ping_ok, self._ping_at = False, time.monotonic()
                # Тело ошибки в журнал не пишется: в нём может оказаться текст запроса.
                raise LlmUnavailable(f"облачный сервис отклонил запрос: HTTP {resp.status_code}"
                                     + (f" ({error_code(resp)})" if error_code(resp) else ""))
            return resp.json()["choices"][0]["message"]["content"]

    @staticmethod
    def _sleep(seconds: float) -> None:
        time.sleep(seconds)


# 429 — превышен лимит тарифа, 498 — у Groq нет свободных мощностей, 503 — сервис перегружен.
RETRY_STATUSES = (429, 498, 503)
_DEFAULT_WAIT_S = 5.0
_DURATION = re.compile(r"(?:(\d+(?:\.\d+)?)h)?(?:(\d+(?:\.\d+)?)m(?!s))?(?:(\d+(?:\.\d+)?)s)?(?:(\d+(?:\.\d+)?)ms)?$")


def _seconds(text: Optional[str]) -> Optional[float]:
    """«2», «7.66s», «2m59.56s», «850ms» → секунды; не разобрали — None."""
    text = (text or "").strip()
    if not text:
        return None
    try:
        return max(0.0, float(text))
    except ValueError:
        pass
    m = _DURATION.match(text)
    if not m or not any(m.groups()):
        return None
    h, mins, s, ms = (float(g) if g else 0.0 for g in m.groups())
    return h * 3600 + mins * 60 + s + ms / 1000


def retry_after(resp) -> float:
    """Сколько ждать перед повтором: Retry-After, затем сброс лимита токенов или запросов (заголовки Groq и OpenAI)."""
    headers = getattr(resp, "headers", None) or {}
    for name in ("retry-after", "x-ratelimit-reset-tokens", "x-ratelimit-reset-requests"):
        wait = _seconds(headers.get(name))
        if wait is not None:
            return wait
    return _DEFAULT_WAIT_S


def _error(resp) -> dict:
    try:
        body = resp.json()
    except ValueError:  # тело — не JSON
        return {}
    error = body.get("error") if isinstance(body, dict) else None
    return error if isinstance(error, dict) else {}


def error_code(resp) -> Optional[str]:
    code = _error(resp).get("code") or _error(resp).get("type")
    return str(code) if code else None


def failed_generation(resp) -> Optional[str]:
    """Ответ модели, который сервис отклонил как не соответствующий схеме; None — это другая ошибка."""
    if resp.status_code != 400:
        return None
    error = _error(resp)
    if error.get("code") != "json_validate_failed":
        return None
    return str(error.get("failed_generation") or "")


def _strip_fences(raw: str) -> str:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    return text.strip()


def _short_error(exc: ValidationError) -> str:
    parts = []
    for err in exc.errors()[:3]:
        loc = ".".join(str(p) for p in err.get("loc", ()))
        parts.append(f"{loc}: {err.get('msg')}" if loc else str(err.get("msg")))
    return "; ".join(parts)
