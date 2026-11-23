"""Замеры эффективности обработчика (план 3-й опроцентовки, Т5).

1. Время по этапам на записках 10, 30, 60 и 100 страниц: оформление отдельно (без модели, медиана
   нескольких запусков) и полный отчёт в режиме hybrid на живой модели.
2. Влияние объёма базы знаний: время проверки оформления при 25 %, 50 % и 100 % правил — на временной
   копии базы знаний с профилем-подмножеством (сама kb/ не меняется).
3. Нагрузка модели: скорость генерации (токенов в секунду, прямой запрос к Ollama), память видеокарты
   и оперативная (`ollama ps`, `nvidia-smi`), число обращений к модели на документ.

    python tools/measure.py --out ../docs/testing/performance              # всё, с живой моделью
    python tools/measure.py --skip-live --out ../docs/testing/performance  # только без модели

Очередь из 10 одновременных загрузок меряет scripts/e2e.ps1 (сценарий E2E-14): для неё нужен поднятый
состав. Документы собираются генератором корпуса test-fixtures/hybrid/generate.py во временную папку.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import datetime
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
REPO = WORKER.parent
sys.path.insert(0, str(WORKER))
sys.path.insert(0, str(REPO / "test-fixtures" / "hybrid"))

SIZES = (10, 30, 60, 100)
STAGES = ("extract", "structure", "format", "claims", "reasoning", "report")
STAGE_TITLES = {"extract": "извлечение", "structure": "структура", "format": "оформление",
                "claims": "модель: утверждения", "reasoning": "правила и требования", "report": "отчёт"}
MACHINE = "ThinkPad P16v, RTX A500 4 ГБ, 32 ГБ ОЗУ"
# Абзацев-заполнителей на страницу: подобрано по прогону 15.11.2026 (при 7,5 вместо 10/30/60/100 страниц
# получилось 12/34/68/114).
PARAGRAPHS_PER_PAGE = 6.5


def build_documents(target: Path, sizes=SIZES) -> dict[int, Path]:
    """Записки заданного объёма: обычная структура, разделы наполнены повторяющимися абзацами."""
    import generate as corpus

    corpus.register_fonts()
    documents: dict[int, Path] = {}
    for pages in sizes:
        extra = max(0, int((pages - 8) * PARAGRAPHS_PER_PAGE))
        filler = [f"{corpus.FILLER} Пункт {i + 1} подробно описывает очередную проверку." for i in range(extra)]
        third = len(filler) // 3
        path = target / f"note-{pages}p.pdf"
        corpus.note(path, intro=corpus.INTRO_OK, requirements=corpus.REQ_OK,
                    analysis=[corpus.FILLER] + filler[:third],
                    architecture=corpus.SEM7_ARCH + filler[third:2 * third],
                    implementation=corpus.SEM7_IMPL + filler[2 * third:],
                    testing=corpus.SEM7_TESTING, conclusion=corpus.CONCL_OK)
        documents[pages] = path
    return documents


def run_once(service, path: Path, mode: str) -> dict:
    from analyzer import check_pdf

    started = time.perf_counter()
    report = check_pdf(str(path), service, mode=mode, semester=7, stage=3)
    total = int((time.perf_counter() - started) * 1000)
    stages = {s["stage"]: s.get("ms", 0) for s in report["pipeline"]["stages"]}
    metrics = report["pipeline"]["metrics"]
    return {"pages": report["document"]["pages"], "total_ms": total, "stages": stages, "metrics": metrics,
            "findings": len(report["findings"]), "kb_version": report["pipeline"].get("kb_version"),
            "worker_version": report["pipeline"].get("worker_version"),
            "skipped": sorted({s["reason"] for s in report["pipeline"]["skipped"]}),
            "models": report["pipeline"]["models"]}


def measure_format(documents: dict[int, Path], repeats: int, kb_dir: Path | None = None,
                   profile_id: str | None = None) -> list[dict]:
    """Оформление без модели: медиана нескольких запусков."""
    os.environ["SAPSR_LLM_MODE"] = "off"
    os.environ["SAPSR_IIS_MODE"] = "off"
    if kb_dir is not None:
        os.environ["SAPSR_KB_DIR"] = str(kb_dir)
    else:
        os.environ.pop("SAPSR_KB_DIR", None)
    from analyzer import build_service, check_pdf

    service = build_service()
    rows = []
    for pages, path in documents.items():
        runs = []
        for _ in range(repeats):
            if profile_id is None:
                runs.append(run_once(service, path, "symbolic"))
            else:
                started = time.perf_counter()
                report = check_pdf(str(path), service, mode="symbolic", profile_id=profile_id)
                runs.append({"pages": report["document"]["pages"],
                             "total_ms": int((time.perf_counter() - started) * 1000),
                             "stages": {s["stage"]: s.get("ms", 0) for s in report["pipeline"]["stages"]},
                             "kb_version": report["pipeline"].get("kb_version"),
                             "worker_version": report["pipeline"].get("worker_version")})
        median = sorted(runs, key=lambda r: r["total_ms"])[len(runs) // 2]
        rows.append({"target": pages, **median,
                     "format_ms": int(statistics.median(r["stages"].get("format", 0) for r in runs))})
    return rows


def kb_subset(share: float, tmp: Path) -> tuple[Path, str, int]:
    """Копия базы знаний с профилем, в котором включена доля правил оформления."""
    from sapsr.checks.engine import GATE_CHECKS
    from sapsr.kb.loader import load_kb

    kb = load_kb()
    rules = [r for r in kb.rules.values() if r.stage == "format"]
    gates = [r.id for r in rules if r.check in GATE_CHECKS]
    others = [r.id for r in rules if r.check not in GATE_CHECKS]
    chosen = gates + others[:max(1, round(len(others) * share))]
    target = tmp / f"kb-{int(share * 100)}"
    shutil.copytree(kb.root, target, ignore=shutil.ignore_patterns("__pycache__"))
    profile_id = f"measure{int(share * 100)}"
    lines = [f"id: {profile_id}", f"title: Замер — {int(share * 100)} % правил оформления", "extends: base",
             "rules:", "  include:"] + [f"    - {rid}" for rid in chosen] + ["content:", "  enabled: false", ""]
    (target / "profiles" / f"{profile_id}.yaml").write_text("\n".join(lines), encoding="utf-8")
    return target, profile_id, len(chosen)


def measure_live(documents: dict[int, Path], model: str) -> list[dict]:
    os.environ["SAPSR_LLM_MODE"] = "live"
    os.environ["SAPSR_LLM_MODEL"] = model
    os.environ["SAPSR_IIS_MODE"] = "off"
    os.environ.pop("SAPSR_KB_DIR", None)
    # Свой пустой кэш: замеряется работа модели, а не чтение прошлых ответов.
    os.environ["SAPSR_LLM_CACHE_DIR"] = tempfile.mkdtemp(prefix="sapsr-measure-cache-")
    from analyzer import build_service

    service = build_service()
    if service.llm is None or not service.llm.available():
        raise RuntimeError("модель недоступна — запустите Ollama")
    rows = []
    for pages, path in documents.items():
        row = {"target": pages, **run_once(service, path, "hybrid")}
        print(f"  {pages} стр.: {row['total_ms'] / 1000:.0f} с, обращений к модели: {row['metrics'].get('llm_calls')}",
              flush=True)
        rows.append(row)
    return rows


def model_load(model: str, base_url: str = "http://127.0.0.1:11434") -> dict:
    """Скорость генерации и память: прямой запрос к Ollama, затем `ollama ps` и `nvidia-smi`."""
    body = json.dumps({"model": model, "stream": False, "think": False,
                       "prompt": "Перечисли пять требований к оформлению пояснительной записки, по одному предложению.",
                       "options": {"temperature": 0, "seed": 42, "num_ctx": 8192}}).encode()
    request = urllib.request.Request(base_url + "/api/generate", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=300) as response:
        answer = json.loads(response.read())
    load = {"model": model,
            "eval_tokens": answer.get("eval_count"),
            "tokens_per_s": round(answer["eval_count"] / (answer["eval_duration"] / 1e9), 1) if answer.get("eval_duration") else None,
            "prompt_tokens_per_s": round(answer["prompt_eval_count"] / (answer["prompt_eval_duration"] / 1e9), 1)
            if answer.get("prompt_eval_duration") else None,
            "load_s": round(answer.get("load_duration", 0) / 1e9, 1)}

    def run(*command: str) -> str:
        try:
            return subprocess.run(command, capture_output=True, text=True, timeout=30, check=False).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ""

    ps = [line for line in run("ollama", "ps").splitlines() if model in line]
    load["ollama_ps"] = " ".join(ps[0].split()) if ps else ""
    load["gpu"] = run("nvidia-smi", "--query-gpu=name,memory.used,memory.total", "--format=csv,noheader")
    details = run("ollama", "show", model)
    load["quantization"] = next((line.split()[-1] for line in details.splitlines() if "quantization" in line.lower()), "")
    return load


# ── вывод ───────────────────────────────────────────────────────────────────

def _s(ms: float | None) -> str:
    return "—" if ms is None else f"{ms / 1000:.1f}".replace(".", ",")


def to_markdown(data: dict, command: str) -> str:
    fmt, live, volume, load = data["format"], data.get("live"), data["volume"], data.get("load")
    env = fmt[0]
    lines = [
        "# Эффективность: время отклика, нагрузка модели, объём базы знаний", "",
        ("Получено скриптом `worker-python/tools/measure.py`; исходные числа — в файле CSV рядом. "
        "Очередь из одновременных загрузок — в `e2e-results.md` (E2E-14)."), "",
        "## Среда", "", "| Что | Значение |", "|---|---|",
        f"| Дата | {datetime.now():%d.%m.%Y %H:%M} |",
        f"| Машина | {MACHINE}; {platform.system()} {platform.release()}, Python {platform.python_version()} |",
        f"| Модель | {data['model']}" + (f", {load['quantization']}" if load and load.get("quantization") else "")
        + " (Ollama, температура 0, seed 42, окно 8192 токена) |",
        "| Режим модели | `live` — живая модель, без кэша ответов" + ("" if live else " (в этом запуске не измерялась)") + " |",
        f"| Версия базы знаний | {env.get('kb_version', '—')} |",
        f"| Версия обработчика | {env.get('worker_version', '—')} |",
        f"| Документы | записки обычной структуры на {', '.join(str(r['pages']) for r in fmt)} стр., генератор корпуса |",
        f"| Команда | `{command}` |", "",
        "## Проверка оформления (без модели)", "",
        f"Медиана {data['repeats']} запусков, секунды. Критерий: документ до 100 страниц — не дольше 60 с.", "",
        "| Страниц | Всего, с | извлечение | структура | оформление | Критерий |", "|---|---|---|---|---|---|",
    ]
    for r in fmt:
        st = r["stages"]
        lines.append(f"| {r['pages']} | {_s(r['total_ms'])} | {_s(st.get('extract'))} | {_s(st.get('structure'))} | "
                     f"{_s(st.get('format'))} | {'выполнен' if r['total_ms'] <= 60000 else 'НЕ выполнен'} |")
    if live:
        lines += ["", "## Полный отчёт (hybrid, живая модель)", "",
                  ("Секунды по этапам. Критерий: документ до 60 страниц — не дольше 5 мин. "
                  "Текста в модель уходит не больше бюджета профиля (60 тыс. символов), поэтому после 30–40 страниц "
                  "время почти не растёт: проверяются разделы с наибольшим приоритетом, остальное помечается "
                  "как не поместившееся (`BUDGET`)."), "",
                  "| Страниц | Всего, с | " + " | ".join(STAGE_TITLES[s] for s in STAGES) +
                  " | Обращений к модели | Время модели, с | Не проверено | Критерий |",
                  "|---|---|" + "---|" * len(STAGES) + "---|---|---|---|"]
        for r in live:
            st, m = r["stages"], r["metrics"]
            criterion = "—" if r["pages"] > 60 else ("выполнен" if r["total_ms"] <= 300000 else "НЕ выполнен")
            skipped = ", ".join(s for s in r["skipped"] if s not in ("IIS_UNAVAILABLE",)) or "—"
            lines.append(f"| {r['pages']} | {_s(r['total_ms'])} | " + " | ".join(_s(st.get(s)) for s in STAGES) +
                         f" | {m.get('llm_calls', '—')} | {_s(m.get('llm_ms'))} | {skipped} | {criterion} |")
    lines += ["", "## Влияние объёма базы знаний", "",
              (f"Время этапа «оформление» при доле включённых правил оформления; медиана {data['repeats']} запусков, "
              "миллисекунды. Правила-«ворота» (есть ли страницы и текст) включены всегда."), "",
              "| Правил | " + " | ".join(f"{r['pages']} стр." for r in fmt) + " |", "|---|" + "---|" * len(fmt)]
    for item in volume:
        lines.append(f"| {item['share']} % ({item['rules']} правил) | "
                     + " | ".join(str(r["format_ms"]) for r in item["rows"]) + " |")
    if load:
        lines += ["", "## Нагрузка модели", "", "| Показатель | Значение |", "|---|---|",
                  f"| Скорость генерации | {load.get('tokens_per_s', '—')} токенов/с (ответ {load.get('eval_tokens', '—')} токенов) |",
                  f"| Скорость чтения подсказки | {load.get('prompt_tokens_per_s', '—')} токенов/с |",
                  f"| Загрузка модели в память | {load.get('load_s', '—')} с |",
                  f"| `ollama ps` | {load.get('ollama_ps') or '—'} |",
                  f"| Видеокарта (занято / всего) | {load.get('gpu') or '—'} |"]
        if live:
            lines.append("| Обращений к модели на документ | "
                         + ", ".join(f"{r['pages']} стр. — {r['metrics'].get('llm_calls', '—')}" for r in live) + " |")
    lines.append("")
    return "\n".join(lines)


def write_csv(data: dict, path: Path) -> None:
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["series", "pages", "total_ms", *STAGES, "llm_calls", "llm_ms", "rules"])
        for r in data["format"]:
            writer.writerow(["format", r["pages"], r["total_ms"], *[r["stages"].get(s, "") for s in STAGES], "", "", ""])
        for r in data.get("live") or []:
            writer.writerow(["hybrid_live", r["pages"], r["total_ms"], *[r["stages"].get(s, "") for s in STAGES],
                             r["metrics"].get("llm_calls", ""), r["metrics"].get("llm_ms", ""), ""])
        for item in data["volume"]:
            for r in item["rows"]:
                writer.writerow([f"kb_{item['share']}", r["pages"], r["total_ms"],
                                 *[r["stages"].get(s, "") for s in STAGES], "", "", item["rules"]])


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sizes", nargs="+", type=int, default=list(SIZES))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--model", default="qwen3.5:4b")
    parser.add_argument("--skip-live", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)

    with tempfile.TemporaryDirectory(prefix="sapsr-measure-") as tmp_name:
        tmp = Path(tmp_name)
        print("сборка документов…", flush=True)
        documents = build_documents(tmp, args.sizes)
        print("оформление…", flush=True)
        data = {"model": args.model, "repeats": args.repeats, "format": measure_format(documents, args.repeats)}
        data["volume"] = []
        for share in (0.25, 0.5, 1.0):
            kb_dir, profile_id, count = kb_subset(share, tmp)
            data["volume"].append({"share": int(share * 100), "rules": count,
                                   "rows": measure_format(documents, args.repeats, kb_dir, profile_id)})
        if not args.skip_live:
            print("живая модель…", flush=True)
            data["load"] = model_load(args.model)
            data["live"] = measure_live(documents, args.model)
    command = "python tools/measure.py " + " ".join(sys.argv[1:] if argv is None else argv)
    text = to_markdown(data, command.strip())
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.with_suffix(".md").write_text(text, encoding="utf-8", newline="\n")
        write_csv(data, args.out.with_suffix(".csv"))
        print(f"записано: {args.out.with_suffix('.md')}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
