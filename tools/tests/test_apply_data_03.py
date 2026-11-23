"""DATA-03: только синтетические данные; сеть и рабочий словарь не используются."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apply_data_03 as tool
import build_dictionary as dictionary
from game_data_providers import atomic_json


def audit_config(keep_words=("ручка", "они")):
    return {
        "categories": [
            {
                "id": "productive_diminutives",
                "words": ["цветик", "ручка"],
                "keep": {word: "остаётся по решению" for word in keep_words if word == "ручка"},
            },
            {
                "id": "function_words",
                "words": ["оне", "они"],
                "keep": {word: "остаётся по решению" for word in keep_words if word == "они"},
            },
            {"id": "other", "words": ["менталист"], "keep": {}},
        ]
    }


def selection_config(keep_words=("ручка", "они")):
    return {
        "cleanup": {
            "word_exclusions": {
                "цветик": "DATA-03",
                "оне": "DATA-03",
                "менталист": "другая задача",
            },
            "word_keep": {
                "ручка": "остаётся по решению",
                "они": "остаётся по решению",
            },
        }
    }


class PolicyTest(unittest.TestCase):
    def test_policy_returns_exact_exclusions_and_kept_words(self):
        excluded, kept = tool.exclusion_policy(audit_config(), selection_config())
        self.assertEqual(excluded, {"цветик", "оне"})
        self.assertEqual(set(kept), {"ручка", "они"})
        self.assertNotIn("менталист", excluded)

    def test_keep_and_exclusion_overlap_fails(self):
        audit = audit_config()
        audit["categories"][1]["words"].append("ручка")
        with self.assertRaises(ValueError):
            tool.exclusion_policy(audit, selection_config())


class RunTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = self.root / "data"
        self.data.mkdir()
        self.words = ["портал", "цветик", "оне", "кот", "она", "акробат"]
        (self.data / "words.txt").write_text("\n".join(self.words) + "\n", encoding="utf-8")
        sources = {word: [{"title": word, "line": index, "source": "https://example.invalid/" + word}]
                   for index, word in enumerate(self.words, 1)}
        dictionary.write_word_sources(self.data / "word_sources.jsonl.gz", sources)
        (self.data / "campaign.json").write_text('{"schema": "synthetic"}\n', encoding="utf-8")
        self.levels = b'[{"index": 1}]\n'
        (self.data / "levels.json").write_bytes(self.levels)
        atomic_json(self.data / "golden_search.json", {})
        atomic_json(self.data / "manifest.json", {
            "words": len(self.words),
            "campaign_source": {"sha256": dictionary.sha256_file(self.data / "campaign.json")},
            "selection": {"parameters": {}},
        })
        self.selection = self.root / "selection.json"
        atomic_json(self.selection, selection_config())
        self.audit = self.root / "audit.json"
        atomic_json(self.audit, audit_config())

    def run_tool(self):
        return tool.run(self.data, self.selection, self.audit)

    def bytes_of_interest(self):
        return {
            name: (self.data / name).read_bytes()
            for name in ("words.txt", "word_sources.jsonl.gz", "golden_search.json",
                         "manifest.json", "campaign.json", "levels.json")
        }

    def test_excludes_words_and_refreshes_derived_data(self):
        summary = self.run_tool()
        self.assertEqual(summary["words_before"], 6)
        self.assertEqual(summary["words_after"], 4)
        self.assertEqual((self.data / "words.txt").read_text(encoding="utf-8"),
                         "портал\nкот\nона\nакробат\n")
        self.assertNotIn("цветик", tool.read_sources(self.data / "word_sources.jsonl.gz"))
        self.assertNotIn("оне", tool.read_sources(self.data / "word_sources.jsonl.gz"))
        self.assertEqual(json.loads((self.data / "golden_search.json").read_text(encoding="utf-8")),
                         tool.golden_counts(["портал", "кот", "она", "акробат"]))
        manifest = json.loads((self.data / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["words"], 4)
        self.assertEqual(manifest["sha256_words_txt"], dictionary.sha256_file(self.data / "words.txt"))
        self.assertEqual(manifest["owned_riddles"]["dictionary_words_sha256"], manifest["sha256_words_txt"])
        self.assertEqual(manifest["selection"]["word_cleanup"]["excluded"], ["оне", "цветик"])
        self.assertEqual(manifest["selection"]["word_cleanup"]["kept"],
                         {"они": "остаётся по решению", "ручка": "остаётся по решению"})
        self.assertEqual((self.data / "levels.json").read_bytes(), self.levels)
        self.assertEqual((self.data / "campaign.json").read_text(encoding="utf-8"),
                         '{"schema": "synthetic"}\n')

    def test_second_run_is_byte_identical(self):
        self.run_tool()
        first = self.bytes_of_interest()
        self.run_tool()
        self.assertEqual(self.bytes_of_interest(), first)

    def test_owner_changes_keep_in_one_config(self):
        audit = audit_config()
        audit["categories"][1]["keep"] = {}
        atomic_json(self.audit, audit)
        self.run_tool()
        config = tool.read_json(self.selection)["cleanup"]
        self.assertIn("они", config["word_exclusions"])
        self.assertNotIn("они", config["word_keep"])

    def test_changed_campaign_fails_before_writing(self):
        (self.data / "campaign.json").write_text('{}\n', encoding="utf-8")
        before = self.bytes_of_interest()
        with self.assertRaises(ValueError):
            self.run_tool()
        self.assertEqual(self.bytes_of_interest(), before)

    def test_broken_sources_fail_before_writing(self):
        rows = tool.read_sources(self.data / "word_sources.jsonl.gz")
        rows.pop("кот")
        dictionary.write_word_sources(self.data / "word_sources.jsonl.gz", rows)
        before = self.bytes_of_interest()
        with self.assertRaises(ValueError):
            self.run_tool()
        self.assertEqual(self.bytes_of_interest(), before)


if __name__ == "__main__":
    unittest.main()
