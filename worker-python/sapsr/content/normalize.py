"""Шаг 3 содержательной цепочки (символьный): нормализация значений по словарю понятий.

«PDF-файлы», «формат PDF», «pdf» сводятся к одному значению PDF по синонимам из
kb/concepts.yaml. Чего нет в словаре — остаётся как есть (в нижнем регистре) и получает
пониженную уверенность: правила сравнивают такие значения буквально.
"""

from __future__ import annotations

import re
from typing import Optional

from sapsr.document.text import fold
from sapsr.kb.schema import Attribute, Concepts


class Normalizer:
    def __init__(self, concepts: Concepts):
        self.attributes: dict[str, Attribute] = {a.id: a for a in concepts.attributes}
        self._values: dict[str, list[tuple[str, list[re.Pattern]]]] = {
            a.id: [(v.id, [re.compile(s) for s in v.synonyms]) for v in a.values]
            for a in concepts.attributes
        }

    def has_dictionary(self, attr_id: str) -> bool:
        return bool(self._values.get(attr_id))

    def canonical(self, attr_id: str, raw: str) -> Optional[str]:
        """Идентификатор значения из словаря или None."""
        key = fold(raw)
        values = self._values.get(attr_id, [])
        for value_id, _ in values:
            if key == fold(value_id) or key == fold(value_id).replace("_", " "):
                return value_id
        for value_id, patterns in values:
            if any(p.search(key) for p in patterns):
                return value_id
        return None

    def values_in_text(self, attr_id: str, text: str) -> set[str]:
        """Какие значения словаря упомянуты в тексте (для проверки, что значение следует из цитаты)."""
        key = fold(text)
        return {value_id for value_id, patterns in self._values.get(attr_id, [])
                if any(p.search(key) for p in patterns)}

    @staticmethod
    def raw_value(raw: str) -> str:
        return fold(raw).strip(" .,;:")
