#!/usr/bin/env python3
"""Офлайн-диагностика отбора заголовков без вывода текстов статей."""
import argparse
import gzip
import json
from collections import defaultdict
from pathlib import Path

import build_dictionary as builder


DEFAULT_WORDS = "дерево учитель женщина телефон каша сахар лев профессор кузнец кузница".split()


def diagnose(path, words):
    reasons = defaultdict(set)
    accepted = set()
    targets = set(words)
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            entry = json.loads(line)
            title = entry.get("word", "")
            word = builder.fold(title)
            if word not in targets:
                continue
            if entry.get("lang_code") != "ru" or entry.get("lang") != "Русский":
                reasons[word].add("не русская статья")
            elif entry.get("pos") != "noun":
                reasons[word].add("не noun")
            elif not builder.valid(word):
                reasons[word].add("не прошёл valid()")
            elif title != title.lower():
                reasons[word].add("title != lower")
            elif set(entry.get("tags", [])) & {"proper-noun", "abbreviation", "diminutive", "form-of"}:
                tags = set(entry.get("tags", [])) & {"proper-noun", "abbreviation", "diminutive", "form-of"}
                reasons[word].add("отброшен по тегам: " + ", ".join(sorted(tags)))
            elif not any(s.get("glosses") and not (set(s.get("tags", [])) & {"form-of", "diminutive", "no-gloss"})
                         for s in entry.get("senses", [])):
                reasons[word].add("нет подходящих glosses (пусто или теги form-of/diminutive/no-gloss)")
            else:
                accepted.add(word)
    return {word: ("проходит отбор" if word in accepted
                   else "; ".join(sorted(reasons[word])) if reasons[word]
                   else "отсутствует в дампе") for word in words}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wiktionary-jsonl", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).resolve().parents[1] / "data/manifest.json")
    parser.add_argument("--words-file", type=Path, help="Список слов по одному в строке")
    args = parser.parse_args()
    expected = json.loads(args.manifest.read_text(encoding="utf-8"))["source"]["sha256_input"]
    actual = builder.sha256_file(args.wiktionary_jsonl)
    if actual != expected:
        parser.error("SHA-256 дампа не совпадает с манифестом")
    words = builder.read_list(args.words_file) if args.words_file else DEFAULT_WORDS
    print("| Слово | Причина в wiktionary_wordlist() |")
    print("| --- | --- |")
    for word, reason in diagnose(args.wiktionary_jsonl, words).items():
        print("| %s | %s |" % (word, reason))


if __name__ == "__main__":
    main()
