#!/usr/bin/env python3
"""Офлайн-генерация справочного Bloom-фильтра в отдельном каталоге кандидата."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import random
import struct

LETTERS = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
MAGIC = b"RUKNOWN\0"
HEADER = struct.Struct("<8sIIIII")
MAX_BITS = 128 * 1024 * 1024
SEED = 20261111


def normalize(raw):
    return "".join(c for c in raw.lower().replace("ё", "е") if c in LETTERS)


def positions(word, bits, hashes):
    digest = hashlib.sha256(word.encode("utf-8")).digest()
    return [value % bits for value in struct.unpack("<8I", digest)[:hashes]]


def build(words, fpr=0.01):
    words = {normalize(w) for w in words} - {""}
    if not words or not 0 < fpr < 1:
        raise ValueError("Нужен непустой словарь и 0 < FPR < 1")
    bits = math.ceil(-len(words) * math.log(fpr) / math.log(2) ** 2 / 8) * 8
    hashes = max(1, min(8, round(bits / len(words) * math.log(2))))
    if not 8 <= bits <= MAX_BITS:
        raise ValueError("Фильтр превышает лимит формата")
    payload = bytearray(bits // 8)
    for word in sorted(words):
        for index in positions(word, bits, hashes):
            payload[index // 8] |= 1 << (index % 8)
    data = HEADER.pack(MAGIC, 1, bits, hashes, len(words), 1) + payload
    return data + hashlib.sha256(data).digest()


def read(data):
    if data is None or not HEADER.size + 32 <= len(data) <= HEADER.size + MAX_BITS // 8 + 32:
        raise ValueError("Неверная длина фильтра")
    magic, version, bits, hashes, count, algorithm = HEADER.unpack_from(data)
    if (magic != MAGIC or version != 1 or algorithm != 1 or not 8 <= bits <= MAX_BITS or bits % 8 or
            not 1 <= hashes <= 8 or not 1 <= count <= 0x7fffffff or len(data) != HEADER.size + bits // 8 + 32 or
            hashlib.sha256(data[:-32]).digest() != data[-32:]):
        raise ValueError("Повреждённый или неподдерживаемый фильтр")
    return bits, hashes, data[HEADER.size:-32]


def might_contain(parsed, raw):
    bits, hashes, payload = parsed
    word = normalize(raw)
    return bool(word) and all(payload[i // 8] & (1 << (i % 8)) for i in positions(word, bits, hashes))


def audit(data, source, game_words, samples=100000, seed=SEED):
    parsed = read(data)
    source = {normalize(w) for w in source} - {""}
    game = {normalize(w) for w in game_words} - {""}
    missing = sorted(game - source)
    if missing:
        raise ValueError("Игровые слова отсутствуют в полном источнике: " + repr(missing[:10]))
    for word in source | game:
        if not might_contain(parsed, word):
            raise ValueError("Ложноотрицательное слово: " + word)
    if samples <= 0:
        raise ValueError("Число контрольных отрицательных должно быть положительным")
    rng = random.Random(seed)
    negatives = set()
    ordered = []
    while len(ordered) < samples:
        word = "".join(rng.choice(LETTERS) for _ in range(rng.randint(3, 16)))
        if word not in source and word not in negatives:
            negatives.add(word)
            ordered.append(word)
    positives = sum(might_contain(parsed, w) for w in ordered)
    return {"source_words": len(source), "game_words": len(game), "false_negatives": 0,
            "negative_seed": seed, "negative_samples": samples, "false_positives": positives,
            "measured_fpr": positives / samples,
            "negatives_sha256": hashlib.sha256(("\n".join(ordered) + "\n").encode()).hexdigest()}


def generate(source_path, manifest_path, game_path, candidate):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    # Same canonical source digest as clean_dictionary, before Bloom normalization.
    if source_path.suffix == ".json":
        document = json.loads(source_path.read_text(encoding="utf-8"))
        words = set(document["words"])
    else:
        words = set(source_path.read_text(encoding="utf-8").splitlines())
    digest = hashlib.sha256(("\n".join(sorted(words)) + "\n").encode()).hexdigest()
    selection = manifest["selection"]
    if digest != selection["input_words_sha256"] or len(words) != selection["input_words"]:
        raise ValueError("Полный источник не совпадает с selection.input_words_sha256/input_words")
    data = build(words)
    report = audit(data, words, game_path.read_text(encoding="utf-8").splitlines())
    bits, hashes, _ = read(data)
    report.update(format="known-words-bloom-v1", byte_order="little-endian", hash_algorithm="sha256-u32le-v1",
                  bit_count=bits, hash_count=hashes, bytes=len(data), target_fpr=0.01,
                  expected_fpr=(1 - math.exp(-hashes * report["source_words"] / bits)) ** hashes,
                  input_words_sha256=digest, sha256=hashlib.sha256(data).hexdigest(),
                  game_words_sha256=hashlib.sha256(game_path.read_bytes()).hexdigest())
    candidate.mkdir(parents=True, exist_ok=True)
    (candidate / "known_words.bloom").write_bytes(data)
    (candidate / "known_words_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-words", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=Path("data/manifest.json"))
    parser.add_argument("--game-words", type=Path, default=Path("data/words.txt"))
    parser.add_argument("--candidate", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if any(args.candidate.resolve().is_relative_to(root / p) for p in ("data", "app", "orchestrator")):
        parser.error("Используйте отдельный каталог кандидата вне data/app/orchestrator")
    try:
        print(json.dumps(generate(args.source_words, args.manifest, args.game_words, args.candidate), ensure_ascii=False))
    except (OSError, ValueError, KeyError) as error:
        parser.exit(2, "Ошибка: %s\n" % error)


if __name__ == "__main__":
    main()
