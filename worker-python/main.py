"""Обработчик очереди pdf_tasks_queue (docs/contracts/queue-v2.md).

SAPSR_QUEUE_PROTOCOL=2 (в docker-compose.yml по умолчанию) — сообщения о ходе проверки в
pdf_progress_queue и результат в две фазы (format, content) или фаза failed.
SAPSR_QUEUE_PROTOCOL=1 (по умолчанию вне Docker) — результат одним сообщением
{task_id, status, errors}. Бэкенд с 23.10.2026 принимает обе версии (задача 5.2).

Любая ошибка при проверке превращается в результат со сбоем, а задание подтверждается:
оно не возвращается в очередь и не роняет обработчик снова. Сообщение, которое нельзя
разобрать, отклоняется без возврата в очередь (basic_reject, requeue=False).
"""

from __future__ import annotations

import json
import logging
import time
from typing import Optional

import pika
from pika.exceptions import AMQPConnectionError

from sapsr.checks.engine import validate_rules
from sapsr.directories import make_directory
from sapsr.errors import FAILURE_MESSAGES, CheckFailure
from sapsr.kb.loader import load_kb
from sapsr.llm.client import LlmClient
from sapsr.log import setup_logging, task_context
from sapsr.pipeline import STAGES, CheckService
from sapsr.report.legacy import failure_v1, to_v1
from sapsr.settings import Settings
from sapsr.storage import locate_file
from sapsr.task import InvalidTask, Task, parse_task

log = logging.getLogger("sapsr.worker")


class Publisher:
    """Отправка сообщений обработчика. Тело — JSON в UTF-8, сообщения сохраняются на диск."""

    def __init__(self, channel, settings: Settings):
        self.channel = channel
        self.settings = settings

    def _send(self, queue: str, payload: dict) -> None:
        self.channel.basic_publish(
            exchange="",
            routing_key=queue,
            body=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            properties=pika.BasicProperties(delivery_mode=2, content_type="application/json",
                                            content_encoding="utf-8"),
        )

    def progress(self, task: Task, stage: str, index: int) -> None:
        self._send(self.settings.progress_queue, {
            "schema": 2, "task_id": task.task_id, "stage": stage, "index": index,
            "total": len(STAGES), "trace_id": task.trace_id,
        })

    def phase(self, task: Task, phase: str, passed: bool, final: bool, report: dict) -> None:
        self._send(self.settings.results_queue, {
            "schema": 2, "task_id": task.task_id, "phase": phase, "passed": passed, "final": final,
            "report": report, "trace_id": task.trace_id,
        })

    def failed(self, task: Task, code: str, message: str) -> None:
        self._send(self.settings.results_queue, {
            "schema": 2, "task_id": task.task_id, "phase": "failed", "final": True,
            "error": {"code": code, "message": message}, "trace_id": task.trace_id,
        })

    def result_v1(self, task: Task, result: dict) -> None:
        self._send(self.settings.results_queue, {
            "task_id": task.task_id, "status": result["status"], "errors": result["errors"],
        })


class Worker:
    def __init__(self, settings: Settings, service: CheckService):
        self.settings = settings
        self.service = service

    def handle(self, body: bytes, publisher: Publisher) -> str:
        """Обрабатывает одно сообщение. Возвращает «ack» или «reject»."""
        try:
            task = parse_task(body)
        except InvalidTask as exc:
            log.warning("сообщение отклонено", extra={"fields": {"reason": str(exc)}})
            return "reject"
        with task_context(task.task_id, task.trace_id):
            started = time.perf_counter()
            log.info("задание получено", extra={"fields": {
                "schema": task.schema_, "source_format": task.file.source_format,
                "work_type": task.profile.work_type, "semester": task.profile.semester,
                "stage": task.profile.stage, "has_storage_key": bool(task.file.storage_key)}})
            if self.settings.queue_protocol == 2:
                self._run_v2(task, publisher)
            else:
                self._run_v1(task, publisher)
            log.info("задание обработано", extra={"fields": {"ms": int((time.perf_counter() - started) * 1000)}})
        return "ack"

    def _run_v1(self, task: Task, publisher: Publisher) -> None:
        try:
            path = locate_file(task, self.settings.storage_root)
            report = None
            for phase in self.service.check(task, path):
                report = phase.report
            result = to_v1(report)
        except CheckFailure as failure:
            log.warning("проверка не выполнена", extra={"fields": {"code": failure.code, "detail": failure.detail}})
            result = failure_v1(failure.user_message, failure.code)
        except Exception:
            log.exception("внутренняя ошибка проверки")
            result = failure_v1(FAILURE_MESSAGES["INTERNAL"], "INTERNAL")
        publisher.result_v1(task, result)
        log.info("результат отправлен", extra={"fields": {"protocol": 1, "status": result["status"],
                                                          "findings": len(result["errors"])}})

    def _run_v2(self, task: Task, publisher: Publisher) -> None:
        try:
            path = locate_file(task, self.settings.storage_root)
            for phase in self.service.check(task, path, progress=lambda stage, i: publisher.progress(task, stage, i)):
                publisher.phase(task, phase.phase, phase.passed, phase.final, phase.report.to_json_dict())
                log.info("фаза отправлена", extra={"fields": {"phase": phase.phase, "passed": phase.passed,
                                                              "final": phase.final,
                                                              "findings": len(phase.report.findings)}})
        except CheckFailure as failure:
            log.warning("проверка не выполнена", extra={"fields": {"code": failure.code, "detail": failure.detail}})
            publisher.failed(task, failure.code, failure.user_message)
        except Exception:
            log.exception("внутренняя ошибка проверки")
            publisher.failed(task, "INTERNAL", FAILURE_MESSAGES["INTERNAL"])

    def on_message(self, channel, method, properties, body) -> None:
        outcome = self.handle(body, Publisher(channel, self.settings))
        if outcome == "reject":
            channel.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
        else:
            channel.basic_ack(delivery_tag=method.delivery_tag)


def build_worker(settings: Settings) -> Worker:
    kb = load_kb(settings.kb_dir, validate_rules=validate_rules)
    llm: Optional[LlmClient] = LlmClient(settings.llm) if settings.llm.mode != "off" else None
    service = CheckService(kb, llm, make_directory(settings.iis_mode), mode=settings.mode,
                           max_pages=settings.max_pages, task_timeout_s=settings.task_timeout_s)
    log.info("обработчик готов", extra={"fields": {
        "protocol": settings.queue_protocol, "mode": settings.mode, "kb_version": kb.version,
        "llm_mode": settings.llm.mode, "llm_model": settings.llm.model, "llm_provider": settings.llm.provider,
        "llm_cloud": bool(llm and llm.is_cloud), "llm_cloud_allowed": settings.llm.allow_cloud,
        "iis": settings.iis_mode}})
    if llm is not None and llm.is_cloud:
        if settings.llm.allow_cloud:
            log.warning("включена облачная языковая модель: текст работ уходит стороннему сервису")
        else:
            log.warning("адрес модели не локальный, облачная модель не разрешена — содержательная проверка выключена")
    return Worker(settings, service)


def connect(settings: Settings) -> pika.BlockingConnection:
    params = pika.ConnectionParameters(
        host=settings.rabbitmq_host,
        port=settings.rabbitmq_port,
        credentials=pika.PlainCredentials(settings.rabbitmq_user, settings.rabbitmq_password),
        # Проверка с моделью идёт минуты: тайм-аут сердцебиения больше общего тайм-аута проверки.
        heartbeat=max(600, int(settings.task_timeout_s) + 60),
    )
    return pika.BlockingConnection(params)


def main() -> None:
    settings = Settings.from_env()
    if not settings.rabbitmq_user or not settings.rabbitmq_password:
        raise SystemExit(
            "[WORKER] Не заданы RABBITMQ_USER и RABBITMQ_PASSWORD. "
            "В Docker они берутся из .env; при запуске вручную задай их в окружении."
        )
    setup_logging(settings.log_format, settings.log_level)
    worker = build_worker(settings)
    delay = 2
    while True:
        try:
            connection = connect(settings)
        except AMQPConnectionError:
            log.warning("RabbitMQ недоступен, повтор", extra={"fields": {"retry_in_s": delay}})
            time.sleep(delay)
            delay = min(delay * 2, 30)
            continue
        delay = 2
        channel = connection.channel()
        channel.queue_declare(queue=settings.tasks_queue, durable=True)
        channel.queue_declare(queue=settings.results_queue, durable=True)
        if settings.queue_protocol == 2:
            channel.queue_declare(queue=settings.progress_queue, durable=True)
        channel.basic_qos(prefetch_count=1)
        channel.basic_consume(queue=settings.tasks_queue, on_message_callback=worker.on_message)
        log.info("жду задания", extra={"fields": {"queue": settings.tasks_queue}})
        try:
            channel.start_consuming()
        except KeyboardInterrupt:
            log.info("остановка по Ctrl+C")
            try:
                channel.stop_consuming()
                connection.close()
            except Exception:
                pass
            return
        except AMQPConnectionError:
            log.warning("соединение с RabbitMQ потеряно, переподключаюсь")
            continue


if __name__ == "__main__":
    main()
