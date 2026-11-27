"""Подпись данных запуска Telegram Mini App (initData) тестовым токеном бота.

Тот же алгоритм, что проверяет бэкенд (TelegramInitDataInterceptor) и что описан в документации Telegram:
  secret_key = HMAC_SHA256(key="WebAppData", msg=<токен бота>)
  hash       = hex(HMAC_SHA256(key=secret_key, msg=data_check_string)),
где data_check_string — пары key=value всех полей, кроме hash, по алфавиту, через "\\n".

Нужна сквозным тестам (план Жерко, Ж1/Ж2): бэкенд в тестовом профиле запускается с тестовым токеном,
а тест подписывает им данные запуска. Никакого «тестового обхода» проверки в самом бэкенде нет.
Java-двойник: backend-java/src/test/java/com/sapsr/backend/testsupport/TelegramInitDataSigner.java.
Обе реализации сверяются с общим примером test-fixtures/telegram-init-data.json.
"""

import hashlib
import hmac
import json
import time
from typing import Mapping, Optional
from urllib.parse import quote, urlencode

# Фальшивый токен в формате настоящего; годится только для тестов
TEST_BOT_TOKEN = "123456789:AAtest-token-for-sapsr-tests-only000"


def data_check_string(fields: Mapping[str, str]) -> str:
    return "\n".join(f"{key}={fields[key]}" for key in sorted(fields) if key != "hash")


def compute_hash(fields: Mapping[str, str], bot_token: str) -> str:
    secret_key = hmac.new(b"WebAppData", bot_token.encode("utf-8"), hashlib.sha256).digest()
    return hmac.new(secret_key, data_check_string(fields).encode("utf-8"), hashlib.sha256).hexdigest()


def sign_fields(fields: Mapping[str, str], bot_token: str = TEST_BOT_TOKEN) -> str:
    """Подписывает готовый набор полей и возвращает строку initData (для заголовка Authorization)."""
    signed = dict(fields)
    signed.pop("hash", None)
    signed["hash"] = compute_hash(signed, bot_token)
    return urlencode(signed, quote_via=quote)


def sign_init_data(
    telegram_id: int,
    bot_token: str = TEST_BOT_TOKEN,
    first_name: str = "Тест",
    auth_date: Optional[int] = None,
    extra: Optional[Mapping[str, str]] = None,
) -> str:
    """initData пользователя с данным Telegram ID; auth_date по умолчанию — сейчас."""
    user = {"id": telegram_id, "first_name": first_name}
    fields = {
        "auth_date": str(int(time.time()) if auth_date is None else auth_date),
        "user": json.dumps(user, ensure_ascii=False, separators=(",", ":")),
    }
    if extra:
        fields.update(extra)
    return sign_fields(fields, bot_token)
