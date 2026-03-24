@echo off
chcp 65001 >nul
title SAPSR Launcher

echo ============================================
echo           SAPSR - Запуск проекта
echo ============================================
echo.

:: ---- 1. Docker (PostgreSQL + RabbitMQ) ----
echo [1/5] Запуск Docker (PostgreSQL + RabbitMQ)...
docker compose up -d
if %errorlevel% neq 0 (
    echo ОШИБКА: Docker не запущен! Открой Docker Desktop и попробуй снова.
    pause
    exit /b 1
)
echo OK: Контейнеры запущены.
echo.

:: ---- 2. Копирование application.properties если нет ----
if not exist "backend-java\src\main\resources\application.properties" (
    echo [!] Создаю application.properties из примера...
    copy "backend-java\src\main\resources\application.properties.example" "backend-java\src\main\resources\application.properties" >nul
    echo ВНИМАНИЕ: Открой backend-java\src\main\resources\application.properties
    echo           и замени ТОКЕН на реальный токен бота от @BotFather!
    echo.
    pause
)

:: ---- 3. Python Worker (отдельное окно) ----
echo [2/5] Запуск Python Worker...
start "SAPSR - Python Worker" cmd /k "cd /d %~dp0worker-python && pip install -r requirements.txt && python main.py"
echo OK: Worker запущен в отдельном окне.
echo.

:: ---- 4. Java Backend (отдельное окно) ----
echo [3/5] Запуск Java Backend...
start "SAPSR - Java Backend" cmd /k "cd /d %~dp0backend-java && mvnw.cmd spring-boot:run"
echo OK: Backend запущен в отдельном окне.
echo.

:: ---- 5. Frontend (отдельное окно) ----
echo [4/5] Запуск Frontend (Vite dev server)...
start "SAPSR - Frontend" cmd /k "cd /d %~dp0frontend-webapp && npm install && npm run dev"
echo OK: Frontend запущен в отдельном окне.
echo.

:: ---- 6. Cloudflare Tunnel (HTTPS для Telegram) ----
echo [5/5] Ожидание запуска Frontend (8 сек)...
timeout /t 8 >nul

echo Запуск Cloudflare Tunnel...
echo.
echo ============================================
echo   HTTPS-ссылка появится в окне туннеля!
echo   Скопируй её и вставь в:
echo     1. @BotFather -> /mybots -> Bot Settings
echo        -> Menu Button -> Edit URL
echo     2. application.properties -> bot.webapp.url
echo ============================================
echo.

where cloudflared >nul 2>nul
if %errorlevel% neq 0 (
    echo cloudflared.exe не найден в PATH!
    echo.
    echo Скачай его:
    echo   https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
    echo.
    echo Или положи cloudflared.exe в папку проекта и запусти вручную:
    echo   cloudflared.exe tunnel --url http://localhost:5173
    echo.
    pause
    exit /b 1
)

start "SAPSR - Cloudflare Tunnel" cmd /k "cloudflared tunnel --url http://localhost:5173"

echo ============================================
echo   ВСЁ ЗАПУЩЕНО!
echo.
echo   Frontend:      http://localhost:5173
echo   Backend API:   http://localhost:8080
echo   RabbitMQ UI:   http://localhost:15672
echo   Telegram URL:  смотри окно Cloudflare Tunnel
echo.
echo   После получения HTTPS-ссылки от Cloudflare:
echo   обнови bot.webapp.url в application.properties
echo   и перезапусти Java Backend.
echo ============================================
echo.
pause
