"""Подпись данных запуска Telegram: совпадает с общим примером, который проверяет и Java."""

import json
from urllib.parse import parse_qsl

import pytest

from telegram_init_data import TEST_BOT_TOKEN, compute_hash, sign_fields, sign_init_data


@pytest.fixture
def vector(fixtures_dir):
    return json.loads((fixtures_dir / "telegram-init-data.json").read_text(encoding="utf-8"))


@pytest.mark.typical
def test_hash_matches_shared_vector(vector):
    assert compute_hash(vector["fields"], vector["bot_token"]) == vector["hash"]


@pytest.mark.typical
def test_signed_string_roundtrip(vector):
    fields = dict(parse_qsl(sign_fields(vector["fields"], vector["bot_token"])))
    assert fields.pop("hash") == vector["hash"]
    assert fields == vector["fields"]


@pytest.mark.negative
def test_other_token_gives_other_hash(vector):
    assert compute_hash(vector["fields"], "987654321:AAanother-token") != vector["hash"]


@pytest.mark.typical
def test_sign_init_data_contains_user_id():
    fields = dict(parse_qsl(sign_init_data(100500, auth_date=1760000000)))
    assert json.loads(fields["user"])["id"] == 100500
    assert fields["hash"] == compute_hash({k: v for k, v in fields.items() if k != "hash"}, TEST_BOT_TOKEN)
