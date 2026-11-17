#!/usr/bin/env python3
"""Установить проверенный пакет SHORT15-02 в data/ с откатом.

Инструмент не генерирует кампанию и не скачивает данные: он принимает только
staged-пакет, сверяет SHA256SUMS, отчёт GEN-04 (`installable=true`) и метрики
качества, сохраняет копию текущего data/ для отката и записывает campaign_source
в manifest. Сеть не используется.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import build_dictionary as dictionary  # noqa: E402
import build_game_data as game  # noqa: E402
from game_data_providers import atomic_json  # noqa: E402

PROFILE = "short15-release-v1"
REQUIRED_LEVELS = 15
REQUIRED_STARS = 49
ARTIFACTS = (
    "words.txt",
    "word_sources.jsonl.gz",
    "levels.json",
    "golden_search.json",
    "owned_riddles.json",
    "campaign.json",
    "manifest.json",
)
EVIDENCE = ("validation_report.json", "quality_report.json", "SHA256SUMS")
STAGE_NAMES = (
    (1, 1, 3),
    (2, 4, 5),
    (3, 6, 8),
    (4, 9, 10),
    (5, 11, 13),
    (6, 14, 15),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_checksums(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2 or len(parts[0]) != 64 or any(c not in "0123456789abcdef" for c in parts[0]):
            raise ValueError(f"Некорректная строка SHA256SUMS {number}")
        digest, name = parts
        if Path(name).name != name or name in values:
            raise ValueError(f"Повтор или недопустимое имя в SHA256SUMS: {name}")
        values[name] = digest
    return values


def verify_campaign(campaign: dict) -> None:
    levels = campaign.get("levels")
    stages = campaign.get("stages")
    if campaign.get("version") != 2 or campaign.get("countMinLength") != 4:
        raise ValueError("campaign.json не является короткой кампанией v2")
    if not isinstance(levels, list) or len(levels) != REQUIRED_LEVELS:
        raise ValueError(f"Ожидалось {REQUIRED_LEVELS} уровней")
    if [level.get("number") for level in levels] != list(range(1, REQUIRED_LEVELS + 1)):
        raise ValueError("Уровни должны идти подряд с 1")
    actual_stages = [(s.get("number"), s.get("firstLevel"), s.get("lastLevel")) for s in stages or []]
    if actual_stages != list(STAGE_NAMES):
        raise ValueError("Ожидались шесть этапов [3,2,3,2,3,2]")
    stars = 0
    types = set()
    for level in levels:
        number = level["number"]
        expected_stage = next(stage for stage, first, last in STAGE_NAMES if first <= number <= last)
        if level.get("stage") != expected_stage:
            raise ValueError(f"Уровень {number}: неверный этап")
        tasks = level.get("tasks")
        if not isinstance(tasks, list) or not 1 <= len(tasks) <= 5:
            raise ValueError(f"Уровень {number}: неверное число заданий")
        required = level.get("requiredStars")
        if not isinstance(required, int) or not 1 <= required <= len(tasks):
            raise ValueError(f"Уровень {number}: неверный порог R")
        stars += len(tasks)
        types.update(task.get("type") for task in tasks)
    if stars != REQUIRED_STARS:
        raise ValueError(f"Ожидалось {REQUIRED_STARS} звёзд, получено {stars}")
    if levels[-1].get("requiredStars") != 4:
        raise ValueError("Финал должен проходить при 4/5")
    if types != {f"T{i}" for i in range(1, 8)}:
        raise ValueError("Кампания должна содержать T1–T7")


def verify_package(package: Path) -> dict:
    """Проверить staged-пакет до любой записи. Возвращает сведения для manifest."""
    package = Path(package).resolve()
    if not package.is_dir():
        raise ValueError("Каталог пакета не найден")
    for name in (*ARTIFACTS, *EVIDENCE):
        if not (package / name).is_file():
            raise ValueError(f"В пакете нет обязательного файла: {name}")
    sums = read_checksums(package / "SHA256SUMS")
    for name in (*ARTIFACTS, "validation_report.json", "quality_report.json"):
        if name not in sums:
            raise ValueError(f"SHA256SUMS не содержит {name}")
    for name, expected in sums.items():
        if sha256(package / name) != expected:
            raise ValueError(f"SHA-256 не совпал: {name}")

    manifest = read_json(package / "manifest.json")
    campaign = read_json(package / "campaign.json")
    verify_campaign(campaign)
    campaign_sha = sha256(package / "campaign.json")
    words = [line for line in (package / "words.txt").read_text(encoding="utf-8").splitlines() if line]
    if manifest.get("schema") != "dictionary-provenance-v1":
        raise ValueError("Неизвестная схема manifest")
    if manifest.get("words") != len(words):
        raise ValueError("Число слов в manifest не совпадает с words.txt")
    checks = {
        "words.txt": "sha256_words_txt",
        "word_sources.jsonl.gz": "sha256_word_sources",
        "levels.json": "sha256_levels_json",
        "golden_search.json": "sha256_golden_search",
    }
    for name, key in checks.items():
        if manifest.get(key) != sha256(package / name):
            raise ValueError(f"manifest.{key} не совпадает с {name}")
    definitions = manifest.get("owned_riddles") or {}
    if (definitions.get("sha256") != sha256(package / "owned_riddles.json") or
            definitions.get("dictionary_words_sha256") != manifest.get("sha256_words_txt")):
        raise ValueError("lexical_data.json не связана с итоговым словарём")
    if (manifest.get("source") or {}).get("license") != "CC-BY-SA-4.0":
        raise ValueError("Нет подтверждённой лицензии словаря")

    from tools.build_owned_hints import verify
    verify(package)
    validation = read_json(package / "validation_report.json")
    if (validation.get("schema") != "campaign-validation-v1" or validation.get("installable") is not True or
            validation.get("unreachable") or validation.get("balance_deviations") or
            validation.get("parity_deviations")):
        raise ValueError("GEN-04 не подтвердил установку пакета")
    if len(validation.get("levels") or []) != REQUIRED_LEVELS:
        raise ValueError("validation_report.json описывает не 15 уровней")

    quality = read_json(package / "quality_report.json")
    campaign_block = quality.get("campaign") or {}
    if campaign_block.get("sha256") not in (None, campaign_sha):
        raise ValueError("quality_report.json относится к другой кампании")
    if campaign_block.get("levels") not in (None, REQUIRED_LEVELS):
        raise ValueError("quality_report.json описывает не 15 уровней")
    if campaign_block.get("master_stars") not in (None, REQUIRED_STARS):
        raise ValueError("quality_report.json ожидает не 49 звёзд")
    hashes = quality.get("hashes") or {}
    if hashes.get("campaign.json") not in (None, campaign_sha):
        raise ValueError("quality_report.hashes не совпадает с campaign.json")

    existing = (manifest.get("campaign_source") or {}).get("sha256")
    if existing is not None and existing != campaign_sha:
        raise ValueError("manifest.campaign_source указывает на другую кампанию")
    return dict(
        package=package,
        manifest=manifest,
        campaign_sha256=campaign_sha,
        validation_sha256=sha256(package / "validation_report.json"),
        quality_sha256=sha256(package / "quality_report.json"),
        words=len(words),
    )


def installed_manifest(info: dict) -> dict:
    manifest = json.loads(json.dumps(info["manifest"]))
    manifest["campaign_source"] = {
        "generator": "tools/build_campaign.py + tools/rebalance_campaign.py",
        "contract": "docs/CAMPAIGN.md",
        "profile": PROFILE,
        "previous_levels_role": "historical comparison only; not v2 task source",
        "sha256": info["campaign_sha256"],
        "levels": REQUIRED_LEVELS,
        "max_stars": REQUIRED_STARS,
        "validation_report_sha256": info["validation_sha256"],
        "quality_report_sha256": info["quality_sha256"],
    }
    return manifest


def verify_installed(directory: Path, manifest: dict | None = None) -> dict:
    directory = Path(directory)
    manifest = manifest or read_json(directory / "manifest.json")
    campaign_source = manifest.get("campaign_source") or {}
    if campaign_source.get("profile") != PROFILE:
        raise ValueError("Установленный manifest не описывает short15")
    if campaign_source.get("sha256") != sha256(directory / "campaign.json"):
        raise ValueError("SHA-256 установленной кампании не совпадает с manifest")
    for name, key in (
        ("words.txt", "sha256_words_txt"),
        ("word_sources.jsonl.gz", "sha256_word_sources"),
        ("levels.json", "sha256_levels_json"),
        ("golden_search.json", "sha256_golden_search"),
    ):
        if manifest.get(key) != sha256(directory / name):
            raise ValueError(f"Установленный {name} не совпадает с manifest")
    definitions = manifest.get("owned_riddles") or {}
    if (definitions.get("sha256") != sha256(directory / "owned_riddles.json") or
            definitions.get("dictionary_words_sha256") != manifest.get("sha256_words_txt")):
        raise ValueError("Установленная lexical_data.json не совпадает с manifest")
    from tools.build_owned_hints import verify
    verify(directory)
    verify_campaign(read_json(directory / "campaign.json"))
    return manifest


def _backup_matches(directory: Path, files: dict[str, str]) -> bool:
    checksum_file = directory / "SHA256SUMS.rollback"
    if not checksum_file.is_file():
        return False
    if read_checksums(checksum_file) != files:
        return False
    return all((directory / name).is_file() and sha256(directory / name) == digest
               for name, digest in files.items())


def backup_current(output: Path, backup: Path) -> dict | None:
    """Сохранить текущий data/ до замены; повтор с теми же байтами безопасен."""
    output, backup = Path(output), Path(backup)
    if not any((output / name).exists() for name in ARTIFACTS):
        return None
    if not (output / "manifest.json").is_file():
        raise ValueError("Текущий data/ без manifest.json нельзя считать точкой отката")
    current = verify_installed(output) if (output / "campaign.json").is_file() else read_json(output / "manifest.json")
    files = {name: sha256(output / name) for name in ARTIFACTS if (output / name).is_file()}
    if (output / "campaign.json").exists() and (current.get("campaign_source") or {}).get("sha256") == files.get("campaign.json"):
        # Уже установлен именно этот short15: не затираем первоначальную v1-копию.
        if backup.exists():
            return dict(status="kept", path=str(backup))
    if backup.exists():
        if _backup_matches(backup, files):
            return dict(status="existing", path=str(backup))
        raise ValueError("Каталог отката уже содержит другие файлы; укажите другой --backup")
    backup.mkdir(parents=True)
    for name in ARTIFACTS:
        if (output / name).is_file():
            shutil.copyfile(output / name, backup / name)
    checksum_text = "".join(f"{digest}  {name}\n" for name, digest in sorted(files.items()))
    (backup / "SHA256SUMS.rollback").write_text(checksum_text, encoding="utf-8")
    atomic_json(backup / "INSTALL_STATE.json", dict(
        schema="short15-rollback-v1", source=str(output), manifest_sha256=files.get("manifest.json"),
        campaign_present=(output / "campaign.json").exists(), files=files))
    (backup / "RESTORE.md").write_text(
        "# Откат data/ перед short15\n\n"
        "Скопируйте файлы из этого каталога обратно в `data/` и проверьте\n"
        "`sha256sum -c SHA256SUMS.rollback`. Каталог создан до установки\n"
        "проверенного пакета SHORT15-02; `campaign.json` в старой data/ отсутствовал.\n",
        encoding="utf-8")
    return dict(status="created", path=str(backup), files=files)


def install(package: Path, output: Path, backup: Path, *, dry_run: bool = False) -> dict:
    info = verify_package(package)
    output, backup = Path(output).resolve(), Path(backup).resolve()
    if output == info["package"]:
        raise ValueError("--package и --out должны различаться")
    if (output / 'manifest.json').is_file():
        current = read_json(output / 'manifest.json')
        if current.get('owned_riddles'):
            from build_owned_hints import verify
            verify(info['package'])  # Reject old packages before backup or writes.
    already = False
    if (output / "manifest.json").is_file():
        attempt = installed_manifest(info)
        already = all((output / name).is_file() and sha256(output / name) == sha256(info["package"] / name)
                      for name in ARTIFACTS if name != "manifest.json")
        if already:
            try:
                verify_installed(output, attempt)
            except ValueError:
                already = False
    backup_result = None
    if not already and not dry_run:
        backup_result = backup_current(output, backup)
    if dry_run:
        return dict(status="dry-run", package=str(info["package"]), output=str(output),
                    campaign_sha256=info["campaign_sha256"], words=info["words"], already_installed=already)
    with tempfile.TemporaryDirectory(prefix="short15-install-", dir=output.parent) as temporary:
        staging = Path(temporary)
        for name in ARTIFACTS:
            if name != "manifest.json":
                shutil.copyfile(info["package"] / name, staging / name)
        atomic_json(staging / "manifest.json", installed_manifest(info))
        verify_installed(staging)
        game.publish(staging, output, ARTIFACTS)
    verify_installed(output)
    return dict(status="installed", package=str(info["package"]), output=str(output),
                campaign_sha256=info["campaign_sha256"], words=info["words"], backup=backup_result)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "data")
    parser.add_argument("--backup", type=Path, default=ROOT / ".local/short15-rollback-v1-data")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = install(args.package, args.out, args.backup, dry_run=args.dry_run)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
