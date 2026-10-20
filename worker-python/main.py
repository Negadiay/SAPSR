import json
import os
import time
from pathlib import Path

import pika

from analyzer import analyze_pdf

# Подключение к RabbitMQ — только из окружения (docker-compose.yml берёт значения из .env).
RABBITMQ_HOST = os.environ.get("RABBITMQ_HOST", "localhost")
RABBITMQ_PORT = int(os.environ.get("RABBITMQ_PORT", "5672"))

# Корень хранилища файлов работ; в Docker — общий том /app/storage (docs/contracts/queue-v2.md, раздел 3)
STORAGE_ROOT_ENV = "SAPSR_STORAGE_ROOT"

TASKS_QUEUE = "pdf_tasks_queue"
RESULTS_QUEUE = "pdf_results_queue"

# Текст для студента при любом сбое проверки: без трассировок и путей на диске
INTERNAL_ERROR_MESSAGE = "Не удалось выполнить автоматическую проверку. Загрузите работу снова; если ошибка повторится, сообщите преподавателю."
FILE_NOT_FOUND_MESSAGE = "Файл работы не найден на сервере. Загрузите работу снова."


class TaskError(Exception):
    """Задание прочитано, но выполнить его нельзя; code — из протокола v2 (error.code)."""

    def __init__(self, code: str, message: str, detail: str = ""):
        super().__init__(detail or message)
        self.code = code
        self.message = message


def rabbitmq_credentials() -> pika.PlainCredentials:
    user = os.environ.get("RABBITMQ_USER")
    password = os.environ.get("RABBITMQ_PASSWORD")
    if not user or not password:
        raise SystemExit(
            "[WORKER] Не заданы RABBITMQ_USER и RABBITMQ_PASSWORD. "
            "В Docker они берутся из .env; при запуске вручную задай их в окружении."
        )
    return pika.PlainCredentials(user, password)


def get_connection() -> pika.BlockingConnection:
    params = pika.ConnectionParameters(
        host=RABBITMQ_HOST,
        port=RABBITMQ_PORT,
        credentials=rabbitmq_credentials(),
        heartbeat=600,
    )
    return pika.BlockingConnection(params)


def parse_task(body: bytes) -> dict:
    """Разбирает задание v1 или v2. ValueError — сообщение нельзя разобрать вовсе."""
    try:
        message = json.loads(body.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        raise ValueError(f"не JSON: {e}") from e
    if not isinstance(message, dict):
        raise ValueError("задание — не JSON-объект")
    if message.get("task_id") is None:
        raise ValueError("нет task_id")
    schema = message.get("schema")
    if schema not in (None, 2):  # без schema — прежний формат v1
        raise ValueError(f"незнакомое значение schema: {schema!r}")
    return message


def resolve_pdf_path(message: dict) -> str:
    """Путь к PDF: v2 — корень хранилища + file.storage_key; иначе (v1 и переходный период) — file_path."""
    file_ref = message.get("file") if isinstance(message.get("file"), dict) else {}
    storage_key = file_ref.get("storage_key")
    storage_root = os.environ.get(STORAGE_ROOT_ENV)

    if storage_key and storage_root:
        root = Path(storage_root).resolve()
        candidate = (root / storage_key).resolve()
        # Ключ из очереди — тоже входные данные: не даём выйти за корень хранилища
        if candidate != root and root not in candidate.parents:
            raise TaskError("INTERNAL", INTERNAL_ERROR_MESSAGE, f"storage_key выходит за корень хранилища: {storage_key!r}")
        if candidate.is_file():
            return str(candidate)
        if not message.get("file_path"):
            raise TaskError("FILE_NOT_FOUND", FILE_NOT_FOUND_MESSAGE, f"нет файла по storage_key {storage_key!r}")

    file_path = message.get("file_path")
    if not file_path:
        raise TaskError("FILE_NOT_FOUND", FILE_NOT_FOUND_MESSAGE, "в задании нет ни storage_key, ни file_path")
    return file_path


def failure_result(task_id, trace_id, code: str, message: str) -> dict:
    """Результат-сбой. Поля v2 (phase: failed, queue-v2.md, раздел 5) и v1 (status/errors) одновременно:
    бэкенд, который ещё понимает только v1, переведёт работу в REJECTED с понятным текстом,
    а бэкенд с приёмом v2 — в FAILED. Получатель игнорирует незнакомые поля."""
    return {
        "schema": 2,
        "task_id": task_id,
        "phase": "failed",
        "final": True,
        "error": {"code": code, "message": message},
        "trace_id": trace_id,
        # v1 — до шага 2 перехода (queue-v2.md, раздел 8)
        "status": "FAIL",
        "errors": [{"severity": "critical", "page": None, "message": message}],
    }


def process_task(message: dict) -> dict:
    """Выполняет задание и всегда возвращает результат: при любой ошибке — результат-сбой."""
    task_id = message.get("task_id")
    trace_id = message.get("trace_id")
    try:
        pdf_path = resolve_pdf_path(message)
        print(f"[WORKER] task_id={task_id} trace_id={trace_id}: начинаю анализ")
        start = time.time()
        result = analyze_pdf(pdf_path)
        elapsed = round(time.time() - start, 2)
        print(f"[WORKER] task_id={task_id}: анализ завершён за {elapsed} сек. Статус: {result['status']}")
        # Обычный результат — пока в формате v1 (queue-v2.md, раздел 8)
        return {"task_id": task_id, "status": result["status"], "errors": result["errors"]}
    except TaskError as e:
        print(f"[WORKER] task_id={task_id} trace_id={trace_id}: сбой {e.code}: {e}")
        return failure_result(task_id, trace_id, e.code, e.message)
    except Exception as e:  # noqa: BLE001 — обработчик не должен падать ни на каком документе
        print(f"[WORKER] task_id={task_id} trace_id={trace_id}: непредвиденная ошибка {type(e).__name__}: {e}")
        return failure_result(task_id, trace_id, "INTERNAL", INTERNAL_ERROR_MESSAGE)


def on_message(channel, method, properties, body):
    print(f"\n{'='*50}")
    print(f"[WORKER] Получено сообщение из очереди '{TASKS_QUEUE}'")

    try:
        message = parse_task(body)
    except ValueError as e:
        # Без возврата в очередь: с политикой DLX оно уйдёт в очередь недоставленных (queue-v2.md, раздел 7)
        print(f"[WORKER] Сообщение нельзя разобрать ({e}) — отклоняю")
        channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
        return

    response = process_task(message)

    channel.basic_publish(
        exchange="",
        routing_key=RESULTS_QUEUE,
        body=json.dumps(response, ensure_ascii=False),
        # Без content_type: слушатель бэкенда читает результат строкой (см. RabbitMQConfig)
        properties=pika.BasicProperties(delivery_mode=2),
    )
    print(f"[WORKER] Результат отправлен в очередь '{RESULTS_QUEUE}'")

    for err in response.get("errors") or []:
        print(f"   [{str(err.get('severity', '?')).upper()}] стр.{err.get('page')}: {err.get('message')}")
    if not response.get("errors"):
        print("   ✅ Документ соответствует требованиям")

    channel.basic_ack(delivery_tag=method.delivery_tag)
    print(f"{'='*50}")


def main():
    print("[WORKER] Подключаюсь к RabbitMQ...")
    connection = get_connection()
    channel = connection.channel()

    channel.queue_declare(queue=TASKS_QUEUE, durable=True)
    channel.queue_declare(queue=RESULTS_QUEUE, durable=True)
    print(f"[WORKER] Очереди '{TASKS_QUEUE}' и '{RESULTS_QUEUE}' готовы")

    channel.basic_qos(prefetch_count=1)
    channel.basic_consume(queue=TASKS_QUEUE, on_message_callback=on_message)

    print(f"[WORKER] Жду задания в очереди '{TASKS_QUEUE}'... (Ctrl+C для выхода)")
    print(f"{'='*50}")

    try:
        channel.start_consuming()
    except KeyboardInterrupt:
        print("\n[WORKER] Остановка...")
        channel.stop_consuming()
    finally:
        connection.close()
        print("[WORKER] Соединение закрыто. До свидания!")


if __name__ == "__main__":
    main()
