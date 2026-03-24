@echo off
chcp 65001 >nul
title SAPSR — Остановка

echo Остановка Docker-контейнеров...
docker compose down
echo [OK] PostgreSQL и RabbitMQ остановлены.
echo.
echo Закройте оставшиеся окна (Backend, Worker, Frontend) вручную (Ctrl+C).
pause
