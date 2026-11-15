#!/usr/bin/env python3
"""Статическая проверка публикационной приватности без сети и без Git-истории.

Проверяются только новые пользовательские артефакты: строки интерфейса,
AboutScreen, лицензионные тексты, generated assets и публикационные документы.
Исторические Git-коммиты, remote, форки и зеркала этим скриптом не проверяются и
не очищаются. Персональные маркеры хранятся только как SHA-256 нормализованных
токенов, чтобы проверка сама не публиковала их значения.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

TOKEN_RE = re.compile(r"[^\W_]+(?:[._@+-][^\W_]+)*", re.UNICODE)
EMAIL_RE = re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", re.UNICODE)
URL_RE = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)


def normalize(text: str) -> str:
    return unicodedata.normalize("NFKC", text).lower().replace("ё", "е")


def token_hashes(text: str) -> set[str]:
    result = set()
    for token in TOKEN_RE.findall(normalize(text)):
        result.add(hashlib.sha256(token.encode("utf-8")).hexdigest())
    return result


def find_forbidden_tokens(text: str, forbidden: set[str]) -> set[str]:
    return token_hashes(text) & forbidden


def find_emails(text: str) -> list[str]:
    return EMAIL_RE.findall(text)


def find_disallowed_urls(text: str, allowed_hosts: set[str]) -> list[str]:
    result = []
    for raw in URL_RE.findall(text):
        url = raw.rstrip(".,;:)!?»")
        host = (urlsplit(url).hostname or "").lower()
        if host not in allowed_hosts:
            result.append(url)
    return result


def load_policy(root: Path) -> dict:
    path = root / "tools/privacy_publication_policy.json"
    policy = json.loads(path.read_text(encoding="utf-8"))
    if policy.get("schema") != "privacy-publication-policy-v1":
        raise ValueError("Неизвестная схема политики приватности")
    hashes = policy.get("forbidden_token_sha256")
    if not isinstance(hashes, list) or not hashes or any(not isinstance(h, str) for h in hashes):
        raise ValueError("Пустой или неверный список персональных маркеров")
    return policy


def parse_strings(path: Path) -> dict[str, str]:
    tree = ET.parse(path)
    values: dict[str, str] = {}
    for node in tree.getroot().iter():
        if node.tag in {"string", "item"} and node.get("name"):
            values[node.get("name")] = "".join(node.itertext())
        elif node.tag == "plurals" and node.get("name"):
            values["plurals:" + node.get("name")] = " ".join(
                "".join(item.itertext()) for item in node.findall("item")
            )
    return values


def source_files(root: Path, require_generated: bool) -> list[Path]:
    files = [
        root / "app/src/main/AndroidManifest.xml",
        root / "app/src/main/kotlin/com/example/sostavslova/ui/screens/Screens.kt",
        root / "app/build.gradle.kts",
        root / "build.gradle.kts",
        root / "settings.gradle.kts",
        root / "gradle.properties",
        root / "README.md",
        root / "LICENSE",
        root / "metadata.json",
        root / "data/manifest.json",
        root / "tools/build_dictionary.py",
    ]
    files.extend(sorted((root / "app/src/main/kotlin").rglob("*.kt")))
    files.extend(sorted((root / "core/src/main/kotlin").rglob("*.kt")))
    files.extend(sorted((root / "app/src/main/res").glob("values*/strings.xml")))
    files.extend(sorted((root / "tools").glob("*.py")))
    files.extend(sorted((root / "legal").glob("*.txt")))
    files.extend(sorted((root / "docs").rglob("*.md")))
    generated = [
        root / "app/src/main/assets/THIRD_PARTY_NOTICES.txt",
        root / "app/src/main/assets/LICENSE",
        root / "app/src/main/assets/LIBRARY_NOTICES.txt",
        root / "app/src/main/assets/RUNTIME_LIBRARIES.json",
        root / "app/src/main/assets/Apache-2.0.txt",
    ]
    files.extend(generated)
    if require_generated:
        missing = [str(p.relative_to(root)) for p in generated if not p.is_file()]
        if missing:
            raise FileNotFoundError("Нет generated assets: " + ", ".join(missing))
    return sorted({p for p in files if p.is_file()})


def check(root: Path, require_generated: bool = False) -> list[str]:
    root = root.resolve()
    policy = load_policy(root)
    forbidden = set(policy["forbidden_token_sha256"])
    allowed_hosts = {host.lower() for host in policy["allowed_user_facing_url_hosts"]}
    errors: list[str] = []

    files = source_files(root, require_generated)
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            errors.append(f"{path.relative_to(root)}: не UTF-8 текст")
            continue
        rel = path.relative_to(root)
        if find_forbidden_tokens(text, forbidden):
            errors.append(f"{rel}: найден персональный маркер")
        emails = find_emails(text)
        if emails:
            errors.append(f"{rel}: найден e-mail")
        if "strings.xml" in path.name:
            for url in find_disallowed_urls(text, allowed_hosts):
                errors.append(f"{rel}: URL вне разрешённых доменов: {url}")

    strings_path = root / "app/src/main/res/values/strings.xml"
    values = parse_strings(strings_path)
    expected = policy["expected_user_strings"]
    for key, expected_value in expected.items():
        if values.get(key) != expected_value:
            errors.append(f"strings.xml: {key} не совпадает с одобренным обезличенным текстом")

    screen = (root / "app/src/main/kotlin/com/example/sostavslova/ui/screens/Screens.kt").read_text(encoding="utf-8")
    for key in ("about_code_heading", "about_code_sources"):
        if "R.string." + key not in screen:
            errors.append(f"Screens.kt: AboutScreen не использует R.string.{key}")
    if "github.com" in screen.lower():
        errors.append("Screens.kt: в AboutScreen осталась ссылка на GitHub")

    license_text = (root / "LICENSE").read_text(encoding="utf-8")
    for marker in ("MIT License", "Правообладатель проекта «Составь слова»",
                   "Permission is hereby granted", "THE SOFTWARE IS PROVIDED"):
        if marker not in license_text:
            errors.append(f"LICENSE: отсутствует обязательный фрагмент: {marker}")

    notices = (root / "legal/THIRD_PARTY_NOTICES.txt").read_text(encoding="utf-8")
    for marker in policy["required_third_party_markers"]:
        if marker not in notices:
            errors.append(f"legal/THIRD_PARTY_NOTICES.txt: потеряно уведомление: {marker}")

    manifest = json.loads((root / "data/manifest.json").read_text(encoding="utf-8"))
    levels_source = manifest.get("levels_source") or {}
    if levels_source.get("url") is not None:
        errors.append("data/manifest.json: levels_source.url должен быть null или отсутствовать")
    if levels_source.get("license") != "MIT":
        errors.append("data/manifest.json: потеряна MIT-лицензия исторических уровней")
    source = manifest.get("source") or {}
    if source.get("license") != "CC-BY-SA-4.0":
        errors.append("data/manifest.json: потеряна лицензия словаря CC BY-SA 4.0")

    for name, source in (
        ("THIRD_PARTY_NOTICES.txt", root / "legal/THIRD_PARTY_NOTICES.txt"),
        ("LIBRARY_NOTICES.txt", root / "legal/LIBRARY_NOTICES.txt"),
        ("Apache-2.0.txt", root / "legal/Apache-2.0.txt"),
        ("RUNTIME_LIBRARIES.json", root / "legal/RUNTIME_LIBRARIES.json"),
    ):
        asset = root / "app/src/main/assets" / name
        if asset.is_file() and source.is_file():
            if asset.read_text(encoding="utf-8") != source.read_text(encoding="utf-8"):
                errors.append(f"assets/{name} не совпадает с legal/{name}")
    generated_license = root / "app/src/main/assets/LICENSE"
    if generated_license.is_file() and generated_license.read_text(encoding="utf-8") != license_text:
        errors.append("assets/LICENSE не совпадает с корневым LICENSE")

    # Если в будущем появится fastlane/F-Droid metadata, персональные поля не должны
    # попадать в публикуемую карточку. Сейчас каталогов нет; проверка безопасна.
    for pattern in ("fastlane/**/*.txt", "fastlane/**/*.json", "metadata/**/*.yml", "metadata/**/*.yaml"):
        for path in root.glob(pattern):
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if find_forbidden_tokens(text, forbidden) or find_emails(text):
                errors.append(f"{path.relative_to(root)}: персональные данные в публикационной metadata")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--require-generated", action="store_true",
                        help="Требовать свежие generated assets и их совпадение с legal/")
    args = parser.parse_args()
    try:
        errors = check(args.root, args.require_generated)
    except Exception as exc:  # noqa: BLE001 - CLI должен вернуть понятную ошибку
        print(f"PRIVACY_PUBLICATION_CHECK=ERROR: {exc}", file=sys.stderr)
        return 2
    if errors:
        for error in errors:
            print("ERROR: " + error, file=sys.stderr)
        print("PRIVACY_PUBLICATION_CHECK=FAIL", file=sys.stderr)
        return 1
    print("PRIVACY_PUBLICATION_CHECK=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
