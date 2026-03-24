@echo off
chcp 65001 >nul
title SAPSR — Запуск всех сервисов

echo ============================================
echo         SAPSR — Запуск всех сервисов
echo ============================================
echo.

:: Проверка Docker
docker info >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Docker не запущен! Запустите Docker Desktop и попробуйте снова.
    pause
    exit /b 1
)

:: Проверка application.properties
if not exist "backend-java\src\main\resources\application.properties" (
    echo [ОШИБКА] Файл application.properties не найден!
    echo Скопируйте application.properties.example и заполните настройки:
    echo   copy backend-java\src\main\resources\application.properties.example backend-java\src\main\resources\application.properties
    pause
    exit /b 1
)

:: Проверка cloudflared
set USE_TUNNEL=0
cloudflared --version >nul 2>&1
if not errorlevel 1 set USE_TUNNEL=1

if "%USE_TUNNEL%"=="0" (
    echo [ПРЕДУПРЕЖДЕНИЕ] cloudflared не найден! Туннель не будет запущен.
    echo Установите: winget install cloudflare.cloudflared
    echo.
)

:: 1. Docker
echo [1/5] Запуск PostgreSQL и RabbitMQ...
docker compose up -d
if errorlevel 1 (
    echo [ОШИБКА] Не удалось запустить Docker-контейнеры!
    pause
    exit /b 1
)
echo [OK] PostgreSQL и RabbitMQ запущены.
echo.

:: Ждём готовности PostgreSQL
echo Ожидание готовности PostgreSQL...
:wait_pg
docker exec sapsr-postgres pg_isready -U sapsr_user >nul 2>&1
if errorlevel 1 (
    timeout /t 2 /nobreak >nul
    goto wait_pg
)
echo [OK] PostgreSQL готов.
echo.

:: 2. Frontend
echo [2/5] Запуск Frontend...
start "SAPSR — Frontend" cmd /k "cd /d %~dp0frontend-webapp && npm run dev"
echo [OK] Frontend запускается на порту 5173.
echo.

:: 3. Cloudflare Tunnel
if "%USE_TUNNEL%"=="0" goto skip_tunnel

echo [3/5] Запуск Cloudflare Tunnel...
start "SAPSR — Cloudflare Tunnel" cmd /k "cloudflared tunnel --url http://localhost:5173"
echo [OK] Туннель запускается в отдельном окне.
echo.
echo ============================================
echo  Скопируйте HTTPS-ссылку из окна туннеля
echo  и вставьте её ниже:
echo ============================================
echo.
set /p TUNNEL_URL="Вставьте URL туннеля: "

if not "%TUNNEL_URL%"=="" (
    powershell -Command "(Get-Content 'backend-java\src\main\resources\application.properties') -replace 'bot\.webapp\.url=.*', 'bot.webapp.url=%TUNNEL_URL%' | Set-Content 'backend-java\src\main\resources\application.properties'"
    echo [OK] application.properties обновлён: bot.webapp.url=%TUNNEL_URL%
    echo.
)
goto done_tunnel

:skip_tunnel
echo [3/5] Cloudflare Tunnel пропущен - cloudflared не установлен.
echo.

:done_tunnel

:: 4. Backend
echo [4/5] Запуск Backend...
start "SAPSR — Backend" cmd /k "cd /d %~dp0backend-java && mvnw.cmd spring-boot:run"
echo [OK] Backend запускается в отдельном окне.
echo.

echo Ожидание запуска Backend, 15 сек...
timeout /t 15 /nobreak >nul

:: 5. Python Worker
echo [5/5] Запуск Python Worker...
start "SAPSR — Worker" cmd /k "cd /d %~dp0worker-python && python main.py"
echo [OK] Worker запущен.
echo.

echo ============================================
echo   Все сервисы запущены!
echo.
echo   Frontend:     http://localhost:5173
echo   Backend:      http://localhost:8080
echo   RabbitMQ UI:  http://localhost:15672
if "%USE_TUNNEL%"=="1" (
    echo   Tunnel:       %TUNNEL_URL%
)
echo.
echo   Menu Button и Открыть кабинет обновлены
echo   автоматически при старте Backend.
echo ============================================
echo.
echo Для остановки: закройте все окна или используйте stop.bat
pause
