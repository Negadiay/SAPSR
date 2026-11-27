# Модульные тесты: сводка

Собрано скриптом `scripts/unit-summary.ps1`. Сквозные сценарии — `e2e-results.md`, оценка качества — `quality.md`.

## Среда

| Что | Значение |
|---|---|
| Дата | 23.11.2026 14:20 |
| Машина | ThinkPad P16v, RTX A500 4 ГБ, 32 ГБ ОЗУ; Windows 11 |
| Обработчик | Python 3.12.10, pytest; модель — сценарная заглушка и записанные ответы qwen3.5:4b, сеть не нужна |
| Интерфейс | Node v25.6.1, Vitest, Testing Library |
| Бэкенд | Java 21, Maven, JUnit 5; база — PostgreSQL 16 в Testcontainers |
| Команды | `python -m pytest -q` (worker-python); `npx vitest run` (frontend-webapp); `mvnw test "-Dtest=!BackendApplicationTests"` (backend-java) |

## Итог

| Набор | Тестов | Результат |
|---|---|---|
| Обработчик | 227 | прошли |
| Интерфейс | 137 | прошли |
| Бэкенд | 242 | прошли |
| **Всего** | **606** | все прошли |

Группы тестов обработчика (маркеры pytest): типичных — 78, граничных — 23, отрицательных — 46; остальные без маркера.

## Обработчик — по файлам

| Файл | Тестов | Результат |
|---|---|---|
| `tests/test_analyzer.py` | 4 | прошли |
| `tests/test_checks.py` | 18 | прошли |
| `tests/test_content.py` | 22 | прошли |
| `tests/test_contract.py` | 14 | прошли |
| `tests/test_document.py` | 12 | прошли |
| `tests/test_evaluate.py` | 5 | прошли |
| `tests/test_fixtures_expected.py` | 20 | прошли |
| `tests/test_kb.py` | 21 | прошли |
| `tests/test_llm_client.py` | 17 | прошли |
| `tests/test_llm_models.py` | 27 | прошли |
| `tests/test_protocol.py` | 24 | прошли |
| `tests/test_reasoning.py` | 17 | прошли |
| `tests/test_replay.py` | 4 | прошли |
| `tests/test_telegram_init_data.py` | 4 | прошли |
| `tests/test_text_layer.py` | 18 | прошли |

## Интерфейс — по файлам

| Файл | Тестов | Результат |
|---|---|---|
| `src/api/client.test.js` | 7 | прошли |
| `src/App.test.jsx` | 25 | прошли |
| `src/components/ConfirmDialog.test.jsx` | 4 | прошли |
| `src/components/ReportView.test.jsx` | 12 | прошли |
| `src/components/StudentName.test.jsx` | 2 | прошли |
| `src/features/admin/Admin.test.jsx` | 17 | прошли |
| `src/features/settings/Settings.test.jsx` | 16 | прошли |
| `src/features/teacher/teacher.test.js` | 11 | прошли |
| `src/features/tour/Tour.test.jsx` | 5 | прошли |
| `src/lib/color.test.js` | 4 | прошли |
| `src/lib/lib.test.js` | 22 | прошли |
| `src/Review.test.jsx` | 12 | прошли |

## Бэкенд — по пакетам

| Пакет | Класс | Тестов | Результат |
|---|---|---|---|
| academic | `AcademicCalendarTest` | 25 | прошли |
| admin | `AdminUsersTest` | 10 | прошли |
| bot | `BotMessagesTest` | 14 | прошли |
| config | `TelegramInitDataInterceptorTest` | 9 | прошли |
| config | `WebMvcConfigTest` | 3 | прошли |
| controller | `AdminControllerTest` | 14 | прошли |
| controller | `AdminSystemControllerTest` | 6 | прошли |
| controller | `ReportControllerTest` | 10 | прошли |
| controller | `ReviewControllerTest` | 6 | прошли |
| controller | `TeacherDashboardControllerTest` | 8 | прошли |
| controller | `UploadControllerTest` | 38 | прошли |
| db | `FreshDatabaseMigrationTest` | 9 | прошли |
| db | `LegacyDatabaseMigrationTest` | 6 | прошли |
| entity | `NameKeyTest` | 5 | прошли |
| entity | `WorkFileNameTest` | 3 | прошли |
| messaging | `TaskMessageTest` | 9 | прошли |
| report | `CommentedPdfTest` | 4 | прошли |
| report | `ReportComparisonTest` | 5 | прошли |
| review | `AnnotationExtractorTest` | 7 | прошли |
| review | `ReviewFileServiceTest` | 5 | прошли |
| SapsrTelegramBotTest | `SapsrTelegramBotTest` | 5 | прошли |
| service | `DocxConverterTest` | 5 | прошли |
| service | `ResultListenerServiceTest` | 17 | прошли |
| storage | `FileKindTest` | 5 | прошли |
| storage | `LocalFileStorageTest` | 14 | прошли |

Сборка заняла 44 с.
