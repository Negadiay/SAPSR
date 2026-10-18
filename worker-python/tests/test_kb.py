"""База знаний: каталог правил, профили, словарь понятий (план, З3)."""

from __future__ import annotations

import shutil

import pytest
import yaml

from sapsr.checks.engine import validate_rules
from sapsr.kb.loader import DEFAULT_KB_DIR, KnowledgeBaseError, load_kb


def test_catalog_is_complete(kb):
    """Все прежние группы проверок перенесены, у каждого правила есть источник."""
    rules = kb.rules
    assert len(rules) >= 40
    for rule in rules.values():
        assert rule.source.doc and rule.title and rule.message
    format_checks = {r.check for r in rules.values() if r.stage == "format"}
    assert {"structure.required_part", "fonts.main_size", "fonts.family", "margins.side",
            "layout.page_numbers", "layout.line_spacing", "layout.paragraph_indent", "references.count",
            "references.bibliography_format", "lists.simple_punctuation", "lists.numbered_punctuation",
            "captions.figure_mentions", "captions.table_mentions", "title.specialty_code",
            "title.city_year_present", "title.role_in_directory", "integrity.hidden_text"} <= format_checks


def test_kb_version_changes_with_content(tmp_path, kb):
    copy = tmp_path / "kb"
    shutil.copytree(DEFAULT_KB_DIR, copy)
    assert load_kb(copy).version == kb.version
    path = copy / "rules" / "20-format.yaml"
    path.write_text(path.read_text(encoding="utf-8").replace("norm_mm: 30", "norm_mm: 25"), encoding="utf-8")
    changed = load_kb(copy, validate_rules=validate_rules)
    assert changed.version != kb.version
    assert changed.rules["FMT-MARGIN-LEFT"].params["norm_mm"] == 25


def broken(tmp_path, rel, mutate):
    copy = tmp_path / "kb"
    shutil.copytree(DEFAULT_KB_DIR, copy)
    path = copy / rel
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    mutate(data)
    path.write_text(yaml.safe_dump(data, allow_unicode=True), encoding="utf-8")
    return copy


@pytest.mark.negative
@pytest.mark.parametrize("mutate, error", [
    (lambda d: d[0].update(check="no.such.check"), "нет проверки"),
    (lambda d: d[0].update(params={"side": "left"}), "параметры"),
    (lambda d: d[0].update(message="Поле {unknown_value}"), "неизвестные поля шаблона"),
    (lambda d: d[0].update(severity="fatal"), "severity"),
    (lambda d: d[0].update(id="bad id"), "не по формату"),
    (lambda d: d.append(dict(d[0])), "повторный идентификатор"),
])
def test_invalid_rules_fail_at_startup(tmp_path, mutate, error):
    copy = broken(tmp_path, "rules/20-format.yaml", mutate)
    with pytest.raises(KnowledgeBaseError, match=error):
        load_kb(copy, validate_rules=validate_rules)


@pytest.mark.negative
def test_content_rule_cannot_block(tmp_path):
    copy = broken(tmp_path, "rules/50-content.yaml", lambda d: d[0].update(blocking=True))
    with pytest.raises(KnowledgeBaseError, match="блокирующим может быть только правило оформления"):
        load_kb(copy)


@pytest.mark.negative
def test_unknown_part_in_profile(tmp_path):
    copy = broken(tmp_path, "profiles/base.yaml", lambda d: d["content"]["sections"].append("part.nonsense"))
    with pytest.raises(KnowledgeBaseError, match="неизвестная часть"):
        load_kb(copy)


def test_profile_resolution(kb):
    assert kb.resolve_profile(None, None, None).warning[0] == "DEFAULT_PROFILE"
    sem7 = kb.resolve_profile("course_project", 7, 2)
    assert sem7.id == "sem7" and sem7.warning is None
    assert sem7.content.requirement_sets == ["sem7"]
    assert sem7.content.quote_min_score == 90, "настройки наследуются от базового профиля"
    assert sem7.rule("REQ-NOT-ESTABLISHED") is not None and sem7.rule("NEURAL-FREEFORM") is None
    base = kb.resolve_profile(None, None, None)
    assert base.rule("REQ-NOT-ESTABLISHED") is None
    assert kb.resolve_profile("diploma", 8, 1).warning[0] == "PROFILE_NOT_FOUND"


def test_profile_override(tmp_path):
    copy = tmp_path / "kb"
    shutil.copytree(DEFAULT_KB_DIR, copy)
    (copy / "profiles" / "sem7_stage3.yaml").write_text(
        "id: sem7_stage3\ntitle: 7 семестр, 3 этап\nextends: sem7\n"
        "match: { semesters: [7], stages: [3] }\n"
        "overrides:\n  FMT-MARGIN-LEFT: { params: { tolerance_mm: 2 }, severity: major, blocking: true }\n"
        "  LST-SIMPLE: { enabled: false }\n", encoding="utf-8")
    kb = load_kb(copy, validate_rules=validate_rules)
    profile = kb.resolve_profile("course_project", 7, 3)
    assert profile.id == "sem7_stage3", "более точный профиль выигрывает"
    margin = profile.rule("FMT-MARGIN-LEFT")
    assert margin.params["tolerance_mm"] == 2 and margin.params["norm_mm"] == 30
    assert margin.blocking and margin.severity == "major"
    assert profile.rule("LST-SIMPLE") is None
    assert kb.resolve_profile("course_project", 7, 2).id == "sem7"


def test_concepts_are_consistent(kb):
    parts = {p.id for p in kb.concepts.doc_parts}
    assert {"part.intro", "part.requirements", "part.architecture", "part.implementation"} <= parts
    neural = kb.concepts.attribute("system.neural_component")
    assert neural.must_be_described_in == ["part.architecture", "part.implementation"]
    for attr in kb.concepts.attributes:
        assert attr.cardinality == "text" or attr.values, attr.id


def test_prompts_are_versioned(kb):
    for name in ("claims", "requirements", "neural"):
        prompt = kb.prompt(name)
        assert prompt.version == "1"
        assert "<<<НАЧАЛО ДАННЫХ>>>" in prompt.user, "текст работы — только в явных границах данных"
        assert "игнорируй" in prompt.system.lower()
