#!/usr/bin/env python3
"""DATA-03: remove owner-approved diminutives and function words from installed data.

The exclusion policy lives in ``tools/selection_config.json`` (``word_exclusions``)
and the audit lists live in ``tools/vocabulary_audit_config.json``.  The latter
marks words that stay on purpose with a ``keep`` mapping, so changing an owner
decision is a config edit followed by this deterministic tool.

The tool updates only dictionary-derived files in ``data/``:

* ``words.txt`` and ``word_sources.jsonl.gz``;
* ``golden_search.json`` (counts depend on the dictionary);
* ``manifest.json`` hashes, word count, selection parameters and the riddle
  dictionary checksum.

``campaign.json``, ``levels.json`` and ``owned_riddles.json`` stay byte-for-byte
unchanged.  A second run produces identical bytes.
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
from copy import deepcopy
from pathlib import Path

import build_dictionary as dictionary
from game_data_providers import atomic_json, json_digest

ROOT = Path(__file__).resolve().parents[1]
AUDITED_CATEGORIES = ("productive_diminutives", "function_words")


def read_json(path: Path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def exclusion_policy(audit_config: dict, selection_config: dict) -> tuple[set[str], dict[str, str]]:
    """Return the exact exclusions and the consciously kept words from the configs."""
    excluded: set[str] = set()
    kept: dict[str, str] = {}
    for category in audit_config["categories"]:
        if category["id"] not in AUDITED_CATEGORIES:
            continue
        keep = category.get("keep", {})
        for word in category["words"]:
            if word in keep:
                kept[word] = keep[word]
            else:
                excluded.add(word)
    overlap = excluded & set(kept)
    if overlap:
        raise ValueError("Слово одновременно исключено и оставлено: " + ", ".join(sorted(overlap)))
    return excluded, kept


def read_sources(path: Path) -> dict[str, list[dict]]:
    sources: dict[str, list[dict]] = {}
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            row = json.loads(line)
            sources[row["word"]] = row["sources"]
    return sources


def filtered_sources(sources: dict[str, list[dict]], excluded: set[str]) -> dict[str, list[dict]]:
    return {word: rows for word, rows in sources.items() if word not in excluded}


def golden_counts(words: list[str]) -> dict:
    result = {}
    for word in dictionary.GOLDEN_WORDS:
        found = dictionary.search(words, word)
        result[word] = {"count": len(found), "words": sorted(found, key=lambda w: (len(w), w))}
    return result


def write_golden(path: Path, golden: dict) -> None:
    # Same serialisation as build_dictionary.write_game_data.
    with Path(path).open("w", encoding="utf-8") as output:
        json.dump(golden, output, ensure_ascii=False, indent=2)
        output.write("\n")


def refresh_manifest(manifest: dict, data: Path, excluded: set[str], kept: dict[str, str],
                     selection_config: Path) -> dict:
    updated = deepcopy(manifest)
    words = data / "words.txt"
    sources = data / "word_sources.jsonl.gz"
    golden = data / "golden_search.json"
    campaign = data / "campaign.json"
    campaign_sha = dictionary.sha256_file(campaign)
    if updated["campaign_source"]["sha256"] != campaign_sha:
        raise ValueError("campaign.json изменён: задача DATA-03 не меняет кампанию")
    words_sha = dictionary.sha256_file(words)
    updated["words"] = len(dictionary.read_list(words))
    updated["sha256_words_txt"] = words_sha
    updated["sha256_word_sources"] = dictionary.sha256_file(sources)
    updated["sha256_golden_search"] = dictionary.sha256_file(golden)
    updated.setdefault("owned_riddles", {})["dictionary_words_sha256"] = words_sha
    cleanup = read_json(selection_config)["cleanup"]
    updated["selection"]["parameters"] = cleanup
    updated["selection"]["sha256"] = json_digest(cleanup)
    updated["selection"]["config_file_sha256"] = dictionary.sha256_file(selection_config)
    updated["selection"]["word_cleanup"] = {
        "task": "DATA-03",
        "generator": "tools/apply_data_03.py",
        "generator_sha256": dictionary.sha256_file(Path(__file__)),
        "excluded": sorted(excluded),
        "excluded_count": len(excluded),
        "kept": {word: kept[word] for word in sorted(kept)},
    }
    return updated


def run(data: Path, selection_config: Path, audit_config: Path) -> dict:
    audit = read_json(audit_config)
    selection = read_json(selection_config)
    excluded, kept = exclusion_policy(audit, selection)
    # Audit keep is the single editable owner decision. Export it to the existing
    # cleanup mechanism rather than maintaining a second independent policy.
    exclusions = selection["cleanup"]["word_exclusions"]
    for category in audit["categories"]:
        if category["id"] in AUDITED_CATEGORIES:
            for word in category["words"]:
                if word in kept:
                    exclusions.pop(word, None)
                else:
                    exclusions[word] = "owner-excluded-all-roles; DATA-03"
    selection["cleanup"]["word_keep"] = kept
    words_path = data / "words.txt"
    sources_path = data / "word_sources.jsonl.gz"
    golden_path = data / "golden_search.json"
    manifest_path = data / "manifest.json"

    before = dictionary.read_list(words_path)
    if len(before) != len(set(before)):
        raise ValueError("words.txt содержит повторы")
    after = [word for word in before if word not in excluded]
    if len(before) - len(after) > len(excluded):
        raise ValueError("Исключено больше слов, чем задано конфигом")
    if set(after) & excluded:
        raise ValueError("Исключённое слово осталось в words.txt")

    sources = read_sources(sources_path)
    if set(sources) != set(before):
        raise ValueError("word_sources.jsonl.gz не совпадает по составу с words.txt")
    sources = filtered_sources(sources, excluded)

    manifest = read_json(manifest_path)
    if manifest["campaign_source"]["sha256"] != dictionary.sha256_file(data / "campaign.json"):
        raise ValueError("campaign.json изменён")
    if read_json(selection_config) != selection:
        config_text = selection_config.read_text(encoding="utf-8")
        for key in ("word_exclusions", "word_keep"):
            block = json.dumps(selection["cleanup"][key], ensure_ascii=False, indent=2).replace("\n", "\n    ")
            pattern = r'"' + key + r'": \{.*?\}'
            if re.search(pattern, config_text, flags=re.S):
                config_text = re.sub(pattern, lambda match: '"' + key + '": ' + block,
                                     config_text, count=1, flags=re.S)
            else:
                raise ValueError("В конфиге нет поля " + key)
        selection_config.write_text(config_text, encoding="utf-8")
    words_path.write_text("\n".join(after) + "\n", encoding="utf-8")
    dictionary.write_word_sources(sources_path, sources)
    write_golden(golden_path, golden_counts(after))

    manifest = read_json(manifest_path)
    manifest = refresh_manifest(manifest, data, excluded, kept, selection_config)
    atomic_json(manifest_path, manifest)
    return {
        "words_before": len(before),
        "words_after": len(after),
        "excluded_present": len(set(before) & excluded),
        "excluded_count": len(excluded),
        "kept": sorted(kept),
        "campaign_sha256": manifest["campaign_source"]["sha256"],
        "sha256_words_txt": manifest["sha256_words_txt"],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--selection-config", type=Path, default=ROOT / "tools/selection_config.json")
    parser.add_argument("--audit-config", type=Path, default=ROOT / "tools/vocabulary_audit_config.json")
    args = parser.parse_args(argv)
    try:
        summary = run(args.data, args.selection_config, args.audit_config)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, "Ошибка: %s\n" % error)
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
