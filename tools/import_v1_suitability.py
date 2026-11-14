#!/usr/bin/env python3
"""OPT-02: import only individually compatible v1 LEX-04 decisions.

The importer is deliberately narrow.  It never derives a status from v1
aggregate pools, never renames a provider/model/context and never invents a new
cache key.  A v1 decision is reusable only when the complete OPT-01 item key
matches the v2 input (word, role, features and item id) and the destination
context is the original v1 context.  Cache writes use the normal provider key
``sha256([context, item])`` and the normal per-item envelope.

Dry-run is the default.  ``--apply`` is required to add verified records to the
given cache.  An existing cache entry with a different result is a conflict and
aborts the run before any write.
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import assess_word_suitability as assess
import audit_suitability_portability as audit
from game_data_providers import atomic_json, json_digest

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "suitability-v1-import-v1"

COMPATIBLE = "compatible"
CONTEXT_CHANGED = "context_changed"
INPUT_CHANGED = "input_changed"
ROLE_CHANGED = "role_changed"
NO_PAIR = "no_v2_pair"
RULE_DECIDED = "rule_decided"
REASONS = (CONTEXT_CHANGED, INPUT_CHANGED, ROLE_CHANGED, NO_PAIR, RULE_DECIDED)


def load_target_context(args, snapshot):
    """Return the exact destination context; never rename or substitute it."""
    sources = []
    v2_context = getattr(args, "v2_context", None)
    v2_current = getattr(args, "v2_current", None)
    if v2_context:
        sources.append(("--v2-context", audit.load_context(v2_context)))
    if v2_current:
        sources.append(("--v2-current", audit.load_context(v2_current)))
    if len({json_digest(context) for _, context in sources}) > 1:
        raise ValueError("Контекст импорта задан двумя разными файлами")
    if sources:
        source, context = sources[0]
    else:
        source, context = "v1-snapshot", snapshot["context"]
    if set(context) != audit.CONTEXT_FIELDS:
        raise ValueError("Контекст импорта: неверный набор полей")
    for key in ("task", "prompt_sha256", "taxonomy_sha256", "schema_sha256"):
        if context[key] != snapshot["context"][key]:
            raise ValueError("Контекст импорта: другая версия LEX-04")
    if context["provider"] not in ("codex", "opencode"):
        raise ValueError("Контекст импорта: неизвестный провайдер")
    if (not isinstance(context["model"], str) or not context["model"].strip() or
            len(context["model"]) > 200):
        raise ValueError("Контекст импорта: недопустимая модель")
    return context, source


def index_snapshot_items(items):
    """Reject two different v1 items for one (word, role) as ambiguous."""
    by_pair = {}
    for item in items.values():
        pair = (item["word"], item["role"])
        if pair in by_pair:
            raise ValueError("Неоднозначная пара слова и роли в снимке: " +
                             item["word"] + " " + item["role"])
        by_pair[pair] = item
    return by_pair


def v2_roles(items):
    roles = defaultdict(set)
    for item in items:
        roles[item["word"]].add(item["role"])
    return roles


def classify(item, v2_by_key, roles, target_context, source_context):
    """Apply the OPT-01 key: input first, then context, then the rule priority."""
    if (item["word"], item["role"]) not in v2_by_key:
        if roles.get(item["word"]):
            return ROLE_CHANGED, {"v2_roles": sorted(roles[item["word"]])}
        return NO_PAIR, {}
    other = v2_by_key[(item["word"], item["role"])]
    if other["id"] != item["id"] or other["features"] != item["features"]:
        changed = sorted(name for name in set(item["features"]) | set(other["features"])
                         if item["features"].get(name) != other["features"].get(name))
        return INPUT_CHANGED, {"changed_features": changed}
    if target_context != source_context:
        changed = sorted(name for name in audit.CONTEXT_FIELDS
                         if target_context.get(name) != source_context.get(name))
        return CONTEXT_CHANGED, {"context_diff": changed}
    if assess.rule_decision(item) is not None:
        return RULE_DECIDED, {}
    return COMPATIBLE, {}


def check_manifests(args):
    """Optional global provenance: same dump and frequency list."""
    v1_manifest = getattr(args, "v1_manifest", None)
    v2_manifest = getattr(args, "v2_manifest", None)
    if not v1_manifest and not v2_manifest:
        return None
    if not (v1_manifest and v2_manifest):
        raise ValueError("Для проверки происхождения нужны оба манифеста")
    first = audit.manifest_hashes(v1_manifest)
    second = audit.manifest_hashes(v2_manifest)
    if not first["dump"] or not first["frequency"]:
        raise ValueError("Манифест v1: нет SHA дампа или частот")
    if not second["dump"] or not second["frequency"]:
        raise ValueError("Манифест v2: нет SHA дампа или частот")
    if first["dump"] != second["dump"]:
        raise ValueError("Манифесты: разные исходные дампы")
    if first["frequency"] != second["frequency"]:
        raise ValueError("Манифесты: разные частотные списки")
    return {
        "v1": first,
        "v2": second,
        "dump_equal": True,
        "frequency_equal": True,
        "v1_words_match_manifest": (audit.file_sha256(args.v1_words) == first.get("words")
                                    if getattr(args, "v1_words", None) else None),
    }


def build_report(args):
    snapshot, items, results = audit.load_snapshot(args.v1_snapshot)
    if getattr(args, "v1_input", None):
        if json_digest(audit.read_json(args.v1_input)) != snapshot["input_sha256"]:
            raise ValueError("Вход v1 не совпадает с input_sha256 снимка")
    by_pair = index_snapshot_items(items)
    v2_document, v2_items, v2_by_key = audit.load_v2_input(args.v2_input)
    target_context, context_source = load_target_context(args, snapshot)
    manifests = check_manifests(args)
    provider = assess.SuitabilityProvider(args.cache, target_context["model"],
                                          provider=target_context["provider"])
    if provider.context != target_context:
        raise ValueError("Контекст провайдера не совпадает с заданным контекстом")
    roles = v2_roles(v2_items)

    records, rejected = [], Counter()
    rejected_examples, keys = [], {}
    for item in sorted(by_pair.values(), key=lambda value: (value["word"], value["role"])):
        result = results[item["id"]]
        reason, details = classify(item, v2_by_key, roles, target_context, snapshot["context"])
        if reason != COMPATIBLE:
            rejected[reason] += 1
            if len(rejected_examples) < 20:
                rejected_examples.append(dict(
                    {"id": item["id"], "word": item["word"], "role": item["role"],
                     "status": result["status"], "reason": reason}, **details))
            continue
        key = provider.key(item)
        if key in keys:
            raise ValueError("Коллизия ключа кэша: " + key)
        keys[key] = item["id"]
        path = provider.path(item)
        state = "pending"
        if path.exists():
            previous = provider.read(item)
            if previous != result:
                raise ValueError("Конфликт кэша: " + item["word"] + " " + item["role"])
            state = "already_present"
        body = {"context": target_context, "item": item, "result": result}
        records.append({
            "id": item["id"], "word": item["word"], "role": item["role"],
            "status": result["status"], "cache_key": key,
            "envelope_sha256": json_digest(body), "state": state,
        })

    written = 0
    apply_writes = bool(getattr(args, "apply", False))
    if apply_writes:
        for row in records:
            if row["state"] != "pending":
                continue
            provider.store(items[row["id"]], results[row["id"]])
            row["state"] = "written"
            written += 1

    already_present = sum(1 for row in records if row["state"] == "already_present")
    pending_write = len(records) - already_present - written
    compatible_suitable = sum(1 for row in records if row["status"] == "suitable")
    by_role = Counter(row["role"] for row in records)
    no_op = not records
    status = ("no-op" if no_op else
              "applied" if apply_writes and written else
              "unchanged" if apply_writes else "dry-run")
    report = {
        "schema": SCHEMA,
        "mode": "apply" if apply_writes else "dry-run",
        "status": status,
        "no_op": no_op,
        "no_op_reason": ("Нет подтверждённых индивидуальных совпадений" if no_op else None),
        "context": target_context,
        "context_source": context_source,
        "context_matches_v1": target_context == snapshot["context"],
        "v1": {
            "snapshot": str(args.v1_snapshot),
            "snapshot_file_sha256": audit.file_sha256(args.v1_snapshot),
            "snapshot_sha256": snapshot["sha256"],
            "input_sha256": snapshot["input_sha256"],
            "schema": snapshot["schema"],
            "policy": snapshot["policy"],
            "context": snapshot["context"],
            "items": len(items),
            "results": len(results),
        },
        "v2": {
            "input": str(args.v2_input),
            "input_file_sha256": audit.file_sha256(args.v2_input),
            "input_sha256": json_digest(v2_document),
            "schema": v2_document.get("schema"),
            "items": len(v2_items),
        },
        "manifests": manifests,
        "counts": {
            "v1_items": len(items),
            "v2_items": len(v2_items),
            "compatible": len(records),
            "compatible_suitable": compatible_suitable,
            "by_role": dict(sorted(by_role.items())),
            "already_present": already_present,
            "written": written,
            "pending_write": pending_write,
            "rejected": sum(rejected.values()),
        },
        "reasons": {reason: rejected.get(reason, 0) for reason in REASONS},
        "rejected_examples": rejected_examples,
        "savings": {
            "new_ai_decisions_avoided": len(records),
            "suitable_decisions_avoided": compatible_suitable,
            "batches_avoided_at_20": (len(records) + 19) // 20,
        },
        "records": records,
    }
    return report


def run(args):
    report = build_report(args)
    atomic_json(args.output, report)
    return report


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-snapshot", type=Path,
                        default=ROOT / "build/reports/suitability/suitability_decisions.json")
    parser.add_argument("--v1-input", type=Path,
                        help="необязательный вход v1 для проверки input_sha256 снимка")
    parser.add_argument("--v2-input", type=Path, required=True)
    parser.add_argument("--v2-context", type=Path,
                        help="JSON с context или word_suitability.json активного прогона")
    parser.add_argument("--v2-current", type=Path,
                        help="word_suitability.json активного прогона (алиас --v2-context)")
    parser.add_argument("--cache", type=Path, default=ROOT / "tools/cache/word_suitability",
                        help="кэш LEX-04; ключи всегда sha256([context, item])")
    parser.add_argument("--v1-manifest", type=Path)
    parser.add_argument("--v2-manifest", type=Path)
    parser.add_argument("--v1-words", type=Path,
                        help="необязательный словарь v1 для сверки манифеста")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "build/reports/finishing/OPT-02.json")
    parser.add_argument("--apply", action="store_true",
                        help="записать проверенные записи; без флага только dry-run")
    return parser


def main():
    args = build_parser().parse_args()
    try:
        report = run(args)
        print(json.dumps({
            "schema": report["schema"], "mode": report["mode"], "status": report["status"],
            "no_op": report["no_op"], "context_matches_v1": report["context_matches_v1"],
            "counts": report["counts"], "reasons": report["reasons"],
            "savings": report["savings"], "output": str(args.output),
        }, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print("Ошибка импорта пригодности v1: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
