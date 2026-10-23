# Полная проверка SAPSR на машине с Docker Desktop (план Жерко, Ж1/Ж2/Ж6).
#   powershell -ExecutionPolicy Bypass -File scripts\check.ps1            — всё
#   powershell -ExecutionPolicy Bypass -File scripts\check.ps1 -Skip unit  — без модульных тестов
#   powershell -ExecutionPolicy Bypass -Command "& .\scripts\check.ps1 -Skip unit,bat"  — несколько частей:
#   с -File список через запятую приходит одной строкой
# Что делает:
#   unit     — pytest обработчика в python:3.12 (те же версии, что в образе); тесты бэкенда в Maven-контейнере
#              (Testcontainers ходят в Docker Desktop); gitleaks по всей истории
#   bat      — подпрограммы start-docker.bat на временном .env (генерация и чтение паролей)
#   stack    — отдельный проект sapsr-check: сборка образов, запуск базы, очереди, обработчика и бэкенда
#              с фальшивым токеном; загрузка PDF через API с подписанными данными запуска; сбойные задания;
#              смена паролей в уже созданных базе и очереди — как это делает start-docker.bat
# Рабочий состав, его тома и .env не трогаются. Ни одно сообщение в Telegram не отправляется.
# Итог печатается в конце и пишется в check-result.log в корне репозитория.

param([string[]]$Skip = @())

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
# Без BOM. Windows PowerShell 5.1 пишет данные во внешнюю программу (rabbitmqadmin в Publish-Task)
# в кодировке [Console]::InputEncoding, и при консоли в UTF-8 (кодовая страница 65001) ставит BOM —
# обработчик отклоняет такое задание как «не JSON». $OutputEncoding — то же для PowerShell 7.
[Console]::InputEncoding = New-Object Text.UTF8Encoding $false
$OutputEncoding = New-Object Text.UTF8Encoding $false

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Log = Join-Path $Root 'check-result.log'
[IO.File]::WriteAllText($Log, "SAPSR check $(Get-Date -Format s) on $env:COMPUTERNAME`n", [Text.Encoding]::UTF8)
$Results = New-Object System.Collections.ArrayList

function Log([string]$line) {
    Write-Host $line
    [IO.File]::AppendAllText($Log, $line + "`n", [Text.Encoding]::UTF8)
}
# Запуск внешней команды с выводом в консоль и лог; возвращает код выхода
function Run {
    Log ("> " + ($args -join ' '))
    $exe = $args[0]; $rest = @(); if ($args.Count -gt 1) { $rest = $args[1..($args.Count - 1)] }
    & $exe @rest 2>&1 | ForEach-Object { Log ("  " + $_) }
    return $LASTEXITCODE
}
function Result([string]$name, [bool]$ok, [string]$detail = '') {
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    [void]$Results.Add("$mark  $name  $detail")
    Log "[$mark] $name $detail"
}
function Skipped([string]$name, [string]$why) {
    [void]$Results.Add("SKIP  $name  ($why)")
    Log "[SKIP] $name ($why)"
}
# Скачивает образ с повторами: Docker Hub при плохой сети отвечает тайм-аутом TLS
$ImageOk = @{}
function Ensure-Image([string]$img) {
    if ($ImageOk.ContainsKey($img)) { return $ImageOk[$img] }
    & docker image inspect $img *> $null
    $ok = ($LASTEXITCODE -eq 0)
    for ($i = 1; (-not $ok) -and $i -le 3; $i++) {
        Log "  docker pull $img (попытка $i из 3)"
        & docker pull -q $img 2>&1 | ForEach-Object { Log "    $_" }
        $ok = ($LASTEXITCODE -eq 0)
        if (-not $ok) { Start-Sleep 5 }
    }
    $ImageOk[$img] = $ok
    return $ok
}
function Section([string]$t) { Log "`n==================== $t ====================" }

# Монтируем только нужные папки (без .git и node_modules) и копируем их внутрь контейнера: в репозитории ничего не меняется
function Mounts([string[]]$dirs) { $m = @(); foreach ($d in $dirs) { $m += '-v'; $m += "$(Join-Path $Root $d):/src/${d}:ro" }; return $m }

# ---------------------------------------------------------------- сеть
Section 'Образы Docker (скачиваются один раз)'
$Images = @('python:3.12-slim', 'maven:3.9-eclipse-temurin-21', 'eclipse-temurin:21-jre', 'node:20-alpine', 'nginx:alpine',
            'postgres:16-alpine', 'rabbitmq:3-management-alpine', 'zricethezav/gitleaks:v8.21.2')
$missing = @($Images | Where-Object { -not (Ensure-Image $_) })
Result 'образы Docker доступны' ($missing.Count -eq 0) $(if ($missing.Count) { "(не скачались: $($missing -join ', ') — Docker Hub не отвечает; проверьте сеть или VPN и запустите снова)" } else { '' })

# ---------------------------------------------------------------- unit
if ($Skip -notcontains 'unit') {
    Section 'pytest (python:3.12)'
    $m = Mounts @('worker-python', 'test-fixtures', 'docs')
    if (-not (Ensure-Image 'python:3.12-slim')) { Skipped 'pytest обработчика' 'нет образа python:3.12-slim' } else {
    $code = Run docker run --rm @m python:3.12-slim sh -c "cp -r /src /work && cd /work/worker-python && pip install -q --disable-pip-version-check --retries 10 --timeout 60 --root-user-action=ignore -r requirements-dev.txt && python -m pytest -p no:cacheprovider -ra"
    Result 'pytest обработчика' ($code -eq 0) }

    Section 'Тесты бэкенда (Maven, JDK 21)'
    $m = Mounts @('backend-java', 'test-fixtures', 'docs')
    if (-not (Ensure-Image 'maven:3.9-eclipse-temurin-21')) { Skipped 'тесты бэкенда' 'нет образа maven' } else {
    $code = Run docker run --rm @m -v sapsr-check-m2:/root/.m2 -v //var/run/docker.sock:/var/run/docker.sock `
        -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true `
        maven:3.9-eclipse-temurin-21 sh -c "cp -r /src /work && cd /work/backend-java && mvn -B -q test '-Dtest=!BackendApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false; c=`$?; grep -h 'Tests run' target/surefire-reports/*.txt 2>/dev/null; for f in `$(grep -l -E 'FAILURE|ERROR' target/surefire-reports/*.txt 2>/dev/null); do cat `$f; done; exit `$c"
    Result 'тесты бэкенда' ($code -eq 0) }

    Section 'gitleaks по всей истории'
    if (-not (Ensure-Image 'zricethezav/gitleaks:v8.21.2')) { Skipped 'gitleaks' 'нет образа gitleaks' } else {
    $code = Run docker run --rm -v "${Root}:/repo:ro" zricethezav/gitleaks:v8.21.2 git /repo --config /repo/.gitleaks.toml --log-opts=--all --redact --no-banner
    # 1 — найдены утечки. Известные находки (два старых токена) перечислены в docs/threat-model.md
    Result 'gitleaks' ($code -eq 0) $(if ($code -eq 1) { '(есть находки — см. docs/threat-model.md; после отзыва токенов внести в .gitleaksignore)' } else { '' }) }
}

# ---------------------------------------------------------------- bat
if ($Skip -notcontains 'bat') {
    Section 'Подпрограммы start-docker.bat'
    $tmp = Join-Path $env:TEMP "sapsr-bat-$(Get-Random)"
    New-Item -ItemType Directory $tmp | Out-Null
    $bat = [IO.File]::ReadAllText((Join-Path $Root 'start-docker.bat'), [Text.Encoding]::UTF8)
    $subs = $bat.Substring($bat.IndexOf("`n:ENSURE_SECRET") + 1)
    $test = "@echo off`r`nchcp 65001 >nul`r`ncd /d `"%~dp0`"`r`ncall :ENSURE_SECRET POSTGRES_PASSWORD`r`ncall :ENSURE_SECRET RABBITMQ_PASSWORD`r`ncall :ENSURE_SECRET RABBITMQ_PASSWORD`r`ncall :READ_ENV POSTGRES_USER sapsr_user`r`ncall :READ_ENV RABBITMQ_PASSWORD`r`necho PGUSER=[%POSTGRES_USER%]`r`necho RMQPASS=[%RABBITMQ_PASSWORD%]`r`nexit /b 0`r`n`r`n" + $subs
    [IO.File]::WriteAllText((Join-Path $tmp 't.bat'), $test, (New-Object Text.UTF8Encoding $false))
    # .env как у тех, кто запускал раньше: паролей нет вовсе; плюс пустая строка из нового .env.example
    [IO.File]::WriteAllText((Join-Path $tmp '.env'), "# комментарий`r`nTELEGRAM_BOT_TOKEN=x`r`nPOSTGRES_PASSWORD=`r`n", (New-Object Text.UTF8Encoding $false))
    $out = & cmd /c (Join-Path $tmp 't.bat') 2>&1 | ForEach-Object { "$_" }
    $out | ForEach-Object { Log "  $_" }
    $envText = [IO.File]::ReadAllLines((Join-Path $tmp '.env'), (New-Object Text.UTF8Encoding $false))
    $envText | ForEach-Object { Log ("  .env: $_" -replace '=([0-9a-f]{4})[0-9a-f]{28}', '=$1…') }
    $pg = @($envText | Where-Object { $_ -match '^POSTGRES_PASSWORD=[0-9a-f]{32}$' }).Count
    $rmq = @($envText | Where-Object { $_ -match '^RABBITMQ_PASSWORD=[0-9a-f]{32}$' }).Count
    $readOk = ($out -join "`n") -match 'PGUSER=\[sapsr_user\]' -and ($out -join "`n") -match 'RMQPASS=\[[0-9a-f]{32}\]'
    Result 'start-docker.bat: пароли дописываются один раз, читаются, кириллица в .env цела' (($pg -eq 1) -and ($rmq -eq 1) -and $readOk -and ($envText -contains 'TELEGRAM_BOT_TOKEN=x') -and ($envText -contains '# комментарий'))
    Remove-Item -Recurse -Force $tmp
}

# ---------------------------------------------------------------- stack
if ($Skip -notcontains 'stack') {
    $P = 'sapsr-check'
    $EnvFile = Join-Path $env:TEMP "sapsr-check-$(Get-Random).env"
    $Token = '123456789:AAtest-token-for-sapsr-tests-only000'
    $PgPass = [guid]::NewGuid().ToString('N'); $RmqPass = [guid]::NewGuid().ToString('N')
    function Write-EnvFile($pg, $rmq) {
        [IO.File]::WriteAllText($EnvFile, "TELEGRAM_BOT_TOKEN=$Token`nNGROK_AUTHTOKEN=dummy`nNGROK_DOMAIN=check.invalid`nPOSTGRES_PASSWORD=$pg`nRABBITMQ_PASSWORD=$rmq`n", (New-Object Text.UTF8Encoding $false))
    }
    Write-EnvFile $PgPass $RmqPass
    $C = @('compose', '-p', $P, '--env-file', $EnvFile, '-f', 'docker-compose.yml', '-f', 'scripts/check.compose.yml')
    function DC { $all = $C + $args; Run docker @all }
    function DCq { $all = $C + $args; (& docker @all 2>$null) }   # тихо, только stdout

    function Sign-InitData([long]$id) {
        $auth = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $user = '{"id":' + $id + ',"first_name":"Check"}'
        $h1 = New-Object Security.Cryptography.HMACSHA256 -ArgumentList (, [Text.Encoding]::UTF8.GetBytes('WebAppData'))
        $secret = $h1.ComputeHash([Text.Encoding]::UTF8.GetBytes($Token))
        $h2 = New-Object Security.Cryptography.HMACSHA256 -ArgumentList (, $secret)
        $hash = -join ($h2.ComputeHash([Text.Encoding]::UTF8.GetBytes("auth_date=$auth`nuser=$user")) | ForEach-Object { $_.ToString('x2') })
        "auth_date=$auth&user=$([Uri]::EscapeDataString($user))&hash=$hash"
    }
    function Sql([string]$q) { (DCq exec -T postgres-db psql -U sapsr_user -d sapsr_db -tAc $q) -join "`n" }
    function Wait-Status([int]$id, [int]$seconds = 120) {
        $deadline = (Get-Date).AddSeconds($seconds)
        # Протокол 2: QUEUED — пока обработчик не начал, PROCESSING — пока нет итога
        do { Start-Sleep 2; $s = (Sql "select status from submissions where id=$id").Trim() } while (@('QUEUED', 'PROCESSING') -contains $s -and (Get-Date) -lt $deadline)
        return $s
    }
    function Upload([string]$path) {
        $resp = Join-Path $env:TEMP "sapsr-check-resp.json"
        $code = & curl.exe -s -o $resp -w '%{http_code}' -H "Authorization: $(Sign-InitData 100500)" -F "file=@$path;type=application/pdf" http://127.0.0.1:18080/api/v1/upload
        $body = Get-Content $resp -Raw -Encoding UTF8
        Log "  POST /upload -> $code $body"
        if ($code -ne '200') { return $null }
        return ($body | ConvertFrom-Json).db_id
    }
    function Publish-Task([string]$payload) {
        # ASCII-only: строка уходит в stdin rabbitmqadmin
        Log "  publish: $payload"
        $payload | & docker @C exec -T rabbitmq rabbitmqadmin -u rmq_user -p $script:RmqPassNow publish exchange=amq.default routing_key=pdf_tasks_queue 2>&1 | ForEach-Object { Log "  $_" }
    }
    function Worker-Restarts { [int](& docker inspect -f '{{.RestartCount}}' (DCq ps -q worker) 2>$null) }
    function Wait-Backend([int]$seconds = 240) {
        $deadline = (Get-Date).AddSeconds($seconds)
        while ((Get-Date) -lt $deadline) {
            $code = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: $(Sign-InitData 100500)" http://127.0.0.1:18080/api/v1/me
            if ($code -match '^[1-5]\d\d$' -and $code -ne '000') { return $code }
            Start-Sleep 3
        }
        return '000'
    }
    $script:RmqPassNow = $RmqPass

    try {
        Section 'Сборка образов'
        $code = DC build
        Result 'сборка образов (backend, worker, frontend)' ($code -eq 0)
        if ($code -ne 0) { throw 'BUILD_FAILED' }

        Section 'Запуск состава sapsr-check'
        $code = DC up -d --wait postgres-db rabbitmq
        $code2 = DC up -d --no-build worker backend
        $me = Wait-Backend
        Log "  GET /me -> $me"
        Result 'состав поднялся, бэкенд отвечает' (($code -eq 0) -and ($code2 -eq 0) -and ($me -ne '000')) "(GET /me = $me)"

        Section 'Порты'
        $ports = (& docker ps --filter "label=com.docker.compose.project=$P" --format '{{.Names}} {{.Ports}}') -join "`n"
        Log $ports
        Result 'наружу опубликован только API на 127.0.0.1:18080' (-not ($ports -match '0\.0\.0\.0:') -and ($ports -match '127\.0\.0\.1:18080'))

        Section 'Подпись данных запуска'
        $bad = (Sign-InitData 100500) -replace 'hash=[0-9a-f]+', ('hash=' + ('0' * 64))
        $c1 = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: $bad" http://127.0.0.1:18080/api/v1/me
        $c2 = & curl.exe -s -o NUL -w '%{http_code}' http://127.0.0.1:18080/api/v1/me
        Log "  неверная подпись -> $c1, без подписи -> $c2"
        Result 'чужая подпись — 403, без подписи — 401' (($c1 -eq '403') -and ($c2 -eq '401'))

        Section 'Сквозной сценарий: загрузка эталонного PDF'
        $id1 = Upload (Join-Path $Root 'test-fixtures\valid-format-sample.pdf')
        if ($id1) {
            $s1 = Wait-Status $id1
            $task = (Sql "select storage_key from submissions where id=$id1").Trim()
            Log "  submission $id1 -> $s1, storage_key=$task"
            Result 'эталон: обработчик прочитал задание v2 и вернул отчёт' (@('SUCCESS', 'REJECTED') -contains $s1) "(статус $s1)"
        } else { Result 'эталон: загрузка' $false }

        Section 'Отрицательный: не PDF под видом PDF'
        $fake = Join-Path $env:TEMP 'sapsr-check-fake.pdf'
        [IO.File]::WriteAllBytes($fake, [Text.Encoding]::ASCII.GetBytes('PK not a pdf at all'))
        $id2 = Upload $fake
        if ($id2) {
            $s2 = Wait-Status $id2
            Log "  submission $id2 -> $s2"
            Result 'не PDF: сбой проверки FAILED, обработчик жив' (($s2 -eq 'FAILED') -and ((Worker-Restarts) -eq 0)) "(статус $s2)"
        } else { Result 'не PDF: загрузка' $false }

        Section 'Отрицательный: задания, которые раньше роняли обработчик'
        $before = Worker-Restarts
        Publish-Task 'this is not json'
        Publish-Task '{"schema": 99, "task_id": 1}'
        if ($id2) {
            Sql "update submissions set status='PROCESSING', format_errors=null where id=$id2" | Out-Null
            Publish-Task ('{"schema":2,"task_id":' + $id2 + ',"type":"CHECK","file":{"storage_key":"../../etc/passwd","sha256":"0","source_format":"pdf"},"trace_id":"00000000-0000-0000-0000-000000000001"}')
            $s3 = Wait-Status $id2 60
            $err = Sql "select format_errors from submissions where id=$id2"
            Log "  submission $id2 -> $s3, errors=$err"
        }
        Start-Sleep 3
        $after = Worker-Restarts
        $logs = (DCq logs worker --tail 60) -join "`n"
        Log ($logs -split "`n" | Select-Object -Last 25 | Out-String)
        Result 'мусор и выход за корень хранилища: результат-сбой, без перезапусков обработчика' (($after -eq $before) -and ($logs -match 'сообщение отклонено') -and ($s3 -eq 'FAILED')) "(перезапусков: $after, статус $s3)"

        Section 'Смена паролей в уже созданных базе и очереди (как в start-docker.bat)'
        $NewPg = [guid]::NewGuid().ToString('N'); $NewRmq = [guid]::NewGuid().ToString('N')
        $c1 = DC exec -T postgres-db psql -q -U sapsr_user -d sapsr_db -c "ALTER USER sapsr_user PASSWORD '$NewPg'"
        $c2 = DC exec -T rabbitmq rabbitmqctl -q change_password rmq_user $NewRmq
        Write-EnvFile $NewPg $NewRmq
        $script:RmqPassNow = $NewRmq
        DC up -d --no-build --force-recreate worker backend | Out-Null
        $me = Wait-Backend
        $id4 = Upload (Join-Path $Root 'test-fixtures\valid-format-sample.pdf')
        $s4 = if ($id4) { Wait-Status $id4 } else { 'нет' }
        Log "  после смены паролей: GET /me=$me, submission $id4 -> $s4"
        Result 'после смены паролей бэкенд и обработчик подключаются' (($c1 -eq 0) -and ($c2 -eq 0) -and (@('SUCCESS', 'REJECTED') -contains $s4))
    }
    catch {
        if ("$_" -eq 'BUILD_FAILED') { Skipped 'проверки состава' 'образы не собрались — см. сборку выше' } else { Result 'проверки состава' $false "(исключение: $_)" }
    }
    finally {
        Section 'Уборка'
        DC logs --no-color --tail 200 backend worker | Out-Null
        DC down -v --remove-orphans | Out-Null
        Remove-Item -Force $EnvFile -ErrorAction SilentlyContinue
    }
}

Section 'ИТОГ'
$Results | ForEach-Object { Log $_ }
$failed = @($Results | Where-Object { $_ -like 'FAIL*' }).Count
Log "Провалено: $failed из $($Results.Count). Лог: $Log"
exit $failed
