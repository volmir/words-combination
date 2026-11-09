"""Только синтетические частотные данные и поддельный транспорт, без HTTP."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_frequency as frequency
import clean_dictionary as clean
import build_game_data as game


class PrepareFrequencyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="frequency with spaces ")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.cache = self.root / "cache with spaces" / "ru_full.txt"
        self.body = "кот 12\nкота 4\nелка 3\nёлка 2\n".encode()
        self.digest = hashlib.sha256(self.body).hexdigest()
        self.manifest = self.root / "manifest.json"
        self.set_manifest(self.body)
        cache_patch = patch.object(frequency, "DEFAULT_CACHE", self.cache)
        cache_patch.start()
        self.addCleanup(cache_patch.stop)
        self.http = patch.object(frequency, "urlopen")
        self.transport = self.http.start()
        self.addCleanup(self.http.stop)
        self.transport.side_effect = AssertionError("HTTP запрещён в тестах")

    def set_manifest(self, body):
        self.manifest.write_text(json.dumps({"selection": {"frequency_source": {
            "snapshot": frequency.REVISION, "sha256": hashlib.sha256(body).hexdigest()}}}))

    def cached(self, body=None):
        self.cache.parent.mkdir(parents=True, exist_ok=True)
        self.cache.write_bytes(self.body if body is None else body)

    def test_missing_and_damaged_offline_cache_fail_without_writing(self):
        for contents in (None, b"broken"):
            with self.subTest(contents=contents):
                if contents is not None:
                    self.cached(contents)
                with self.assertRaisesRegex(ValueError, "prepare_frequency.py.*--allow-network"):
                    frequency.prepare(manifest=self.manifest)
                self.transport.assert_not_called()
        self.assertEqual(b"broken", self.cache.read_bytes())
        self.assertEqual([self.cache], list(self.cache.parent.iterdir()))

    def test_download_then_repeat_never_redownloads_and_provenance_is_compatible(self):
        self.transport.side_effect = None
        self.transport.return_value = io.BytesIO(self.body)
        result = frequency.prepare(allow_network=True, manifest=self.manifest)
        self.assertEqual(self.cache.resolve(), result)
        self.transport.assert_called_once_with(frequency.DOWNLOAD_URL, timeout=60)
        stat = self.cache.stat()
        for allow_network in (False, True):
            self.assertEqual(result, frequency.prepare(allow_network=allow_network, manifest=self.manifest))
        self.transport.assert_called_once()
        self.assertEqual(stat.st_mtime_ns, self.cache.stat().st_mtime_ns)
        metadata = clean.frequency_provenance(result, self.digest)
        self.assertEqual("CC-BY-SA-3.0", metadata["license"])
        self.assertEqual(frequency.REVISION, metadata["snapshot"])
        source = json.loads((result.parent / "source.json").read_text())
        self.assertEqual(frequency.README_URL, source["license_source"])
        self.assertEqual({"ru_full.txt", "source.json"}, {p.name for p in result.parent.iterdir()})

    def test_corrupt_cache_is_repaired_only_after_validation(self):
        self.cached(b"broken")
        self.transport.side_effect = None
        self.transport.return_value = io.BytesIO(self.body)
        frequency.prepare(allow_network=True, manifest=self.manifest)
        self.assertEqual(self.body, self.cache.read_bytes())

    def test_interruption_and_wrong_hash_preserve_old_cache_and_metadata(self):
        class Interrupted(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise OSError("обрыв")
                return super().read(3)
        for response in (Interrupted(self.body), io.BytesIO(b"other 1\n")):
            with self.subTest(response=response):
                self.cached(b"previous cache")
                source = self.cache.parent / "source.json"
                source.write_text("previous metadata")
                self.transport.side_effect = None
                self.transport.return_value = response
                with self.assertRaises((OSError, ValueError)):
                    frequency.prepare(allow_network=True, manifest=self.manifest)
                self.assertEqual(b"previous cache", self.cache.read_bytes())
                self.assertEqual("previous metadata", source.read_text())
                self.assertEqual({self.cache, source}, set(self.cache.parent.iterdir()))

    def test_bad_format_even_with_matching_hash_is_not_published(self):
        for body in (b"", b"\n", b"word\n", b"word -1\n", b"word 1.2\n", b"word 2 extra\n", b"\xff 2\n"):
            with self.subTest(body=body):
                self.set_manifest(body)
                self.transport.side_effect = None
                self.transport.return_value = io.BytesIO(body)
                with self.assertRaises(ValueError):
                    frequency.prepare(allow_network=True, manifest=self.manifest)
                self.assertFalse(self.cache.exists())
                self.assertEqual([], list(self.cache.parent.iterdir()))

    def test_explicit_path_with_spaces_wins_and_is_never_replaced(self):
        path = self.root / "already here.txt"
        path.write_bytes(self.body)
        self.cached(b"broken")
        self.assertEqual(path, frequency.prepare(path, allow_network=True, manifest=self.manifest))
        self.assertEqual(path, frequency.resolve_frequency_list(path, self.manifest))
        self.assertEqual(b"broken", self.cache.read_bytes())
        path.write_bytes(b"other 1\n")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            frequency.prepare(path, allow_network=True, manifest=self.manifest)
        self.transport.assert_not_called()
        self.assertEqual(self.digest, json.loads((path.parent / "source.json").read_text())["files"][0]["sha256"])

    def test_shared_resolution_validates_default_but_never_falls_back_from_explicit(self):
        self.cached()
        self.assertEqual(self.cache, frequency.resolve_frequency_list(manifest=self.manifest))
        with self.assertRaisesRegex(ValueError, "--frequency-list"):
            frequency.resolve_frequency_list(self.root / "missing", self.manifest)
        self.cached(b"other 2\n")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            frequency.resolve_frequency_list(manifest=self.manifest)
        self.transport.assert_not_called()

    def test_clean_and_delegating_cli_prepare_only_resolve_cache_without_network(self):
        self.cached()
        dump = self.root / "dump"
        dump.write_text(json.dumps({"word": "кот", "lang_code": "ru", "lang": "Русский", "pos": "noun",
                                    "senses": [{"glosses": ["Синтетический предмет."]}]}), encoding="utf-8")
        self.manifest.write_text(json.dumps({**json.loads(self.manifest.read_text()), "source": {
            "sha256_input": hashlib.sha256(dump.read_bytes()).hexdigest(), "dump_date": "2026-11-01"}}))
        config = json.loads((clean.TOOLS / "selection_config.json").read_text())
        config["cleanup"]["level_replacements"] = {}
        config_path = self.root / "config.json"
        config_path.write_text(json.dumps(config))
        source = self.root / "words.txt"
        source.write_text("кот\n", encoding="utf-8")
        args = argparse.Namespace(dump=dump, manifest=self.manifest, frequency_list=None,
                                  definitions_source="clean", check_release=False, verify_audit=None,
                                  prepare_only=True, cache=self.root / "pipeline", reports=self.root / "reports",
                                  config=config_path, source_words=source, golden=source, out=self.root / "output")
        with patch.object(clean.dictionary, "LEVELS", [("кот", 0, 0, "к", 0)]):
            for run in (clean.run, game.run):
                args.frequency_list = None
                result = run(args)
                self.assertEqual("prepared", result["status"])
                self.assertEqual(self.digest, result["frequency_sha256"])
                self.assertFalse(result["published"])
                self.assertFalse(args.out.exists())
        self.transport.assert_not_called()

    def test_cli_explicit_path_is_a_single_argument(self):
        # Real manifest hash deliberately differs: this must report checksum, not a split path.
        path = self.root / "local list.txt"
        path.write_bytes(self.body)
        result = subprocess.run([sys.executable, str(frequency.ROOT / "tools/prepare_frequency.py"),
                                 "--frequency-list", str(path)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("SHA-256", result.stderr)
        self.assertIn(str(path), result.stderr)

    def test_lemma_forms_are_normalized_and_deduplicated_with_homonymy(self):
        self.cached()
        records = {
            "кот": {"eligible": True, "forms": ["КОТА", "кота", "кота"]},
            "елка": {"eligible": True, "forms": ["ёлка", "ЕЛКА"]},
            "омоним": {"eligible": True, "forms": ["кота"]},
            "пропуск": {"eligible": True, "forms": []},
        }
        words = game.frequencies(records, frequency_list=self.cache)["words"]
        self.assertEqual(16, words["кот"]["frequency"])
        self.assertEqual(5, words["елка"]["frequency"])
        self.assertEqual(4, words["омоним"]["frequency"])
        self.assertEqual({"frequency": 0, "freq_rank": None}, words["пропуск"])


if __name__ == "__main__":
    unittest.main()
