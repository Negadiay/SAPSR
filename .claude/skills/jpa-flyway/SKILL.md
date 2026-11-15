---
name: jpa-flyway
description: Правила работы с базой в backend-java SAPSR — сущности JPA, запросы репозиториев, транзакции, миграции Flyway и их тесты. Использовать, когда меняется сущность в entity/, репозиторий, запрос @Query, добавляется миграция db/migration/V*__*.sql или правится PipelineState и другие места с @Transactional.
---

# JPA и Flyway в SAPSR

## Как устроено сейчас

- Схему ведёт **только Flyway**: `backend-java/src/main/resources/db/migration/V1__baseline.sql … V8__…`. Hibernate схему не создаёт: `spring.jpa.hibernate.ddl-auto=validate` — при расхождении сущности и таблицы приложение не стартует.
- Сущности: `entity/` (`User`, `Submission`, `ReviewFile`, `Annotation`, `AcademicSetting`, `ProcentovkaDate`, `GroupCourseOverride`, `EmailVerification`). Репозитории: `repository/`.
- Состояние работы меняется в `pipeline/PipelineState.java` (методы с `@Transactional`); результаты обработчика приходят через `service/ResultListenerService.java`.
- Время хранится как `LocalDateTime` по Минску (решение 12). Не переводить на `Instant`/`timestamptz` попутно — только отдельным решением.
- Тесты миграций: `db/FreshDatabaseMigrationTest` (пустая база) и `db/LegacyDatabaseMigrationTest` (база со старыми данными), Testcontainers + `postgres:16-alpine`.

## Миграции

1. Новая миграция — следующий номер: `V9__коротко_что_меняется.sql` (латиница, `snake_case`). **Уже применённые файлы не редактировать никогда** — у развёрнутых баз не сойдётся контрольная сумма.
2. Сначала миграция, потом сущность: поле в сущности без колонки = падение на `validate`.
3. Добавляя `NOT NULL` в таблицу с данными — сначала колонка с `DEFAULT` или заполнение `UPDATE`, потом ограничение. Иначе `LegacyDatabaseMigrationTest` (и рабочая база) упадёт.
4. Внешний ключ — сразу с индексом и явным `ON DELETE` (что должно случиться с работами, если удалили пользователя).
5. Статусы (`status`, `contentStatus`) — обычные строковые поля `String`, не enum. Новое значение добавлять одновременно во все места, где оно сравнивается: `PipelineState`, запросы `SubmissionRepository` (там литералы `'PENDING'`, `'LATE'`), фронт и `docs/contracts/`. Опечатка в литерале компилятор не поймает — нужен тест.
6. Переименование или удаление колонки, которую читает обработчик или фронт, — сначала проверить `docs/contracts/` и `worker-python`.

## Сущности и запросы

- У `Submission` два `@ManyToOne` (студент и преподаватель) без явного `fetch`, а у `@ManyToOne` по умолчанию `EAGER`: каждая загруженная работа тянет обоих пользователей, в списках это лишние запросы. Для экранов со списками — `join fetch` в `@Query` или проекция (интерфейс/record с нужными полями). Переход на `LAZY` возможен, но тогда проверить все места, где связь читается вне транзакции (`LazyInitializationException`).
- Признак N+1: в цикле по списку работ вызывается `getStudent().getName()` и т. п. Проверка — `logging.level.org.hibernate.SQL=DEBUG` в тесте и подсчёт запросов.
- Массовые изменения статусов (просроченные, `LATE`) — одним `@Modifying @Query update`, как уже сделано в `SubmissionRepository`, а не загрузкой и сохранением по одной.
- В `@Query` — только параметры (`:id`), никаких склеенных строк.

## Транзакции

- `@Transactional` — на методе сервиса, который меняет несколько таблиц или читает-и-пишет (проверка статуса + смена статуса).
- **Внутри транзакции не ходить наружу:** RabbitMQ, почта, Telegram, ИИС, модель. Сначала сохранить, после коммита — отправить (или `TransactionSynchronization.afterCommit`). Иначе при откате письмо уже ушло, а записи нет.
- Повторная доставка сообщения из очереди не должна ломать данные: обработка результата должна быть идемпотентной (проверять, что этап ещё не закрыт).
- `@Transactional` на `private` методе и вызов транзакционного метода из того же класса не работают (прокси Spring).

## Проверка

Из `backend-java`, **без** `BackendApplicationTests` (полный контекст поднимает бота с настоящим токеном и рассылает сообщения людям):

```powershell
.\mvnw.cmd test "-Dtest=!BackendApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Только тесты базы (нужен Docker Desktop):

```powershell
.\mvnw.cmd test "-Dtest=FreshDatabaseMigrationTest,LegacyDatabaseMigrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Новая миграция с изменением данных — добавить случай в `LegacyDatabaseMigrationTest`. Для сложного запроса — тест `@DataJpaTest` на Testcontainers, не на H2.

_Основа: `skills/jpa-patterns` из affaan-m/everything-claude-code (MIT), переписано под SAPSR._
