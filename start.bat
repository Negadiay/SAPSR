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

:: 1. Docker (PostgreSQL + RabbitMQ)
echo [1/4] Запуск PostgreSQL и RabbitMQ...
docker compose up -d
if errorlevel 1 (
    echo [ОШИБКА] Не удалось запустить Docker-контейнеры!
    pause
    exit /b 1
)
echo [OK] PostgreSQL (порт 5433) и RabbitMQ (порт 5672) запущены.
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

:: 2. Backend (Spring Boot)
echo [2/4] Запуск Backend (Spring Boot)...
start "SAPSR — Backend" cmd /k "cd /d %~dp0backend-java && mvnw.cmd spring-boot:run"
echo [OK] Backend запускается в отдельном окне (порт 8080).
echo.

:: Пауза, чтобы бэкенд успел подняться
echo Ожидание запуска Backend (15 сек)...
timeout /t 15 /nobreak >nul

:: 3. Python Worker
echo [3/4] Запуск Python Worker...
start "SAPSR — Worker" cmd /k "cd /d %~dp0worker-python && python main.py"
echo [OK] Worker запущен в отдельном окне.
echo.

:: 4. Frontend (Vite)
echo [4/4] Запуск Frontend (Vite)...
start "SAPSR — Frontend" cmd /k "cd /d %~dp0frontend-webapp && npm run dev"
echo [OK] Frontend запускается в отдельном окне (порт 5173).
echo.

echo ============================================
echo   Все сервисы запущены!
echo.
echo   Frontend:  http://localhost:5173
echo   Backend:   http://localhost:8080
echo   RabbitMQ:  http://localhost:15672
echo ============================================
echo.
echo Закройте это окно когда закончите работу.
echo Для остановки Docker: docker compose down
pause
