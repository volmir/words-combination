#!/usr/bin/env python3
"""LEX-04: role-specific suitability, cheap rules then opt-in Codex batches."""
import argparse
import json
import os
import re
import sys
from pathlib import Path

from classify_word_categories import export_snapshot, import_snapshot
from game_data_providers import CategoryProvider, atomic_json, json_digest
from word_categories import ROLE_EXCLUSIONS

ROOT = Path(__file__).resolve().parents[1]
VERSION = "word-suitability-v1"
PREFERRED_SOURCES = {"хлопкопункт", "реввоенсовет", "селитроварня"}
STATUSES = {"suitable", "unsuitable", "insufficient_data"}
LABELS = {"rare", "obsolete", "restricted_usage", "historical", "religious", "jargon",
          "slang", "offensive", "dialect", "specialist", "abbreviation", "diminutive",
          "productive_diminutive", "derived_feminine", "proper_name", "unknown_label"}
# These restrictions may never be lifted by frequency, a preference or the model.
HARD_LABELS = {"offensive", "proper_name", "productive_diminutive"}
PREFERRED_SOURCE_LABELS = {"rare", "historical", "obsolete", "restricted_usage", "abbreviation", "specialist"}
POLICY = {"version": VERSION, "common_min_occurrences": 100, "common_max_rank": 30000,
          "source_length": [8, 13], "target_length": [3, 30],
          "hard_labels": sorted(HARD_LABELS), "allowed_labels": sorted(LABELS),
          "role_exclusions": sorted(ROLE_EXCLUSIONS), "preferred_sources": sorted(PREFERRED_SOURCES),
          "preferred_source_labels": sorted(PREFERRED_SOURCE_LABELS)}
PROMPT = """Оцени пригодность русского слова для игры «Составь слова» отдельно в указанной роли.
source — исходное длинное слово; target — назначенная игроку разгадка, в том числе подсказка.
Исходник может быть редким, но цель должна быть понятной обычному игроку.
Вход содержит только слово, роль и структурированные признаки. frequency — наблюдаемая
частота словоформ, freq_rank — ранг; они не доказывают узнаваемость отдельного значения.
Верни suitable, unsuitable или insufficient_data, короткую причину (до 160 символов)
и assessment_version из policy.version. При сомнении выбирай insufficient_data.
Не оценивай качество текстов словарных статей, не создавай определений.
Не используй инструменты, файлы, поиск или внешние источники. Только вход JSON.
Для каждого входного id верни ровно один результат, без дополнительных полей.
"""


def validate_item(item, policy):
    if not isinstance(item, dict) or set(item) != {"id", "word", "role", "features"}:
        raise ValueError("Пригодность: посторонние поля входа")
    if (not isinstance(item["word"], str) or not re.fullmatch(r"[а-я]{3,30}", item["word"]) or
            item["role"] not in ("source", "target")):
        raise ValueError("Пригодность: недопустимое слово или роль (нужна нормализация ё→е)")
    f = item["features"]
    if not isinstance(f, dict) or set(f) != {"noun", "role_decision", "reachable", "frequency", "freq_rank", "labels"}:
        raise ValueError("Пригодность: недопустимые признаки")
    if (type(f["noun"]) is not bool or f["role_decision"] not in ("keep", "exclude", "review") or
            (f["reachable"] is not None and type(f["reachable"]) is not bool) or
            type(f["frequency"]) is not int or f["frequency"] < 0 or
            (f["freq_rank"] is not None and (type(f["freq_rank"]) is not int or f["freq_rank"] < 1)) or
            not isinstance(f["labels"], list) or
            any(not isinstance(label, str) or label not in LABELS for label in f["labels"]) or
            f["labels"] != sorted(set(f["labels"]))):
        raise ValueError("Пригодность: недопустимые значения признаков")
    if item["id"] != json_digest([item["word"], item["role"], f]):
        raise ValueError("Пригодность: ID не соответствует входу")


def result_schema(policy):
    properties = {"id": {"type": "string"}, "status": {"type": "string", "enum": sorted(STATUSES)},
                  "reason": {"type": "string", "minLength": 1, "maxLength": 160},
                  "assessment_version": {"type": "string", "enum": [VERSION]}}
    return {"type": "object", "additionalProperties": False, "required": ["results"],
            "properties": {"results": {"type": "array", "items": {
                "type": "object", "additionalProperties": False,
                "required": list(properties), "properties": properties}}}}


def validate_results(payload, items, policy):
    if not isinstance(payload, dict) or set(payload) != {"results"} or not isinstance(payload["results"], list):
        raise ValueError("Пригодность: неверный ответ")
    expected, seen = {item["id"] for item in items}, set()
    for result in payload["results"]:
        if (not isinstance(result, dict) or set(result) != {"id", "status", "reason", "assessment_version"} or
                not isinstance(result["id"], str) or result["id"] not in expected or result["id"] in seen or
                not isinstance(result["status"], str) or result["status"] not in STATUSES or
                not isinstance(result["reason"], str) or not result["reason"].strip() or
                len(result["reason"]) > 160 or any(ord(c) < 32 for c in result["reason"]) or
                result["assessment_version"] != VERSION):
            raise ValueError("Пригодность: невалидный результат")
        seen.add(result["id"])
    if seen != expected:
        raise ValueError("Пригодность: пропущены ID")
    return payload["results"]


class SuitabilityProvider(CategoryProvider):
    task = VERSION
    prompt = PROMPT
    snapshot_schema = "word-suitability-decisions-v1"
    snapshot_policy = {"assessment_version": VERSION}
    schema_builder = staticmethod(result_schema)
    validate_item = staticmethod(validate_item)
    validate_results = staticmethod(validate_results)

    def request_payload(self, items):
        return {"task": self.task, "prompt": self.prompt, "policy": self.taxonomy, "items": items}

    def __init__(self, cache, model, **kwargs):
        super().__init__(cache, model, POLICY, **kwargs)


def input_items(document):
    """Closed input boundary: never silently forward article/definition fields."""
    if (not isinstance(document, dict) or set(document) != {"schema", "candidates"} or
            document["schema"] != "word-suitability-input-v1" or not isinstance(document["candidates"], list)):
        raise ValueError("Нужен word-suitability-input-v1")
    items = []
    identities = set()
    for row in document["candidates"]:
        if not isinstance(row, dict) or set(row) != {"word", "role", "features"}:
            raise ValueError("Пригодность: посторонние поля кандидата")
        item = dict(row, id=json_digest([row["word"], row["role"], row["features"]]))
        validate_item(item, POLICY)
        identity = (item["word"], item["role"])
        if identity in identities:
            raise ValueError("Повтор слова в одной роли")
        identities.add(identity)
        items.append(item)
    return sorted(items, key=lambda item: (item["word"], item["role"]))


def decision(item, status, reason):
    return {"id": item["id"], "status": status, "reason": reason, "assessment_version": VERSION}


def rule_decision(item):
    word, role, f = item["word"], item["role"], item["features"]
    if word in ROLE_EXCLUSIONS:
        return decision(item, "unsuitable", "Явный запрет владельца для исходников и целей")
    if (not f["noun"] or f["role_decision"] == "exclude" or
            set(f["labels"]) & HARD_LABELS or not POLICY[role + "_length"][0] <= len(word) <= POLICY[role + "_length"][1]):
        return decision(item, "unsuitable", "Ограничение роли или лексического состава")
    if f["reachable"] is False:
        return decision(item, "unsuitable", "Не выполнены ограничения достижимости роли")
    if f["reachable"] is None:
        return decision(item, "insufficient_data", "Достижимость роли ещё не подтверждена")
    if (role == "source" and word in PREFERRED_SOURCES and
            set(f["labels"]) <= PREFERRED_SOURCE_LABELS):
        return decision(item, "suitable", "Предпочтительный исходник владельца; достижимость подтверждена")
    if (f["role_decision"] == "keep" and not f["labels"] and
            f["frequency"] >= POLICY["common_min_occurrences"] and
            f["freq_rank"] is not None and f["freq_rank"] <= POLICY["common_max_rank"]):
        return decision(item, "suitable", "Нейтральное слово с подтверждённой высокой частотой")
    return None


def apply_decisions(items, answers, context):
    output = []
    for item in items:
        ruled = rule_decision(item)  # Reapply hard priorities even to cached/conflicting model answers.
        answer = answers.get(item["id"])
        if answer is not None:
            validate_results({"results": [answer]}, [item], POLICY)
        result = ruled or answer or decision(item, "insufficient_data", "Оценка ИИ пока недоступна")
        output.append(dict(item, assessment=result, assessment_source="rules" if ruled else "ai" if answer else "pending",
                           assessment_sha256=json_digest([context, item, result])))
    return {"schema": VERSION, "context": context, "assessments": output,
            "pools": {role: sorted(item["word"] for item in output if item["role"] == role and
                                   item["assessment"]["status"] == "suitable") for role in ("source", "target")}}


def rank_suitable(output, role):
    """Stable final tie break: preference only for source, observed rank, then word."""
    if role not in ("source", "target"):
        raise ValueError("Неизвестная роль")
    rows = [row for row in output["assessments"] if row["role"] == role and
            row["assessment"]["status"] == "suitable"]
    return [row["word"] for row in sorted(rows, key=lambda row: (
        0 if role == "source" and row["word"] in PREFERRED_SOURCES else 1,
        row["features"]["freq_rank"] if row["features"]["freq_rank"] is not None else float("inf"), row["word"]))]


def run(args):
    document = json.loads(args.input.read_text(encoding="utf-8"))
    items = input_items(document)
    disputed = [item for item in items if rule_decision(item) is None]
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8")) if args.snapshot else None
    if args.snapshot and (not isinstance(snapshot, dict) or not isinstance(snapshot.get("context"), dict)):
        raise ValueError("Некорректный снимок пригодности")
    model = args.model or (snapshot["context"].get("model") if snapshot is not None else None)
    provider = SuitabilityProvider(args.cache, model, allow_network=args.allow_network,
                                   batch_size=args.batch_size, timeout=args.timeout, retries=args.retries)
    if snapshot is not None:
        import_snapshot(snapshot, provider, document, disputed)
    answers, pending, error = provider.exchange(disputed)
    output = apply_decisions(items, answers, provider.context)
    snapshot = export_snapshot(args.reports / "suitability_decisions.json", provider, document, disputed, answers)
    output.update(input_sha256=json_digest(document), snapshot_sha256=snapshot["sha256"])
    output["ranked_pools"] = {role: rank_suitable(output, role) for role in ("source", "target")}
    atomic_json(args.reports / "word_suitability.json", output)
    summary = {"pending": pending, "error": error, "evaluated_by_rules": len(items) - len(disputed),
               "evaluated_by_ai": len(answers), "pool_sizes": {role: len(words) for role, words in output["pools"].items()}}
    atomic_json(args.reports / "summary.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=ROOT / "tools/cache/word_suitability")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports/suitability")
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--model", default=os.environ.get("CATEGORY_MODEL"))
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--retries", type=int, default=2)
    args = parser.parse_args()
    try:
        summary = run(args)
        print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
        return 2 if summary["pending"] else 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print("Ошибка оценки пригодности: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
