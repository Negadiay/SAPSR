"""Путь к файлу работы по ключу хранилища (docs/contracts/queue-v2.md, раздел 3).

Ключ из очереди — тоже входные данные: путь = корень + ключ, и он не должен выйти за корень.
Абсолютный file_path из задания используется только на переходный период, если ключа нет.
"""

from __future__ import annotations

import logging
from pathlib import Path, PurePosixPath, PureWindowsPath

from sapsr.errors import CheckFailure
from sapsr.task import Task

log = logging.getLogger("sapsr.storage")


def resolve_storage_key(root: Path, key: str) -> Path:
    if not key or "\x00" in key:
        raise CheckFailure("FILE_NOT_FOUND", "пустой или недопустимый ключ хранилища")
    if PurePosixPath(key).is_absolute() or PureWindowsPath(key).is_absolute() or PureWindowsPath(key).drive:
        raise CheckFailure("FILE_NOT_FOUND", "абсолютный ключ хранилища")
    base = root.resolve()
    path = (base / key).resolve()
    if path == base or not path.is_relative_to(base):
        raise CheckFailure("FILE_NOT_FOUND", "ключ хранилища выходит за корень")
    return path


def locate_file(task: Task, storage_root: Path) -> Path:
    if task.file.storage_key:
        path = resolve_storage_key(storage_root, task.file.storage_key)
    else:
        log.warning("в задании нет storage_key — используется переходный file_path")
        path = Path(task.file_path)
    if not path.is_file():
        raise CheckFailure("FILE_NOT_FOUND", "файла нет по ключу хранилища")
    return path
