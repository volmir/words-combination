#!/usr/bin/env python3
"""LEX11-01: deterministic targeted T3 target replacement and manifest refresh.

This tool never downloads data and never installs into ``data/``.  It takes the
installed short15 inputs, applies exactly one editorial change (l11-t3 target),
checks that every other campaign byte-level value is preserved, and writes a
staged campaign plus a refreshed manifest for the existing dictionary staging.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import json
from pathlib import Path

import build_dictionary as dictionary
from game_data_providers import atomic_json
from install_short15_package import PROFILE, REQUIRED_LEVELS, REQUIRED_STARS, verify_campaign

ROOT = Path(__file__).resolve().parents[1]
LEVEL = 11
T3_ID = "l11-t3"
T5_ID = "l11-t5"
SOURCE_WORD = "подстрекатель"
OLD_TARGET = "адресок"
NEW_TARGET = "постель"
T3_MIN_LENGTH = 7
T5_ENDING = "ок"
T5_MIN_LENGTH = 5


def formable(source: str, word: str) -> bool:
    return bool(word) and word != source and not (Counter(word) - Counter(source))


def _task(level: dict, task_id: str) -> dict:
    matches = [task for task in level["tasks"] if task["id"] == task_id]
    if len(matches) != 1:
        raise ValueError(f"Уровень {LEVEL}: ожидалось ровно одно задание {task_id}")
    return matches[0]


def apply_campaign(baseline: dict, words: set[str]) -> tuple[dict, dict]:
    campaign = deepcopy(baseline)
    verify_campaign(campaign)
    levels = [level for level in campaign["levels"] if level["number"] == LEVEL]
    if len(levels) != 1:
        raise ValueError(f"Ожидался ровно один уровень {LEVEL}")
    level = levels[0]
    if level["word"] != SOURCE_WORD:
        raise ValueError("Исходное слово уровня 11 изменено")
    t3 = _task(level, T3_ID)
    t5 = _task(level, T5_ID)
    expected_t3 = {"hintTarget": OLD_TARGET, "id": T3_ID, "minLength": T3_MIN_LENGTH, "type": "T3"}
    expected_t5 = {"ending": T5_ENDING, "id": T5_ID, "minLength": T5_MIN_LENGTH, "type": "T5"}
    if t3 != expected_t3:
        raise ValueError("T3 уровня 11 не совпадает с ожидаемой исходной конфигурацией")
    if t5 != expected_t5:
        raise ValueError("T5 уровня 11 не совпадает с ожидаемой исходной конфигурацией")
    if NEW_TARGET not in words or OLD_TARGET in words:
        raise ValueError("Словарь не соответствует исключению адресок / наличию постель")
    if not formable(SOURCE_WORD, NEW_TARGET) or len(NEW_TARGET) < T3_MIN_LENGTH:
        raise ValueError("Новая цель T3 несоставима или короче minLength")

    before = deepcopy(campaign)
    t3["hintTarget"] = NEW_TARGET

    # Only the one hintTarget field may differ; this includes T5 and all other tasks.
    for old_level, new_level in zip(before["levels"], campaign["levels"]):
        for old_task, new_task in zip(old_level["tasks"], new_level["tasks"]):
            if old_task.get("id") == T3_ID:
                old_task = dict(old_task, hintTarget=NEW_TARGET)
            if old_task != new_task:
                raise ValueError("Изменение затронуло не только цель T3 уровня 11")

    assigned: dict[str, str] = {}
    for item in campaign["levels"]:
        for task in item["tasks"]:
            targets = ([task["hintTarget"]] if task["type"] == "T3"
                       else task.get("targets", []) if task["type"] == "T4" else [])
            for target in targets:
                key = target.lower().replace("ё", "е")
                if key in assigned:
                    raise ValueError(f"Цель {target!r} уже назначена в {assigned[key]}")
                assigned[key] = task["id"]

    t3_answers = sorted(word for word in words
                        if len(word) >= T3_MIN_LENGTH and formable(SOURCE_WORD, word))
    t5_answers = sorted(word for word in words
                        if len(word) >= T5_MIN_LENGTH and word.endswith(T5_ENDING)
                        and formable(SOURCE_WORD, word))
    if NEW_TARGET not in t3_answers:
        raise ValueError("Цель T3 недостижима")
    if not t5_answers or OLD_TARGET in t5_answers:
        raise ValueError("T5 недостижим или исключённое слово осталось ответом")
    return campaign, {"t3_answers": t3_answers, "t5_answers": t5_answers}


def refresh_manifest(manifest: dict, baseline: dict, campaign_path: Path, staging: Path) -> dict:
    updated = deepcopy(manifest)
    updated["levels_source"] = deepcopy(baseline["levels_source"])
    for key in ("source_candidates", "source_only"):
        if key in baseline.get("selection", {}):
            updated["selection"][key] = deepcopy(baseline["selection"][key])
    words = staging / "words.txt"
    sources = staging / "word_sources.jsonl.gz"
    levels = staging / "levels.json"
    golden = staging / "golden_search.json"
    updated["words"] = len(dictionary.read_list(words))
    updated["sha256_words_txt"] = dictionary.sha256_file(words)
    updated["sha256_word_sources"] = dictionary.sha256_file(sources)
    updated["sha256_levels_json"] = dictionary.sha256_file(levels)
    updated["sha256_golden_search"] = dictionary.sha256_file(golden)
    updated["campaign_source"] = {
        "generator": "tools/apply_lex11_01.py",
        "contract": "docs/CAMPAIGN.md",
        "profile": PROFILE,
        "previous_levels_role": "historical comparison only; not v2 task source",
        "sha256": dictionary.sha256_file(campaign_path),
        "levels": REQUIRED_LEVELS,
        "max_stars": REQUIRED_STARS,
    }
    return updated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-data", type=Path, default=ROOT / "data")
    parser.add_argument("--staging", type=Path, required=True,
                        help="каталог clean_dictionary.py с words.txt и sources")
    parser.add_argument("--campaign", type=Path, required=True,
                        help="куда записать адресный campaign.json")
    parser.add_argument("--manifest", type=Path, required=True,
                        help="куда записать обновлённый manifest.json")
    parser.add_argument("--levels", type=Path, default=ROOT / "data/levels.json",
                        help="исторический levels.json, сохраняемый побайтно")
    args = parser.parse_args(argv)
    try:
        baseline_campaign = json.loads((args.baseline_data / "campaign.json").read_text())
        baseline_manifest = json.loads((args.baseline_data / "manifest.json").read_text())
        candidate_manifest = json.loads((args.staging / "manifest.json").read_text())
        words = set(dictionary.read_list(args.staging / "words.txt"))
        campaign, reachability = apply_campaign(baseline_campaign, words)

        args.staging.mkdir(parents=True, exist_ok=True)
        args.campaign.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.campaign.write_text(json.dumps(campaign, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                                 encoding="utf-8")
        if args.levels.resolve() != (args.staging / "levels.json").resolve():
            (args.staging / "levels.json").write_bytes(args.levels.read_bytes())
        manifest = refresh_manifest(candidate_manifest, baseline_manifest, args.campaign, args.staging)
        atomic_json(args.manifest, manifest)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, "Ошибка: %s\n" % error)
    print(json.dumps({
        "words": manifest["words"],
        "campaign_sha256": manifest["campaign_source"]["sha256"],
        "t3_target": NEW_TARGET,
        "t3_answers": len(reachability["t3_answers"]),
        "t5_answers": reachability["t5_answers"],
        "t5_unchanged": {"ending": T5_ENDING, "minLength": T5_MIN_LENGTH},
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
