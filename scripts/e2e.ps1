#requires -Version 7
# Сквозные сценарии SAPSR с таблицей результатов (план 3-й опроцентовки, Т4; сценарии — docs/testing/test-plan.md).
#   pwsh -File scripts\e2e.ps1             — собрать образы, поднять изолированный состав, пройти сценарии
#   pwsh -File scripts\e2e.ps1 -NoBuild    — не пересобирать образы
# Состав — отдельный проект sapsr-e2e (как scripts/check.ps1): свои тома, фальшивый токен бота, выключенный
# опрос Telegram, наружу — только API на 127.0.0.1:18080. Рабочий состав, его тома и .env не трогаются,
# ни одно сообщение в Telegram не уходит. Языковая модель — записанные ответы qwen3.5:4b (replay).
# Фамилия студентов — как на титульных листах тестовых записок (Иванов), иначе добавится замечание о чужой работе.
# Студенты регистрируются через API (нужен ответ ИИС о группе 321702); преподаватель — вставкой в базу.
# Итог — docs/testing/e2e-results.md и e2e-result.log; в конце состав удаляется вместе с томами и образами.

param([switch]$NoBuild, [switch]$Keep)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = New-Object Text.UTF8Encoding $false
$Utf8 = New-Object Text.UTF8Encoding $false

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Log = Join-Path $Root 'e2e-result.log'
[IO.File]::WriteAllText($Log, "SAPSR e2e $(Get-Date -Format s)`n", $Utf8)
function Log([string]$line) { Write-Host $line; [IO.File]::AppendAllText($Log, $line + "`n", $Utf8) }

$P = 'sapsr-e2e'
$Api = 'http://127.0.0.1:18080/api/v1'
$Token = '123456789:AAtest-token-for-sapsr-tests-only000'
$Tmp = Join-Path $env:TEMP "sapsr-e2e-$(Get-Random)"
New-Item -ItemType Directory $Tmp | Out-Null
$EnvFile = Join-Path $Tmp 'e2e.env'
$PgPass = [guid]::NewGuid().ToString('N'); $RmqPass = [guid]::NewGuid().ToString('N')
function Write-EnvFile([string]$llmMode) {
    [IO.File]::WriteAllText($EnvFile, "TELEGRAM_BOT_TOKEN=$Token`nNGROK_AUTHTOKEN=dummy`nNGROK_DOMAIN=e2e.invalid`nPOSTGRES_PASSWORD=$PgPass`nRABBITMQ_PASSWORD=$RmqPass`nE2E_LLM_MODE=$llmMode`n", $Utf8)
}
Write-EnvFile 'replay'
$C = @('compose', '-p', $P, '--env-file', $EnvFile, '-f', 'docker-compose.yml', '-f', 'scripts/check.compose.yml', '-f', 'scripts/e2e.compose.yml')
function DC { $all = $C + $args; & docker @all 2>&1 | ForEach-Object { Log "  $_" }; return $LASTEXITCODE }
function DCq { $all = $C + $args; (& docker @all 2>$null) }

# Тестовые пользователи: Telegram ID вымышленные.
$S1 = 100501; $S2 = 100502; $S3 = 100503; $Teacher = 200500; $Stranger = 100599

function Sign([long]$id) {
    $auth = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $user = '{"id":' + $id + ',"first_name":"E2E"}'
    $h1 = [Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes('WebAppData'))
    $h2 = [Security.Cryptography.HMACSHA256]::new($h1.ComputeHash([Text.Encoding]::UTF8.GetBytes($Token)))
    $hash = -join ($h2.ComputeHash([Text.Encoding]::UTF8.GetBytes("auth_date=$auth`nuser=$user")) | ForEach-Object { $_.ToString('x2') })
    "auth_date=$auth&user=$([Uri]::EscapeDataString($user))&hash=$hash"
}
# Запрос к API: возвращает @{ code; body (разобранный JSON или $null); raw }
function Call([long]$as, [string]$method, [string]$path, $json = $null, [string]$file = $null, [hashtable]$form = @{}) {
    $out = Join-Path $Tmp 'resp.bin'
    $a = @('-s', '-o', $out, '-w', '%{http_code}', '-X', $method, '-H', "Authorization: $(Sign $as)")
    if ($null -ne $json) {
        $bodyFile = Join-Path $Tmp 'req.json'
        [IO.File]::WriteAllText($bodyFile, ($json | ConvertTo-Json -Compress -Depth 6), $Utf8)
        $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$bodyFile")
    }
    if ($file) { $a += @('-F', "file=@$file") }
    foreach ($k in $form.Keys) { $a += @('--form-string', "$k=$($form[$k])") }
    $code = & curl.exe @a "$Api$path"
    $raw = if (Test-Path $out) { [IO.File]::ReadAllText($out, [Text.Encoding]::UTF8) } else { '' }
    $body = $null
    try { if ($raw) { $body = $raw | ConvertFrom-Json -Depth 30 } } catch { }
    Log "  $method $path (как $as) -> $code $($raw.Substring(0, [Math]::Min(300, $raw.Length)))"
    return @{ code = [string]$code; body = $body; raw = $raw }
}
function Sql([string]$q) { (DCq exec -T postgres-db psql -U sapsr_user -d sapsr_db -tAc $q) -join "`n" }
# Ждёт конца проверки (и оформления, и содержания); возвращает «СТАТУС|СОДЕРЖАНИЕ»
function Wait-Work([int]$id, [int]$seconds = 180) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        Start-Sleep 2
        $s = (Sql "select status || '|' || coalesce(content_status, '') from submissions where id=$id").Trim()
    } while (($s -match '^(QUEUED|PROCESSING)\|' -or $s -match '\|PENDING$') -and (Get-Date) -lt $deadline)
    return $s
}
function Upload([long]$as, [string]$path, [string]$teacherKey = $null) {
    $form = @{}; if ($teacherKey) { $form['teacher_id'] = $teacherKey }
    Call $as 'POST' '/upload' $null $path $form
}
function Report([long]$as, [int]$id) { (Call $as 'GET' "/submissions/$id/report-v2").body }
function Ids($report) { if ($report -and $report.report) { @($report.report.findings | ForEach-Object { $_.requirement_id }) } else { @() } }
function Skips($report) { if ($report -and $report.report) { @($report.report.pipeline.skipped | ForEach-Object { $_.reason }) } else { @() } }

$Rows = New-Object System.Collections.ArrayList
function Row([string]$id, [string]$group, [string]$scenario, [string]$expected, [string]$got, [bool]$ok, [string]$comment = '') {
    [void]$Rows.Add([pscustomobject]@{ id = $id; group = $group; scenario = $scenario; expected = $expected; got = $got; ok = $ok; comment = $comment })
    Log "[$(if ($ok) { 'PASS' } else { 'FAIL' })] $id $scenario — $got"
}
$Fix = Join-Path $Root 'test-fixtures'
$Started = Get-Date
$Versions = @{}

try {
    if (-not $NoBuild) {
        Log '== Сборка образов'
        if ((DC build) -ne 0) { throw 'BUILD_FAILED' }
    }
    Log '== Запуск состава sapsr-e2e'
    DC up -d --wait postgres-db rabbitmq | Out-Null
    DC up -d --no-build gotenberg worker backend | Out-Null
    $deadline = (Get-Date).AddSeconds(240); $me = '000'
    while ((Get-Date) -lt $deadline -and $me -notmatch '^[2-4]\d\d$') {
        Start-Sleep 3
        $me = & curl.exe -s -o NUL -w '%{http_code}' -H "Authorization: $(Sign $S1)" "$Api/me"
    }
    if ($me -notmatch '^[2-4]\d\d$') { throw "бэкенд не ответил (GET /me = $me)" }
    $Versions.gotenberg = (& docker inspect -f '{{.Config.Image}}' (DCq ps -q gotenberg)) 2>$null
    $Versions.migrations = (Sql "select string_agg(version, ',' order by installed_rank) from flyway_schema_history where success").Trim()

    # Преподаватель и студент первого курса — вставкой в базу (преподавателя нельзя регистрировать: письмо на почту).
    Sql "insert into users (telegram_id, role, full_name, last_name, first_name, middle_name, url_id, teacher_groups) values ($Teacher, 'TEACHER', 'Преподавателева Вера Павловна', 'Преподавателева', 'Вера', 'Павловна', 'e2e-teacher', '[""321702"",""621701""]')" | Out-Null
    Sql "insert into users (telegram_id, role, full_name, last_name, first_name, group_number, name_key, namesake_number) values ($S3, 'STUDENT', 'Первокурсников Пётр (гр. 621701)', 'Первокурсников', 'Пётр', '621701', 'первокурсников петр', 1)" | Out-Null

    # ── E2E-04, E2E-05: регистрация, повторная регистрация, тёзки ────────────────────────────────
    $name = @{ role = 'STUDENT'; last_name = 'Иванов'; first_name = 'Иван'; middle_name = 'Иванович'; group = '321702' }
    $r1 = Call $S1 'POST' '/register' $name
    $again = Call $S1 'POST' '/register' @{ role = 'STUDENT'; last_name = 'Другой'; first_name = 'Олег'; group = '321702' }
    $kept = (Sql "select last_name || ' ' || group_number from users where telegram_id=$S1").Trim()
    Row 'E2E-04' 'отрицательный' 'Повторная регистрация с того же аккаунта Telegram' '409 ALREADY_REGISTERED, данные не изменились' `
        "$($again.code) $($again.body.code); в базе: $kept" (($r1.code -eq '200') -and ($again.code -eq '409') -and ($again.body.code -eq 'ALREADY_REGISTERED') -and ($kept -eq 'Иванов 321702')) `
        $(if ($r1.code -ne '200') { "первая регистрация: $($r1.code) $($r1.raw) — нужна сеть до ИИС" } else { '' })

    $r2 = Call $S2 'POST' '/register' @{ role = 'STUDENT'; last_name = 'иванов'; first_name = 'ИВАН'; middle_name = 'Иванович'; group = '321702' }
    $me1 = (Call $S1 'GET' '/me').body; $me2 = (Call $S2 'GET' '/me').body
    Row 'E2E-05' 'типичный' 'Тёзки: двое с одинаковым ФИО в одной группе' 'номера тёзок 1 и 2' `
        "номера: $($me1.namesake_number) и $($me2.namesake_number)" (($r2.code -eq '200') -and ($me1.namesake_number -eq 1) -and ($me2.namesake_number -eq 2))

    $teachers = Call $S1 'GET' '/teachers'
    $key = @($teachers.body)[0].id
    Row 'E2E-10' 'граничный' 'Список преподавателей студента не раскрывает Telegram ID' 'ключ преподавателя, поля telegram_id нет' `
        "id=$key; telegram_id в ответе: $($teachers.raw -match 'telegram_id')" (($key -eq 'e2e-teacher') -and ($teachers.raw -notmatch 'telegram_id') -and ($teachers.raw -notmatch "$Teacher"))

    # ── E2E-01: PDF → отчёт ──────────────────────────────────────────────────────────────────────
    $u = Upload $S1 (Join-Path $Fix 'hybrid\content-formats.pdf') $key
    $id1 = $u.body.db_id; $st = if ($id1) { Wait-Work $id1 } else { 'нет' }
    $rep = if ($id1) { Report $S1 $id1 } else { $null }
    $ids = Ids $rep
    Row 'E2E-01' 'типичный' 'Загрузка PDF 7 семестра → отчёт с содержательным замечанием' 'SUCCESS, содержание DONE, найдено CNT-CONSIST-002, требования семестра проверены' `
        "$st; замечаний: $($ids.Count); противоречие: $($ids -contains 'CNT-CONSIST-002'); требований в карте: $(@($rep.report.requirements).Count)" `
        (($st -eq 'SUCCESS|DONE') -and ($ids -contains 'CNT-CONSIST-002') -and (@($rep.report.requirements).Count -gt 0) -and ($null -eq $rep.feedback))

    # ── E2E-11: отметка преподавателя ─────────────────────────────────────────────────────────────
    if ($id1) {
        $finding = @($rep.report.findings | Where-Object { $_.requirement_id -eq 'CNT-CONSIST-002' })[0].id
        $put = Call $Teacher 'PUT' "/teacher/submissions/$id1/findings/$finding/feedback" @{ verdict = 'correct' }
        $denied = Call $S1 'PUT' "/teacher/submissions/$id1/findings/$finding/feedback" @{ verdict = 'incorrect' }
        $seen = (Report $Teacher $id1).feedback.$finding
        Row 'E2E-11' 'типичный' 'Преподаватель отмечает замечание «верно»; студент отметить не может' '200 и отметка в отчёте преподавателя; студенту 403' `
            "PUT $($put.code), студенту $($denied.code), в отчёте: $seen" (($put.code -eq '200') -and ($denied.code -eq '403') -and ($seen -eq 'correct'))
    }

    # ── E2E-02: DOCX → PDF-версия → отчёт ────────────────────────────────────────────────────────
    $u = Upload $S1 (Join-Path $Fix 'docx\sample.docx') $key
    $id2 = $u.body.db_id; $st = if ($id2) { Wait-Work $id2 } else { "нет ($($u.code) $($u.body.code))" }
    $row = if ($id2) { (Sql "select source_format || '|' || (source_storage_key is not null) || '|' || right(storage_key, 4) from submissions where id=$id2").Trim() } else { '' }
    $rep = if ($id2) { Report $S1 $id2 } else { $null }
    $warn = @($rep.report.pipeline.warnings | ForEach-Object { $_.code })
    $src = if ($id2) { & curl.exe -s -o (Join-Path $Tmp 'source.docx') -w '%{http_code}' -H "Authorization: $(Sign $S1)" "$Api/submissions/$id2/source" } else { '—' }
    if ($id2) { & docker @C cp "backend:/app/storage/$((Sql "select storage_key from submissions where id=$id2").Trim())" (Join-Path $Tmp 'from-docx.pdf') 2>$null | Out-Null }
    Row 'E2E-02' 'типичный' 'Загрузка DOCX: преобразование в PDF, проверка PDF-версии, исходник сохранён' 'работа проверена (SUCCESS или REJECTED), source_format = docx, предупреждение CONVERTED_FROM_DOCX, исходник скачивается' `
        "$st; $row; предупреждения: $($warn -join ','); исходник: $src; шрифт: $($rep.report.document.main_font); замечания: $((Ids $rep) -join ',')" `
        (($st -match '^(SUCCESS|REJECTED)\|') -and ($row -eq 'docx|true|.pdf') -and ($warn -contains 'CONVERTED_FROM_DOCX') -and ($src -eq '200'))

    # ── E2E-09: не PDF и не DOCX; загрузка не студентом ──────────────────────────────────────────
    $fake = Join-Path $Tmp 'fake.pdf'; [IO.File]::WriteAllBytes($fake, [Text.Encoding]::ASCII.GetBytes('MZ this is not a pdf'))
    $bad = Upload $S1 $fake $key
    $byTeacher = Upload $Teacher (Join-Path $Fix 'hybrid\content-clean.pdf')
    $byStranger = Upload $Stranger (Join-Path $Fix 'hybrid\content-clean.pdf')
    Row 'E2E-09' 'отрицательный' 'Файл не PDF и не DOCX; загрузка преподавателем и незарегистрированным' '400 UNSUPPORTED_FORMAT; 403 и 403; работ не создано' `
        "$($bad.code) $($bad.body.code); преподаватель $($byTeacher.code), посторонний $($byStranger.code)" `
        (($bad.code -eq '400') -and ($bad.body.code -eq 'UNSUPPORTED_FORMAT') -and ($byTeacher.code -eq '403') -and ($byStranger.code -eq '403'))

    # ── E2E-06: нечитаемый текстовый слой ────────────────────────────────────────────────────────
    $u = Upload $S2 (Join-Path $Fix 'hybrid\unreadable-text.pdf') $key
    $id6 = $u.body.db_id; $st = if ($id6) { Wait-Work $id6 } else { 'нет' }
    $rep = if ($id6) { Report $S2 $id6 } else { $null }
    Row 'E2E-06' 'отрицательный' 'PDF с нечитаемым текстовым слоем' 'REJECTED, одно замечание DOC-TEXT-UNREADABLE, остальное пропущено (TEXT_UNREADABLE)' `
        "$st; замечания: $((Ids $rep) -join ','); пропуски: $((Skips $rep | Select-Object -Unique) -join ',')" `
        (($st -match '^REJECTED\|') -and ((Ids $rep) -join ',') -eq 'DOC-TEXT-UNREADABLE' -and ((Skips $rep) -contains 'TEXT_UNREADABLE'))

    # ── E2E-12: растровые шрифты LaTeX ───────────────────────────────────────────────────────────
    $u = Upload $S2 (Join-Path $Fix 'hybrid\tex-type3.pdf')
    $id12 = $u.body.db_id; $st = if ($id12) { Wait-Work $id12 } else { 'нет' }
    $rep = if ($id12) { Report $S2 $id12 } else { $null }
    $fmt = @($rep.report.findings | Where-Object { $_.origin -eq 'symbolic' } | ForEach-Object { $_.requirement_id })
    Row 'E2E-12' 'граничный' 'PDF из LaTeX с растровыми шрифтами (случай куратора)' 'работа принята (SUCCESS), по оформлению одно нестрогое замечание FMT-FONT-BITMAP, титульный лист найден' `
        "$st; оформление: $($fmt -join ',')" (($st -match '^SUCCESS\|') -and (($fmt -join ',') -eq 'FMT-FONT-BITMAP'))

    # ── E2E-08: семестр без требований ───────────────────────────────────────────────────────────
    $u = Upload $S3 (Join-Path $Fix 'hybrid\content-clean.pdf') $key
    $id8 = $u.body.db_id; $st = if ($id8) { Wait-Work $id8 } else { 'нет' }
    $rep = if ($id8) { Report $S3 $id8 } else { $null }
    $msg = @($rep.report.pipeline.skipped | Where-Object { $_.reason -eq 'NO_REQUIREMENTS' })[0].message
    $sem = (Sql "select coalesce(semester::text, 'нет') from submissions where id=$id8").Trim()
    Row 'E2E-08' 'граничный' 'Студент первого курса: для семестра нет набора требований' 'работа проверена, в «Не проверено» — NO_REQUIREMENTS с номером семестра' `
        "$st; семестр $sem; сообщение: $msg" (($st -match '^SUCCESS\|DONE') -and ($msg -match 'нет требований к курсовым работам'))

    # ── E2E-03: отклонена → преподаватель → пометки → новая версия со сравнением ─────────────────
    $u = Upload $S2 (Join-Path $Fix 'hybrid\no-conclusion.pdf') $key
    $id3 = $u.body.db_id; $st3 = if ($id3) { Wait-Work $id3 } else { 'нет' }
    $queue = (Call $Teacher 'GET' '/teacher/submissions').body
    $item = @($queue | Where-Object { $_.id -eq $id3 })[0]
    $review = Call $Teacher 'POST' "/teacher/submissions/$id3/review-file" $null (Join-Path $Fix 'review\no-conclusion-reviewed.pdf')
    $verdict = Call $Teacher 'POST' "/teacher/submissions/$id3/verdict" @{ verdict = 'REVISION'; review_file_id = $review.body.review_file_id }
    $comments = (Call $S2 'GET' "/submissions/$id3/annotations").body
    $u = Upload $S2 (Join-Path $Fix 'hybrid\content-clean.pdf') $key
    $id3b = $u.body.db_id; $st3b = if ($id3b) { Wait-Work $id3b } else { 'нет' }
    $rep = if ($id3b) { Report $S2 $id3b } else { $null }
    $fixed = @($rep.comparison.fixed | ForEach-Object { $_.requirement_id })
    $parent = if ($id3b) { (Sql "select coalesce(parent_id::text, '') from submissions where id=$id3b").Trim() } else { '' }
    Row 'E2E-03' 'типичный' 'Работа отклонена по оформлению → у преподавателя → файл с пометками и «на доработку» → новая версия со сравнением' `
        'REJECTED и видна преподавателю с пометкой тёзки; комментарий прочитан; новая версия связана с прежней; в сравнении исправлено STR-CONCLUSION' `
        "v1: $st3, в очереди: $($null -ne $item), тёзка: $($item.student.has_namesake) №$($item.student.namesake_number); комментариев: $(@($review.body.annotations).Count); вердикт $($verdict.code); студент видит комментариев: $(@($comments.annotations).Count); v2: $st3b, parent=$parent; исправлено: $($fixed -join ',')" `
        (($st3 -match '^REJECTED\|') -and ($null -ne $item) -and ($item.student.namesake_number -eq 2) -and (@($review.body.annotations).Count -ge 1) -and ($verdict.code -eq '200') -and (@($comments.annotations).Count -ge 1) -and ($parent -eq "$id3") -and ($fixed -contains 'STR-CONCLUSION'))

    # ── E2E-14: очередь — 10 одновременных загрузок (Т5) ─────────────────────────────────────────
    $auth = Sign $S1
    $pdfPath = Join-Path $Fix 'hybrid\content-clean.pdf'
    $t0 = Get-Date
    $ids = 1..10 | ForEach-Object -ThrottleLimit 10 -Parallel {
        $out = & curl.exe -s -H "Authorization: $using:auth" -F "file=@$using:pdfPath" --form-string "teacher_id=$using:key" "$using:Api/upload"
        try { ($out | ConvertFrom-Json).db_id } catch { $null }
    }
    $ids = @($ids | Where-Object { $_ })
    $done = @{}; $maxQueued = 0
    $deadline = (Get-Date).AddSeconds(600)
    while ($done.Count -lt $ids.Count -and (Get-Date) -lt $deadline) {
        Start-Sleep 1
        $statusLines = Sql "select id || '|' || status || '|' || coalesce(content_status, '') from submissions where id in ($($ids -join ','))"
        $queued = 0
        foreach ($line in ($statusLines -split "`n" | Where-Object { $_ })) {
            $f = $line.Split('|')
            if ($f[1] -eq 'QUEUED') { $queued++ }
            $final = ($f[1] -notin @('QUEUED', 'PROCESSING')) -and ($f[2] -ne 'PENDING')
            if ($final -and -not $done.ContainsKey($f[0])) { $done[$f[0]] = [int]((Get-Date) - $t0).TotalSeconds }
        }
        $maxQueued = [Math]::Max($maxQueued, $queued)
    }
    $times = @($done.Values | Sort-Object)
    $Versions.queue = "загрузок: $($ids.Count); до итога, с: $($times -join ', '); наибольшая очередь: $maxQueued"
    Row 'E2E-14' 'граничный' '10 одновременных загрузок одного документа (очередь RabbitMQ, один обработчик, записанные ответы модели)' `
        'все 10 приняты и получили итог, работы не потеряны' `
        "принято $($ids.Count), с итогом $($done.Count); время до итога: от $($times[0]) до $($times[-1]) с; наибольшая очередь: $maxQueued" `
        (($ids.Count -eq 10) -and ($done.Count -eq 10)) 'время — от одновременной отправки до окончательного отчёта каждой работы'
    # ── E2E-07: модель недоступна ────────────────────────────────────────────────────────────────
    Write-EnvFile 'live'
    DC up -d --no-build --force-recreate worker | Out-Null
    Start-Sleep 8
    $u = Upload $S1 (Join-Path $Fix 'hybrid\narrow-margin.pdf')
    $id7 = $u.body.db_id; $st = if ($id7) { Wait-Work $id7 240 } else { 'нет' }
    $rep = if ($id7) { Report $S1 $id7 } else { $null }
    Row 'E2E-07' 'отрицательный' 'Языковая модель недоступна' 'оформление проверено (найдено FMT-MARGIN-LEFT), содержание — «не проверено» (LLM_UNAVAILABLE), работа не потеряна' `
        "$st; замечания: $((Ids $rep) -join ','); пропуски: $((Skips $rep | Select-Object -Unique) -join ',')" `
        (($st -match '^SUCCESS\|') -and ((Ids $rep) -contains 'FMT-MARGIN-LEFT') -and ((Skips $rep) -contains 'LLM_UNAVAILABLE'))

    $restarts = [int](& docker inspect -f '{{.RestartCount}}' (DCq ps -q worker) 2>$null)
    $errors = @((DCq logs backend --no-color) | Select-String -Pattern 'ERROR' | Where-Object { $_ -notmatch 'Telegram|telegram|\[BOT\]' }).Count
    Row 'E2E-13' 'граничный' 'За весь прогон обработчик не перезапускался, в журнале бэкенда нет ошибок, кроме отправки в Telegram с фальшивым токеном' `
        'перезапусков 0, ошибок 0' "перезапусков: $restarts, ошибок: $errors" (($restarts -eq 0) -and ($errors -eq 0))

    # PDF-версия образца DOCX — в корпус: по ней проверяется работа с подставленными шрифтами.
    $converted = Join-Path $Tmp 'from-docx.pdf'
    if ((Test-Path $converted) -and -not (Test-Path (Join-Path $Fix 'docx\sample-converted.pdf'))) {
        Copy-Item $converted (Join-Path $Fix 'docx\sample-converted.pdf')
        Log '  сохранён test-fixtures/docx/sample-converted.pdf'
    }
}
catch {
    Row 'E2E-00' 'служебный' 'Запуск состава и прохождение сценариев' 'без исключений' "$_" $false
}
finally {
    Log '== Журналы и уборка'
    (DCq logs --no-color --tail 80 backend worker gotenberg) | ForEach-Object { [IO.File]::AppendAllText($Log, "    $_`n", $Utf8) }
    if (-not $Keep) { DC down -v --rmi local --remove-orphans | Out-Null }
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}

# ── таблица результатов ──────────────────────────────────────────────────────────────────────────
$sorted = $Rows | Sort-Object id
$failed = @($sorted | Where-Object { -not $_.ok }).Count
$kb = (& (Join-Path $Root 'worker-python\.venv\Scripts\python.exe') -c "import sys; sys.path.insert(0, 'worker-python'); from sapsr.kb.loader import load_kb; print(load_kb().version)" 2>$null)
$md = @(
    '# Сквозные сценарии: результаты', '',
    'Получено скриптом `scripts/e2e.ps1`; сценарии описаны в `test-plan.md`. Журнал прогона — `e2e-result.log` в корне репозитория.', '',
    '## Среда', '',
    '| Что | Значение |', '|---|---|',
    "| Дата | $(Get-Date -Format 'dd.MM.yyyy HH:mm') |",
    '| Машина | ThinkPad P16v, RTX A500 4 ГБ, 32 ГБ ОЗУ; Windows 11, Docker Desktop |',
    '| Состав | изолированный проект Docker Compose `sapsr-e2e`: PostgreSQL 16, RabbitMQ 3, бэкенд, обработчик, Gotenberg; фальшивый токен бота, опрос Telegram выключен |',
    '| Модель | qwen3.5:4b, режим `replay` — записанные ответы (`test-fixtures/llm-replay`); в E2E-07 — `live` с недоступным адресом |',
    "| Конвертер DOCX | $($Versions.gotenberg) |",
    "| Миграции базы | $($Versions.migrations) |",
    "| Очередь (E2E-14) | $($Versions.queue) |",
    "| Версия базы знаний | $kb |",
    "| Длительность | $([int]((Get-Date) - $Started).TotalSeconds) с |",
    '| Команда | `pwsh -File scripts\e2e.ps1` |', '',
    "## Результаты: пройдено $($sorted.Count - $failed) из $($sorted.Count)", '',
    '| ID | Группа | Сценарий | Ожидалось | Получено | Прошёл | Комментарий |', '|---|---|---|---|---|---|---|'
)
foreach ($r in $sorted) {
    $cells = @($r.id, $r.group, $r.scenario, $r.expected, $r.got, $(if ($r.ok) { 'да' } else { '**нет**' }), $r.comment) | ForEach-Object { ($_ -replace '\|', '/') -replace "`r?`n", ' ' }
    $md += '| ' + ($cells -join ' | ') + ' |'
}
$md += ''
New-Item -ItemType Directory -Force (Join-Path $Root 'docs\testing') | Out-Null
[IO.File]::WriteAllText((Join-Path $Root 'docs\testing\e2e-results.md'), ($md -join "`n"), $Utf8)
Log "Провалено: $failed из $($sorted.Count). Таблица: docs/testing/e2e-results.md"
exit $failed
