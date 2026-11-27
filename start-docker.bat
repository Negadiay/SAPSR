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

rem Без блока в скобках: внутри него %TOKEN% подставился бы до ввода и токен не записался бы
if exist ".env" goto ENV_OK
copy ".env.example" ".env" >nul
echo Первый запуск: нужен токен бота.
set /p TOKEN=Вставь токен от @BotFather и нажми Enter: 
powershell -NoProfile -Command "$u=New-Object Text.UTF8Encoding $false; $c=[IO.File]::ReadAllLines('.env',$u) -replace '^TELEGRAM_BOT_TOKEN=.*', 'TELEGRAM_BOT_TOKEN=%TOKEN%'; [IO.File]::WriteAllLines('.env',$c,$u)"
:ENV_OK

findstr /r /b /c:"NGROK_AUTHTOKEN=." ".env" >nul
if %errorlevel% equ 0 goto NGROK_OK
echo.
echo Нужны данные ngrok (https://dashboard.ngrok.com): authtoken и бесплатный постоянный адрес (Domains).
set /p NG_TOKEN=Вставь ngrok authtoken: 
set /p NG_DOMAIN=Вставь адрес (например name.ngrok-free.app, без https://): 
set "NG_DOMAIN=%NG_DOMAIN:https://=%"
>> ".env" echo.
>> ".env" echo NGROK_AUTHTOKEN=%NG_TOKEN%
>> ".env" echo NGROK_DOMAIN=%NG_DOMAIN%
:NGROK_OK

rem Пароли базы и очереди: если в .env их нет или они пустые - дописываем случайные
call :ENSURE_SECRET POSTGRES_PASSWORD
call :ENSURE_SECRET RABBITMQ_PASSWORD
call :READ_ENV POSTGRES_USER sapsr_user
call :READ_ENV POSTGRES_PASSWORD
call :READ_ENV RABBITMQ_USER rmq_user
call :READ_ENV RABBITMQ_PASSWORD

rem База и очередь могли быть созданы раньше со старыми паролями (они хранятся в томах).
rem Приводим пароли к значениям из .env, иначе бэкенд и обработчик не подключатся.
echo Запуск базы и очереди...
docker compose up -d --wait postgres-db rabbitmq
if %errorlevel% neq 0 (
    echo ОШИБКА запуска базы или очереди. Смотри сообщения выше.
    pause
    exit /b 1
)
docker compose exec -T postgres-db psql -q -U "%POSTGRES_USER%" -d sapsr_db -c "ALTER USER %POSTGRES_USER% PASSWORD '%POSTGRES_PASSWORD%'" >nul
if %errorlevel% neq 0 echo ВНИМАНИЕ: не удалось обновить пароль базы. Если бэкенд не подключается - смотри README, раздел про пароли.
docker compose exec -T rabbitmq rabbitmqctl -q change_password "%RABBITMQ_USER%" "%RABBITMQ_PASSWORD%" >nul
if %errorlevel% neq 0 echo ВНИМАНИЕ: не удалось обновить пароль очереди. Если обработчик не подключается - смотри README, раздел про пароли.

echo Сборка и запуск контейнеров (первый раз - несколько минут)...
docker compose up -d --build --remove-orphans
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
exit /b 0

rem ---- Подпрограммы ----

rem Если переменной %1 в .env нет или она пустая - записать случайный пароль (32 шестнадцатеричных символа)
:ENSURE_SECRET
findstr /r /b /c:"%~1=." ".env" >nul
if %errorlevel% equ 0 exit /b 0
powershell -NoProfile -Command "$k='%~1'; $v=[guid]::NewGuid().ToString('N'); $u=New-Object Text.UTF8Encoding $false; $c=@([IO.File]::ReadAllLines('.env',$u)); if ($c -match ('^'+$k+'=')) { $c=$c -replace ('^'+$k+'=.*$'), ($k+'='+$v) } else { $c+=($k+'='+$v) }; [IO.File]::WriteAllLines('.env',$c,$u)"
echo В .env записан случайный %~1
exit /b 0

rem Прочитать переменную %1 из .env в окружение; %2 - значение по умолчанию
:READ_ENV
set "%~1=%~2"
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do if "%%A"=="%~1" if not "%%B"=="" set "%~1=%%B"
exit /b 0
