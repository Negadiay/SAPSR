"""Проверки анализатора на эталонном PDF и на заведомо плохих файлах."""

import pytest

from analyzer import analyze_pdf

REFERENCE_PDF = "valid-format-sample.pdf"

# Подстроки сообщений о базовых правилах оформления — на эталоне их быть не должно
BASIC_FORMAT_MESSAGES = ("поле", "шрифт", "интервал", "отступ", "номер страниц")


@pytest.mark.typical
def test_reference_pdf_has_no_basic_format_errors(fixtures_dir):
    """PY-TYP-01. Эталон: нет критических замечаний и замечаний по полям, шрифту, интервалам."""
    result = analyze_pdf(str(fixtures_dir / REFERENCE_PDF))

    assert result["status"] in ("SUCCESS", "FAIL")
    assert isinstance(result["errors"], list)
    assert not [e for e in result["errors"] if e["severity"] == "critical"]
    basic = [e for e in result["errors"] if any(m in e["message"].lower() for m in BASIC_FORMAT_MESSAGES)]
    assert basic == []


@pytest.mark.typical
def test_reference_pdf_passes(fixtures_dir):
    """PY-TYP-02. Эталон проходит проверку целиком."""
    result = analyze_pdf(str(fixtures_dir / REFERENCE_PDF))
    assert result["status"] == "SUCCESS", result["errors"]


@pytest.mark.negative
def test_missing_file_gives_fail_not_exception(tmp_path):
    """PY-NEG-01. Нет файла — результат FAIL, а не исключение."""
    result = analyze_pdf(str(tmp_path / "нет-такого.pdf"))
    assert result["status"] == "FAIL"
    assert result["errors"]


@pytest.mark.negative
def test_not_a_pdf_gives_fail_not_exception(tmp_path):
    """PY-NEG-02. Не PDF с расширением .pdf — результат FAIL, а не исключение."""
    fake = tmp_path / "fake.pdf"
    fake.write_bytes(b"PK\x03\x04 this is a zip, not a pdf")
    result = analyze_pdf(str(fake))
    assert result["status"] == "FAIL"
    assert result["errors"]
