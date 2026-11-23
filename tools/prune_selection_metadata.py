#!/usr/bin/env python3
"""Remove a retired reference field from generated selection metadata only.

The field name is explicit CLI input so this one-time migration does not retain
knowledge of the deleted mechanism. Dictionaries and their checksums are untouched.
Accepts manifests and generated summary reports; never pass historical evidence.
"""
import argparse
import json
from pathlib import Path

from game_data_providers import atomic_json


def prune(document, field):
    """Prune only diagnostic fields, preserving every other piece of metadata."""
    if not isinstance(document, dict):
        raise ValueError("Expected a JSON object")
    if "selection" in document:
        references = document["selection"].get("reference_checks", {})
        references.pop(field, None)
    else:
        document.get("reference_checks", {}).pop(field, None)
        document.pop(field, None)
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--field", required=True, help="Retired diagnostic field")
    parser.add_argument("files", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.files:
        before = path.read_bytes()
        document = json.loads(before)
        updated = prune(json.loads(before), args.field)
        if updated != document:
            atomic_json(path, updated)
            print(path)


if __name__ == "__main__":
    main()
