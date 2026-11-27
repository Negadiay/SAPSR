FROM python:3.12-slim
# /app/backend нужен, чтобы путь вида /app/backend/../storage/файл.pdf, который присылает бэкенд, был валиден
WORKDIR /app/worker
RUN mkdir -p /app/backend /app/storage
COPY requirements.txt .
# Повторы и тайм-аут — на случай медленной сети при первой сборке
RUN pip install --no-cache-dir --retries 10 --timeout 60 -r requirements.txt
COPY . .
CMD ["python", "-u", "main.py"]
