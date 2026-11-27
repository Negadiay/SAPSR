"""Оценка качества проверки на корпусе и сравнение режимов (план 3-й опроцентовки, Т3).

Прогоняет документы test-fixtures/hybrid в режимах symbolic (только правила), neural (только модель)
и hybrid, сравнивает замечания с разметкой `truth` из expected.json и считает по категориям точность,
полноту и F1, долю корректных документов с лишними замечаниями, долю выдуманных моделью цитат, число
блокирующих замечаний от модели (должно быть 0) и время. С --repeat проверяется воспроизводимость:
отчёты повторных запусков должны совпадать (без времени).

    python tools/evaluate.py                          # replay: записанные ответы qwen3.5:4b, без Ollama
    python tools/evaluate.py --llm live --repeat 1    # живая модель
    python tools/evaluate.py --out ../docs/testing/quality

Как считается совпадение. Замечание правила или связки «модель + правило» сопоставляется с разметкой по
идентификатору требования. У чисто нейросетевого режима идентификаторов нет (модель пишет свободный
текст), поэтому его содержательные замечания сопоставляются по числу: лишние сверх размеченных —
ложные срабатывания. Замечания из `truth.allowed` (спорные формулировки) не считаются ни верными,
ни ложными. Требования семестра оцениваются только у документов с `truth.requirements_missing`.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import sys
import time
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(WORKER))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from record_replay import CORPUS, REPLAY, corpus_documents, profile_of

MODES = ("symbolic", "neural", "hybrid")
MODE_TITLES = {"symbolic": "только правила", "neural": "только модель", "hybrid": "гибридный"}
CATEGORIES = ("format", "structure", "content", "requirements")
CATEGORY_TITLES = {"format": "оформление", "structure": "структура", "content": "содержание (согласованность)",
                   "requirements": "требования семестра"}
MACHINE = "ThinkPad P16v, RTX A500 4 ГБ, 32 ГБ ОЗУ"


@dataclass
class Counts:
    tp: int = 0
    fp: int = 0
    fn: int = 0

    def add(self, tp: int, fp: int, fn: int) -> None:
        self.tp, self.fp, self.fn = self.tp + tp, self.fp + fp, self.fn + fn

    @property
    def precision(self) -> float | None:
        return self.tp / (self.tp + self.fp) if self.tp + self.fp else None

    @property
    def recall(self) -> float | None:
        return self.tp / (self.tp + self.fn) if self.tp + self.fn else None

    @property
    def f1(self) -> float | None:
        p, r = self.precision, self.recall
        if p is None or r is None:
            return None
        return 2 * p * r / (p + r) if p + r else 0.0


@dataclass
class DocResult:
    name: str
    mode: str
    group: str
    expected: list[str]
    got: list[str]
    extra: list[str]
    missed: list[str]
    ms: int
    llm_blocking: int
    hallucinated: float | None
    failure: str | None = None
    counts: dict[str, Counts] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.extra and not self.missed


def category_of(kb, requirement_id: str, finding_category: str | None = None) -> str:
    """Категория замечания для таблиц: целостность файла считается оформлением."""
    rule = kb.rules.get(requirement_id)
    category = rule.category if rule is not None else finding_category
    if requirement_id.startswith("REQ") or category == "requirements":
        return "requirements"
    if category == "content":
        return "content"
    return "structure" if category == "structure" else "format"


def compare(kb, entry: dict, findings: list[dict], mode: str) -> tuple[dict[str, Counts], list[str], list[str]]:
    """Сравнение замечаний одного документа с разметкой: счётчики по категориям, лишние и пропущенные."""
    truth = entry.get("truth", {"findings": entry.get("findings", [])})
    allowed = set(truth.get("allowed", []))
    expected: dict[str, set[str]] = defaultdict(set)
    for rid in truth.get("findings", []):
        expected[category_of(kb, rid)].add(rid)
    labelled_requirements = "requirements_missing" in truth
    if labelled_requirements:
        expected["requirements"].update(truth["requirements_missing"])

    got: dict[str, set[str]] = defaultdict(set)
    neural_content = 0
    for f in findings:
        rid = f["requirement_id"]
        if rid in allowed:
            continue
        category = category_of(kb, rid, f.get("category"))
        if category == "requirements" and not labelled_requirements:
            continue
        if f.get("origin") == "neural":
            neural_content += 1
        else:
            got[category].add(rid)

    counts = {c: Counts() for c in CATEGORIES}
    extra: list[str] = []
    missed: list[str] = []
    for category in CATEGORIES:
        exp, have = expected[category], got[category]
        if category == "content" and mode == "neural":
            tp = min(len(exp), neural_content)
            counts[category].add(tp, neural_content - tp, len(exp) - tp)
            extra += ["замечание модели"] * (neural_content - tp)
            missed += sorted(exp)[tp:]
            continue
        counts[category].add(len(exp & have), len(have - exp), len(exp - have))
        extra += sorted(have - exp)
        missed += sorted(exp - have)
    return counts, extra, missed


def stable(report: dict) -> str:
    """Отчёт без времени и счётчиков обращений к модели — для сравнения повторных запусков."""
    pipeline = {k: v for k, v in report["pipeline"].items() if k not in ("stages", "metrics")}
    body = {k: v for k, v in report.items() if k not in ("generated_at", "pipeline")}
    return json.dumps({**body, "pipeline": pipeline}, ensure_ascii=False, sort_keys=True)


def build_service(llm_mode: str, replay_dir: Path):
    os.environ["SAPSR_LLM_MODE"] = "off" if llm_mode == "stub" else llm_mode
    os.environ["SAPSR_LLM_REPLAY_DIR"] = str(replay_dir)
    os.environ.setdefault("SAPSR_IIS_MODE", "off")
    os.environ.setdefault("SAPSR_TASK_TIMEOUT_S", "3600")
    import analyzer
    if llm_mode != "stub":
        return analyzer.build_service()
    sys.path.insert(0, str(WORKER / "tests"))
    from scripted_llm import ScriptedModel

    from sapsr.llm.client import LlmClient, LlmConfig
    return analyzer.build_service(llm=LlmClient(LlmConfig(mode="stub", model="scripted"), stub=ScriptedModel()))


def evaluate(names: list[str] | None = None, modes=MODES, llm_mode: str = "replay", repeat: int = 1,
             replay_dir: Path = REPLAY, corpus: Path = CORPUS) -> dict:
    from analyzer import check_pdf
    from sapsr.errors import CheckFailure

    service = build_service(llm_mode, replay_dir)
    kb = service.kb
    documents = corpus_documents(corpus)
    names = names or list(documents)
    results: list[DocResult] = []
    reproducible = {mode: True for mode in modes}
    env: dict = {}
    for name in names:
        entry = documents[name]
        for mode in modes:
            first = None
            failure = None
            ms = 0
            for _ in range(max(1, repeat)):
                started = time.perf_counter()
                try:
                    report = check_pdf(str(corpus / name), service, mode=mode,
                                       source_format=entry.get("source_format", "pdf"), **profile_of(entry))
                except CheckFailure as exc:
                    failure, report = exc.code, None
                    break
                ms = ms or int((time.perf_counter() - started) * 1000)
                if first is None:
                    first = report
                elif stable(report) != stable(first):
                    reproducible[mode] = False
            if failure is not None or "failure" in entry:
                ok = failure == entry.get("failure")
                results.append(DocResult(name, mode, entry.get("group", ""), [entry.get("failure", "—")],
                                         [failure or "отчёт"], [] if ok else [failure or "отчёт"],
                                         [] if ok else [entry.get("failure", "")], 0, 0, None, failure=failure))
                continue
            findings = first["findings"]
            counts, extra, missed = compare(kb, entry, findings, mode)
            truth = entry.get("truth", {"findings": entry.get("findings", [])})
            metrics = first["pipeline"]["metrics"]
            results.append(DocResult(
                name=name, mode=mode, group=entry.get("group", ""),
                expected=sorted(truth.get("findings", []) + truth.get("requirements_missing", [])),
                got=sorted(f["requirement_id"] for f in findings), extra=extra, missed=missed, ms=ms,
                llm_blocking=sum(1 for f in findings if f["blocking"] and f["origin"] != "symbolic"),
                hallucinated=metrics.get("claims_hallucinated_quote_share") if mode == "hybrid" else None,
                counts=counts))
            if not env:
                env = {"kb_version": first["pipeline"].get("kb_version"),
                       "worker_version": first["pipeline"].get("worker_version")}
            llm = next((m for m in first["pipeline"]["models"] if m["role"] == "llm"), None)
            if llm and "model" not in env:
                env["model"] = llm["name"]
                env["quantization"] = llm.get("quantization")
    return {"results": results, "reproducible": reproducible, "env": env, "documents": documents,
            "llm_mode": llm_mode, "repeat": repeat, "modes": list(modes)}


def summarize(run: dict) -> dict:
    """Сводные числа по режимам."""
    summary = {}
    for mode in run["modes"]:
        rows = [r for r in run["results"] if r.mode == mode and r.failure is None and r.counts]
        totals = {c: Counts() for c in CATEGORIES}
        overall = Counts()
        for r in rows:
            for c in CATEGORIES:
                totals[c].add(r.counts[c].tp, r.counts[c].fp, r.counts[c].fn)
                overall.add(r.counts[c].tp, r.counts[c].fp, r.counts[c].fn)
        clean = [r for r in rows if not r.expected]
        hallucinated = [r.hallucinated for r in rows if r.hallucinated is not None]
        summary[mode] = {
            "categories": totals, "overall": overall, "documents": len(rows),
            "clean_documents": len(clean), "clean_with_extra": sum(1 for r in clean if r.extra),
            "llm_blocking": sum(r.llm_blocking for r in rows),
            "hallucinated": sum(hallucinated) / len(hallucinated) if hallucinated else None,
            "mean_ms": int(sum(r.ms for r in rows) / len(rows)) if rows else 0,
            "exact": sum(1 for r in rows if r.ok),
        }
    return summary


# ── вывод ───────────────────────────────────────────────────────────────────

def _num(value: float | None) -> str:
    return "—" if value is None else f"{value:.2f}".replace(".", ",")


def _pct(part: int, whole: int) -> str:
    return "—" if not whole else f"{part} из {whole} ({part / whole * 100:.0f} %)"


def to_markdown(run: dict, command: str) -> str:
    summary = summarize(run)
    env = run["env"]
    live = run["llm_mode"] == "live"
    model = env.get("model") or "—"
    lines = [
        "# Качество проверки: сравнение режимов",
        "",
        "Получено скриптом `worker-python/tools/evaluate.py`; исходные числа — в файле CSV рядом.",
        "",
        "## Среда",
        "",
        "| Что | Значение |",
        "|---|---|",
        f"| Дата | {datetime.now():%d.%m.%Y %H:%M} |",
        f"| Машина | {MACHINE}; {platform.system()} {platform.release()}, Python {platform.python_version()} |",
        f"| Модель | {model}" + (f", {env['quantization']}" if env.get("quantization") else "") + " (Ollama, температура 0, seed 42) |",
        f"| Режим модели | `{run['llm_mode']}`" + (" — живая модель" if live else " — записанные ответы этой модели, "
                                                 "`test-fixtures/llm-replay`" if run["llm_mode"] == "replay" else "") + " |",
        f"| Версия базы знаний | {env.get('kb_version', '—')} |",
        f"| Версия обработчика | {env.get('worker_version', '—')} |",
        f"| Корпус | `test-fixtures/hybrid`, документов: {len({r.name for r in run['results']})} |",
        f"| Повторов | {run['repeat']} |",
        f"| Команда | `{command}` |",
        "",
        "## Сводка по режимам",
        "",
        "| Показатель | " + " | ".join(MODE_TITLES[m] for m in run["modes"]) + " |",
        "|---|" + "---|" * len(run["modes"]),
    ]

    def row(title: str, cell) -> None:
        lines.append(f"| {title} | " + " | ".join(cell(summary[m], m) for m in run["modes"]) + " |")

    row("Точность (все категории)", lambda s, m: _num(s["overall"].precision))
    row("Полнота (все категории)", lambda s, m: _num(s["overall"].recall))
    row("F1 (все категории)", lambda s, m: _num(s["overall"].f1))
    row("Документов с результатом точно по разметке", lambda s, m: _pct(s["exact"], s["documents"]))
    row("Корректных документов с лишними замечаниями", lambda s, m: _pct(s["clean_with_extra"], s["clean_documents"]))
    row("Блокирующих замечаний от модели", lambda s, m: str(s["llm_blocking"]))
    row("Доля выдуманных цитат (отсеяны до правил)", lambda s, m: _num(s["hallucinated"]) if m == "hybrid" else "—")
    row("Среднее время на документ", lambda s, m: f"{s['mean_ms'] / 1000:.1f} с".replace(".", ","))
    row("Воспроизводимость (отчёты повторов совпали)",
        lambda s, m: ("да" if run["reproducible"][m] else "НЕТ") if run["repeat"] > 1 else "не проверялась")
    if not live:
        lines += ["", ("Время в режиме `replay` — без времени ответа модели (ответы берутся из записи); "
                      "время с живой моделью — в `performance.md`.")]

    lines += ["", "## По категориям", "",
              "TP — верно найдено, FP — лишнее, FN — пропущено.", "",
              "| Режим | Категория | TP | FP | FN | Точность | Полнота | F1 |", "|---|---|---|---|---|---|---|---|"]
    for mode in run["modes"]:
        for category in CATEGORIES:
            c = summary[mode]["categories"][category]
            lines.append(f"| {MODE_TITLES[mode]} | {CATEGORY_TITLES[category]} | {c.tp} | {c.fp} | {c.fn} | "
                         f"{_num(c.precision)} | {_num(c.recall)} | {_num(c.f1)} |")

    lines += ["", "## По документам", "",
              "| Документ | Группа | Режим | Ожидалось | Лишнее | Пропущено | Итог |", "|---|---|---|---|---|---|---|"]
    groups = {"typical": "типичный", "boundary": "граничный", "negative": "отрицательный"}
    for r in run["results"]:
        verdict = "совпало" if r.ok else "расхождение"
        lines.append(f"| `{r.name}` | {groups.get(r.group, r.group)} | {MODE_TITLES[r.mode]} | "
                     f"{', '.join(r.expected) or 'нет замечаний'} | {', '.join(r.extra) or '—'} | "
                     f"{', '.join(r.missed) or '—'} | {verdict} |")
    lines += ["", ("Режим «только правила» не проверяет содержание, режим «только модель» — оформление: "
                  "пропуски в этих категориях — их свойство, а не сбой."), ""]
    return "\n".join(lines)


def write_csv(run: dict, path: Path) -> None:
    with open(path, "w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["document", "group", "mode", "category", "tp", "fp", "fn", "ms", "llm_blocking",
                         "hallucinated_quote_share", "expected", "got", "extra", "missed"])
        for r in run["results"]:
            if r.failure is not None or not r.counts:
                writer.writerow([r.name, r.group, r.mode, "failure", "", "", "", "", "", "",
                                 ";".join(r.expected), ";".join(r.got), ";".join(r.extra), ";".join(r.missed)])
                continue
            for category in CATEGORIES:
                c = r.counts[category]
                writer.writerow([r.name, r.group, r.mode, category, c.tp, c.fp, c.fn, r.ms, r.llm_blocking,
                                 "" if r.hallucinated is None else r.hallucinated,
                                 ";".join(r.expected), ";".join(r.got), ";".join(r.extra), ";".join(r.missed)])


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("documents", nargs="*", help="имена файлов корпуса; по умолчанию — все")
    parser.add_argument("--modes", nargs="+", default=list(MODES), choices=MODES)
    parser.add_argument("--llm", default="replay", choices=("replay", "live", "record", "stub"))
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--replay-dir", type=Path, default=REPLAY)
    parser.add_argument("--out", type=Path, help="путь без расширения: будут записаны .md и .csv")
    args = parser.parse_args(argv)

    run = evaluate(args.documents or None, args.modes, args.llm, args.repeat, args.replay_dir)
    command = "python tools/evaluate.py " + " ".join(sys.argv[1:] if argv is None else argv)
    text = to_markdown(run, command.strip())
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.with_suffix(".md").write_text(text, encoding="utf-8", newline="\n")
        write_csv(run, args.out.with_suffix(".csv"))
        print(f"записано: {args.out.with_suffix('.md')}, {args.out.with_suffix('.csv')}")
    else:
        print(text)
    return 0 if all(run["reproducible"].values()) else 1


if __name__ == "__main__":
    sys.exit(main())
