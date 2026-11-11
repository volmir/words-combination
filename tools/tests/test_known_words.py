import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_known_words as bloom


class KnownWordsTest(unittest.TestCase):
    def test_shared_vectors(self):
        vectors = json.loads((Path(bloom.__file__).parent / "known_words_vectors.json").read_text())
        data = bloom.build(vectors["words"])
        self.assertEqual(vectors["binary_hex"], data.hex())
        parsed = bloom.read(data)
        for row in vectors["queries"]:
            self.assertEqual(row["expected"], bloom.might_contain(parsed, row["raw"]), row)

    def test_all_corruption_and_truncation_rejected(self):
        data = bloom.build(["ёжик", "образец"])
        for i in range(len(data)):
            with self.assertRaises(ValueError):
                bloom.read(data[:i])
            corrupt = bytearray(data)
            corrupt[i] ^= 1
            with self.assertRaises(ValueError):
                bloom.read(corrupt)
        for invalid in (None, data + b"\0"):
            with self.assertRaises(ValueError):
                bloom.read(invalid)

    def test_invalid_headers_with_valid_checksums(self):
        for offset, value in [(8, 2), (12, 0), (12, 0xfffffff8), (16, 0), (16, 33), (20, 0), (24, 2)]:
            data = bytearray(bloom.build(["образец"]))
            struct.pack_into("<I", data, offset, value)
            data[-32:] = hashlib.sha256(data[:-32]).digest()
            with self.assertRaises(ValueError):
                bloom.read(data)

    def test_synthetic_no_false_negatives_and_fixed_seed_fpr(self):
        rng = bloom.random.Random(42)
        words = {"".join(rng.choices(bloom.LETTERS, k=8)) for _ in range(5000)}
        data = bloom.build(words)
        self.assertEqual(data, bloom.build(reversed(sorted(words))))
        report = bloom.audit(data, words, words, samples=20000)
        self.assertEqual(0, report["false_negatives"])
        self.assertTrue(0.007 < report["measured_fpr"] < 0.014, report)
        self.assertEqual(report, bloom.audit(data, words, words, samples=20000))
        with self.assertRaises(ValueError):
            bloom.audit(data, words, ["образец"])

    def test_pinned_source_and_no_writes_on_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, manifest, game = [root / p for p in ("source.json", "manifest.json", "words.txt")]
            words = ["образец", "ёжик"]
            source.write_text(json.dumps({"words": words}))
            digest = hashlib.sha256(("\n".join(sorted(words)) + "\n").encode()).hexdigest()
            manifest.write_text(json.dumps({"selection": {"input_words_sha256": digest, "input_words": 2}}))
            game.write_text("образец\n")
            candidate = root / "candidate"
            report = bloom.generate(source, manifest, game, candidate)
            self.assertEqual(1, report["game_words"])
            self.assertTrue((candidate / "known_words.bloom").is_file())
            source.write_text(json.dumps({"words": words + ["лишнее"]}))
            with self.assertRaises(ValueError):
                bloom.generate(source, manifest, game, root / "bad")
            self.assertFalse((root / "bad").exists())

    def test_invalid_inputs(self):
        for words, fpr in [([], .01), (["abc"], .01), (["кот"], 0), (["кот"], 1)]:
            with self.assertRaises(ValueError):
                bloom.build(words, fpr)
