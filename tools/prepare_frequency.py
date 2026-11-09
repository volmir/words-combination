#!/usr/bin/env python3
"""Проверка локального FrequencyWords; загрузка только с --allow-network."""
import argparse
import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CACHE = ROOT / ".local/frequency/frequencywords-2018-ru/ru_full.txt"
MANIFEST = ROOT / "data/manifest.json"
REVISION = "f8a65e6ddc17e0baa2e366a909986798d8dbe55b"
REPOSITORY = "https://github.com/hermitdave/FrequencyWords"
DOWNLOAD_URL = ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/" + REVISION +
                "/content/2018/ru/ru_full.txt")
README_URL = REPOSITORY + "/blob/" + REVISION + "/README.md"


def validate(path, expected_sha=None):
    """Проверяем все строки, в том числе не относящиеся к игровым словам."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if expected_sha is not None and actual != expected_sha:
        raise ValueError("SHA-256 частотного списка не совпадает: " + str(path))
    rows = 0
    with path.open(encoding="utf-8-sig") as source:
        for number, line in enumerate(source, 1):
            if not line.strip():
                continue
            fields = line.split()
            if len(fields) != 2 or re.fullmatch(r"[0-9]+", fields[1]) is None:
                raise ValueError("--frequency-list %s:%d: ожидаются словоформа и неотрицательное целое" %
                                 (path, number))
            rows += 1
    if not rows:
        raise ValueError("--frequency-list пуст: " + str(path))
    return actual


def expected_digest(manifest):
    source = json.loads(Path(manifest).read_text(encoding="utf-8"))["selection"]["frequency_source"]
    if source.get("snapshot") != REVISION or not re.fullmatch(r"[0-9a-f]{64}", source.get("sha256", "")):
        raise ValueError("Манифест не задаёт закреплённый снимок FrequencyWords")
    return source["sha256"]


def resolve_frequency_list(explicit=None, manifest=MANIFEST, *, required=True):
    """Явный локальный список имеет приоритет; стандартный кэш всегда сверяем с манифестом."""
    if explicit is not None:
        path = Path(explicit)
        if not path.is_file():
            raise ValueError("Указанный --frequency-list отсутствует; скачивания нет: " + str(path))
        validate(path)
        return path.resolve()
    path = DEFAULT_CACHE
    if not required and not path.exists():
        return None  # Совместимость экспериментальных режимов без частотного входа.
    try:
        validate(path, expected_digest(manifest))
    except (OSError, ValueError) as error:
        raise ValueError("Нет исправного частотного кэша. Выполните python3 tools/prepare_frequency.py "
                         "--allow-network или укажите --frequency-list; автоматических загрузок нет. " + str(error)) from error
    return path.resolve()


def write_metadata(path, digest):
    metadata = {
        "dataset": "FrequencyWords Russian full frequency list, OpenSubtitles 2018",
        "repository": REPOSITORY, "commit": REVISION,
        "content_license_declared_by_upstream": "CC-BY-SA-3.0",
        "license_source": README_URL,
        "files": [{"file": path.name, "sha256": digest, "url": DOWNLOAD_URL}],
    }
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".source-", delete=False) as target:
            temporary = Path(target.name)
            json.dump(metadata, target, ensure_ascii=False, indent=2)
            target.write("\n")
        os.replace(temporary, path.parent / "source.json")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def prepare(frequency_list=None, *, allow_network=False, manifest=MANIFEST):
    digest = expected_digest(manifest)
    if frequency_list is not None:
        path = Path(frequency_list).resolve()
        validate(path, digest)
        write_metadata(path, digest)
        return path
    path = DEFAULT_CACHE
    try:
        validate(path, digest)
    except (OSError, ValueError):
        if not allow_network:
            return resolve_frequency_list(manifest=manifest)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".frequency-", delete=False) as target:
                temporary = Path(target.name)
                with urlopen(DOWNLOAD_URL, timeout=60) as response:
                    shutil.copyfileobj(response, target)
            validate(temporary, digest)
            os.replace(temporary, path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    write_metadata(path, digest)
    return path.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--frequency-list", type=Path, help="Проверить уже имеющийся полный список")
    args = parser.parse_args()
    try:
        print(prepare(args.frequency_list, allow_network=args.allow_network))
    except (OSError, ValueError, KeyError, HTTPException) as error:
        parser.exit(2, "Ошибка: %s\n" % error)


if __name__ == "__main__":
    main()
