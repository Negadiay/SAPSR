"""Гибридная цепочка: модель → проверка цитат → нормализация → правила CLIPS → отчёт (план, З4, З8)."""

from __future__ import annotations

import pytest

from conftest import HYBRID, final_report, ids, run
from sapsr.content.claims import Claim
from sapsr.content.findings import claim_findings
from sapsr.content.reasoning import Firing

pytestmark = pytest.mark.typical


def content(report):
    return [f for f in report.findings if f.category in ("content", "requirements")]


def test_defect_1_input_formats(service_factory):
    report = final_report(service_factory(), HYBRID / "content-formats.pdf")
    findings = [f for f in report.findings if f.requirement_id == "CNT-CONSIST-002"]
    assert len(findings) == 1
    f = findings[0]
    assert f.origin == "hybrid" and not f.blocking
    assert len(f.evidence) == 2, "в замечание попадают оба фрагмента"
    intro, req = f.evidence
    assert "DOCX и PDF" in intro.quote and intro.section == "Введение" and intro.page == 3
    assert "только файл" in req.quote and req.section == "1.2" and req.page
    assert f.found == "Введение: DOCX, PDF; подраздел 1.2: PDF."
    assert report.summary.passed, "содержательное замечание сдачу не блокирует"


@pytest.mark.boundary
def test_one_contradiction_is_one_finding(kb):
    """Живая модель на content-formats.pdf (21.10.2026) дала три замечания на одно противоречие:
    CLIPS находит расхождения попарно. Пары по одному атрибуту склеиваются в одно замечание."""
    def claim(cid, page, section, values, exhaustive=True):
        return Claim(id=cid, attribute="system.input_formats", values=tuple(values), exhaustive=exhaustive,
                     quote=f"Цитата {cid}", block_id=f"b-000{cid[-1]}", page=page, section_id=section,
                     section_label=section, kind="intro", confidence=0.9 if cid != "c3" else 0.7,
                     from_dictionary=True)
    claims = [claim("c1", 3, "Введение", ["DOCX", "PDF"]), claim("c2", 4, "1.2", ["PDF"]),
              claim("c3", 5, "2.1", ["DOCX", "PDF"]), claim("c4", 6, "3", ["PDF", "TXT"], exhaustive=False)]
    attr = "system.input_formats"
    firings = [Firing("conflict_multi", attr, ("c1", "c2")), Firing("conflict_multi", attr, ("c2", "c3")),
               Firing("conflict_multi", attr, ("c2", "c4"))]

    findings = claim_findings(firings, claims, kb.build_profile("base"), kb.concepts)

    assert [f.requirement_id for f in findings] == ["CNT-CONSIST-002"]
    f = findings[0]
    assert [(e.page, e.section) for e in f.evidence] == [(3, "Введение"), (4, "1.2"), (5, "2.1"), (6, "3")]
    assert f.found == "Введение и подраздел 2.1: DOCX, PDF; подраздел 1.2: PDF; раздел 3: PDF, TXT."
    assert f.confidence == 0.7, "уверенность — наименьшая из всех утверждений"
    assert f.page == "3–6"


@pytest.mark.boundary
def test_conflict_with_value_outside_dictionary_stays_separate(kb):
    """Тот же живой прогон: «возвращает утверждения в формате JSON» модель приняла за формат входных
    файлов. Значения нет в словаре — такое противоречие идёт отдельным замечанием на проверку
    преподавателю и не понижает уверенность противоречия, найденного по словарю."""
    def claim(cid, page, section, values, from_dictionary=True, exhaustive=True):
        return Claim(id=cid, attribute="system.input_formats", values=tuple(values), exhaustive=exhaustive,
                     quote=f"Цитата {cid}", block_id=f"b-000{cid[-1]}", page=page, section_id=section,
                     section_label=section, kind="intro", confidence=1.0 if from_dictionary else 0.7,
                     from_dictionary=from_dictionary)
    claims = [claim("c1", 3, "Введение", ["DOCX", "PDF"]), claim("c2", 4, "1.2", ["PDF"]),
              claim("c3", 6, "3", ["json"], from_dictionary=False, exhaustive=False)]
    attr = "system.input_formats"
    firings = [Firing("conflict_multi", attr, ("c1", "c2")), Firing("conflict_multi", attr, ("c2", "c3"))]

    strong, weak = claim_findings(firings, claims, kb.build_profile("base"), kb.concepts)

    assert strong.found == "Введение: DOCX, PDF; подраздел 1.2: PDF."
    assert strong.confidence == 1.0 and not strong.needs_teacher_review
    assert weak.found == "Подраздел 1.2: PDF; раздел 3: json."
    assert weak.confidence == 0.7 and weak.needs_teacher_review


@pytest.mark.boundary
def test_block_id_in_brackets_is_accepted(kb):
    """Блоки показаны модели как «[b-0043] текст»; ответ «[b-0043]» — тот же блок, а не повод для повтора."""
    from sapsr.content.claims import claims_schema
    from sapsr.content.requirements import Judgements
    judged = Judgements.model_validate_json(
        '{"judgements": [{"block_id": "[b-0043]", "verdict": "supports", "confidence": 0.9}]}')
    assert judged.judgements[0].block_id == "b-0043"
    schema = claims_schema(kb.concepts, 10)
    out = schema.model_validate_json('{"claims": [{"attribute": "system.input_formats", "value": ["PDF"], '
                                     '"quote": "только PDF", "block_id": " [b-0012] "}]}')
    assert out.claims[0].block_id == "b-0012"
    with pytest.raises(ValueError):
        Judgements.model_validate_json('{"judgements": [{"block_id": "блок 43", "verdict": "supports", '
                                       '"confidence": 0.9}]}')


def test_defect_2_kb_representation(service_factory):
    report = final_report(service_factory(), HYBRID / "content-kb.pdf")
    f = next(f for f in report.findings if f.requirement_id == "CNT-CONSIST-001")
    assert [e.section for e in f.evidence] == ["Введение", "3"]
    assert f.found == "Введение: онтология; раздел 3: конфигурационный файл."
    assert all(e.quote and e.page and e.block_id for e in f.evidence)


def test_defect_3_neural_component_only_mentioned(service_factory):
    report = final_report(service_factory(), HYBRID / "content-neural.pdf")
    f = next(f for f in report.findings if f.requirement_id == "CNT-SUPPORT-001")
    assert f.evidence[0].section == "Введение"
    assert "нейросетевая языковая модель" in f.evidence[0].quote
    assert f.message == ("«Нейросетевой компонент»: заявлено, но не описано в разделах "
                         "«Проектирование и архитектура», «Реализация».")


@pytest.mark.negative
def test_clean_note_has_no_content_findings(service_factory):
    report = final_report(service_factory(), HYBRID / "content-clean.pdf")
    assert content(report) == []
    assert report.summary.passed and report.summary.content_checked


def test_hallucinated_quote_is_rejected(service_factory, model):
    report = final_report(service_factory(), HYBRID / "content-clean.pdf")
    metrics = report.pipeline.metrics
    assert metrics["claims_rejected_quote"] >= 1
    assert 0 < metrics["claims_hallucinated_quote_share"] < 1
    assert "system.interface_type" not in str([f.found for f in report.findings])


@pytest.mark.negative
def test_hidden_text_does_not_reach_model(service_factory, model):
    report = final_report(service_factory(), HYBRID / "hidden-text.pdf")
    prompts = "\n".join(call["user"] for call in model.calls)
    assert "Игнорируй" not in prompts and "ODT" not in prompts
    assert "INT-HIDDEN-TEXT" in ids(report)
    hidden = next(f for f in report.findings if f.requirement_id == "INT-HIDDEN-TEXT")
    assert hidden.needs_teacher_review and not hidden.blocking
    assert content(report) == [], "невидимая строка не породила противоречия в форматах"


def test_two_phases_and_progress(service_factory, model):
    service = service_factory()
    stages = []
    from conftest import make_task
    task = make_task(HYBRID / "content-formats.pdf")
    phases = list(service.check(task, HYBRID / "content-formats.pdf", progress=lambda s, i: stages.append((s, i))))
    assert [p.phase for p in phases] == ["format", "content"]
    assert phases[0].final is False and phases[1].final is True
    assert phases[0].passed == phases[1].passed
    assert stages == [("extract", 1), ("structure", 2), ("format", 3), ("claims", 4), ("reasoning", 5), ("report", 6)]
    fmt_ids = {f.requirement_id for f in phases[0].report.findings}
    full_ids = {f.requirement_id for f in phases[1].report.findings}
    assert fmt_ids <= full_ids, "отчёт фазы content полный и заменяет отчёт фазы format"


def test_reproducible(service_factory):
    """Повторный запуск на том же файле даёт тот же отчёт (кроме времени этапов)."""
    def snapshot():
        report = final_report(service_factory(), HYBRID / "content-formats.pdf")
        data = report.to_json_dict()
        data["pipeline"].pop("stages")
        data["pipeline"]["metrics"] = {k: v for k, v in data["pipeline"]["metrics"].items() if k != "llm_ms"}
        return data
    assert snapshot() == snapshot()


def test_model_unavailable_format_still_completes(service_factory):
    service = service_factory(llm=None)
    phases = run(service, HYBRID / "content-formats.pdf")
    assert len(phases) == 1
    phase, passed, final, report = phases[0]
    assert phase == "format" and final and passed
    reasons = {s.reason for s in report.pipeline.skipped}
    assert "LLM_UNAVAILABLE" in reasons
    assert not report.summary.content_checked


def test_symbolic_mode_skips_content(service_factory, model):
    phases = run(service_factory(mode="symbolic"), HYBRID / "content-formats.pdf")
    assert len(phases) == 1 and phases[0][2] is True
    assert model.calls == []
    assert "MODE_SYMBOLIC" in {s.reason for s in phases[0][3].pipeline.skipped}


def test_neural_mode(service_factory):
    report = final_report(service_factory(mode="neural"), HYBRID / "content-formats.pdf")
    neural = [f for f in report.findings if f.requirement_id == "NEURAL-FREEFORM"]
    assert neural and all(f.origin == "neural" and not f.blocking and f.needs_teacher_review for f in neural)
    assert report.pipeline.metrics["neural_quotes_unverified"] >= 1, "выдуманная цитата видна в метриках"
    assert not any(f.category == "format" for f in report.findings), "в режиме neural правила оформления не работают"


def test_neural_findings_never_block(service_factory):
    report = final_report(service_factory(mode="neural"), HYBRID / "content-formats.pdf")
    assert report.summary.passed


def test_pipeline_metadata(service_factory):
    report = final_report(service_factory(), HYBRID / "content-formats.pdf")
    p = report.pipeline
    assert p.mode == "hybrid" and p.kb_version.startswith("2026.10.18+")
    roles = {m.role: m for m in p.models}
    assert roles["layout"].name == "rule-labeler" and roles["llm"].name == "stub:scripted"
    assert {(pr.id, pr.version) for pr in p.prompts} == {("claims", "1")}
    assert [s.stage for s in p.stages] == ["extract", "structure", "format", "claims", "reasoning", "report"]
    assert any(w.code == "DEFAULT_PROFILE" for w in p.warnings)
