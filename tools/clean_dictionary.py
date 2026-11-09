#!/usr/bin/env python3
"""Офлайн-отбор игровой базы; определения, ИИ и прежние overrides не используются."""
import argparse
import gzip
import hashlib
import json
import random
import re
import sqlite3
from collections import Counter
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
TAG_REASONS = {"abbreviation": "abbreviation", "form-of": "form_variant", "diminutive": "diminutive",
               "obsolete": "obsolete", "slang": "slang", "reduced": "slang", "neologism": "neologism",
               "rare": "rare", "proper-noun": "proper_name"}


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


def gloss_labels(text, config):
    """Разбираются только начальные пометы, а не произвольные упоминания в статье."""
    rest = normalize_form(text).strip()
    reasons, specialist, labels = set(), False, []
    patterns = [(reason, pattern) for reason, items in config["label_patterns"].items() for pattern in items]
    patterns += [("specialist", re.escape(label) + r"\.") for label in config["specialist_labels"]]
    patterns += [("neutral", re.escape(label) + r"\.") for label in config["neutral_labels"]]
    for _ in range(20):
        rest = rest.lstrip(" [](),;:")
        matched = False
        for reason, pattern in patterns:
            match = re.match("(?:" + pattern + r")(?![а-я])", rest)
            if match:
                labels.append(match.group())
                if reason == "specialist":
                    specialist = True
                elif reason != "neutral":
                    reasons.add(reason)
                rest = rest[match.end():]
                rest = re.sub(r"^\s*(?:и|или|также)\s+", "", rest)
                matched = True
                break
        if not matched:
            break
    if re.match(r"^(?:ошибочное|неправильное) (?:написание|образование)", rest):
        reasons.add("typo")
    if re.match(r"^(?:аббревиатура|сокращение (?:от|слова)|сокращенное название)", rest):
        reasons.add("abbreviation")
    if re.match(r"^(?:орфографический вариант|вариант(?:ное)? (?:написание|форм[аы])|форма слова)", rest):
        reasons.add("form_variant")
    if re.match(r"^(?:устаревшее|старинное|архаическое) (?:название|написание|слово)", rest):
        reasons.add("obsolete")
    specialist |= any(re.search(pattern, rest) for pattern in config["specialist_descriptions"])
    return reasons, specialist, labels


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


def extract_records(dump, universe, config):
    records = {word: {"forms": [word], "sources": [], "senses": [], "other_pos": [], "labels": []} for word in sorted(universe)}
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
            for sense in entry.get("senses", []) or [{}]:
                reasons, subject = field_labels(sense, config)
                reasons |= common_reasons
                subject |= common_subject
                # Отсутствие толкования не является условием удаления слова.
                for text in sense.get("glosses", []) or [""]:
                    found, specialized, labels = gloss_labels(text, config)
                    record["labels"].extend(labels)
                    reference = re.fullmatch(r"(?:[а-я.-]+[.,]?\s+){0,6}то же,? что\s+([а-яё]+)[.!]?", normalize_form(text))
                    record["senses"].append({"reasons": sorted(reasons | found), "specialist": subject or specialized,
                                             "reference": reference.group(1) if reference else None})
    for record in records.values():
        for field in ("forms", "other_pos", "labels"):
            record[field] = sorted(set(record[field]))
    # Отсылочная форма с уменьшительным суффиксом подтверждается отношением
    # к слову с явно помеченным основным уменьшительным значением.
    # Один суффикс, либо одно упоминание другого слова, недостаточны.
    inherited = []
    for word, record in records.items():
        if not any(word.endswith(suffix) for suffix in config["reference_diminutive_suffixes"]):
            continue
        for sense in record["senses"]:
            target = records.get(sense["reference"])
            if target and target["senses"] and "diminutive" in target["senses"][0]["reasons"]:
                inherited.append(sense)
    for sense in inherited:
        sense["reasons"] = sorted(set(sense["reasons"]) | {"diminutive"})
    return records


def decide(records, ranks, config, protected):
    decisions = {}
    for word, record in records.items():
        frequency = ranks[word]["frequency"]
        reasons = []
        decision = "exclude"
        modern = [sense for sense in record["senses"] if not (set(sense["reasons"]) - {"abbreviation"})]
        usable = [sense for sense in modern if not sense["reasons"] or frequency >= config["abbreviation_min_occurrences"]]
        if not dictionary.valid(word):
            reasons = ["invalid_word"]
        elif not record["sources"]:
            reasons = record["other_pos"] or ["not_noun"]
        elif not modern:
            reasons = sorted({reason for sense in record["senses"] for reason in sense["reasons"]}) or ["unclassified"]
        elif not usable:
            reasons = ["abbreviation"]
        elif frequency < config["min_occurrences"]:
            decision = "review"
            reasons = ["usage_unconfirmed" if frequency == 0 else "low_frequency"]
        elif all(sense["specialist"] for sense in usable) and frequency < config["specialist_min_occurrences"]:
            reasons = ["narrow_term"]
        elif len(word) > config["long_word_length"] and frequency < config["long_word_min_occurrences"]:
            decision, reasons = "review", ["compound_candidate"]
        else:
            decision = "keep"
        would_reject = []
        if word in protected and reasons:
            if set(reasons) & {"invalid_word", "not_noun", "proper_name"}:
                raise ValueError("Слово уровня нарушает жёсткие ограничения правил игры: " + word)
            would_reject = reasons
            decision, reasons = "keep", []
        decisions[word] = {"decision": decision, "reasons": reasons, "would_reject": would_reject,
                           "frequency": frequency, "freq_rank": ranks[word]["freq_rank"], "level_exception": bool(would_reject)}
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
    rows = [{"word": word, **decision, "reasons": ";".join(decision["reasons"]),
             "would_reject": ";".join(decision["would_reject"])} for word, decision in sorted(decisions.items())]
    fields = ["word", "decision", "frequency", "freq_rank", "reasons", "level_exception", "would_reject"]
    write_csv(directory / "decisions.csv", fields, rows)
    write_csv(directory / "review_words.csv", fields, [row for row in rows if row["decision"] == "review"])
    write_csv(directory / "level_exceptions.csv", fields, [row for row in rows if row["level_exception"]])
    by_reason = {}
    for word, decision in sorted(decisions.items()):
        for reason in decision["reasons"]:
            by_reason.setdefault(reason, []).append(word)
    write_csv(directory / "rejected_by_reason.csv", ["reason", "count"],
              [{"reason": reason, "count": len(words)} for reason, words in sorted(by_reason.items(), key=lambda item: (-len(item[1]), item[0]))])
    atomic_json(directory / "rejection_examples.json", {reason: words[:30] for reason, words in by_reason.items()})
    atomic_json(directory / "recognized_labels.json", dict(Counter(label for record in records.values() for label in record["labels"]).most_common()))
    rng = random.Random(seed)
    selected = [row for row in rows if row["decision"] == "keep"]
    write_csv(directory / "selected_random_50.csv", fields, rng.sample(selected, min(50, len(selected))))
    sample = []
    for status in ("keep", "exclude", "review"):
        group = [row for row in rows if row["decision"] == status]
        sample.extend(dict(row, human_decision="", comment="") for row in rng.sample(group, min(200, len(group))))
    write_csv(directory / "selection_sample.csv", fields + ["human_decision", "comment"], sample)
    return {"golden_excluded": {word: decisions[word]["reasons"] if word in decisions else ["outside_input"]
                                for word in sorted(golden) if word not in decisions or decisions[word]["decision"] != "keep"},
            "level_exceptions": {word: value["would_reject"] for word, value in decisions.items() if value["level_exception"]},
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
    vocabulary = args.cache / ("source-vocabulary-" + dump_sha + ".json")
    if args.source_words:
        universe = set(dictionary.read_list(args.source_words))
    elif vocabulary.exists():
        universe = set(json.loads(vocabulary.read_text())["words"])
    else:
        universe = set(dictionary.wiktionary_wordlist(args.dump)[0])
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
        records = extract_records(args.dump, universe, config)
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
    protected = {replacements.get(level[0], {}).get("word", level[0]) for level in original}
    if protected - universe:
        raise ValueError("Слова уровней отсутствуют в исходном наборе: " + ", ".join(sorted(protected - universe)))
    decisions = decide(records, frequency["words"], config, protected)
    selected = {word for word, value in decisions.items() if value["decision"] == "keep"}
    references = reports(args.reports, decisions, records, set(dictionary.read_list(args.golden)), original, config["sample_seed"])
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
               "A_modern_noun_candidates": sum(any(not (set(sense["reasons"]) - {"abbreviation"}) for sense in record["senses"])
                                               for record in records.values()),
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
    parser.add_argument("--prepare-only", action="store_true", help="Собрать и проверить в кэше, сохранив рабочие данные")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, sqlite3.Error) as error:
        parser.exit(2, "Ошибка: %s\n" % error)


if __name__ == "__main__":
    main()
