#!/bin/sh
# Адрес мини-приложения задаётся в .env (NGROK_DOMAIN) и приходит сюда как BOT_WEBAPP_URL.
set -e

if [ -z "$BOT_WEBAPP_URL" ]; then
  echo "[entrypoint] ОШИБКА: BOT_WEBAPP_URL не задан. Укажи NGROK_DOMAIN в .env"
  exit 1
fi

# start-docker.bat ищет эту строку в логах, чтобы показать ссылку
echo "[entrypoint] WEBAPP_URL=$BOT_WEBAPP_URL"
exec java -jar /app/backend/app.jar
