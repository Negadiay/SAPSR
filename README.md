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
- **Кабинет преподавателя** — вердикты, комментарии, шаблоны ответов, уведомления в Telegram
- **Кабинет студента** — загрузка работы, отслеживание статуса, детальный отчёт об ошибках
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
| Frontend | React 19, Vite 8, Framer Motion |
| Worker | Python 3.10+, pdfplumber, pika, pydantic, CLIPS (clipspy), rapidfuzz; языковая модель — Ollama (`qwen3.5:4b`) |
| База данных | PostgreSQL 16 |
| Очередь сообщений | RabbitMQ 3 |
| Инфраструктура | Docker, Docker Compose |
| Интеграция | Telegram Bot API 7.2, Spring Mail (Gmail SMTP) |
| Деплой фронтенда | GitHub Pages, Cloudflare Tunnel |

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
├── start.bat                   # Скрипт автозапуска (Windows)
└── test-fixtures/              # Тестовые PDF-файлы
```

---

## Требования

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (для PostgreSQL и RabbitMQ)
- Java 21+
- Python 3.10+
- [Ollama](https://ollama.com) с моделью `qwen3.5:4b` — для содержательной проверки (без неё проверяется только оформление)
- Node.js 18+
- Telegram Bot Token (получить у [@BotFather](https://t.me/BotFather))
- Gmail-аккаунт для SMTP (верификация преподавателей)

---

## Установка и запуск

### Автоматический запуск (Windows)

```bat
start.bat
```

Скрипт последовательно поднимает инфраструктуру Docker, запускает Python-воркер, Java-бэкенд и фронтенд-сервер в отдельных окнах терминала.

---

### Ручной запуск

**1. Инфраструктура (PostgreSQL + RabbitMQ)**

```bash
docker compose up -d
```

Сервисы будут доступны:
- PostgreSQL: `localhost:5433`
- RabbitMQ: `localhost:5672`
- RabbitMQ Management UI: [http://localhost:15672](http://localhost:15672)

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
python main.py
```

Воркер подключается к RabbitMQ и начинает обрабатывать очередь `pdf_tasks_queue`. Настройки (протокол очередей, адрес модели, режимы) — переменные окружения, см. `worker-python/README.md`.

**4. Frontend**

```bash
cd frontend-webapp
npm install
npm run dev
```

Dev-сервер запустится на `http://localhost:5173`.

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

### Frontend

```bash
cd frontend-webapp
npm test
npm run lint
```

### Worker

```bash
cd worker-python
pip install -r requirements-dev.txt
python -m pytest
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
spring.datasource.url=jdbc:postgresql://localhost:5433/sapsr
spring.datasource.username=postgres
spring.datasource.password=postgres

# RabbitMQ
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672

# Email (Gmail SMTP)
spring.mail.username=YOUR_EMAIL@gmail.com
spring.mail.password=YOUR_APP_PASSWORD

# Домен преподавателей
teacher.email.domain=@bsuir.by
```

### `frontend-webapp/vite.config.js`

Укажите базовый URL бэкенда в разделе `proxy` или через переменную окружения `VITE_API_BASE_URL`.

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




