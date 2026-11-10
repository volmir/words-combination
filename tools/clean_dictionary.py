#!/usr/bin/env python3
"""Офлайн-отбор по значениям, морфологии и частоте; прежние overrides не используются."""
import argparse
import gzip
import hashlib
import json
import random
import re
import sqlite3
from collections import Counter
from functools import lru_cache
from pathlib import Path
from urllib.parse import quote

import build_dictionary as dictionary
from build_game_data import frequencies, publish, write_csv
from game_data_providers import atomic_json, json_digest
from game_data_quality import normalize_form
from prepare_frequency import resolve_frequency_list

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
ARTIFACTS = ("words.txt", "word_sources.jsonl.gz", "levels.json", "golden_search.json", "manifest.json")
# All decisions are derived from source features. No article text is cached.
TAG_REASONS = {
    "abbreviation": "abbreviation", "form-of": "form_variant", "diminutive": "diminutive",
    "obsolete": "obsolete", "archaic": "obsolete", "dated": "obsolete",
    "dialectal": "dialect", "regional": "dialect", "slang": "slang", "reduced": "slang",
    "derogatory": "slang", "pejorative": "slang", "vulgar": "offensive",
    "offensive": "offensive", "obscene": "offensive", "neologism": "neologism",
    "rare": "rare", "proper-noun": "proper_name", "historical": "restricted_usage",
    "religious": "restricted_usage", "feminine-of": "restricted_usage",
    "substantivized": "restricted_usage", "interjection": "not_noun_sense",
}
SOFT_REASONS = {"abbreviation", "rare", "restricted_usage"}
CONNECTOR = re.compile(r"^(?:(?:а\s+)?также|и|или|иногда|обычно|преимущественно|реже|часто)\s+")


def load_config(path):
    config = json.loads(Path(path).read_text(encoding="utf-8"))["cleanup"]
    for key in ("min_occurrences", "abbreviation_min_occurrences", "specialist_min_occurrences", "long_word_length", "long_word_min_occurrences", "sample_seed"):
        if type(config[key]) is not int or config[key] <= 0:
            raise ValueError("Некорректный порог очистки: " + key)
    if not 3 <= config["long_word_length"] <= 30:
        raise ValueError("Длина должна находиться в пределах правил игры")
    for patterns in config["label_patterns"].values():
        for pattern in patterns:
            re.compile(pattern)
    return config


@lru_cache(maxsize=8)
def label_matchers(serialized):
    config = json.loads(serialized)
    patterns = [(reason, pattern) for reason, items in config["label_patterns"].items() for pattern in items]
    for key, reason in (("specialist_labels", "specialist"), ("restricted_labels", "restricted_usage"),
                        ("neutral_labels", "neutral")):
        patterns.extend((reason, re.escape(label) + r"\.") for label in config.get(key, []))
    return [(reason, re.compile("(?:" + pattern + r")(?![а-я])")) for reason, pattern in patterns]


def gloss_features(text, config):
    """Only the leading label chain and explicit relational formulas are evidence."""
    rest = normalize_form(text).strip()
    reasons, specialist, labels = set(), False, []
    patterns = label_matchers(json.dumps({k: config.get(k, []) for k in
        ("label_patterns", "specialist_labels", "restricted_labels", "neutral_labels")}, sort_keys=True))
    while rest:
        rest = rest.lstrip(" [](),;:\u00ab\u00bb")
        # Pick the longest label: e.g. разг.-сниж. must not become neutral разг.
        matches = [(reason, match) for reason, pattern in patterns if (match := pattern.match(rest))]
        if matches:
            reason, match = max(matches, key=lambda item: item[1].end())
            labels.append(match.group())
            if reason == "specialist":
                specialist = True
            elif reason != "neutral":
                reasons.add(reason)
            rest = rest[match.end():]
            continue
        connector = CONNECTOR.match(rest)
        if connector:
            rest = rest[connector.end():]
            continue
        # Unknown leading abbreviations cannot hide a later forbidden label.
        unknown = re.match(r"[а-я]{2,}(?:\.-[а-я]+)?\.(?=[\s,;)])", rest)
        if unknown:
            reasons.add("unknown_label")
            labels.append(unknown.group())
            rest = rest[unknown.end():]
            continue
        break
    if re.match(r"^(?:ошибочное|неправильное) (?:написание|образование)", rest):
        reasons.add("typo")
    if re.match(r"^(?:аббревиатура|акроним|сокращение|сокращенн(?:ое|ая) (?:название|наименование|форма)|сложносокращенн(?:ое слово|ая форма))\b", rest):
        reasons.add("abbreviation")
    if re.match(r"^(?:орфографический вариант|вариант(?:ное)? (?:написание|форм[аы])|форма слова)", rest):
        reasons.add("form_variant")
    if re.match(r"^(?:устаревшее|старинное|архаическое) (?:название|написание|слово)", rest):
        reasons.add("obsolete")
    if re.match(r"^(?:женск(?:ий|ая|ое) (?:род|вариант|соответствие)|субстантивированн[а-я]+)\b", rest):
        reasons.add("restricted_usage")
    specialist |= any(re.search(pattern, rest) for pattern in config["specialist_descriptions"])
    reference = re.fullmatch(r"то же,? что\s+[«\"]?([а-яё]+)[»\"]?[.!]?", rest)
    base = re.match(r"^(?:от|к)\s+(?:слова\s+)?[«\"]?([а-яё]+)\b", rest) if "diminutive" in reasons else None
    return {"reasons": reasons, "specialist": specialist, "labels": labels,
            "reference": dictionary.fold(reference.group(1)) if reference else None,
            "base": dictionary.fold(base.group(1)) if base else None}


def gloss_labels(text, config):
    result = gloss_features(text, config)
    return result["reasons"], result["specialist"], result["labels"]


def field_labels(value, config):
    reasons = {TAG_REASONS[tag] for tag in value.get("tags", []) if tag in TAG_REASONS}
    if value.get("form_of") or value.get("alt_of"):
        reasons.add("form_variant")
    specialist = bool(set(value.get("topics", [])) & set(config["specialist_topics"]))
    for label in value.get("raw_tags", []):
        found, subject, _ = gloss_labels(label, config)
        reasons.update(found)
        specialist |= subject
    return reasons, specialist


def diminutive_stems(word, config):
    """A small transparent morphology heuristic, never a decision by itself."""
    stems = {word.rstrip("аояьеийы")}
    stems.update(word[:-len(suffix)] for suffix in config["reference_diminutive_suffixes"]
                 if word.endswith(suffix) and len(word) - len(suffix) >= 3)
    # Productive consonant alternations: руч-/рук-, нож-/ног-, зайч-/зайц-.
    return {stem.translate(str.maketrans({"ч": "к", "ж": "г", "ш": "х", "ц": "к"})) for stem in stems}


def related_diminutive(word, target, config):
    return (any(word.endswith(suffix) for suffix in config["reference_diminutive_suffixes"])
            and bool(diminutive_stems(word, config) & diminutive_stems(target, config)))


def extract_records(dump, universe, config):
    # universe=None is a broad noun inventory, with NO early sense/diminutive filtering.
    records = {word: {"forms": [word], "sources": [], "senses": [], "other_pos": [], "labels": []}
               for word in sorted(universe or [])}
    opener = gzip.open if str(dump).endswith(".gz") else open
    with opener(dump, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != "ru" or entry.get("lang") != "Русский":
                continue
            title = entry.get("word", "")
            word = dictionary.fold(title)
            if universe is None and entry.get("pos") == "noun" and title == title.lower() and dictionary.valid(word):
                records.setdefault(word, {"forms": [word], "sources": [], "senses": [], "other_pos": [], "labels": []})
            if word not in records:
                continue
            record = records[word]
            if entry.get("pos") != "noun" or title != title.lower():
                record["other_pos"].append("proper_name" if title != title.lower() or entry.get("pos") == "name" else "not_noun")
                continue
            provenance = {"title": title, "line": line_number,
                          "source": "https://ru.wiktionary.org/wiki/" + quote(title, safe="")}
            record["sources"].append(provenance)
            record["forms"].extend(normalize_form(form["form"]) for form in entry.get("forms", [])
                                   if isinstance(form.get("form"), str) and dictionary.valid(normalize_form(form["form"])))
            common_reasons, common_subject = field_labels(entry, config)
            for sense_index, sense in enumerate(entry.get("senses", []) or [{}]):
                reasons, subject = field_labels(sense, config)
                reasons |= common_reasons
                subject |= common_subject
                references, bases = set(), set()
                # glosses describe ONE sense; an unmarked continuation cannot rescue it.
                for text in sense.get("glosses", []) or [""]:
                    features = gloss_features(text, config)
                    reasons |= features["reasons"]
                    subject |= features["specialist"]
                    record["labels"].extend(features["labels"])
                    if features["reference"]:
                        references.add(features["reference"])
                    if features["base"]:
                        bases.add(features["base"])
                record["senses"].append({"reasons": sorted(reasons), "specialist": subject,
                    "reference": next(iter(references)) if len(references) == 1 else None,
                    "bases": sorted(bases), "line": line_number, "sense_index": sense_index})
    for record in records.values():
        for field in ("forms", "other_pos", "labels"):
            record[field] = sorted(set(record[field]))
    # Monotone fixed point: independent of entry/sense order, safe for cycles.
    changed = True
    while changed:
        changed = False
        for word, record in sorted(records.items()):
            for sense in record["senses"]:
                target = records.get(sense["reference"])
                if not target or not related_diminutive(word, sense["reference"], config):
                    continue
                if target["senses"] and all("diminutive" in s["reasons"] for s in target["senses"]):
                    if "diminutive" not in sense["reasons"]:
                        sense["reasons"] = sorted(set(sense["reasons"]) | {"diminutive"})
                        changed = True
    for word, record in records.items():
        for sense in record["senses"]:
            target = records.get(sense["reference"])
            if (target and related_diminutive(word, sense["reference"], config)
                    and "diminutive" not in sense["reasons"]
                    and any("diminutive" in s["reasons"] for s in target["senses"])):
                sense["reasons"] = sorted(set(sense["reasons"]) | {"ambiguous_diminutive_reference"})
    return records


def decide(records, ranks, config, protected=()):
    decisions = {}
    for word, record in sorted(records.items()):
        frequency = ranks[word]["frequency"]
        reasons, exception = [], []
        decision = "exclude"
        modern, usable = [], []
        lexical = config["lexicalized_diminutives"].get(word)
        for sense in record["senses"]:
            flags = set(sense["reasons"])
            # Narrow owner policy only applies to existing, morphologically related senses.
            if (lexical and "diminutive" in flags and not (flags - SOFT_REASONS - {"diminutive"})
                    and related_diminutive(word, lexical["base"], config)
                    and (not sense["bases"] or lexical["base"] in sense["bases"])):
                flags.remove("diminutive")
                exception.append(lexical["reason"])
            if flags - SOFT_REASONS:
                continue
            modern.append(sense)
            if "abbreviation" in flags and (word not in config["lexicalized_abbreviations"]
                    or frequency < config["abbreviation_min_occurrences"]):
                continue
            usable.append(sense)
        compound = any(word.startswith(prefix) and word[len(prefix):] in records
                       for prefix in config["productive_compound_prefixes"])
        diminutive = (any("diminutive" in s["reasons"] and not (set(s["reasons"]) - SOFT_REASONS - {"diminutive"})
                          for s in record["senses"])
                      and any(word.endswith(suffix) for suffix in config["reference_diminutive_suffixes"]))
        if not dictionary.valid(word):
            reasons = ["invalid_word"]
        elif not record["sources"]:
            reasons = record["other_pos"] or ["not_noun"]
        elif not modern:
            reasons = sorted({reason for sense in record["senses"] for reason in sense["reasons"]}) or ["unclassified"]
            if set(reasons) & {"unknown_label", "ambiguous_diminutive_reference"}:
                decision = "review"
        elif not usable:
            reasons = ["abbreviation"]
        elif diminutive and not exception:
            # Wordform frequency cannot establish the usage of the independent sense.
            decision, reasons = "review", ["ambiguous_diminutive_usage"]
        elif compound and word not in config["lexicalized_abbreviations"]:
            decision, reasons = "review", ["compound_abbreviation_candidate"]
        elif frequency < config["min_occurrences"]:
            decision = "review"
            reasons = ["usage_unconfirmed" if frequency == 0 else "low_frequency"]
        elif all(sense["specialist"] or set(sense["reasons"]) & {"rare", "restricted_usage"} for sense in usable) and frequency < config["specialist_min_occurrences"]:
            reasons = ["narrow_term" if all(s["specialist"] for s in usable) else "restricted_usage_frequency"]
        elif len(word) > config["long_word_length"] and frequency < config["long_word_min_occurrences"]:
            decision, reasons = "review", ["compound_candidate"]
        else:
            decision = "keep"
        if decision == "keep" and word in config["lexicalized_abbreviations"] and any("abbreviation" in s["reasons"] for s in usable):
            exception.append(config["lexicalized_abbreviations"][word])
        roles = {role: {"decision": decision, "reasons": list(reasons),
                        "exception": sorted(set(exception)) if decision == "keep" else []}
                 for role in ("answer", "source", "target")}
        if word in config["role_exclusions"]:
            for role in ("source", "target"):
                roles[role] = {"decision": "exclude", "reasons": ["campaign_role_exclusion"],
                               "exception": [config["role_exclusions"][word]]}
        if (word in config["preferred_sources"] and modern
                and set(reasons) <= {"usage_unconfirmed", "low_frequency", "narrow_term", "restricted_usage_frequency",
                                     "compound_candidate", "compound_abbreviation_candidate", "abbreviation"}):
            roles["source"] = {"decision": "keep", "reasons": [],
                               "exception": [config["preferred_sources"][word]]}
        would_reject = []
        if word in protected and reasons:
            if set(reasons) & {"invalid_word", "not_noun", "proper_name"}:
                raise ValueError("Слово уровня нарушает жёсткие ограничения правил игры: " + word)
            would_reject = reasons
            decision, reasons = "keep", []
        decisions[word] = {"decision": decision, "reasons": reasons, "would_reject": would_reject,
                           "frequency": frequency, "freq_rank": ranks[word]["freq_rank"], "level_exception": bool(would_reject),
                           "roles": roles, "sense_count": len(record["senses"]), "usable_sense_count": len(usable)}
    return decisions


def prepare_levels(selected, original, replacements):
    words = sorted(selected)
    levels, changes, errors = [], [], []
    for old_word, answers, needed, old_letter, letter_count in original:
        replacement = replacements.get(old_word)
        word = replacement["word"] if replacement else old_word
        available = dictionary.search(words, word)
        starts = Counter(answer[0] for answer in available)
        letter = replacement.get("letter", old_letter) if replacement else old_letter
        if replacement and starts[letter] < letter_count:
            letter = min(starts, key=lambda char: (-starts[char], char), default=letter)
        if word not in selected or len(available) < max(answers, needed) or starts[letter] < letter_count:
            errors.append({"word": word, "present": word in selected, "available": len(available),
                           "needed": max(answers, needed), "letter": letter, "letter_available": starts[letter], "letter_needed": letter_count})
        levels.append((word, answers, needed, letter, letter_count))
        if replacement:
            changes.append({"old_word": old_word, "new_word": word, "old_letter": old_letter, "new_letter": letter,
                            "answers": answers, "words": needed, "letterCount": letter_count, "available": len(available)})
    if errors:
        raise ValueError("Уровни недостижимы; пороги не изменены: " + json.dumps(errors, ensure_ascii=False))
    if len({level[0] for level in levels}) != len(levels):
        raise ValueError("Замены создают повторные слова уровней и конфликт сохранённого прогресса")
    return levels, changes


def frequency_provenance(path, digest):
    source = {"kind": "frequency-list", "sha256": digest}
    metadata_path = path.parent / "source.json"
    if metadata_path.exists():
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        matching = [item for item in metadata.get("files", []) if item.get("file") == path.name]
        if matching:
            if len(matching) != 1 or matching[0].get("sha256") != digest:
                raise ValueError("SHA-256 частотного списка не совпадает с source.json")
            source.update(project=metadata.get("dataset"), url=metadata.get("repository"),
                          snapshot=metadata.get("commit"), license=metadata.get("content_license_declared_by_upstream"))
    return source


def reports(directory, decisions, records, golden, original_levels, seed):
    rows = []
    fields = ["word", "decision", "frequency", "freq_rank", "reasons", "level_exception", "would_reject",
              "sense_count", "usable_sense_count"]
    for role in ("answer", "source", "target"):
        fields.extend([role + "_decision", role + "_reasons", role + "_exception"])
    for word, decision in sorted(decisions.items()):
        row = {key: value for key, value in decision.items() if key != "roles"}
        row.update(word=word, reasons=";".join(decision["reasons"]),
                   would_reject=";".join(decision["would_reject"]))
        for role, result in decision["roles"].items():
            for key, value in result.items():
                row[role + "_" + key] = ";".join(value) if isinstance(value, list) else value
        rows.append(row)
    write_csv(directory / "decisions.csv", fields, rows)
    write_csv(directory / "review_words.csv", fields, [row for row in rows if row["decision"] == "review"])
    write_csv(directory / "level_exceptions.csv", fields, [row for row in rows if row["level_exception"]])
    exceptions = [{"word": word, "role": role, "decision": value["decision"], "frequency": item["frequency"],
                   "reason": ";".join(value["exception"]), "base_reasons": ";".join(item["roles"]["answer"]["reasons"])}
                  for word, item in sorted(decisions.items()) for role, value in item["roles"].items() if value["exception"]]
    write_csv(directory / "role_exceptions.csv", ["word", "role", "decision", "frequency", "reason", "base_reasons"], exceptions)
    by_reason = {}
    for row in rows:
        for reason in decisions[row["word"]]["reasons"]:
            by_reason.setdefault(reason, []).append(row)
    write_csv(directory / "rejected_by_reason.csv", ["reason", "count"],
              [{"reason": reason, "count": len(words)} for reason, words in sorted(by_reason.items(), key=lambda item: (-len(item[1]), item[0]))])
    atomic_json(directory / "rejection_examples.json", {reason: group[:30] for reason, group in sorted(by_reason.items())})
    atomic_json(directory / "recognized_labels.json", dict(sorted(Counter(label for record in records.values() for label in record["labels"]).items())))
    rng = random.Random(seed)
    selected = [row for row in rows if row["decision"] == "keep"]
    write_csv(directory / "selected_random_50.csv", fields, rng.sample(selected, min(50, len(selected))))
    sample = []
    for status in ("keep", "exclude", "review"):
        group = [row for row in rows if row["decision"] == status]
        sample.extend(dict(row, human_decision="", comment="") for row in rng.sample(group, min(200, len(group))))
    write_csv(directory / "selection_sample.csv", fields + ["human_decision", "comment"], sample)
    atomic_json(directory / "sample_features.json", {row["word"]: records[row["word"]]["senses"] for row in sample})
    # Successive filters are cumulative; role branches follow the answer funnel.
    noun = {w for w, r in records.items() if dictionary.valid(w) and r["sources"]}
    usable = {w for w in noun if decisions[w]["usable_sense_count"]}
    roles = {role: dict(sorted(Counter(d["roles"][role]["decision"] for d in decisions.values()).items()))
             for role in ("answer", "source", "target")}
    atomic_json(directory / "funnel.json", {"input": len(records), "noun_records": len(noun),
        "usable_sense": len(usable), "observed_usage": sum(decisions[w]["frequency"] > 0 for w in usable),
        "roles": roles, "legacy_v1_exceptions": sum(d["level_exception"] for d in decisions.values())})
    return {"golden_excluded": {word: decisions[word]["reasons"] if word in decisions else ["outside_input"]
                                for word in sorted(golden) if word not in decisions or decisions[word]["decision"] != "keep"},
            "level_exceptions": {word: value["would_reject"] for word, value in decisions.items() if value["level_exception"]},
            "role_exceptions": exceptions,
            "original_levels_excluded": [level[0] for level in original_levels if decisions.get(level[0], {}).get("decision") != "keep"]}


def run(args):
    if args.dump is None or not args.dump.is_file():
        raise ValueError("Нужен существующий локальный дамп --dump; загрузок нет")
    explicit_frequency = getattr(args, "frequency_list", None)
    if explicit_frequency is not None and not explicit_frequency.is_file():
        raise ValueError("Указанный --frequency-list отсутствует; скачивания нет")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    dump_sha = dictionary.sha256_file(args.dump)
    if dump_sha != manifest["source"]["sha256_input"] or manifest["source"]["dump_date"] != "2026-11-01":
        raise ValueError("SHA-256 или дата дампа не совпадает с манифестом 2026-11-01")
    args.frequency_list = resolve_frequency_list(explicit_frequency, args.manifest)
    config = load_config(args.config)
    frequency_sha = dictionary.sha256_file(args.frequency_list)
    frequency_source = frequency_provenance(args.frequency_list, frequency_sha)
    audit_only = getattr(args, "audit_only", False)
    prefix = "source-nouns-v2-" if audit_only else "source-vocabulary-"
    vocabulary = args.cache / (prefix + dump_sha + ".json")
    extracted = None
    if args.source_words:
        universe = set(dictionary.read_list(args.source_words))
    elif vocabulary.exists():
        universe = set(json.loads(vocabulary.read_text())["words"])
    else:
        # Never prefilter diminutive-only or plural noun entries out of the input.
        extracted = extract_records(args.dump, None, config)
        universe = set(extracted)
    if not universe:
        raise ValueError("Исходный словарь пуст")
    base_sha = hashlib.sha256(("\n".join(sorted(universe)) + "\n").encode()).hexdigest()
    if vocabulary.exists() and json.loads(vocabulary.read_text())["sha256"] != base_sha:
        raise ValueError("В --cache уже зафиксирован другой исходный словарь; выберите отдельный кэш")
    atomic_json(vocabulary, {"words": sorted(universe), "sha256": base_sha})
    code_sha = json_digest([dictionary.sha256_file(Path(__file__)), dictionary.sha256_file(TOOLS / "build_dictionary.py"),
                            dictionary.sha256_file(TOOLS / "build_game_data.py"), dictionary.sha256_file(TOOLS / "game_data_quality.py")])
    stage_key = json_digest([dump_sha, base_sha, config, code_sha])
    stage = args.cache / "stages" / stage_key
    record_file = stage / "records.json"
    if record_file.exists():
        records = json.loads(record_file.read_text())
    else:
        records = extracted if extracted is not None else extract_records(args.dump, universe, config)
        atomic_json(record_file, records)
    frequency_file = stage / ("frequency-" + frequency_sha + ".json")
    if frequency_file.exists():
        frequency = json.loads(frequency_file.read_text())
    else:
        frequency = frequencies({word: {"eligible": True, "forms": record["forms"]} for word, record in records.items()},
                                frequency_list=args.frequency_list)
        atomic_json(frequency_file, frequency)
    replacements = config["level_replacements"]
    original = dictionary.LEVELS
    protected = set() if audit_only else {replacements.get(level[0], {}).get("word", level[0]) for level in original}
    if protected - universe:
        raise ValueError("Слова уровней отсутствуют в исходном наборе: " + ", ".join(sorted(protected - universe)))
    decisions = decide(records, frequency["words"], config, protected)
    selected = {word for word, value in decisions.items() if value["decision"] == "keep"}
    references = reports(args.reports, decisions, records, set(dictionary.read_list(args.golden)), original, config["sample_seed"])
    if audit_only:
        summary = {"status": "audited", "policy_version": config["policy_version"], "input_words": len(universe),
                   "selected": len(selected), "counts": dict(sorted(Counter(d["decision"] for d in decisions.values()).items())),
                   "reference_checks": references, "input_words_sha256": base_sha, "dump_sha256": dump_sha,
                   "frequency_source": frequency_source, "config_sha256": json_digest(config), "generator_sha256": code_sha,
                   "semantic_assessment": None, "ai_used": False, "published": False, "package_built": False}
        atomic_json(args.reports / "summary.json", summary)
        return summary
    key = json_digest([stage_key, frequency_source, original, sorted(selected)])
    candidate = args.cache / "builds" / key
    candidate.mkdir(parents=True, exist_ok=True)
    # Одинаковый порядок полей до и после JSON-кэша нужен для стабильного gzip.
    sources = {word: [{"title": row["title"], "line": row["line"], "source": row["source"]}
                      for row in records[word]["sources"]] for word in selected}
    (candidate / "words.txt").write_text("\n".join(sorted(selected)) + "\n", encoding="utf-8")
    dictionary.write_word_sources(candidate / "word_sources.jsonl.gz", sources)
    counts = Counter(value["decision"] for value in decisions.values())
    summary = {"input_words": len(universe), "selected": len(selected), "excluded": counts["exclude"], "review": counts["review"],
               "A_modern_noun_candidates": sum(value["usable_sense_count"] > 0 for value in decisions.values()),
               "words_with_observed_forms": sum(value["frequency"] > 0 for value in frequency["words"].values()),
               "level_exceptions": len(references["level_exceptions"]), "golden_excluded": references["golden_excluded"],
               "rejected_by_reason": dict(Counter(reason for value in decisions.values() for reason in value["reasons"])),
               "candidate": str(candidate), "published": False, "ai_used": False, "manual_audit_passed": False,
               "definitions_used_for_selection": False, "frequency_sha256": frequency_sha, "input_words_sha256": base_sha}
    try:
        levels, changes = prepare_levels(selected, original, replacements)
    except ValueError as error:
        summary.update(status="blocked_levels", error=str(error))
        atomic_json(args.reports / "summary.json", summary)
        raise
    dictionary.write_game_data(candidate, sources, "2026-11-01", dump_sha, levels_spec=levels)
    generated = json.loads((candidate / "manifest.json").read_text())
    generated["source"]["selection"] = "Modern game nouns selected by structured fields, leading sense labels and wordform usage; level exceptions recorded"
    generated["selection"] = {"mode": "game-cleanup-v1", "parameters": config, "sha256": json_digest(config),
                              "config_file_sha256": dictionary.sha256_file(args.config), "generator_sha256": code_sha,
                              "input_words": len(universe), "input_words_sha256": base_sha,
                              "frequency_source": frequency_source,
                              "old_overrides_used": False, "definitions_required": False, "candidate_limit": None,
                              "reference_checks": references, "audit": {"passed": False, "method": "automated-first-pass"}}
    generated["levels_source"]["changes"] += "; owner-authorized replacements: " + ", ".join(change["old_word"] for change in changes) + "; numerical thresholds preserved"
    generated["levels_source"]["replacements"] = changes
    atomic_json(candidate / "manifest.json", generated)
    if not args.prepare_only:
        if (args.out / "definitions.db").exists():
            raise ValueError("В --out уже есть definitions.db; требуется отдельная фильтрация базы определений")
        publish(candidate, args.out, ARTIFACTS)
        summary["published"] = True
    summary.update(status="prepared" if args.prepare_only else "built", levels=changes,
                   words_bytes=(candidate / "words.txt").stat().st_size,
                   search_counts={word: value["count"] for word, value in json.loads((candidate / "golden_search.json").read_text()).items()})
    atomic_json(args.reports / "summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, required=True)
    parser.add_argument("--frequency-list", type=Path, help="Локальный список; иначе проверенный стандартный кэш")
    parser.add_argument("--source-words", type=Path, help="Необязательный исходный набор; иначе фиксированный снимок или полный отбор из дампа")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/manifest.json")
    parser.add_argument("--config", type=Path, default=TOOLS / "selection_config.json")
    parser.add_argument("--cache", type=Path, default=TOOLS / "cache/cleanup")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports/dictionary_cleanup")
    parser.add_argument("--out", type=Path, default=ROOT / "data")
    parser.add_argument("--golden", type=Path, default=ROOT / "core/src/test/resources/frequent_nouns_golden.txt")
    parser.add_argument("--audit-only", action="store_true", help="Проверить широкий словарь и роли v2; только отчёты, без сборки пакета и исключений v1")
    parser.add_argument("--prepare-only", action="store_true", help="Собрать и проверить в кэше, сохранив рабочие данные")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, sqlite3.Error) as error:
        parser.exit(2, "Ошибка: %s\n" % error)


if __name__ == "__main__":
    main()
