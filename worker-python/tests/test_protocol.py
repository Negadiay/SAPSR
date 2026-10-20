"""Обмен с бэкендом через очереди (docs/contracts/queue-v2.md) — без RabbitMQ, на поддельном канале."""

from __future__ import annotations

import json
import shutil
from dataclasses import replace
from pathlib import Path

import pytest

from conftest import CONTRACTS, HYBRID, NOW, make_llm
from main import Publisher, Worker
from sapsr.pipeline import CheckService
from sapsr.settings import Settings
from sapsr.task import InvalidTask, parse_task

KEY = "2026/10/8f3a2c1e-5b7d-4e2a-9c4f-1a2b3c4d5e6f.pdf"
TRACE = "4b1e0c7a-2f3d-4a5b-8c6d-7e8f9a0b1c2d"


class FakeChannel:
    def __init__(self):
        self.published: list[tuple[str, dict, object]] = []
        self.acked, self.rejected = [], []

    def basic_publish(self, exchange, routing_key, body, properties):
        self.published.append((routing_key, json.loads(body.decode("utf-8")), properties))

    def basic_ack(self, delivery_tag):
        self.acked.append(delivery_tag)

    def basic_reject(self, delivery_tag, requeue):
        self.rejected.append((delivery_tag, requeue))

    def queue(self, name):
        return [body for key, body, _ in self.published if key == name]


class Method:
    delivery_tag = 7


@pytest.fixture
def storage(tmp_path):
    def put(fixture: str, key: str = KEY) -> Path:
        target = tmp_path / key
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(HYBRID / fixture, target)
        return tmp_path
    return put


def task_body(key=KEY, **extra) -> bytes:
    task = json.loads((CONTRACTS / "examples" / "queue-v2" / "task.json").read_text(encoding="utf-8"))
    task["file"]["storage_key"] = key
    task["file"]["sha256"] = None
    task["file_path"] = "/не/используется/при/наличии/ключа.pdf"
    task.update(extra)
    return json.dumps(task, ensure_ascii=False).encode("utf-8")


def worker(kb, directory, model, root: Path, protocol: int, llm=True, service=None) -> Worker:
    settings = replace(Settings(), storage_root=root, queue_protocol=protocol)
    service = service or CheckService(kb, make_llm(model) if llm else None, directory, clock=lambda: NOW)
    return Worker(settings, service)


def deliver(w: Worker, body: bytes) -> FakeChannel:
    channel = FakeChannel()
    w.on_message(channel, Method(), None, body)
    return channel


# ── v1: как сейчас ждёт бэкенд ──────────────────────────────────────────────

@pytest.mark.typical
def test_v1_single_result(kb, directory, model, storage):
    root = storage("content-formats.pdf")
    channel = deliver(worker(kb, directory, model, root, 1), task_body())
    assert channel.acked == [7]
    assert [key for key, _, _ in channel.published] == ["pdf_results_queue"]
    result = channel.queue("pdf_results_queue")[0]
    assert set(result) == {"task_id", "status", "errors"} and result["status"] == "SUCCESS"
    content = next(e for e in result["errors"] if e["requirement_id"] == "CNT-CONSIST-002")
    assert content["severity"] == "warning", "неблокирующее замечание не считается «ошибкой» в v1"
    assert {"severity", "page", "location", "message", "rule", "found", "fix", "context"} <= set(content)


@pytest.mark.negative
def test_v1_failure_is_acked_with_fail(kb, directory, model, tmp_path):
    channel = deliver(worker(kb, directory, model, tmp_path, 1), task_body())
    result = channel.queue("pdf_results_queue")[0]
    assert channel.acked == [7]
    assert result["status"] == "FAIL" and result["errors"][0]["severity"] == "critical"
    assert result["errors"][0]["message"].startswith("Файл работы не найден")


# ── v2: прогресс и две фазы ─────────────────────────────────────────────────

@pytest.mark.typical
def test_v2_progress_and_two_phases(kb, directory, model, storage):
    root = storage("content-formats.pdf")
    channel = deliver(worker(kb, directory, model, root, 2), task_body())
    progress = channel.queue("pdf_progress_queue")
    assert [(p["index"], p["stage"]) for p in progress] == list(enumerate(
        ["extract", "structure", "format", "claims", "reasoning", "report"], start=1))
    assert all(p["schema"] == 2 and p["total"] == 6 and p["trace_id"] == TRACE and p["task_id"] == 153
               for p in progress)
    phases = channel.queue("pdf_results_queue")
    assert [(r["phase"], r["passed"], r["final"]) for r in phases] == [("format", True, False), ("content", True, True)]
    assert all(r["schema"] == 2 and r["report"]["schema"] == 2 and r["trace_id"] == TRACE for r in phases)
    assert any(f["requirement_id"] == "CNT-CONSIST-002" for f in phases[1]["report"]["findings"])
    _, _, props = channel.published[0]
    assert props.delivery_mode == 2 and props.content_type == "application/json"


@pytest.mark.typical
def test_v2_rejected_by_format_is_final(kb, directory, model, storage):
    root = storage("scan-no-text.pdf")
    channel = deliver(worker(kb, directory, model, root, 2), task_body())
    phases = channel.queue("pdf_results_queue")
    assert [(r["phase"], r["passed"], r["final"]) for r in phases] == [("format", False, True)]
    assert [p["stage"] for p in channel.queue("pdf_progress_queue")] == ["extract", "structure", "format"]
    assert model.calls == []


@pytest.mark.typical
def test_v2_model_off_format_only(kb, directory, model, storage):
    root = storage("content-formats.pdf")
    channel = deliver(worker(kb, directory, model, root, 2, llm=False), task_body())
    phases = channel.queue("pdf_results_queue")
    assert [(r["phase"], r["final"]) for r in phases] == [("format", True)]
    assert "LLM_UNAVAILABLE" in {s["reason"] for s in phases[0]["report"]["pipeline"]["skipped"]}


@pytest.mark.negative
@pytest.mark.parametrize("fixture, key, code", [
    (None, KEY, "FILE_NOT_FOUND"),
    ("encrypted.pdf", KEY, "PDF_ENCRYPTED"),
    ("content-clean.pdf", "../../etc/passwd", "FILE_NOT_FOUND"),
    ("content-clean.pdf", "C:/Windows/win.ini", "FILE_NOT_FOUND"),
])
def test_v2_failed_phase(kb, directory, model, storage, tmp_path, fixture, key, code):
    root = storage(fixture) if fixture else tmp_path
    channel = deliver(worker(kb, directory, model, root, 2), task_body(key=key))
    assert channel.acked == [7]
    failed = channel.queue("pdf_results_queue")[-1]
    assert failed["phase"] == "failed" and failed["final"] is True and failed["error"]["code"] == code
    assert "/" not in failed["error"]["message"] and "\\" not in failed["error"]["message"], "без путей на диске"


@pytest.mark.negative
def test_v2_internal_error(kb, directory, model, storage):
    class Broken(CheckService):
        def check(self, *args, **kwargs):
            raise RuntimeError("неожиданная ошибка")
            yield  # pragma: no cover

    root = storage("content-clean.pdf")
    service = Broken(kb, None, directory)
    channel = deliver(worker(kb, directory, model, root, 2, service=service), task_body())
    assert channel.acked == [7]
    assert channel.queue("pdf_results_queue")[0]["error"]["code"] == "INTERNAL"


@pytest.mark.negative
@pytest.mark.parametrize("body", [
    b"not json", b"[1, 2]", json.dumps({"schema": 3, "task_id": 1}).encode(),
    json.dumps({"schema": 2, "file": {"storage_key": "a.pdf"}}).encode(),
    json.dumps({"schema": 2, "task_id": 1}).encode(),
    json.dumps({"schema": 2, "task_id": 1, "type": "EXPLAIN", "file_path": "/a.pdf"}).encode(),
])
def test_unparseable_messages_are_rejected(kb, directory, model, tmp_path, body):
    channel = deliver(worker(kb, directory, model, tmp_path, 2), body)
    assert channel.rejected == [(7, False)] and channel.acked == [] and channel.published == []


def test_contract_task_example_parses():
    task = parse_task((CONTRACTS / "examples" / "queue-v2" / "task.json").read_bytes())
    assert task.task_id == 153 and task.file.storage_key.startswith("2026/10/")
    assert task.profile.semester == 7 and task.student.group == "321702" and task.trace_id == TRACE


def test_v1_task_still_accepted():
    task = parse_task(json.dumps({"task_id": 5, "file_path": "/app/storage/x.pdf", "status": "PROCESSING"}).encode())
    assert task.schema_ == 1 and task.file.storage_key is None and task.profile.empty


def test_null_profile_and_student():
    task = parse_task(json.dumps({"schema": 2, "task_id": 5, "file": {"storage_key": "a.pdf"},
                                  "profile": None, "student": None, "trace_id": TRACE}).encode())
    assert task.profile.empty and task.student.last_name is None


def test_invalid_task_message_has_no_personal_data():
    with pytest.raises(InvalidTask) as exc:
        parse_task(json.dumps({"schema": 2, "task_id": "x", "student": {"last_name": "Иванов"},
                               "file": {"storage_key": "a.pdf"}}).encode())
    assert "Иванов" not in str(exc.value)


def test_publisher_failed_message_shape():
    channel = FakeChannel()
    Publisher(channel, Settings()).failed(parse_task(task_body()), "TIMEOUT", "Проверка не уложилась во время.")
    _, body, _ = channel.published[0]
    example = json.loads((CONTRACTS / "examples" / "queue-v2" / "result-failed.json").read_text(encoding="utf-8"))
    assert set(body) == set(example) and body["error"].keys() == example["error"].keys()
