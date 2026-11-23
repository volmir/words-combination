#!/usr/bin/env python3
"""DICT-03B: offline, deterministic restoration of six owner-approved nouns.

Only the explicit lexicalization list is applied. Candidate reports are read-only.
The dump and full frequency list must match the installed manifest before parsing.
No article text is retained. Campaign, golden results and rights evidence are immutable.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile

import build_dictionary as dictionary
import clean_dictionary as clean
from apply_data_03 import read_sources, golden_counts
from audit_game_vocabulary import answers_for, task_accepted, task_needed
from build_game_data import frequencies
from game_data_providers import atomic_json, json_digest
from prepare_frequency import DEFAULT_CACHE, expected_digest, validate

ROOT = Path(__file__).resolve().parents[1]
APPROVED_BASES = {
    "дедушка": "дед", "грядка": "гряда", "книжка": "книга",
    "дочка": "дочь", "полка": "пола", "головка": "голова",
}
APPROVED_WORDS = frozenset(APPROVED_BASES)
FORBIDDEN = frozenset("цветик лапонька мальчишка девчонка печенька звездочка улочка пупочка "
                      "лапочка четверочка тапочка дяденька батенька тятенька мишка малышка ледышка корзинка".split())
DIMINUTIVE_REASONS = {"diminutive", "ambiguous_diminutive_usage", "ambiguous_diminutive_reference"}
ALLOWED_REASONS = DIMINUTIVE_REASONS | {"jargon"}
BASE_WORDS_SHA = "52830763a161476c0d43f23f74e3c3014bddcd49fb2721eb08dae03a2bc7cd01"
BASE_SOURCES_SHA = "fb1431b0cbeabb26ea82fe2339b8a78ea10a2d5a985b4c39cf184cedb859d2d0"


def digest_words(words):
    return hashlib.sha256(("\n".join(sorted(words)) + "\n").encode()).hexdigest()


def verify_inputs(dump, frequency, manifest_path):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if dictionary.sha256_file(dump) != manifest["source"]["sha256_input"]:
        raise ValueError("SHA-256 локального дампа не совпадает с manifest")
    validate(frequency, expected_digest(manifest_path))
    return manifest


def original_config(config):
    baseline = deepcopy(config)
    for word, base in APPROVED_BASES.items():
        if word in FORBIDDEN or word in baseline.get("word_exclusions", {}):
            raise ValueError("Согласованный список пересекается с исключениями: " + word)
        if baseline["lexicalized_diminutives"].pop(word, None) != {
                "base": base, "reason": "owner-confirmed-independent"}:
            raise ValueError("Нет точного решения владельца в конфиге: " + word)
    return baseline


def eligible(record, decision):
    reasons = set(decision["reasons"])
    return (bool(record["sources"]) and decision["decision"] != "keep"
            and bool(reasons & DIMINUTIVE_REASONS) and reasons <= ALLOWED_REASONS)


def select_additions(records, before_decisions, after_decisions):
    added, skipped = [], []
    for word in sorted(APPROVED_WORDS):
        if (word not in records or not eligible(records[word], before_decisions[word])
                or after_decisions[word]["decision"] != "keep"):
            skipped.append({"word": word, "reasons": before_decisions.get(word, {}).get("reasons", ["not_noun"])})
        else:
            added.append(word)
    return added, skipped


def candidates(records, decisions, installed):
    rows = [{"word": word, "frequency": decision["frequency"], "freq_rank": decision["freq_rank"],
             "reasons": decision["reasons"]}
            for word, decision in decisions.items()
            if word not in installed and word not in FORBIDDEN and eligible(records[word], decision)]
    return sorted(rows, key=lambda row: (-row["frequency"], row["word"]))[:200]


def campaign_pools(campaign, before, after):
    rows = []
    for level in campaign["levels"]:
        old = answers_for(level["word"], before)
        new = answers_for(level["word"], after)
        tasks = []
        for task in level["tasks"]:
            pool_before = task_accepted(task, old, campaign["countMinLength"])
            pool_after = task_accepted(task, new, campaign["countMinLength"])
            if not pool_before <= pool_after or len(pool_after) < task_needed(task):
                raise ValueError("Задание теряет запас: " + task["id"])
            tasks.append({"id": task["id"], "before": len(pool_before), "after": len(pool_after),
                          "added": sorted(pool_after - pool_before), "removed": sorted(pool_before - pool_after)})
        rows.append({"level": level["number"], "word": level["word"], "before": len(old), "after": len(new),
                     "added": sorted(new - old), "tasks": tasks})
    return rows


def write_candidates(directory, rows, manifest):
    document = {"schema": "diminutive-candidates-v1", "applied": False, "limit": 200,
                "dump_sha256": manifest["source"]["sha256_input"],
                "frequency_sha256": manifest["selection"]["frequency_source"]["sha256"], "candidates": rows}
    atomic_json(directory / "DIMINUTIVE_CANDIDATES.json", document)
    lines = ["# Кандидаты на отдельное решение владельца", "",
             "Этот список НЕ применяется к словарю и не подтверждает самостоятельность слов. "
             "Показаны отсутствующие существительные, исключённые только по причинам уменьшительности "
             "(возможно вместе с jargon). Явные запреты владельца, включая «корзинка», не предлагаются повторно.", "",
             "Частота — сумма наблюдений словоформ; ранг — среди всех существительных локального дампа. "
             "Порядок: частота по убыванию, затем слово; максимум 200 кандидатов. Текстов статей нет.", "",
             "Машинный отчёт: [DIMINUTIVE_CANDIDATES.json](DIMINUTIVE_CANDIDATES.json).", "",
             "| Слово | Частота | Ранг | Причины |", "|---|---:|---:|---|"]
    lines.extend(f"| {row['word']} | {row['frequency']} | {row['freq_rank'] or '—'} | {', '.join(row['reasons'])} |"
                 for row in rows)
    (directory / "DIMINUTIVE_CANDIDATES.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def run(data, dump, frequency, config_path, reports):
    manifest = verify_inputs(dump, frequency, data / "manifest.json")
    config = clean.load_config(config_path)
    baseline_config = original_config(config)
    paths = {name: dictionary.sha256_file(data / name)
             for name in ("campaign.json", "golden_search.json", "levels.json", "owned_riddles.json")}
    if (paths["campaign.json"] != manifest["campaign_source"]["sha256"]
            or paths["golden_search.json"] != manifest["sha256_golden_search"]):
        raise ValueError("Кампания или golden_search изменены")
    installed = dictionary.read_list(data / "words.txt")
    if (installed != sorted(set(installed)) or set(installed) & FORBIDDEN
            or digest_words(installed) != manifest["sha256_words_txt"]
            or len(installed) != manifest["words"]):
        raise ValueError("Повреждён установленный словарь")
    previous_delta = manifest["selection"].get("independent_words", {}).get("added", [])
    if previous_delta and set(previous_delta) != APPROVED_WORDS:
        raise ValueError("Неожиданный список предыдущих добавлений")
    if not previous_delta and set(installed) & APPROVED_WORDS:
        raise ValueError("Согласованное слово уже присутствует до DICT-03B")
    before = set(installed) - set(previous_delta)
    if digest_words(before) != BASE_WORDS_SHA:
        raise ValueError("Исходный словарь не совпадает с DATA-03")
    sources = read_sources(data / "word_sources.jsonl.gz")
    if (set(sources) != set(installed)
            or dictionary.sha256_file(data / "word_sources.jsonl.gz") != manifest["sha256_word_sources"]):
        raise ValueError("Происхождение не соответствует словарю")
    with tempfile.TemporaryDirectory() as temporary:
        original_sources = Path(temporary) / "word_sources.jsonl.gz"
        dictionary.write_word_sources(original_sources, {w: sources[w] for w in before})
        if dictionary.sha256_file(original_sources) != BASE_SOURCES_SHA:
            raise ValueError("Изменено происхождение исходных слов")

    # Parse the verified dump, rather than trusting an unverified local cache.
    records = clean.extract_records(dump, None, baseline_config)
    ranks = frequencies({w: {"eligible": True, "forms": r["forms"]} for w, r in records.items()},
                        frequency_list=frequency)["words"]
    old_decisions = clean.decide(records, ranks, baseline_config)
    new_decisions = clean.decide(records, ranks, config)
    added, skipped = select_additions(records, old_decisions, new_decisions)
    after = before | set(added)
    campaign = json.loads((data / "campaign.json").read_text(encoding="utf-8"))
    pools = campaign_pools(campaign, before, after)
    if golden_counts(sorted(after)) != json.loads((data / "golden_search.json").read_text(encoding="utf-8")):
        raise ValueError("Добавления меняют golden_search.json")
    # No files are written until input, policy, provenance and pool checks pass.
    for word in added:
        sources[word] = records[word]["sources"]
    (data / "words.txt").write_text("\n".join(sorted(after)) + "\n", encoding="utf-8")
    dictionary.write_word_sources(data / "word_sources.jsonl.gz", sources)
    manifest["words"] = len(after)
    manifest["sha256_words_txt"] = dictionary.sha256_file(data / "words.txt")
    manifest["sha256_word_sources"] = dictionary.sha256_file(data / "word_sources.jsonl.gz")
    manifest["owned_riddles"]["dictionary_words_sha256"] = manifest["sha256_words_txt"]
    selection = manifest["selection"]
    selection.update(parameters=config, sha256=json_digest(config),
                     config_file_sha256=dictionary.sha256_file(config_path))
    selection["independent_words"] = {
        "task": "DICT-03B", "added": added, "reason": "owner-confirmed-independent",
        "previous_words_sha256": BASE_WORDS_SHA, "generator": "tools/apply_dict_03b.py",
        "generator_sha256": dictionary.sha256_file(Path(__file__)),
    }
    atomic_json(data / "manifest.json", manifest)
    if paths != {name: dictionary.sha256_file(data / name) for name in paths}:
        raise ValueError("Изменены защищённые данные кампании/загадок")
    reports.mkdir(parents=True, exist_ok=True)
    write_candidates(reports, candidates(records, new_decisions, after), manifest)
    evidence = {"schema": "dict-03b-report-v1", "before_count": len(before), "after_count": len(after),
                "added": added, "removed": sorted(before - after), "skipped": skipped,
                "words_sha256_before": BASE_WORDS_SHA, "words_sha256_after": manifest["sha256_words_txt"],
                "unchanged_sha256": paths, "pools": pools,
                "eligibility": [{"word": w, "base": APPROVED_BASES[w], "reasons_before": old_decisions[w]["reasons"],
                                 "frequency": ranks[w]["frequency"], "freq_rank": ranks[w]["freq_rank"],
                                 "decision_after": new_decisions[w]["decision"], "sources": records[w]["sources"],
                                 "campaign_levels": [r["level"] for r in pools if w in answers_for(r["word"], {w})]}
                                for w in sorted(APPROVED_WORDS)]}
    atomic_json(reports / "DICT_03B_REPORT.json", evidence)
    return {"words": len(after), "added": added, "skipped": skipped}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--dump", type=Path, required=True)
    parser.add_argument("--frequency-list", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--config", type=Path, default=ROOT / "tools/selection_config.json")
    parser.add_argument("--reports", type=Path, default=ROOT / "docs")
    args = parser.parse_args()
    try:
        result = run(args.data, args.dump, args.frequency_list, args.config, args.reports)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, "BLOCKED: " + str(error) + "\n")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
