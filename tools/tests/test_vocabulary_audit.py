"""DATA-06: synthetic-only tests for the vocabulary audit report."""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import audit_game_vocabulary as audit


def config():
    return {
        "schema": audit.CONFIG_SCHEMA,
        "report": {
            "title": "Тестовый аудит",
            "margin_threshold": 2,
            "example_limit": 2,
        },
        "known_narrow_tasks": [
            {"level": 2, "type": "T3", "dictionary_words": 2},
            {"level": 1, "type": "T5", "dictionary_words": 3},
        ],
        "categories": [
            {
                "id": "function_words",
                "title": "Служебные слова",
                "description": "Синтетическая категория.",
                "words": ["они", "она"],
                "absent_reference": ["что", "кто"],
                "absent_notes": {"что": "Нет в тестовом словаре."},
                "word_notes": {"они": "местоимение", "она": "местоимение"},
            },
            {
                "id": "productive_diminutives",
                "title": "Уменьшительные",
                "description": "Синтетическая категория уменьшительных.",
                "words": ["домик", "котик"],
                "absent_reference": ["адресок"],
                "absent_notes": {"адресок": "Исторический пример."},
                "lexicalized_exceptions": ["ручка", "ножка"],
                "word_notes": {"домик": "от «дом»", "котик": "от «кот»"},
            },
        ],
    }


def dictionary():
    return {
        "они", "она", "домик", "котик", "ручка", "ножка",
        "кот", "ток", "рак", "карта", "катер", "крот", "тара", "кара",
        "картотека", "картотекаа", "домиконитка",
    }


def campaign():
    return {
        "version": 2,
        "countMinLength": 4,
        "stages": [{"number": 1, "firstLevel": 1, "lastLevel": 2}],
        "levels": [
            {
                "number": 1,
                "stage": 1,
                "requiredStars": 2,
                "word": "картотека",
                "tasks": [
                    {"id": "l01-t1", "type": "T1", "n": 2},
                    {"id": "l01-t2", "type": "T2", "n": 1, "letter": "к"},
                    {"id": "l01-t3", "type": "T3", "minLength": 5, "hintTarget": "катер"},
                    {"id": "l01-t4", "type": "T4", "targets": ["карта"]},
                    {"id": "l01-t5", "type": "T5", "minLength": 4, "ending": "ра"},
                    {"id": "l01-t6", "type": "T6", "category": "objects", "n": 1,
                     "words": ["кот", "рак"]},
                    {"id": "l01-t7", "type": "T7", "n": 2, "letter": "р"},
                ],
            },
            {
                "number": 2,
                "stage": 1,
                "requiredStars": 2,
                "word": "домиконитка",
                "tasks": [
                    {"id": "l02-t1", "type": "T1", "n": 2},
                    {"id": "l02-t2", "type": "T2", "n": 1, "letter": "д"},
                    {"id": "l02-t3", "type": "T3", "minLength": 4, "hintTarget": "домик"},
                    {"id": "l02-t4", "type": "T4", "targets": ["котик"]},
                    {"id": "l02-t5", "type": "T5", "minLength": 4, "ending": "ик"},
                    {"id": "l02-t6", "type": "T6", "category": "objects", "n": 1,
                     "words": ["домик", "котик"]},
                    {"id": "l02-t7", "type": "T7", "n": 2, "letter": "о"},
                ],
            },
        ],
    }


def report(inputs=None):
    return audit.build_report(dictionary(), campaign(), config(), inputs=inputs)


class VocabularyAuditTest(unittest.TestCase):
    def test_answers_use_counter_source_exclusion_and_minimum_length(self):
        words = {"кот", "ток", "кто", "от", "кошка", "картотека"}
        self.assertEqual({"ток", "кто"}, audit.answers_for("кот", words))
        self.assertEqual({"ток", "кто", "от"},
                         audit.answers_for("кот", words, minimum=2))

    def test_all_task_types_are_counted(self):
        built = report()
        level = built["levels"][0]
        rows = {task["id"]: task for task in level["tasks"]}
        expected = {
            "l01-t1": (5, 2, 3, 0.4),
            "l01-t2": (4, 1, 3, 0.25),
            "l01-t3": (2, 1, 1, 0.5),
            "l01-t4": (1, 1, 0, 1.0),
            "l01-t5": (2, 1, 1, 0.5),
            "l01-t6": (2, 1, 1, 0.5),
            "l01-t7": (6, 2, 4, 1 / 3),
        }
        for task_id, (count, needed, margin, share) in expected.items():
            with self.subTest(task=task_id):
                self.assertEqual(count, rows[task_id]["count"])
                self.assertEqual(needed, rows[task_id]["needed"])
                self.assertEqual(margin, rows[task_id]["margin"])
                self.assertAlmostEqual(share, rows[task_id]["threshold_share"], places=5)
        self.assertTrue(rows["l01-t4"]["exact_target"])
        self.assertFalse(rows["l01-t3"]["exact_target"])

    def test_categories_show_assigned_targets_roles_and_letter_levels(self):
        built = report()
        categories = {row["id"]: row for row in built["categories"]}
        words = {row["word"]: row for row in categories["productive_diminutives"]["words"]}
        domik = words["домик"]
        kotik = words["котик"]
        self.assertEqual([2], domik["buildable_levels"])
        self.assertEqual([2], domik["assigned"]["T3_target"])
        self.assertEqual([], domik["assigned"]["T4_target"])
        self.assertEqual([2], domik["assigned"]["T6_list"])
        self.assertEqual([2], domik["task_roles"]["T1"])
        self.assertEqual([], domik["task_roles"]["T4"])
        self.assertEqual([2], domik["task_roles"]["T5"])
        self.assertEqual([2], domik["task_roles"]["T6"])
        self.assertEqual([2], domik["task_roles"]["T7"])
        self.assertEqual([2], kotik["assigned"]["T4_target"])
        self.assertIn("l02-t4", kotik["affected_task_ids"])
        self.assertIn("l02-t3", domik["affected_task_ids"])
        function_words = {row["word"]: row for row in categories["function_words"]["words"]}
        self.assertEqual([2], function_words["они"]["task_roles"]["T7"])
        self.assertEqual([], function_words["они"]["task_roles"]["T1"])

    def test_absent_references_and_lexicalized_exceptions_are_not_counted(self):
        built = report()
        categories = {row["id"]: row for row in built["categories"]}
        function_words = categories["function_words"]
        self.assertEqual([], function_words["absent_words"])
        self.assertEqual(["что", "кто"],
                         [row["word"] for row in function_words["absent_reference"]])
        self.assertFalse(function_words["absent_reference"][0]["present"])
        diminutives = categories["productive_diminutives"]
        self.assertEqual(["ручка", "ножка"], diminutives["lexicalized_exceptions"])
        self.assertNotIn("ручка", diminutives["present_words"])
        self.assertEqual(["адресок"], [row["word"] for row in diminutives["absent_reference"]])

    def test_affected_tasks_and_narrow_levels_are_explicit(self):
        built = report()
        affected = {row["task_id"]: row for row in built["affected_tasks"]}
        self.assertIn("l02-t3", affected)
        self.assertEqual(["домик", "котик"], affected["l02-t3"]["hits"])
        self.assertEqual(["productive_diminutives"],
                         list(affected["l02-t3"]["hits_by_category"]))
        self.assertTrue(affected["l02-t3"]["would_break"])
        self.assertFalse(affected["l02-t7"]["would_break"])
        self.assertEqual([1, 2], [row["level"] for row in built["narrow_levels"]])
        narrow = {row["level"]: row for row in built["narrow_levels"]}
        self.assertNotIn("l02-t4", [task["id"] for task in narrow[2]["tasks"]])
        self.assertIn("l01-t5", [task["id"] for task in narrow[1]["tasks"]])

    def test_known_narrow_checks_show_matches_and_differences(self):
        built = report()
        checks = {(row["level"], row["type"]): row for row in built["known_narrow_tasks"]}
        self.assertTrue(checks[(2, "T3")]["matches"])
        self.assertEqual(2, checks[(2, "T3")]["observed"])
        self.assertFalse(checks[(1, "T5")]["matches"])
        self.assertEqual(3, checks[(1, "T5")]["expected"])
        self.assertEqual(2, checks[(1, "T5")]["observed"])

    def test_markdown_contains_required_lists_and_is_deterministic(self):
        inputs = {"campaign": {"path": "data/campaign.json", "sha256": "a" * 64},
                  "words": {"path": "data/words.txt", "sha256": "b" * 64},
                  "config": {"path": "tools/vocabulary_audit_config.json", "sha256": "c" * 64}}
        first = report(inputs)
        second = report(copy.deepcopy(inputs))
        self.assertEqual(first, second)
        markdown = audit.render_markdown(first)
        self.assertEqual(markdown, audit.render_markdown(second))
        for heading in ("## Кандидаты к исключению", "## Затронутые задания",
                        "## Уровни с запасом меньше или равным 10",
                        "## Запас достижимости по всем заданиям",
                        "## Проверка известных узких мест", "## Риски чистки"):
            self.assertIn(heading, markdown)
        self.assertLess(markdown.index("## Риски чистки"),
                        len(markdown))
        self.assertIn("домик, котик", markdown)
        self.assertIn("адресок", markdown)
        self.assertIn("`" + "a" * 64 + "`", markdown)
        self.assertNotIn("Thread", markdown)

    def test_config_and_input_validation(self):
        valid = config()
        audit.validate_config(valid)
        duplicate = copy.deepcopy(valid)
        duplicate["categories"][0]["words"].append("домик")
        with self.assertRaises(ValueError):
            audit.validate_config(duplicate)
        noncanonical = copy.deepcopy(valid)
        noncanonical["categories"][0]["words"][0] = "Они"
        with self.assertRaises(ValueError):
            audit.validate_config(noncanonical)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "words.txt"
            path.write_text("домик\nдомик\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                audit.read_words(path)
            path.write_text("домик\nДом\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                audit.read_words(path)

    def test_invalid_campaign_version_is_rejected(self):
        campaign_doc = campaign()
        campaign_doc["version"] = 1
        with self.assertRaises(ValueError):
            audit.build_report(dictionary(), campaign_doc, config())

    def test_config_file_is_valid_and_categories_are_explicit(self):
        loaded = audit.load_config()
        self.assertEqual(audit.CONFIG_SCHEMA, loaded["schema"])
        ids = [category["id"] for category in loaded["categories"]]
        self.assertEqual(len(ids), len(set(ids)))
        for category in loaded["categories"]:
            self.assertTrue(category["words"])
            self.assertTrue(category.get("description"))


if __name__ == "__main__":
    unittest.main()
