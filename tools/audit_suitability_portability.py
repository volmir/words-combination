#!/usr/bin/env python3
"""OPT-01: read-only audit of v1 LEX-04 decisions against the v2 suitability input.

The audit never writes a cache and never calls a model.  A v1 record is
``exact-compatible`` only when the complete LEX-04 key matches: schema,
assessment version, policy, context (including provider and model), and the
item (word, role, features, id).  Word/role matches with changed features or a
different context are reported as ``needs-recheck``; absent pairs are
``unavailable``.  The same records are also counted against the pinned v1
context, which is the only no-new-AI replay that does not mix providers.
"""
import argparse
import gzip
import hashlib
import json
from collections import Counter
from pathlib import Path

import assess_word_suitability as assess
from game_data_providers import atomic_json, json_digest, prompt_hash
from word_categories import normalize

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT_SCHEMA = "word-suitability-decisions-v1"
SNAPSHOT_FIELDS = {"schema", "context", "taxonomy", "input_sha256", "items", "results",
                   "complete", "policy", "sha256"}
CONTEXT_FIELDS = {"task", "prompt_sha256", "taxonomy_sha256", "schema_sha256", "provider", "model"}
EXACT, NEEDS, UNAVAILABLE = "exact-compatible", "needs-recheck", "unavailable"


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_snapshot(path):
    """Validate the portable snapshot envelope and every item/result envelope."""
    return load_snapshot_value(read_json(path))


def load_snapshot_value(document):
    if set(document) != SNAPSHOT_FIELDS:
        raise ValueError("Снимок пригодности: неверный набор полей")
    body = {key: value for key, value in document.items() if key != "sha256"}
    if document["sha256"] != json_digest(body):
        raise ValueError("Снимок пригодности: SHA-256 не совпадает")
    if document["schema"] != SNAPSHOT_SCHEMA:
        raise ValueError("Снимок пригодности: неверная схема")
    if document["policy"] != {"assessment_version": assess.VERSION}:
        raise ValueError("Снимок пригодности: несовместимая политика")
    if document["taxonomy"] != assess.POLICY:
        raise ValueError("Снимок пригодности: несовместимая таксономия меток")
    if set(document["context"]) != CONTEXT_FIELDS:
        raise ValueError("Снимок пригодности: неверный контекст")
    semantic = {"task": assess.VERSION, "prompt_sha256": prompt_hash(assess.PROMPT),
                "taxonomy_sha256": json_digest(assess.POLICY),
                "schema_sha256": json_digest(assess.result_schema(assess.POLICY))}
    if any(document["context"].get(key) != value for key, value in semantic.items()):
        raise ValueError("Снимок пригодности: другая версия LEX-04")
    items, results = document["items"], document["results"]
    if not isinstance(items, list) or not isinstance(results, list):
        raise ValueError("Снимок пригодности: items/results должны быть списками")
    by_id, result_by_id = {}, {}
    for item in items:
        assess.validate_item(item, assess.POLICY)
        if item["id"] in by_id:
            raise ValueError("Снимок пригодности: повтор item id")
        by_id[item["id"]] = item
    for result in results:
        if not isinstance(result, dict) or result.get("id") not in by_id:
            raise ValueError("Снимок пригодности: результат без item")
        if result["id"] in result_by_id:
            raise ValueError("Снимок пригодности: повтор result id")
        assess.validate_results({"results": [result]}, [by_id[result["id"]]], assess.POLICY)
        result_by_id[result["id"]] = result
    if document["complete"] is not True or len(results) != len(items):
        raise ValueError("Снимок пригодности: нужен полный снимок")
    return document, by_id, result_by_id


def load_v2_input(path):
    return load_v2_input_value(read_json(path))


def load_v2_input_value(document):
    items = assess.input_items(document)
    by_key = {}
    for item in items:
        key = (item["word"], item["role"])
        if key in by_key:
            raise ValueError("Вход v2: неоднозначная пара слова и роли")
        by_key[key] = item
    return document, items, by_key


def load_context(path):
    document = read_json(path)
    context = document.get("context", document)
    if not isinstance(context, dict) or set(context) != CONTEXT_FIELDS:
        raise ValueError("Контекст LEX-04: неверный набор полей")
    return context


def load_all_assessments(path, snapshot):
    """Validate the full rule+AI result and its link to the portable snapshot."""
    document = read_json(path)
    if document.get("schema") != assess.VERSION or not isinstance(document.get("assessments"), list):
        raise ValueError("word_suitability.json: неверная схема")
    if document.get("snapshot_sha256") != snapshot["sha256"]:
        raise ValueError("word_suitability.json: снимок не совпадает")
    if document.get("input_sha256") != snapshot["input_sha256"]:
        raise ValueError("word_suitability.json: вход не совпадает")
    if document.get("context") != snapshot["context"]:
        raise ValueError("word_suitability.json: контекст не совпадает")
    by_id, bad_sha = {}, 0
    for row in document["assessments"]:
        item = {key: row[key] for key in ("id", "word", "role", "features")}
        if row["id"] in by_id:
            raise ValueError("word_suitability.json: повтор id")
        assess.validate_item(item, assess.POLICY)
        assess.validate_results({"results": [row["assessment"]]}, [item], assess.POLICY)
        if row["assessment_sha256"] != json_digest([document["context"], item, row["assessment"]]):
            bad_sha += 1
        if row["assessment_source"] not in ("rules", "ai", "pending"):
            raise ValueError("word_suitability.json: неизвестный assessment_source")
        by_id[row["id"]] = row
    return document, by_id, bad_sha


def check_rule_semantics(assessments):
    """Every v1 rules record must still be reproduced by the current rule function."""
    mismatches, ai_disputed = [], []
    for row in assessments["assessments"]:
        item = {key: row[key] for key in ("id", "word", "role", "features")}
        ruled = assess.rule_decision(item)
        if row["assessment_source"] == "rules":
            if ruled is None or ruled["status"] != row["assessment"]["status"]:
                mismatches.append({"word": row["word"], "role": row["role"],
                                   "v1": row["assessment"]["status"],
                                   "current": None if ruled is None else ruled["status"]})
        elif row["assessment_source"] == "ai" and ruled is not None:
            ai_disputed.append({"word": row["word"], "role": row["role"],
                                "current": ruled["status"]})
    return mismatches, ai_disputed


def check_cache(cache, snapshot, items, results):
    """Verify every v1 per-item cache file and its key against the snapshot."""
    files = sorted(Path(cache).glob("*.json"))
    problems, seen = [], set()
    for path in files:
        envelope = read_json(path)
        if not isinstance(envelope, dict):
            problems.append({"file": path.name, "reason": "not an object"})
            continue
        body = {key: value for key, value in envelope.items() if key != "sha256"}
        if set(body) != {"context", "item", "result"} or envelope.get("sha256") != json_digest(body):
            problems.append({"file": path.name, "reason": "bad envelope sha"})
            continue
        item = body["item"]
        if (body["context"] != snapshot["context"] or item.get("id") not in items or
                items[item["id"]] != item or results.get(item["id"]) != body["result"]):
            problems.append({"file": path.name, "reason": "cache/snapshot mismatch"})
        if path.stem != json_digest([body["context"], item]):
            problems.append({"file": path.name, "reason": "bad cache key"})
        seen.add(item.get("id"))
    if seen != set(items):
        problems.append({"reason": "cache ids do not cover snapshot",
                         "missing": len(set(items) - seen), "extra": len(seen - set(items))})
    return {"files": len(files), "ids": len(seen), "valid": not problems, "problems": problems[:20]}


def manifest_hashes(path):
    document = read_json(path)
    return {
        "dump": document.get("source", {}).get("sha256_input"),
        "frequency": document.get("selection", {}).get("frequency_source", {}).get("sha256"),
        "words": document.get("definitions", {}).get("dictionary_words_sha256"),
    }


def profile_link(profile, word_suitability, words, manifest):
    """Check the v1 GEN-01 profile cache inputs and payload against files on disk."""
    with gzip.open(profile, "rt", encoding="utf-8") as stream:
        document = json.load(stream)
    inputs = document.get("inputs", {})
    manifest_document = read_json(manifest)
    expected = {
        "suitability": file_sha256(word_suitability),
        "words": file_sha256(words),
        "manifest": file_sha256(manifest),
        "database": manifest_document.get("definitions", {}).get("sha256"),
        "dump": manifest_document.get("source", {}).get("sha256_input"),
        "frequency": manifest_hashes(manifest)["frequency"],
    }
    mismatches = {key: {"expected": value, "actual": inputs.get(key)}
                  for key, value in expected.items() if inputs.get(key) != value}
    word_document = read_json(word_suitability)
    payload = document.get("payload", {})
    return {"path": str(profile), "schema": document.get("schema"),
            "payload_assessments": len(payload.get("assessments", [])),
            "payload_sha256": document.get("payload_sha256"),
            "payload_equal_word_suitability": payload.get("assessments") ==
                                              word_document.get("assessments"),
            "mismatches": mismatches}


def classify(snapshot, items, results, v2_by_key, active_context):
    records = []
    for item_id, item in items.items():
        result = results[item_id]
        key = (item["word"], item["role"])
        v2 = v2_by_key.get(key)
        if v2 is None:
            active_class = pinned_class = UNAVAILABLE
            reason = "no_v2_pair"
            changed = []
        elif v2["id"] != item["id"] or v2["features"] != item["features"]:
            active_class = pinned_class = NEEDS
            reason = "input_changed"
            changed = sorted(name for name in set(item["features"]) | set(v2["features"])
                             if item["features"].get(name) != v2["features"].get(name))
        else:
            changed = []
            reason = "context_changed"
            pinned_class = EXACT
            active_class = EXACT if active_context == snapshot["context"] else NEEDS
        records.append({
            "id": item_id, "word": item["word"], "role": item["role"],
            "status": result["status"], "reason": result["reason"],
            "features": item["features"], "v2_features": None if v2 is None else v2["features"],
            "active_class": active_class, "pinned_class": pinned_class,
            "classification_reason": reason, "changed_features": changed,
            "context_diff": [] if active_context == snapshot["context"] else
            sorted(name for name in CONTEXT_FIELDS if active_context.get(name) != snapshot["context"].get(name)),
        })
    return records


def counts(records, field):
    counter = Counter((row[field], row["role"], row["status"]) for row in records)
    return [{"class": key[0], "role": key[1], "status": key[2], "count": value}
            for key, value in sorted(counter.items())]


def summarize(records, snapshot, v2_document, v2_items, active_context, all_assessments=None,
              current=None):
    active = counts(records, "active_class")
    pinned = counts(records, "pinned_class")
    by_reason = Counter(row["classification_reason"] for row in records)
    total = len(records)
    active_exact = sum(row["count"] for row in active if row["class"] == EXACT)
    pinned_exact = sum(row["count"] for row in pinned if row["class"] == EXACT)
    active_suitable = sum(row["count"] for row in active
                          if row["class"] == EXACT and row["status"] == "suitable")
    pinned_suitable = sum(row["count"] for row in pinned
                          if row["class"] == EXACT and row["status"] == "suitable")
    result = {
        "records": total,
        "active_context": active_context,
        "v1_context": snapshot["context"],
        "context_diff": sorted(name for name in CONTEXT_FIELDS
                               if active_context.get(name) != snapshot["context"].get(name)),
        "active": {"exact": active_exact, "suitable": active_suitable,
                   "needs_recheck": total - active_exact - by_reason["no_v2_pair"],
                   "unavailable": by_reason["no_v2_pair"], "by_class_role_status": active},
        "pinned_v1": {"exact": pinned_exact, "suitable": pinned_suitable,
                      "needs_recheck": total - pinned_exact - by_reason["no_v2_pair"],
                      "unavailable": by_reason["no_v2_pair"], "by_class_role_status": pinned},
        "reasons": dict(sorted(by_reason.items())),
        "v2_input": {"items": len(v2_items), "schema": v2_document.get("schema"),
                     "sha256_file": None, "sha256_json": json_digest(v2_document)},
    }
    if all_assessments is not None:
        all_by_id = {row["id"]: row for row in all_assessments["assessments"]}
        v2_exact = sum(1 for item in v2_items if item["id"] in all_by_id)
        v2_ai = sum(1 for item in v2_items if item["id"] in all_by_id and
                    all_by_id[item["id"]]["assessment_source"] == "ai")
        v2_rules = sum(1 for item in v2_items if item["id"] in all_by_id and
                       all_by_id[item["id"]]["assessment_source"] == "rules")
        result["v1_all"] = {
            "assessments": len(all_assessments["assessments"]),
            "ai": sum(1 for row in all_assessments["assessments"] if row["assessment_source"] == "ai"),
            "rules": sum(1 for row in all_assessments["assessments"] if row["assessment_source"] == "rules"),
            "v2_exact_item": v2_exact, "v2_ai_exact": v2_ai, "v2_rules_exact": v2_rules,
            "v2_missing_exact": len(v2_items) - v2_exact,
        }
    if current is not None:
        current_ai = {row["id"]: row for row in current["assessments"]
                      if row["assessment_source"] == "ai"}
        v1_by_id = {row["id"]: row for row in (all_assessments or {}).get("assessments", [])}
        common = set(current_ai) & set(v1_by_id)
        conflicts = [{"word": v1_by_id[item]["word"], "role": v1_by_id[item]["role"],
                      "v1": v1_by_id[item]["assessment"]["status"],
                      "current": current_ai[item]["assessment"]["status"]}
                     for item in sorted(common)
                     if v1_by_id[item]["assessment"]["status"] != current_ai[item]["assessment"]["status"]]
        result["current_v2"] = {
            "context": current.get("context"), "ai": len(current_ai),
            "overlap_v1": len(common), "conflicts": len(conflicts),
            "conflict_examples": conflicts[:10],
        }
    return result


def examples(records, limit=10):
    """Deterministic coverage of statuses, roles and classes for the report."""
    order = [
        ("pinned_class", EXACT, "target", "suitable", None),
        ("pinned_class", EXACT, "source", "suitable", None),
        ("pinned_class", EXACT, "target", "insufficient_data", None),
        ("pinned_class", EXACT, "target", "unsuitable", None),
        ("pinned_class", EXACT, "source", "insufficient_data", None),
        ("pinned_class", EXACT, "source", "unsuitable", None),
        ("active_class", NEEDS, "target", "suitable", "input_changed"),
        ("active_class", NEEDS, "source", "suitable", "input_changed"),
        ("active_class", UNAVAILABLE, "target", "suitable", None),
        ("active_class", UNAVAILABLE, "source", "suitable", None),
    ]
    selected, seen = [], set()
    for field, klass, role, status, reason in order:
        for row in sorted(records, key=lambda value: (value["word"], value["role"])):
            if (row["id"] in seen or row[field] != klass or row["role"] != role or
                    row["status"] != status or (reason and row["classification_reason"] != reason)):
                continue
            selected.append({key: row[key] for key in (
                "id", "word", "role", "status", "reason", "features", "v2_features",
                "active_class", "pinned_class", "classification_reason", "changed_features",
                "context_diff")})
            seen.add(row["id"])
            break
        if len(selected) >= limit:
            break
    return selected


def build_report(args):
    if not (args.v2_context or args.v2_current):
        raise ValueError("Нужен --v2-context или --v2-current")
    snapshot, items, results = load_snapshot(args.v1_snapshot)
    v2_document, v2_items, v2_by_key = load_v2_input(args.v2_input)
    active_context = load_context(args.v2_context or args.v2_current)
    if args.v1_input:
        v1_input = read_json(args.v1_input)
        if json_digest(v1_input) != snapshot["input_sha256"]:
            raise ValueError("Вход v1 не совпадает с input_sha256 снимка")
    all_assessments = None
    rule_mismatches, ai_disputed, bad_assessment_sha = [], [], 0
    if args.v1_word_suitability:
        all_assessments, _, bad_assessment_sha = load_all_assessments(
            args.v1_word_suitability, snapshot)
        ai_rows = {row["id"]: row for row in all_assessments["assessments"]
                   if row["assessment_source"] == "ai"}
        if set(ai_rows) != set(items):
            raise ValueError("word_suitability.json: AI-оценки не совпадают со снимком")
        for item_id, result in results.items():
            if ai_rows[item_id]["assessment"] != result:
                raise ValueError("word_suitability.json: AI-результат не совпадает со снимком")
        rule_mismatches, ai_disputed = check_rule_semantics(all_assessments)
    cache = check_cache(args.v1_cache, snapshot, items, results) if args.v1_cache else None
    records = classify(snapshot, items, results, v2_by_key, active_context)
    selected = None
    if getattr(args, "only", None):
        word, role = normalize(args.only[0]), args.only[1]
        selected = next((row for row in records if row["word"] == word and row["role"] == role), None)
        if selected is None:
            selected = {"word": word, "role": role, "v1_decision": None,
                        "v2_item": v2_by_key.get((word, role)),
                        "active_class": UNAVAILABLE, "pinned_class": UNAVAILABLE,
                        "classification_reason": "no_v1_decision"}
    report = summarize(records, snapshot, v2_document, v2_items, active_context,
                       all_assessments, read_json(args.v2_current) if args.v2_current else None)
    report.update({
        "schema": "suitability-portability-audit-v1",
        "v1": {
            "snapshot": str(args.v1_snapshot), "snapshot_sha256": snapshot["sha256"],
            "input_sha256": snapshot["input_sha256"], "schema": snapshot["schema"],
            "complete": snapshot["complete"], "items": len(items), "results": len(results),
            "context": snapshot["context"], "policy": snapshot["policy"],
        },
        "v2": {
            "input": str(args.v2_input), "input_sha256": json_digest(v2_document),
            "input_file_sha256": file_sha256(args.v2_input), "words": None,
            "words_sha256": None, "word_count": None,
        },
        "provenance": {
            "v1_cache": cache, "rule_mismatches": rule_mismatches[:20],
            "rule_mismatch_count": len(rule_mismatches), "ai_now_rule_decided": ai_disputed[:20],
            "ai_now_rule_decided_count": len(ai_disputed),
            "bad_assessment_sha": bad_assessment_sha,
            "v1_profile": None, "manifests": None, "normalization": None,
        },
        "examples": examples(records),
        "selected": selected,
        "v2_only": [],
    })
    v1_word_set = ({normalize(line) for line in
                    Path(args.v1_words).read_text(encoding="utf-8").split()}
                   if args.v1_words else set())
    v2_words, v2_raw = [], []
    if args.v2_words:
        v2_raw = Path(args.v2_words).read_text(encoding="utf-8").split()
        v2_words = [normalize(line) for line in v2_raw]
        targets = {item["word"] for item in v2_items if item["role"] == "target"}
        report["v2"].update(words=str(args.v2_words), words_sha256=file_sha256(args.v2_words),
                            word_count=len(v2_words), input_targets=len(targets),
                            words_match_targets=set(v2_words) == targets)
        all_by_id = {row["id"]: row for row in all_assessments["assessments"]} if all_assessments else {}
        report["v2_only"] = [
            {"word": item["word"], "role": item["role"], "features": item["features"]}
            for item in v2_items
            if item["id"] not in all_by_id and item["word"] not in v1_word_set
        ]
    if args.v1_manifest and args.v2_manifest:
        first, second = manifest_hashes(args.v1_manifest), manifest_hashes(args.v2_manifest)
        report["provenance"]["manifests"] = {
            "v1": first, "v2": second,
            "dump_equal": first["dump"] == second["dump"],
            "frequency_equal": first["frequency"] == second["frequency"],
            "v1_words_match_manifest": (not args.v1_words or
                                         file_sha256(args.v1_words) == first["words"]),
            "v2_words_match_manifest": (not args.v2_words or
                                         file_sha256(args.v2_words) == second["words"]),
        }
    if args.v1_profile:
        report["provenance"]["v1_profile"] = profile_link(
            args.v1_profile, args.v1_word_suitability, args.v1_words, args.v1_manifest)
    v1_input = read_json(args.v1_input) if args.v1_input else None
    v1_raw = (Path(args.v1_words).read_text(encoding="utf-8").split()
              if args.v1_words else [])
    report["provenance"]["normalization"] = {
        "v1_dictionary_words_noncanonical": sum(1 for word in v1_raw if word != normalize(word)),
        "v1_input_words_noncanonical": sum(1 for item in (v1_input or {}).get("candidates", [])
                                           if item["word"] != normalize(item["word"])),
        "v2_dictionary_words_noncanonical": sum(1 for word in v2_raw if word != normalize(word)),
        "v2_input_words_noncanonical": sum(1 for item in v2_items
                                           if item["word"] != normalize(item["word"])),
    }
    return report


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--v1-snapshot", type=Path,
                        default=ROOT / "build/reports/suitability/suitability_decisions.json")
    parser.add_argument("--v1-word-suitability", type=Path,
                        default=ROOT / "build/reports/suitability/word_suitability.json")
    parser.add_argument("--v1-input", type=Path,
                        default=ROOT / "build/reports/level_profiles/suitability_input.json")
    parser.add_argument("--v1-cache", type=Path, default=ROOT / "tools/cache/word_suitability")
    parser.add_argument("--v1-profile", type=Path, default=ROOT / (
        "tools/cache/level_profiles/4f6d9905ff36c3d8d512d66e18069dd5cbd1f3939aa99c8a2f53dd5e029737bf.json.gz"))
    parser.add_argument("--v1-words", type=Path, default=ROOT / "data/words.txt")
    parser.add_argument("--v1-manifest", type=Path, default=ROOT / "data/manifest.json")
    parser.add_argument("--v2-input", type=Path, required=True)
    parser.add_argument("--v2-current", type=Path)
    parser.add_argument("--v2-context", type=Path,
                        help="JSON с context или word_suitability.json; иначе нужен --v2-current")
    parser.add_argument("--v2-words", type=Path)
    parser.add_argument("--v2-manifest", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/finishing/OPT-01.json")
    parser.add_argument("--only", nargs=2, metavar=("WORD", "ROLE"),
                        help="показать одну пару word role")
    parser.add_argument("--print-full", action="store_true")
    return parser


def main():
    args = build_parser().parse_args()
    report = build_report(args)
    atomic_json(args.output, report)
    summary = {
        "schema": report["schema"],
        "v1_snapshot_sha256": report["v1"]["snapshot_sha256"],
        "v2_input_sha256": report["v2"]["input_sha256"],
        "active_context_exact": report["active"]["exact"],
        "active_context_suitable": report["active"]["suitable"],
        "pinned_v1_exact": report["pinned_v1"]["exact"],
        "pinned_v1_suitable": report["pinned_v1"]["suitable"],
        "needs_recheck": report["active"]["needs_recheck"],
        "unavailable": report["active"]["unavailable"],
        "selected": report.get("selected"),
        "output": str(args.output),
    }
    print(json.dumps(report if args.print_full else summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
