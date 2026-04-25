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

:: ---- 3. Ожидание RabbitMQ ----
echo Ожидание запуска RabbitMQ (20 сек)...
timeout /t 20 >nul

:: ---- 4. Python Worker (отдельное окно) ----
echo [2/5] Запуск Python Worker...
start "SAPSR - Python Worker" cmd /k "cd /d %~dp0worker-python && pip install -r requirements.txt && python main.py"
echo OK: Worker запущен в отдельном окне.
echo.

:: ---- 5. Java Backend (отдельное окно) ----
echo [3/5] Запуск Java Backend...
start "SAPSR - Java Backend" cmd /k "cd /d %~dp0backend-java && mvnw.cmd spring-boot:run"
echo OK: Backend запущен в отдельном окне.
echo.

:: ---- 6. Frontend (отдельное окно) ----
echo [4/5] Запуск Frontend (Vite dev server)...
start "SAPSR - Frontend" cmd /k "cd /d %~dp0frontend-webapp && npm install && npm run dev"
echo OK: Frontend запущен в отдельном окне.
echo.

:: ---- 7. Ожидание Frontend ----
echo [5/5] Ожидание запуска Frontend (8 сек)...
timeout /t 8 >nul

:: ---- 8. Cloudflare Tunnel — запуск и перехват ссылки ----
echo Запуск Cloudflare Tunnel...

where cloudflared >nul 2>nul
if %errorlevel% neq 0 (
    echo cloudflared.exe не найден в PATH!
    echo.
    echo Скачай его:
    echo   https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
    echo.
    pause
    exit /b 1
)

:: Запускаем cloudflared и пишем вывод в файл
set TUNNEL_LOG=%~dp0tunnel_output.log
if exist "%TUNNEL_LOG%" del "%TUNNEL_LOG%"

start "SAPSR - Cloudflare Tunnel" cmd /k "cloudflared tunnel --protocol http2 --url http://localhost:5173 > "%TUNNEL_LOG%" 2>&1 & type "%TUNNEL_LOG%""

:: ---- 9. Ждём появления ссылки в логе ----
echo Ожидание HTTPS-ссылки от Cloudflare...
set WEBAPP_URL=

:WAIT_URL
timeout /t 2 >nul
if not exist "%TUNNEL_LOG%" goto WAIT_URL

:: Ищем строку с trycloudflare.com в логе
for /f "tokens=*" %%A in ('findstr /i "trycloudflare.com" "%TUNNEL_LOG%" 2^>nul') do (
    set "LINE=%%A"
)

:: Вытаскиваем URL из строки через PowerShell
if defined LINE (
    for /f "delims=" %%B in ('powershell -NoProfile -Command "if ('%LINE%' -match '(https://[^\s]+trycloudflare\.com)') { $matches[1] }"') do (
        set "WEBAPP_URL=%%B"
    )
)

if not defined WEBAPP_URL goto WAIT_URL

echo.
echo ============================================
echo   Получена ссылка: %WEBAPP_URL%
echo ============================================
echo.

:: ---- 10. Обновляем application.properties ----
echo Обновляю bot.webapp.url в application.properties...

set PROPS_FILE=%~dp0backend-java\src\main\resources\application.properties

powershell -NoProfile -Command ^
    "(Get-Content '%PROPS_FILE%') -replace 'bot\.webapp\.url=.*', 'bot.webapp.url=%WEBAPP_URL%' | Set-Content '%PROPS_FILE%'"

echo OK: bot.webapp.url обновлён.
echo.

:: ---- 11. Перезапуск Java Backend ----
echo Перезапуск Java Backend с новым URL...

:: Останавливаем процесс на порту 8080
echo Освобождаю порт 8080...
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo Завершаю PID %%P...
    taskkill /PID %%P /F >nul 2>nul
)

:: Ждём пока Telegram поймёт что бот отключился (иначе 409 Conflict)
echo Ожидание освобождения порта и сброса сессии Telegram (10 сек)...
timeout /t 10 >nul

:: Запускаем заново
start "SAPSR - Java Backend" cmd /k "cd /d %~dp0backend-java && mvnw.cmd spring-boot:run"
echo OK: Backend перезапущен.
echo.

echo ============================================
echo   ВСЁ ЗАПУЩЕНО И НАСТРОЕНО АВТОМАТИЧЕСКИ!
echo.
echo   Frontend:      http://localhost:5173
echo   Backend API:   http://localhost:8080
echo   RabbitMQ UI:   http://localhost:15672
echo   Telegram URL:  %WEBAPP_URL%
echo ============================================
echo.
pause