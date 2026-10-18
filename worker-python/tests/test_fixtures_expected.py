"""Сверка test-fixtures/hybrid/expected.json с тем, что выдаёт обработчик."""

from __future__ import annotations

import json

import pytest

from conftest import HYBRID, final_report
from sapsr.errors import CheckFailure

EXPECTED = {k: v for k, v in json.loads((HYBRID / "expected.json").read_text(encoding="utf-8")).items()
            if not k.startswith("_")}


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_expected(service_factory, name):
    expected = EXPECTED[name]
    if "failure" in expected:
        with pytest.raises(CheckFailure) as exc:
            final_report(service_factory(), HYBRID / name)
        assert exc.value.code == expected["failure"]
        return
    report = final_report(service_factory(), HYBRID / name)
    assert report.summary.passed is expected["passed"]
    assert sorted(f.requirement_id for f in report.findings) == sorted(expected["findings"])
