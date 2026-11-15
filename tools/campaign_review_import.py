#!/usr/bin/env python3
"""Stage reviewed short15 editorial changes; never overwrite shipped game data.

The importer reads the exported snapshot, the installed read-only game inputs and
the editable ``CAMPAIGN_REVIEW_15.xlsx`` workbook.  Only the ``Правки`` sheet is
interpreted as editorial input.  The result is a separate candidate campaign,
a machine-readable diff/report and an optional pending hint file under a staging
directory (``build/reports/curation/`` by default in the documented command).

Nothing in ``data/`` is written.  Definitions, categories, rights metadata and
the definitions database stay read-only.  ``HINT_REPLACE`` is deliberately kept
outside the candidate and outside the database until a separate owner decision.
"""
from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import posixpath
import re
import sqlite3
import subprocess
import sys
from xml.etree import ElementTree as ET
from zipfile import BadZipFile, ZipFile


NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
DOC_REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PKG_REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"
FIELDS = ("level", "task_id", "type", "word", "action", "new_value", "reason",
          "rights_status", "evidence_url")
HEADERS = ("Уровень", "ID задания", "Тип", "Текущее слово", "Действие",
           "Новое значение", "Основание", "Права/статус", "Источник/URL")
ACTIONS = {"T6_ADD", "T6_REMOVE", "SET_N", "SET_R", "T4_SWAP", "T3_SWAP",
           "HINT_REPLACE"}
PROTECTED_OUTPUTS = ("data", "orchestrator", ".git", "app/src", "core/src", "tools")
T4_STOPWORDS = frozenset('''
есть они она оно така так том рад вид дело раз три два один это все всё как что кто где когда там тут уже еще ещё
или но да нет не ни же ли бы мы вы я он его ее её их нас вас себя свой своя свое своё этот тот та то те весь вся
какой такой чей который которые потому поэтому также тоже только даже ведь вот вон ну уж мол де ага ого под пот лет
эта эти этих тех этого эту этой этом этим ими нам вами нами мной тобой кем чем кого чего кому чему перед через между
над без для при про около после до от по из у к о об во на за с со а и абы дабы либо нежели чем
'''.split())


def sha256_file(path: Path) -> str:
    digest = sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def word_norm(value: object) -> str:
    return str(value).strip().lower().replace("ё", "е")


def canonical_word(value: object, label: str) -> str:
    word = word_norm(value)
    if not re.fullmatch(r"[а-я]+", word):
        raise ValueError(f"{label}: ожидалось русское слово, получено {value!r}")
    if not 3 <= len(word) <= 30:
        raise ValueError(f"{label}: длина слова должна быть 3..30, получено {len(word)}")
    return word


def formable(source: str, word: str) -> bool:
    source, word = word_norm(source), word_norm(word)
    return bool(word) and word != source and not (Counter(word) - Counter(source))


def parse_positive_integer(text: str, label: str) -> int:
    value = str(text).strip()
    if not re.fullmatch(r"[0-9]+(?:\.0+)?", value):
        raise ValueError(f"{label}: ожидалось целое число, получено {text!r}")
    number = int(float(value))
    if number <= 0:
        raise ValueError(f"{label}: число должно быть положительным")
    return number


def _cell_value(cell, shared: list[str]) -> str:
    if cell.find(f"{NS}f") is not None:
        raise ValueError(f"Формулы запрещены на листе «Правки»: {cell.attrib.get('r', '')}")
    kind = cell.attrib.get("t")
    raw = cell.find(f"{NS}v")
    if kind == "inlineStr":
        content = cell.find(f"{NS}is")
        return ("".join(node.text or "" for node in content.iter(f"{NS}t"))
                if content is not None else "").strip()
    if kind == "s" and raw is not None:
        try:
            return shared[int(raw.text or "0")].strip()
        except (ValueError, IndexError) as error:
            raise ValueError("Повреждённая таблица общих строк XLSX") from error
    return (raw.text or "").strip() if raw is not None else ""


def xlsx_rows(path: Path, sheet_name: str = "Правки", *,
              with_header: bool = False):
    """Extract the editable sheet with the Python standard library only.

    By default returns ``{row_number: [A..I]}`` for compatibility with the
    prepared editor tooling.  ``with_header=True`` additionally returns the
    detected header row.  Cells outside A..I are ignored.  Formulas are rejected
    instead of being evaluated.  Other workbook sheets are never used.
    """
    try:
        with ZipFile(path) as archive:
            workbook = ET.fromstring(archive.read("xl/workbook.xml"))
            relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
            targets = {entry.attrib["Id"]: entry.attrib["Target"]
                       for entry in relationships.findall(f"{PKG_REL}Relationship")}
            sheets = workbook.find(f"{NS}sheets")
            if sheets is None:
                raise ValueError("XLSX не содержит листов")
            sheet = next((entry for entry in sheets
                          if entry.attrib.get("name") == sheet_name), None)
            if sheet is None:
                raise ValueError(f"Не найден лист XLSX «{sheet_name}»")
            relation = sheet.attrib.get(DOC_REL + "id")
            if relation not in targets:
                raise ValueError(f"Нет связи для листа «{sheet_name}»")
            target = targets[relation].replace("\\", "/")
            if target.startswith("/"):
                sheet_path = target.lstrip("/")
            else:
                sheet_path = posixpath.normpath(posixpath.join("xl", target))
            if not sheet_path.startswith("xl/") or sheet_path not in archive.namelist():
                raise ValueError(f"Некорректный путь листа XLSX: {sheet_path}")
            shared: list[str] = []
            if "xl/sharedStrings.xml" in archive.namelist():
                for entry in ET.fromstring(archive.read("xl/sharedStrings.xml")).findall(f"{NS}si"):
                    shared.append("".join(node.text or ""
                                          for node in entry.iter(f"{NS}t")))
            root = ET.fromstring(archive.read(sheet_path))
            data = root.find(f"{NS}sheetData")
            if data is None:
                raise ValueError(f"Лист «{sheet_name}» пуст")
            result: dict[int, list[str]] = {}
            for row in data.findall(f"{NS}row"):
                row_number = int(row.attrib["r"])
                values = [""] * 9
                for cell in row.findall(f"{NS}c"):
                    reference = cell.attrib.get("r", "")
                    match = re.match(r"^([A-Z]+)[0-9]+$", reference)
                    if not match:
                        continue
                    column = 0
                    for letter in match.group(1):
                        column = column * 26 + ord(letter) - ord("A") + 1
                    if 1 <= column <= 9:
                        values[column - 1] = _cell_value(cell, shared)
                result[row_number] = values
    except (BadZipFile, KeyError, ET.ParseError) as error:
        raise ValueError(f"Не удалось прочитать XLSX: {path.name}") from error
    header_row = None
    for number in sorted(result):
        if tuple(result[number][:9]) == HEADERS:
            header_row = number
            break
    if header_row is None:
        raise ValueError("Не найдена строка заголовков A–I листа «Правки»")
    return (result, header_row) if with_header else result


def _resolve(root: Path, value: Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else (root / path)


def _ensure_output(root: Path, output: Path) -> None:
    resolved = output.resolve()
    if resolved == root:
        raise ValueError("Запрещено писать staging-результат в корень проекта")
    for relative in PROTECTED_OUTPUTS:
        protected = (root / relative).resolve()
        if resolved == protected or protected in resolved.parents:
            raise ValueError(f"Запрещено писать staging-результат в {relative}/")
    if resolved.exists() and not resolved.is_dir():
        raise ValueError("--output должен быть каталогом staging")


def _json_list(value: object) -> list[str]:
    try:
        parsed = json.loads(str(value or "[]"))
    except (TypeError, ValueError):
        return []
    return [str(item) for item in parsed] if isinstance(parsed, list) else []


def _shares_root(left: str, right: str) -> bool:
    left, right = word_norm(left), word_norm(right)
    shorter = min(len(left), len(right))
    if shorter < 3:
        return bool(left) and left == right
    common = 0
    for a, b in zip(left, right):
        if a != b:
            break
        common += 1
    return common >= 4 or common / shorter >= 0.7


def _prepare_hint():
    tools = str(Path(__file__).resolve().parent)
    if tools not in sys.path:
        sys.path.insert(0, tools)
    from hint_text import prepare
    return prepare


def _safe_hint_for_db(db: sqlite3.Connection, word: str):
    """Return the first safe project hint for a word, or None."""
    row = db.execute("SELECT forms,related FROM word_metadata WHERE word=?", (word,)).fetchone()
    forms = _json_list(row["forms"]) if row is not None else []
    related = _json_list(row["related"]) if row is not None else []
    prepare = _prepare_hint()
    for row in db.execute("SELECT text FROM definitions WHERE word=? ORDER BY ord,id", (word,)):
        text = row["text"] or ""
        if not text:
            continue
        safe = prepare(word, text, forms, related)
        if safe:
            return safe
    return None


def _validate_hint_text(db: sqlite3.Connection, word: str, text: str) -> str:
    """Reject a hint that is unusable, contradictory or reveals its answer."""
    text = str(text).strip()
    if not text:
        raise ValueError("HINT_REPLACE: пустой текст подсказки")
    row = db.execute("SELECT forms,related FROM word_metadata WHERE word=?", (word,)).fetchone()
    forms = _json_list(row["forms"]) if row is not None else []
    related = _json_list(row["related"]) if row is not None else []
    prepare = _prepare_hint()
    safe = prepare(word, text, forms, related)
    if safe is None:
        raise ValueError("HINT_REPLACE: подсказка небезопасна или неинформативна")
    family = {word_norm(item) for item in [word, *forms, *related] if word_norm(item)}
    tokens = re.findall(r"[а-я]+", word_norm(text))
    for token in tokens:
        if token in family or any(_shares_root(token, base) for base in family):
            raise ValueError(f"HINT_REPLACE: подсказка раскрывает ответ {word!r}")
    return safe


def _task_old_words(task: dict) -> set[str]:
    kind = task.get("type")
    if kind == "T3":
        value = task.get("hintTarget")
        return {word_norm(value)} if isinstance(value, str) and value else set()
    if kind == "T4":
        return {word_norm(value) for value in task.get("targets", [])
                if isinstance(value, str) and value}
    if kind == "T6":
        return {word_norm(value) for value in task.get("words", [])
                if isinstance(value, str) and value}
    return set()


def _evidence_ok(value: str) -> bool:
    text = str(value).strip()
    if not text or text.lower() in {"нет", "-", "—", "не подтверждено"}:
        return False
    return bool(re.match(r"^(?:https?://|www\.)\S+", text, re.I)) or len(text) >= 4


def _load_snapshot(path: Path) -> dict:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise ValueError(f"Некорректный JSON снимка: {path.name}") from error
    if not isinstance(document, dict) or not isinstance(document.get("snapshot"), dict):
        raise ValueError("Снимок не содержит объекта snapshot")
    edits = document.get("edits")
    if not isinstance(edits, list):
        raise ValueError("Снимок не содержит списка edits")
    for index, row in enumerate(edits):
        if not isinstance(row, dict) or not {"level", "task_id", "type", "word"} <= set(row):
            raise ValueError(f"Некорректная строка edits[{index}] в снимке")
    return document


def _verify_inputs(root: Path, snapshot_path: Path, workbook_path: Path,
                   snapshot: dict, expected_workbook_sha256: str | None,
                   expected_snapshot_sha256: str | None) -> tuple[dict, dict]:
    data = root / "data"
    files = {
        "campaign.json": data / "campaign.json",
        "words.txt": data / "words.txt",
        "definitions.db": data / "definitions.db",
        "manifest.json": data / "manifest.json",
    }
    for name, path in files.items():
        if not path.is_file():
            raise FileNotFoundError(f"Нет обязательного входа data/{name}")
    if not snapshot_path.is_file():
        raise FileNotFoundError(f"Нет входного снимка: {snapshot_path.name}")
    if not workbook_path.is_file():
        raise FileNotFoundError(f"Нет входной книги: {workbook_path.name}")

    hashes = {name: sha256_file(path) for name, path in files.items()}
    hashes["snapshot"] = sha256_file(snapshot_path)
    hashes["workbook"] = sha256_file(workbook_path)
    if expected_snapshot_sha256 and hashes["snapshot"] != expected_snapshot_sha256.strip().lower():
        raise ValueError("SHA-256 снимка не совпадает с ожидаемым")
    if expected_workbook_sha256 and hashes["workbook"] != expected_workbook_sha256.strip().lower():
        raise ValueError("SHA-256 книги не совпадает с ожидаемым")

    snapshot_block = snapshot["snapshot"]
    required_hashes = {
        "campaign.json": "campaign_sha256",
        "words.txt": "dictionary_sha256",
        "definitions.db": "definitions_sha256",
        "manifest.json": "manifest_sha256",
    }
    for name, key in required_hashes.items():
        expected = snapshot_block.get(key)
        if not isinstance(expected, str) or not expected:
            raise ValueError(f"Снимок не содержит {key}")
        if hashes[name] != expected:
            raise ValueError(f"SHA-256 {name} не совпадает со снимком")
    inputs = snapshot_block.get("inputs") or {}
    if isinstance(inputs, dict):
        for name in files:
            expected = inputs.get(name)
            if expected is not None and expected != hashes[name]:
                raise ValueError(f"SHA-256 {name} не совпадает со snapshot.inputs")
    workbook_pin = snapshot_block.get("workbook_sha256")
    if isinstance(workbook_pin, str) and workbook_pin and workbook_pin != hashes["workbook"]:
        raise ValueError("SHA-256 книги не совпадает со снимком")

    try:
        manifest = json.loads(files["manifest.json"].read_text(encoding="utf-8"))
    except ValueError as error:
        raise ValueError("Некорректный data/manifest.json") from error
    if not isinstance(manifest, dict):
        raise ValueError("data/manifest.json должен быть объектом")
    campaign_source = manifest.get("campaign_source") or {}
    if not isinstance(campaign_source, dict):
        raise ValueError("manifest.campaign_source должен быть объектом")
    expected_campaign = campaign_source.get("sha256")
    if expected_campaign and expected_campaign != hashes["campaign.json"]:
        raise ValueError("SHA-256 campaign.json расходится с manifest.campaign_source.sha256")
    expected_words = manifest.get("sha256_words_txt")
    if expected_words and expected_words != hashes["words.txt"]:
        raise ValueError("SHA-256 words.txt расходится с manifest.sha256_words_txt")
    definitions = manifest.get("definitions") or {}
    if not isinstance(definitions, dict):
        raise ValueError("manifest.definitions должен быть объектом")
    expected_database = definitions.get("sha256")
    if expected_database and expected_database != hashes["definitions.db"]:
        raise ValueError("SHA-256 definitions.db расходится с manifest.definitions.sha256")
    return hashes, manifest


def _normalize_level_cell(value: str):
    text = str(value).strip()
    if not text:
        return ""
    if re.fullmatch(r"[0-9]+(?:\.0+)?", text):
        return int(float(text))
    return text


def _identity_actual(values: list[str]) -> list[object]:
    return [_normalize_level_cell(values[0]), values[1].strip(),
            values[2].strip().upper(), word_norm(values[3])]


def _identity_expected(row: dict) -> list[object]:
    return [row["level"], str(row["task_id"]), str(row["type"]).upper(),
            word_norm(row["word"])]


def _validate_direct_identity(values: list[str], task: dict, level: dict) -> None:
    if not values[0].strip() or not values[2].strip():
        raise ValueError("Строка правки должна содержать уровень и тип задания")
    if _normalize_level_cell(values[0]) != level["number"]:
        raise ValueError(f'{task["id"]}: номер уровня не соответствует заданию')
    if values[2].strip().upper() != task["type"]:
        raise ValueError(f'{task["id"]}: тип не соответствует заданию')
    old = word_norm(values[3])
    if old and old not in _task_old_words(task):
        raise ValueError(f'{task["id"]}: старое слово {old!r} отсутствует в снимке')


def _apply_edits(campaign: dict, snapshot: dict, rows: dict[int, list[str]],
                 header_row: int, dictionary: set[str], db: sqlite3.Connection,
                 categories: dict[str, str], category_sources: dict[str, str],
                 defined: set[str]) -> tuple[dict, list, list, list, set[int]]:
    candidate = deepcopy(campaign)
    levels = {level["number"]: level for level in candidate["levels"]}
    tasks = {task["id"]: (level, task) for level in candidate["levels"]
             for task in level["tasks"]}
    snapshot_edits = snapshot["edits"]
    data_rows = [(number, values) for number, values in sorted(rows.items())
                 if number > header_row]
    template_mode = len(data_rows) >= len(snapshot_edits)
    direct_start = header_row + 1
    if template_mode:
        direct_start = header_row + 1 + len(snapshot_edits)
        for offset, expected in enumerate(snapshot_edits):
            number = header_row + 1 + offset
            values = rows.get(number, [""] * 9)
            if _identity_actual(values) != _identity_expected(expected):
                raise ValueError(
                    f"Строка {number}: изменены защищённые колонки A–D; "
                    f"было {_identity_expected(expected)}, стало {_identity_actual(values)}")

    applied: list[dict] = []
    review_flags: list[dict] = []
    hint_overrides: list[dict] = []
    hint_texts: dict[tuple[str, str], str] = {}
    affected_levels: set[int] = set()

    for number, values in data_rows:
        if not any(str(value).strip() for value in values):
            continue
        action = values[4].strip().upper()
        if not action:
            if number >= direct_start and any(str(value).strip() for value in values[:4]):
                task_id = values[1].strip()
                if task_id not in tasks:
                    raise ValueError(f"Строка {number}: неизвестный ID задания {task_id!r}")
                level, task = tasks[task_id]
                _validate_direct_identity(values, task, level)
            continue
        if action not in ACTIONS:
            raise ValueError(f"Строка {number}: неизвестное действие {action!r}")
        reason = values[6].strip()
        if not reason:
            raise ValueError(f"Строка {number}: заполните основание изменения")
        task_id = values[1].strip()
        if task_id not in tasks:
            raise ValueError(f"Строка {number}: неизвестный ID задания {task_id!r}")
        level, task = tasks[task_id]
        if number >= direct_start:
            _validate_direct_identity(values, task, level)
        # In template mode A–D were already checked row by row above.
        old = word_norm(values[3])
        new_raw = values[5].strip()
        rights_status = values[7].strip()
        evidence = values[8].strip()
        label = f"Строка {number}, {task_id}"
        before: object = old
        after: object = new_raw if action == "HINT_REPLACE" else word_norm(new_raw)

        if action == "T6_ADD":
            if task["type"] != "T6":
                raise ValueError(f"{label}: T6_ADD требует задание T6")
            if old:
                raise ValueError(f"{label}: для T6_ADD колонка «Текущее слово» должна быть пустой")
            new = canonical_word(new_raw, label)
            if new not in dictionary:
                raise ValueError(f"{label}: слова {new!r} нет в словаре")
            if not formable(level["word"], new):
                raise ValueError(f"{label}: слово {new!r} не составляется из исходника")
            if new in {word_norm(word) for word in task["words"]}:
                raise ValueError(f"{label}: слово {new!r} уже есть в T6")
            if not _evidence_ok(evidence):
                raise ValueError(f"{label}: для T6_ADD нужен проверяемый источник/основание")
            task["words"].append(new)
            category = categories.get(new)
            category_source = category_sources.get(new, "")
            if (category != task.get("category") or not category
                    or str(category_source).startswith(("ai:", "unknown:"))):
                review_flags.append({
                    "code": "manual_category_evidence_required",
                    "task": task_id, "word": new,
                    "observed_category": category,
                    "observed_category_source": category_source,
                    "expected_category": task.get("category"),
                    "evidence": evidence,
                    "override_category": True,
                    "manual_confirmation_required": True,
                })
            if new not in defined:
                review_flags.append({
                    "code": "definition_missing", "task": task_id, "word": new,
                })
        elif action == "T6_REMOVE":
            if task["type"] != "T6":
                raise ValueError(f"{label}: T6_REMOVE требует задание T6")
            if new_raw:
                raise ValueError(f"{label}: для T6_REMOVE колонка «Новое значение» должна быть пустой")
            words = [word_norm(word) for word in task["words"]]
            if old not in words:
                raise ValueError(f"{label}: удаляемого слова {old!r} нет в T6")
            index = words.index(old)
            del task["words"][index]
            before, after = old, ""
        elif action in {"SET_N", "SET_R"}:
            if action == "SET_N":
                if "n" not in task:
                    raise ValueError(f"{label}: у задания нет порога n")
                before = task["n"]
                task["n"] = parse_positive_integer(new_raw, "n")
                after = task["n"]
            else:
                before = level["requiredStars"]
                level["requiredStars"] = parse_positive_integer(new_raw, "R")
                after = level["requiredStars"]
                affected_levels.add(level["number"])
        elif action == "T4_SWAP":
            if task["type"] != "T4":
                raise ValueError(f"{label}: T4_SWAP требует задание T4")
            targets = [word_norm(target) for target in task.get("targets", [])]
            if old not in targets:
                raise ValueError(f"{label}: старое слово {old!r} не является целью T4")
            new = canonical_word(new_raw, label)
            if new == old:
                raise ValueError(f"{label}: новое слово совпадает со старым")
            if new not in dictionary or not formable(level["word"], new):
                raise ValueError(f"{label}: слово {new!r} отсутствует в словаре или не составляется")
            if new not in defined:
                review_flags.append({
                    "code": "definition_missing", "task": task_id, "word": new,
                })
            task["targets"] = [new if word_norm(target) == old else target
                               for target in task["targets"]]
            before, after = old, new
        elif action == "T3_SWAP":
            if task["type"] != "T3":
                raise ValueError(f"{label}: T3_SWAP требует задание T3")
            if old != word_norm(task.get("hintTarget")):
                raise ValueError(f"{label}: старое слово не совпадает с текущим hintTarget")
            new = canonical_word(new_raw, label)
            if new == old:
                raise ValueError(f"{label}: новое слово совпадает со старым")
            if new not in dictionary or not formable(level["word"], new):
                raise ValueError(f"{label}: слово {new!r} отсутствует в словаре или не составляется")
            if len(new) < int(task.get("minLength", 0)):
                raise ValueError(f"{label}: длина новой цели T3 меньше minLength")
            if new not in defined:
                review_flags.append({
                    "code": "definition_missing", "task": task_id, "word": new,
                })
            task["hintTarget"] = new
            before, after = old, new
        elif action == "HINT_REPLACE":
            if task["type"] not in {"T3", "T4", "T6"}:
                raise ValueError(f"{label}: у этого задания нет целевых подсказок")
            selected = _task_old_words(task)
            if old not in selected:
                raise ValueError(f"{label}: слово {old!r} не является целью задания")
            if not new_raw:
                raise ValueError(f"{label}: пустой текст подсказки")
            hint_key = (task_id, old)
            if hint_key in hint_texts:
                raise ValueError(f"{label}: противоречивый повтор HINT_REPLACE для {old!r}")
            safe = _validate_hint_text(db, old, new_raw)
            hint_texts[hint_key] = new_raw
            hint_overrides.append({
                "task_id": task_id,
                "level": level["number"],
                "word": old,
                "text": new_raw,
                "safe_preview": safe,
                "reason": reason,
                "evidence": evidence,
                "rights_status": rights_status,
            })
            review_flags.append({
                "code": "hint_override_runtime_integration_required",
                "task": task_id, "word": old,
                "note": "Текст только в pending-файле; definitions.db и права не изменяются",
            })
            before, after = old, new_raw

        applied.append({
            "row": number, "level": level["number"], "task_id": task_id,
            "action": action, "before": before, "after": after,
            "reason": reason, "rights_status": rights_status,
            "evidence": evidence,
        })
    return candidate, applied, review_flags, hint_overrides, affected_levels


def _campaign_diff(before: dict, after: dict) -> list[dict]:
    changes: list[dict] = []
    before_levels = {level["number"]: level for level in before.get("levels", [])}
    after_levels = {level["number"]: level for level in after.get("levels", [])}
    for number in sorted(set(before_levels) | set(after_levels)):
        left, right = before_levels.get(number), after_levels.get(number)
        path = f"levels[{number}]"
        if left is None or right is None:
            changes.append({"path": path, "before": left, "after": right})
            continue
        for key in ("word", "stage", "requiredStars"):
            if left.get(key) != right.get(key):
                changes.append({"path": f"{path}.{key}", "before": left.get(key), "after": right.get(key)})
        before_tasks = {task.get("id"): task for task in left.get("tasks", [])}
        after_tasks = {task.get("id"): task for task in right.get("tasks", [])}
        for task_id in sorted(set(before_tasks) | set(after_tasks)):
            old_task, new_task = before_tasks.get(task_id), after_tasks.get(task_id)
            task_path = f"{path}.tasks[{task_id}]"
            if old_task is None or new_task is None:
                changes.append({"path": task_path, "before": old_task, "after": new_task})
                continue
            for key in sorted(set(old_task) | set(new_task)):
                if old_task.get(key) != new_task.get(key):
                    changes.append({"path": f"{task_path}.{key}",
                                    "before": old_task.get(key), "after": new_task.get(key)})
    return changes


def _validate_candidate(campaign: dict, original_campaign: dict, dictionary: set[str],
                        affected_levels: set[int]) -> None:
    """Independent reachability and uniqueness checks before writing a candidate."""
    original_levels = {level["number"]: level for level in original_campaign["levels"]}
    levels = campaign.get("levels")
    if not isinstance(levels, list) or len(levels) != len(original_levels):
        raise ValueError("Импорт не может изменить число уровней")
    if sum(len(level.get("tasks", [])) for level in levels) != sum(
            len(level.get("tasks", [])) for level in original_campaign["levels"]):
        raise ValueError("Импорт не может изменить число заданий/звёзд")
    count_min = int(campaign.get("countMinLength", 4))
    accepted_by_task: dict[str, set[str]] = {}
    assigned_targets: list[tuple[str, str]] = []
    baseline_targets = Counter(
        word_norm(target)
        for level in original_campaign.get("levels", [])
        for task in level.get("tasks", [])
        if task.get("type") in {"T3", "T4"}
        for target in ([task.get("hintTarget")] if task.get("type") == "T3"
                       else task.get("targets", []))
        if isinstance(target, str) and target)
    seen_ids: set[str] = set()
    for level in levels:
        number = level.get("number")
        if number not in original_levels:
            raise ValueError("Уровень отсутствует в исходной кампании")
        if level.get("word") != original_levels[number].get("word"):
            raise ValueError(f"Уровень {number}: исходное слово нельзя менять через лист правок")
        tasks = level.get("tasks")
        if not isinstance(tasks, list) or not tasks:
            raise ValueError(f"Уровень {number}: нет заданий")
        required = level.get("requiredStars")
        if type(required) is not int or not 1 <= required <= len(tasks):
            raise ValueError(f"Уровень {number}: R вне 1..M")
        source = word_norm(level["word"])
        available = [word for word in dictionary if formable(source, word)]
        for task in tasks:
            task_id = task.get("id")
            if not isinstance(task_id, str) or not task_id or task_id in seen_ids:
                raise ValueError("Пустой или повторный ID задания")
            seen_ids.add(task_id)
            kind = task.get("type")
            if kind not in {f"T{i}" for i in range(1, 8)}:
                raise ValueError(f"{task_id}: неизвестный тип задания")
            accepted: set[str]
            if kind == "T1":
                accepted = {word for word in available if len(word) >= count_min}
            elif kind == "T2":
                letter = task.get("letter")
                if (not isinstance(letter, str) or letter != word_norm(letter)
                        or len(letter) != 1 or letter not in source):
                    raise ValueError(f"{task_id}: буква T2 отсутствует в исходнике")
                accepted = {word for word in available
                            if len(word) >= count_min and word.startswith(letter)}
            elif kind == "T3":
                min_length = task.get("minLength")
                if type(min_length) is not int or not 3 <= min_length <= len(source):
                    raise ValueError(f"{task_id}: неверная minLength T3")
                accepted = {word for word in available if len(word) >= min_length}
                target = task.get("hintTarget")
                if not isinstance(target, str) or not target:
                    raise ValueError(f"{task_id}: отсутствует цель T3")
                assigned_targets.append((task_id, word_norm(target)))
            elif kind == "T4":
                targets = task.get("targets")
                if not isinstance(targets, list) or not targets:
                    raise ValueError(f"{task_id}: нет целей T4")
                normalized = [word_norm(target) for target in targets]
                assigned_targets.extend((task_id, target) for target in normalized)
                accepted = set(normalized)
            elif kind == "T5":
                ending = task.get("ending")
                min_length = task.get("minLength")
                if (not isinstance(ending, str) or ending != word_norm(ending)
                        or not 2 <= len(ending) <= 4 or type(min_length) is not int
                        or not 3 <= min_length <= len(source)):
                    raise ValueError(f"{task_id}: неверные окончание/длина T5")
                accepted = {word for word in available
                            if len(word) >= min_length and word.endswith(ending)}
            elif kind == "T6":
                if not isinstance(task.get("category"), str) or not task["category"]:
                    raise ValueError(f"{task_id}: нет категории T6")
                words = task.get("words")
                if not isinstance(words, list) or not words:
                    raise ValueError(f"{task_id}: нет слов T6")
                normalized = [word_norm(word) for word in words]
                accepted = set(normalized)
            else:  # T7
                letter = task.get("letter")
                if (not isinstance(letter, str) or letter != word_norm(letter)
                        or len(letter) != 1 or letter not in source):
                    raise ValueError(f"{task_id}: буква T7 отсутствует в исходнике")
                accepted = {word for word in available if letter in word}
            accepted_by_task[task_id] = accepted
            if kind in {"T1", "T2", "T6", "T7"}:
                threshold = task.get("n")
                if type(threshold) is not int or threshold <= 0:
                    raise ValueError(f"{task_id}: неверный порог n")
                if threshold > len(accepted):
                    raise ValueError(
                        f"{task_id}: порог n={threshold} недостижим, максимум {len(accepted)}")
    candidate_targets = Counter(word for _, word in assigned_targets)
    collisions = [word for word, count in candidate_targets.items()
                  if count > baseline_targets.get(word, 0)]
    if collisions:
        raise ValueError(f"Назначенные T3/T4 цели неуникальны: {collisions}")
    # Check the R-chain only for levels touched by the workbook.  Unchanged
    # production campaign data was already validated by the release pipeline.
    from itertools import combinations
    levels_by_number = {level["number"]: level for level in levels}
    for number in sorted(affected_levels):
        level = levels_by_number.get(number)
        if level is None:
            continue
        chosen_count = level["requiredStars"]
        tasks = level["tasks"]
        for chosen in combinations(tasks, chosen_count):
            union: set[str] = set()
            for task in chosen:
                union.update(accepted_by_task[task["id"]])
            for task in chosen:
                kind = task["type"]
                needed = (len(task.get("targets", [])) if kind == "T4"
                          else 1 if kind in {"T3", "T5"} else task.get("n"))
                if type(needed) is not int or len(union & accepted_by_task[task["id"]]) < needed:
                    raise ValueError(
                        f"Уровень {number}: набор R не достигает задания {task['id']}")


def _read_database(path: Path) -> tuple[sqlite3.Connection, dict[str, str],
                                         dict[str, str], set[str]]:
    connection = sqlite3.connect(Path(path).resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    categories = {row["word"]: row["category"]
                  for row in connection.execute("SELECT word,category FROM word_metadata")}
    category_sources = {row["word"]: row["category_source"]
                        for row in connection.execute(
                            "SELECT word,category_source FROM word_metadata")}
    defined = {row[0] for row in connection.execute(
        "SELECT DISTINCT word FROM definitions")}
    return connection, categories, category_sources, defined


def _write_json(path: Path, value: object, *, sort_keys: bool = False) -> bytes:
    payload = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=sort_keys)
               + "\n").encode("utf-8")
    path.write_bytes(payload)
    return payload


def _relative_name(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.name


def _run_official_validation(root: Path, candidate_path: Path, output: Path,
                             validator: Path, profile: str) -> dict:
    """Run the existing validator; never leave a false validated_campaign behind."""
    validation_dir = output / "official_validation"
    validation_dir.mkdir(parents=True, exist_ok=True)
    report_path = validation_dir / "validation_report.json"
    validated_path = validation_dir / "validated_campaign.json"
    report_path.unlink(missing_ok=True)
    validated_path.unlink(missing_ok=True)
    if not validator.is_file():
        return {
            "status": "FAIL", "profile": profile, "exit_code": None,
            "reason": "validator_not_found", "validated_campaign": False,
        }
    command = [sys.executable, str(validator), "--campaign", str(candidate_path),
               "--profile", profile, "--output", str(validation_dir)]
    try:
        completed = subprocess.run(command, cwd=root, capture_output=True,
                                   text=True, timeout=1800)
    except (OSError, subprocess.TimeoutExpired) as error:
        validated_path.unlink(missing_ok=True)
        return {
            "status": "FAIL", "profile": profile, "exit_code": None,
            "reason": str(error)[:400], "validated_campaign": False,
        }
    status = "FAIL"
    reason = ""
    if report_path.is_file():
        try:
            validation_report = json.loads(report_path.read_text(encoding="utf-8"))
            report_status = str(validation_report.get("status", "")).lower()
            report_installable = validation_report.get("installable", True)
            if report_status in {"valid", "pass"} and report_installable is True:
                status = "PASS"
            else:
                reason = "validation_report_not_valid"
        except ValueError:
            reason = "validation_report_invalid_json"
    else:
        reason = "validation_report_missing"
    if not validated_path.is_file():
        status = "FAIL"
        reason = reason or "validated_campaign_missing"
    if completed.returncode != 0:
        status = "FAIL"
        reason = reason or f"validator_exit_{completed.returncode}"
    if status != "PASS":
        # A failing validator must not leave an old/new success artifact.
        validated_path.unlink(missing_ok=True)
    return {
        "status": status,
        "profile": profile,
        "exit_code": completed.returncode,
        "reason": reason,
        "validated_campaign": validated_path.is_file(),
        "report_file": _relative_name(report_path, root) if report_path.is_file() else None,
    }


def stage(root: Path, snapshot_path: Path, workbook_path: Path, out: Path,
          *, expected_workbook_sha256: str | None = None,
          expected_snapshot_sha256: str | None = None,
          validate: bool = False, profile: str = "short15-release-v1",
          validator: Path | None = None) -> dict:
    """Build a staged candidate from the workbook.  Never writes to ``data/``."""
    root = Path(root).resolve()
    snapshot_path = _resolve(root, snapshot_path).resolve()
    workbook_path = _resolve(root, workbook_path).resolve()
    output = _resolve(root, out).resolve()
    _ensure_output(root, output)

    snapshot = _load_snapshot(snapshot_path)
    hashes, _ = _verify_inputs(
        root, snapshot_path, workbook_path, snapshot,
        expected_workbook_sha256, expected_snapshot_sha256)
    rows, header_row = xlsx_rows(workbook_path, with_header=True)
    original_path = root / "data" / "campaign.json"
    original_bytes = original_path.read_bytes()
    try:
        campaign = json.loads(original_bytes.decode("utf-8"))
    except ValueError as error:
        raise ValueError("Некорректный data/campaign.json") from error
    dictionary = {word_norm(line) for line in
                  (root / "data" / "words.txt").read_text(encoding="utf-8").splitlines()
                  if word_norm(line)}
    db, categories, category_sources, defined = _read_database(
        root / "data" / "definitions.db")
    try:
        candidate, applied, flags, hints, affected = _apply_edits(
            campaign, snapshot, rows, header_row, dictionary, db, categories,
            category_sources, defined)
    finally:
        db.close()

    if applied and candidate != campaign:
        # The real DB is needed for changed targets' hints during _apply_edits;
        # reachability itself only needs the dictionary and campaign structure.
        _validate_candidate(candidate, campaign, dictionary, affected)

    output.mkdir(parents=True, exist_ok=True)
    candidate_path = output / "campaign.candidate.json"
    if candidate == campaign:
        candidate_bytes = original_bytes
    else:
        candidate_bytes = (json.dumps(candidate, ensure_ascii=False, indent=2,
                                      sort_keys=True) + "\n").encode("utf-8")
    candidate_path.write_bytes(candidate_bytes)
    diff = _campaign_diff(campaign, candidate)
    _write_json(output / "campaign.diff.json", diff, sort_keys=True)
    _write_json(output / "hint_overrides.pending.json", hints)
    report = {
        "schema": "short15-editorial-import-v1",
        "status": "STAGED_NOT_VALIDATED",
        "source_campaign_sha256": hashes["campaign.json"],
        "candidate_campaign_sha256": sha256(candidate_bytes).hexdigest(),
        "candidate_equals_source": candidate == campaign,
        "inputs": hashes,
        "changes": applied,
        "diff": diff,
        "summary": {
            "actions": len(applied),
            "candidate_changes": len(diff),
            "levels_changed": len({row["level"] for row in applied}),
            "manual_review_flags": len(flags),
        },
        "needs_manual_review": flags,
        "production_validated": False,
        "rights_audit_passed": False,
        "rights_review_passed": False,
        "hint_overrides_integrated": False if hints else None,
        "publishable": False,
        "candidate_file": "campaign.candidate.json",
        "diff_file": "campaign.diff.json",
        "hint_overrides_file": "hint_overrides.pending.json",
        "official_validation": None,
    }
    _write_json(output / "review_report.json", report)

    if validate:
        validator_path = _resolve(root, validator) if validator else root / "tools" / "validate_campaign.py"
        result = _run_official_validation(root, candidate_path, output,
                                          validator_path.resolve(), profile)
        report["official_validation"] = result
        report["status"] = ("STAGED_VALIDATION_PASSED" if result["status"] == "PASS"
                            else "STAGED_VALIDATION_FAILED")
        _write_json(output / "review_report.json", report)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd(), help="корень проекта")
    parser.add_argument("--snapshot", required=True, type=Path,
                        help="JSON-снимок из tools/campaign_review_export.py")
    parser.add_argument("--workbook", required=True, type=Path,
                        help="редактируемая книга CAMPAIGN_REVIEW_15.xlsx")
    parser.add_argument("--output", required=True, type=Path,
                        help="отдельный staging-каталог (не data/)")
    parser.add_argument("--expected-workbook-sha256", "--workbook-sha256",
                        dest="expected_workbook_sha256",
                        help="необязательный SHA-256 книги для жёсткой сверки")
    parser.add_argument("--expected-snapshot-sha256", "--snapshot-sha256",
                        dest="expected_snapshot_sha256",
                        help="необязательный SHA-256 снимка")
    parser.add_argument("--validate", action="store_true",
                        help="запустить официальный tools/validate_campaign.py на кандидате")
    parser.add_argument("--profile", default="short15-release-v1",
                        help="профиль официального валидатора")
    parser.add_argument("--validator", type=Path,
                        help="путь к tools/validate_campaign.py (для тестов/локальной копии)")
    args = parser.parse_args(argv)
    try:
        report = stage(
            args.root, args.snapshot, args.workbook, args.output,
            expected_workbook_sha256=args.expected_workbook_sha256,
            expected_snapshot_sha256=args.expected_snapshot_sha256,
            validate=args.validate, profile=args.profile,
            validator=args.validator,
        )
    except (OSError, ValueError, KeyError, TypeError, IndexError,
            sqlite3.Error, BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    root = args.root.resolve()
    output = args.output if args.output.is_absolute() else root / args.output
    print(json.dumps({
        "status": report["status"],
        "changes": len(report["changes"]),
        "needs_manual_review": len(report["needs_manual_review"]),
        "candidate": str((output.resolve() / "campaign.candidate.json")),
        "candidate_sha256": report["candidate_campaign_sha256"],
        "official_validation": (report["official_validation"] or {}).get("status"),
        "publishable": False,
    }, ensure_ascii=False))
    if report["official_validation"] and report["official_validation"]["status"] != "PASS":
        return 2
    return 0


# Compatibility with the prepared editor tooling that used the shorter name.
sha = sha256_file


if __name__ == "__main__":
    raise SystemExit(main())
