"""Титульный лист: обозначение работы, город и год, руководство кафедры, ФИО студента.

Справочники ИИС приходят через ctx.directory; недоступный справочник — не замечание,
а пропущенная проверка в pipeline.skipped.
"""

from __future__ import annotations

import re
from typing import Optional

from sapsr.checks.context import CheckContext
from sapsr.checks.registry import Hit, Params, SkipCheck, check
from sapsr.document.text import clean_text, extract_context, normalize_code, normalize_person_name
from sapsr.report.model import Evidence


def _context(ctx: CheckContext, text: str, pattern: re.Pattern) -> str:
    cp = ctx.layout.context
    return extract_context(text, pattern, cp.before, cp.after, cp.max_len)


def _title_evidence(quote: Optional[str] = None) -> list[Evidence]:
    return [Evidence(page=1, quote=quote, section="Титульный лист")]


# ── обозначение работы ──────────────────────────────────────────────────────

class SpecialtyParams(Params):
    # Регулярные выражения обозначения; первая группа — код специальности. Пробуются по порядку.
    designation_patterns: list[str]


@check("title.specialty_code", SpecialtyParams, provides=("code",))
def specialty_code(ctx: CheckContext, params: SpecialtyParams) -> list[Hit]:
    text = clean_text(ctx.raw.full_text)
    for raw_pattern in params.designation_patterns:
        pattern = re.compile(raw_pattern)
        match = pattern.search(text)
        if match:
            break
    else:
        return []
    known = ctx.directory.specialties()
    if known is None:
        raise SkipCheck("IIS_UNAVAILABLE", "Справочник специальностей ИИС недоступен — код специальности не сверялся.")
    code = match.group(1).strip()
    if normalize_code(code) in known:
        return []
    return [Hit(values={"code": code}, evidence=_title_evidence(match.group(0)),
                pages=[1], context=_context(ctx, text, pattern), location="Страница 1, обозначение работы")]


# ── город и год ─────────────────────────────────────────────────────────────

class CityYearParams(Params):
    # Строка «Минск 2026»: год — в первой или второй группе.
    city_year_pattern: str
    year_pattern: str


def _city_year(ctx: CheckContext, params: CityYearParams):
    pattern = re.compile(params.city_year_pattern, re.IGNORECASE)
    match = pattern.search(ctx.raw.first_page_text)
    if not match:
        return None, pattern
    return int(match.group(1) or match.group(2)), pattern


@check("title.city_year_present", CityYearParams, provides=("current_year",))
def city_year_present(ctx: CheckContext, params: CityYearParams) -> list[Hit]:
    year, _ = _city_year(ctx, params)
    if year is not None:
        return []
    return [Hit(values={"current_year": ctx.now.year}, evidence=_title_evidence(), pages=[1], location="Страница 1")]


@check("title.year_current", CityYearParams, provides=("year", "current_year"))
def year_current(ctx: CheckContext, params: CityYearParams) -> list[Hit]:
    year, pattern = _city_year(ctx, params)
    if year is None or year == ctx.now.year:
        return []
    return [Hit(values={"year": year, "current_year": ctx.now.year}, evidence=_title_evidence(),
                pages=[1], context=_context(ctx, ctx.raw.first_page_text, pattern), location="Страница 1")]


@check("title.years_consistent", CityYearParams, provides=("year", "other_years"))
def years_consistent(ctx: CheckContext, params: CityYearParams) -> list[Hit]:
    year, _ = _city_year(ctx, params)
    if year is None or year != ctx.now.year:
        return []
    year_re = re.compile(params.year_pattern)
    others = sorted({int(y) for y in year_re.findall(ctx.raw.first_page_text)} - {year})
    if not others:
        return []
    return [Hit(values={"year": year, "other_years": ", ".join(map(str, others))}, evidence=_title_evidence(),
                pages=[1], context=_context(ctx, ctx.raw.first_page_text, year_re), location="Страница 1")]


# ── люди на титульном листе ────────────────────────────────────────────────

class PersonPatterns(Params):
    # Форматы ФИО по порядку предпочтения: «Фамилия Имя Отчество», «И.О. Фамилия», «Фамилия И.О.».
    person_patterns: list[str]
    # Сколько строк ниже подписи роли искать ФИО.
    lines_below: int


def find_person(text: str, label: re.Pattern, people: PersonPatterns) -> Optional[str]:
    patterns = [re.compile(p) for p in people.person_patterns]
    lines = [line.strip() for line in text.splitlines()]
    for idx, line in enumerate(lines):
        label_match = label.search(line)
        if not label_match:
            continue
        after = line[label_match.end():].strip(" :-—–\t")
        candidates = ([after] if after else []) + [
            lines[idx + k] for k in range(1, people.lines_below + 1) if idx + k < len(lines)
        ]
        for candidate in candidates:
            for pattern in patterns:
                match = pattern.search(candidate)
                if match:
                    return match.group(0)
    return None


class RoleParams(PersonPatterns):
    label_pattern: str
    role_name: str
    role_name_genitive: str
    role_title: str


def _label(params: RoleParams) -> re.Pattern:
    return re.compile(params.label_pattern, re.IGNORECASE)


_ROLE_VALUES = ("role_name", "role_name_genitive", "role_title")


def _role_values(params: RoleParams, **extra) -> dict:
    return {**{k: getattr(params, k) for k in _ROLE_VALUES}, **extra}


@check("title.role_present", RoleParams, provides=_ROLE_VALUES)
def role_present(ctx: CheckContext, params: RoleParams) -> list[Hit]:
    if _label(params).search(ctx.raw.first_page_text):
        return []
    return [Hit(values=_role_values(params), evidence=_title_evidence(), pages=[1], location="Страница 1")]


@check("title.role_name_parsed", RoleParams, provides=_ROLE_VALUES)
def role_name_parsed(ctx: CheckContext, params: RoleParams) -> list[Hit]:
    label = _label(params)
    text = ctx.raw.first_page_text
    if not label.search(text) or find_person(text, label, params):
        return []
    return [Hit(values=_role_values(params), evidence=_title_evidence(), pages=[1],
                context=_context(ctx, text, label), location="Страница 1")]


@check("title.role_in_directory", RoleParams, provides=_ROLE_VALUES + ("name",))
def role_in_directory(ctx: CheckContext, params: RoleParams) -> list[Hit]:
    label = _label(params)
    text = ctx.raw.first_page_text
    name = find_person(text, label, params) if label.search(text) else None
    if not name:
        return []
    known = ctx.directory.employee_names()
    if known is None:
        raise SkipCheck("IIS_UNAVAILABLE",
                        f"Справочник сотрудников ИИС недоступен — {params.role_name} не сверялся.")
    if normalize_person_name(name) in known:
        return []
    return [Hit(values=_role_values(params, name=name), evidence=_title_evidence(name), pages=[1],
                context=_context(ctx, text, label), location="Страница 1")]


class StudentParams(PersonPatterns):
    label_pattern: str


def student_on_title(ctx: CheckContext, params: StudentParams) -> Optional[str]:
    return find_person(ctx.raw.first_page_text, re.compile(params.label_pattern, re.IGNORECASE), params)


def surname_matches(found: str, last_name: str) -> bool:
    words = re.findall(r"[а-я]+(?:-[а-я]+)?", normalize_person_name(found))
    return normalize_person_name(last_name) in words


@check("title.student_matches_registration", StudentParams, provides=("name",))
def student_matches_registration(ctx: CheckContext, params: StudentParams) -> list[Hit]:
    """Фамилия на титуле совпадает с зарегистрированной — защита от подмены (план Котко, К2).
    Только предупреждение преподавателю, сдачу не блокирует."""
    last_name = ctx.task.student.last_name
    if not last_name:
        return []
    found = student_on_title(ctx, params)
    if not found or surname_matches(found, last_name):
        return []
    return [Hit(values={"name": found}, evidence=_title_evidence(found), pages=[1], location="Страница 1")]
