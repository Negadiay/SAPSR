"""Логи обработчика: JSON-строки с trace_id и task_id в каждой записи (план Жерко, Ж4).

В лог не пишутся: текст работы, ФИО студента, ответы модели, абсолютные пути хранилища.
Дополнительные поля передаются так: log.info("…", extra={"fields": {...}}).
"""

from __future__ import annotations

import contextlib
import contextvars
import json
import logging
import sys
import time

_trace_id: contextvars.ContextVar = contextvars.ContextVar("trace_id", default=None)
_task_id: contextvars.ContextVar = contextvars.ContextVar("task_id", default=None)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        trace_id, task_id = _trace_id.get(), _task_id.get()
        if trace_id:
            payload["trace_id"] = trace_id
        if task_id is not None:
            payload["task_id"] = task_id
        fields = getattr(record, "fields", None)
        if isinstance(fields, dict):
            payload.update(fields)
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


class TextFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        prefix = time.strftime("%H:%M:%S", time.localtime(record.created))
        ids = " ".join(f"{k}={v}" for k, v in (("task", _task_id.get()), ("trace", _trace_id.get())) if v)
        fields = getattr(record, "fields", None)
        extra = " ".join(f"{k}={v}" for k, v in fields.items()) if isinstance(fields, dict) else ""
        line = " ".join(p for p in (prefix, record.levelname, f"[{record.name}]", ids, record.getMessage(), extra) if p)
        if record.exc_info:
            line += "\n" + self.formatException(record.exc_info)
        return line


def setup_logging(fmt: str = "json", level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter() if fmt == "json" else TextFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
    for noisy in ("pika", "pdfminer", "urllib3"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


@contextlib.contextmanager
def task_context(task_id, trace_id):
    t1, t2 = _task_id.set(task_id), _trace_id.set(trace_id)
    try:
        yield
    finally:
        _task_id.reset(t1)
        _trace_id.reset(t2)
