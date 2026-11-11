#!/usr/bin/env python3
"""Определения из проверенного локального дампа; игровой набор не меняется, сети нет."""
import argparse
import datetime
import gzip
import json
import re
import shutil
import sqlite3
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import quote

import build_dictionary as dictionary
import clean_dictionary as clean
import word_categories as thematic
from build_game_data import build_database, publish, write_csv
from game_data_providers import atomic_json, json_digest
from game_data_quality import normalize_form

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
WORD_ARTIFACTS = ("words.txt", "levels.json", "golden_search.json", "word_sources.jsonl.gz")
CHECKSUMS = ("sha256_words_txt", "sha256_levels_json", "sha256_golden_search", "sha256_word_sources")
REFERENCE = re.compile(r'^(?:[а-я.-]+[.,]?\s+){0,6}то же,?\s+что\s+[«"]?([а-яё]+)[»"]?(?:\s*\[\d+\])?[.!]?$', re.I)


class PlainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style"}:
            self.hidden += 1
        if not self.hidden and tag in {"br", "p", "div", "li"}:
            self.parts.append(" ")

    def handle_endtag(self, tag):
        if tag in {"script", "style"}:
            self.hidden = max(0, self.hidden - 1)
        elif not self.hidden:
            self.parts.append(" ")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def plain_text(text):
    parser = PlainText()
    parser.feed(text.split("◆", 1)[0])
    parser.close()
    return " ".join(re.sub(r"<[^>]*>", " ", "".join(parser.parts)).replace("<", " ").replace(">", " ").split())


def load_settings(path):
    settings = json.loads(Path(path).read_text(encoding="utf-8"))
    for key in ("schema_version", "min_chars", "max_chars", "max_meanings", "reference_depth"):
        if type(settings[key]) is not int or settings[key] <= 0:
            raise ValueError("Некорректная настройка определений: " + key)
    if settings["schema_version"] < 8 or settings["min_chars"] > settings["max_chars"] or settings["reference_depth"] > 5:
        raise ValueError("Некорректная схема или границы определений")
    datetime.date.fromisoformat(settings["generation_date"])
    return settings


def extract_articles(path, cleanup):
    articles = {}
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != "ru" or entry.get("lang") != "Русский" or entry.get("pos") != "noun":
                continue
            title = entry.get("word", "")
            word = dictionary.fold(title)
            if title != title.lower() or not dictionary.valid(word):
                continue
            record = articles.setdefault(word, {"display": title, "forms": set(), "related": set(), "senses": []})
            record["forms"].add(word)
            for form in entry.get("forms", []):
                value = normalize_form(form.get("form", ""))
                if re.fullmatch(r"[а-я]+", value) and len(value) >= 2:
                    record["forms"].add(value)
            for related in entry.get("related", []):
                if "Ближайшее родство" not in related.get("raw_tags", []):
                    continue
                value = normalize_form(related.get("word", ""))
                if re.fullmatch(r"[а-я]+", value) and len(value) >= 2:
                    record["related"].add(value)
            common, _ = clean.field_labels(entry, cleanup)
            url = "https://ru.wiktionary.org/wiki/" + quote(title, safe="")
            for sense in entry.get("senses", []):
                reasons, _ = clean.field_labels(sense, cleanup)
                for gloss in sense.get("glosses", []):
                    text = plain_text(gloss)
                    found, _, _ = clean.gloss_labels(text, cleanup)
                    flags = sorted((common | reasons | found) - {"abbreviation"})
                    reference = REFERENCE.fullmatch(normalize_form(text))
                    # Entry-wide themes are safe only for a single sense. An entry
                    # with multiple meanings must not lend its zoological tag to a tool.
                    themes = {field: sense.get(field, entry.get(field, []) if len(entry.get("senses", [])) == 1 else [])
                              for field in ("categories", "topics", "raw_tags", "tags")}
                    record["senses"].append({"text": text, "source": url, "flags": flags, **themes,
                                              "reference": reference.group(1) if reference else None})
    # Обратная связь не добавляет синонимы: только явно помеченное ближайшее родство.
    reverse = [(word, relative) for word, record in articles.items() for relative in record["related"] if relative in articles]
    for word, relative in reverse:
        articles[relative]["related"].add(word)
    for record in articles.values():
        record["forms"] = sorted(record["forms"])
        record["related"] = sorted(record["related"])
        record["senses"] = sorted(enumerate(record["senses"]), key=lambda item: (bool(item[1]["flags"]), len(item[1]["flags"]), item[0]))
        record["senses"] = [sense for _, sense in record["senses"]]
    return articles


def select_definitions(words, articles, settings, removed, taxonomy=None, deduplicate=True):
    taxonomy = taxonomy if taxonomy is not None else thematic.load_taxonomy()
    rows, failures, resolutions = [], {}, []

    def resolve(sense, trail, depth):
        target = sense["reference"]
        if not target:
            if settings["min_chars"] <= len(sense["text"]) <= settings["max_chars"] and re.search(r"[а-яё]", sense["text"], re.I):
                return sense
            return None
        if depth >= settings["reference_depth"] or target in trail or target not in articles:
            return None
        for candidate in articles[target]["senses"]:
            resolved = resolve(candidate, trail | {target}, depth + 1)
            if resolved:
                return resolved
        return None

    def category(sense, trail, depth):
        # A reference to a homonym is not evidence for the first meaning alone.
        target = sense["reference"]
        if not target:
            return thematic.classify_sense(sense, taxonomy)
        if depth >= settings["reference_depth"] or target in trail or target not in articles:
            return thematic.word_category([])
        return thematic.word_category([category(candidate, trail | {target}, depth + 1)
                                       for candidate in articles[target]["senses"]])

    for word in sorted(words):
        if word in removed:
            failures[word] = ["complaint"]
            continue
        record = articles.get(word)
        if record is None:
            failures[word] = ["no_article"]
            continue
        seen, count = set(), 0
        for sense in record["senses"]:
            resolved = resolve(sense, {word}, 0)
            if resolved is None or (deduplicate and resolved["text"].casefold() in seen):
                continue
            seen.add(resolved["text"].casefold())
            rows.append({"word": word, "display": record["display"], "text": resolved["text"], "source": resolved["source"],
                         "origin": "wiktionary", **category(sense, {word}, 0), "guessable": 0})
            if resolved is not sense:
                resolutions.append({"word": word, "target": sense["reference"], "source": resolved["source"]})
            count += 1
            if count >= settings["max_meanings"]:
                break
        if not count:
            failures[word] = ["reference_cycle_or_missing"] if any(s["reference"] for s in record["senses"]) else ["no_usable_gloss"]
    return rows, failures, resolutions


def validate_database(path, words, settings):
    with sqlite3.connect(path) as db:
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or db.execute("PRAGMA user_version").fetchone()[0] < 8:
            raise ValueError("Повреждённая или устаревшая база определений")
        if {row[0] for row in db.execute("SELECT word FROM word_metadata")} != words:
            raise ValueError("Метаданные не соответствуют игровому словарю")
        taxonomy = thematic.load_taxonomy()
        for category, source in db.execute("SELECT category,category_source FROM word_metadata"):
            if category not in taxonomy or not source or (category == "other") != source.startswith("unknown:"):
                raise ValueError("Некорректная категория или её происхождение")
        for word, text, source, origin, example in db.execute("SELECT word,text,source,origin,example FROM definitions"):
            if (word not in words or origin != "wiktionary" or not source.startswith("https://ru.wiktionary.org/wiki/") or example or
                    not settings["min_chars"] <= len(text) <= settings["max_chars"] or re.search(r"[<>\r\n]", text)):
                raise ValueError("Некорректное определение: " + word)
        for forms, related in db.execute("SELECT forms,related FROM word_metadata"):
            for values in (json.loads(forms), json.loads(related)):
                if not isinstance(values, list) or any(not isinstance(word, str) or not re.fullmatch(r"[а-я]+", word) for word in values):
                    raise ValueError("Некорректные формы или однокоренные слова")


def cached_articles(path, dump_sha, cleanup, cache_directory):
    extraction_key = json_digest([dump_sha, cleanup, dictionary.sha256_file(Path(__file__)),
                                  dictionary.sha256_file(TOOLS / "clean_dictionary.py"), dictionary.sha256_file(TOOLS / "game_data_quality.py")])
    cache = cache_directory / ("articles-" + extraction_key + ".json")
    if cache.exists():
        articles = json.loads(cache.read_text(encoding="utf-8"))
    else:
        articles = extract_articles(path, cleanup)
        atomic_json(cache, articles)
    return articles, extraction_key


def run(args):
    if args.dump is None or not args.dump.is_file():
        raise ValueError("Нужен существующий локальный --dump; загрузок нет")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    dump_sha = dictionary.sha256_file(args.dump)
    if dump_sha != manifest["source"]["sha256_input"] or manifest["source"]["dump_date"] != "2026-11-01":
        raise ValueError("SHA-256 или дата дампа не совпадает с манифестом")
    source = args.manifest.parent
    for name, checksum in zip(WORD_ARTIFACTS, CHECKSUMS):
        if dictionary.sha256_file(source / name) != manifest[checksum]:
            raise ValueError("Нарушен SHA-256 игрового файла: " + name)
    words = set(dictionary.read_list(source / "words.txt"))
    if len(words) != manifest["words"]:
        raise ValueError("Число слов не совпадает с манифестом")
    settings = load_settings(args.config)
    cleanup = clean.load_config(args.selection_config)
    removed = set(dictionary.read_list(args.remove_definitions))
    articles, extraction_key = cached_articles(args.dump, dump_sha, cleanup, args.cache)
    taxonomy = thematic.load_taxonomy()
    rows, failures, resolutions = select_definitions(words, articles, settings, removed, taxonomy)
    # Check every usable meaning, including those beyond the display limit. A
    # hidden homonym must not silently become a confident T6 target.
    all_rows, _, _ = select_definitions(words, articles, dict(settings, max_meanings=10**9), removed,
                                       taxonomy, deduplicate=False)
    by_category = {}
    for row in all_rows:
        by_category.setdefault(row["word"], []).append(row)
    word_metadata = {word: {**thematic.word_category(by_category.get(word, [])),
                           "forms": articles.get(word, {}).get("forms", [word]),
                           "related": articles.get(word, {}).get("related", [])} for word in sorted(words)}
    index = thematic.category_index(word_metadata, taxonomy)
    # Closed, text-free features for the independent category classifier. Articles,
    # definitions, raw labels and source URLs never enter model requests.
    index["classification_features"] = {
        word: {"reason": value["category_source"].removeprefix("unknown:"),
               "sense_count": len(by_category.get(word, [])),
               "candidate_categories": sorted({row["category"] for row in by_category.get(word, [])
                                                if row["category"] != "other"})}
        for word, value in word_metadata.items() if value["category"] == "other"}
    masker_sha = dictionary.sha256_file(ROOT / "core/src/main/kotlin/com/example/sostavslova/core/DefinitionHint.kt")
    content = json_digest([extraction_key, manifest["sha256_words_txt"], settings, sorted(removed), masker_sha,
                           dictionary.sha256_file(TOOLS / "build_game_data.py"),
                           dictionary.sha256_file(TOOLS / "word_categories.py"), taxonomy])
    candidate = args.cache / "builds" / content
    candidate.mkdir(parents=True, exist_ok=True)
    previous_version, previous_content = settings["schema_version"] - 1, None
    installed = args.out / "definitions.db"
    if installed.exists():
        with sqlite3.connect(installed) as db:
            previous_version = db.execute("PRAGMA user_version").fetchone()[0]
            previous_content = dict(db.execute("SELECT key,value FROM build_metadata")).get("content_sha256")
    state_file = candidate / "state.json"
    state = json.loads(state_file.read_text()) if state_file.exists() else {"version": settings["schema_version"]}
    state["version"] = max(state["version"], settings["schema_version"], previous_version + int(previous_content != content))
    atomic_json(state_file, state)
    database = candidate / "definitions.db"
    rebuild = not database.exists()
    if not rebuild:
        with sqlite3.connect(database) as db:
            rebuild = db.execute("PRAGMA user_version").fetchone()[0] != state["version"]
    metadata = {"content_sha256": content, "rights_policy": "wiktionary-provisional-v1", "source_sha256": dump_sha,
                "dictionary_words_sha256": manifest["sha256_words_txt"], "masker_version": settings["masker_version"],
                "masker_sha256": masker_sha, "generator": "build_local_definitions.py"}
    if rebuild:
        build_database(database, rows, word_metadata, metadata, state["version"])
    validate_database(database, words, settings)
    defined = {row["word"] for row in rows}
    block = {"rights_policy": "wiktionary-provisional-v1", "sha256": dictionary.sha256_file(database),
             "rights_review": {"passed": False, "status": "unverified-provisional", "replacement_required": True},
             "generator": {"provider": "local-dump", "model": None, "date": settings["generation_date"],
                           "config_sha256": dictionary.sha256_file(args.config), "source_sha256": dump_sha},
             "counts": {"own": 0, "wiktionary": len(rows)}, "coverage": {"words": len(defined), "total": len(words)},
             "audit": {"passed": False}, "user_version": state["version"], "guessability_evaluated": False,
             "dictionary_words_sha256": manifest["sha256_words_txt"],
             "hint_processing": {"version": settings["masker_version"], "sha256": masker_sha,
                                 "strategy": "runtime-core-mask-forms-and-cognates", "replacement": "***"}}
    generated = dict(manifest, definitions=block)
    for name in WORD_ARTIFACTS:
        shutil.copyfile(source / name, candidate / name)
    atomic_json(candidate / "manifest.json", generated)
    summary = {"words": len(words), "words_with_definitions": len(defined), "words_without_definitions": len(words - defined),
               "definitions": len(rows), "coverage_fraction": len(defined) / max(1, len(words)), "resolved_references": len(resolutions),
               "failure_reasons": dict(Counter(reason for reasons in failures.values() for reason in reasons)),
               "database": str(database), "database_bytes": database.stat().st_size, "user_version": state["version"],
               "published": not args.prepare_only, "ai_used": False, "audit_passed": False, "dictionary_changed": False}
    args.reports.mkdir(parents=True, exist_ok=True)
    atomic_json(args.reports / "category_index.json", dict(index, database_sha256=block["sha256"],
                                                          words_sha256=manifest["sha256_words_txt"]))
    write_csv(args.reports / "missing_definitions.csv", ["word", "reason"],
              [{"word": word, "reason": ";".join(reasons)} for word, reasons in sorted(failures.items())])
    write_csv(args.reports / "resolved_references.csv", ["word", "target", "source"], resolutions)
    by_word = {}
    for row in rows:
        by_word.setdefault(row["word"], []).append(row["text"])
    with (args.reports / "hint_inputs.jsonl").open("w", encoding="utf-8") as output:
        output.write(json.dumps({"schema": "definition-hint-audit-v1", "database_sha256": block["sha256"],
                                 "words_sha256": manifest["sha256_words_txt"], "masker_version": settings["masker_version"]}) + "\n")
        for word in sorted(words):
            record = articles.get(word, {})
            output.write(json.dumps({"word": word, "forms": record.get("forms", [word]), "related": record.get("related", []),
                                     "definitions": by_word.get(word, [])}, ensure_ascii=False) + "\n")
    if not args.prepare_only:
        publish(candidate, args.out)
    atomic_json(args.reports / "summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/manifest.json")
    parser.add_argument("--config", type=Path, default=TOOLS / "definition_import_config.json")
    parser.add_argument("--selection-config", type=Path, default=TOOLS / "selection_config.json")
    parser.add_argument("--cache", type=Path, default=TOOLS / "cache/local_definitions")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports/local_definitions")
    parser.add_argument("--out", type=Path, default=ROOT / "data")
    parser.add_argument("--remove-definitions", type=Path, default=TOOLS / "overrides/remove_definitions.txt")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args), ensure_ascii=False, indent=2))
    except (ValueError, OSError, KeyError, sqlite3.Error) as error:
        parser.exit(2, "Ошибка: %s\n" % error)


if __name__ == "__main__":
    main()
