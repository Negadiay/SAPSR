# Записанные ответы модели

Ответы настоящей языковой модели на документы `test-fixtures/hybrid` — для режима `replay`: сквозные тесты и запасной показ без Ollama (README, «Показ без модели»). Папка подключается к обработчику в `docker-compose.yml` (`/app/llm-replay`, только чтение).

Ответ лежит по ключу запроса: модель, версия подсказки, текст подсказки и JSON-схема (`worker-python/sapsr/llm/client.py`, `request_key`). Поэтому записи устаревают, когда меняются подсказки, словарь понятий, требования семестра или сам документ, — тогда записать заново.

## Как записать

На машине с Ollama и моделью `qwen3.5:4b`, из `worker-python`. Семестр и этап — те же, что будут на показе: от них зависят требования семестра в подсказках.

```bash
for f in content-formats content-kb content-neural content-clean hidden-text; do
  SAPSR_LLM_MODE=record SAPSR_LLM_REPLAY_DIR=../test-fixtures/llm-replay \
    python analyzer.py ../test-fixtures/hybrid/$f.pdf --semester 7 --stage 2 --report v2 > /dev/null
  SAPSR_LLM_MODE=record SAPSR_LLM_REPLAY_DIR=../test-fixtures/llm-replay \
    python analyzer.py ../test-fixtures/hybrid/$f.pdf --semester 7 --stage 2 --report v2 --mode neural > /dev/null
done
```

Проверить: тот же отчёт без модели — `SAPSR_LLM_MODE=replay SAPSR_LLM_REPLAY_DIR=../test-fixtures/llm-replay python analyzer.py … --semester 7 --stage 2 --report v2`.

В записи — только ответы модели, но в них есть цитаты из документов. Поэтому записываются только вымышленные записки из `test-fixtures/hybrid`, чужие работы — нет.
