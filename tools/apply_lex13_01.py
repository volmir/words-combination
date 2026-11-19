#!/usr/bin/env python3
"""LEX13-01: deterministic targeted T3 target replacement and manifest refresh.

The tool never downloads data and never regenerates the dictionary.  It applies
exactly one owner-approved editorial change (l13-t3 target), checks that every
other campaign byte-level value is preserved, and rewrites the campaign plus the
campaign hash in the installed manifest.  Owner approval, the human audit record
and the authored hint text live in docs/ and are not authored by this tool.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import json
from pathlib import Path

import build_dictionary as dictionary
from game_data_providers import atomic_json
from install_short15_package import verify_campaign

ROOT = Path(__file__).resolve().parents[1]
LEVEL = 13
T3_ID = "l13-t3"
T5_ID = "l13-t5"
SOURCE_WORD = "складирование"
OLD_TARGET = "вероника"
NEW_TARGET = "ровесник"
T3_MIN_LENGTH = 8
T5_ENDING = "ние"
T5_MIN_LENGTH = 6


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
        raise ValueError(f"Исходное слово уровня {LEVEL} изменено")
    t3 = _task(level, T3_ID)
    t5 = _task(level, T5_ID)
    expected_t3 = {"hintTarget": OLD_TARGET, "id": T3_ID, "minLength": T3_MIN_LENGTH, "type": "T3"}
    expected_t5 = {"ending": T5_ENDING, "id": T5_ID, "minLength": T5_MIN_LENGTH, "type": "T5"}
    if t3 != expected_t3:
        raise ValueError("T3 уровня 13 не совпадает с ожидаемой исходной конфигурацией")
    if t5 != expected_t5:
        raise ValueError("T5 уровня 13 не совпадает с ожидаемой исходной конфигурацией")
    if NEW_TARGET not in words or OLD_TARGET not in words:
        raise ValueError("Словарь не содержит ровесник или потерял веронику")
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
                raise ValueError("Изменение затронуло не только цель T3 уровня 13")

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
    if not t5_answers:
        raise ValueError("T5 недостижим")
    return campaign, {"t3_answers": t3_answers, "t5_answers": t5_answers}


def refresh_manifest(manifest: dict, campaign_path: Path) -> dict:
    updated = deepcopy(manifest)
    source = updated["campaign_source"]
    source["generator"] = "tools/apply_lex13_01.py"
    source["sha256"] = dictionary.sha256_file(campaign_path)
    return updated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    args = parser.parse_args(argv)
    try:
        campaign_path = args.data / "campaign.json"
        manifest_path = args.data / "manifest.json"
        baseline_campaign = json.loads(campaign_path.read_text(encoding="utf-8"))
        baseline_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if baseline_manifest["campaign_source"]["sha256"] != dictionary.sha256_file(campaign_path):
            raise ValueError("manifest.campaign_source.sha256 не соответствует campaign.json")
        words = set(dictionary.read_list(args.data / "words.txt"))
        if baseline_manifest.get("sha256_words_txt") != dictionary.sha256_file(args.data / "words.txt"):
            raise ValueError("manifest.sha256_words_txt не соответствует words.txt")
        campaign, reachability = apply_campaign(baseline_campaign, words)
        campaign_path.write_text(json.dumps(campaign, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
                                 encoding="utf-8")
        manifest = refresh_manifest(baseline_manifest, campaign_path)
        atomic_json(manifest_path, manifest)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, "Ошибка: %s\n" % error)
    print(json.dumps({
        "campaign_sha256": manifest["campaign_source"]["sha256"],
        "t3_target": NEW_TARGET,
        "t3_answers": len(reachability["t3_answers"]),
        "t5_answers": len(reachability["t5_answers"]),
        "t5_unchanged": {"ending": T5_ENDING, "minLength": T5_MIN_LENGTH},
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
