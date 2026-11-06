#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Офлайн-сборка игрового словаря из raw JSONL русского Викисловаря.

Источник: Kaikki/Wiktextract, CC BY-SA 4.0. SQL не используется.
По умолчанию включаются только заголовки существительных и их происхождение.
Определения и отбор для игры собирает tools/build_game_data.py.
Скрипт ничего не скачивает. См. docs/DEFINITIONS_POLICY.md.
"""
import argparse
import datetime
import gzip
import hashlib
import json
import os
import tempfile
from collections import Counter
from urllib.parse import quote

SOURCE_PAGE = "https://kaikki.org/ruwiktionary/rawdata.html"
SOURCE_DOWNLOAD = "https://kaikki.org/ruwiktionary/raw-wiktextract-data.jsonl.gz"
LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
CHANGES = "Отбор слов и значений, нормализация ё→е, извлечение обычного текста; примеры исключены."

ALPHABET = "абвгдежзийклмнопрстуфхцчшщъыьэюя"  # 32 буквы, ё свёрнута в е
MIN_LEN, MAX_LEN = 3, 30

# Уровни из оригинального проекта (models/Level.php):
# (слово, нужно отгадать для прохождения, нужно слов для звезды «слова», буква, сколько слов на букву)
# ИСПРАВЛЕНО: у «реввоенсовет» буква задания была латинской 'c', теперь кириллическая 'с'.
LEVELS = [
    ("рекуперация", 8, 14, "п", 6), ("опровержение", 8, 14, "р", 3), ("хлопкопункт", 10, 20, "к", 8),
    ("аспирантура", 10, 15, "а", 7), ("ратификация", 8, 14, "ф", 7), ("чемпионство", 12, 24, "о", 6),
    ("симфонизация", 10, 16, "с", 6), ("субъективизм", 10, 15, "б", 6), ("намеривание", 7, 14, "в", 5),
    ("окаменелость", 14, 30, "м", 8), ("демонстрация", 18, 45, "н", 7), ("артикуляция", 9, 20, "р", 5),
    ("фисгармоника", 15, 40, "г", 10), ("перетекание", 10, 15, "к", 6), ("сурдоперевод", 10, 14, "о", 4),
    ("колядование", 10, 20, "в", 10), ("селитроварня", 15, 40, "т", 15), ("субординация", 12, 25, "б", 8),
    ("реввоенсовет", 8, 14, "с", 5), ("правоведение", 9, 14, "о", 5), ("одноколейка", 10, 14, "д", 5),
    ("аккумулятор", 12, 20, "к", 10), ("передвигание", 12, 18, "г", 5), ("переводчица", 10, 15, "ч", 6),
    ("питекантроп", 20, 40, "т", 10), ("вегетарианец", 12, 24, "а", 6), ("регулировщик", 10, 16, "л", 6),
    ("видеотехника", 12, 24, "в", 8), ("мелодичность", 14, 26, "с", 10), ("пудлинговка", 16, 30, "п", 10),
]
GOLDEN_WORDS = ["портал", "акробат", "каменистость", "демонстрация", "питекантроп", "ааа"]

def fold(word):
    return word.strip().lower().replace("ё", "е")


def valid(word):
    return MIN_LEN <= len(word) <= MAX_LEN and all(ch in ALPHABET for ch in word)


def read_list(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [fold(l) for l in f if l.strip() and not l.lstrip().startswith("#")]


def can_make(src_counts, word):
    return all(src_counts.get(ch, 0) >= n for ch, n in Counter(word).items())


def search(words, source):
    sc = Counter(source)
    return [w for w in words if w != source and can_make(sc, w)]


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def wiktionary_wordlist(path):
    """Независимая выборка заголовков, не пересечение с прежним SQL-словарём."""
    sources, stats = {}, Counter()
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            entry = json.loads(line)
            stats["entries"] += 1
            if entry.get("lang_code") != "ru" or entry.get("lang") != "Русский" or entry.get("pos") != "noun":
                continue
            title = entry.get("word", "")
            word = fold(title)
            if not valid(word) or title != title.lower():
                continue
            flags = set(entry.get("tags", []))
            if flags & {"proper-noun", "abbreviation", "diminutive", "form-of"}:
                continue
            if not any(s.get("glosses") and not (set(s.get("tags", [])) & {"form-of", "diminutive", "no-gloss"})
                       for s in entry.get("senses", [])):
                continue
            record = {"title": title, "line": line_number,
                      "source": "https://ru.wiktionary.org/wiki/" + quote(title, safe="")}
            if record not in sources.setdefault(word, []):
                sources[word].append(record)
    stats["words"] = len(sources)
    return sources, stats


def write_word_sources(path, sources):
    # Время заголовка gzip фиксировано: одинаковый вход даёт одинаковый SHA-256.
    with open(path, "wb") as output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as compressed:
            for word in sorted(sources):
                row = {"word": word, "sources": sources[word]}
                compressed.write((json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n").encode())


def write_game_data(directory, sources, dump_date, input_sha, definitions=None, levels_spec=None):
    words = sorted(sources)
    levels, errors = [], []
    for index, (source, answers, needed, letter, count) in enumerate(LEVELS if levels_spec is None else levels_spec, 1):
        available = search(words, source)
        first = sum(w.startswith(letter) for w in available)
        if source not in sources or len(available) < needed or first < count or letter not in ALPHABET:
            errors.append("%s: слово=%s, всего=%d/%d, на %s=%d/%d" %
                          (source, source in sources, len(available), needed, letter, first, count))
        levels.append({"index": index, "word": source, "answers": answers, "words": needed,
                       "letter": letter, "letterCount": count, "available": len(available)})
    if errors:
        raise ValueError("Уровни недостижимы; данные не заменены:\n" + "\n".join(errors))
    os.makedirs(directory, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dictionary-", dir=directory) as staged:
        def write_json(name, value):
            with open(os.path.join(staged, name), "w", encoding="utf-8") as out:
                json.dump(value, out, ensure_ascii=False, indent=2)
                out.write("\n")
        with open(os.path.join(staged, "words.txt"), "w", encoding="utf-8", newline="\n") as out:
            out.write("\n".join(words) + "\n")
        write_json("levels.json", levels)
        golden = {}
        for word in GOLDEN_WORDS:
            result = search(words, word)
            golden[word] = {"count": len(result), "words": sorted(result, key=lambda w: (len(w), w))}
        write_json("golden_search.json", golden)
        write_word_sources(os.path.join(staged, "word_sources.jsonl.gz"), sources)
        manifest = {
            "schema": "dictionary-provenance-v1", "words": len(words),
            "sha256_words_txt": sha256_file(os.path.join(staged, "words.txt")),
            "sha256_word_sources": sha256_file(os.path.join(staged, "word_sources.jsonl.gz")),
            "sha256_levels_json": sha256_file(os.path.join(staged, "levels.json")),
            "sha256_golden_search": sha256_file(os.path.join(staged, "golden_search.json")),
            "alphabet": ALPHABET, "minLen": MIN_LEN, "maxLen": MAX_LEN,
            "source": {"project": "Русский Викисловарь", "extractor": "Kaikki.org / Wiktextract",
                       "url": SOURCE_PAGE, "license": "CC-BY-SA-4.0", "license_url": LICENSE_URL,
                       "dump_date": dump_date, "sha256_input": input_sha,
                       "selection": "Russian lowercase noun headwords; no SQL selection or free-text glosses"},
            "definitions": definitions,
            "levels_source": {"url": "https://github.com/volmir/words-api/blob/master/models/Level.php",
                              "license": "MIT", "changes": "Cyrillic с in level 19; level 20 uses правоведение"},
        }
        write_json("manifest.json", manifest)
        for name in os.listdir(staged):
            os.replace(os.path.join(staged, name), os.path.join(directory, name))
    return levels


def quarantine_definitions(path):
    if not os.path.isfile(path):
        return None
    directory = os.path.join(os.path.dirname(os.path.abspath(path)), "quarantine")
    os.makedirs(directory, exist_ok=True)
    destination = os.path.join(directory, "defs-unreviewed-%s.db" % sha256_file(path))
    os.replace(path, destination)
    return destination


def main():
    ap = argparse.ArgumentParser(description="Сборка из согласованного открытого источника; загрузок нет")
    ap.add_argument("--wiktionary-jsonl", "--definitions-jsonl", dest="jsonl", required=True)
    ap.add_argument("--dump-date", required=True)
    ap.add_argument("--out", default="data")
    ap.add_argument("--overrides", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "overrides"))
    ap.add_argument("--sql", help="Не поддерживается: прежний SQL-источник исключён")
    ap.add_argument("--with-definitions", action="store_true")
    ap.add_argument("--definitions-only", action="store_true")
    ap.add_argument("--db-out", default="build/definitions.db")
    ap.add_argument("--audit-out", default="build/reports/licensing")
    a = ap.parse_args()
    if a.sql:
        ap.error("SQL-источник исключён: используйте --wiktionary-jsonl")
    if not os.path.isfile(a.jsonl):
        ap.error("не найден локальный JSONL")
    try:
        datetime.datetime.strptime(a.dump_date, "%Y-%m-%d")
    except ValueError:
        ap.error("--dump-date должен иметь формат YYYY-MM-DD")
    if a.with_definitions or a.definitions_only:
        ap.error("Определения собираются tools/build_game_data.py; используйте новый конвейер")
    input_sha = sha256_file(a.jsonl)
    sources, stats = wiktionary_wordlist(a.jsonl)
    additions = set(read_list(os.path.join(a.overrides, "add.txt")))
    if additions - sources.keys():
        ap.error("Нельзя добавлять слова без происхождения: " + ", ".join(sorted(additions - sources.keys())))
    for word in read_list(os.path.join(a.overrides, "remove.txt")):
        sources.pop(word, None)
    levels = write_game_data(a.out, sources, a.dump_date, input_sha)
    quarantine = quarantine_definitions(a.db_out)
    os.makedirs(a.audit_out, exist_ok=True)
    summary = {"stats": dict(stats), "accepted_words": len(sources), "definitions_included": False,
               "quarantine": quarantine, "source_sha256": input_sha,
               "levels": [{"word": level["word"], "available": level["available"]} for level in levels]}
    with open(os.path.join(a.audit_out, "summary.json"), "w", encoding="utf-8") as output:
        json.dump(summary, output, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
