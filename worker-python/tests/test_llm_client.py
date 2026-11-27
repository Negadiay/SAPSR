"""Клиент языковой модели на поддельном сервере Ollama (план, З1б; решения 1–2; Ж2)."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from pydantic import BaseModel

from sapsr.llm.client import (
    LlmClient, LlmConfig, LlmSchemaError, LlmUnavailable, is_local_url,
)


class Answer(BaseModel):
    verdict: str
    score: int


class FakeOllama:
    """Отвечает на /api/tags, /api/show и /api/chat; ответы чата — по очереди из списка."""

    def __init__(self, replies, models=("qwen3.5:4b",)):
        self.replies = list(replies)
        self.models = models
        self.requests: list[dict] = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _send(self, payload):
                body = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.path == "/api/tags":
                    self._send({"models": [{"name": m} for m in fake.models]})
                else:
                    self.send_error(404)

            def do_POST(self):
                data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path == "/api/show":
                    self._send({"details": {"quantization_level": "Q4_K_M", "parameter_size": "4B"}})
                    return
                fake.requests.append(data)
                self._send({"message": {"role": "assistant", "content": fake.replies.pop(0)}, "done": True})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()


@pytest.fixture
def ollama():
    servers = []

    def start(*replies, **kwargs):
        server = FakeOllama(replies, **kwargs)
        servers.append(server)
        return server
    yield start
    for server in servers:
        server.close()


def client(url, **kwargs) -> LlmClient:
    return LlmClient(LlmConfig(base_url=url, **kwargs))


@pytest.mark.typical
def test_request_is_reproducible_and_schema_bound(ollama):
    server = ollama('{"verdict": "ok", "score": 3}')
    llm = client(server.url)
    assert llm.available()
    result = llm.complete_json("test.v1", "система", "пользователь", Answer)
    assert result.data == Answer(verdict="ok", score=3) and result.attempts == 1
    sent = server.requests[0]
    assert sent["options"]["temperature"] == 0 and sent["options"]["seed"] == 42
    assert sent["think"] is False and sent["stream"] is False
    assert sent["format"]["properties"].keys() == {"verdict", "score"}
    assert llm.model_details()["quantization"] == "Q4_K_M"


@pytest.mark.boundary
def test_one_retry_on_schema_violation(ollama):
    server = ollama("это не JSON", '{"verdict": "ok", "score": 1}')
    result = client(server.url).complete_json("test.v1", "s", "u", Answer)
    assert result.attempts == 2
    retry = server.requests[1]["messages"]
    assert retry[-1]["role"] == "user" and "JSON-схемы" in retry[-1]["content"]


@pytest.mark.negative
def test_two_schema_violations_raise(ollama):
    server = ollama('{"verdict": 1}', '{"score": "x"}')
    llm = client(server.url)
    with pytest.raises(LlmSchemaError):
        llm.complete_json("test.v1", "s", "u", Answer)
    assert llm.stats.schema_failures == 1


@pytest.mark.typical
def test_cache_by_text_prompt_and_model(ollama, tmp_path):
    server = ollama('{"verdict": "ok", "score": 2}', '{"verdict": "other", "score": 5}')
    llm = client(server.url, cache_dir=tmp_path)
    first = llm.complete_json("test.v1", "s", "u", Answer)
    again = llm.complete_json("test.v1", "s", "u", Answer)
    assert again.cached and again.data == first.data and len(server.requests) == 1
    changed = llm.complete_json("test.v2", "s", "u", Answer)  # новая версия подсказки — новый ключ
    assert not changed.cached and changed.data.score == 5


@pytest.mark.typical
def test_record_then_replay(ollama, tmp_path):
    server = ollama('{"verdict": "ok", "score": 7}')
    recorder = client(server.url, mode="record", replay_dir=tmp_path)
    recorder.complete_json("test.v1", "s", "u", Answer)
    replay = LlmClient(LlmConfig(mode="replay", replay_dir=tmp_path))
    assert replay.complete_json("test.v1", "s", "u", Answer).data.score == 7
    with pytest.raises(LlmUnavailable):
        replay.complete_json("test.v1", "s", "другой запрос", Answer)


@pytest.mark.negative
def test_server_down():
    llm = client("http://127.0.0.1:9")
    assert not llm.available()
    with pytest.raises(LlmUnavailable):
        llm.complete_json("test.v1", "s", "u", Answer)


@pytest.mark.negative
def test_model_not_pulled(ollama):
    server = ollama(models=("llama3:8b",))
    assert not client(server.url).available()


@pytest.mark.negative
def test_cloud_requires_explicit_permission():
    llm = client("https://api.example.com", provider="openai", model="gpt")
    assert llm.is_cloud and not llm.available()
    with pytest.raises(LlmUnavailable, match="облачная модель не разрешена"):
        llm.complete_json("test.v1", "s", "текст работы", Answer)
    allowed = client("https://api.example.com", provider="openai", model="gpt", allow_cloud=True)
    assert allowed.is_cloud


def test_off_mode():
    llm = LlmClient(LlmConfig(mode="off"))
    assert not llm.available()
    with pytest.raises(LlmUnavailable):
        llm.complete_json("test.v1", "s", "u", Answer)


@pytest.mark.parametrize("url, local", [
    ("http://localhost:11434", True), ("http://127.0.0.1:11434", True), ("http://ollama:11434", True),
    ("http://host.docker.internal:11434", True), ("http://192.168.1.20:11434", True),
    ("https://gigachat.devices.sberbank.ru/api/v1", False), ("https://llm.api.cloud.yandex.net/v1", False),
])
def test_is_local_url(url, local):
    assert is_local_url(url) is local


def test_stub_file(tmp_path):
    stub = tmp_path / "stub.json"
    stub.write_text(json.dumps({"default:test": {"verdict": "stub", "score": 0}}), encoding="utf-8")
    llm = LlmClient(LlmConfig(mode="stub", stub_file=stub))
    assert llm.complete_json("test.v1", "s", "u", Answer).data.verdict == "stub"
