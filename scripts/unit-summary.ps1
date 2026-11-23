#requires -Version 7
# Сводка модульных тестов трёх частей SAPSR (план 3-й опроцентовки, Т7) → docs/testing/unit-summary.md.
#   pwsh -File scripts\unit-summary.ps1                 — прогнать все три набора и собрать сводку
#   pwsh -File scripts\unit-summary.ps1 -UseBackendReports — бэкенд не запускать, взять готовые отчёты surefire
# Бэкенд — без BackendApplicationTests (полный контекст поднимает бота с настоящим токеном); тесты базы
# идут на Testcontainers — нужен запущенный Docker Desktop.

param([switch]$UseBackendReports)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$Utf8 = New-Object Text.UTF8Encoding $false
$Root = Split-Path -Parent $PSScriptRoot
$Tmp = Join-Path $env:TEMP "sapsr-unit-$(Get-Random)"
New-Item -ItemType Directory $Tmp | Out-Null
$Started = Get-Date

# ── обработчик ──────────────────────────────────────────────────────────────
$py = Join-Path $Root 'worker-python\.venv\Scripts\python.exe'
$junit = Join-Path $Tmp 'pytest.xml'
Push-Location (Join-Path $Root 'worker-python')
& $py -m pytest -q -p no:cacheprovider "--junitxml=$junit" | Out-Null
Pop-Location
$worker = @{}
$markers = @{ typical = 0; boundary = 0; negative = 0 }
if (Test-Path $junit) {
    foreach ($case in ([xml](Get-Content $junit -Raw)).SelectNodes('//testcase')) {
        $file = ($case.classname -split '\.')[1] + '.py'
        if (-not $worker.ContainsKey($file)) { $worker[$file] = @{ total = 0; failed = 0; skipped = 0 } }
        $worker[$file].total++
        if ($case.failure -or $case.error) { $worker[$file].failed++ }
        if ($case.skipped) { $worker[$file].skipped++ }
    }
}
# Группы тестов обработчика — по маркерам pytest.
Push-Location (Join-Path $Root 'worker-python')
foreach ($m in @($markers.Keys)) {
    $line = (& $py -m pytest -q -p no:cacheprovider --collect-only -m $m 2>$null | Select-String -Pattern '(\d+)(/\d+)? tests? (collected|selected)' | Select-Object -Last 1)
    if ($line) { $markers[$m] = [int]$line.Matches[0].Groups[1].Value }
}
Pop-Location

# ── интерфейс ───────────────────────────────────────────────────────────────
$vitestJson = Join-Path $Tmp 'vitest.json'
Push-Location (Join-Path $Root 'frontend-webapp')
& npx vitest run --reporter=json "--outputFile=$vitestJson" 2>$null | Out-Null
Pop-Location
$frontend = @{}
if (Test-Path $vitestJson) {
    foreach ($suite in (Get-Content $vitestJson -Raw | ConvertFrom-Json).testResults) {
        $name = $suite.name.Replace('\', '/') -replace '^.*/frontend-webapp/', ''
        $frontend[$name] = @{ total = @($suite.assertionResults).Count
                              failed = @($suite.assertionResults | Where-Object { $_.status -eq 'failed' }).Count
                              skipped = @($suite.assertionResults | Where-Object { $_.status -in 'skipped', 'pending' }).Count }
    }
}

# ── бэкенд ──────────────────────────────────────────────────────────────────
$reports = Join-Path $Root 'backend-java\target\surefire-reports'
if (-not $UseBackendReports) {
    Push-Location (Join-Path $Root 'backend-java')
    & .\mvnw.cmd -o -q clean test '-Dtest=!BackendApplicationTests' '-Dsurefire.failIfNoSpecifiedTests=false' 2>&1 | Out-Null
    Pop-Location
}
$backend = @{}
foreach ($f in Get-ChildItem $reports -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue) {
    $s = ([xml](Get-Content $f.FullName -Raw)).testsuite
    $package = ($s.name -replace '^com\.sapsr\.backend\.?', '') -replace '\.[^.]+$', ''
    if (-not $package) { $package = '(корень)' }
    $class = ($s.name -split '\.')[-1]
    $key = "$package|$class"
    $backend[$key] = @{ total = [int]$s.tests; failed = [int]$s.failures + [int]$s.errors; skipped = [int]$s.skipped }
}

# ── сводка ──────────────────────────────────────────────────────────────────
function Sum($table) {
    $t = 0; $f = 0; $s = 0
    foreach ($v in $table.Values) { $t += $v.total; $f += $v.failed; $s += $v.skipped }
    return @{ total = $t; failed = $f; skipped = $s }
}
function Verdict($v) { if ($v.failed) { "**не прошли: $($v.failed)**" } elseif ($v.skipped) { "прошли (пропущено: $($v.skipped))" } else { 'прошли' } }
$w = Sum $worker; $fe = Sum $frontend; $be = Sum $backend

$md = @(
    '# Модульные тесты: сводка', '',
    'Собрано скриптом `scripts/unit-summary.ps1`. Сквозные сценарии — `e2e-results.md`, оценка качества — `quality.md`.', '',
    '## Среда', '', '| Что | Значение |', '|---|---|',
    "| Дата | $(Get-Date -Format 'dd.MM.yyyy HH:mm') |",
    '| Машина | ThinkPad P16v, RTX A500 4 ГБ, 32 ГБ ОЗУ; Windows 11 |',
    "| Обработчик | Python $((& $py --version) -replace 'Python ', ''), pytest; модель — сценарная заглушка и записанные ответы qwen3.5:4b, сеть не нужна |",
    "| Интерфейс | Node $((& node --version)), Vitest, Testing Library |",
    '| Бэкенд | Java 21, Maven, JUnit 5; база — PostgreSQL 16 в Testcontainers |',
    '| Команды | `python -m pytest -q` (worker-python); `npx vitest run` (frontend-webapp); `mvnw test "-Dtest=!BackendApplicationTests"` (backend-java) |', '',
    '## Итог', '', '| Набор | Тестов | Результат |', '|---|---|---|',
    "| Обработчик | $($w.total) | $(Verdict $w) |",
    "| Интерфейс | $($fe.total) | $(Verdict $fe) |",
    "| Бэкенд | $($be.total) | $(Verdict $be) |",
    "| **Всего** | **$($w.total + $fe.total + $be.total)** | $(if ($w.failed + $fe.failed + $be.failed) { '**есть непрошедшие**' } else { 'все прошли' }) |", '',
    "Группы тестов обработчика (маркеры pytest): типичных — $($markers.typical), граничных — $($markers.boundary), отрицательных — $($markers.negative); остальные без маркера.", '',
    '## Обработчик — по файлам', '', '| Файл | Тестов | Результат |', '|---|---|---|'
)
foreach ($k in $worker.Keys | Sort-Object) { $md += "| ``tests/$k`` | $($worker[$k].total) | $(Verdict $worker[$k]) |" }
$md += @('', '## Интерфейс — по файлам', '', '| Файл | Тестов | Результат |', '|---|---|---|')
foreach ($k in $frontend.Keys | Sort-Object) { $md += "| ``$k`` | $($frontend[$k].total) | $(Verdict $frontend[$k]) |" }
$md += @('', '## Бэкенд — по пакетам', '', '| Пакет | Класс | Тестов | Результат |', '|---|---|---|---|')
foreach ($k in $backend.Keys | Sort-Object) { $p = $k.Split('|'); $md += "| $($p[0]) | ``$($p[1])`` | $($backend[$k].total) | $(Verdict $backend[$k]) |" }
$md += @('', "Сборка заняла $([int]((Get-Date) - $Started).TotalSeconds) с.", '')

New-Item -ItemType Directory -Force (Join-Path $Root 'docs\testing') | Out-Null
[IO.File]::WriteAllText((Join-Path $Root 'docs\testing\unit-summary.md'), ($md -join "`n"), $Utf8)
Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
Write-Host "Обработчик $($w.total), интерфейс $($fe.total), бэкенд $($be.total); не прошли: $($w.failed + $fe.failed + $be.failed)"
exit ($w.failed + $fe.failed + $be.failed)
