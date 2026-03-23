import json
import time
import pika

from analyzer import analyze_pdf

RABBITMQ_HOST = "localhost"
RABBITMQ_PORT = 5672
RABBITMQ_USER = "rmq_user"
RABBITMQ_PASS = "rmq_password"

TASKS_QUEUE = "pdf_tasks_queue"
RESULTS_QUEUE = "pdf_results_queue"


def get_connection() -> pika.BlockingConnection:
    credentials = pika.PlainCredentials(RABBITMQ_USER, RABBITMQ_PASS)
    params = pika.ConnectionParameters(
        host=RABBITMQ_HOST,
        port=RABBITMQ_PORT,
        credentials=credentials,
        heartbeat=600,
    )
    return pika.BlockingConnection(params)


def on_message(channel, method, properties, body):
    print(f"\n{'='*50}")
    print(f"[WORKER] Получено сообщение из очереди '{TASKS_QUEUE}'")

    try:
        message = json.loads(body.decode("utf-8"))
        task_id = message.get("task_id")
        file_path = message.get("file_path")
        print(f"[WORKER] task_id={task_id}, file_path={file_path}")
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        print(f"[WORKER] Ошибка парсинга сообщения: {e}")
        channel.basic_ack(delivery_tag=method.delivery_tag)
        return

    if not file_path:
        print("[WORKER] file_path отсутствует — пропускаю задание")
        channel.basic_ack(delivery_tag=method.delivery_tag)
        return

    print(f"[WORKER] Начинаю анализ файла: {file_path}")
    start = time.time()
    result = analyze_pdf(file_path)
    elapsed = round(time.time() - start, 2)
    print(f"[WORKER] Анализ завершён за {elapsed} сек. Статус: {result['status']}")

    response = {
        "task_id": task_id,
        "status": result["status"],
        "errors": result["errors"],
    }

    channel.basic_publish(
        exchange="",
        routing_key=RESULTS_QUEUE,
        body=json.dumps(response, ensure_ascii=False),
        properties=pika.BasicProperties(delivery_mode=2),
    )
    print(f"[WORKER] Результат отправлен в очередь '{RESULTS_QUEUE}'")

    if result["errors"]:
        for err in result["errors"]:
            print(f"   ❌ Стр. {err['page']}: {err['message']}")
    else:
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
