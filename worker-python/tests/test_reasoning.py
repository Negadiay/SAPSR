"""Продукционные правила CLIPS (kb/reasoning) и требования семестра (план, З4–З5)."""

from __future__ import annotations

import clips
import pytest

from conftest import HYBRID, final_report
from sapsr.content.claims import Claim
from sapsr.content.reasoning import Reasoner, split_constructs


def rule_names(source: str) -> set[str]:
    return {parts[1] for parts in (c[1:].split(None, 2) for c in split_constructs(source))
            if len(parts) >= 2 and parts[0] == "defrule"}


def claim(cid, attr, values, section, kind, exhaustive=True):
    return Claim(id=cid, attribute=attr, values=tuple(sorted(values)), exhaustive=exhaustive, quote="q" * 20,
                 block_id="b-0001", page=1, section_id=section, section_label=section, kind=kind,
                 confidence=1.0, from_dictionary=True)


@pytest.fixture
def reasoner(kb):
    return Reasoner(kb.reasoning)


@pytest.mark.typical
def test_single_valued_conflict(reasoner, kb):
    firings = reasoner.run_claims([
        claim("c-001", "system.kb_representation", ["ontology"], "s-1", "part.intro"),
        claim("c-002", "system.kb_representation", ["config_file"], "s-5", "part.implementation"),
        claim("c-003", "system.kb_representation", ["ontology"], "s-6", "part.conclusion"),
    ], kb.concepts)
    pairs = {(f.rule, f.claims) for f in firings}
    assert pairs == {("conflict_single", ("c-001", "c-002")), ("conflict_single", ("c-002", "c-003"))}


@pytest.mark.typical
def test_multi_valued_rules(reasoner, kb):
    firings = reasoner.run_claims([
        claim("c-001", "system.input_formats", ["DOCX", "PDF"], "s-1", "part.intro"),
        claim("c-002", "system.input_formats", ["PDF"], "s-3", "part.requirements"),
        claim("c-003", "system.input_formats", ["ODT"], "s-4", "part.architecture", exhaustive=False),
        claim("c-004", "system.input_formats", ["PDF"], "s-5", "part.implementation", exhaustive=False),
    ], kb.concepts)
    pairs = {f.claims for f in firings if f.rule == "conflict_multi"}
    assert ("c-001", "c-002") in pairs, "два полных перечня различаются"
    assert ("c-001", "c-003") in pairs and ("c-002", "c-003") in pairs, "ODT нет в полном перечне"
    assert not any("c-004" in p for p in pairs), "PDF есть во всех полных перечнях"


@pytest.mark.boundary
def test_passing_mention_outside_dictionary_is_not_a_conflict(reasoner, kb):
    """Живой прогон 23.10.2026: «в формате JSON» про ответ модели попало в форматы входных файлов."""
    firings = reasoner.run_claims([
        claim("c-001", "system.input_formats", ["PDF"], "s-3", "part.requirements"),
        claim("c-002", "system.input_formats", ["json"], "s-6", "part.implementation", exhaustive=False),
        claim("c-003", "system.input_formats", ["json", "DOCX"], "s-7", "part.implementation", exhaustive=False),
    ], kb.concepts)
    pairs = {f.claims for f in firings if f.rule == "conflict_multi"}
    assert pairs == {("c-001", "c-003")}, "сравнивается только DOCX из словаря"


@pytest.mark.boundary
def test_same_section_is_not_a_conflict(reasoner, kb):
    firings = reasoner.run_claims([
        claim("c-001", "system.kb_representation", ["ontology"], "s-1", "part.intro"),
        claim("c-002", "system.kb_representation", ["frames"], "s-1", "part.intro"),
    ], kb.concepts)
    assert firings == []


@pytest.mark.typical
def test_support_rule(reasoner, kb):
    only_intro = reasoner.run_claims([claim("c-001", "system.neural_component", ["llm"], "s-1", "part.intro")],
                                     kb.concepts)
    assert [(f.rule, f.claims) for f in only_intro] == [("claim_without_support", ("c-001",))]
    described = reasoner.run_claims([
        claim("c-001", "system.neural_component", ["llm"], "s-1", "part.intro"),
        claim("c-002", "system.neural_component", ["llm"], "s-7", "part.implementation"),
    ], kb.concepts)
    assert described == []


def test_catalog_references_existing_clips_rules(kb):
    names = set().union(*(rule_names(src) for src in kb.reasoning.values()))
    assert {"conflict_single", "conflict_multi_exhaustive", "conflict_multi_outside",
            "claim_without_support", "satisfied", "violated", "disputed-structure"} <= names


def statuses(reasoner, requirement, judgements, threshold=0.75):
    rows = reasoner.run("requirements.clp", {
        "requirement": [requirement], "judgement": judgements, "threshold": [{"value": threshold}]}, "status")
    return {(r["value"], r["review"], r["reason"]) for r in rows}


def req(applicable=True, part=True):
    sym = lambda v: clips.Symbol("TRUE" if v else "FALSE")
    return {"id": "R", "applicable": sym(applicable), "part-present": sym(part)}


def judge(verdict, confidence, verified=True):
    return {"req": "R", "block": "b-0001", "verdict": clips.Symbol(verdict), "confidence": confidence,
            "verified": clips.Symbol("TRUE" if verified else "FALSE")}


@pytest.mark.parametrize("requirement, judgements, expected", [
    (req(applicable=False), [], {("not_applicable", "FALSE", "stage")}),
    (req(), [judge("supports", 0.9)], {("satisfied", "FALSE", "evidence")}),
    (req(), [judge("contradicts", 0.9)], {("violated", "FALSE", "evidence")}),
    (req(), [judge("supports", 0.9), judge("contradicts", 0.8)], {("disputed", "TRUE", "evidence")}),
    (req(), [judge("supports", 0.5)], {("not_established", "TRUE", "low-confidence")}),
    (req(), [judge("supports", 0.9, verified=False)], {("not_established", "TRUE", "no-evidence")}),
    (req(), [], {("not_established", "TRUE", "no-evidence")}),
    (req(part=False), [judge("supports", 0.9)], {("disputed", "TRUE", "structure")}),
])
def test_requirement_status_rules(reasoner, requirement, judgements, expected):
    """Расхождение правила и модели по содержательному требованию — показываются оба результата."""
    assert statuses(reasoner, requirement, judgements) == expected


@pytest.mark.typical
def test_semester_requirements_stage_2(service_factory):
    report = final_report(service_factory(), HYBRID / "content-clean.pdf", work_type="course_project",
                          semester=7, stage=2)
    assert report.profile.id == "sem7" and not report.profile.is_default
    status = {r.id: r for r in report.requirements}
    assert status["REQ7-NEURAL-ROLE"].status == "satisfied"
    assert status["REQ7-NEURAL-ROLE"].evidence[0].quote.startswith("Нейросетевая модель разметки")
    assert status["REQ7-SYMBOLIC-EXPLAIN"].status == "satisfied"
    assert status["REQ7-INTEGRATION"].status == "not_established"
    assert status["REQ7-INTEGRATION"].needs_teacher_review, "неуверенный ответ модели — на проверку преподавателю"
    assert status["REQ7-TESTING"].status == "not_applicable" and status["REQ7-EFFICIENCY"].status == "not_applicable"
    req_findings = {f.requirement_id: f for f in report.findings if f.category == "requirements"}
    assert "REQ7-NEURAL-ROLE" not in req_findings, "выполненное требование — не замечание"
    assert req_findings["REQ7-INTEGRATION"].status == "not_established"
    assert not any(f.blocking for f in req_findings.values())
    assert ("requirements", "1") in {(p.id, p.version) for p in report.pipeline.prompts}


@pytest.mark.typical
def test_semester_requirements_stage_1_not_applicable(service_factory, model):
    report = final_report(service_factory(), HYBRID / "content-clean.pdf", semester=7, stage=1)
    assert {r.status for r in report.requirements} == {"not_applicable"}
    assert not any(c["prompt"].startswith("requirements") for c in model.calls)


@pytest.mark.typical
def test_profile_not_found_warning(service_factory):
    report = final_report(service_factory(llm=None), HYBRID / "content-clean.pdf", semester=5)
    assert report.profile.is_default
    assert {w.code for w in report.pipeline.warnings} == {"PROFILE_NOT_FOUND"}
