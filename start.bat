@echo off
chcp 65001 >nul
title SAPSR Launcher

echo ============================================
echo           SAPSR - Запуск проекта
echo ============================================
echo.

:: ---- 1. Docker (PostgreSQL + RabbitMQ) ----
echo [1/4] Запуск Docker (PostgreSQL + RabbitMQ)...
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
echo [2/4] Запуск Python Worker...
start "SAPSR - Python Worker" cmd /k "cd /d %~dp0worker-python && pip install -r requirements.txt && python main.py"
echo OK: Worker запущен в отдельном окне.
echo.

:: ---- 4. Java Backend (отдельное окно) ----
echo [3/4] Запуск Java Backend...
start "SAPSR - Java Backend" cmd /k "cd /d %~dp0backend-java && mvnw.cmd spring-boot:run"
echo OK: Backend запущен в отдельном окне.
echo.

:: ---- 5. Frontend (отдельное окно) ----
echo [4/4] Запуск Frontend...
start "SAPSR - Frontend" cmd /k "cd /d %~dp0frontend-webapp && npm install && npm run dev"
echo OK: Frontend запущен в отдельном окне.
echo.

echo ============================================
echo   Все компоненты запущены!
echo.
echo   Frontend:     http://localhost:5173
echo   Backend API:  http://localhost:8080
echo   RabbitMQ UI:  http://localhost:15672
echo ============================================
echo.
echo Это окно можно закрыть.
pause
