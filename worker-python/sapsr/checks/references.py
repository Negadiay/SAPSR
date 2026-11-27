"""Список использованных источников: количество и оформление записей.

Записи берутся из блоков bibliography_entry модели документа. Проверка оформления —
перенос прежней проверки по признакам образцов 2025 года; разбор записи на поля
моделью — задача З7 (уровень [C]).
"""

from __future__ import annotations

import re

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, Params, check
from sapsr.report.model import Evidence


class CountParams(Params):
    # Срабатывает, если записей at_least ≤ n < fewer_than.
    fewer_than: int
    at_least: int
    # Рекомендуемое число — только для текста замечания.
    recommended: int


@check("references.count", CountParams, provides=("count",))
def count(ctx: CheckContext, params: CountParams) -> list[Hit]:
    if ctx.references_section() is None:
        return []  # отсутствие раздела — отдельное правило структуры
    n = len(ctx.doc.references)
    if params.at_least <= n < params.fewer_than:
        section = ctx.references_section()
        return [Hit(values={"count": n}, evidence=[Evidence(page=section.page_start, section=section.title)],
                    location="Список использованных источников")]
    return []


class BibFormatParams(Params):
    # Признаки записи — регулярные выражения (без учёта регистра).
    re_entry_number: str
    re_year: str
    re_url: str
    re_zone_dash: str
    re_access_date: str
    re_pages: str
    re_local_media: str
    re_official: str
    re_site_mark: str
    re_doi: str
    # Сколько подозрительных записей перечислить в поле found и сколько показать фрагментами.
    list_limit: int
    quote_limit: int
    quote_chars: int


def entry_issues(entry: str, rx: dict[str, re.Pattern]) -> list[str]:
    """Каких обязательных признаков образца не хватает записи."""
    text = re.sub(r"\s+", " ", rx["entry_number"].sub("", entry, count=1)).strip()
    issues: list[str] = []
    has_url = bool(rx["url"].search(text))
    has_doi = bool(rx["doi"].search(text))
    has_year = bool(rx["year"].search(text))
    composite = "//" in text
    site = bool(rx["site_mark"].search(text))
    official = bool(rx["official"].search(text))
    media = bool(rx["local_media"].search(text))

    if not rx["zone_dash"].search(text):
        issues.append("нет разделителя зон описания через длинное тире")
    if not has_year and not has_url and not has_doi:
        issues.append("нет года публикации, URL или DOI")
    if has_url and not rx["access_date"].search(text) and not has_doi:
        issues.append("для URL нет даты обращения")
    if composite:
        if not rx["pages"].search(text) and not has_url and not has_doi and not media:
            issues.append("для составной части нет страниц, URL, DOI или носителя")
    elif not site and not official and not has_url and not has_doi:
        if "/" not in text:
            issues.append("нет сведений об ответственности после «/»")
        if not rx["pages"].search(text) and not media:
            issues.append("нет объёма источника: «– 44 с.» или аналога")
    return issues


@check("references.bibliography_format", BibFormatParams, provides=("suspicious", "sample"))
def bibliography_format(ctx: CheckContext, params: BibFormatParams) -> list[Hit]:
    rx = {
        key.removeprefix("re_"): re.compile(value, re.IGNORECASE)
        for key, value in params.model_dump().items() if key.startswith("re_")
    }
    suspicious = []
    for idx, block_id in enumerate(ctx.doc.references, start=1):
        block = ctx.doc.block(block_id)
        issues = entry_issues(block.text, rx)
        if issues:
            suspicious.append((idx, block, issues))
    if not suspicious:
        return []
    sample = "; ".join(f"#{idx}: {', '.join(issues)}" for idx, _, issues in suspicious[:params.list_limit])
    return [Hit(
        values={"suspicious": len(suspicious), "sample": sample},
        evidence=[Evidence(page=b.page, block_id=b.id, quote=b.text[:params.quote_chars])
                  for _, b, _ in suspicious],
        pages=sorted({b.page for _, b, _ in suspicious}),
        context=" | ".join(b.text[:params.quote_chars] for _, b, _ in suspicious[:params.quote_limit]),
        location="Список использованных источников",
    )]
