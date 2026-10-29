# SAPSR — Система Автоматической Проверки Студенческих Работ

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-6db33f?logo=springboot)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776ab?logo=python)
![React](https://img.shields.io/badge/React-19-61dafb?logo=react)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ed?logo=docker)

**SAPSR** — интеллектуальная микросервисная платформа для автоматического нормоконтроля студенческих курсовых и дипломных работ. Система избавляет преподавателей от рутинной проверки оформления (шрифты, поля, ГОСТы) и позволяет сосредоточиться на содержании работы.

> Разработано для нужд БГУИР (Белорусский государственный университет информатики и радиоэлектроники).

---

## Содержание

- [О проекте](#о-проекте)
- [Возможности](#возможности)
- [Архитектура](#архитектура)
- [Стек технологий](#стек-технологий)
- [Структура проекта](#структура-проекта)
- [Требования](#требования)
- [Установка и запуск](#установка-и-запуск)
- [Конфигурация](#конфигурация)
- [Деплой](#деплой)

---

## О проекте

Преподаватели тратят значительное время на проверку оформления студенческих работ — соответствие шрифтов, полей, межстрочных интервалов требованиям ГОСТ и внутренних стандартов вуза. SAPSR автоматизирует этот процесс: студент загружает PDF через Telegram Mini App, система анализирует документ и формирует подробный отчёт об ошибках форматирования. Преподаватель получает уже проверенную работу и выносит вердикт только по содержанию.

<!-- Сюда можно добавить скриншоты интерфейса -->

---

## Возможности

- **Автоматический нормоконтроль PDF** — проверка по стандартам ГОСТ:
  - семейство и размер шрифта
  - поля страницы (с допуском)
  - межстрочный интервал
  - абзацные отступы
  - нумерация таблиц, рисунков и страниц
  - оформление списка литературы и ссылок
  - структура документа (титульный лист, оглавление, разделы)
- **Telegram Mini App** — единый интерфейс для студентов и преподавателей без установки отдельного приложения
- **Асинхронная обработка** — очередь RabbitMQ гарантирует стабильность при высокой нагрузке
- **Кабинет преподавателя** — итог автоматической проверки, возврат работы с пометками в PDF, решение, уведомления в Telegram
- **Кабинет студента** — сдача работы (вид работы и семестр система определяет сама), ход проверки по шагам, замечания с подсказками, комментарии преподавателя, версии
- **Красная зона администратора** — состояние обработчика и выбор модели проверки (лёгкая, основная, тяжёлая, облачная Groq) без перезапуска; облачная — только с защитой персональных данных (решения 2 и 13)
- **Работа с комментариями** — преподаватель получает PDF студента, где каждое замечание стоит комментарием на своём месте: неправильно оформленный список выделен целиком. Если содержание проверяется дольше 5 минут, работа приходит с пометкой «ещё проверяется», а итог — следующим сообщением (решение 14)
- **Интерактивное обучение** — для каждой роли, по шагам с подсветкой элементов
- **Верификация email** — подтверждение роли преподавателя по домену `@bsuir.by`
- **Доступность** — переключение тем, размеров шрифта, режима высокой контрастности

---

## Архитектура

Система состоит из трёх независимых сервисов, взаимодействующих через REST API и очередь сообщений:

```
Студент / Преподаватель (Telegram)
            │
            ▼
   Frontend (React + Vite)
            │  REST
            ▼
   Backend (Spring Boot :8080)
       │              │
       ▼              ▼
 PostgreSQL      RabbitMQ
                    │  pdf_tasks_queue
                    ▼
           Python Worker (анализ PDF)
                    │  pdf_results_queue
                    ▼
   Backend ← результаты → Telegram-уведомление
```

| Сервис | Роль |
|--------|------|
| **backend-java** | REST API, Telegram Bot, аутентификация, хранение данных |
| **worker-python** | Извлечение и анализ параметров PDF, формирование отчёта |
| **frontend-webapp** | Telegram Mini App — интерфейс студента и преподавателя |

---

## Стек технологий

| Компонент | Технология |
|-----------|------------|
| Backend | Java 21, Spring Boot 4.0.3, Spring Data JPA, Spring AMQP |
| Frontend | React 19, Vite 8 |
| Worker | Python 3.10+, pdfplumber, pika, pydantic, CLIPS (clipspy), rapidfuzz; языковая модель — Ollama (`qwen3.5:4b`) |
| База данных | PostgreSQL 16 |
| Очередь сообщений | RabbitMQ 3 |
| Инфраструктура | Docker, Docker Compose |
| Интеграция | Telegram Bot API 7.2, Spring Mail (Gmail SMTP) |
| Деплой фронтенда | GitHub Pages, ngrok |

---

## Структура проекта

```
SAPSR/
├── backend-java/               # Spring Boot REST API и Telegram Bot
│   ├── src/main/java/          # Контроллеры, сервисы, сущности
│   ├── src/main/resources/     # application.properties.example
│   │   └── db/migration/       # Схема базы данных: миграции Flyway
│   └── pom.xml
│
├── worker-python/              # Сервис анализа PDF
│   ├── main.py                 # Потребитель очереди RabbitMQ
│   ├── analyzer.py             # Запуск проверки из командной строки
│   ├── sapsr/                  # Гибридное ядро: модель документа, правила, модель, отчёт v2
│   ├── kb/                     # База знаний: правила, профили, словарь понятий, правила CLIPS
│   ├── tests/                  # Тесты pytest
│   └── README.md               # Устройство обработчика и настройки
│
├── frontend-webapp/            # React Telegram Mini App
│   ├── src/
│   │   ├── App.jsx             # Корневой компонент (UI студента и преподавателя)
│   │   └── main.jsx            # Точка входа
│   ├── package.json
│   └── vite.config.js
│
├── docs/
│   ├── decisions.md            # Журнал решений
│   ├── decision-tasks.md       # Задачи по решениям: кто и что делает
│   ├── contracts/              # Контракты: протокол очередей, REST API
│   └── plan-7sem/              # Планы участников
│
├── docker-compose.yml          # PostgreSQL + RabbitMQ
├── dev-seed.example.sql        # Образец тестовых данных; свой dev-seed.sql — не в git
├── start-docker.bat            # Запуск всего состава в Docker (Windows)
└── test-fixtures/              # Тестовые PDF-файлы
```

---

## Требования

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (для PostgreSQL и RabbitMQ)
- Java 21+
- Python 3.12 (в Docker-образе обработчика — 3.12; библиотекам моделей нужен 3.10+)
- [Ollama](https://ollama.com) с моделью `qwen3.5:4b` — для содержательной проверки (без неё проверяется только оформление); по желанию `qwen3.5:2b` (лёгкая) и `qwen3.5:9b` (тяжёлая) — их можно выбрать в «Красной зоне»
- Node.js 18+
- Telegram Bot Token (получить у [@BotFather](https://t.me/BotFather))
- Gmail-аккаунт для SMTP (верификация преподавателей)

---

## Установка и запуск

### Автоматический запуск (Windows)

```bat
start-docker.bat
```

Скрипт поднимает весь состав в Docker: базу, очередь, обработчик, бэкенд, интерфейс (nginx) и ngrok с закреплённым адресом.

**Один раз в @BotFather — кнопка «Открыть» в списке чатов.** Её адрес (главное мини-приложение бота) задаётся только в BotFather: `/mybots` → бот → **Bot Settings** → **Configure Mini App** → **Edit Mini App URL** → `https://<NGROK_DOMAIN>/`. Кнопку меню внутри чата бэкенд обновляет сам при каждом запуске, а эту — нет: со старым адресом она открывает приложение без сервера.

---

### Ручной запуск

**1. Инфраструктура (PostgreSQL + RabbitMQ)**

```bash
docker compose up -d
```

Сервисы будут доступны только с этого компьютера (порты привязаны к `127.0.0.1`):
- PostgreSQL: `localhost:5433`
- RabbitMQ: `localhost:5672`
- RabbitMQ Management UI: [http://localhost:15672](http://localhost:15672)

Пароли базы и очереди — в `.env` (`POSTGRES_PASSWORD`, `RABBITMQ_PASSWORD`; образец — `.env.example`). `start-docker.bat` сам впишет случайные, если их нет, и при каждом запуске приведёт к ним пароли в уже созданных базе и очереди. Запуская бэкенд из IDE, укажите те же пароли в своём `application.properties`.

**2. Backend**

```bash
cd backend-java
./mvnw spring-boot:run
```

API запустится на `http://localhost:8080`. При первом запуске бэкенд сам создаёт схему базы миграциями Flyway.

Тестовые данные (необязательно): скопируйте `dev-seed.example.sql` в `dev-seed.sql`, впишите свои данные и загрузите командами из начала файла. `dev-seed.sql` в `.gitignore` — настоящие Telegram ID и почты в репозиторий не попадают.

**3. Python Worker**

```bash
cd worker-python
pip install -r requirements.txt
RABBITMQ_USER=rmq_user RABBITMQ_PASSWORD=<из .env> SAPSR_STORAGE_ROOT=../storage python main.py
```

В PowerShell переменные задаются так: `$env:RABBITMQ_PASSWORD="<из .env>"`. Без `RABBITMQ_USER` и `RABBITMQ_PASSWORD` обработчик не запустится — зашитых паролей в коде нет.

Воркер подключается к RabbitMQ и начинает обрабатывать очередь `pdf_tasks_queue`. Остальные настройки (протокол очередей, адрес модели, режимы) — переменные окружения, см. `worker-python/README.md`.

**4. Frontend**

```bash
cd frontend-webapp
npm install
npm run dev
```

Dev-сервер запустится на `http://localhost:5173`.

---

### Администратор

Курс, семестр и этап опроцентовки студент не выбирает: сервер определяет их сам — курс по первой цифре номера группы, семестр по дате, этап по датам опроцентовок (решение 11 в `docs/decisions.md`). Администратор в приложении видит всё это строками и исправляет, если что-то определилось неверно: семестр сейчас, сроки каждого курса (опроцентовки и сдача), курсы групп. Там же он открывает группу, видит её студентов и преподавателей, исправляет ФИО и группу студента (преподавателей — только просмотр), удаляет пустую группу, добавленную вручную, и назначает новых администраторов. Работы он не проверяет.

Роль администратора регистрацией не получить. **Первого** администратора записывают в базу вручную по Telegram ID (узнать его можно у [@userinfobot](https://t.me/userinfobot)):

```bash
docker exec -it sapsr-postgres psql -U sapsr_user -d sapsr_db -c "INSERT INTO users (telegram_id, role, full_name) VALUES (123456789, 'ADMIN', 'Администратор');"
```

**Следующих** назначает администратор: человек регистрируется студентом со своего отдельного аккаунта, администратор находит его в группе на экране «Управление», открывает правку и делает администратором — после подтверждения учебные данные этого аккаунта (работы, группа, почта) удаляются.

Вкладка **«Красная зона»** на том же экране — то, что меняет работу всей системы сразу (решение 13): на связи ли обработчик и какая модель проверяет работы. Модель выбирается из каталога обработчика (`worker-python/llm-models.yaml`) и действует для новых проверок без перезапуска. Модели скачиваются заранее: `ollama pull qwen3.5:2b`, `ollama pull qwen3.5:9b`; нескачанная видна как недоступная.

**Облачная модель** — Groq, `openai/gpt-oss-120b` (решение 2): бесплатный тариф без карты, запросы не хранятся и не идут на обучение. Выбирается там же, но только если её разрешил тот, кто разворачивает систему, — в `.env` (ключ — на `https://console.groq.com/keys`):

```env
SAPSR_LLM_ALLOW_CLOUD=1
SAPSR_LLM_CLOUD_API_KEY=<ключ gsk_...>
SAPSR_LLM_CLOUD_EXTRA_BODY={"reasoning_effort": "low", "include_reasoning": false}   # короче рассуждения — быстрее
```

Адрес и модель Groq подставляются сами. Другой OpenAI-совместимый сервис — свои `SAPSR_LLM_CLOUD_BASE_URL` (только HTTPS) и `SAPSR_LLM_CLOUD_MODEL`, пример запасного — в `.env.example`. Бесплатный тариф ограничивает токены в минуту: обработчик ждёт, сколько просит сервис, поэтому большая записка на нём проверяется дольше — до нескольких минут.

Ключ живёт только в `.env` обработчика: бэкенд и приложение его не видят. Перед отправкой из текста убираются ФИО, почта, телефоны и номера групп, титульный лист не отправляется вовсе; администратор подтверждает выбор отдельно, студент видит пометку при сдаче.

Для администратора нужен отдельный аккаунт Telegram: у одного аккаунта одна роль. Снять роль — `DELETE FROM users WHERE telegram_id = 123456789 AND role = 'ADMIN';`. Сразу после назначения стоит открыть «Управление» и ввести сроки текущего семестра — у каждого курса свои, кнопка «Для всех курсов» копирует их на все: без дат этап не определяется и проверяются все требования семестра.

---

### Показ без модели

Если на машине показа Ollama не поднимется, обработчик отвечает записанными ответами модели:

1. В `.env`: `SAPSR_LLM_MODE=replay`.
2. Перезапустить обработчик: `docker compose up -d worker` (или `start-docker.bat`).

Ответы записываются заранее на машине с моделью в `test-fixtures/llm-replay` — как, написано в `test-fixtures/llm-replay/README.md`. Записанный ответ находится, только если запрос к модели тот же: тот же документ, семестр и этап, та же база знаний и подсказки. На незаписанные документы содержательная проверка пропускается с пометкой в отчёте. На показе так и говорим: ответы модели записаны.

---

## Тесты

### Backend

> ⚠️ **Не запускайте `BackendApplicationTests`.** Он поднимает приложение целиком с вашим `application.properties`: бот подключается с настоящим токеном, а при старте рассылает сообщение **всем пользователям из базы**. Пока для Telegram нет заглушки, этот тест исключаем при каждом запуске.

PowerShell и cmd:

```bat
cd backend-java
.\mvnw.cmd test "-Dtest=!BackendApplicationTests" "-Dsurefire.failIfNoSpecifiedTests=false"
```

bash (в двойных кавычках bash принимает `!` за команду истории, поэтому кавычки одинарные):

```bash
cd backend-java
./mvnw test '-Dtest=!BackendApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
```

Тесты миграций (`FreshDatabaseMigrationTest`, `LegacyDatabaseMigrationTest`) поднимают временный PostgreSQL в Docker через Testcontainers. Нужен запущенный Docker Desktop; без него эти тесты пропускаются, остальные идут как обычно.

### Worker (Python)

```bash
cd worker-python
pip install -r requirements-dev.txt
python -m pytest
```

Тесты не ходят в сеть: справочники ИИС в них отключены. Группы — маркеры `typical`, `boundary`, `negative` (например, `python -m pytest -m negative`). Функция подписи данных запуска Telegram тестовым токеном — `worker-python/tests/telegram_init_data.py`, её Java-двойник — `backend-java/src/test/java/com/sapsr/backend/testsupport/TelegramInitDataSigner.java`; обе сверяются с `test-fixtures/telegram-init-data.json`.

### Frontend

```bash
cd frontend-webapp
npm test
npm run lint
```

Сеть, RabbitMQ и Ollama не нужны: модель заменяет заглушка, ИИС — тестовый справочник. Подробнее — `worker-python/README.md`.

---

## Конфигурация

### `backend-java/src/main/resources/application.properties`

```properties
# Telegram
telegram.bot.token=YOUR_BOT_TOKEN
telegram.bot.username=YOUR_BOT_USERNAME

# База данных
spring.datasource.url=jdbc:postgresql://localhost:5433/sapsr_db
spring.datasource.username=sapsr_user
spring.datasource.password=<POSTGRES_PASSWORD из .env>

# RabbitMQ
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672

# Email (Gmail SMTP)
spring.mail.username=YOUR_EMAIL@gmail.com
spring.mail.password=YOUR_APP_PASSWORD

# Домен преподавателей
sapsr.teacher.allowed-domain=@bsuir.by

# Сообщение «SAPSR снова работает» всем при запуске; false — не рассылать (отладка)
sapsr.bot.startup-notice=true
```

Время во всей системе — по Минску, независимо от часового пояса машины (VPN для ngrok его меняет): бэкенд ставит себе пояс `Europe/Minsk` при запуске, в `docker-compose.yml` у сервисов `TZ=Europe/Minsk`.

### `frontend-webapp/vite.config.js`

Укажите базовый URL бэкенда в разделе `proxy` или через переменную окружения `VITE_API_BASE`.

---

## Деплой

### Frontend → GitHub Pages

```bash
cd frontend-webapp
npm run build
npm run deploy
```

### Backend → JAR

```bash
cd backend-java
./mvnw clean package '-Dtest=!BackendApplicationTests' -Dsurefire.failIfNoSpecifiedTests=false
java -jar target/backend-*.jar
```

Почему тест исключён — в разделе «Тесты».




