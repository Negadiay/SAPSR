"""Сценарная заглушка языковой модели для тестов (режим stub клиента LlmClient).

Отвечает так, как ответила бы модель на записки из test-fixtures/hybrid: находит в блоках
данных известные фразы и возвращает утверждения с идентификаторами этих блоков. Плюс
нарочно «выдуманная» цитата, чтобы проверить символьную защиту от галлюцинаций.
Все запросы сохраняются в `calls` — по ним проверяется, что скрытый текст до модели не дошёл.
"""

from __future__ import annotations

import json
import re

_RE_BLOCK = re.compile(r"^\[(b-\d{4,})\] (.*)$", re.MULTILINE)

# (фраза в блоке, атрибут, значения, полный перечень, цитата)
CLAIMS = [
    ("в форматах DOCX и PDF", "system.input_formats", ["DOCX", "PDF"], True,
     "Система принимает файлы в форматах DOCX и PDF"),
    ("в форматах DOCX, ODT и PDF", "system.input_formats", ["DOCX", "ODT", "PDF"], True,
     "Система принимает файлы в форматах DOCX, ODT и PDF"),
    ("только файл пояснительной записки в формате PDF", "system.input_formats", ["PDF"], True,
     "Входные данные системы — только файл пояснительной записки в формате PDF"),
    ("Система принимает файлы в формате PDF", "system.input_formats", ["PDF"], True,
     "Система принимает файлы в формате PDF"),
    ("— файл пояснительной записки в формате PDF", "system.input_formats", ["PDF"], True,
     "Входные данные системы — файл пояснительной записки в формате PDF"),
    ("представлена онтологией", "system.kb_representation", ["ontology"], True,
     "База знаний системы представлена онтологией предметной области нормоконтроля"),
    ("хранятся в конфигурационном файле YAML", "system.kb_representation", ["config_file"], True,
     "Правила и параметры проверки хранятся в конфигурационном файле YAML"),
    ("представлена продукционными правилами", "system.kb_representation", ["production_rules"], True,
     "База знаний представлена продукционными правилами"),
    ("используется нейросетевая языковая модель", "system.neural_component", ["llm"], False,
     "в системе используется нейросетевая языковая модель"),
    ("Нейросетевая модель разметки", "system.neural_component", ["layout_model"], False,
     "Нейросетевая модель разметки получает строки документа с координатами"),
    ("Языковая модель запускается локально", "system.neural_component", ["llm"], False,
     "Языковая модель запускается локально и возвращает утверждения в формате JSON"),
    ("реализован на языке Python", "system.languages", ["python", "java"], True,
     "Обработчик реализован на языке Python, сервер — на языке Java"),
]

HALLUCINATION = {"attribute": "system.interface_type", "value": ["web"], "exhaustive": False,
                 "quote": "Преподаватель работает с системой через веб-интерфейс в браузере"}

# Требования семестра: (фраза в блоке, требование, оценка, уверенность)
JUDGEMENTS = [
    ("Нейросетевая модель разметки получает", "Нейросетевой компонент", "supports", 0.9),
    ("продукционными правилами, которые выполняет механизм вывода", "Символьный компонент", "supports", 0.85),
    ("Обработчик получает задание из очереди", "Точки интеграции", "supports", 0.6),
]


class ScriptedModel:
    def __init__(self, hallucinate: bool = True):
        self.calls: list[dict] = []
        self.hallucinate = hallucinate

    def __call__(self, prompt_ref: str, system: str, user: str, schema) -> str:
        self.calls.append({"prompt": prompt_ref, "system": system, "user": user})
        blocks = _RE_BLOCK.findall(user)
        if prompt_ref.startswith("claims"):
            return json.dumps({"claims": self._claims(blocks)}, ensure_ascii=False)
        if prompt_ref.startswith("requirements"):
            title = re.search(r"^Требование: (.*)$", user, re.MULTILINE).group(1)
            return json.dumps({"judgements": self._judge(title, blocks)}, ensure_ascii=False)
        if prompt_ref.startswith("neural"):
            return json.dumps({"findings": self._neural(blocks)}, ensure_ascii=False)
        raise AssertionError(f"неожиданная подсказка {prompt_ref}")

    def _claims(self, blocks) -> list[dict]:
        claims = []
        for block_id, text in blocks:
            for phrase, attr, values, exhaustive, quote in CLAIMS:
                if phrase in text:
                    claims.append({"attribute": attr, "value": values, "exhaustive": exhaustive,
                                   "quote": quote, "block_id": block_id})
            if self.hallucinate and "Целью курсового проекта" in text:
                claims.append({**HALLUCINATION, "block_id": block_id})
        return claims

    @staticmethod
    def _judge(title: str, blocks) -> list[dict]:
        result = []
        for block_id, text in blocks:
            verdict, confidence, quote = "irrelevant", 0.5, ""
            for phrase, req_title, v, conf in JUDGEMENTS:
                if phrase in text and title.startswith(req_title):
                    verdict, confidence = v, conf
                    start = text.index(phrase)
                    quote = text[start:start + 120]
            result.append({"block_id": block_id, "verdict": verdict, "quote": quote, "confidence": confidence})
        return result

    @staticmethod
    def _neural(blocks) -> list[dict]:
        found = []
        texts = dict(blocks)
        a = next((b for b, t in blocks if "в форматах DOCX и PDF" in t), None)
        b = next((b for b, t in blocks if "только файл пояснительной записки" in t), None)
        if a and b:
            found.append({"message": "Форматы входных файлов описаны по-разному.",
                          "found": "Во введении DOCX и PDF, в требованиях только PDF.",
                          "quote": "Система принимает файлы в форматах DOCX и PDF", "block_id": a,
                          "quote_2": "только файл пояснительной записки в формате PDF", "block_id_2": b})
        if texts:
            found.append({"message": "Не описан порядок развёртывания системы.",
                          "found": "В записке нет раздела о развёртывании.",
                          "quote": "Система разворачивается в облаке Kubernetes", "block_id": next(iter(texts))})
        return found
