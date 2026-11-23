"""Синтетические списки; сеть и реальные словарные статьи не используются."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_word_familiarity as tool
import prepare_frequency


def sha(body):
    return hashlib.sha256(body).hexdigest()


class WordFamiliarityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="familiarity with spaces ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.words = self.root / "words.txt"
        self.frequency = self.root / "frequency.txt"
        self.config = self.root / "config.json"
        self.manifest = self.root / "manifest.json"
        self.output = self.root / "word_familiarity.txt"
        # Порядок словаря намеренно не совпадает с частотным.
        self.words.write_text("лось\nкот\nрыба\nснег\nдом\n", encoding="utf-8")
        self.frequency.write_text("кот 90\nдом 70\nрыба 30\nснег 10\nлось 1\n", encoding="utf-8")
        self.thresholds = {"frequent_max_rank": 2, "ordinary_max_rank": 4}
        self.config.write_text(json.dumps({"familiarity": self.thresholds}), encoding="utf-8")
        self.baseline = {
            "words": 5, "sha256_words_txt": sha(self.words.read_bytes()),
            "selection": {"config_file_sha256": "historical-selection-checksum", "frequency_source": {
                "snapshot": prepare_frequency.REVISION, "sha256": sha(self.frequency.read_bytes()),
                "license": "CC-BY-SA-3.0", "project": "synthetic-frequency"}},
            "untouched": {"campaign": "preserve"},
        }
        self.write_manifest()
        http_patch = patch.object(prepare_frequency, "urlopen")
        self.http = http_patch.start()
        self.addCleanup(http_patch.stop)
        self.http.side_effect = AssertionError("Сеть запрещена")

    def write_manifest(self):
        self.manifest.write_text(json.dumps(self.baseline), encoding="utf-8")

    def build(self):
        return tool.build(self.words, self.manifest, self.config, self.frequency, self.output)

    def test_inclusive_thresholds_length_and_dictionary_order(self):
        result = self.build()
        self.assertEqual(b"1\n3\n2\n2\n3\n", self.output.read_bytes())
        self.assertEqual(len(self.words.read_text().splitlines()), len(self.output.read_text().splitlines()))
        self.assertEqual({"0": 0, "1": 1, "2": 2, "3": 2}, result["distribution"])

    def test_absent_word_is_zero_and_rank_counts_non_dictionary_rows(self):
        self.frequency.write_text("это 100\nкот 10\nони 5\nдом 0\n", encoding="utf-8")
        self.baseline["selection"]["frequency_source"]["sha256"] = sha(self.frequency.read_bytes())
        self.write_manifest()
        self.build()
        self.assertEqual(b"0\n3\n0\n0\n2\n", self.output.read_bytes())

    def test_normalization_blank_lines_and_duplicates_preserve_first_rank(self):
        self.frequency.write_text("\ufeffЁЛКА 90\n\nёлка 50\nони 10\nКОТ 2\n", encoding="utf-8")
        values = tool.familiarity_values(["елка", "кот", "елки"], self.frequency, self.thresholds)
        self.assertEqual([3, 2, 0], values)

    def test_determinism_and_manifest_provenance(self):
        self.build()
        first = self.output.read_bytes(), self.manifest.read_bytes(), self.words.read_bytes()
        self.build()
        self.assertEqual(first, (self.output.read_bytes(), self.manifest.read_bytes(), self.words.read_bytes()))
        generated = json.loads(self.manifest.read_text())
        metadata = generated.pop("word_familiarity")
        self.assertEqual(self.baseline, generated)
        self.assertEqual(sha(self.output.read_bytes()), metadata["sha256"])
        self.assertEqual(self.thresholds, metadata["thresholds"])
        self.assertEqual(5, metadata["word_count"])
        self.assertEqual(self.baseline["sha256_words_txt"], metadata["words_sha256"])
        self.assertEqual(self.baseline["selection"]["frequency_source"], metadata["frequency_source"])
        self.assertEqual("CC-BY-SA-3.0", metadata["license"])
        self.http.assert_not_called()

    def test_missing_mismatched_empty_and_malformed_frequency_preserve_outputs(self):
        self.output.write_bytes(b"previous output")
        for body in (None, b"other 1\n", b"", b"\n", b"word -1\n", b"word\n"):
            with self.subTest(body=body):
                if body is None:
                    self.frequency.unlink(missing_ok=True)
                else:
                    self.frequency.write_bytes(body)
                if body != b"other 1\n" and body is not None:
                    self.baseline["selection"]["frequency_source"]["sha256"] = sha(body)
                    self.write_manifest()
                before = self.manifest.read_bytes()
                with self.assertRaises((OSError, ValueError)):
                    self.build()
                self.assertEqual(b"previous output", self.output.read_bytes())
                self.assertEqual(before, self.manifest.read_bytes())
        self.http.assert_not_called()

    def test_configurable_thresholds_and_invalid_thresholds(self):
        self.config.write_text(json.dumps({"familiarity": {
            "frequent_max_rank": 1, "ordinary_max_rank": 2}}))
        self.build()
        self.assertEqual(b"1\n3\n1\n1\n2\n", self.output.read_bytes())
        for frequent, ordinary in ((0, 2), (2, 2), (3, 2), (True, 4), (1, 2.5)):
            with self.subTest(frequent=frequent, ordinary=ordinary):
                with self.assertRaises(ValueError):
                    tool.thresholds_from({"familiarity": {
                        "frequent_max_rank": frequent, "ordinary_max_rank": ordinary}})

    def test_changed_dictionary_and_alias_output_are_rejected(self):
        self.words.write_text("кот\nдом\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Словарь"):
            self.build()
        with self.assertRaisesRegex(ValueError, "разными"):
            tool.build(self.words, self.manifest, self.config, self.frequency, self.words)
        self.assertFalse(self.output.exists())

    def test_cli_reports_blocked_for_explicit_file_with_wrong_sha(self):
        self.frequency.write_text("кот 1\n", encoding="utf-8")
        before = self.manifest.read_bytes()
        result = subprocess.run([sys.executable, str(Path(tool.__file__)),
                                 "--words", str(self.words), "--manifest", str(self.manifest),
                                 "--config", str(self.config), "--frequency-list", str(self.frequency),
                                 "--output", str(self.output)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("BLOCKED: SHA-256", result.stderr)
        self.assertFalse(self.output.exists())
        self.assertEqual(before, self.manifest.read_bytes())


if __name__ == "__main__":
    unittest.main()
