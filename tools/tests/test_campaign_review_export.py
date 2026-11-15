"""Synthetic and installed-data checks for tools/campaign_review_export.py."""
from __future__ import annotations

import csv
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import campaign_review_export as review


def digest(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


SOURCES = [
    "чемпионство", "вегетарианец", "аккумулятор", "благословение", "благоразумие",
    "безрассудство", "журналистика", "поздравление", "благодарность", "пуэрториканец",
    "подстрекатель", "проблематика", "складирование", "контрабандист", "документалист",
]
T6_WORDS = ["артист", "артистка", "банкир", "бариста", "батрак", "радист"]
DICTIONARY = sorted(set(SOURCES + T6_WORDS + [
    "кот", "ток", "крот", "карта", "катер", "тетка", "тара", "тора", "кара", "работа",
    "утро", "отель", "десерт", "проблема", "билет", "весна", "ковер", "банк", "стадион",
]))


def campaign_fixture() -> dict:
    counts = [1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 5, 5, 5]
    stages = [(1, 3), (4, 5), (6, 8), (9, 10), (11, 13), (14, 15)]
    levels = []
    for number, (source, count) in enumerate(zip(SOURCES, counts), 1):
        types = (["T1", "T2", "T3", "T4", "T5"] if number != 15 else
                 ["T1", "T2", "T3", "T5", "T7"])[:count]
        if number == 14:
            types = ["T1", "T2", "T4", "T5", "T6"]
        tasks = []
        for kind in types:
            task = {"id": f"l{number:02d}-{kind.lower()}", "type": kind}
            if kind == "T1":
                task["n"] = 1
            elif kind == "T2":
                task.update(letter="к", n=1)
            elif kind == "T3":
                task.update(minLength=3, hintTarget="кот")
            elif kind == "T4":
                task["targets"] = ["ток"]
            elif kind == "T5":
                task.update(ending="от", minLength=3)
            elif kind == "T6":
                task.update(category="professions", n=5, words=T6_WORDS)
            elif kind == "T7":
                task.update(letter="о", n=1)
            tasks.append(task)
        stage = next(index for index, (first, last) in enumerate(stages, 1) if first <= number <= last)
        levels.append({"number": number, "word": source, "stage": stage,
                       "requiredStars": 4 if number >= 14 else 1, "tasks": tasks})
    return {
        "version": 2,
        "countMinLength": 4,
        "stages": [{"number": index, "firstLevel": first, "lastLevel": last}
                   for index, (first, last) in enumerate(stages, 1)],
        "levels": levels,
    }


def write_database(path: Path, defined: list[str]) -> None:
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE definitions(id INTEGER PRIMARY KEY, word TEXT NOT NULL, display TEXT NOT NULL,
                ord INTEGER NOT NULL, text TEXT NOT NULL, notes TEXT NOT NULL DEFAULT '',
                antonym TEXT NOT NULL DEFAULT '', example TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '',
                origin TEXT NOT NULL, category TEXT NOT NULL, guessable INTEGER NOT NULL CHECK(guessable IN (0,1)));
            CREATE TABLE word_metadata(word TEXT PRIMARY KEY, category TEXT NOT NULL,
                category_source TEXT NOT NULL DEFAULT 'unknown:legacy_input',
                forms TEXT NOT NULL DEFAULT '[]', related TEXT NOT NULL DEFAULT '[]');
            PRAGMA user_version=8;
        """)
        for number, word in enumerate(defined, 1):
            category = "professions" if word in T6_WORDS else "other"
            source = "sense.categories:профессии" if category == "professions" else "unknown:uncertain_sense"
            db.execute("INSERT INTO word_metadata(word,category,category_source,forms,related) VALUES(?,?,?,?,?)",
                       (word, category, source, json.dumps([word], ensure_ascii=False), "[]"))
            text = ("Кот — домашнее животное, которое любит молоко и ловит мышей."
                    if word == "кот" else f"Толкование слова {word}, достаточное для проверки подсказки.")
            db.execute("INSERT INTO definitions(word,display,ord,text,notes,antonym,example,source,origin,category,guessable)"
                       " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                       (word, word, 1, text, "", "", "", "https://ru.wiktionary.org/wiki/" + word,
                        "wiktionary", category, 0))


def write_fixture(root: Path) -> None:
    data = root / "data"
    data.mkdir(parents=True)
    campaign = campaign_fixture()
    campaign_path = data / "campaign.json"
    campaign_path.write_text(json.dumps(campaign, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    words_path = data / "words.txt"
    words_path.write_text("\n".join(DICTIONARY) + "\n", encoding="utf-8")
    database_path = data / "definitions.db"
    write_database(database_path, DICTIONARY)
    definitions = {
        "audit": {"passed": False},
        "counts": {"own": 0, "wiktionary": len(DICTIONARY)},
        "coverage": {"total": len(DICTIONARY), "words": len(DICTIONARY)},
        "dictionary_words_sha256": digest(words_path),
        "rights_policy": "wiktionary-provisional-v1",
        "rights_review": {"passed": False, "replacement_required": True,
                          "status": "unverified-provisional"},
        "sha256": digest(database_path),
        "user_version": 8,
    }
    manifest = {
        "schema": "dictionary-provenance-v1",
        "words": len(DICTIONARY),
        "sha256_words_txt": digest(words_path),
        "campaign_source": {"profile": "short15-release-v1", "levels": 15,
                            "max_stars": 49, "sha256": digest(campaign_path)},
        "definitions": definitions,
        "minLen": 3,
    }
    (data / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True) + "\n",
                                        encoding="utf-8")


class ExportSyntheticTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        write_fixture(self.root)

    def test_export_has_15_levels_49_tasks_and_full_answer_catalog(self):
        payload = review.export(self.root)
        self.assertEqual(15, len(payload["overview"]))
        self.assertEqual(49, len(payload["tasks"]))
        self.assertEqual(15, len({row["level"] for row in payload["overview"]}))
        self.assertEqual(49, sum(row["M"] for row in payload["overview"]))
        self.assertEqual(49, payload["snapshot"]["stars"])
        self.assertTrue(all(row["accepted"] >= 0 for row in payload["overview"]))
        self.assertGreater(payload["snapshot"]["accepted_rows"], 0)
        self.assertEqual(payload["snapshot"]["accepted_rows"], len(payload["answers"]))
        self.assertEqual("short15-editorial-export-v1", payload["schema"])

    def test_t6_catalog_reports_six_candidates_with_n_five(self):
        payload = review.export(self.root)
        task = next(row for row in payload["tasks"] if row["task_id"] == "l14-t6")
        self.assertEqual(5, task["n"])
        self.assertEqual(6, task["target_count"])
        self.assertEqual(T6_WORDS, task["targets"].split(", "))
        self.assertEqual(6, task["recounted_count"])
        self.assertEqual(T6_WORDS, task["recounted_targets"].split(", "))

    def test_empty_editorial_rows_do_not_change_the_original_campaign(self):
        before = (self.root / "data/campaign.json").read_bytes()
        payload = review.export(self.root)
        self.assertTrue(payload["edits"])
        self.assertTrue(all(not row["action"] for row in payload["edits"]))
        self.assertEqual(before, (self.root / "data/campaign.json").read_bytes())
        candidate = json.loads(before)
        self.assertEqual(json.loads(before), candidate)

    def test_export_is_deterministic_and_writes_standard_library_catalogs(self):
        first = review.export(self.root)
        second = review.export(self.root)
        self.assertEqual(first, second)
        output = self.root / "build/reports/review/curation_snapshot.json"
        json_path, directory = review.write_outputs(first, self.root, output)
        self.assertEqual(json_path, output)
        self.assertTrue(json_path.is_file())
        for name in ("levels", "tasks", "answers", "hints", "edits", "short_repeats",
                     "short_repeats_adjacent"):
            self.assertTrue((directory / f"{name}.csv").is_file(), name)
            self.assertTrue((directory / f"{name}.tsv").is_file(), name)
        with (directory / "tasks.csv").open(encoding="utf-8", newline="") as stream:
            self.assertEqual(49, len(list(csv.DictReader(stream))))
        with (directory / "answers.csv").open(encoding="utf-8", newline="") as stream:
            self.assertEqual(first["snapshot"]["accepted_rows"], len(list(csv.DictReader(stream))))
        first_json = json_path.read_bytes()
        review.write_outputs(second, self.root, output)
        self.assertEqual(first_json, json_path.read_bytes())

    def test_export_is_read_only_and_refuses_data_output(self):
        hashes = {path.name: digest(path) for path in (self.root / "data").iterdir()}
        review.export(self.root)
        self.assertEqual(hashes, {path.name: digest(path) for path in (self.root / "data").iterdir()})
        with self.assertRaisesRegex(ValueError, "data/"):
            review.write_outputs(review.export(self.root), self.root, self.root / "data/export.json")

    def test_original_definition_and_safe_masked_hint_are_exported(self):
        payload = review.export(self.root)
        hints = [row for row in payload["hints"] if row["word"] == "кот"]
        self.assertTrue(hints)
        self.assertTrue(any(row["raw_text"].startswith("Кот —") for row in hints))
        safe = [row for row in hints if row["safe"]]
        self.assertTrue(safe)
        self.assertTrue(safe[0]["safe_preview"].startswith("***"))
        tokens = safe[0]["safe_preview"].lower().replace("ё", "е").split()
        self.assertNotIn("кот", [token.strip(".,;:!?—–-") for token in tokens])
        self.assertEqual("***", safe[0]["mask"])

    def test_snapshot_contains_input_hashes_and_rights(self):
        payload = review.export(self.root)
        snapshot = payload["snapshot"]
        self.assertEqual(digest(self.root / "data/campaign.json"), snapshot["campaign_sha256"])
        self.assertEqual(digest(self.root / "data/words.txt"), snapshot["dictionary_sha256"])
        self.assertEqual(digest(self.root / "data/definitions.db"), snapshot["definitions_sha256"])
        self.assertEqual(digest(self.root / "data/manifest.json"), snapshot["manifest_sha256"])
        self.assertEqual(snapshot["campaign_sha256"], snapshot["inputs"]["campaign.json"])
        self.assertEqual(len(DICTIONARY), snapshot["rights"]["wiktionary_definitions"])
        self.assertFalse(snapshot["rights"]["audit_passed"])
        self.assertFalse(snapshot["rights"]["rights_review_passed"])
        self.assertFalse(snapshot["rights"]["publication_allowed"])
        self.assertNotIn(str(self.root), json.dumps(payload, ensure_ascii=False))


@unittest.skipUnless((ROOT / "data/campaign.json").is_file(), "installed short15 data is absent")
class InstalledShort15Test(unittest.TestCase):
    def test_installed_campaign_has_15_levels_49_tasks_and_t6_six_candidates(self):
        payload = review.export(ROOT)
        self.assertEqual(15, len(payload["overview"]))
        self.assertEqual(49, len(payload["tasks"]))
        self.assertEqual(49, sum(row["M"] for row in payload["overview"]))
        task = next(row for row in payload["tasks"] if row["task_id"] == "l14-t6")
        self.assertEqual(5, task["n"])
        self.assertEqual(6, task["target_count"])
        self.assertEqual(6, task["recounted_count"])
        self.assertEqual(
            ["артист", "артистка", "банкир", "бариста", "батрак", "радист"],
            task["targets"].split(", "))
        manifest = json.loads((ROOT / "data/manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["campaign_source"]["sha256"], payload["snapshot"]["campaign_sha256"])
        self.assertEqual(31351, payload["snapshot"]["rights"]["wiktionary_definitions"])
        self.assertFalse(payload["snapshot"]["rights"]["audit_passed"])
        self.assertFalse(payload["snapshot"]["rights"]["rights_review_passed"])


if __name__ == "__main__":
    unittest.main()
