#!/bin/sh
# Если BOT_WEBAPP_URL не задан — ждём временную ссылку от Cloudflare Tunnel и подставляем её.
set -e

if [ -z "$BOT_WEBAPP_URL" ]; then
  echo "[entrypoint] BOT_WEBAPP_URL не задан, жду ссылку от Cloudflare Tunnel..."
  i=0
  while [ $i -lt 90 ]; do
    HOST=$(curl -s http://tunnel:2000/quicktunnel 2>/dev/null | sed -n 's/.*"hostname":"\([^"]*\)".*/\1/p')
    if [ -n "$HOST" ]; then
      export BOT_WEBAPP_URL="https://$HOST"
      break
    fi
    i=$((i+1)); sleep 2
  done
fi

if [ -z "$BOT_WEBAPP_URL" ]; then
  echo "[entrypoint] ОШИБКА: не удалось получить ссылку туннеля. Задай BOT_WEBAPP_URL в .env"
  exit 1
fi

echo "[entrypoint] WEBAPP_URL=$BOT_WEBAPP_URL"
exec java -jar /app/backend/app.jar
