#!/usr/bin/env python3
"""Частоты структурных меток русских статей; тексты толкований не выводятся."""
import argparse
import gzip
import json
from collections import Counter, defaultdict
from pathlib import Path

import build_dictionary as dictionary


def inspect(path):
    counts = defaultdict(Counter)
    opener = gzip.open if str(path).endswith(".gz") else open
    entries = nouns = 0
    with opener(path, "rt", encoding="utf-8") as source:
        for line in source:
            if not line.strip():
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != "ru" or entry.get("lang") != "Русский":
                continue
            entries += 1
            counts["pos"][entry.get("pos", "")] += 1
            if entry.get("pos") != "noun":
                continue
            nouns += 1
            for field in ("tags", "raw_tags", "topics"):
                counts["entry." + field].update(entry.get(field, []))
                for sense in entry.get("senses", []):
                    counts["sense." + field].update(sense.get(field, []))
    return {"russian_entries": entries, "noun_entries": nouns,
            "fields": {field: dict(sorted(counter.items(), key=lambda item: (-item[1], item[0])))
                       for field, counter in sorted(counts.items())}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).resolve().parents[1] / "data/manifest.json")
    parser.add_argument("--out", type=Path, default=Path("tools/cache/tag_inventory.json"))
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    digest = dictionary.sha256_file(args.dump)
    if digest != manifest["source"]["sha256_input"]:
        parser.error("SHA-256 дампа не совпадает с манифестом")
    inventory = inspect(args.dump)
    inventory["source_sha256"] = digest
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(inventory, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
