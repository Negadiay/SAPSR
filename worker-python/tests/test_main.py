"""Обработчик очереди: задания v1 и v2, путь к файлу, результат-сбой вместо падения."""

import json
from types import SimpleNamespace

import pytest

import main


def task_v2(storage_key="2026/10/abc.pdf", file_path="/app/storage/2026/10/abc.pdf"):
    return {
        "schema": 2,
        "task_id": 153,
        "type": "CHECK",
        "file": {"storage_key": storage_key, "sha256": "0" * 64, "source_format": "pdf"},
        "trace_id": "4b1e0c7a-2f3d-4a5b-8c6d-7e8f9a0b1c2d",
        "file_path": file_path,
    }


class FakeChannel:
    def __init__(self):
        self.published, self.acked, self.rejected = [], [], []

    def basic_publish(self, exchange, routing_key, body, properties=None):
        self.published.append((routing_key, json.loads(body)))

    def basic_ack(self, delivery_tag):
        self.acked.append(delivery_tag)

    def basic_reject(self, delivery_tag, requeue):
        self.rejected.append((delivery_tag, requeue))


# ---- разбор задания ----

@pytest.mark.typical
def test_parse_v1_and_v2():
    assert main.parse_task(b'{"task_id": 1, "file_path": "/x.pdf", "status": "PENDING"}')["task_id"] == 1
    assert main.parse_task(json.dumps(task_v2()).encode())["schema"] == 2


@pytest.mark.typical
def test_parse_example_from_contract(fixtures_dir):
    """Пример задания из протокола (docs/contracts/examples/queue-v2/task.json) разбирается."""
    body = (fixtures_dir.parent / "docs/contracts/examples/queue-v2/task.json").read_bytes()
    assert main.parse_task(body)["task_id"] == 153


@pytest.mark.negative
@pytest.mark.parametrize("body", [b"not json", b"[1,2]", b'{"file_path": "/x.pdf"}', b'{"task_id": 1, "schema": 3}', b"\xff\xfe"])
def test_parse_rejects_garbage(body):
    with pytest.raises(ValueError):
        main.parse_task(body)


# ---- путь к файлу ----

@pytest.mark.typical
def test_storage_key_is_used_when_file_exists(tmp_path, monkeypatch):
    pdf = tmp_path / "2026" / "10" / "abc.pdf"
    pdf.parent.mkdir(parents=True)
    pdf.write_bytes(b"%PDF-1.4")
    monkeypatch.setenv(main.STORAGE_ROOT_ENV, str(tmp_path))
    assert main.resolve_pdf_path(task_v2(file_path="/elsewhere.pdf")) == str(pdf.resolve())


@pytest.mark.boundary
def test_falls_back_to_file_path(tmp_path, monkeypatch):
    """Переходный период: корень не задан или файла по ключу нет — берётся file_path."""
    monkeypatch.delenv(main.STORAGE_ROOT_ENV, raising=False)
    assert main.resolve_pdf_path(task_v2(file_path="/old/path.pdf")) == "/old/path.pdf"
    monkeypatch.setenv(main.STORAGE_ROOT_ENV, str(tmp_path))
    assert main.resolve_pdf_path(task_v2(file_path="/old/path.pdf")) == "/old/path.pdf"
    assert main.resolve_pdf_path({"task_id": 1, "file_path": "/v1.pdf"}) == "/v1.pdf"


@pytest.mark.negative
def test_storage_key_cannot_escape_root(tmp_path, monkeypatch):
    monkeypatch.setenv(main.STORAGE_ROOT_ENV, str(tmp_path / "storage"))
    with pytest.raises(main.TaskError):
        main.resolve_pdf_path(task_v2(storage_key="../../etc/passwd"))


@pytest.mark.negative
def test_no_path_at_all():
    with pytest.raises(main.TaskError) as e:
        main.resolve_pdf_path({"task_id": 1})
    assert e.value.code == "FILE_NOT_FOUND"


# ---- обработчик не падает ----

@pytest.mark.negative
def test_analyzer_exception_becomes_failure_and_message_is_acked(monkeypatch):
    def boom(path):
        raise RuntimeError("секретный /путь/на/диске")

    monkeypatch.setattr(main, "analyze_pdf", boom)
    channel = FakeChannel()
    main.on_message(channel, SimpleNamespace(delivery_tag=7), None, json.dumps(task_v2()).encode())

    assert channel.acked == [7]
    queue, result = channel.published[0]
    assert queue == main.RESULTS_QUEUE
    # v2
    assert result["phase"] == "failed" and result["final"] is True
    assert result["error"]["code"] == "INTERNAL"
    assert result["trace_id"] == task_v2()["trace_id"]
    # v1 — нынешний бэкенд переведёт работу в REJECTED
    assert result["status"] == "FAIL" and result["errors"]
    # подробности — только в лог
    assert "/путь/" not in json.dumps(result, ensure_ascii=False)


@pytest.mark.typical
def test_normal_result_stays_v1(monkeypatch):
    monkeypatch.setattr(main, "analyze_pdf", lambda path: {"status": "SUCCESS", "errors": []})
    channel = FakeChannel()
    main.on_message(channel, SimpleNamespace(delivery_tag=1), None, b'{"task_id": 5, "file_path": "/x.pdf"}')
    assert channel.published[0][1] == {"task_id": 5, "status": "SUCCESS", "errors": []}
    assert channel.acked == [1]


@pytest.mark.negative
def test_garbage_is_rejected_without_requeue():
    channel = FakeChannel()
    main.on_message(channel, SimpleNamespace(delivery_tag=3), None, b"not json")
    assert channel.rejected == [(3, False)]
    assert channel.published == [] and channel.acked == []


@pytest.mark.negative
def test_real_broken_pdf_end_to_end(tmp_path):
    """Настоящий анализатор на повреждённом файле: результат FAIL, сообщение подтверждено."""
    broken = tmp_path / "broken.pdf"
    broken.write_bytes(b"%PDF-1.4\n garbage")
    channel = FakeChannel()
    main.on_message(channel, SimpleNamespace(delivery_tag=9), None,
                    json.dumps({"task_id": 9, "file_path": str(broken)}).encode())
    assert channel.acked == [9]
    assert channel.published[0][1]["status"] == "FAIL"
