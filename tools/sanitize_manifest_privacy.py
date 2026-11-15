#!/usr/bin/env python3
"""Обезличивает metadata установленного manifest без изменения данных и хэшей.

Правит только `levels_source.url` и добавляет нейтральное `provenance`; словарь,
уровни, кампания и definitions не читаются и не меняются. Скрипт нужен для
воспроизводимой миграции уже собранного `data/manifest.json` после PRIV-01.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

PROVENANCE = "historical v1 snapshot of this project"


def sanitize(manifest: dict) -> dict:
    levels = manifest.get("levels_source")
    if not isinstance(levels, dict):
        raise ValueError("manifest.levels_source отсутствует или имеет неверный тип")
    levels["url"] = None
    levels.setdefault("provenance", PROVENANCE)
    return manifest


def is_sanitized(manifest: dict) -> bool:
    levels = manifest.get("levels_source") or {}
    return levels.get("url") is None and levels.get("provenance") == PROVENANCE


def write_manifest(path: Path, manifest: dict) -> None:
    text = json.dumps(manifest, ensure_ascii=False, separators=(", ", ": ")) + "\n"
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=path.parent, prefix=path.name + ".", delete=False
    ) as stream:
        stream.write(text)
        temporary = stream.name
    os.replace(temporary, path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("data/manifest.json"))
    parser.add_argument("--check", action="store_true",
                        help="Только проверить, что metadata уже обезличена")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if args.check:
        if not is_sanitized(manifest):
            print("MANIFEST_PRIVACY=FAIL", file=sys.stderr)
            return 1
        print("MANIFEST_PRIVACY=PASS")
        return 0
    write_manifest(args.manifest, sanitize(manifest))
    print("MANIFEST_PRIVACY=MIGRATED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
