#!/usr/bin/env python3
"""Read-only export of the installed short15 campaign for editorial review.

The export reads only the installed ``data/campaign.json``, ``data/words.txt``,
``data/lexical_data.json`` and ``data/manifest.json``. It performs no network calls,
does not use AI, never writes into ``data/`` and keeps all source paths out of the
payload. JSON is the reproducible machine-readable snapshot; CSV/TSV files are
plain standard-library catalogs for people and do not require Excel.
"""
from __future__ import annotations

import argparse
import lexical_json
import csv
from collections import Counter, defaultdict
from hashlib import sha256
import json
from pathlib import Path
import sys


INPUT_FILES = ("campaign.json", "words.txt", "owned_riddles.json", "manifest.json")
MASK = "***"
WEIGHTS = {3: 3, 4: 2}


def digest(path: Path) -> str:
    h = sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def normalize(word: str) -> str:
    return str(word).strip().lower().replace("ё", "е")


def read_meta_array(value: str) -> list[str]:
    try:
        result = json.loads(value or "[]")
        return [str(item) for item in result] if isinstance(result, list) else []
    except (ValueError, TypeError):
        return []


def formable(source: str, word: str) -> bool:
    source = normalize(source)
    word = normalize(word)
    if not word or word == source:
        return False
    available = Counter(source)
    return not (Counter(word) - available)


def task_criteria(task: dict, count_min_length: int) -> str:
    kind = task["type"]
    if kind in ("T1", "T2", "T6", "T7"):
        condition = {
            "T1": f"слова длиной ≥{count_min_length}",
            "T2": f"на указанную букву (≥{count_min_length})",
            "T6": "слова указанной темы",
            "T7": "с указанной буквой",
        }[kind]
        return f'{condition}: {task["n"]}'
    if kind == "T3":
        return f'любое слово длиной ≥{task["minLength"]}'
    if kind == "T4":
        return f'все {len(task["targets"])} загадки'
    if kind == "T5":
        return f'окончание «{task["ending"]}», длина ≥{task["minLength"]}'
    raise ValueError("Unknown task type: " + str(kind))


def applicable(word: str, task: dict, count_min_length: int) -> bool:
    typ = task["type"]
    if typ == "T1":
        return len(word) >= count_min_length
    if typ == "T2":
        return len(word) >= count_min_length and word.startswith(task["letter"])
    if typ == "T3":
        return len(word) >= task["minLength"]
    if typ == "T4":
        return word in task["targets"]
    if typ == "T5":
        return len(word) >= task["minLength"] and word.endswith(task["ending"])
    if typ == "T6":
        return word in task["words"]
    if typ == "T7":
        return task["letter"] in word
    return False


def _display(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.name


def _read_json(path: Path, root: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise ValueError(f"Некорректный JSON: {_display(path, root)}") from error
    if not isinstance(value, dict):
        raise ValueError(f"Ожидался JSON-объект: {_display(path, root)}")
    return value


def _validate_campaign(campaign: dict, manifest: dict, stars: int, campaign_sha: str) -> None:
    levels = campaign.get("levels")
    if not isinstance(levels, list) or not levels:
        raise ValueError("campaign.json не содержит уровней")
    if [level.get("number") for level in levels] != list(range(1, len(levels) + 1)):
        raise ValueError("Уровни должны идти подряд с 1")
    stage_numbers = []
    for level in levels:
        if not isinstance(level.get("word"), str) or not level["word"].strip():
            raise ValueError(f'Уровень {level.get("number")}: нет исходного слова')
        tasks = level.get("tasks")
        if not isinstance(tasks, list) or not tasks:
            raise ValueError(f'Уровень {level.get("number")}: нет заданий')
        if not isinstance(level.get("stage"), int):
            raise ValueError(f'Уровень {level.get("number")}: нет этапа')
        stage_numbers.append(level["stage"])
    stages = campaign.get("stages") or []
    if stages:
        if [stage.get("number") for stage in stages] != list(range(1, len(stages) + 1)):
            raise ValueError("Этапы должны идти подряд с 1")
        covered = [False] * len(levels)
        for stage in stages:
            first, last = stage.get("firstLevel"), stage.get("lastLevel")
            if not isinstance(first, int) or not isinstance(last, int) or not 1 <= first <= last <= len(levels):
                raise ValueError("Некорректные границы этапа")
            for number in range(first, last + 1):
                if covered[number - 1] or stage_numbers[number - 1] != stage["number"]:
                    raise ValueError("Этапы не покрывают уровни ровно один раз")
                covered[number - 1] = True
        if not all(covered):
            raise ValueError("Этапы не покрывают все уровни")
    expected_levels = (manifest.get("campaign_source") or {}).get("levels")
    if expected_levels is not None and expected_levels != len(levels):
        raise ValueError("Число уровней расходится с manifest.campaign_source.levels")
    expected_stars = (manifest.get("campaign_source") or {}).get("max_stars")
    if expected_stars is not None and expected_stars != stars:
        raise ValueError("Число заданий/звёзд расходится с manifest.campaign_source.max_stars")
    expected_sha = (manifest.get("campaign_source") or {}).get("sha256")
    if expected_sha is not None and expected_sha != campaign_sha:
        raise ValueError("SHA-256 campaign.json расходится с manifest.campaign_source.sha256")
    ids = set()
    for level in levels:
        for task in level["tasks"]:
            task_id = task.get("id")
            if not isinstance(task_id, str) or not task_id.strip() or task_id in ids:
                raise ValueError(f'Уровень {level["number"]}: пустой или повторный ID задания')
            ids.add(task_id)
            if task.get("type") not in {f"T{i}" for i in range(1, 8)}:
                raise ValueError(f"{task_id}: неизвестный тип задания")
            if task["type"] in ("T1", "T2", "T6", "T7") and not isinstance(task.get("n"), int):
                raise ValueError(f"{task_id}: отсутствует порог n")
            if task["type"] in ("T3", "T5") and not isinstance(task.get("minLength"), int):
                raise ValueError(f"{task_id}: отсутствует minLength")
            if task["type"] in ("T2", "T7") and not isinstance(task.get("letter"), str):
                raise ValueError(f"{task_id}: отсутствует буква")
            if task["type"] == "T3" and not isinstance(task.get("hintTarget"), str):
                raise ValueError(f"{task_id}: отсутствует hintTarget")
            if task["type"] == "T4" and not isinstance(task.get("targets"), list):
                raise ValueError(f"{task_id}: отсутствуют targets")
            if task["type"] == "T5" and not isinstance(task.get("ending"), str):
                raise ValueError(f"{task_id}: отсутствует окончание")
            if task["type"] == "T6":
                if not isinstance(task.get("category"), str) or not isinstance(task.get("words"), list):
                    raise ValueError(f"{task_id}: отсутствуют category/words")


def _short_repeats(local_words: dict[int, list[str]]) -> dict:
    """Report repetition of 3/4-letter legal answers across all 15 levels."""
    numbers = sorted(local_words)
    adjacent = []
    for left, right in zip(numbers, numbers[1:]):
        shared = sorted(set(local_words[left]) & set(local_words[right]))
        short = [word for word in shared if 3 <= len(word) <= 4]
        adjacent.append({
            "left": left,
            "right": right,
            "words": short,
            "weighted": sum(WEIGHTS.get(len(word), 1) for word in shared),
            "short_weighted": sum(WEIGHTS.get(len(word), 0) for word in shared),
        })
    occurrences = Counter(
        word for number in numbers for word in local_words[number] if 3 <= len(word) <= 4
    )
    rows = [
        {"word": word, "levels": count, "weighted": count * WEIGHTS.get(len(word), 0)}
        for word, count in sorted(occurrences.items(), key=lambda row: (-row[1], -WEIGHTS.get(len(row[0]), 0), row[0]))
        if count >= 2
    ]
    top = [row for row in rows[:20]]
    frequent = [row for row in rows if row["levels"] >= 5]
    return {
        "basis": "all legal buildable answers; 3/4-letter words weighted 3/2, longer words 1",
        "weights": {"3": 3, "4": 2, "longer": 1},
        "weighted_adjacent": sum(row["weighted"] for row in adjacent),
        "short_weighted_adjacent": sum(row["short_weighted"] for row in adjacent),
        "adjacent_pairs": adjacent,
        "repeated_words": rows,
        "repeated_on_at_least_5_levels": frequent,
        "top20": top,
    }


def _manifest_checks(manifest: dict, words: list[str], words_sha: str, database_sha: str,
                     origins: dict[str, int], defined_words: int) -> None:
    expected_words = manifest.get("words")
    if expected_words is not None and expected_words != len(words):
        raise ValueError("Число слов расходится с manifest.words")
    expected_words_sha = manifest.get("sha256_words_txt")
    if expected_words_sha is not None and expected_words_sha != words_sha:
        raise ValueError("SHA-256 words.txt расходится с manifest.sha256_words_txt")
    definitions = manifest.get("owned_riddles") or manifest.get("definitions") or {}
    expected_database_sha = definitions.get("sha256")
    if expected_database_sha is not None and expected_database_sha != database_sha:
        raise ValueError("SHA-256 lexical_data.json расходится с manifest.definitions.sha256")
    dictionary_sha = definitions.get("dictionary_words_sha256")
    if dictionary_sha is not None and dictionary_sha != words_sha:
        raise ValueError("lexical_data.json в manifest относится к другому словарю")
    if "owned_riddles" in manifest and definitions.get("count") != defined_words:
        raise ValueError("Riddle count differs from manifest")
    counts = definitions.get("counts") or {}
    if counts:
        for origin in sorted(set(counts) | set(origins)):
            if counts.get(origin, 0) != origins.get(origin, 0):
                raise ValueError(f"Число определений {origin!r} расходится с manifest")
    coverage = definitions.get("coverage") or {}
    if coverage.get("total") is not None and coverage["total"] != len(words):
        raise ValueError("manifest.definitions.coverage.total расходится с words.txt")
    if coverage.get("words") is not None and coverage["words"] != defined_words:
        raise ValueError("manifest.definitions.coverage.words расходится с lexical_data.json")


def export(root: Path) -> dict:
    root = Path(root).resolve()
    paths = {name: root / "data" / name for name in INPUT_FILES}
    for path in paths.values():
        if not path.is_file():
            raise FileNotFoundError(f"Required installed input is absent: {_display(path, root)}")
    campaign = _read_json(paths["campaign.json"], root)
    manifest = _read_json(paths["manifest.json"], root)
    campaign_sha = digest(paths["campaign.json"])
    words_sha = digest(paths["words.txt"])
    database_sha = digest(paths["owned_riddles.json"])
    manifest_sha = digest(paths["manifest.json"])

    all_words = sorted({normalize(line) for line in paths["words.txt"].read_text(encoding="utf-8").splitlines()
                        if normalize(line)})
    words = [word for word in all_words if len(word) >= 3]
    count_min_length = int(campaign.get("countMinLength", 4))
    stars = sum(len(level.get("tasks") or []) for level in campaign.get("levels") or [])
    _validate_campaign(campaign, manifest, stars, campaign_sha)

    tools_directory = Path(__file__).resolve().parent
    if str(tools_directory) not in sys.path:
        sys.path.insert(0, str(tools_directory))
    from hint_text import prepare  # existing project masking rule

    document = lexical_json.review_data(paths['owned_riddles.json'])
    metadata = document['word_metadata']
    definitions = defaultdict(list)
    for row in document['definitions']:
        definitions[row['word']].append(row)
    counts = Counter(row['origin'] for row in document['definitions'])
    db_origins = [dict(origin=origin, count=count) for origin,count in sorted(counts.items())]
    defined_words = len(definitions)
    origins = {row["origin"]: row["count"] for row in db_origins}
    _manifest_checks(manifest, all_words, words_sha, database_sha, origins, defined_words)

    local_words = {}
    seen_levels = Counter()
    for level in campaign["levels"]:
        accepted = [word for word in words if formable(level["word"], word)]
        local_words[level["number"]] = accepted
        seen_levels.update(accepted)

    overview, task_rows, answer_rows, hint_rows, edit_rows = [], [], [], [], []
    for level in campaign["levels"]:
        num, source = level["number"], level["word"]
        tasks = level["tasks"]
        source_meta = metadata.get(source, {})
        overview.append({
            "level": num, "stage": level["stage"], "source": source,
            "R": level["requiredStars"], "M": len(tasks),
            "accepted": len(local_words[num]), "tasks": ", ".join(task["type"] for task in tasks),
            "source_category": source_meta.get("category", ""),
            "source_category_source": source_meta.get("category_source", ""),
            "source_definitions": len(definitions.get(source, ())),
        })
        hints_needed = defaultdict(set)
        priority_words = set()
        for task in tasks:
            typ = task["type"]
            targets = list(task.get("targets", []))
            if typ == "T3":
                targets = [task["hintTarget"]]
            elif typ == "T6":
                targets = list(task["words"])
            if typ == "T3":
                recounted = [word for word in local_words[num] if len(word) >= task["minLength"]]
            elif typ == "T4":
                recounted = sorted(set(targets) & set(local_words[num]))
            elif typ == "T6":
                recounted = [
                    word for word in local_words[num]
                    if metadata.get(word, {}).get("category") == task["category"]
                    and not str(metadata.get(word, {}).get("category_source", "")).startswith("unknown:")
                ]
            elif typ == "T1":
                recounted = [word for word in local_words[num] if len(word) >= count_min_length]
            elif typ == "T2":
                recounted = [word for word in local_words[num]
                             if len(word) >= count_min_length and word.startswith(task["letter"])]
            elif typ == "T5":
                recounted = [word for word in local_words[num]
                             if len(word) >= task["minLength"] and word.endswith(task["ending"])]
            else:
                recounted = [word for word in local_words[num] if task["letter"] in word]
            task_rows.append({
                "level": num, "source": source, "task_id": task["id"], "type": typ,
                "criteria": task_criteria(task, count_min_length), "letter": task.get("letter", ""),
                "ending": task.get("ending", ""), "minLength": task.get("minLength", ""),
                "n": task.get("n", ""), "targets": ", ".join(targets),
                "category": task.get("category", ""), "target_count": len(targets),
                "recounted_count": len(recounted),
                "recounted_targets": ", ".join(recounted) if typ in ("T3", "T4", "T6") else "",
            })
            for target in targets:
                if typ in ("T3", "T4", "T6"):
                    hints_needed[target].add(typ + " " + task["id"])
                    priority_words.add(target)
                    edit_rows.append({
                        "level": num, "task_id": task["id"], "type": typ,
                        "word": target, "action": "", "new_value": "",
                        "reason": "", "rights_status": "", "evidence_url": ""
                    })
            if not targets:
                edit_rows.append({
                    "level": num, "task_id": task["id"], "type": typ,
                    "word": "", "action": "", "new_value": "",
                    "reason": "", "rights_status": "", "evidence_url": ""
                })

        for word in local_words[num]:
            meta = metadata.get(word, {})
            task_types = [task["type"] for task in tasks if applicable(word, task, count_min_length)]
            answer_rows.append({
                "level": num, "source": source, "word": word, "length": len(word),
                "repeat_levels": seen_levels[word],
                "roles": ", ".join(task_types),
                "priority": ("цель" if word in priority_words else
                             "короткий повтор" if len(word) <= 4 and seen_levels[word] >= 5
                             else "обычный ответ"),
                "category": meta.get("category", ""),
                "category_source": meta.get("category_source", ""),
                "definitions": len(definitions.get(word, ())),
                "origin": ",".join(sorted({d["origin"] for d in definitions.get(word, ())}))
            })
        for word, roles in sorted(hints_needed.items()):
            meta = metadata.get(word, {})
            forms = read_meta_array(meta.get("forms", "[]"))
            related = read_meta_array(meta.get("related", "[]"))
            meanings = definitions.get(word, ())
            if not meanings:
                meanings = [{"id": "", "ord": "", "text": "", "source": "", "origin": ""}]
            for definition in meanings:
                cleaned = prepare(word, definition["text"], forms, related) if definition["text"] else None
                hint_rows.append({
                    "level": num, "source": source, "word": word,
                    "roles": ", ".join(sorted(roles)),
                    "definition_id": definition["id"], "definition_order": definition["ord"],
                    "raw_text": definition["text"], "safe_preview": cleaned or "",
                    "safe": bool(cleaned), "origin": definition["origin"],
                    "source_url": definition["source"],
                    "category": meta.get("category", ""),
                    "category_source": meta.get("category_source", ""),
                    "mask": MASK,
                })

    definitions_block = manifest.get("owned_riddles") or manifest.get("definitions") or {}
    audit = definitions_block.get("audit") or {}
    rights_review = definitions_block.get("rights_review") or {}
    rights = {
        "policy": definitions_block.get("rights_policy"),
        "status": rights_review.get("status"),
        "audit_passed": bool(audit.get("passed")),
        "rights_review_passed": bool(rights_review.get("passed")),
        "replacement_required": rights_review.get("replacement_required"),
        "wiktionary_definitions": origins.get("wiktionary", 0),
        "own_definitions": origins.get("own", 0),
        "manifest_counts": definitions_block.get("counts") or {},
        "actual_counts": dict(sorted(origins.items())),
        "coverage": definitions_block.get("coverage") or {},
        "actual_defined_words": defined_words,
        "audit_and_rights_passed": bool(audit.get("passed")) and bool(rights_review.get("passed")),
        "publication_allowed": False,
        "publication_note": "публикация требует отдельного решения владельца и пройденного ручного аудита",
    }
    short_repeats = _short_repeats(local_words)
    return {
        "schema": "short15-editorial-export-v1",
        "snapshot": {
            "campaign_sha256": campaign_sha,
            "dictionary_sha256": words_sha,
            "definitions_sha256": database_sha,
            "manifest_sha256": manifest_sha,
            "inputs": {
                "campaign.json": campaign_sha,
                "words.txt": words_sha,
                "owned_riddles.json": database_sha,
                "manifest.json": manifest_sha,
            },
            "campaign_id": manifest.get("campaign_source", {}),
            "definitions_rights": definitions_block,
            "rights": rights,
            "database_origins": db_origins,
            "accepted_rows": len(answer_rows), "stars": stars,
            "word_count": len(all_words), "answer_word_count": len(words),
            "hint_rows": len(hint_rows),
            "count_min_length": count_min_length,
        },
        "overview": overview, "tasks": task_rows, "answers": answer_rows,
        "hints": hint_rows, "short_repeats": short_repeats, "edits": edit_rows
    }


TABLES = {
    "levels": ("overview", ("level", "stage", "source", "R", "M", "accepted", "tasks",
                            "source_category", "source_category_source", "source_definitions")),
    "tasks": ("tasks", ("level", "source", "task_id", "type", "criteria", "letter", "ending",
                        "minLength", "n", "targets", "category", "target_count",
                        "recounted_count", "recounted_targets")),
    "answers": ("answers", ("level", "source", "word", "length", "repeat_levels", "roles",
                            "priority", "category", "category_source", "definitions", "origin")),
    "hints": ("hints", ("level", "source", "word", "roles", "definition_id", "definition_order",
                        "raw_text", "safe_preview", "safe", "origin", "source_url", "category",
                        "category_source", "mask")),
    "edits": ("edits", ("level", "task_id", "type", "word", "action", "new_value", "reason",
                        "rights_status", "evidence_url")),
}


def _write_table(path: Path, rows: list[dict], fields: tuple[str, ...], delimiter: str) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter=delimiter,
                                lineterminator="\n", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def write_catalogs(payload: dict, directory: Path) -> list[str]:
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    written = []
    for name, (key, fields) in TABLES.items():
        rows = payload[key]
        for suffix, delimiter in (("csv", ","), ("tsv", "\t")):
            path = directory / f"{name}.{suffix}"
            _write_table(path, rows, fields, delimiter)
            written.append(path.name)
    repeat_fields = ("word", "levels", "weighted")
    for suffix, delimiter in (("csv", ","), ("tsv", "\t")):
        path = directory / f"short_repeats.{suffix}"
        _write_table(path, payload["short_repeats"]["repeated_words"], repeat_fields, delimiter)
        written.append(path.name)
    adjacent_fields = ("left", "right", "words", "weighted", "short_weighted")
    adjacent = [dict(row, words=" ".join(row["words"])) for row in payload["short_repeats"]["adjacent_pairs"]]
    for suffix, delimiter in (("csv", ","), ("tsv", "\t")):
        path = directory / f"short_repeats_adjacent.{suffix}"
        _write_table(path, adjacent, adjacent_fields, delimiter)
        written.append(path.name)
    return written


def write_outputs(payload: dict, root: Path, output: Path, catalog_dir: Path | None = None) -> tuple[Path, Path]:
    root, output = Path(root).resolve(), Path(output)
    data_directory = (root / "data").resolve()
    if output.suffix.lower() == ".json":
        json_path = output
        directory = Path(catalog_dir) if catalog_dir is not None else output.parent
    else:
        directory = output
        json_path = output / "curation_snapshot.json"
    for candidate in (json_path, directory):
        resolved = candidate.resolve()
        if resolved == data_directory or data_directory in resolved.parents:
            raise ValueError("Запрещено записывать экспорт в data/")
    directory.mkdir(parents=True, exist_ok=True)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
                         encoding="utf-8")
    write_catalogs(payload, directory)
    return json_path, directory


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="project root")
    parser.add_argument("--output", "--out", type=Path, required=True,
                        help="JSON file or a directory for curation_snapshot.json")
    parser.add_argument("--catalog-dir", type=Path,
                        help="CSV/TSV directory; defaults to the JSON directory")
    args = parser.parse_args(argv)
    try:
        payload = export(args.root.resolve())
        json_path, directory = write_outputs(payload, args.root, args.output, args.catalog_dir)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(2, f"ERROR: {error}\n")
    print(f'Exported {len(payload["overview"])} levels, {len(payload["tasks"])} tasks, '
          f'{len(payload["answers"])} accepted answer rows, {len(payload["hints"])} definition rows; '
          f'JSON={json_path.name}; catalogs={directory.name}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
