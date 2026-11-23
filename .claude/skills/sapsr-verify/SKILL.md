---
name: sapsr-verify
description: Финальная проверка изменений в SAPSR перед тем, как пользователь сделает коммит, — тесты бэкенда без рассылки в Telegram, pytest и ruff обработчика, lint, тесты и сборка фронта, разбор diff, по желанию полный изолированный прогон scripts/check.ps1. Итог — отчёт «прошло / не прошло» и git status. Использовать в конце любой задачи с правками кода, перед опроцентовкой, когда просят «проверь всё», «прогони тесты», «можно коммитить?».
---

# Финальная проверка SAPSR

Коммиты делает пользователь сам. Задача навыка — прогнать проверки, честно доложить результат и показать `git status`. **Не коммитить и не пушить.**

## Запрещено при проверке

- `BackendApplicationTests` и полный запуск бэкенда с локальным `application.properties`: там настоящий токен бота, при старте `StartupNotificationService` рассылает сообщение всем пользователям из базы.
- Регистрация преподавателей через обычный путь (письмо с кодом уходит на настоящую почту), отправка писем и сообщений настоящим людям.
- Прогон модели по работам настоящих студентов. Только `test-fixtures/`.

## Порядок

Проверять только те части, которые затронуты (`git status`, `git diff --stat`), но если правка в контракте между частями (`docs/contracts/`, отчёт, очередь) — все три.

### 1. Бэкенд (`backend-java`)

```powershell
.\mvnw.cmd test "-Dtest=!BackendApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Тесты миграций (`FreshDatabaseMigrationTest`, `LegacyDatabaseMigrationTest`) поднимают Postgres через Testcontainers — нужен запущенный Docker Desktop. Нет Docker — сказать, что эти тесты не прошли по этой причине, а не замалчивать. `mvn` не установлен — только `mvnw.cmd`; без сети работает `-o`. Сеть есть из PowerShell, не из Git Bash.

### 2. Обработчик (`worker-python`)

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m ruff check sapsr tests
```

Ruff: в коде есть старые замечания — сравнить число с `HEAD` (`git stash` не использовать; достаточно сравнить замечания в изменённых файлах). Новых быть не должно. Если менялись классы отчёта (`sapsr/report/model.py`) — `test_contract.py` сверит схему; обновлять её командой `python tools/export_contract.py`, а не руками.

### 3. Фронт (`frontend-webapp`)

```powershell
npm run lint
npm test
npm run build
```

Если vitest не стартует за 60 с («Timeout waiting for worker») — `npx vitest run --pool=threads`, затем `npx vitest run --pool=forks --maxWorkers=1` (около 2 минут на весь набор).

### 4. Разбор diff

Пройти `git diff` глазами и отметить:

- секреты: токены, пароли, ключи облачных моделей; `application.properties` и `.env` не должны попасть в git (их место — `*.example`);
- изменён уже применённый файл `db/migration/V*__*.sql` — недопустимо (подробнее навык `jpa-flyway`);
- поменялся формат обмена — обновлены ли `docs/contracts/` и обе стороны (бэкенд и обработчик);
- поменялось поведение, описанное в решении, — нужна правка в `docs/decisions.md` (навык `decision-record`);
- забытые отладочные `System.out`, `print`, `console.log`, закомментированный код;
- файлы с CRLF: в репозитории LF.

По желанию — агенты `silent-failure-hunter` (обработка ошибок, очереди) и `database-reviewer` (миграции, сущности).

### 5. Полный изолированный прогон (по желанию, долго)

Перед опроцентовкой или после правок в очередях и Docker — `scripts/check.ps1`: отдельный проект `sapsr-check`, фальшивый токен, рабочий состав и `.env` не трогаются, ни одно сообщение в Telegram не уходит. Запускать в фоне:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\check.ps1
```

Итог — в конце вывода и в `check-result.log`. Отдельные части пропускаются через `-Skip unit`, `-Skip bat`, `-Skip stack` (несколько — через `-Command "& .\scripts\check.ps1 -Skip unit,bat"`).

## Отчёт

```
Проверка SAPSR — ДД.ММ.ГГГГ
Бэкенд:      ПРОШЛО (N тестов) / НЕ ПРОШЛО (какие, с выводом) / НЕ ЗАПУСКАЛОСЬ (почему)
Обработчик:  pytest …, ruff: новых замечаний 0
Фронт:       lint …, тесты …, сборка …
Diff:        замечаний нет / список
Полный прогон: не запускался / итог check-result.log
```

Затем `git status` и, если просят, предложение разбить изменения на коммиты (только предложение). Что не проверено — назвать прямо.

_Основа: `skills/springboot-verification` из affaan-m/everything-claude-code (MIT), переписано под SAPSR: без SpotBugs/PMD/JaCoCo, которых в проекте нет, и с правилами безопасного запуска._
