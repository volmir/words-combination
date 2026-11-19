#!/usr/bin/env python3
"""DATA-06: read-only vocabulary audit for future cleanup.

The script reads ``data/words.txt`` and ``data/campaign.json``, applies the
curated categories from ``tools/vocabulary_audit_config.json`` and writes a
reproducible Markdown report plus JSON next to it.  It never edits ``data/``,
runtime assets or the campaign, and it never runs the dictionary generator.

Examples are single words only.  Dictionary articles, definitions and other
third-party prose are not copied into the report or tests.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALPHABET = "абвгдежзийклмнопрстуфхцчшщъыьэюя"
SCHEMA = "vocabulary-audit-v1"
CONFIG_SCHEMA = "vocabulary-audit-config-v1"
TASK_TYPES = ("T1", "T2", "T3", "T4", "T5", "T6", "T7")
DEFAULT_WORDS = ROOT / "data" / "words.txt"
DEFAULT_CAMPAIGN = ROOT / "data" / "campaign.json"
DEFAULT_CONFIG = Path(__file__).with_name("vocabulary_audit_config.json")
DEFAULT_MARKDOWN = ROOT / "docs" / "VOCABULARY_AUDIT.md"
DEFAULT_JSON = ROOT / "docs" / "VOCABULARY_AUDIT.json"


def normalize(value: str) -> str:
    """Same canonical form as the game alphabet: lower case, ё -> е, Cyrillic only."""
    return "".join(ch for ch in value.lower().replace("ё", "е") if ch in ALPHABET)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def display_path(path: Path) -> str:
    """Stable relative path in reports when the file is inside the repository."""
    try:
        return str(Path(path).resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def atomic_text(path: Path, text: str) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, staged = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(text)
        os.replace(staged, path)
    finally:
        if os.path.exists(staged):
            os.unlink(staged)


def atomic_json(path: Path, value) -> None:
    atomic_text(path, json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n")


def canonical_word(value, label: str) -> str:
    if type(value) is not str:
        raise ValueError(f"{label}: слово должно быть строкой")
    word = normalize(value)
    if word != value:
        raise ValueError(f"{label}: слово не каноническое: {value!r}")
    if not 3 <= len(word) <= 30:
        raise ValueError(f"{label}: длина слова вне 3..30: {value!r}")
    return word


def validate_config(document) -> dict:
    if not isinstance(document, dict) or document.get("schema") != CONFIG_SCHEMA:
        raise ValueError("Конфиг: неверная схема")
    report = document.get("report")
    if not isinstance(report, dict):
        raise ValueError("Конфиг: нет report")
    margin = report.get("margin_threshold")
    examples = report.get("example_limit")
    if type(margin) is not int or margin < 0:
        raise ValueError("Конфиг: margin_threshold должен быть целым >= 0")
    if type(examples) is not int or examples < 1:
        raise ValueError("Конфиг: example_limit должен быть целым >= 1")
    if not isinstance(report.get("title"), str) or not report["title"].strip():
        raise ValueError("Конфиг: пустой заголовок")

    known = document.get("known_narrow_tasks", [])
    if not isinstance(known, list):
        raise ValueError("Конфиг: known_narrow_tasks должен быть списком")
    for index, row in enumerate(known):
        label = f"Конфиг: known_narrow_tasks[{index}]"
        if not isinstance(row, dict):
            raise ValueError(label + ": ожидается объект")
        if type(row.get("level")) is not int or row["level"] < 1:
            raise ValueError(label + ": неверный уровень")
        if row.get("type") not in TASK_TYPES:
            raise ValueError(label + ": неверный тип задания")
        if type(row.get("dictionary_words")) is not int or row["dictionary_words"] < 0:
            raise ValueError(label + ": неверное число слов")

    categories = document.get("categories")
    if not isinstance(categories, list) or not categories:
        raise ValueError("Конфиг: categories пуст")
    seen_ids, seen_words = set(), {}
    for index, category in enumerate(categories):
        label = f"Конфиг: categories[{index}]"
        if not isinstance(category, dict):
            raise ValueError(label + ": ожидается объект")
        category_id = category.get("id")
        if type(category_id) is not str or not category_id.strip() or category_id in seen_ids:
            raise ValueError(label + ": пустой или повторный id")
        seen_ids.add(category_id)
        for field in ("title", "description"):
            if type(category.get(field)) is not str or not category[field].strip():
                raise ValueError(label + f": пустое поле {field}")
        words = category.get("words")
        if not isinstance(words, list) or not words:
            raise ValueError(label + ": пустой список words")
        canonical = []
        for word_index, word in enumerate(words):
            checked = canonical_word(word, f"{label}.words[{word_index}]")
            if checked in seen_words:
                raise ValueError(f"{label}: слово повторяется в категориях: {checked} "
                                 f"(уже в {seen_words[checked]})")
            seen_words[checked] = category_id
            canonical.append(checked)
        if len(set(canonical)) != len(canonical):
            raise ValueError(label + ": слова повторяются")
        for field in ("absent_reference", "lexicalized_exceptions"):
            values = category.get(field, [])
            if not isinstance(values, list):
                raise ValueError(label + f": {field} должен быть списком")
            checked_values = []
            for word_index, word in enumerate(values):
                checked = canonical_word(word, f"{label}.{field}[{word_index}]")
                if checked in canonical:
                    raise ValueError(label + f": {field} пересекается с words: {checked}")
                checked_values.append(checked)
            if len(set(checked_values)) != len(checked_values):
                raise ValueError(label + f": {field} содержит повторы")
        notes = category.get("word_notes", {})
        if not isinstance(notes, dict):
            raise ValueError(label + ": word_notes должен быть объектом")
        for word in notes:
            if word not in canonical:
                raise ValueError(label + f": word_notes для отсутствующего слова: {word}")
        absent_notes = category.get("absent_notes", {})
        if not isinstance(absent_notes, dict):
            raise ValueError(label + ": absent_notes должен быть объектом")
        for word in absent_notes:
            if word not in category.get("absent_reference", []):
                raise ValueError(label + f": absent_notes для отсутствующей ссылки: {word}")
    return document


def load_config(path: Path = DEFAULT_CONFIG) -> dict:
    return validate_config(read_json(path))


def read_words(path: Path = DEFAULT_WORDS) -> set[str]:
    words: set[str] = set()
    with Path(path).open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            raw = line.strip()
            if not raw:
                continue
            word = normalize(raw)
            if word != raw:
                raise ValueError(f"words.txt:{line_number}: слово не каноническое: {raw!r}")
            if not 3 <= len(word) <= 30:
                raise ValueError(f"words.txt:{line_number}: длина слова вне 3..30: {raw!r}")
            if word in words:
                raise ValueError(f"words.txt:{line_number}: повтор слова: {raw!r}")
            words.add(word)
    if not words:
        raise ValueError("words.txt пуст")
    return words


def answers_for(source: str, dictionary: set[str], minimum: int = 3) -> set[str]:
    """All dictionary words buildable from source letters, source itself excluded."""
    source = normalize(source)
    counts = Counter(source)
    return {
        word for word in dictionary
        if minimum <= len(word) <= 30 and word != source
        and not (Counter(word) - counts)
    }


def task_needed(task: dict) -> int:
    if task["type"] == "T4":
        return len(task["targets"])
    if task["type"] in ("T3", "T5"):
        return 1
    return task["n"]


def task_accepted(task: dict, answers: set[str], count_min_length: int) -> set[str]:
    kind = task["type"]
    if kind == "T1":
        return {word for word in answers if len(word) >= count_min_length}
    if kind == "T2":
        return {word for word in answers
                if len(word) >= count_min_length and word.startswith(task["letter"])}
    if kind == "T3":
        return {word for word in answers if len(word) >= task["minLength"]}
    if kind == "T4":
        return answers & set(task["targets"])
    if kind == "T5":
        return {word for word in answers
                if len(word) >= task["minLength"] and word.endswith(task["ending"])}
    if kind == "T6":
        return answers & set(task["words"])
    if kind == "T7":
        return {word for word in answers if task["letter"] in word}
    raise ValueError("Неизвестный тип задания: " + str(kind))


def task_parameter(task: dict) -> str:
    kind = task["type"]
    if kind == "T1":
        return f"n={task['n']}"
    if kind == "T2":
        return f"n={task['n']}, letter={task['letter']}"
    if kind == "T3":
        return f"minLength={task['minLength']}, hintTarget={task['hintTarget']}"
    if kind == "T4":
        return "targets=" + ", ".join(task["targets"])
    if kind == "T5":
        return f"ending={task['ending']}, minLength={task['minLength']}"
    if kind == "T6":
        return f"category={task['category']}, n={task['n']}, words={len(task['words'])}"
    if kind == "T7":
        return f"n={task['n']}, letter={task['letter']}"
    return ""


def level_answers(level: dict, dictionary: set[str]) -> set[str]:
    return answers_for(level["word"], dictionary)


def category_word_entry(word: str, category: dict) -> dict:
    return {
        "word": word,
        "note": category.get("word_notes", {}).get(word, ""),
        "buildable_levels": [],
        "task_roles": {kind: [] for kind in TASK_TYPES},
        "assigned": {"T3_target": [], "T4_target": [], "T6_list": []},
        "affected_task_ids": [],
    }


def build_report(dictionary: set[str], campaign: dict, config: dict, inputs=None) -> dict:
    if campaign.get("version") != 2:
        raise ValueError("Кампания: ожидается version=2")
    count_min_length = campaign.get("countMinLength")
    if type(count_min_length) is not int or count_min_length < 3:
        raise ValueError("Кампания: неверный countMinLength")
    levels = campaign.get("levels")
    if not isinstance(levels, list) or not levels:
        raise ValueError("Кампания: нет уровней")

    configured_categories = config["categories"]
    categories = []
    for category in configured_categories:
        present = [word for word in category["words"] if word in dictionary]
        absent = [word for word in category["words"] if word not in dictionary]
        absent_reference = []
        for word in category.get("absent_reference", []):
            absent_reference.append({
                "word": word,
                "present": word in dictionary,
                "note": category.get("absent_notes", {}).get(word, ""),
            })
        categories.append({
            "id": category["id"],
            "title": category["title"],
            "description": category["description"],
            "configured_words": list(category["words"]),
            "present_words": present,
            "absent_words": absent,
            "examples": present[:config["report"]["example_limit"]],
            "absent_reference": absent_reference,
            "lexicalized_exceptions": list(category.get("lexicalized_exceptions", [])),
            "words": [category_word_entry(word, category) for word in present],
        })

    word_entries = {}
    for category in categories:
        for entry in category["words"]:
            word_entries[entry["word"]] = entry
    candidate_words = set(word_entries)

    task_rows = []
    accepted_by_task = {}
    level_rows = []
    task_order = []
    for level in levels:
        answers = level_answers(level, dictionary)
        rows = []
        for task in level["tasks"]:
            accepted = task_accepted(task, answers, count_min_length)
            needed = task_needed(task)
            margin = len(accepted) - needed
            row = {
                "id": task["id"],
                "type": task["type"],
                "parameter": task_parameter(task),
                "needed": needed,
                "count": len(accepted),
                "margin": margin,
                "threshold_share": round(needed / len(accepted), 6) if accepted else None,
                "exact_target": task["type"] == "T4",
            }
            rows.append(row)
            task_rows.append({"level": level["number"], **row})
            accepted_by_task[task["id"]] = accepted
            task_order.append(task["id"])
        level_rows.append({
            "number": level["number"],
            "stage": level.get("stage"),
            "required_stars": level.get("requiredStars"),
            "word": normalize(level["word"]),
            "answers_count": len(answers),
            "tasks": rows,
        })
        for word in sorted(candidate_words & answers):
            word_entries[word]["buildable_levels"].append(level["number"])
        for task in level["tasks"]:
            accepted = accepted_by_task[task["id"]]
            kind = task["type"]
            for word in sorted(candidate_words & accepted):
                word_entries[word]["task_roles"][kind].append(level["number"])
                if task["id"] not in word_entries[word]["affected_task_ids"]:
                    word_entries[word]["affected_task_ids"].append(task["id"])
            if kind == "T3":
                target = normalize(task["hintTarget"])
                if target in word_entries:
                    word_entries[target]["assigned"]["T3_target"].append(level["number"])
                    if task["id"] not in word_entries[target]["affected_task_ids"]:
                        word_entries[target]["affected_task_ids"].append(task["id"])
            elif kind == "T4":
                for target in task["targets"]:
                    target = normalize(target)
                    if target in word_entries:
                        word_entries[target]["assigned"]["T4_target"].append(level["number"])
                        if task["id"] not in word_entries[target]["affected_task_ids"]:
                            word_entries[target]["affected_task_ids"].append(task["id"])
            elif kind == "T6":
                for word in task["words"]:
                    word = normalize(word)
                    if word in word_entries:
                        word_entries[word]["assigned"]["T6_list"].append(level["number"])
                        if task["id"] not in word_entries[word]["affected_task_ids"]:
                            word_entries[word]["affected_task_ids"].append(task["id"])

    for category in categories:
        role_counts = {"T3_target": 0, "T4_target": 0, "T6_list": 0, "T5": 0, "letter_tasks": 0}
        category_task_ids = set()
        for entry in category["words"]:
            if entry["assigned"]["T3_target"]:
                role_counts["T3_target"] += 1
            if entry["assigned"]["T4_target"]:
                role_counts["T4_target"] += 1
            if entry["assigned"]["T6_list"]:
                role_counts["T6_list"] += 1
            if entry["task_roles"]["T5"]:
                role_counts["T5"] += 1
            for kind in ("T1", "T2", "T3", "T7"):
                if entry["task_roles"][kind]:
                    role_counts["letter_tasks"] += 1
                    category_task_ids.update(
                        task["id"] for task in task_rows
                        if task["level"] in entry["task_roles"][kind] and task["type"] == kind
                    )
            category_task_ids.update(entry["affected_task_ids"])
        category["role_counts"] = role_counts
        category["affected_task_count"] = len(category_task_ids)

    candidates = []
    for category in categories:
        for entry in category["words"]:
            candidates.append({
                "word": entry["word"],
                "category": category["id"],
                "category_title": category["title"],
                "note": entry["note"],
                "buildable_levels": entry["buildable_levels"],
                "task_roles": entry["task_roles"],
                "assigned": entry["assigned"],
                "affected_task_ids": entry["affected_task_ids"],
            })

    affected_tasks = []
    for task in task_rows:
        accepted = accepted_by_task[task["id"]]
        hits = sorted(candidate_words & accepted)
        if not hits:
            continue
        by_category = defaultdict(list)
        for category in categories:
            for entry in category["words"]:
                if entry["word"] in hits:
                    by_category[category["id"]].append(entry["word"])
        affected_tasks.append({
            "level": task["level"],
            "task_id": task["id"],
            "type": task["type"],
            "needed": task["needed"],
            "count": task["count"],
            "margin": task["margin"],
            "margin_after_removal": task["count"] - len(hits) - task["needed"],
            "would_break": task["count"] - len(hits) < task["needed"],
            "hits": hits,
            "hits_by_category": {key: sorted(value) for key, value in by_category.items()},
        })

    threshold = config["report"]["margin_threshold"]
    narrow_levels = []
    for level in level_rows:
        tasks = [task for task in level["tasks"]
                 if not task["exact_target"] and task["margin"] <= threshold]
        if tasks:
            narrow_levels.append({
                "level": level["number"],
                "word": level["word"],
                "min_margin": min(task["margin"] for task in tasks),
                "tasks": tasks,
            })

    exact_target_levels = []
    for level in level_rows:
        tasks = [task for task in level["tasks"] if task["exact_target"]]
        if tasks:
            exact_target_levels.append({
                "level": level["number"],
                "tasks": [dict(id=task["id"], type=task["type"], needed=task["needed"],
                               count=task["count"]) for task in tasks],
            })

    known_checks = []
    for expected in config.get("known_narrow_tasks", []):
        task = next((row for row in task_rows
                     if row["level"] == expected["level"] and row["type"] == expected["type"]), None)
        observed = task["count"] if task else None
        known_checks.append({
            "level": expected["level"],
            "type": expected["type"],
            "task_id": task["id"] if task else None,
            "expected": expected["dictionary_words"],
            "observed": observed,
            "matches": observed == expected["dictionary_words"],
            "note": expected.get("note", ""),
        })

    category_summary = []
    for category in categories:
        category_summary.append({
            "id": category["id"],
            "title": category["title"],
            "configured": len(category["configured_words"]),
            "present": len(category["present_words"]),
            "absent": len(category["absent_words"]),
            "examples": category["examples"],
            "affected_tasks": category["affected_task_count"],
            "role_counts": category["role_counts"],
        })

    total_tasks = sum(len(level["tasks"]) for level in levels)
    return {
        "schema": SCHEMA,
        "title": config["report"]["title"],
        "inputs": inputs or {},
        "campaign": {
            "version": campaign.get("version"),
            "countMinLength": count_min_length,
            "levels": len(levels),
            "tasks": total_tasks,
        },
        "summary": {
            "dictionary_words": len(dictionary),
            "levels": len(levels),
            "tasks": total_tasks,
            "candidate_words": len(candidates),
            "categories": category_summary,
            "affected_tasks": len(affected_tasks),
            "narrow_levels": [row["level"] for row in narrow_levels],
        },
        "categories": categories,
        "candidates": candidates,
        "levels": level_rows,
        "affected_tasks": affected_tasks,
        "narrow_levels": narrow_levels,
        "exact_target_levels": exact_target_levels,
        "known_narrow_tasks": known_checks,
    }


def format_levels(levels) -> str:
    return ", ".join(str(value) for value in levels) if levels else "—"


def format_percent(value) -> str:
    return "—" if value is None else f"{value * 100:.1f}%"


def role_text(entry: dict) -> str:
    parts = []
    for key, label in (("T3_target", "T3-цель"), ("T4_target", "T4-цель"),
                       ("T6_list", "T6-список")):
        if entry["assigned"].get(key):
            parts.append(f"{label}: {format_levels(entry['assigned'][key])}")
    for kind in TASK_TYPES:
        levels = entry["task_roles"].get(kind, [])
        if levels:
            parts.append(f"{kind}: {format_levels(levels)}")
    return "; ".join(parts) if parts else "—"


def render_markdown(report: dict) -> str:
    summary = report["summary"]
    inputs = report.get("inputs", {})
    lines = [f"# {report.get('title', 'Аудит словаря')}", ""]
    lines.extend([
        "Отчёт только читает `data/words.txt` и `data/campaign.json`. Словарь, кампания и",
        "runtime-assets не изменяются; регенерация не запускается. Это список кандидатов",
        "для будущего решения владельца, а не применённая чистка.",
        "",
        f"- Словарь: **{summary['dictionary_words']:,}** слов.".replace(",", " "),
        f"- Кампания: **{summary['levels']}** уровней, **{summary['tasks']}** заданий.",
        f"- Кандидатов: **{summary['candidate_words']}** в **{len(summary['categories'])}** категориях.",
        f"- Затронуто заданий: **{summary['affected_tasks']}**.",
        "",
    ])
    if inputs:
        lines.append("Входные файлы:")
        for key in ("words", "campaign", "config"):
            item = inputs.get(key, {})
            if item:
                lines.append(f"- `{item.get('path', key)}` — SHA-256 `{item.get('sha256', '—')}`")
        lines.append("")

    lines.extend([
        "## Сводка категорий",
        "",
        "| Категория | Слов в словаре | Настроено | Примеры (до 5) | Затронуто заданий |",
        "|---|---:|---:|---|---:|",
    ])
    for row in summary["categories"]:
        examples = ", ".join(row["examples"]) if row["examples"] else "—"
        lines.append(f"| {row['title']} | {row['present']} | {row['configured']} | "
                     f"{examples} | {row['affected_tasks']} |")
    lines.append("")

    lines.extend([
        "## Кандидаты к исключению",
        "",
        "Ниже перечислены все слова из конфига, которые сейчас есть в словаре. Это",
        "кандидаты к рассмотрению, а не решение об удалении. «Буквы» — уровни, из букв",
        "исходника которых слово можно собрать; «Роли» — задания, где слово учитывается",
        "по правилам `:core`.",
        "",
    ])
    for category in report["categories"]:
        lines.append(f"### {category['title']} ({len(category['present_words'])})")
        lines.append("")
        lines.append(category["description"])
        lines.append("")
        if category["absent_words"]:
            lines.append("Настроено, но отсутствует в словаре: "
                         + ", ".join(f"«{word}»" for word in category["absent_words"]) + ".")
            lines.append("")
        if category["absent_reference"]:
            references = []
            for item in category["absent_reference"]:
                status = "есть в словаре" if item["present"] else "отсутствует"
                note = f" — {item['note'].rstrip('.')}" if item["note"] else ""
                references.append(f"«{item['word']}» ({status}){note}")
            lines.append("Контрольные формы: " + "; ".join(references) + ".")
            lines.append("")
        if category["lexicalized_exceptions"]:
            lines.append("Исключены как подтверждённые лексикализации: "
                         + ", ".join(f"«{word}»" for word in category["lexicalized_exceptions"]) + ".")
            lines.append("")
        assigned = {"T3_target": [], "T4_target": [], "T6_list": []}
        t5_answers = []
        letter_levels = set()
        for entry in category["words"]:
            for key in assigned:
                if entry["assigned"].get(key):
                    assigned[key].append(
                        f"{entry['word']} (уровни {format_levels(entry['assigned'][key])})")
            if entry["task_roles"]["T5"]:
                t5_answers.append(
                    f"{entry['word']} (уровни {format_levels(entry['task_roles']['T5'])})")
            letter_levels.update(entry["buildable_levels"])

        def listed(rows):
            return "; ".join(rows) if rows else "нет"

        lines.extend([
            f"- T3-цели: {listed(assigned['T3_target'])}.",
            f"- T4-цели: {listed(assigned['T4_target'])}.",
            f"- T5-ответы: {listed(t5_answers)}.",
            f"- T6-список: {listed(assigned['T6_list'])}.",
            f"- Буквенные наборы: {format_levels(sorted(letter_levels))}.",
            "",
        ])
        if not category["present_words"]:
            lines.extend(["В текущем словаре слов категории нет.", ""])
            continue
        lines.extend([
            "| Слово | Буквы (уровни) | Роли в заданиях | Примечание |",
            "|---|---|---|---|",
        ])
        for entry in category["words"]:
            note = entry["note"] or "—"
            lines.append(f"| {entry['word']} | {format_levels(entry['buildable_levels'])} | "
                         f"{role_text(entry)} | {note} |")
        lines.append("")

    lines.extend([
        "## Затронутые задания",
        "",
        "Слово считается затронутым, если оно входит в принятый набор задания по",
        "правилам `:core` (T1/T2 — длина от 4, T3/T5 — собственные пороги, T4 — точные",
        "цели, T6 — список задания, T7 — вхождение буквы). «После удаления» — запас,",
        "если исключить все перечисленные слова одновременно.",
        "",
    ])
    if not report["affected_tasks"]:
        lines.extend(["Нет заданий, затронутых текущими кандидатами.", ""])
    else:
        lines.extend([
            "| Уровень | Задание | Тип | Слов сейчас | Порог | Запас | После удаления | Слова-кандидаты |",
            "|---|---|---|---:|---:|---:|---:|---|",
        ])
        for row in report["affected_tasks"]:
            after = "недостижимо" if row["would_break"] else str(row["margin_after_removal"])
            hits = ", ".join(row["hits"])
            lines.append(f"| {row['level']} | {row['task_id']} | {row['type']} | "
                         f"{row['count']} | {row['needed']} | {row['margin']} | {after} | {hits} |")
        lines.append("")

    lines.extend([
        "## Уровни с запасом меньше или равным 10",
        "",
        "Учитываются задания с числовым порогом (T1/T2/T3/T5/T6/T7): запас = число",
        "подходящих слов минус порог. T4 с точным списком целей имеет запас 0 по",
        "построению и показан отдельно, чтобы не смешивать его с нехваткой словаря.",
        "",
    ])
    if report["narrow_levels"]:
        for row in report["narrow_levels"]:
            tasks = ", ".join(
                f"{task['id']} ({task['type']}: {task['count']} слов, порог {task['needed']}, "
                f"запас {task['margin']}, {format_percent(task['threshold_share'])})"
                for task in row["tasks"]
            )
            lines.append(f"- Уровень {row['level']}: {tasks}.")
    else:
        lines.append("- Нет уровней с запасом ≤ 10.")
    lines.append("")
    if report["exact_target_levels"]:
        exact = ", ".join(
            f"{row['level']} ({', '.join(task['id'] for task in row['tasks'])})"
            for row in report["exact_target_levels"]
        )
        lines.append(f"Точные цели T4 (запас 0 по построению): {exact}.")
        lines.append("")

    lines.extend([
        "## Запас достижимости по всем заданиям",
        "",
        "| Уровень | Задание | Тип | Параметр | Слов | Порог | Запас | Порог/слов |",
        "|---|---|---|---|---:|---:|---:|---:|",
    ])
    for level in report["levels"]:
        for task in level["tasks"]:
            lines.append(f"| {level['number']} | {task['id']} | {task['type']} | "
                         f"{task['parameter']} | {task['count']} | {task['needed']} | "
                         f"{task['margin']} | {format_percent(task['threshold_share'])} |")
    lines.append("")

    lines.extend([
        "## Проверка известных узких мест",
        "",
        "| Задание | Ожидание из постановки | Факт | Статус |",
        "|---|---:|---:|---|",
    ])
    for row in report["known_narrow_tasks"]:
        status = "совпадает" if row["matches"] else "расхождение"
        note = f" — {row['note'].rstrip('.')}" if row["note"] else ""
        lines.append(f"| {row['level']} {row['type']} | {row['expected']} | "
                     f"{row['observed'] if row['observed'] is not None else '—'} | {status}{note} |")
    lines.append("")

    campaign_sha = inputs.get("campaign", {}).get("sha256")
    lines.extend([
        "## Риски чистки",
        "",
        f"Текущий SHA-256 `data/campaign.json`: `{campaign_sha or '—'}`. В приложении этот",
        "хэш является идентификатором кампании (`campaignProgressV2`). Любое изменение",
        "файла кампании — замена `hintTarget`, цели T4, списка T6, порога или окончания —",
        "меняет SHA-256 и даёт `campaign-mismatch`: сохранения игроков по кампании",
        "сбрасываются. Это отдельное продуктовое решение, а не побочный эффект чистки.",
        "",
        "Варианты, которые нужно рассматривать без применения в этой задаче:",
        "",
        "1. **Исключение только из ввода.** Убрать слово из общего словаря ответов,",
        "   сохранив возможность использовать его как назначенную цель. Сейчас роли",
        "   слова не разделены в `words.txt`; для такого варианта нужен отдельный",
        "   механизм ролей, иначе цель станет недостижимой.",
        "2. **Замена цели.** Изменить `hintTarget` T3, цель T4 или список T6 в",
        "   `campaign.json`. Это меняет SHA-256 кампании и сбрасывает сохранения;",
        "   замена требует проверки достижимости и уникальности целей.",
        "3. **Исключение из исходников.** Заменить слово уровня или убрать его из",
        "   источников. Это меняет `campaign.json`, может сделать задания недостижимыми",
        "   и также сбрасывает сохранения.",
        "",
        "Удаление обычного ответа, не назначенного целью, не меняет `campaign.json`, но",
        "уменьшает запас задания. Если после удаления число подходящих слов становится",
        "меньше порога, кампанию всё равно придётся менять. Перед любой чисткой нужны",
        "`validate_campaign`, проверки достижимости, пересчёт хэшей и `:core:test`;",
        "в этой задаче данные не изменялись.",
        "",
    ])
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="DATA-06: аудит словаря (только отчёт)")
    parser.add_argument("--words", type=Path, default=DEFAULT_WORDS)
    parser.add_argument("--campaign", type=Path, default=DEFAULT_CAMPAIGN)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--markdown", type=Path, default=DEFAULT_MARKDOWN)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--check", action="store_true",
                        help="не писать файлы, а сверить их с уже созданным отчётом")
    args = parser.parse_args(argv)

    config = load_config(args.config)
    dictionary = read_words(args.words)
    campaign = read_json(args.campaign)
    inputs = {
        "words": {"path": display_path(args.words), "sha256": sha256_file(args.words)},
        "campaign": {"path": display_path(args.campaign), "sha256": sha256_file(args.campaign)},
        "config": {"path": display_path(args.config), "sha256": sha256_file(args.config)},
    }
    report = build_report(dictionary, campaign, config, inputs=inputs)
    markdown = render_markdown(report)

    if args.check:
        existing_markdown = Path(args.markdown).read_text(encoding="utf-8") \
            if Path(args.markdown).exists() else None
        existing_json = read_json(args.json) if Path(args.json).exists() else None
        expected_json = json.loads(json.dumps(report, ensure_ascii=False))
        if existing_markdown != markdown or existing_json != expected_json:
            print("Отчёт устарел: пересоздайте без --check", file=sys.stderr)
            return 1
        print("Отчёт совпадает с текущими данными")
        return 0

    atomic_text(args.markdown, markdown)
    atomic_json(args.json, report)
    print(f"Написано: {args.markdown}")
    print(f"Написано: {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
