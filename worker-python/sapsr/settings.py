"""Настройки обработчика из переменных окружения. Значения по умолчанию совпадают с
прежним поведением: без новых переменных обработчик работает как раньше (протокол v1)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping, Optional

from sapsr.kb.loader import DEFAULT_KB_DIR
from sapsr.llm.client import LlmConfig

WORKER_ROOT = Path(__file__).resolve().parents[1]


def _bool(value: Optional[str], default: bool) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on", "да")


def _path(value: Optional[str]) -> Optional[Path]:
    return Path(value) if value else None


def _default_storage_root() -> Path:
    # В Docker общий том смонтирован в /app/storage; локально бэкенд хранит файлы в ../storage
    # относительно своего каталога — то есть в <репозиторий>/storage.
    docker = Path("/app/storage")
    return docker if docker.is_dir() else WORKER_ROOT.parent / "storage"


@dataclass(frozen=True)
class Settings:
    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    # Учётные данные очереди — только из окружения (в Docker — из .env), в коде паролей нет.
    rabbitmq_user: str = ""
    rabbitmq_password: str = ""
    tasks_queue: str = "pdf_tasks_queue"
    results_queue: str = "pdf_results_queue"
    progress_queue: str = "pdf_progress_queue"
    # 1 — результат одним сообщением {task_id, status, errors}, как сейчас ждёт бэкенд;
    # 2 — прогресс и две фазы результата (queue-v2.md). Включать после задачи 5.2 у Котко.
    queue_protocol: int = 1
    storage_root: Path = field(default_factory=_default_storage_root)
    kb_dir: Path = DEFAULT_KB_DIR
    # symbolic — только правила; hybrid — правила + модель (целевой); neural — только модель (для сравнения).
    mode: str = "hybrid"
    max_pages: int = 300
    task_timeout_s: float = 540.0
    iis_mode: str = "http"
    log_format: str = "json"
    log_level: str = "INFO"
    llm: LlmConfig = field(default_factory=LlmConfig)

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        get = env.get
        llm = LlmConfig(
            mode=get("SAPSR_LLM_MODE", "live"),
            provider=get("SAPSR_LLM_PROVIDER", "ollama"),
            base_url=get("SAPSR_LLM_BASE_URL", "http://localhost:11434"),
            model=get("SAPSR_LLM_MODEL", "qwen3.5:4b"),
            temperature=float(get("SAPSR_LLM_TEMPERATURE", "0")),
            seed=int(get("SAPSR_LLM_SEED", "42")),
            timeout_s=float(get("SAPSR_LLM_TIMEOUT_S", "120")),
            num_ctx=int(get("SAPSR_LLM_NUM_CTX", "8192")),
            allow_cloud=_bool(get("SAPSR_LLM_ALLOW_CLOUD"), False),
            api_key=get("SAPSR_LLM_API_KEY") or None,
            cache_dir=_path(get("SAPSR_LLM_CACHE_DIR", str(WORKER_ROOT / ".cache" / "llm"))),
            replay_dir=_path(get("SAPSR_LLM_REPLAY_DIR")),
            stub_file=_path(get("SAPSR_LLM_STUB_FILE")),
        )
        settings = cls(
            rabbitmq_host=get("RABBITMQ_HOST", "localhost"),
            rabbitmq_port=int(get("RABBITMQ_PORT", "5672")),
            rabbitmq_user=get("RABBITMQ_USER", ""),
            rabbitmq_password=get("RABBITMQ_PASSWORD", get("RABBITMQ_PASS", "")),
            tasks_queue=get("SAPSR_TASKS_QUEUE", "pdf_tasks_queue"),
            results_queue=get("SAPSR_RESULTS_QUEUE", "pdf_results_queue"),
            progress_queue=get("SAPSR_PROGRESS_QUEUE", "pdf_progress_queue"),
            queue_protocol=int(get("SAPSR_QUEUE_PROTOCOL", "1")),
            storage_root=_path(get("SAPSR_STORAGE_ROOT")) or _default_storage_root(),
            kb_dir=_path(get("SAPSR_KB_DIR")) or DEFAULT_KB_DIR,
            mode=get("SAPSR_MODE", "hybrid"),
            max_pages=int(get("SAPSR_MAX_PAGES", "300")),
            task_timeout_s=float(get("SAPSR_TASK_TIMEOUT_S", "540")),
            iis_mode=get("SAPSR_IIS_MODE", "http"),
            log_format=get("SAPSR_LOG_FORMAT", "json"),
            log_level=get("SAPSR_LOG_LEVEL", "INFO"),
            llm=llm,
        )
        settings.validate()
        return settings

    def validate(self) -> None:
        if self.queue_protocol not in (1, 2):
            raise ValueError("SAPSR_QUEUE_PROTOCOL: 1 или 2")
        if self.mode not in ("symbolic", "hybrid", "neural"):
            raise ValueError("SAPSR_MODE: symbolic, hybrid или neural")
        if self.iis_mode not in ("http", "off"):
            raise ValueError("SAPSR_IIS_MODE: http или off")
