"""Шаги 4–5 цепочки (символьные): продукционный вывод CLIPS над утверждениями
и свидетельствами (план, З3 и З4).

Правила записаны в kb/reasoning/*.clp — это часть базы знаний, а не кода. Здесь только
загрузка фактов, запуск вывода и сборка замечаний по каталогу правил.

Решатель — заменяемый компонент: Reasoner можно заменить другой реализацией с теми же
входом (утверждения) и выходом (срабатывания), не трогая модель и правила оформления.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Optional

import clips

from sapsr.content.claims import Claim
from sapsr.kb.schema import Concepts

# Содержательные правила каталога (поле check) и значения, которые они дают шаблонам сообщений.
_PAIR = ("attribute_title", "section_a", "section_b", "values_a", "values_b", "variants")
_REQ = ("requirement_title", "requirement_text", "sufficient", "expected_parts")
CONTENT_RULES: dict[str, tuple[str, ...]] = {
    "conflict_single": _PAIR,
    "conflict_multi": _PAIR,
    "claim_without_support": ("attribute_title", "section_a", "values_a", "required_parts"),
    "requirement_violated": _REQ,
    "requirement_disputed": _REQ,
    "requirement_not_established": _REQ,
    "neural_freeform": ("model_message", "model_found"),
}


@dataclass
class Firing:
    rule: str
    attribute: str
    claims: tuple[str, ...]


def _sym(value: bool) -> clips.Symbol:
    return clips.Symbol("TRUE" if value else "FALSE")


class Reasoner:
    def __init__(self, sources: dict[str, str]):
        self.sources = sources

    def _environment(self, source_name: str) -> clips.Environment:
        env = clips.Environment()
        _load(env, self.sources[source_name])
        return env

    def run_claims(self, claims: Iterable[Claim], concepts: Concepts) -> list[Firing]:
        env = self._environment("claims.clp")
        attribute = env.find_template("attribute")
        claim_t = env.find_template("claim")
        for attr in concepts.attributes:
            attribute.assert_fact(name=attr.id, cardinality=clips.Symbol(attr.cardinality),
                                  **{"described-in": list(attr.must_be_described_in)})
        dictionary = {a.id: {v.id for v in a.values} for a in concepts.attributes}
        for c in claims:
            known = [v for v in c.values if v in dictionary.get(c.attribute, ())]
            claim_t.assert_fact(id=c.id, attr=c.attribute, vals=list(c.values), known=known,
                                exhaustive=_sym(c.exhaustive), section=c.section_id, kind=c.kind)
        env.run()
        firings = []
        for fact in env.facts():
            if fact.template.name == "firing":
                firings.append(Firing(rule=str(fact["rule"]), attribute=str(fact["attr"]),
                                      claims=tuple(str(c) for c in fact["claims"])))
        firings.sort(key=lambda f: (f.rule, f.attribute, f.claims))
        return firings

    def run(self, source_name: str, facts: dict[str, list[dict]], output: str) -> list[dict]:
        """Общий запуск: факты по шаблонам → факты шаблона output как словари."""
        env = self._environment(source_name)
        for template_name, rows in facts.items():
            template = env.find_template(template_name)
            for row in rows:
                template.assert_fact(**row)
        env.run()
        result = []
        for fact in env.facts():
            if fact.template.name == output:
                result.append({slot.name: _plain(fact[slot.name]) for slot in fact.template.slots})
        return result


def _plain(value):
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, clips.Symbol):
        return str(value)
    return value


def _load(env: clips.Environment, source: str) -> None:
    """Загружает конструкции CLIPS из текста по одной (env.build принимает одну конструкцию)."""
    for construct in split_constructs(source):
        env.build(construct)


def split_constructs(source: str) -> list[str]:
    constructs: list[str] = []
    depth = 0
    start: Optional[int] = None
    in_string = False
    i = 0
    while i < len(source):
        ch = source[i]
        if in_string:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_string = False
        elif ch == ";":
            while i < len(source) and source[i] != "\n":
                i += 1
            continue
        elif ch == '"':
            in_string = True
        elif ch == "(":
            if depth == 0:
                start = i
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0 and start is not None:
                constructs.append(source[start:i + 1])
                start = None
        i += 1
    if depth != 0:
        raise ValueError("несбалансированные скобки в правилах CLIPS")
    return constructs


def rule_names(source: str) -> set[str]:
    names = set()
    for construct in split_constructs(source):
        parts = construct[1:].split(None, 2)
        if len(parts) >= 2 and parts[0] == "defrule":
            names.add(parts[1])
    return names
