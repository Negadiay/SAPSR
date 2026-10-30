"""Каталог моделей, выбор модели на проверку и защита данных облачной модели (решение 13)."""

from __future__ import annotations

import json
import re

import pytest
from conftest import HYBRID, NOW, WORKER, make_task
from pydantic import BaseModel
from scripted_llm import ScriptedModel
from test_llm_client import FakeOllama

from sapsr.llm.catalog import Catalog, LlmRegistry, ModelSpec, load_catalog
from sapsr.llm.client import LlmClient, LlmConfig, LlmUnavailable
from sapsr.llm.privacy import PrivateLlm, Redactor
from sapsr.pipeline import CheckService

CATALOG = WORKER / "llm-models.yaml"
CLOUD_ENV = {
    "SAPSR_LLM_CLOUD_BASE_URL": "https://llm.example.com/v1", "SAPSR_LLM_CLOUD_MODEL": "big-model",
    "SAPSR_LLM_CLOUD_API_KEY": "секретный-ключ",
    "SAPSR_LLM_CLOUD_EXTRA_BODY": '{"provider": {"data_collection": "deny", "zdr": true}}',
    "SAPSR_LLM_CLOUD_HEADERS": '{"OpenAI-Project": "b1g-folder"}',
}


class Answer(BaseModel):
    verdict: str
    score: int


# ── каталог ─────────────────────────────────────────────────────────────────

@pytest.mark.typical
def test_catalog_has_light_main_heavy_and_cloud():
    catalog = load_catalog(CATALOG, LlmConfig(), env={})
    assert catalog.default_id == "main"
    assert [(m.id, m.tier) for m in catalog.models.values()] == [
        ("light", "light"), ("main", "main"), ("heavy", "heavy"), ("cloud", "cloud")]
    assert catalog.models["main"].model == "qwen3.5:4b"
    assert catalog.models["light"].model == "qwen3.5:2b" and catalog.models["heavy"].model == "qwen3.5:9b"
    # Облачная по умолчанию — Groq (решение 2), но без разрешения и ключа в .env сама не заработает.
    cloud = catalog.models["cloud"]
    assert cloud.base_url == "https://api.groq.com/openai/v1" and cloud.model == "openai/gpt-oss-120b"
    assert cloud.extra_body == {} and cloud.api_key_env == "SAPSR_LLM_CLOUD_API_KEY"


def test_catalog_values_come_from_environment():
    catalog = load_catalog(CATALOG, LlmConfig(), env={"SAPSR_LLM_MODEL": "qwen3:4b", **CLOUD_ENV})
    cloud = catalog.models["cloud"]
    assert catalog.models["main"].model == "qwen3:4b"
    assert cloud.base_url == "https://llm.example.com/v1" and cloud.model == "big-model"
    assert cloud.extra_body == {"provider": {"data_collection": "deny", "zdr": True}}
    assert cloud.headers == {"OpenAI-Project": "b1g-folder"}
    # Ключ в каталоге не хранится — только имя переменной.
    assert "секретный-ключ" not in repr(catalog)


def test_without_catalog_file_one_model_from_old_settings(tmp_path):
    catalog = load_catalog(tmp_path / "нет.yaml", LlmConfig(model="qwen3:4b"))
    assert list(catalog.models) == ["main"] and catalog.models["main"].model == "qwen3:4b"


@pytest.mark.negative
def test_catalog_rejects_bad_entries(tmp_path):
    bad = tmp_path / "models.yaml"
    bad.write_text("models:\n  - {id: x, tier: huge, model: m}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="tier"):
        load_catalog(bad, LlmConfig(), env={})
    bad.write_text("default: nope\nmodels:\n  - {id: x, model: m}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="nope"):
        load_catalog(bad, LlmConfig(), env={})


# ── выбор модели на проверку ────────────────────────────────────────────────

@pytest.fixture
def local_registry():
    servers = []

    def make(pulled=("qwen3.5:4b", "qwen3.5:2b"), env=None):
        server = FakeOllama([], models=pulled)
        servers.append(server)
        base = LlmConfig(base_url=server.url)
        return LlmRegistry(load_catalog(CATALOG, base, env=env or {}), base, env=env or {})
    yield make
    for server in servers:
        server.close()


@pytest.mark.typical
def test_choice_follows_admin_and_falls_back_to_default(local_registry):
    registry = local_registry()
    assert registry.choose(None).model_id == "main" and registry.choose(None).warning is None
    light = registry.choose("light")
    assert light.model_id == "light" and light.client.config.model == "qwen3.5:2b" and light.warning is None
    # Тяжёлая не скачана — проверит основная, и в отчёте будет сказано почему.
    heavy = registry.choose("heavy")
    assert heavy.model_id == "main" and "«Тяжёлая» недоступна" in heavy.warning
    unknown = registry.choose("gpt-5")
    assert unknown.model_id == "main" and "нет в каталоге" in unknown.warning


@pytest.mark.negative
def test_cloud_needs_permission_https_and_key(local_registry):
    status = {m["id"]: m for m in local_registry().status()}
    assert status["cloud"]["cloud"] and not status["cloud"]["available"]
    assert "не разрешена" in status["cloud"]["reason"]

    not_allowed = {m["id"]: m for m in local_registry(env=CLOUD_ENV).status()}["cloud"]
    assert "не разрешена" in not_allowed["reason"]

    http = LlmClient(LlmConfig(provider="openai", base_url="http://llm.example.com/v1", model="m", api_key="k",
                               allow_cloud=True))
    assert "HTTPS" in http.cloud_problem()
    with pytest.raises(LlmUnavailable, match="HTTPS"):
        http.complete_json("test.v1", "s", "текст работы", Answer)
    no_key = LlmClient(LlmConfig(provider="openai", base_url="https://llm.example.com/v1", model="m",
                                 allow_cloud=True))
    assert "ключ" in no_key.cloud_problem()


def test_status_has_no_addresses_or_keys(local_registry):
    rows = local_registry(env=CLOUD_ENV).status()
    text = json.dumps(rows, ensure_ascii=False)
    assert "example.com" not in text and "секретный" not in text and "b1g-folder" not in text
    assert {r["id"]: r["available"] for r in rows} == {"light": True, "main": True, "heavy": False, "cloud": False}


def test_never_falls_back_to_cloud():
    spec = ModelSpec(id="cloud", title="Облачная", tier="cloud", model="m", provider="openai",
                     base_url="https://llm.example.com/v1")
    local = ModelSpec(id="main", title="Основная", tier="main", model="qwen3.5:4b",
                      base_url="http://127.0.0.1:9")
    registry = LlmRegistry(Catalog(default_id="cloud", models={"cloud": spec, "main": local}),
                           LlmConfig(allow_cloud=True), env={})
    choice = registry.choose("main")
    assert choice.model_id == "main" and not choice.client.is_cloud


def test_replay_mode_offers_only_default(tmp_path):
    base = LlmConfig(mode="replay", replay_dir=tmp_path)
    registry = LlmRegistry(load_catalog(CATALOG, base, env={}), base, env={})
    rows = {r["id"]: r for r in registry.status()}
    assert rows["main"]["available"] and not rows["light"]["available"]
    assert "replay" in rows["light"]["reason"]


# ── облачный запрос ─────────────────────────────────────────────────────────

class FakeResponse:
    def __init__(self, payload, status=200, redirect=False, headers=None):
        self.payload, self.status_code, self.is_redirect = payload, status, redirect
        self.headers = headers or {}

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)

    def json(self):
        return self.payload


class FakeCloud:
    """Поддельный OpenAI-совместимый сервис: запоминает запросы, отвечает ответом respond(user)."""

    def __init__(self, respond, redirect=False, before=()):
        self.respond, self.redirect = respond, redirect
        self.posts: list[dict] = []
        # Ответы-ошибки, которые сервис отдаст первыми (лимит тарифа, отказ по схеме).
        self.before = list(before)

    def get(self, url, headers=None, timeout=None, allow_redirects=True):
        return FakeResponse({"data": []})

    def post(self, url, json=None, headers=None, timeout=None, allow_redirects=True):
        self.posts.append({"url": url, "body": json, "headers": headers, "allow_redirects": allow_redirects})
        if self.redirect:
            return FakeResponse({}, status=307, redirect=True)
        if self.before:
            return self.before.pop(0)
        user = "\n".join(m["content"] for m in json["messages"] if m["role"] == "user")
        content = self.respond(user)
        return FakeResponse({"choices": [{"message": {"content": content}}]})


def cloud_client(fake: FakeCloud, **kwargs) -> LlmClient:
    config = LlmConfig(provider="openai", base_url="https://llm.example.com/v1", model="big-model",
                       api_key="k", allow_cloud=True, extra_body={"provider": {"zdr": True}, "model": "чужая"},
                       headers={"OpenAI-Project": "b1g"}, **kwargs)
    return LlmClient(config, session=fake)


def test_cloud_request_carries_service_fields_and_no_redirects():
    fake = FakeCloud(lambda user: '{"verdict": "ok", "score": 1}')
    result = cloud_client(fake).complete_json("test.v1", "s", "u", Answer)
    assert result.data.verdict == "ok"
    post = fake.posts[0]
    assert post["allow_redirects"] is False
    assert post["body"]["provider"] == {"zdr": True} and post["body"]["model"] == "big-model"
    assert post["headers"] == {"OpenAI-Project": "b1g", "Authorization": "Bearer k"}


@pytest.mark.negative
def test_cloud_redirect_is_not_followed():
    fake = FakeCloud(lambda user: "{}", redirect=True)
    with pytest.raises(LlmUnavailable, match="перенаправлением"):
        cloud_client(fake).complete_json("test.v1", "s", "u", Answer)
    assert len(fake.posts) == 1


def sleepless(client: LlmClient) -> list[float]:
    waits: list[float] = []
    client._sleep = waits.append
    return waits


@pytest.mark.typical
def test_rate_limit_waits_as_service_asks_and_continues():
    """Бесплатный тариф Groq: 429 с Retry-After — подождать и повторить, раздел не теряется."""
    limited = FakeResponse({"error": {"code": "rate_limit_exceeded"}}, status=429, headers={"retry-after": "3"})
    tokens = FakeResponse({}, status=429, headers={"x-ratelimit-reset-tokens": "1m2.5s"})
    fake = FakeCloud(lambda user: '{"verdict": "ok", "score": 1}', before=[limited, tokens])
    client = cloud_client(fake, timeout_s=120)
    waits = sleepless(client)
    assert client.complete_json("test.v1", "s", "u", Answer).data.verdict == "ok"
    assert waits == [3.0, 62.5] and len(fake.posts) == 3
    assert client.stats.as_dict()["llm_rate_waits"] == 2


@pytest.mark.negative
def test_rate_limit_longer_than_deadline_skips_without_waiting():
    daily = FakeResponse({}, status=429, headers={"retry-after": "3600"})
    fake = FakeCloud(lambda user: "{}", before=[daily])
    client = cloud_client(fake)
    waits = sleepless(client)
    with pytest.raises(LlmUnavailable, match="ограничил число запросов"):
        client.complete_json("test.v1", "s", "u", Answer, timeout_s=60)
    assert waits == [] and client.stats.as_dict()["llm_errors"] == 1


def test_service_schema_rejection_goes_through_our_retry():
    """Groq сам сверяет ответ со схемой и отвечает 400 json_validate_failed — модель просят исправиться."""
    rejected = FakeResponse({"error": {"code": "json_validate_failed", "failed_generation": '{"verdict": "ok"}'}},
                            status=400)
    fake = FakeCloud(lambda user: '{"verdict": "ok", "score": 2}', before=[rejected])
    result = cloud_client(fake).complete_json("test.v1", "s", "u", Answer)
    assert result.data.score == 2 and result.attempts == 2
    retry = fake.posts[1]["body"]["messages"]
    assert retry[-2] == {"role": "assistant", "content": '{"verdict": "ok"}'}


@pytest.mark.negative
def test_rejected_key_names_status_but_not_body():
    denied = FakeResponse({"error": {"code": "invalid_api_key", "message": "текст запроса: Терешкова"}}, status=401)
    fake = FakeCloud(lambda user: "{}", before=[denied])
    with pytest.raises(LlmUnavailable, match=r"HTTP 401 \(invalid_api_key\)") as exc:
        cloud_client(fake).complete_json("test.v1", "s", "u", Answer)
    assert "Терешкова" not in str(exc.value)


@pytest.mark.parametrize("text, seconds", [("2", 2.0), ("7.66s", 7.66), ("2m59.5s", 179.5), ("850ms", 0.85),
                                           ("1h", 3600.0), ("скоро", None), ("", None)])
def test_wait_durations(text, seconds):
    from sapsr.llm.client import _seconds
    assert _seconds(text) == (pytest.approx(seconds) if seconds is not None else None)


# ── персональные данные ─────────────────────────────────────────────────────

TEXT = ("Работу выполнила Терешкова А. В., студентка гр. 321702. Руководитель — Н.В. Гракова. "
        "Отзыв Терешковой отправлен на anna.t@mail.ru и по телефону +375 (29) 123-45-67 или 8 029 765 43 21. "
        "В группе 321702 сдали все. Модель Qwen 3.5 обрабатывает PDF; рисунок А.1, ГОСТ 7.32-2017, 2020–2026 гг.")


@pytest.mark.typical
def test_redactor_removes_personal_data_and_restores_it():
    redactor = Redactor(names=["Терешкова", "Анна"], groups=["321702"])
    safe = redactor.redact(TEXT)
    for secret in ("Терешков", "Гракова", "anna.t@mail.ru", "123-45-67", "765 43 21", "321702"):
        assert secret not in safe, secret
    # Остальное — как было: модель должна понимать текст.
    for kept in ("Qwen 3.5", "PDF", "рисунок А.1", "ГОСТ 7.32-2017", "2020–2026"):
        assert kept in safe, kept
    assert re.search(r"\[ФИО-1\].*\[ФИО-2\]", safe) and "[ПОЧТА-1]" in safe and "[ТЕЛЕФОН-2]" in safe
    assert safe.count("[ГРУППА-1]") == 2, "одно значение — одна метка"
    assert redactor.restore(safe) == TEXT


def test_private_llm_sends_redacted_text_and_returns_real_quotes():
    seen: list[str] = []

    def stub(prompt_ref, system, user, schema):
        seen.append(user)
        quote = re.search(r"Работу выполнила [^,]+", user).group(0)
        return {"verdict": quote, "score": 1}

    client = PrivateLlm(LlmClient(LlmConfig(mode="stub"), stub=stub), Redactor(names=["Терешкова"]))
    result = client.complete_json("test.v1", "s", TEXT, Answer)
    assert "Терешкова" not in seen[0]
    assert result.data.verdict == "Работу выполнила Терешкова А. В."
    assert client.redactor.replaced >= 4


@pytest.mark.typical
def test_cloud_check_in_pipeline_is_marked_and_still_finds_contradiction(kb, directory):
    class CloudStub(LlmClient):
        @property
        def is_cloud(self):
            return True

    model = ScriptedModel()
    cloud = CloudStub(LlmConfig(mode="stub", model="big-model", provider="openai", allow_cloud=True,
                                base_url="https://llm.example.com/v1", api_key="k"), stub=model)
    registry = LlmRegistry.single(cloud)
    service = CheckService(kb, registry, directory, clock=lambda: NOW)
    pdf = HYBRID / "content-formats.pdf"
    task = make_task(pdf, student={"last_name": "Иванов", "group": "321702"})
    report = list(service.check(task, pdf))[-1].report
    assert any(w.code == "CLOUD_LLM" for w in report.pipeline.warnings)
    assert "pd_redacted" in report.pipeline.metrics
    assert next(m for m in report.pipeline.models if m.role == "llm").cloud is True
    # Цитаты сверены с настоящим текстом — противоречие найдено, как и локальной моделью.
    assert any(f.requirement_id == "CNT-CONSIST-002" for f in report.findings)
    assert all("321702" not in call["user"] for call in model.calls)
