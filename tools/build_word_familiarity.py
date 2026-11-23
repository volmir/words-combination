#!/usr/bin/env python3
"""Офлайн-узнаваемость по закреплённому FrequencyWords, без изменения словаря.

Ранг — номер непустой строки всего списка (с единицы), не ранг среди игровых
слов. После нормализации регистра и ё→е берётся первое вхождение словоформы.
Пороги включительны. Скрипт не скачивает входы и не использует эвристики.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import tempfile

from build_dictionary import fold, valid
from prepare_frequency import DEFAULT_CACHE, expected_digest, validate

ROOT = Path(__file__).resolve().parents[1]


def thresholds_from(config):
    thresholds = config["familiarity"]
    frequent = thresholds["frequent_max_rank"]
    ordinary = thresholds["ordinary_max_rank"]
    if (type(frequent) is not int or type(ordinary) is not int
            or not 0 < frequent < ordinary):
        raise ValueError("familiarity: нужны целые 0 < frequent_max_rank < ordinary_max_rank")
    return {"frequent_max_rank": frequent, "ordinary_max_rank": ordinary}


def familiarity_values(words, frequency, thresholds):
    """frequency уже проверен validate; позиции всех словоформ сохраняются."""
    wanted = set(words)
    ranks = {}
    rank = 0
    with frequency.open(encoding="utf-8-sig") as source:
        for line in source:
            if not line.strip():
                continue
            rank += 1
            word = fold(line.split()[0])
            if word in wanted:
                ranks.setdefault(word, rank)
    values = []
    for word in words:
        rank = ranks.get(word)
        values.append(0 if rank is None else
                      3 if rank <= thresholds["frequent_max_rank"] else
                      2 if rank <= thresholds["ordinary_max_rank"] else 1)
    return values


def atomic_bytes(path, body):
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name + "-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as target:
            target.write(body)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def build(words_path, manifest_path, config_path, frequency_path, output_path):
    paths = [path.resolve() for path in
             (words_path, manifest_path, config_path, frequency_path, output_path)]
    if len(set(paths)) != len(paths):
        raise ValueError("Входы, манифест и выход должны быть разными файлами")
    # Включая явно заданный файл: никакого обхода проверки SHA и никакой сети.
    validate(frequency_path, expected_digest(manifest_path))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    source = manifest["selection"]["frequency_source"]
    if source.get("license") != "CC-BY-SA-3.0":
        raise ValueError("Нет лицензии FrequencyWords CC-BY-SA-3.0 в манифесте")
    thresholds = thresholds_from(json.loads(config_path.read_text(encoding="utf-8")))
    words_body = words_path.read_bytes()
    words_sha = hashlib.sha256(words_body).hexdigest()
    words = words_body.decode("utf-8").splitlines()
    if (not words or len(words) != len(set(words))
            or any(word != fold(word) or not valid(word) for word in words)
            or words_sha != manifest["sha256_words_txt"] or len(words) != manifest["words"]):
        raise ValueError("Словарь повреждён или не совпадает с манифестом")
    values = familiarity_values(words, frequency_path, thresholds)
    body = "".join(f"{value}\n" for value in values).encode("ascii")
    metadata = {
        "file": output_path.name,
        "sha256": hashlib.sha256(body).hexdigest(),
        "word_count": len(words),
        "words_sha256": words_sha,
        "thresholds": thresholds,
        "frequency_source": source,
        "license": "CC-BY-SA-3.0",
        "rank_method": "first-normalized-wordform-row-1-based-nonempty",
        "config_file_sha256": hashlib.sha256(config_path.read_bytes()).hexdigest(),
        "generator": "tools/build_word_familiarity.py",
    }
    manifest["word_familiarity"] = metadata
    manifest_body = (json.dumps(manifest, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    atomic_bytes(output_path, body)
    atomic_bytes(manifest_path, manifest_body)
    distribution = Counter(values)
    return {"sha256": metadata["sha256"], "word_count": len(words),
            "distribution": {str(value): distribution[value] for value in range(4)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--words", type=Path, default=ROOT / "data/words.txt")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/manifest.json")
    parser.add_argument("--config", type=Path, default=ROOT / "tools/selection_config.json")
    parser.add_argument("--frequency-list", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--output", type=Path, default=ROOT / "data/word_familiarity.txt")
    args = parser.parse_args()
    try:
        print(json.dumps(build(args.words, args.manifest, args.config, args.frequency_list, args.output),
                         ensure_ascii=False, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"BLOCKED: {error}\n")


if __name__ == "__main__":
    main()
