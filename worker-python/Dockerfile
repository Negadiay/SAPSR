FROM python:3.12-slim
# /app/backend нужен, чтобы путь вида /app/backend/../storage/файл.pdf, который присылает бэкенд, был валиден
WORKDIR /app/worker
RUN mkdir -p /app/backend /app/storage
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
CMD ["python", "-u", "main.py"]
