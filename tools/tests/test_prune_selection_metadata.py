"""Synthetic metadata: removal must preserve unrelated selection and license data."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prune_selection_metadata import prune


class PruneSelectionMetadataTest(unittest.TestCase):
    def test_manifest_loses_only_retired_reference(self):
        manifest = {"words": 2, "sha256_words_txt": "unchanged", "source": {"license": "test"},
                    "selection": {"parameters": {"threshold": 10}, "reference_checks": {
                        "retired_sample": {"кот": ["reason"]}, "level_exceptions": {}}}}
        expected = copy.deepcopy(manifest)
        del expected["selection"]["reference_checks"]["retired_sample"]
        self.assertEqual(expected, prune(manifest, "retired_sample"))
        self.assertEqual(expected, prune(manifest, "retired_sample"))

    def test_summary_removes_both_report_forms_and_preserves_decisions(self):
        summary = {"selected": 2, "retired_sample": {}, "reference_checks": {
            "retired_sample": {}, "level_exceptions": {}}, "decisions": {"кот": "keep"}}
        self.assertEqual({"selected": 2, "reference_checks": {"level_exceptions": {}},
                          "decisions": {"кот": "keep"}}, prune(summary, "retired_sample"))

    def test_manifest_top_level_is_not_a_summary(self):
        manifest = {"selection": {}, "retired_sample": "unrelated"}
        self.assertEqual(manifest, prune(copy.deepcopy(manifest), "retired_sample"))
        with self.assertRaisesRegex(ValueError, "JSON object"):
            prune([], "retired_sample")
