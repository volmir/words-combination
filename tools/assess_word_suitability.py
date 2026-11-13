#!/usr/bin/env python3
"""LEX-04: role-specific suitability, cheap rules then opt-in Codex batches."""
import argparse
import json
import math
import os
import re
import sys
import time
from pathlib import Path

from classify_word_categories import export_snapshot, import_snapshot
from game_data_providers import CategoryProvider, atomic_json, json_digest
import termination_guard as termination
from word_categories import ROLE_EXCLUSIONS, normalize

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


def input_items(document, pairs=None):
    """Closed input boundary; optional explicit pairs select a subset of roles."""
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
    return selected_items(sorted(items, key=lambda item: (item["word"], item["role"])), pairs)


def read_pairs_file(path):
    """UTF-8 request list: one normalized (word, role) pair per line.

    The role is `source`/`исходник` or `target`/`цель`; blanks and `#` comments
    are ignored. Repeated pairs are collapsed deterministically.
    """
    pairs, seen = [], set()
    for number, line in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), 1):
        token = line.strip()
        if not token or token.startswith("#"):
            continue
        fields = token.replace("\t", " ").split()
        if len(fields) != 2:
            raise ValueError(f"Пригодность: строка {number} должна содержать слово и роль")
        word, role = normalize(fields[0]), fields[1].casefold()
        role = {"исходник": "source", "цель": "target"}.get(role, role)
        if role not in ("source", "target") or not re.fullmatch(r"[а-я]{3,30}", word):
            raise ValueError(f"Пригодность: недопустимая пара в строке {number} (нужна нормализация ё→е)")
        if (word, role) not in seen:
            seen.add((word, role))
            pairs.append((word, role))
    # Keep the caller's (SEL-04 plan) order: a limited budget must reach the
    # highest-priority source/assignment/rescue pairs first.
    return pairs


def selected_items(items, pairs):
    """Only explicitly requested pairs; never silently expand to the full input."""
    if pairs is None:
        return items
    available = {(item["word"], item["role"]): item for item in items}
    missing = [f"{word}:{role}" for word, role in pairs if (word, role) not in available]
    if missing:
        raise ValueError("В выборочном входе нет пар: " + ", ".join(missing))
    return [available[pair] for pair in pairs]


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


def import_selected_snapshot(snapshot, provider, document, items):
    """Reuse exact compatible items from a full or partial selective snapshot."""
    if not isinstance(snapshot, dict):
        raise ValueError("Некорректный снимок пригодности")
    fields = {"schema", "context", "taxonomy", "input_sha256", "items", "results", "complete", "policy", "sha256"}
    body = {key: value for key, value in snapshot.items() if key != "sha256"}
    if (set(snapshot) != fields or snapshot["schema"] != provider.snapshot_schema or
            snapshot["sha256"] != json_digest(body) or snapshot["context"] != provider.context or
            snapshot["taxonomy"] != provider.taxonomy or snapshot["input_sha256"] != json_digest(document) or
            snapshot["policy"] != provider.snapshot_policy or type(snapshot["complete"]) is not bool):
        raise ValueError("Снимок несовместим: версия, вход, политика, провайдер, модель или SHA-256")
    snapshot_items, results = snapshot["items"], snapshot["results"]
    if (not isinstance(snapshot_items, list) or any(not isinstance(item, dict) for item in snapshot_items) or
            not isinstance(results, list) or any(not isinstance(result, dict) for result in results)):
        raise ValueError("Некорректный список решений снимка")
    by_id = {}
    for item in snapshot_items:
        provider.validate_item(item, provider.taxonomy)
        if item["id"] in by_id:
            raise ValueError("Повтор элементов снимка")
        by_id[item["id"]] = item
    result_ids = [result.get("id") for result in results]
    if (any(not isinstance(result_id, str) for result_id in result_ids) or
            len(set(result_ids)) != len(result_ids) or not set(result_ids) <= set(by_id)):
        raise ValueError("Некорректный список решений снимка")
    provider.validate_results({"results": results}, [by_id[result_id] for result_id in result_ids],
                              provider.taxonomy)
    if snapshot["complete"] != (len(results) == len(snapshot_items)):
        raise ValueError("Некорректный признак полноты снимка")
    answers = {result["id"]: result for result in results}
    wanted = [item for item in items if item["id"] in answers and by_id[item["id"]] == item]
    # Validate every conflict before writing any decision.
    for item in wanted:
        previous = provider.read(item)
        if previous is not None and previous != answers[item["id"]]:
            raise ValueError("Снимок конфликтует с кэшем")
    for item in wanted:
        provider.store(item, answers[item["id"]])


def role_counters(items, answers, cached_ids):
    """Role-specific sources plus final unresolved (pending or insufficient data)."""
    counters = {role: {"rule_decided": 0, "cache_hit": 0, "ai_new": 0, "unresolved": 0}
                for role in ("source", "target")}
    for item in items:
        row, ruled = counters[item["role"]], rule_decision(item)
        if ruled is not None:
            row["rule_decided"] += 1
            result = ruled
        elif item["id"] in answers:
            row["cache_hit" if item["id"] in cached_ids else "ai_new"] += 1
            result = answers[item["id"]]
        else:
            row["unresolved"] += 1
            continue
        if result["status"] == "insufficient_data":
            row["unresolved"] += 1
    return counters


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
    started = time.monotonic()
    document = json.loads(args.input.read_text(encoding="utf-8"))
    pairs_path = getattr(args, "pairs_file", None)
    pairs = read_pairs_file(pairs_path) if pairs_path else None
    items = input_items(document, pairs)
    disputed = [item for item in items if rule_decision(item) is None]
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8")) if args.snapshot else None
    if args.snapshot and (not isinstance(snapshot, dict) or not isinstance(snapshot.get("context"), dict)):
        raise ValueError("Некорректный снимок пригодности")
    model = args.model or (snapshot["context"].get("model") if snapshot is not None else None)
    budget_time = getattr(args, "time_budget", None)
    if budget_time is not None:
        if type(budget_time) not in (int, float) or not math.isfinite(budget_time) or budget_time < 0:
            raise ValueError("time-budget должен быть неотрицательным числом секунд")
        budget_time = max(0.0, float(budget_time) - (time.monotonic() - started))
    provider = SuitabilityProvider(args.cache, model, allow_network=args.allow_network,
                                   batch_size=args.batch_size, timeout=args.timeout, retries=args.retries,
                                   provider=args.provider,
                                   max_new=getattr(args, "max_new_decisions", None),
                                   max_batches=getattr(args, "max_batches", None),
                                   time_budget=budget_time)
    if snapshot is not None and (pairs is None or disputed):
        if pairs is None:
            import_snapshot(snapshot, provider, document, disputed)
        else:
            import_selected_snapshot(snapshot, provider, document, disputed)
    cached_ids = {item["id"] for item in disputed if provider.read(item) is not None}
    if disputed:
        answers, pending, error = provider.exchange(disputed)
    else:
        # An empty request list is a valid no-op: no cache scan and no model call.
        answers, pending, error = {}, 0, None
    output = apply_decisions(items, answers, provider.context)
    output.update(mode="selective" if pairs is not None else "full",
                  requested_pairs=len(pairs) if pairs is not None else len(items), selected_pairs=len(items))
    snapshot = export_snapshot(args.reports / "suitability_decisions.json", provider, document, disputed, answers)
    output.update(input_sha256=json_digest(document), snapshot_sha256=snapshot["sha256"])
    output["ranked_pools"] = {role: rank_suitable(output, role) for role in ("source", "target")}
    atomic_json(args.reports / "word_suitability.json", output)
    stats = getattr(provider, "stats", {})
    summary = {"mode": output["mode"], "requested_pairs": output["requested_pairs"],
               "selected_pairs": len(items), "pending": pending, "error": error,
               "evaluated_by_rules": len(items) - len(disputed),
               "evaluated_by_ai": len(answers), "cache_hit": stats.get("cached", 0),
               "ai_new": stats.get("ai_new", 0), "batches": stats.get("batches", 0),
               "role_counters": role_counters(items, answers, cached_ids),
               "pool_sizes": {role: len(words) for role, words in output["pools"].items()},
               "budget": {"max_new_decisions": getattr(args, "max_new_decisions", None),
                          "max_batches": getattr(args, "max_batches", None),
                          "time_budget": getattr(args, "time_budget", None)},
               "spent_time": round(time.monotonic() - started, 6)}
    atomic_json(args.reports / "summary.json", summary)
    return summary


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=ROOT / "tools/cache/word_suitability")
    parser.add_argument("--reports", type=Path, default=ROOT / "build/reports/suitability")
    parser.add_argument("--pairs-file", type=Path,
                        help="UTF-8, по одной паре «слово роль» в строке; без флага — полный проход")
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--max-new-decisions", type=int, help="Максимум новых ИИ-решений за вызов")
    parser.add_argument("--max-batches", type=int, help="Максимум батчей модели за вызов")
    parser.add_argument("--time-budget", type=float, help="Лимит времени на ИИ-батчи, секунды")
    parser.add_argument("--model", default=os.environ.get("CATEGORY_MODEL"))
    parser.add_argument("--provider", choices=("codex", "opencode"),
                        default=os.environ.get("CATEGORY_PROVIDER", "codex"))
    parser.add_argument("--allow-network", action="store_true")
    parser.add_argument("--batch-size", type=int, default=20)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--retries", type=int, default=2)
    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    termination.become_group_leader()
    termination.install_termination_handler()
    try:
        summary = run(args)
        print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
        return 2 if summary["pending"] else 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print("Ошибка оценки пригодности: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
