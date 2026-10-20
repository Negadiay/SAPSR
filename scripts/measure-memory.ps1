# Замер оперативной памяти при полном составе (задача 8.3, план Жерко Ж8).
# Запуск на машине для защиты, когда всё поднято (start-docker.bat) и модель загружена:
#   powershell -ExecutionPolicy Bypass -File scripts\measure-memory.ps1
# Чтобы модель точно была в памяти, перед замером проверь одну работу через мини-приложение
# или выполни: ollama run qwen3.5:4b "привет"
# Результат печатается и сохраняется в memory-<компьютер>-<дата>.txt в текущей папке.

$ErrorActionPreference = 'Continue'
$out = "memory-$env:COMPUTERNAME-$(Get-Date -Format 'yyyy-MM-dd-HHmm').txt"

function Section($title) { "`n=== $title ===" }

$report = & {
    Section 'Компьютер'
    $os = Get-CimInstance Win32_OperatingSystem
    $cs = Get-CimInstance Win32_ComputerSystem
    "Имя: $env:COMPUTERNAME; модель: $($cs.Manufacturer) $($cs.Model)"
    'Оперативная память: всего {0:N1} ГБ, свободно {1:N1} ГБ, занято {2:N1} ГБ' -f `
        ($os.TotalVisibleMemorySize / 1MB), ($os.FreePhysicalMemory / 1MB), `
        (($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / 1MB)
    'Файл подкачки: занято {0:N1} ГБ из {1:N1} ГБ' -f `
        (($os.SizeStoredInPagingFiles - $os.FreeSpaceInPagingFiles) / 1MB), ($os.SizeStoredInPagingFiles / 1MB)

    Section 'Виртуальная машина Docker (WSL2, процесс vmmem / vmmemWSL)'
    Get-Process vmmem, vmmemWSL -ErrorAction SilentlyContinue |
        ForEach-Object { '{0}: {1:N1} ГБ' -f $_.ProcessName, ($_.WorkingSet64 / 1GB) }

    Section 'Контейнеры (docker stats)'
    docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'

    Section 'Ollama на ноутбуке (процессы и модели в памяти)'
    Get-Process ollama*, 'ollama app' -ErrorAction SilentlyContinue |
        ForEach-Object { '{0}: {1:N1} ГБ' -f $_.ProcessName, ($_.WorkingSet64 / 1GB) }
    if (Get-Command ollama -ErrorAction SilentlyContinue) { ollama ps } else { 'ollama не найдена в PATH' }

    Section 'Видеокарта'
    if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
        nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv
    } else { 'nvidia-smi не найдена' }

    Section 'Самые большие процессы Windows'
    Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 10 |
        ForEach-Object { '{0,-30} {1,6:N2} ГБ' -f $_.ProcessName, ($_.WorkingSet64 / 1GB) }
}

$report | Tee-Object -FilePath $out
"`nСохранено в $out"
