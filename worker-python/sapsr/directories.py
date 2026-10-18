"""Справочники ИИС БГУИР для проверок титульного листа.

Анализатор больше не ходит в ИИС сам: проверки получают справочник через интерфейс
Directory (план, З9). Пока у бэкенда нет таблиц iis_groups и iis_employees (план Котко, К3),
работает IisHttpDirectory — тот же запрос к ИИС, что и раньше, но:
  · с коротким тайм-аутом и «предохранителем»: после сбоя ИИС не опрашивается
    `retry_after_s` секунд, и следующие работы не ждут по 26 секунд;
  · недоступный справочник — это None, а зависящие проверки попадают в pipeline.skipped.
Когда появятся таблицы, сюда добавится реализация, читающая их из PostgreSQL.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Optional, Protocol

import requests

from sapsr.document.text import normalize_code, normalize_person_name

log = logging.getLogger("sapsr.directories")

SPECIALITIES_URL = "https://iis.bsuir.by/api/v1/specialities"
EMPLOYEES_URL = "https://iis.bsuir.by/api/v1/employees/all"


class Directory(Protocol):
    name: str

    def specialties(self) -> Optional[frozenset[str]]:
        """Нормализованные коды специальностей или None, если справочник недоступен."""

    def employee_names(self) -> Optional[frozenset[str]]:
        """Нормализованные варианты ФИО сотрудников или None, если справочник недоступен."""


class NullDirectory:
    name = "off"

    def specialties(self) -> Optional[frozenset[str]]:
        return None

    def employee_names(self) -> Optional[frozenset[str]]:
        return None


class StaticDirectory:
    """Справочник из готовых списков — для тестов и запуска без сети."""

    name = "static"

    def __init__(self, specialty_codes=(), employee_names=()):
        self._codes = frozenset(normalize_code(c) for c in specialty_codes)
        self._names = frozenset(v for n in employee_names for v in name_variants({"fio": n}))

    def specialties(self) -> Optional[frozenset[str]]:
        return self._codes

    def employee_names(self) -> Optional[frozenset[str]]:
        return self._names


def name_variants(employee: dict) -> set[str]:
    variants = set()
    fio = employee.get("fio")
    if fio:
        variants.add(normalize_person_name(fio))
        parts = str(fio).split()
        if len(parts) >= 2:
            initials = "".join(f"{p[0]}." for p in parts[1:3])
            variants.add(normalize_person_name(f"{parts[0]} {initials}"))
            variants.add(normalize_person_name(f"{initials} {parts[0]}"))
    last_name = employee.get("lastName") or employee.get("last_name")
    first_name = employee.get("firstName") or employee.get("first_name")
    middle_name = employee.get("middleName") or employee.get("middle_name")
    if last_name and first_name:
        first_initial = str(first_name).strip()[:1]
        middle_initial = str(middle_name).strip()[:1] if middle_name else ""
        middle_part = f"{middle_initial}." if middle_initial else ""
        variants.add(normalize_person_name(f"{last_name} {first_initial}.{middle_part}"))
        variants.add(normalize_person_name(f"{first_initial}.{middle_part} {last_name}"))
        if middle_name:
            variants.add(normalize_person_name(f"{last_name} {first_name} {middle_name}"))
    return {v for v in variants if v}


class IisHttpDirectory:
    name = "iis-http"

    def __init__(self, ttl_s: float = 3600, retry_after_s: float = 600,
                 connect_timeout_s: float = 3, read_timeout_s: float = 8,
                 session: Optional[requests.Session] = None):
        self.ttl_s = ttl_s
        self.retry_after_s = retry_after_s
        self.timeout = (connect_timeout_s, read_timeout_s)
        self.session = session or requests.Session()
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[float, frozenset[str]]] = {}
        self._failed_at: dict[str, float] = {}

    def _get(self, key: str, url: str, parse) -> Optional[frozenset[str]]:
        now = time.monotonic()
        with self._lock:
            cached = self._cache.get(key)
            if cached and now - cached[0] < self.ttl_s:
                return cached[1]
            failed = self._failed_at.get(key)
            if failed is not None and now - failed < self.retry_after_s:
                return cached[1] if cached else None
        try:
            resp = self.session.get(url, timeout=self.timeout)
            resp.raise_for_status()
            value = frozenset(parse(resp.json()))
        except Exception as exc:  # сеть, тайм-аут, неожиданный ответ
            log.warning("справочник ИИС недоступен", extra={"fields": {"directory": key, "error": type(exc).__name__}})
            with self._lock:
                self._failed_at[key] = now
            return cached[1] if cached else None
        with self._lock:
            self._cache[key] = (now, value)
            self._failed_at.pop(key, None)
        return value

    def specialties(self) -> Optional[frozenset[str]]:
        def parse(payload):
            return (normalize_code(item.get("code", "")) for item in payload
                    if isinstance(item, dict) and item.get("code"))
        return self._get("specialties", SPECIALITIES_URL, parse)

    def employee_names(self) -> Optional[frozenset[str]]:
        def parse(payload):
            employees = payload if isinstance(payload, list) else payload.get("employees", [])
            names: set[str] = set()
            for employee in employees:
                if isinstance(employee, dict):
                    names.update(name_variants(employee))
            return names
        return self._get("employees", EMPLOYEES_URL, parse)


def make_directory(mode: str) -> Directory:
    if mode == "off":
        return NullDirectory()
    if mode == "http":
        return IisHttpDirectory()
    raise ValueError(f"неизвестный режим справочника ИИС: {mode}")
