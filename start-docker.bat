@echo off
chcp 65001 >nul
title SAPSR Docker
cd /d "%~dp0"

echo ============================================
echo        SAPSR - запуск через Docker
echo ============================================

docker info >nul 2>nul
if %errorlevel% neq 0 (
    echo ОШИБКА: Docker не запущен. Открой Docker Desktop и повтори.
    pause
    exit /b 1
)

if not exist ".env" (
    copy ".env.example" ".env" >nul
    echo Первый запуск: нужен токен бота.
    set /p TOKEN=Вставь токен от @BotFather и нажми Enter: 
    powershell -NoProfile -Command "(Get-Content '.env') -replace '^TELEGRAM_BOT_TOKEN=.*', 'TELEGRAM_BOT_TOKEN=%TOKEN%' | Set-Content '.env'"
)

echo Сборка и запуск контейнеров (первый раз - несколько минут)...
docker compose up -d --build
if %errorlevel% neq 0 (
    echo ОШИБКА запуска. Смотри сообщения выше.
    pause
    exit /b 1
)

echo Жду ссылку Telegram Mini App...
:WAIT
timeout /t 3 >nul
for /f "tokens=2 delims==" %%A in ('docker compose logs backend 2^>nul ^| findstr /c:"WEBAPP_URL="') do set "WEBAPP_URL=%%A"
if not defined WEBAPP_URL goto WAIT

echo.
echo ============================================
echo   Telegram Mini App: %WEBAPP_URL%
echo   Сайт:        http://localhost:5173
echo   Backend API: http://localhost:8080
echo   RabbitMQ UI: http://localhost:15672
echo   Остановить:  docker compose down
echo   Логи:        docker compose logs -f
echo ============================================
pause
