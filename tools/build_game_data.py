#!/usr/bin/env python3
"""Возобновляемый конвейер A–F. Сеть доступна только с --allow-network."""
import argparse
import csv
import datetime
import gzip
import json
import math
import os
import random
import re
import shutil
import sqlite3
import tempfile
from collections import Counter
from pathlib import Path
from urllib.parse import quote

import build_dictionary as dictionary
from game_data_providers import ApiProvider, FileProvider, OllamaProvider, atomic_json, json_digest, prompt_hash
from game_data_quality import definition_issues, fold, normalize_form, tokens

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
REJECT_CODES = {"not_noun", "proper_name", "abbreviation", "form_variant", "diminutive", "obsolete",
                "dialect", "jargon", "slang", "neologism", "rare", "narrow_term", "compound", "offensive",
                "typo", "low_familiarity"}
ARTIFACTS = ("words.txt", "word_sources.jsonl.gz", "levels.json", "golden_search.json", "definitions.db", "manifest.json")


def load_config(path):
    config = json.loads(Path(path).read_text(encoding="utf-8"))
    for key in ("target_size", "target_min_size", "min_len", "max_len", "min_familiarity",
                "familiarity_without_frequency", "rank_cutoff", "llm_candidates", "batch_size",
                "definition_min_chars", "definition_max_chars", "requests_per_minute",
                "api_timeout_seconds", "max_output_tokens"):
        if type(config[key]) is not int or config[key] <= 0:
            raise ValueError("Положительное целое обязательно: " + key)
    if not 3 <= config["min_len"] <= config["max_len"] <= 30:
        raise ValueError("Длины должны оставаться в жёстких пределах игры 3–30")
    if not config["min_familiarity"] <= config["familiarity_without_frequency"] <= 5:
        raise ValueError("Некорректные пороги узнаваемости")
    if not 1 <= config["batch_size"] <= 40 or not 0 <= config["definition_retries"] <= 2:
        raise ValueError("Пачка не больше 40; повторных формулировок не больше двух")
    for key in ("ollama_batch_size", "ollama_context_length", "ollama_max_output_tokens", "ollama_timeout_seconds"):
        if key in config and (type(config[key]) is not int or config[key] <= 0):
            raise ValueError("Положительное целое обязательно: " + key)
    if config.get("ollama_batch_size", 4) > 40:
        raise ValueError("Пачка Ollama не больше 40")
    if config.get("ollama_max_output_tokens", 2048) >= config.get("ollama_context_length", 8192):
        raise ValueError("Для Ollama нужен контекст больше лимита вывода")
    if not 0 < config["gloss_similarity_cutoff"] <= 1 or config["api_retries"] < 0:
        raise ValueError("Некорректные параметры проверки или повторов")
    if config["definition_min_chars"] > config["definition_max_chars"]:
        raise ValueError("Некорректная длина определения")
    if (set(config["tag_reasons"]) | set(config["raw_tag_patterns"])) - REJECT_CODES:
        raise ValueError("Неизвестный reason-код в конфиге")
    return config


def label_reasons(value, config):
    result = set()
    tags = set(value.get("tags", []))
    for reason, markers in config["tag_reasons"].items():
        if tags.intersection(markers):
            result.add(reason)
    for label in value.get("raw_tags", []):
        for reason, patterns in config["raw_tag_patterns"].items():
            if any(re.search(pattern, label, re.I) for pattern in patterns):
                result.add(reason)
    if set(value.get("topics", [])) & set(config["narrow_topics"]):
        result.add("narrow_term")
    if value.get("form_of") or value.get("alt_of"):
        result.add("form_variant")
    return result


def prefilter(path, config, protected, universe=None):
    records = {}
    count = 0
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != "ru" or entry.get("lang") != "Русский":
                continue
            count += 1
            title = entry.get("word", "")
            word = fold(title)
            if universe is not None and word not in universe:
                continue
            record = records.setdefault(word, {"word": word, "display": title, "forms": set(), "sources": [],
                                              "glosses": [], "reasons": set(), "accepted": False,
                                              "protected": word in protected, "has_noun": False})
            if record["has_noun"] and entry.get("pos") != "noun":
                continue
            if entry.get("pos") == "noun" and not record["has_noun"]:
                record["has_noun"] = True
                record["reasons"].clear()
                record["display"] = title
            reasons = label_reasons(entry, config)
            if not dictionary.valid(word):
                reasons.add("invalid_word")
            if entry.get("pos") != "noun":
                reasons.add("not_noun")
            if entry.get("pos") == "name" or title != title.lower():
                reasons.add("proper_name")
            if entry.get("pos") == "abbrev":
                reasons.add("abbreviation")
            if len(word) > config["max_len"]:
                reasons.add("compound")
            if len(word) < config["min_len"]:
                reasons.add("too_short")
            if entry.get("pos") == "noun" and dictionary.valid(word):
                record["forms"].add(word)
                record["forms"].update(normalize_form(form["form"]) for form in entry.get("forms", [])
                                       if isinstance(form.get("form"), str) and dictionary.valid(normalize_form(form["form"])))
                article = "https://ru.wiktionary.org/wiki/" + quote(title, safe="")
                record["sources"].append({"title": title, "line": line_number, "source": article})
                senses = entry.get("senses", []) or [{}]
                sense_reasons = [label_reasons(sense, config) for sense in senses]
                # Достаточно одного современного самостоятельного значения.
                if not any(not flags for flags in sense_reasons):
                    reasons.update(set().union(*sense_reasons))
                for sense in senses:
                    for gloss in sense.get("glosses", []):
                        item = {"text": gloss, "source": article}
                        if item not in record["glosses"]:
                            record["glosses"].append(item)
            record["reasons"].update(reasons)
            if not reasons:
                record["accepted"] = True
                record["display"] = title
    for word, record in records.items():
        record.pop("has_noun")
        record["reasons"] = [] if record.pop("accepted") else sorted(record["reasons"])
        record["forms"] = sorted(record["forms"])
        record["suffix_candidate"] = any(word.endswith(suffix) or word[:-1].endswith(suffix)
                                           for suffix in config["diminutive_suffixes"])
        record["eligible"] = not record["reasons"] or record["protected"]
    absent = protected - records.keys()
    impossible = [word for word in protected & records.keys()
                  if not dictionary.valid(word) or not records[word]["sources"]]
    if absent or impossible:
        raise ValueError("Защищённые слова отсутствуют среди допустимых существительных: " +
                         ", ".join(sorted(absent | set(impossible))))
    return {"records": records, "russian_entries": count,
            "A": sum(record["eligible"] for record in records.values())}


def frequencies(records, corpus=None, frequency_list=None):
    if corpus is not None and frequency_list is not None:
        raise ValueError("--corpus и --frequency-list нельзя использовать одновременно")
    eligible = {word: record for word, record in records.items() if record["eligible"]}
    if corpus is None and frequency_list is None:
        return {"skipped": True, "B": len(eligible), "words": {word: {"frequency": None, "freq_rank": None} for word in eligible}}
    forms = {form for word, record in eligible.items() for form in set(record["forms"]) | {word}}
    counts = Counter()
    if frequency_list is not None:
        rows = 0
        with Path(frequency_list).open(encoding="utf-8-sig") as source:
            for line_number, line in enumerate(source, 1):
                if not line.strip():
                    continue
                parts = line.split()
                if len(parts) != 2 or re.fullmatch(r"[0-9]+", parts[1]) is None:
                    raise ValueError("--frequency-list %s:%d: ожидаются словоформа и неотрицательное целое" %
                                     (frequency_list, line_number))
                count = int(parts[1])
                form = normalize_form(parts[0])
                if form in forms:
                    counts[form] += count
                rows += 1
        if not rows:
            raise ValueError("--frequency-list пуст: " + str(frequency_list))
    else:
        carry = ""
        with Path(corpus).open(encoding="utf-8") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), ""):
                text = carry + chunk
                tail = re.search(r"[а-яё\u0300\u0301]+$", text, re.I)
                carry = tail.group() if tail else ""
                body = text[:tail.start()] if tail else text
                counts.update(token for token in tokens(body) if token in forms)
            if carry:
                token = normalize_form(carry)
                if token in forms:
                    counts[token] += 1
    totals = {word: sum(counts[form] for form in set(record["forms"]) | {word}) for word, record in eligible.items()}
    ordered = sorted(totals, key=lambda word: (-totals[word], word))
    return {"skipped": False, "B": len(eligible), "words": {
        word: {"frequency": totals[word], "freq_rank": rank if totals[word] else None}
        for rank, word in enumerate(ordered, 1)}}


def candidate_words(records, ranks, config):
    eligible = [word for word, record in records.items() if record["eligible"]]
    ordered = sorted(eligible, key=lambda word: (ranks[word]["freq_rank"] or math.inf, json_digest(word)))
    protected = [word for word in ordered if records[word]["protected"]]
    if len(protected) > min(config["llm_candidates"], config["target_size"]):
        raise ValueError("Лимит меньше числа защищённых слов")
    ordinary = [word for word in ordered if not records[word]["protected"]]
    chosen = set(protected + ordinary[:config["llm_candidates"] - len(protected)])
    return [word for word in ordered if word in chosen]


def pilot_candidates(candidates, records, size, seed):
    if type(size) is not int or size <= 0:
        raise ValueError("--pilot-size должен быть положительным целым")
    ordered = sorted(candidates, key=lambda word: json_digest(["pilot-v1", seed, word]))
    size = min(size, len(ordered))
    groups = {word: ("protected" if records[word]["protected"] else
                     "suffix_candidate" if records[word]["suffix_candidate"] else "other") for word in ordered}
    familiar = max(1, size // 4) if size else 0
    suffixes = (size - familiar) // 2
    quotas = {"protected": familiar, "suffix_candidate": suffixes, "other": size - familiar - suffixes}
    chosen = set()
    for group, quota in quotas.items():
        chosen.update([word for word in ordered if groups[word] == group][:quota])
    chosen.update([word for word in ordered if word not in chosen][:size - len(chosen)])
    return [word for word in ordered if word in chosen], {word: groups[word] for word in ordered if word in chosen}


def validators(categories):
    def definition(result):
        if not isinstance(result, dict) or set(result) != {"word", "familiarity", "reject", "category", "definition", "unsure"}:
            raise ValueError("Неверная схема ответа определения")
        if not isinstance(result["word"], str) or not dictionary.valid(result["word"]) or result["word"] != fold(result["word"]):
            raise ValueError("Неверное слово в ответе")
        if type(result["familiarity"]) is not int or not 1 <= result["familiarity"] <= 5:
            raise ValueError("familiarity должен быть целым 1–5")
        if not isinstance(result["reject"], list) or any(not isinstance(reason, str) or reason not in REJECT_CODES for reason in result["reject"]):
            raise ValueError("Неизвестный код reject")
        if not isinstance(result["category"], str) or result["category"] not in categories:
            raise ValueError("Неизвестная категория")
        if type(result["unsure"]) is not bool or (result["definition"] is not None and not isinstance(result["definition"], str)):
            raise ValueError("Неверные типы definition/unsure")
        if result["unsure"] and result["definition"] is not None:
            raise ValueError("При unsure=true требуется definition=null")

    def guess(result):
        if not isinstance(result, dict) or set(result) != {"id", "guess"} or not isinstance(result["id"], str):
            raise ValueError("Неверная схема ответа загадки")
        if result["guess"] is not None and not isinstance(result["guess"], str):
            raise ValueError("guess должен быть строкой или null")
    return {"definition": definition, "guess": guess}


def select(records, ranks, answers, candidates, config, removed):
    protected = {word for word, record in records.items() if record["protected"]}
    if removed & protected:
        raise ValueError("Удаление конфликтует с защищёнными словами: " + ", ".join(sorted(removed & protected)))
    decisions, would_reject, accepted = {}, {}, []
    for word, record in records.items():
        reasons = list(record["reasons"])
        if record["eligible"]:
            reasons = []
            if word not in candidates:
                reasons.append("candidate_limit")
            else:
                answer = answers[word]
                reasons.extend(answer["reject"])
                if answer["familiarity"] < config["min_familiarity"]:
                    reasons.append("low_familiarity")
                rank = ranks[word]["freq_rank"]
                if not (rank is not None and rank <= config["rank_cutoff"] or
                        answer["familiarity"] >= config["familiarity_without_frequency"]):
                    reasons.append("frequency_or_familiarity")
        if word in removed:
            reasons.append("complaint")
        if word in protected:
            would_reject[word] = sorted(set(reasons + record["reasons"]))
            reasons = []
        decisions[word] = sorted(set(reasons))
        if not reasons:
            accepted.append(word)
    ordinary = sorted((word for word in accepted if word not in protected),
                      key=lambda word: (-answers[word]["familiarity"], ranks[word]["freq_rank"] or math.inf, json_digest(word)))
    selected = protected | set(ordinary[:config["target_size"] - len(protected)])
    for word in accepted:
        if word not in selected:
            decisions[word] = ["target_limit"]
    return selected, decisions, would_reject


def write_csv(path, fields, rows):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with Path(path).open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def selection_reports(directory, records, decisions, selected, protected_rejections, config):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    rows = [{"word": word, "reason": reason} for word in sorted(decisions) for reason in decisions[word]]
    write_csv(directory / "rejected_by_reason.csv", ["word", "reason"], rows)
    examples = {}
    for row in rows:
        group = examples.setdefault(row["reason"], [])
        if len(group) < 30:
            group.append(row["word"])
    atomic_json(directory / "rejection_examples.json", examples)
    rng = random.Random(config["sample_seed"])
    kept = sorted(selected)
    rejected = sorted(word for word, reasons in decisions.items() if reasons)
    borderline = sorted(word for word, record in records.items() if record["eligible"] and record["suffix_candidate"])
    sample = []
    for decision, words in (("kept", kept), ("rejected", rejected), ("borderline", borderline)):
        for word in rng.sample(words, min(200, len(words))):
            sample.append({"word": word, "decision": decision, "reason": ";".join(decisions.get(word, []))})
    write_csv(directory / "selection_sample.csv", ["word", "decision", "reason"], sample)
    write_csv(directory / "selected_random_50.csv", ["word"], [{"word": word} for word in rng.sample(kept, min(50, len(kept)))])
    protected_rows = [{"word": word, "would_reject": ";".join(reasons)}
                      for word, reasons in sorted(protected_rejections.items()) if reasons]
    write_csv(directory / "protected_words.csv", ["word", "would_reject"], protected_rows)
    return dict(Counter(row["reason"] for row in rows))


def quality_stage(selected, records, answers, provider, prompt, retry_template, config, removed):
    rows, failures, pending = [], {}, 0
    for attempt in range(config["definition_retries"] + 1):
        if attempt == 0:
            current = {word: answers[word] for word in sorted(selected)}
        else:
            retry_words = sorted(word for word, issues in failures.items() if "wiktionary_overlap" in issues)
            if not retry_words:
                break
            retry_prompt = prompt + "\n" + retry_template.replace("{attempt}", str(attempt))
            current = provider.exchange("definition", retry_prompt,
                                        [{"word": word, "diminutive_candidate": records[word]["suffix_candidate"]} for word in retry_words])
            pending += len(retry_words) - len(current)
        for word, answer in current.items():
            record = records[word]
            if word in removed:
                failures[word] = ["complaint"]
                continue
            text = answer["definition"]
            issues = ["unsure"] if answer["unsure"] else definition_issues(
                word, text, record["forms"], [gloss["text"] for gloss in record["glosses"]], config)
            if issues:
                failures[word] = issues
            else:
                failures.pop(word, None)
                rows.append({"word": word, "display": record["display"], "text": text,
                             "source": "", "origin": "own", "category": answers[word]["category"], "guessable": 0})
        if pending:
            break
    return rows, failures, pending


def wiktionary_rows(selected, records, answers, config, removed, failures=None):
    rows = []
    for word in sorted(selected):
        if word in removed:
            if failures is not None:
                failures[word] = ["complaint"]
            continue
        accepted = 0
        rejected = set()
        for gloss in records[word]["glosses"]:
            text = gloss["text"].strip()
            if re.search(r"^(то же,? что|уменьшительн|действие по значению|форма\b|см\.)", text, re.I):
                rejected.add("reference_gloss")
                continue
            issues = definition_issues(word, text, records[word]["forms"], [], config, wiktionary=True)
            if issues:
                rejected.update(issues)
                continue
            rows.append({"word": word, "display": records[word]["display"], "text": text,
                         "source": gloss["source"], "origin": "wiktionary", "category": answers[word]["category"], "guessable": 0})
            accepted += 1
            if accepted == 2:
                break
        if not accepted and failures is not None:
            failures[word] = sorted(rejected) or ["no_glosses"]
    return rows


def deduplicate(rows, failures):
    seen, result = set(), []
    for row in sorted(rows, key=lambda item: (item["origin"] != "own", item["word"], item["text"])):
        key = " ".join(fold(row["text"]).split())
        if key in seen:
            failures.setdefault(row["word"], []).append("duplicate")
        else:
            seen.add(key)
            result.append(row)
    return result


def check_levels(sources, reference, reports):
    errors = []
    previous = {level["word"]: level for level in reference}
    for word, answers, needed, letter, letter_count in dictionary.LEVELS:
        available = dictionary.search(sorted(sources), word)
        first = sum(value.startswith(letter) for value in available)
        if word not in sources or len(available) < max(answers, needed) or first < letter_count:
            old = previous.get(word, {}).get("available", max(needed, answers))
            ratio = min(1, len(available) / max(old, 1))
            errors.append({"word": word, "available": len(available), "letter_available": first,
                           "current": {"answers": answers, "words": needed, "letterCount": letter_count},
                           "proposed": {"answers": min(len(available), max(1, math.floor(answers * ratio))),
                                        "words": min(len(available), max(1, math.floor(needed * ratio))),
                                        "letterCount": min(first, max(1, math.floor(letter_count * ratio)))}})
    if errors:
        atomic_json(Path(reports) / "unreachable_levels.json", errors)
        raise ValueError("Уровни недостижимы; данные сохранены. Пропорциональные предложения требуют решения владельца: " +
                         str(Path(reports) / "unreachable_levels.json"))


def build_database(path, rows, categories, metadata, version):
    if path.exists():
        path.unlink()
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE definitions(id INTEGER PRIMARY KEY, word TEXT NOT NULL, display TEXT NOT NULL,
                ord INTEGER NOT NULL, text TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
                antonym TEXT NOT NULL DEFAULT '', example TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '',
                origin TEXT NOT NULL, category TEXT NOT NULL, guessable INTEGER NOT NULL CHECK(guessable IN (0,1)));
            CREATE TABLE word_metadata(word TEXT PRIMARY KEY, category TEXT NOT NULL);
            CREATE TABLE build_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE INDEX idx_definitions_word ON definitions(word);
        """)
        order = Counter()
        for row in rows:
            order[row["word"]] += 1
            db.execute("INSERT INTO definitions(word,display,ord,text,source,origin,category,guessable) VALUES(?,?,?,?,?,?,?,?)",
                       (row["word"], row["display"], order[row["word"]], row["text"], row["source"],
                        row["origin"], row["category"], row["guessable"]))
        db.executemany("INSERT INTO word_metadata VALUES(?,?)", sorted(categories.items()))
        db.executemany("INSERT INTO build_metadata VALUES(?,?)", sorted((key, str(value)) for key, value in metadata.items()))
        db.execute("PRAGMA user_version = %d" % version)
        db.commit()
        db.execute("VACUUM")


def validate_database(path, records, taxonomy, config):
    with sqlite3.connect(path) as db:
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or db.execute("PRAGMA user_version").fetchone()[0] < 4:
            raise ValueError("База повреждена или имеет старую схему")
        seen = set()
        for word, text, origin, source, category, guessable in db.execute("SELECT word,text,origin,source,category,guessable FROM definitions"):
            if word not in records or category not in taxonomy or origin not in {"own", "wiktionary"} or guessable not in (0, 1):
                raise ValueError("Нарушена целостность определения: " + word)
            if origin == "wiktionary" and not source.startswith("https://ru.wiktionary.org/wiki/") or origin == "own" and source:
                raise ValueError("Неверное происхождение определения: " + word)
            record = records[word]
            issues = definition_issues(word, text, record["forms"], [gloss["text"] for gloss in record["glosses"]], config,
                                       wiktionary=origin == "wiktionary")
            key = " ".join(fold(text).split())
            if issues or key in seen:
                raise ValueError("Определение не прошло ворота: " + word + " " + ",".join(issues))
            seen.add(key)
        for word, category in db.execute("SELECT word,category FROM word_metadata"):
            if word not in records or category not in taxonomy:
                raise ValueError("Неверная категория слова: " + word)


def manifest_identity(manifest):
    copy = json.loads(json.dumps(manifest))
    copy["definitions"].pop("audit", None)
    return json_digest(copy)


def audit_sample(candidate, output, reports, seed):
    manifest = json.loads((candidate / "manifest.json").read_text())
    identity = manifest_identity(manifest)
    path = Path(reports) / "definitions_audit_sample.csv"
    binding = path.with_suffix(".csv.meta.json")
    if binding.exists():
        previous = json.loads(binding.read_text())
        if previous["identity"] == identity and path.exists():
            return path
        path = Path(reports) / ("definitions_audit_sample." + identity[:16] + ".csv")
        binding = path.with_suffix(".csv.meta.json")
        if binding.exists() and json.loads(binding.read_text())["identity"] == identity and path.exists():
            return path
    with sqlite3.connect(candidate / "definitions.db") as db:
        rows = [dict(zip(("id", "word", "text", "origin", "source", "category", "guessable"), row)) for row in db.execute(
            "SELECT id,word,text,origin,source,category,guessable FROM definitions ORDER BY id")]
    selected = random.Random(seed).sample(rows, min(300, len(rows)))
    immutable = {str(row["id"]): json_digest({key: str(value) for key, value in row.items()}) for row in selected}
    write_csv(path, ["id", "word", "text", "origin", "source", "category", "guessable", "ok", "bad", "comment"],
              [dict(row, ok="", bad="", comment="") for row in selected])
    atomic_json(binding, {"identity": identity, "candidate": str(candidate.resolve()), "out": str(output.resolve()),
                          "rows": immutable, "sha256_db": manifest["definitions"]["sha256"]})
    return path


def publish(candidate, output, artifacts=ARTIFACTS):
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="game-data-", dir=output.parent) as temporary:
        stage = Path(temporary)
        for name in artifacts:
            shutil.copyfile(candidate / name, stage / name)
            if dictionary.sha256_file(candidate / name) != dictionary.sha256_file(stage / name):
                raise ValueError("Ошибка копирования артефакта: " + name)
        # Манифест заменяется последним; до этого проверка хэшей блокирует смешанную сборку.
        backups = {}
        try:
            for name in artifacts:
                target = output / name
                if target.exists():
                    backup = stage / (name + ".backup")
                    shutil.copyfile(target, backup)
                    backups[name] = backup
                os.replace(stage / name, target)
        except OSError:
            for name in artifacts:
                if name in backups:
                    os.replace(backups[name], output / name)
                elif not (stage / name).exists():
                    (output / name).unlink(missing_ok=True)
            raise


def verify_selection_inputs(manifest, overrides, config):
    selection = manifest.get("selection") or {}
    if (selection.get("config_file_sha256") != dictionary.sha256_file(config) or
            selection.get("remove_sha256") != json_digest(sorted(set(dictionary.read_list(overrides / "remove.txt")))) or
            selection.get("remove_definitions_sha256") != json_digest(sorted(set(dictionary.read_list(overrides / "remove_definitions.txt"))))):
        raise ValueError("Конфиг или списки жалоб изменились; пересоберите данные и проведите новый аудит")


def verify_audit(path, output, reviewer, overrides=TOOLS / "overrides", config=TOOLS / "selection_config.json"):
    if not reviewer.strip():
        raise ValueError("Укажите --reviewer: аудит проводит человек")
    binding = json.loads(path.with_suffix(".csv.meta.json").read_text())
    candidate = Path(binding["candidate"])
    manifest = json.loads((candidate / "manifest.json").read_text())
    if str(output.resolve()) != binding["out"] or manifest_identity(manifest) != binding["identity"]:
        raise ValueError("Аудит относится к другой сборке или каталогу")
    verify_selection_inputs(manifest, overrides, config)
    for name, key in (("words.txt", "sha256_words_txt"), ("word_sources.jsonl.gz", "sha256_word_sources"),
                      ("levels.json", "sha256_levels_json"), ("golden_search.json", "sha256_golden_search")):
        if dictionary.sha256_file(candidate / name) != manifest[key]:
            raise ValueError("Артефакты изменились после создания выборки")
    if dictionary.sha256_file(candidate / "definitions.db") != binding["sha256_db"]:
        raise ValueError("База изменилась после создания выборки")
    if (output / "definitions.db").exists() and dictionary.sha256_file(output / "definitions.db") != binding["sha256_db"]:
        with sqlite3.connect(output / "definitions.db") as current, sqlite3.connect(candidate / "definitions.db") as proposed:
            if current.execute("PRAGMA user_version").fetchone()[0] >= proposed.execute("PRAGMA user_version").fetchone()[0]:
                raise ValueError("Нельзя подтвердить устаревшую сборку поверх новой версии БД")
    seen, checked, bad = set(), 0, 0
    with path.open(encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source):
            identity = row["id"]
            immutable = {key: value for key, value in row.items() if key not in {"ok", "bad", "comment"}}
            if identity in seen or binding["rows"].get(identity) != json_digest(immutable):
                raise ValueError("Состав или содержимое выборки аудита изменено")
            seen.add(identity)
            ok_value, bad_value = row["ok"].strip().lower(), row["bad"].strip().lower()
            valid = {"", "0", "1", "true", "false", "да", "нет"}
            if ok_value not in valid or bad_value not in valid:
                raise ValueError("В ok/bad допустимы пустое значение, 0/1, false/true, нет/да")
            ok_flag, bad_flag = ok_value in {"1", "true", "да"}, bad_value in {"1", "true", "да"}
            if ok_flag and bad_flag:
                raise ValueError("Одновременно заполнены ok и bad")
            checked += int(ok_flag or bad_flag)
            bad += int(bad_flag)
    if seen != set(binding["rows"]) or checked < 300 or bad / checked > 0.03:
        raise ValueError("Аудит не пройден: требуется >=300 проверок и <=3%% bad; проверено=%d, bad=%d" % (checked, bad))
    manifest["definitions"]["audit"] = {"passed": True, "checked": checked, "bad": bad, "bad_fraction": bad / checked,
                                        "reviewer": reviewer, "sample_sha256": dictionary.sha256_file(path),
                                        "database_sha256": binding["sha256_db"]}
    atomic_json(candidate / "manifest.json", manifest)
    publish(candidate, output)
    return manifest["definitions"]["audit"]


def require_release_audit(output, overrides=TOOLS / "overrides", config=TOOLS / "selection_config.json"):
    database = output / "definitions.db"
    if not database.exists():
        return
    manifest = json.loads((output / "manifest.json").read_text())
    block = manifest.get("definitions") or {}
    audit = block.get("audit") or {}
    digest = dictionary.sha256_file(database)
    if (block.get("rights_policy") not in {"own-generated-v1", "hybrid-v1"} or block.get("sha256") != digest or
            audit.get("passed") is not True or audit.get("database_sha256") != digest or
            type(audit.get("checked")) is not int or type(audit.get("bad")) is not int or
            audit.get("checked", 0) < 300 or not 0 <= audit.get("bad", -1) <= audit.get("checked", 0) * 0.03):
        raise ValueError("Release заблокирован: definitions.db не соответствует пройденному ручному аудиту")
    verify_selection_inputs(manifest, overrides, config)


def build_reference_data(args, manifest, input_sha, config, taxonomy, a, b, candidates, stage, reports, code_hash, protected):
    """Экспериментальная база из дампа: без ИИ, без слова при отсутствии пригодного толкования."""
    records = a["records"]
    candidates = set(candidates)
    removed = set(dictionary.read_list(args.overrides / "remove.txt"))
    removed_definitions = set(dictionary.read_list(args.overrides / "remove_definitions.txt"))
    key = json_digest(["dump-definitions-v1", input_sha, sorted(candidates), config, code_hash, b["source"],
                       sorted(removed), sorted(removed_definitions)])
    cached = stage / ("reference-" + key + ".json")
    if cached.exists():
        extracted = json.loads(cached.read_text())
        rows, failures = extracted["rows"], extracted["failures"]
    else:
        failures = {}
        categories = {word: {"category": "other"} for word in candidates}
        rows = wiktionary_rows(candidates, records, categories, config, removed | removed_definitions, failures)
        rows = deduplicate(rows, failures)
        atomic_json(cached, {"rows": rows, "failures": failures})
    selected = {row["word"] for row in rows}
    decisions = {word: ([] if word in selected else
                        ["no_definition"] + failures.get(word, ["missing"]) if word in candidates else
                        record["reasons"] if not record["eligible"] else ["candidate_limit"])
                 for word, record in records.items()}
    overrides = {word: decisions[word] or records[word]["reasons"] for word in protected}
    reasons = selection_reports(reports, records, decisions, selected, overrides, config)
    candidate = args.cache / "builds" / key
    candidate.mkdir(parents=True, exist_ok=True)
    old_database = args.out / "definitions.db"
    previous_version, previous_content = 3, None
    if old_database.exists():
        with sqlite3.connect(old_database) as db:
            previous_version = db.execute("PRAGMA user_version").fetchone()[0]
            previous_content = dict(db.execute("SELECT key,value FROM build_metadata")).get("content_sha256")
    state_path = candidate / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {
        "date": args.generation_date or datetime.date.today().isoformat(), "version": 4}
    if args.generation_date and args.generation_date != state["date"]:
        raise ValueError("Дата отличается от сохранённой сборки; используйте отдельный --cache")
    state["version"] = max(state["version"], previous_version + int(previous_content != key), 4)
    atomic_json(state_path, state)
    generator = {"provider": "local-dump", "model": None, "prompt_sha256": None, "date": state["date"]}
    rights_review = {"passed": False, "status": "unverified-provisional", "replacement_required": True,
                     "decision": "docs/decisions/2026-11-06-provisional-definitions.md"}
    database = candidate / "definitions.db"
    metadata = {"content_sha256": key, "rights_policy": "wiktionary-provisional-v1", "source_sha256": input_sha,
                "generator": json.dumps(generator, sort_keys=True), "rights_review": json.dumps(rights_review, sort_keys=True),
                "selection_sha256": json_digest(config), "categories_sha256": json_digest(taxonomy),
                "guessability_evaluated": "false"}
    rebuild = not database.exists()
    if not rebuild:
        with sqlite3.connect(database) as db:
            rebuild = db.execute("PRAGMA user_version").fetchone()[0] != state["version"]
    if rebuild:
        build_database(database, rows, dict.fromkeys(selected, "other"), metadata, state["version"])
    validate_database(database, {word: records[word] for word in selected}, taxonomy, config)
    (candidate / "words.txt").write_text("\n".join(sorted(selected)) + ("\n" if selected else ""), encoding="utf-8")
    sources = {word: records[word]["sources"] for word in selected}
    dictionary.write_word_sources(candidate / "word_sources.jsonl.gz", sources)
    definitions = {"rights_policy": "wiktionary-provisional-v1", "rights_review": rights_review,
                   "sha256": dictionary.sha256_file(database), "generator": generator,
                   "counts": {"own": 0, "wiktionary": len(rows)}, "audit": {"passed": False},
                   "user_version": state["version"], "guessability_evaluated": False}
    selection = {"mode": "definition-coverage-v1", "ai_used": False, "parameters": config,
                 "sha256": json_digest(config), "config_file_sha256": dictionary.sha256_file(args.config),
                 "corpus_sha256": b["source"]["sha256"] if b["source"]["kind"] == "corpus" else None,
                 "frequency_list_sha256": b["source"]["sha256"] if b["source"]["kind"] == "frequency-list" else None,
                 "frequency_source": b["source"], "frequency_skipped": b["skipped"],
                 "categories_sha256": json_digest(taxonomy),
                 "candidates": len(candidates), "candidates_sha256": json_digest(sorted(candidates)),
                 "remove_sha256": json_digest(sorted(removed)),
                 "remove_definitions_sha256": json_digest(sorted(removed_definitions)),
                 "target_size_applied": False}
    missing = sorted(protected - selected)
    summary = {"input_words": len(records), "A": a["A"], "B": b["B"], "frequency_skipped": b["skipped"],
               "frequency_source": b["source"],
               "ai_used": False, "candidates": len(candidates), "D": len(selected), "definitions": len(rows),
               "excluded_without_definition": len(candidates - selected), "without_definition": 0,
               "without_definition_fraction": 0, "guessability_evaluated": False, "guessable_zero_fraction": None,
               "rejected_by_reason": reasons, "source_sha256": input_sha, "database": str(database),
               "words_file": str(candidate / "words.txt"), "candidate_directory": str(candidate),
               "missing_protected_words": missing, "published": False,
               "sizes": {name: (candidate / name).stat().st_size for name in ("words.txt", "definitions.db")}}
    atomic_json(candidate / "prototype_metadata.json", {"definitions": definitions, "selection": selection, "summary": summary})
    write_csv(reports / "definition_failures.csv", ["word", "reason"],
              [{"word": word, "reason": reason} for word in sorted(candidates - selected)
               for reason in failures.get(word, ["missing"])])
    write_csv(reports / "protected_definition_gaps.csv", ["word", "reasons"],
              [{"word": word, "reasons": ";".join(decisions[word])} for word in missing])
    rng = random.Random(config["sample_seed"])
    write_csv(reports / "definitions_review_sample.csv", ["word", "text", "origin", "source", "ok", "bad", "comment"],
              [dict({key: row[key] for key in ("word", "text", "origin", "source")}, ok="", bad="", comment="")
               for row in rng.sample(rows, min(300, len(rows)))])
    reference_file = args.manifest.parent / "levels.json"
    reference = json.loads(reference_file.read_text()) if reference_file.exists() else []
    level_error = None
    try:
        check_levels(sources, reference, reports)
    except ValueError as error:
        level_error = str(error)
    if missing or level_error:
        summary["status"] = "blocked_protected_words_or_levels"
        atomic_json(reports / "funnel.json", summary)
        raise ValueError("База собрана в кэше; рабочие данные сохранены. Отсутствуют защищённые слова: " +
                         ", ".join(missing) + (". " + level_error if level_error else ""))
    dictionary.write_game_data(candidate, sources, manifest["source"]["dump_date"], input_sha, definitions)
    generated = json.loads((candidate / "manifest.json").read_text())
    generated["selection"] = selection
    atomic_json(candidate / "manifest.json", generated)
    summary["audit_sample"] = str(audit_sample(candidate, args.out, reports, config["sample_seed"]))
    publish(candidate, args.out)
    summary.update(status="built_provisional_definitions", published=True)
    atomic_json(reports / "funnel.json", summary)
    return summary


def run(args):
    if args.check_release:
        require_release_audit(args.out, args.overrides, args.config)
        return {"release_audit": "ok"}
    if args.verify_audit:
        return verify_audit(args.verify_audit, args.out, args.reviewer, args.overrides, args.config)
    if getattr(args, "definitions_source", "own") == "clean":
        if getattr(args, "frequency_list", None) is None:
            raise ValueError("Для очистки нужен локальный --frequency-list")
        import clean_dictionary
        cleanup_args = argparse.Namespace(**vars(args))
        cleanup_args.cache = args.cache / "cleanup"
        cleanup_args.reports = args.reports / "dictionary_cleanup"
        return clean_dictionary.run(cleanup_args)
    pilot_size = getattr(args, "pilot_size", None)
    if pilot_size is not None and (type(pilot_size) is not int or pilot_size <= 0):
        raise ValueError("--pilot-size должен быть положительным целым")
    if pilot_size and args.with_wiktionary_glosses:
        raise ValueError("Проба --pilot-size предназначена для собственных определений")
    reports = args.reports
    if pilot_size:
        label = re.sub(r"[^A-Za-z0-9_-]", "_", args.model or "external-unspecified")
        reports = reports / ("pilot-%s-%s" % (pilot_size, label))
    if args.dump is None or not args.dump.is_file():
        raise ValueError("Нужен существующий локальный дамп --dump; загрузок нет")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    input_sha = dictionary.sha256_file(args.dump)
    if input_sha != manifest["source"]["sha256_input"] or manifest["source"]["dump_date"] != "2026-11-01":
        raise ValueError("SHA-256 или дата дампа не совпадает с согласованным манифестом 2026-11-01")
    if args.corpus is not None and not args.corpus.is_file():
        raise ValueError("Указанный --corpus отсутствует; скачивания нет")
    frequency_list = getattr(args, "frequency_list", None)
    if args.corpus is not None and frequency_list is not None:
        raise ValueError("--corpus и --frequency-list нельзя использовать одновременно")
    if frequency_list is not None and not frequency_list.is_file():
        raise ValueError("Указанный --frequency-list отсутствует; скачивания нет")
    config = load_config(args.config)
    definitions_source = getattr(args, "definitions_source", "own")
    if definitions_source == "dump" and pilot_size:
        raise ValueError("--pilot-size применяется только к оценке ИИ; для дампа используется полный набор кандидатов")
    taxonomy = json.loads(args.categories.read_text(encoding="utf-8"))
    if not isinstance(taxonomy, dict) or not taxonomy:
        raise ValueError("Нужна непустая таксономия категорий")
    protected = set(dictionary.read_list(args.golden)) | {level[0] for level in dictionary.LEVELS}
    source_words = args.source_words or args.manifest.parent / "words.txt"
    universe_path = args.cache / "source_vocabulary" / (json_digest([input_sha, dictionary.sha256_file(source_words) if args.source_words else None]) + ".json")
    if universe_path.exists():
        universe = set(json.loads(universe_path.read_text())["words"])
    elif source_words.is_file():
        universe = set(dictionary.read_list(source_words))
        atomic_json(universe_path, {"words": sorted(universe), "source_sha256": dictionary.sha256_file(source_words)})
    else:
        universe = None
    code_hash = json_digest([dictionary.sha256_file(Path(__file__)), dictionary.sha256_file(TOOLS / "game_data_quality.py")])
    a_key = json_digest([input_sha, config, sorted(protected), sorted(universe) if universe is not None else None, code_hash])
    stage = args.cache / "stages" / a_key
    stage.mkdir(parents=True, exist_ok=True)
    a_path = stage / "A.json"
    if a_path.exists():
        a = json.loads(a_path.read_text())
    else:
        a = prefilter(args.dump, config, protected, universe)
        atomic_json(a_path, a)
    records = a["records"]
    if universe is not None and universe - records.keys():
        raise ValueError("Слова исходного списка не найдены в дампе: " + ", ".join(sorted(universe - records.keys())))
    rng = random.Random(config["sample_seed"])
    eligible_words = sorted(word for word, record in records.items() if record["eligible"])
    write_csv(reports / "prefilter_random_50.csv", ["word"],
              [{"word": word} for word in rng.sample(eligible_words, min(50, len(eligible_words)))])
    corpus_sha = dictionary.sha256_file(args.corpus) if args.corpus else None
    frequency_list_sha = dictionary.sha256_file(frequency_list) if frequency_list else None
    frequency_source = {"kind": "frequency-list" if frequency_list else "corpus" if args.corpus else "none",
                        "sha256": frequency_list_sha if frequency_list else corpus_sha}
    b_path = stage / ("B-" + json_digest(frequency_source) + ".json")
    if b_path.exists():
        b = json.loads(b_path.read_text())
    else:
        b = frequencies(records, args.corpus, frequency_list)
        b["source"] = frequency_source
        atomic_json(b_path, b)
    candidate_config = dict(config, llm_candidates=config.get("reference_candidates", 60000)) if definitions_source == "dump" else config
    candidates = candidate_words(records, b["words"], candidate_config)
    if definitions_source == "dump":
        return build_reference_data(args, manifest, input_sha, config, taxonomy, a, b, candidates, stage, reports, code_hash, protected)
    sample_groups = {}
    if pilot_size:
        candidates, sample_groups = pilot_candidates(candidates, records, pilot_size, config["sample_seed"])
        records = {word: records[word] for word in candidates}
        write_csv(reports / "pilot_words.csv", ["word", "sample_group", "protected", "diminutive_candidate"],
                  [{"word": word, "sample_group": sample_groups[word], "protected": records[word]["protected"],
                    "diminutive_candidate": records[word]["suffix_candidate"]} for word in candidates])
    base = args.prompt.read_text(encoding="utf-8").replace("{min_chars}", str(config["definition_min_chars"]))
    base = base.replace("{max_chars}", str(config["definition_max_chars"]))
    prompt = base + "\nКатегории: " + json.dumps(taxonomy, ensure_ascii=False, sort_keys=True) + "\nКоды reject: " + ", ".join(sorted(REJECT_CODES))
    retry = (TOOLS / "prompts/definition_retry_v1.txt").read_text(encoding="utf-8")
    guess_prompt = (TOOLS / "prompts/guess_v1.txt").read_text(encoding="utf-8")
    model = args.model or "external-unspecified"
    if args.provider != "file" and not args.model:
        raise ValueError("Для API требуется явно выбранная --model")
    if args.provider == "ollama":
        provider = OllamaProvider(args.cache, model, validators(taxonomy), config, args.allow_network,
                                  getattr(args, "ollama_url", "http://127.0.0.1:11434"))
    elif args.provider == "file":
        provider = FileProvider(args.cache, model, validators(taxonomy), config)
    else:
        provider = ApiProvider(args.cache, model, validators(taxonomy), config, args.provider, args.allow_network)
    answers = provider.exchange("definition", prompt, [{"word": word, "diminutive_candidate": records[word]["suffix_candidate"]} for word in candidates])
    summary = {"input_words": len(a["records"]), "A": a["A"], "B": b["B"], "frequency_skipped": b["skipped"],
               "frequency_source": b["source"],
               "llm_candidates": len(candidates), "answers": len(answers), "D": None,
               "pending_definitions": len(candidates) - len(answers), "source_sha256": input_sha,
               "offensive_filter_enabled": True, "model": model}
    if pilot_size:
        summary.update({"pilot_size": len(candidates), "sample_groups": dict(Counter(sample_groups.values())),
                        "published": False, "levels_checked": False})
    reports.mkdir(parents=True, exist_ok=True)
    if len(answers) != len(candidates):
        decisions = {word: record["reasons"] if not record["eligible"] else [] for word, record in records.items()}
        overrides = {word: record["reasons"] for word, record in records.items() if record["protected"] and record["reasons"]}
        summary["rejected_by_reason"] = selection_reports(reports, records, decisions, set(), overrides, config)
        summary["status"] = "awaiting_definition_responses"
        atomic_json(reports / "funnel.json", summary)
        return summary
    if model == "external-unspecified":
        raise ValueError("Перед импортом укажите фактическую --model и используйте запросы, экспортированные с этой моделью")
    removed = set(dictionary.read_list(args.overrides / "remove.txt"))
    removed_definitions = set(dictionary.read_list(args.overrides / "remove_definitions.txt"))
    d_path = stage / ("D-" + json_digest([answers, sorted(removed), sorted(candidates), b["source"]]) + ".json")
    if d_path.exists():
        cached = json.loads(d_path.read_text())
        selected, decisions, overrides = set(cached["selected"]), cached["decisions"], cached["protected"]
    else:
        selected, decisions, overrides = select(records, b["words"], answers, set(candidates), config, removed)
        atomic_json(d_path, {"selected": sorted(selected), "decisions": decisions, "protected": overrides})
    summary["D"] = len(selected)
    summary["rejected_by_reason"] = selection_reports(reports, records, decisions, selected, overrides, config)
    sources = {word: records[word]["sources"] for word in selected}
    reference_file = args.manifest.parent / "levels.json"
    reference = json.loads(reference_file.read_text()) if reference_file.exists() else []
    if not pilot_size:
        check_levels(sources, reference, reports)
    e_path = stage / ("E-" + json_digest([sorted(selected), answers, sorted(removed_definitions), prompt_hash(prompt), prompt_hash(retry), model]) + ".json")
    cached_e = json.loads(e_path.read_text()) if e_path.exists() else {}
    if cached_e.get("complete"):
        rows, failures, pending = cached_e["rows"], cached_e["failures"], 0
    else:
        rows, failures, pending = quality_stage(selected, records, answers, provider, prompt, retry, config, removed_definitions)
        atomic_json(e_path, {"rows": rows, "failures": failures, "complete": pending == 0})
    if args.with_wiktionary_glosses:
        f_path = stage / ("F-" + json_digest([sorted(selected), answers, sorted(removed_definitions)]) + ".json")
        if f_path.exists():
            imported = json.loads(f_path.read_text())
        else:
            imported = wiktionary_rows(selected, records, answers, config, removed_definitions)
            atomic_json(f_path, imported)
        rows += imported
    rows = deduplicate(rows, failures)
    summary["pending_rewrites"] = pending
    if pending:
        summary["status"] = "awaiting_rewrite_responses"
        atomic_json(reports / "funnel.json", summary)
        return summary
    riddles = [{"id": json_digest([row["word"], row["text"], row["category"]]), "category": row["category"],
                "definition": row["text"], "length": len(row["word"]), "first_letter": row["word"][0]} for row in rows]
    guesses = provider.exchange("guess", guess_prompt, riddles)
    summary["pending_guesses"] = len(riddles) - len(guesses)
    if len(guesses) != len(riddles):
        summary["status"] = "awaiting_guess_responses"
        atomic_json(reports / "funnel.json", summary)
        return summary
    for row, riddle in zip(rows, riddles):
        guess = guesses[riddle["id"]]["guess"]
        row["guessable"] = int(guess is not None and fold(guess) == row["word"])
    if pilot_size:
        by_word = {row["word"]: row for row in rows}
        result_file = reports / "pilot_results.csv"
        write_csv(result_file, ["word", "sample_group", "familiarity", "reject", "selection_reasons", "category",
                                "generated_definition", "definition", "definition_issues", "guessable"],
                  [{"word": word, "sample_group": sample_groups[word], "familiarity": answers[word]["familiarity"],
                    "reject": ";".join(answers[word]["reject"]), "selection_reasons": ";".join(decisions[word]),
                    "category": answers[word]["category"], "generated_definition": answers[word]["definition"],
                    "definition": by_word.get(word, {}).get("text", ""), "definition_issues": ";".join(failures.get(word, [])),
                    "guessable": by_word.get(word, {}).get("guessable", "")} for word in candidates])
        summary.update({"status": "pilot_complete", "pilot_results": str(result_file), "definitions": len(rows),
                        "without_definition": len(selected - by_word.keys()),
                        "without_definition_fraction": len(selected - by_word.keys()) / max(1, len(selected)),
                        "definition_reasons": dict(Counter(reason for word in selected - by_word.keys()
                                                           for reason in failures.get(word, ["missing"]))),
                        "guessable_zero_fraction": sum(not row["guessable"] for row in rows) / max(1, len(rows)),
                        "usage": provider.usage_summary()})
        atomic_json(reports / "funnel.json", summary)
        return summary
    selected_records = {word: records[word] for word in selected}
    content = json_digest([rows, sorted(selected), config, model, prompt_hash(prompt), b["source"], input_sha,
                           taxonomy, args.provider, code_hash, prompt_hash(guess_prompt), args.generation_date,
                           sorted(removed), sorted(removed_definitions), args.with_wiktionary_glosses])
    candidate = args.cache / "builds" / content
    candidate.mkdir(parents=True, exist_ok=True)
    state_path = candidate / "state.json"
    previous_version, previous_content = 3, None
    old_database = args.out / "definitions.db"
    if old_database.exists():
        with sqlite3.connect(old_database) as db:
            previous_version = db.execute("PRAGMA user_version").fetchone()[0]
            previous_content = dict(db.execute("SELECT key,value FROM build_metadata")).get("content_sha256")
    if state_path.exists():
        state = json.loads(state_path.read_text())
    else:
        state = {"date": args.generation_date or datetime.date.today().isoformat(),
                 "version": max(4, previous_version + int(previous_content != content))}
    state["version"] = max(state["version"], previous_version + int(previous_content != content), 4)
    atomic_json(state_path, state)
    if args.generation_date and args.generation_date != state["date"]:
        raise ValueError("Дата отличается от уже сохранённой сборки; используйте отдельный --cache")
    rights = "hybrid-v1" if any(row["origin"] == "wiktionary" for row in rows) else "own-generated-v1"
    generator = {"provider": args.provider, "model": model, "prompt_sha256": prompt_hash(prompt),
                 "prompt_version": "definition_v1", "date": state["date"],
                 "retry_prompt_sha256": [prompt_hash(prompt + "\n" + retry.replace("{attempt}", str(attempt)))
                                         for attempt in range(1, config["definition_retries"] + 1)],
                 "guess_prompt_sha256": prompt_hash(guess_prompt)}
    database = candidate / "definitions.db"
    metadata = {"rights_policy": rights, "content_sha256": content, "generator": json.dumps(generator, sort_keys=True),
                "source_sha256": input_sha, "selection_sha256": json_digest(config),
                "own_license": "<ЛИЦЕНЗИЯ — решает владелец>", "categories_sha256": json_digest(taxonomy)}
    rebuild = not database.exists()
    if not rebuild:
        with sqlite3.connect(database) as db:
            rebuild = db.execute("PRAGMA user_version").fetchone()[0] != state["version"]
    if rebuild:
        build_database(database, rows, {word: answers[word]["category"] for word in selected}, metadata, state["version"])
    validate_database(database, selected_records, taxonomy, config)
    definitions = {"rights_policy": rights, "sha256": dictionary.sha256_file(database), "generator": generator,
                   "counts": dict(Counter(row["origin"] for row in rows)), "audit": {"passed": False},
                   "user_version": state["version"]}
    definitions["counts"] = {origin: definitions["counts"].get(origin, 0) for origin in ("own", "wiktionary")}
    dictionary.write_game_data(candidate, sources, manifest["source"]["dump_date"], input_sha, definitions)
    generated = json.loads((candidate / "manifest.json").read_text())
    generated["selection"] = {"parameters": config, "sha256": json_digest(config), "config_file_sha256": dictionary.sha256_file(args.config),
                              "corpus_sha256": corpus_sha, "frequency_list_sha256": frequency_list_sha,
                              "frequency_source": b["source"], "frequency_skipped": b["skipped"],
                              "categories_sha256": json_digest(taxonomy),
                              "remove_sha256": json_digest(sorted(removed)), "remove_definitions_sha256": json_digest(sorted(removed_definitions))}
    existing = args.out / "manifest.json"
    current = json.loads(existing.read_text()) if existing.exists() else {}
    if current.get("definitions") and manifest_identity(current) == manifest_identity(generated):
        generated["definitions"]["audit"] = current["definitions"].get("audit", {"passed": False})
    atomic_json(candidate / "manifest.json", generated)
    sample = audit_sample(candidate, args.out, reports, config["sample_seed"])
    with_definitions = {row["word"] for row in rows}
    summary.update({"definitions": len(rows), "without_definition": len(selected - with_definitions),
                    "without_definition_fraction": len(selected - with_definitions) / max(1, len(selected)),
                    "definition_reasons": dict(Counter(reason for word in selected - with_definitions for reason in failures.get(word, ["missing"]))),
                    "guessable_zero_fraction": sum(not row["guessable"] for row in rows) / max(1, len(rows)),
                    "sizes": {name: (candidate / name).stat().st_size for name in ("definitions.db", "words.txt")},
                    "audit_sample": str(sample), "below_target_minimum": len(selected) < config["target_min_size"]})
    write_csv(reports / "definitions_rewrite_candidates.csv", ["word", "origin", "category", "text"],
              [{key: row[key] for key in ("word", "origin", "category", "text")} for row in rows if not row["guessable"]])
    write_csv(reports / "definition_failures.csv", ["word", "reason"],
              [{"word": word, "reason": reason} for word in sorted(failures) for reason in failures[word]])
    if definitions["counts"]["wiktionary"] and not generated["definitions"]["audit"].get("passed"):
        summary["status"] = "awaiting_hybrid_audit"
    else:
        publish(candidate, args.out)
        summary["status"] = "built_audited" if generated["definitions"]["audit"].get("passed") else "built_awaiting_manual_audit"
    atomic_json(reports / "funnel.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path)
    parser.add_argument("--source-words", type=Path, help="Исходный словарь; по умолчанию снимок words.txt рядом с --manifest")
    frequency_input = parser.add_mutually_exclusive_group()
    frequency_input.add_argument("--corpus", type=Path, help="Локальный текстовый корпус UTF-8")
    frequency_input.add_argument("--frequency-list", type=Path,
                                 help="Локальный список UTF-8: словоформа и число употреблений (FrequencyWords)")
    parser.add_argument("--definitions-source", choices=("clean", "dump", "own"), default="clean",
                        help="По умолчанию очистка игровой базы без определений; dump/own — прежние эксперименты")
    parser.add_argument("--prepare-only", action="store_true", help="Для clean: собрать в кэше без замены data/")
    parser.add_argument("--provider", choices=("file", "anthropic", "openai", "ollama"), default="file")
    parser.add_argument("--model")
    parser.add_argument("--pilot-size", type=int, help="Оценить пробную выборку без публикации данных и проверки полных уровней")
    parser.add_argument("--ollama-url", default="http://127.0.0.1:11434", help="Только локальный сервер Ollama")
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--out", type=Path, default=ROOT / "data")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/manifest.json")
    parser.add_argument("--config", type=Path, default=TOOLS / "selection_config.json")
    parser.add_argument("--categories", type=Path, default=TOOLS / "categories.json")
    parser.add_argument("--golden", type=Path, default=ROOT / "core/src/test/resources/frequent_nouns_golden.txt")
    parser.add_argument("--prompt", type=Path, default=TOOLS / "prompts/definition_v1.txt")
    parser.add_argument("--overrides", type=Path, default=TOOLS / "overrides")
    parser.add_argument("--cache", type=Path, default=TOOLS / "cache")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports")
    parser.add_argument("--generation-date", help="Дата генерации; сохраняется в кэше для воспроизводимости")
    parser.add_argument("--with-wiktionary-glosses", action="store_true")
    parser.add_argument("--verify-audit", type=Path)
    parser.add_argument("--reviewer", default="")
    parser.add_argument("--check-release", action="store_true")
    args = parser.parse_args()
    try:
        if args.generation_date:
            datetime.date.fromisoformat(args.generation_date)
        print(json.dumps(run(args), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, sqlite3.Error) as error:
        parser.exit(2, "Ошибка: %s\n" % error)


if __name__ == "__main__":
    main()
