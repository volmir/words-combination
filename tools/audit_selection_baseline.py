#!/usr/bin/env python3
"""OPT-03: read-only, reproducible baseline of a 30-slot GEN-02 selection.

The audit does not run GEN-02 and never writes a cache.  It reconstructs the
diagnostic ``R`` set from the recorded 30 slots and the immutable GEN-01
profile payload, recounts every one of the 435 unordered Jaccard pairs, and
separates the *best found* candidate from a *proved impossibility* of a valid
30-slot campaign under the current R.

``R`` here is the ordered list of 30 per-slot recognizable-answer sets:

    {"slot": 1, "word": "...", "recognizable_answers": [...]}

It is not the star threshold ``R`` from the game rules.  The SHA-256 of the
canonical JSON body is the R fingerprint.  A different candidate, profile
payload or answer set produces a different fingerprint.
"""
import argparse
import gzip
import json
from collections import defaultdict
from pathlib import Path

import select_level_words as selection
from build_dictionary import sha256_file
from game_data_providers import atomic_json, json_digest
from game_data_quality import normalize_form
from word_categories import ROLE_EXCLUSIONS

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "selection-baseline-v1"
R_SCHEMA = "selection-r-v1"
PROPOSAL_SCHEMA = "level-word-proposal-v1"
PROFILE_SCHEMA = "level-word-profiles-v1"


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def load_profile(path):
    """Read a GEN-01 cache without trusting its filename or mtime."""
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as source:
        document = json.load(source)
    if document.get("schema") != PROFILE_SCHEMA:
        raise ValueError("Профили: неверная схема")
    payload = document.get("payload")
    if not isinstance(payload, dict) or not isinstance(payload.get("profiles"), dict):
        raise ValueError("Профили: нет payload.profiles")
    if document.get("payload_sha256") != json_digest(payload):
        raise ValueError("Профили: SHA-256 payload не совпадает")
    return document


def load_proposal(path):
    proposal = read_json(path)
    validate_proposal(proposal)
    return proposal


def validate_proposal(proposal, levels=30):
    if not isinstance(proposal, dict) or proposal.get("schema") != PROPOSAL_SCHEMA:
        raise ValueError("Предложение: неверная схема")
    slots = proposal.get("slots")
    if not isinstance(slots, list) or len(slots) != levels:
        raise ValueError("Предложение: нужны ровно 30 слотов")
    indexes = []
    for row in slots:
        if not isinstance(row, dict):
            raise ValueError("Предложение: слот должен быть объектом")
        index, word = row.get("index"), row.get("word")
        if type(index) is not int or not 1 <= index <= levels:
            raise ValueError("Предложение: неверный номер слота")
        if not isinstance(word, str) or not word or normalize_form(word) != word:
            raise ValueError("Предложение: неверное или ненормализованное слово")
        indexes.append(index)
    if sorted(indexes) != list(range(1, levels + 1)):
        raise ValueError("Предложение: номера слотов должны быть уникальны и покрывать 1–30")


def previous_words(previous):
    if isinstance(previous, dict) and "levels" in previous:
        previous = previous["levels"]
    if not isinstance(previous, list):
        return []
    result = []
    for row in previous:
        word = row.get("word") if isinstance(row, dict) else row
        if isinstance(word, str) and word:
            result.append(normalize_form(word))
    return result


def band_for_slot(config, slot):
    number = slot + 1
    for band in config["bands"]:
        if band["first"] <= number <= band["last"]:
            return band
    raise ValueError("Полосы не покрывают слот " + str(number))


def planned_types(config, slot):
    if slot < 25:
        return [f"T{i}" for i in range(1, slot // 5 + 2)]
    return list(config["master_types"][slot - 25])


def task_options(profile, config):
    """The same preliminary capacity witnesses used by GEN-02."""
    p, plan = profile, config["planning"]
    r = p["recognizable"]
    return {
        "T1": {"count"} if r["count_4plus"] else set(),
        "T2": {k for k, value in r["initial_4plus"].items() if value},
        "T3": {w for w in p["riddle_targets"]
               if len(w) >= plan["long_min_length"] and w not in ROLE_EXCLUSIONS},
        "T4": set(p["riddle_targets"]) - ROLE_EXCLUSIONS,
        "T5": {suffix for suffix, lengths in r["suffix_lengths"].items()
               if any(int(number) >= plan["suffix_min_length"] and count
                      for number, count in lengths.items())},
        "T6": {theme for theme, count in r["themes"].items() if count and theme != "other"},
        "T7": {letter for letter, count in r["containing"].items() if count},
    }


def is_candidate(word, profile, alphabet):
    return (bool(profile.get("source_eligible")) and word not in ROLE_EXCLUSIONS
            and 8 <= len(word) <= 13 and normalize_form(word) == word
            and set(word) <= set(alphabet))


def local_failures(word, profile, slot, config, alphabet, options=None):
    if not is_candidate(word, profile, alphabet):
        return ["source_ineligible"]
    band = band_for_slot(config, slot)
    failures = []
    count = len(profile["recognizable_answers"])
    if not band["min"] <= count <= band["max"]:
        failures.append("recognizable_range")
    options = options if options is not None else task_options(profile, config)
    failures.extend(f"{kind}_empty" for kind in planned_types(config, slot)
                    if not options[kind])
    return failures


def candidate_options(profiles, config, alphabet):
    candidates = sorted(word for word, profile in profiles.items()
                        if is_candidate(word, profile, alphabet))
    return candidates, {word: task_options(profiles[word], config) for word in candidates}


def r_records(proposal, profiles):
    records = []
    for row in sorted(proposal["slots"], key=lambda value: value["index"]):
        word = row["word"]
        if word not in profiles:
            raise ValueError("R-набор нельзя восстановить: слово отсутствует в профилях: " + word)
        answers = sorted(set(profiles[word]["recognizable_answers"]))
        records.append({"slot": row["index"], "word": word,
                        "recognizable_answers": answers})
    return records


def r_digest(records):
    return json_digest({"schema": R_SCHEMA, "levels": len(records), "slots": records})


def source_digest(records):
    return json_digest({"schema": R_SCHEMA, "levels": len(records),
                        "sources": [row["word"] for row in records]})


def union_digest(records):
    union = sorted({answer for row in records for answer in row["recognizable_answers"]})
    return json_digest({"schema": R_SCHEMA, "recognizable_union": union})


def pair_metrics(records):
    pairs = []
    for upper, right in enumerate(records):
        right_answers = set(right["recognizable_answers"])
        for lower in range(upper):
            left = records[lower]
            left_answers = set(left["recognizable_answers"])
            intersection = len(left_answers & right_answers)
            union = len(left_answers | right_answers)
            pairs.append({
                "slots": [left["slot"], right["slot"]],
                "words": [left["word"], right["word"]],
                "intersection": intersection,
                "union": union,
                "jaccard": intersection / union if union else 0.0,
            })
    pairs.sort(key=lambda row: row["slots"])
    values = [row["jaccard"] for row in pairs]
    return {
        "pairs_expected": len(records) * (len(records) - 1) // 2,
        "pairs_observed": len(pairs),
        "pairs_complete": len(pairs) == len(records) * (len(records) - 1) // 2,
        "sum": sum(values),
        "mean": sum(values) / len(values) if values else 0.0,
        "max": max(values, default=0.0),
        "pairs": pairs,
    }


def proposal_pair_check(proposal, pairs):
    rows = proposal.get("collisions")
    if not isinstance(rows, list) or not rows:
        return {"available": False}
    expected = {}
    for row in rows:
        if isinstance(row, dict) and isinstance(row.get("slots"), list) and len(row["slots"]) == 2:
            expected[tuple(row["slots"])] = row
    mismatches = []
    for pair in pairs:
        row = expected.get(tuple(pair["slots"]))
        if (row is None or row.get("intersection") != pair["intersection"]
                or row.get("union") != pair["union"]
                or abs(float(row.get("jaccard", -1)) - pair["jaccard"]) > 1e-12):
            if len(mismatches) < 10:
                mismatches.append(pair["slots"])
    return {
        "available": True,
        "rows": len(rows),
        "matches": len(rows) == len(pairs) and not mismatches,
        "mismatch_examples": mismatches,
        "extra_rows": len(set(expected) - {tuple(pair["slots"]) for pair in pairs}),
    }


def short_word_metrics(records):
    by_length = {3: defaultdict(list), 4: defaultdict(list)}
    per_level = []
    for row in records:
        answers = set(row["recognizable_answers"])
        short = {length: sorted(word for word in answers if len(word) == length)
                 for length in (3, 4)}
        per_level.append({
            "slot": row["slot"],
            "word": row["word"],
            "length_3": len(short[3]),
            "length_4": len(short[4]),
            "length_3_4": len(short[3]) + len(short[4]),
        })
        for length in (3, 4):
            for word in short[length]:
                by_length[length][word].append(row["slot"])

    def summary(length):
        levels = {word: slots for word, slots in by_length[length].items()}
        repeated = {word: slots for word, slots in levels.items() if len(slots) >= 2}
        adjacent = []
        for index in range(len(records) - 1):
            left = {word for word in records[index]["recognizable_answers"] if len(word) == length}
            right = {word for word in records[index + 1]["recognizable_answers"] if len(word) == length}
            adjacent.append({"levels": [records[index]["slot"], records[index + 1]["slot"]],
                             "intersection": len(left & right)})
        top = sorted(repeated.items(), key=lambda item: (-len(item[1]), item[0]))[:10]
        return {
            "levels_with_at_least_one": sum(1 for row in per_level if row[f"length_{length}"]),
            "total_occurrences": sum(len(slots) for slots in levels.values()),
            "unique_words": len(levels),
            "on_2plus_levels": len(repeated),
            "on_5plus_levels": sum(1 for slots in repeated.values() if len(slots) >= 5),
            "repeat_occurrences": sum(len(slots) - 1 for slots in repeated.values()),
            "adjacent_intersections": adjacent,
            "top_repeated": [{"word": word, "levels": slots, "count": len(slots)}
                             for word, slots in top],
        }

    three, four = summary(3), summary(4)
    all_short = defaultdict(list)
    for row in records:
        for word in set(row["recognizable_answers"]):
            if len(word) in (3, 4):
                all_short[word].append(row["slot"])
    combined_repeated = {word: slots for word, slots in all_short.items() if len(slots) >= 2}
    return {
        "definition": ("Число узнаваемых ответов длины 3 и 4 в каждом слоте; "
                       "повторы считаются по нормализованному слову между уровнями. "
                       "repeat_occurrences — лишние появления сверх первого."),
        "per_level": per_level,
        "length_3": three,
        "length_4": four,
        "combined": {
            "total_occurrences": sum(len(slots) for slots in all_short.values()),
            "unique_words": len(all_short),
            "on_2plus_levels": len(combined_repeated),
            "on_5plus_levels": sum(1 for slots in combined_repeated.values() if len(slots) >= 5),
            "repeat_occurrences": sum(len(slots) - 1 for slots in combined_repeated.values()),
        },
    }


def band_metrics(config, slot_metrics):
    result = []
    by_slot = {row["slot"]: row for row in slot_metrics}
    for band in config["bands"]:
        numbers = list(range(band["first"], band["last"] + 1))
        rows = [by_slot[number] for number in numbers if number in by_slot]
        result.append({
            "first": band["first"],
            "last": band["last"],
            "min": band["min"],
            "max": band["max"],
            "slots": len(numbers),
            "selected": len(rows),
            "valid_slots": sum(not row["local_violations"] for row in rows),
            "out_of_range_slots": [row["slot"] for row in rows if not row["in_band"]],
            "length_3": sum(row["length_3"] for row in rows),
            "length_4": sum(row["length_4"] for row in rows),
        })
    return result


def alphabet_metrics(alphabet, records):
    letters = set("".join(row["word"] for row in records))
    return {
        "alphabet_size": len(alphabet),
        "letters": alphabet,
        "covered_letters": "".join(letter for letter in alphabet if letter in letters),
        "covered_count": sum(1 for letter in alphabet if letter in letters),
        "missing_letters": "".join(letter for letter in alphabet if letter not in letters),
        "missing_count": sum(1 for letter in alphabet if letter not in letters),
        "all_covered": all(letter in letters for letter in alphabet),
    }


def retained_metrics(records, old_words, config):
    retained = [row["word"] for row in records if row["word"] in old_words]
    return {
        "count": len(retained),
        "words": retained,
        "minimum": config["retain_min"],
        "maximum": config["retain_max"],
        "within_bounds": config["retain_min"] <= len(retained) <= config["retain_max"],
    }


def planning_reservations(records, profiles, config, options, candidates):
    groups = {"targets_T3_T4": {}, "suffixes_T5": {}, "themes_T6": {}}
    candidate_set = set(candidates)
    reserve = 0
    for slot, row in enumerate(records):
        word = row["word"]
        if word not in candidate_set:
            continue
        for kind in planned_types(config, slot):
            values = options[word][kind]
            reserve += min(len(values), config["planning"]["reserve_per_task"])
            group = ("targets_T3_T4" if kind in ("T3", "T4") else
                     "suffixes_T5" if kind == "T5" else
                     "themes_T6" if kind == "T6" else None)
            if group:
                groups[group][f"{slot + 1:02}_{kind}"] = values
    return {key: selection.matching(value) for key, value in groups.items()}, reserve


def independent_feasibility(profiles, config, alphabet, old_words):
    """Prove (or fail to prove) that a valid 30-slot campaign exists now."""
    levels = config["levels"]
    candidates, options = candidate_options(profiles, config, alphabet)
    feasible = {}
    for slot in range(levels):
        feasible[f"{slot + 1:02}"] = {
            word for word in candidates
            if not local_failures(word, profiles[word], slot, config, alphabet, options[word])
        }
    source_matching = selection.matching(feasible)
    possible = set().union(*feasible.values()) if feasible else set()
    covered = set("".join(sorted(possible)))
    old_possible = sorted(set(old_words) & possible)
    missing = "".join(letter for letter in alphabet if letter not in covered)
    reasons = []
    if source_matching["matched"] < levels:
        reasons.append(
            f"По диапазонам и пулам заданий можно заполнить не более "
            f"{source_matching['matched']}/{levels} разных слотов.")
    if len(old_possible) < config["retain_min"]:
        reasons.append(
            f"Старых исходников, подходящих хотя бы одному слоту: "
            f"{len(old_possible)}; требуется минимум {config['retain_min']}.")
    if missing:
        reasons.append("В объединении подходящих кандидатур отсутствуют буквы: " + missing + ".")
    return {
        "proven_infeasible": bool(reasons),
        "scope": ("Только текущий подтверждённый R и текущий пул профилей; "
                  "не глобальное утверждение о будущих данных."),
        "reasons": reasons,
        "slot_candidate_counts": {slot: len(words) for slot, words in feasible.items()},
        "distinct_source_matching": source_matching,
        "old_eligible_any_slot": old_possible,
        "eligible_source_count": len(candidates),
        "max_recognizable_answers": max(
            (len(profiles[word]["recognizable_answers"]) for word in candidates), default=0),
        "missing_letters_in_feasible_pool": missing,
    }


def candidate_evaluation(records, profiles, config, alphabet, old_words, feasibility):
    levels = config["levels"]
    candidates, options = candidate_options(profiles, config, alphabet)
    slot_metrics = []
    violations = []
    selected = [row["word"] for row in records]
    for slot, row in enumerate(records):
        band = band_for_slot(config, slot)
        count = len(row["recognizable_answers"])
        failures = local_failures(row["word"], profiles[row["word"]], slot,
                                  config, alphabet, options.get(row["word"]))
        in_band = band["min"] <= count <= band["max"]
        slot_metrics.append({
            "slot": row["slot"],
            "word": row["word"],
            "band": {"first": band["first"], "last": band["last"],
                     "min": band["min"], "max": band["max"]},
            "recognizable_answers": count,
            "all_answers": len(profiles[row["word"]]["answers"]),
            "in_band": in_band,
            "local_violations": failures,
            "length_3": sum(1 for word in row["recognizable_answers"] if len(word) == 3),
            "length_4": sum(1 for word in row["recognizable_answers"] if len(word) == 4),
        })
        for code in failures:
            violations.append({"code": code, "slot": row["slot"], "word": row["word"]})
    if len(records) != levels:
        violations.append({"code": "slot_count", "actual": len(records), "expected": levels})
    if len(set(selected)) != len(selected):
        violations.append({"code": "duplicate_sources",
                           "count": len(selected) - len(set(selected))})
    retained = retained_metrics(records, old_words, config)
    if not retained["within_bounds"]:
        violations.append({"code": "retained_bounds", "actual": retained["count"],
                           "minimum": config["retain_min"],
                           "maximum": config["retain_max"]})
    letters = alphabet_metrics(alphabet, records)
    if not letters["all_covered"]:
        violations.append({"code": "alphabet_coverage",
                           "missing": letters["missing_letters"]})
    jaccard = pair_metrics(records)
    for metric, limit_key in (("mean", "mean_jaccard_max"), ("max", "max_jaccard_max")):
        if jaccard[metric] > config[limit_key] + 1e-12:
            violations.append({"code": metric + "_jaccard", "actual": jaccard[metric],
                               "limit": config[limit_key]})
    reservations, reserve = planning_reservations(records, profiles, config, options, candidates)
    for name, result in reservations.items():
        if result["unmatched"]:
            violations.append({"code": name, "unmatched": result["unmatched"]})
    return {
        "slot_metrics": slot_metrics,
        "violations": violations,
        "valid": not violations,
        "retained": retained,
        "alphabet": letters,
        "jaccard": jaccard,
        "planning_reservations": reservations,
        "planning_reserve": reserve,
        "feasibility": feasibility,
    }


def input_section(profile_document, proposal, input_hashes):
    actual = dict(input_hashes or {})
    proposal_inputs = proposal.get("inputs") if isinstance(proposal.get("inputs"), dict) else {}
    fields = {
        "profile_file_sha256": ("profiles_sha256", actual.get("profile_file_sha256")),
        "profile_payload_sha256": ("profile_payload_sha256", actual.get("profile_payload_sha256")),
        "words_sha256": ("words_sha256", actual.get("words_sha256")),
        "previous_levels_sha256": ("previous_levels_sha256", actual.get("previous_levels_sha256")),
        "config_sha256": ("config_sha256", actual.get("config_sha256")),
        "alphabet_sha256": ("alphabet_sha256", actual.get("alphabet_sha256")),
        "selector_sha256": ("selector_sha256", actual.get("selector_sha256")),
    }
    matches = {}
    for name, (proposal_key, value) in fields.items():
        expected = proposal_inputs.get(proposal_key)
        matches[name + "_match"] = (None if expected is None or value is None
                                    else expected == value)
    profile_mismatch = any(matches.get(name) is False for name in (
        "profile_file_sha256_match", "profile_payload_sha256_match"))
    other_mismatch = any(matches.get(name) is False for name in (
        "words_sha256_match", "previous_levels_sha256_match",
        "config_sha256_match", "alphabet_sha256_match"))
    return {
        "profile": {
            "schema": profile_document.get("schema"),
            "file_sha256": actual.get("profile_file_sha256"),
            "payload_sha256": actual.get("profile_payload_sha256"),
            "proposal_file_sha256": proposal_inputs.get("profiles_sha256"),
            "proposal_payload_sha256": proposal_inputs.get("profile_payload_sha256"),
            "file_match": matches.get("profile_file_sha256_match"),
            "payload_match": matches.get("profile_payload_sha256_match"),
            "profile_inputs": profile_document.get("inputs", {}),
        },
        "words_sha256": actual.get("words_sha256"),
        "proposal_words_sha256": proposal_inputs.get("words_sha256"),
        "previous_levels_sha256": actual.get("previous_levels_sha256"),
        "proposal_previous_levels_sha256": proposal_inputs.get("previous_levels_sha256"),
        "config_sha256": actual.get("config_sha256"),
        "proposal_config_sha256": proposal_inputs.get("config_sha256"),
        "alphabet_sha256": actual.get("alphabet_sha256"),
        "proposal_alphabet_sha256": proposal_inputs.get("alphabet_sha256"),
        "selector_sha256": actual.get("selector_sha256"),
        "proposal_selector_sha256": proposal_inputs.get("selector_sha256"),
        "matches": matches,
        "profile_version_mismatch": profile_mismatch,
        "other_input_mismatch": other_mismatch,
    }


def reference_section(reference, profiles, candidate_r_sha):
    if not reference:
        return None
    sources = reference.get("sources") or []
    missing = [word for word in sources if word not in profiles]
    records = []
    for number, word in enumerate(sources, 1):
        if word in profiles:
            records.append({"slot": number, "word": word,
                            "recognizable_answers": sorted(set(
                                profiles[word]["recognizable_answers"]))})
    complete = bool(sources) and not missing and len(records) == len(sources)
    digest = r_digest(records) if complete else None
    metrics = pair_metrics(records) if records else None
    reported_mean = reference.get("reported_mean")
    reported_sum = reference.get("reported_sum")
    return {
        "label": reference.get("label"),
        "sources": sources,
        "complete": complete,
        "missing_sources": missing,
        "r": {
            "schema": R_SCHEMA,
            "sha256": digest,
            "same_as_candidate": digest == candidate_r_sha if digest else False,
            "sources_sha256": source_digest(records) if records else None,
        },
        "reported": {"mean": reported_mean, "sum": reported_sum},
        "recomputed_full_pairs": ({"pairs": metrics["pairs_observed"],
                                   "mean": metrics["mean"], "max": metrics["max"],
                                   "sum": metrics["sum"]} if metrics else None),
        "kind": "best_found_measured_composition",
        "proven_minimum": False,
        "note": ("Это не строгий минимум и не доказанная нижняя граница: "
                 "нижние 25 слотов — best_found, верхние 5 — отдельная "
                 "min-cost-flow оценка; SHA R не совпадает с текущим кандидатом. "
                 "Число 5,3458% относится к другому набору R, чем 8,1693%."),
    }


def build_report(profile_document, proposal, previous, config, alphabet,
                 *, input_hashes=None, expected_r_sha256=None, reference=None):
    """Pure report builder used by the CLI and synthetic tests."""
    validate_proposal(proposal, config["levels"])
    if len(alphabet) != 32 or len(set(alphabet)) != 32 or "ё" in alphabet:
        raise ValueError("Ожидались 32 уникальные буквы Alphabet.LETTERS после ё→е")
    profiles = profile_document["payload"]["profiles"]
    records = r_records(proposal, profiles)
    old = previous_words(previous)
    r_sha = r_digest(records)
    candidate_r = r_sha
    inputs = input_section(profile_document, proposal, input_hashes)
    expected = expected_r_sha256 or proposal.get("r_sha256")
    r_version_mismatch = bool(expected and expected != r_sha)
    feasibility = independent_feasibility(profiles, config, alphabet, old)
    candidate = candidate_evaluation(records, profiles, config, alphabet, old, feasibility)
    jaccard = candidate.pop("jaccard")
    slot_metrics = candidate.pop("slot_metrics")
    pair_check = proposal_pair_check(proposal, jaccard["pairs"])
    proposal_pair_mismatch = pair_check.get("matches") is False

    if r_version_mismatch or inputs["profile_version_mismatch"] or proposal_pair_mismatch:
        status = "r_version_mismatch"
    elif inputs["other_input_mismatch"]:
        status = "input_version_mismatch"
    else:
        status = "baseline_recorded"

    if feasibility["proven_infeasible"]:
        classification = "best_found_with_proven_impossibility"
    elif not candidate["valid"]:
        classification = "best_found_search_exhausted"
    else:
        classification = "best_found_valid"

    return {
        "schema": SCHEMA,
        "mode": "read-only",
        "status": status,
        "new_ai_calls": 0,
        "publication": False,
        "r": {
            "schema": R_SCHEMA,
            "sha256": r_sha,
            "sources_sha256": source_digest(records),
            "union_sha256": union_digest(records),
            "levels": len(records),
            "slot_words": [row["word"] for row in records],
            "expected_sha256": expected,
            "version_mismatch": r_version_mismatch,
            "proposal_pair_mismatch": proposal_pair_mismatch,
            "definition": ("Упорядоченные 30 наборов recognizable_answers по слотам; "
                           "канонический JSON {schema, levels, slots}; не порог R "
                           "из правил звёзд."),
        },
        "inputs": inputs,
        "candidate": {
            "kind": "best_found",
            "validation": ("proven_impossible" if feasibility["proven_infeasible"] else
                           "valid" if candidate["valid"] else "search_exhausted"),
            "classification": classification,
            "proposal_status": proposal.get("status"),
            "proposal_installable": proposal.get("installable"),
            "selected_slots": len(records),
            "unique_sources": len({row["word"] for row in records}),
            "valid_slots": sum(not row["local_violations"] for row in slot_metrics),
            "invalid_slots": [row["slot"] for row in slot_metrics if row["local_violations"]],
            "violations": candidate["violations"],
            "retained": candidate["retained"],
            "planning_reservations": candidate["planning_reservations"],
            "planning_reserve": candidate["planning_reserve"],
            "installable": candidate["valid"],
        },
        "evaluation": {
            "best_found": {
                "kind": "best_found",
                "is_lower_bound": False,
                "is_strict_minimum": False,
                "metrics": {
                    "selected": len(records),
                    "unique_sources": len({row["word"] for row in records}),
                    "valid_slots": sum(not row["local_violations"] for row in slot_metrics),
                    "retained": candidate["retained"]["count"],
                    "mean_jaccard": jaccard["mean"],
                    "max_jaccard": jaccard["max"],
                    "covered_letters": candidate["alphabet"]["covered_count"],
                    "missing_letters": candidate["alphabet"]["missing_letters"],
                },
                "violations": candidate["violations"],
            },
            "proven_impossible": feasibility,
            "classification": classification,
            "note": ("best_found — лучший проверенный кандидат ограниченного поиска, "
                     "а не минимум. proven_impossible — доказательство только для "
                     "текущего R; оно не означает невозможность после новых данных."),
        },
        "jaccard": jaccard,
        "levels": {
            "levels": config["levels"],
            "slot_count": len(records),
            "retained": candidate["retained"],
            "slot_metrics": slot_metrics,
        },
        "alphabet": candidate["alphabet"],
        "bands": band_metrics(config, slot_metrics),
        "short_words": short_word_metrics(records),
        "proposal_pair_check": pair_check,
        "reference": reference_section(reference, profiles, candidate_r),
    }


def reference_from_document(document, label="DATA01_ANALYSIS_20261114"):
    floor = document.get("measured_floor") if isinstance(document, dict) else None
    if not isinstance(floor, dict):
        return None
    low = floor.get("low_slots_bands_1_25", {}).get("sources", [])
    high = floor.get("high_sources", [])
    total = floor.get("measured_total_floor", {})
    sources = list(low) + list(high)
    if not sources:
        return None
    return {"label": label, "sources": sources,
            "reported_mean": total.get("mean"), "reported_sum": total.get("sum")}


def run(args):
    config = selection.load_config(args.config)
    alphabet = selection.read_alphabet(args.alphabet)
    profile_document = load_profile(args.profiles)
    proposal = load_proposal(args.proposal)
    previous = read_json(args.previous_levels)
    reference = None
    if args.reference:
        reference = reference_from_document(read_json(args.reference))
    hashes = {
        "profile_file_sha256": sha256_file(args.profiles),
        "profile_payload_sha256": profile_document["payload_sha256"],
        "words_sha256": sha256_file(args.words),
        "previous_levels_sha256": sha256_file(args.previous_levels),
        "config_sha256": sha256_file(args.config),
        "alphabet_sha256": sha256_file(args.alphabet),
        "selector_sha256": sha256_file(Path(selection.__file__)),
    }
    report = build_report(profile_document, proposal, previous, config, alphabet,
                          input_hashes=hashes,
                          expected_r_sha256=args.expected_r_sha256,
                          reference=reference)
    atomic_json(args.output, report)
    return report


def default_profiles():
    summary = ROOT / "build/reports/level_profiles/summary.json"
    if summary.exists():
        return Path(read_json(summary)["cache_path"])
    return ROOT / "tools/cache/level_profiles/4f6d9905ff36c3d8d512d66e18069dd5cbd1f3939aa99c8a2f53dd5e029737bf.json.gz"


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--proposal", type=Path,
                        default=ROOT / "build/reports/level_words_proposal.json",
                        help="level_words_proposal.json проверяемого кандидата")
    parser.add_argument("--profiles", type=Path, help="gzip-кэш GEN-01; по умолчанию summary.json")
    parser.add_argument("--words", type=Path, default=ROOT / "data/words.txt")
    parser.add_argument("--previous-levels", type=Path, default=ROOT / "data/levels.json")
    parser.add_argument("--config", type=Path, default=ROOT / "tools/level_selection_config.json")
    parser.add_argument("--alphabet", type=Path,
                        default=ROOT / "core/src/main/kotlin/com/example/sostavslova/core/Alphabet.kt")
    parser.add_argument("--reference", type=Path,
                        help="необязательный DATA-01 JSON с measured_floor для сравнения R")
    parser.add_argument("--expected-r-sha256",
                        help="закреплённый SHA R; при несовпадении статус r_version_mismatch")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "build/reports/finishing/OPT-03.json")
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    if args.profiles is None:
        args.profiles = default_profiles()
    try:
        report = run(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, "Ошибка базовой метрики: " + str(error) + "\n")
    print(json.dumps({
        "status": report["status"],
        "r_sha256": report["r"]["sha256"],
        "classification": report["candidate"]["classification"],
        "pairs": report["jaccard"]["pairs_observed"],
        "mean_jaccard": report["jaccard"]["mean"],
        "max_jaccard": report["jaccard"]["max"],
        "retained": report["levels"]["retained"]["count"],
        "valid_slots": report["candidate"]["valid_slots"],
        "proven_impossible": report["evaluation"]["proven_impossible"]["proven_infeasible"],
    }, ensure_ascii=False, sort_keys=True))
    if report["status"] != "baseline_recorded":
        parser.exit(3, "Версия R или входных профилей не совпала с закреплённой.\n")


if __name__ == "__main__":
    main()
