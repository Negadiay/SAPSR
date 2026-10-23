# Обработчик SAPSR — гибридное ядро проверки

Получает задание из `pdf_tasks_queue`, проверяет PDF и отправляет отчёт в `pdf_results_queue`.
Протокол — `docs/contracts/queue-v2.md`, формат отчёта — `docs/contracts/report-v2.md`,
план работ — `docs/plan-7sem/1-intellect-buzychkov.md`.

## Цепочка

```
1 extract    pdfplumber → символы, строки, координаты; скрытый текст отделяется     sapsr/document/extract.py
2 structure  разметка строк на блоки → модель документа                              sapsr/document/labeling.py, build.py
3 format     правила оформления и структуры из базы знаний          → фаза format   sapsr/checks/, kb/rules/
4 claims     языковая модель: утверждения о системе (JSON по схеме)                  sapsr/content/claims.py
5 reasoning  проверка цитат, нормализация, правила CLIPS; требования семестра         sapsr/content/, kb/reasoning/
6 report     отчёт v2                                                → фаза content  sapsr/report/
```

Модель только извлекает факты; решение выносят правила, блокировать сдачу может только правило
оформления. Нет модели или ИИС — зависящие проверки пропускаются и перечисляются в отчёте
(`pipeline.skipped`), оформление проверяется всегда.

## Структура

| Где | Что |
|---|---|
| `main.py` | обработчик очереди: задание → проверка → результат v1 или прогресс и фазы v2 |
| `analyzer.py` | прежний вход `analyze_pdf(path)` (формат v1) и запуск из командной строки |
| `sapsr/pipeline.py` | цепочка целиком, две фазы, тайм-аут, режимы |
| `sapsr/document/` | извлечение, разметка, модель документа (pydantic) |
| `sapsr/checks/` | реестр проверок оформления и сами проверки; движок правил |
| `sapsr/content/` | фрагменты для модели, утверждения, проверка цитат, нормализация, CLIPS, требования семестра, режим `neural` |
| `sapsr/llm/client.py` | клиент языковой модели |
| `sapsr/report/` | модель отчёта v2, сборка, перевод в v1 |
| `sapsr/directories.py` | справочники ИИС для титульного листа |
| `kb/` | база знаний — см. ниже |
| `tests/` | тесты (pytest) |
| `tools/export_contract.py` | выгрузка JSON-схемы и примеров отчёта в `docs/contracts` |

## База знаний — `kb/`

| Файл | Что в нём |
|---|---|
| `catalog.yaml` | версия каталога, базовый профиль, действующие версии подсказок и файлы правил CLIPS |
| `rules/*.yaml` | правила: идентификатор, источник, применимость, серьёзность, блокировка, проверка, параметры, тексты |
| `profiles/*.yaml` | профили требований: какие правила включены, переопределения параметров, настройки содержательной проверки |
| `concepts.yaml` | словарь понятий: части записки, атрибуты системы и синонимы их значений |
| `requirements/*.yaml` | требования семестра (`sem7.yaml` — черновик) |
| `reasoning/*.clp` | продукционные правила CLIPS |
| `prompts/<id>.v<N>.md` | подсказки модели; новая версия — новый файл и ссылка в `catalog.yaml` |
| `layout.yaml` | параметры извлечения и разметки (зоны колонтитулов, признаки заголовков) |

Чтобы изменить норму — правьте параметры правила, код трогать не нужно. Новое правило над уже
существующей проверкой — новая запись в `rules/`. Каталог проверяется при запуске: неизвестная
проверка, неверный параметр или поле шаблона, которого проверка не даёт, — ошибка запуска, а не
сбой на первой работе. Версия базы знаний (`pipeline.kb_version` в отчёте) меняется при любой правке `kb/`.

Пример правила:

```yaml
- id: FMT-MARGIN-LEFT
  title: Левое поле
  category: format
  source: { doc: "СТП 01-2024", clause: null }   # пункт — только по тексту стандарта
  severity: minor
  check: margins.side                            # имя проверки в sapsr/checks
  params: { side: left, norm_mm: 30, tolerance_mm: 5.3, low_percentile: 2, high_percentile: 98, min_chars: 5 }
  message: "Нарушено левое поле страницы."
  expected: "Левое поле должно быть {norm_mm:g} мм."
  found: "Наименьшее левое поле — {actual_mm:.1f} мм; страниц с нарушением: {affected}."
  fix: "Установите левое поле {norm_mm:g} мм."
```

## Запуск

Python 3.10+ (в Docker — 3.12).

```bash
cd worker-python
python -m venv .venv
.venv/Scripts/activate            # Windows; в Linux: source .venv/bin/activate
pip install -r requirements-dev.txt

python -m pytest                  # тесты: сеть, Ollama и RabbitMQ не нужны
python -m pytest -m negative      # только отрицательные сценарии (typical, boundary, negative)

python analyzer.py работа.pdf --report v2 --llm off --iis off          # отчёт v2 без модели и ИИС
python analyzer.py работа.pdf --report v2 --semester 7 --stage 2       # с требованиями 7-го семестра
python analyzer.py работа.pdf --mode symbolic                          # symbolic | hybrid | neural
python analyzer.py работа.pdf --dump-document doc.json                 # модель документа в JSON

python main.py                    # обработчик очереди (нужен RabbitMQ)
```

### Языковая модель

Решение 1: `qwen3.5:4b` в Ollama.

```bash
ollama pull qwen3.5:4b
ollama ps                          # после первого запроса: 100% GPU
```

Обработчик в Docker обращается к Ollama на ноутбуке по `http://host.docker.internal:11434`
(`docker-compose.yml`); локальный запуск — `http://localhost:11434`. Без Ollama проверка идёт,
но только оформление.

Записать ответы настоящей модели для сквозных тестов и CI (режим `replay`):

```bash
SAPSR_LLM_MODE=record SAPSR_LLM_REPLAY_DIR=../test-fixtures/llm-replay python analyzer.py работа.pdf --report v2
SAPSR_LLM_MODE=replay SAPSR_LLM_REPLAY_DIR=../test-fixtures/llm-replay python analyzer.py работа.pdf --report v2
```

В записях лежат только ответы модели (с цитатами из работы) — для чужих работ их не коммитить.

## Настройки — переменные окружения

| Переменная | По умолчанию | Что значит |
|---|---|---|
| `SAPSR_QUEUE_PROTOCOL` | `1`, в Docker — `2` | `2` — прогресс и две фазы результата; `1` — результат одним сообщением `{task_id, status, errors}`. Бэкенд с 23.10.2026 принимает обе версии (задача 5.2) |
| `SAPSR_STORAGE_ROOT` | `/app/storage` в Docker, иначе `../storage` | корень хранилища файлов работ |
| `SAPSR_MODE` | `hybrid` | `symbolic` — только правила; `hybrid` — правила + модель; `neural` — только модель (для сравнения) |
| `SAPSR_MAX_PAGES` | `300` | больше — сбой `TOO_LARGE` |
| `SAPSR_TASK_TIMEOUT_S` | `540` | общий тайм-аут проверки; содержательная часть по нему обрывается с пометкой в отчёте |
| `SAPSR_IIS_MODE` | `http` | `http` — справочники из ИИС (с тайм-аутом и паузой после сбоя); `off` — без ИИС |
| `SAPSR_LLM_MODE` | `live` | `live`, `record`, `replay`, `stub`, `off` |
| `SAPSR_LLM_PROVIDER` | `ollama` | `ollama` или `openai` (любой OpenAI-совместимый сервер) |
| `SAPSR_LLM_BASE_URL` | `http://localhost:11434` | адрес модели |
| `SAPSR_LLM_MODEL` | `qwen3.5:4b` | модель |
| `SAPSR_LLM_TEMPERATURE`, `SAPSR_LLM_SEED` | `0`, `42` | воспроизводимость |
| `SAPSR_LLM_TIMEOUT_S`, `SAPSR_LLM_NUM_CTX` | `120`, `8192` | тайм-аут вызова, окно контекста |
| `SAPSR_LLM_ALLOW_CLOUD` | выключено | нелокальный адрес модели разрешается только так (решение 2) |
| `SAPSR_LLM_API_KEY` | — | ключ OpenAI-совместимого сервиса |
| `SAPSR_LLM_CACHE_DIR` | `worker-python/.cache/llm` | кэш ответов модели |
| `SAPSR_LLM_REPLAY_DIR` | — | каталог записанных ответов для `record` и `replay` |
| `SAPSR_LLM_STUB_FILE` | — | JSON с ответами для режима `stub` |
| `SAPSR_KB_DIR` | `worker-python/kb` | база знаний |
| `SAPSR_LOG_FORMAT`, `SAPSR_LOG_LEVEL` | `json`, `INFO` | логи: JSON-строки с `trace_id` и `task_id` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `rmq_user`, `rmq_password` | очередь |

## Тестовые документы

`test-fixtures/hybrid/generate.py` строит записки, которые проходят оформление, но содержат
три содержательных дефекта из записки, а также отрицательные примеры (скрытая инструкция,
узкое поле, PDF с паролем, скан без текста). Ожидаемые результаты — `expected.json` рядом,
его сверяет `tests/test_fixtures_expected.py`. В тестах модель заменяет сценарная заглушка
`tests/scripted_llm.py`, ИИС — справочник с вымышленными сотрудниками.
