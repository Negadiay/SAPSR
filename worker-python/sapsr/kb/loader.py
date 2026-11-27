"""Загрузка и проверка базы знаний из worker-python/kb/.

Ошибка в базе знаний — ошибка запуска: обработчик не стартует с неверным каталогом,
а не падает на первой работе. Проверяется:
  · каждый файл — по pydantic-схеме (sapsr/kb/schema.py);
  · идентификаторы правил уникальны, профили ссылаются на существующие правила;
  · у каждого правила оформления есть проверка в реестре, параметры проходят её схему,
    шаблоны сообщений ссылаются только на известные значения (validate_rules).
"""

from __future__ import annotations

import fnmatch
import hashlib
import re
import string
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

import yaml
from pydantic import ValidationError

from sapsr.kb.schema import (
    Catalog, Concepts, ContentSettings, LayoutParams, Profile, RequirementSet, Rule, Severity,
)

DEFAULT_KB_DIR = Path(__file__).resolve().parents[2] / "kb"


class KnowledgeBaseError(Exception):
    pass


@dataclass(frozen=True)
class Prompt:
    id: str
    version: str
    system: str
    user: str

    @property
    def ref(self) -> str:
        return f"{self.id}.v{self.version}"

    def render(self, **values) -> tuple[str, str]:
        return (string.Template(self.system).safe_substitute(values),
                string.Template(self.user).safe_substitute(values))


@dataclass
class ActiveRule:
    rule: Rule
    severity: Severity
    blocking: bool
    params: dict

    @property
    def id(self) -> str:
        return self.rule.id


@dataclass
class ResolvedProfile:
    id: str
    title: str
    rules: list[ActiveRule]
    content: ContentSettings
    is_default: bool = False
    # Предупреждение для отчёта: (код, текст) — например, применён базовый профиль.
    warning: Optional[tuple[str, str]] = None

    def rules_for(self, stage: str) -> list[ActiveRule]:
        return [r for r in self.rules if r.rule.stage == stage]

    def rule(self, rule_id: str) -> Optional[ActiveRule]:
        return next((r for r in self.rules if r.id == rule_id), None)


@dataclass
class KnowledgeBase:
    root: Path
    catalog: Catalog
    layout: LayoutParams
    concepts: Concepts
    rules: dict[str, Rule]
    profiles: dict[str, Profile]
    requirement_sets: dict[str, RequirementSet]
    prompts: dict[str, Prompt]
    reasoning: dict[str, str]
    content_hash: str = ""
    _profile_cache: dict = field(default_factory=dict, repr=False)

    @property
    def version(self) -> str:
        return f"{self.catalog.version}+{self.content_hash[:8]}"

    def prompt(self, name: str) -> Prompt:
        ref = getattr(self.catalog.prompts, name)
        return self.prompts[ref]

    # ── профили ─────────────────────────────────────────────────────────────

    def _chain(self, profile_id: str) -> list[Profile]:
        chain: list[Profile] = []
        seen: set[str] = set()
        current: Optional[str] = profile_id
        while current:
            if current in seen:
                raise KnowledgeBaseError(f"цикл в наследовании профилей: {profile_id}")
            seen.add(current)
            profile = self.profiles.get(current)
            if profile is None:
                raise KnowledgeBaseError(f"нет профиля {current}")
            chain.append(profile)
            current = profile.extends
        return list(reversed(chain))

    def build_profile(self, profile_id: str) -> ResolvedProfile:
        if profile_id in self._profile_cache:
            return self._profile_cache[profile_id]
        chain = self._chain(profile_id)
        include: object = "all"
        exclude: set[str] = set()
        overrides: dict[str, dict] = {}
        content: dict = {}
        for profile in chain:
            # Раздел rules дочернего профиля целиком заменяет выбор правил родителя.
            if "rules" in profile.model_fields_set:
                include = profile.rules.include
                exclude = _match_ids(self.rules, profile.rules.exclude)
            for rule_id, override in profile.overrides.items():
                merged = overrides.setdefault(rule_id, {"params": {}})
                for key in ("enabled", "severity", "blocking"):
                    value = getattr(override, key)
                    if value is not None:
                        merged[key] = value
                merged["params"].update(override.params)
            content.update({k: v for k, v in profile.content.model_dump().items() if v is not None})
        included = set(self.rules) if include == "all" else _match_ids(self.rules, include)

        active: list[ActiveRule] = []
        for rule_id, rule in self.rules.items():
            ov = overrides.get(rule_id, {"params": {}})
            enabled = rule_id in included and rule_id not in exclude
            if "enabled" in ov:
                enabled = ov["enabled"]
            if not enabled:
                continue
            active.append(ActiveRule(
                rule=rule,
                severity=ov.get("severity", rule.severity),
                blocking=ov.get("blocking", rule.blocking),
                params={**rule.params, **ov["params"]},
            ))
        leaf = chain[-1]
        resolved = ResolvedProfile(id=leaf.id, title=leaf.title, rules=active,
                                   content=ContentSettings(**content))
        self._profile_cache[profile_id] = resolved
        return resolved

    def resolve_profile(self, work_type: Optional[str], semester: Optional[int],
                        stage: Optional[int], explicit: Optional[str] = None) -> ResolvedProfile:
        """Профиль по полям задания. Пустой профиль — базовый с предупреждением в отчёте (З3, шаг 3)."""
        if explicit:
            return self.build_profile(explicit)
        base = self.catalog.base_profile
        if work_type is None and semester is None and stage is None:
            return _with_warning(self.build_profile(base), "DEFAULT_PROFILE",
                                 "Вид работы, семестр и этап не указаны — применены базовые требования "
                                 "к оформлению; требования семестра не проверялись.")
        candidates = []
        for profile in self.profiles.values():
            match = profile.match
            if match is None:
                continue
            if (_fits(match.work_types, work_type) and _fits(match.semesters, semester)
                    and _fits(match.stages, stage)):
                specificity = sum(1 for v in (match.work_types, match.semesters, match.stages) if v != "all")
                candidates.append((specificity, profile.id))
        if candidates:
            candidates.sort(key=lambda c: (-c[0], c[1]))
            return self.build_profile(candidates[0][1])
        return _with_warning(self.build_profile(base), "PROFILE_NOT_FOUND",
                             "Для указанных вида работы, семестра и этапа нет профиля требований — "
                             "применены базовые требования к оформлению.")


def _with_warning(profile: ResolvedProfile, code: str, message: str) -> ResolvedProfile:
    return ResolvedProfile(id=profile.id, title=profile.title, rules=profile.rules,
                           content=profile.content, is_default=True, warning=(code, message))


def _fits(allowed, value) -> bool:
    if allowed == "all":
        return True
    return value is not None and value in allowed


def _match_ids(rules: dict[str, Rule], patterns) -> set[str]:
    result: set[str] = set()
    for pattern in patterns or []:
        matched = fnmatch.filter(rules.keys(), pattern)
        if not matched:
            raise KnowledgeBaseError(f"шаблон {pattern!r} не совпал ни с одним правилом")
        result.update(matched)
    return result


# ── чтение файлов ───────────────────────────────────────────────────────────

def _read_yaml(path: Path):
    try:
        with open(path, encoding="utf-8") as fh:
            return yaml.safe_load(fh)
    except yaml.YAMLError as exc:
        raise KnowledgeBaseError(f"{path.name}: ошибка YAML: {exc}") from exc


def _validate(model, data, where: str):
    try:
        return model.model_validate(data)
    except ValidationError as exc:
        raise KnowledgeBaseError(f"{where}: {exc}") from exc


_RE_PROMPT_SECTION = re.compile(r"^## (system|user)\s*$", re.MULTILINE)


def _read_prompt(path: Path) -> Prompt:
    name = path.stem  # claims.v1
    match = re.fullmatch(r"([a-z_]+)\.v(\d+)", name)
    if not match:
        raise KnowledgeBaseError(f"{path.name}: имя подсказки должно быть вида <id>.v<номер>.md")
    text = path.read_text(encoding="utf-8")
    parts = _RE_PROMPT_SECTION.split(text)
    sections = {parts[i]: parts[i + 1].strip("\n") for i in range(1, len(parts) - 1, 2)}
    if set(sections) != {"system", "user"}:
        raise KnowledgeBaseError(f"{path.name}: нужны разделы «## system» и «## user»")
    return Prompt(id=match.group(1), version=match.group(2),
                  system=sections["system"].strip(), user=sections["user"].strip())


def _content_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file() and "__pycache__" not in p.parts):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
    return digest.hexdigest()


def load_kb(root: Path | str = DEFAULT_KB_DIR,
            validate_rules: Optional[Callable[["KnowledgeBase"], None]] = None) -> KnowledgeBase:
    root = Path(root)
    if not root.is_dir():
        raise KnowledgeBaseError(f"нет каталога базы знаний {root}")
    catalog = _validate(Catalog, _read_yaml(root / "catalog.yaml"), "catalog.yaml")
    layout = _validate(LayoutParams, _read_yaml(root / "layout.yaml"), "layout.yaml")
    concepts = _validate(Concepts, _read_yaml(root / "concepts.yaml"), "concepts.yaml")

    rules: dict[str, Rule] = {}
    for path in sorted((root / "rules").glob("*.yaml")):
        data = _read_yaml(path) or []
        if not isinstance(data, list):
            raise KnowledgeBaseError(f"rules/{path.name}: ожидается список правил")
        for i, item in enumerate(data):
            rule = _validate(Rule, item, f"rules/{path.name}[{i}]")
            if rule.id in rules:
                raise KnowledgeBaseError(f"повторный идентификатор правила {rule.id}")
            rules[rule.id] = rule

    profiles: dict[str, Profile] = {}
    for path in sorted((root / "profiles").glob("*.yaml")):
        profile = _validate(Profile, _read_yaml(path), f"profiles/{path.name}")
        if profile.id != path.stem:
            raise KnowledgeBaseError(f"profiles/{path.name}: id {profile.id!r} не совпадает с именем файла")
        profiles[profile.id] = profile

    requirement_sets: dict[str, RequirementSet] = {}
    for path in sorted((root / "requirements").glob("*.yaml")):
        rset = _validate(RequirementSet, _read_yaml(path), f"requirements/{path.name}")
        requirement_sets[rset.id] = rset

    prompts = {p.stem: _read_prompt(p) for p in sorted((root / "prompts").glob("*.md"))}
    reasoning = {name: (root / "reasoning" / name).read_text(encoding="utf-8") for name in catalog.reasoning}

    kb = KnowledgeBase(root=root, catalog=catalog, layout=layout, concepts=concepts, rules=rules,
                       profiles=profiles, requirement_sets=requirement_sets, prompts=prompts,
                       reasoning=reasoning, content_hash=_content_hash(root))
    _check_references(kb)
    if validate_rules is not None:
        validate_rules(kb)
    return kb


def _check_references(kb: KnowledgeBase) -> None:
    if kb.catalog.base_profile not in kb.profiles:
        raise KnowledgeBaseError(f"нет базового профиля {kb.catalog.base_profile}")
    for name in ("claims", "requirements", "neural"):
        ref = getattr(kb.catalog.prompts, name)
        if ref not in kb.prompts:
            raise KnowledgeBaseError(f"catalog.yaml: нет подсказки {ref}")
    part_ids = {p.id for p in kb.concepts.doc_parts}
    for attr in kb.concepts.attributes:
        for part in attr.must_be_described_in:
            if part not in part_ids:
                raise KnowledgeBaseError(f"concepts.yaml: {attr.id} ссылается на неизвестную часть {part}")
        for value in attr.values:
            for pattern in value.synonyms:
                _compile(pattern, f"concepts.yaml: {attr.id}/{value.id}")
    for part in kb.concepts.doc_parts:
        for pattern in part.heading_patterns + part.structural_titles + part.page_patterns:
            _compile(pattern, f"concepts.yaml: {part.id}")
    for profile in kb.profiles.values():
        for rule_id in profile.overrides:
            if rule_id not in kb.rules:
                raise KnowledgeBaseError(f"profiles/{profile.id}: переопределение неизвестного правила {rule_id}")
        for part in profile.content.sections or []:
            if part not in part_ids:
                raise KnowledgeBaseError(f"profiles/{profile.id}: неизвестная часть записки {part}")
        for set_id in profile.content.requirement_sets or []:
            if set_id not in kb.requirement_sets:
                raise KnowledgeBaseError(f"profiles/{profile.id}: нет набора требований {set_id}")
        kb.build_profile(profile.id)
    for rset in kb.requirement_sets.values():
        for req in rset.requirements:
            for part in req.expected_in:
                if part not in part_ids:
                    raise KnowledgeBaseError(f"requirements/{rset.id}: {req.id} — неизвестная часть {part}")


def _compile(pattern: str, where: str) -> None:
    try:
        re.compile(pattern)
    except re.error as exc:
        raise KnowledgeBaseError(f"{where}: неверное регулярное выражение {pattern!r}: {exc}") from exc


def template_fields(template: str) -> set[str]:
    """Имена полей шаблона «{name}» / «{name:g}»."""
    names = set()
    for _, field_name, _, _ in string.Formatter().parse(template):
        if field_name:
            names.add(field_name.split(".")[0].split("[")[0])
    return names
