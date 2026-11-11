#!/usr/bin/env python3
"""Automatic residual thematic classification; offline snapshots, opt-in Codex CLI."""
import argparse
import copy
import json
import os
import shlex
import sys
from collections import Counter
from pathlib import Path

import word_categories as thematic
from game_data_providers import (CATEGORY_REASONS, CategoryProvider, atomic_json, json_digest,
                                 validate_category_item, validate_category_results)

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT_SCHEMA = "word-category-decisions-v1"


def classification_items(index, taxonomy):
    if (not isinstance(index, dict) or index.get("schema") != "word-category-index-v1" or
            not isinstance(index.get("word_metadata"), dict)):
        raise ValueError("Нужен category_index.json от LEX-02")
    items = []
    for word, value in sorted(index["word_metadata"].items()):
        if (not isinstance(value, dict) or value.get("category") not in taxonomy or
                not isinstance(value.get("category_source"), str) or
                (value["category"] == "other") != value["category_source"].startswith("unknown:")):
            raise ValueError("Некорректные локальные метаданные: " + word)
        if not thematic.category_target_allowed(word, value) or value["category"] != "other":
            continue
        reason = value["category_source"].removeprefix("unknown:")
        features = index.get("classification_features", {}).get(word, {
            "reason": reason if reason in CATEGORY_REASONS else "no_thematic_evidence",
            "sense_count": 0, "candidate_categories": []})
        item = {"word": word, "features": features, "id": json_digest([word, features])}
        validate_category_item(item, taxonomy)
        items.append(item)
    return items


def export_snapshot(path, provider, index, items, answers, threshold=0.8):
    body = {"schema": SNAPSHOT_SCHEMA, "context": provider.context, "taxonomy": provider.taxonomy,
            "input_sha256": json_digest(index), "items": items,
            "results": [answers[item["id"]] for item in items if item["id"] in answers],
            "complete": len(answers) == len(items), "policy": {"min_confidence": threshold}}
    snapshot = dict(body, sha256=json_digest(body))
    atomic_json(path, snapshot)
    return snapshot


def import_snapshot(snapshot, provider, index, items):
    if not isinstance(snapshot, dict):
        raise ValueError("Некорректный снимок решений")
    fields = {"schema", "context", "taxonomy", "input_sha256", "items", "results", "complete", "policy", "sha256"}
    body = {key: value for key, value in snapshot.items() if key != "sha256"}
    if (set(snapshot) != fields or snapshot["schema"] != SNAPSHOT_SCHEMA or
            snapshot["sha256"] != json_digest(body) or snapshot["context"] != provider.context or
            snapshot["taxonomy"] != provider.taxonomy or snapshot["input_sha256"] != json_digest(index) or
            snapshot["items"] != items or type(snapshot["complete"]) is not bool):
        raise ValueError("Снимок несовместим: версия, вход, таксономия, промпт, провайдер, модель или SHA-256")
    results = snapshot["results"]
    if not isinstance(results, list) or any(not isinstance(result, dict) for result in results):
        raise ValueError("Некорректный список решений снимка")
    identities = {result.get("id") for result in results if isinstance(result.get("id"), str)}
    selected = [item for item in items if item["id"] in identities]
    validate_category_results({"results": results}, selected, provider.taxonomy)
    if snapshot["complete"] != (len(results) == len(items)):
        raise ValueError("Некорректный признак полноты снимка")
    # Validate all results and conflicts before importing any of them.
    by_id = {result["id"]: result for result in results}
    for item in selected:
        previous = provider.read(item)
        if previous is not None and previous != by_id[item["id"]]:
            raise ValueError("Снимок конфликтует с кэшем")
    for item in selected:
        provider.store(item, by_id[item["id"]])


def apply_decisions(index, taxonomy, items, answers, threshold, context):
    metadata = copy.deepcopy(index["word_metadata"])
    for item in items:
        result = answers.get(item["id"])
        if result is None:
            continue
        value = metadata[item["word"]]
        # Eligibility is checked again at application, independent of model output.
        if not thematic.category_target_allowed(item["word"], value) or value["category"] != "other":
            continue
        value["category_confidence"] = result["confidence"]
        value["category_decision_sha256"] = json_digest([context, item, result])
        if result["category"] == "other":
            value.update(category="other", category_source="unknown:ai_other")
        elif result["confidence"] < threshold:
            value.update(category="other", category_source="unknown:ai_low_confidence")
        else:
            value.update(category=result["category"], category_source="ai:" + context["provider"] + ":" + context["model"])
    output = thematic.category_index(metadata, taxonomy)
    for key in ("database_sha256", "words_sha256"):
        if key in index:
            output[key] = index[key]
    # Source database identity does not imply its categories have been rewritten.
    output["classification"] = {"context": context, "input_sha256": json_digest(index),
                                "min_confidence": threshold, "database_categories_updated": False}
    return output


def source_group(value):
    source = value["category_source"]
    if source.startswith("ai:") or source.startswith("unknown:ai_"):
        return "ai" if value["category"] != "other" else "ai_unresolved"
    return "unknown" if source.startswith("unknown:") else "rules"


def independent_accuracy(path, metadata, taxonomy):
    if path is None:
        return None
    labels = json.loads(path.read_text(encoding="utf-8"))
    if (set(labels) != {"schema", "independent", "annotator", "labels"} or
            labels["schema"] != "word-category-gold-v1" or labels["independent"] is not True or
            not isinstance(labels["annotator"], str) or not labels["annotator"].strip() or
            not isinstance(labels["labels"], dict) or not labels["labels"]):
        raise ValueError("Нужна независимая разметка с annotator и непустыми labels")
    for word, category in labels["labels"].items():
        if word not in metadata or not isinstance(category, str) or category not in taxonomy:
            raise ValueError("Некорректное слово или enum независимой разметки")
    correct = sum(metadata[word]["category"] == category for word, category in labels["labels"].items())
    ai_labels = {word: category for word, category in labels["labels"].items()
                 if source_group(metadata[word]) in {"ai", "ai_unresolved"}}
    ai_correct = sum(metadata[word]["category"] == category for word, category in ai_labels.items())
    return {"size": len(labels["labels"]), "correct": correct, "fraction": correct / len(labels["labels"]),
            "ai_size": len(ai_labels), "ai_correct": ai_correct,
            "ai_fraction": ai_correct / len(ai_labels) if ai_labels else None,
            "labels_sha256": json_digest(labels)}


def write_report(path, output, taxonomy, snapshot, pending, accuracy=None, resume=None):
    metadata = output["word_metadata"]
    total = len(metadata)
    groups = Counter(source_group(value) for value in metadata.values())
    covered_groups = Counter(source_group(metadata[word]) for words in output["categories"].values() for word in words)
    covered = sum(len(words) for words in output["categories"].values())
    lines = ["# Категории слов", "", f"Всего слов: {total}; покрыто для T6: {covered} ({covered / max(1, total):.2%}).",
             "Ориентиры: покрытие ≥35%; точность ≥90% только по независимой разметке.",
             f"Снимок: `{snapshot['schema']}`, SHA-256 `{snapshot['sha256']}`.",
             f"Незавершённых решений ИИ: {pending}. Порог уверенности: {output['classification']['min_confidence']}.",
             "", "| Источник | Слов | Доля от всех слов | Покрыто T6 | Доля покрытия от всех слов |",
             "|---|---:|---:|---:|---:|"]
    for source in ("rules", "ai", "ai_unresolved", "unknown"):
        lines.append(f"| {source} | {groups[source]} | {groups[source] / max(1, total):.2%} | "
                     f"{covered_groups[source]} | {covered_groups[source] / max(1, total):.2%} |")
    lines += ["", "rules — локальные правила; ai — принятые решения модели; ai_unresolved — other/низкая уверенность."]
    if accuracy is None:
        lines += ["", "Точность не измерена: независимая разметка отсутствует (размер 0)."]
    else:
        lines += ["", f"Точность: {accuracy['fraction']:.2%}, независимая выборка n={accuracy['size']}; "
                  f"SHA-256 разметки `{accuracy['labels_sha256']}`."]
        lines += [f"Точность ИИ: {accuracy['ai_fraction']:.2%}, n={accuracy['ai_size']}." if accuracy["ai_size"] else
                  "Точность ИИ не измерена: в независимой выборке нет решений ИИ (n=0)."]
    lines += ["", "## Категории (не более 20 примеров)", "", "| Категория | Число | Примеры |", "|---|---:|---|"]
    counts = Counter(value["category"] for value in metadata.values())
    for category in sorted(taxonomy):
        examples = [word for word, value in sorted(metadata.items()) if value["category"] == category][:20]
        lines.append(f"| {category} — {taxonomy[category]} | {counts[category]} | {', '.join(examples)} |")
    large = [category for category, words in output["categories"].items() if len(words) >= 40]
    lines += ["", "Категории ≥40 слов (пригодные для T6): " + (", ".join(large) or "нет") + ".",
              "", "## Диагностическая выборка (до 300)", "", "| Слово | Категория | Источник |", "|---|---|---|"]
    # Stable hash sampling across all sources, independent of iteration order/time.
    sample = sorted(metadata, key=lambda word: json_digest(["category-diagnostic-v1", word]))[:300]
    for word in sample:
        value = metadata[word]
        lines.append(f"| {word} | {value['category']} | {source_group(value)} |")
    if resume:
        lines += ["", "Возобновление (выполненные слова не отправляются повторно):", "", "```sh", resume, "```"]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {"total": total, "covered": covered, "coverage_fraction": covered / max(1, total),
            "sources": dict(groups), "coverage_by_source": dict(covered_groups),
            "categories_at_least_40": large, "accuracy": accuracy,
            "pending": pending, "snapshot_sha256": snapshot["sha256"]}


def resume_command(args, model):
    command = ["python3", str(ROOT / "tools/classify_word_categories.py"), "--input", str(args.input.resolve()),
               "--taxonomy", str(args.taxonomy.resolve()), "--cache", str(args.cache.resolve()),
               "--reports", str(args.reports.resolve()), "--model", model,
               "--batch-size", str(args.batch_size), "--timeout", str(args.timeout), "--retries", str(args.retries),
               "--min-confidence", str(args.min_confidence), "--allow-network"]
    command += ["--snapshot", str((args.reports / "category_decisions.json").resolve())]
    if args.gold:
        command += ["--gold", str(args.gold.resolve())]
    return shlex.join(command)


def run(args):
    taxonomy = thematic.load_taxonomy(args.taxonomy)
    index = json.loads(args.input.read_text(encoding="utf-8"))
    items = classification_items(index, taxonomy)
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8")) if args.snapshot else None
    if args.snapshot and (not isinstance(snapshot, dict) or not isinstance(snapshot.get("context"), dict) or
                          not isinstance(snapshot.get("policy"), dict)):
        raise ValueError("Некорректный снимок решений")
    policy = snapshot["policy"] if snapshot is not None else {}
    if args.min_confidence is None:
        args.min_confidence = policy.get("min_confidence", 0.8)
    if type(args.min_confidence) not in (int, float) or not 0 <= args.min_confidence <= 1:
        raise ValueError("min-confidence должен быть от 0 до 1")
    if snapshot is not None and policy != {"min_confidence": args.min_confidence}:
        raise ValueError("Снимок несовместим: изменён порог уверенности")
    model = args.model or (snapshot["context"].get("model") if snapshot is not None else None)
    provider = CategoryProvider(args.cache, model, taxonomy, allow_network=args.allow_network,
                                batch_size=args.batch_size, timeout=args.timeout, retries=args.retries)
    if snapshot is not None:
        import_snapshot(snapshot, provider, index, items)
    answers, pending, error = provider.exchange(items)
    output = apply_decisions(index, taxonomy, items, answers, args.min_confidence, provider.context)
    snapshot = export_snapshot(args.reports / "category_decisions.json", provider, index, items, answers, args.min_confidence)
    output["classification"]["snapshot_sha256"] = snapshot["sha256"]
    atomic_json(args.reports / "category_index.json", output)
    accuracy = independent_accuracy(args.gold, output["word_metadata"], taxonomy)
    resume = resume_command(args, model) if pending else None
    summary = write_report(args.reports / "CATEGORIES.md", output, taxonomy, snapshot, pending, accuracy, resume)
    summary.update(error=error, resume=resume)
    atomic_json(args.reports / "summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "build/reports/local_definitions/category_index.json")
    parser.add_argument("--taxonomy", type=Path, default=thematic.TAXONOMY_PATH)
    parser.add_argument("--cache", type=Path, default=ROOT / "tools/cache/word_categories")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports/categories")
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--model", default=os.environ.get("CATEGORY_MODEL"))
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--min-confidence", type=float, help="По умолчанию из снимка, иначе 0.8")
    parser.add_argument("--gold", type=Path)
    args = parser.parse_args()
    try:
        summary = run(args)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 2 if summary["pending"] else 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print("Ошибка классификации: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
