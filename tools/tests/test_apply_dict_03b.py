"""DICT-03B: synthetic source features and offline failure cases only."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apply_dict_03b as tool
import build_dictionary as dictionary
from game_data_providers import atomic_json
from prepare_frequency import REVISION


class PolicyTest(unittest.TestCase):
    def test_list_is_exact_and_cannot_overlap_an_exclusion(self):
        config = tool.clean.load_config(tool.ROOT / "tools/selection_config.json")
        self.assertFalse(tool.APPROVED_WORDS & tool.FORBIDDEN)
        baseline = tool.original_config(config)
        self.assertFalse(tool.APPROVED_WORDS & baseline["lexicalized_diminutives"].keys())
        for word in tool.APPROVED_WORDS:
            changed = deepcopy(config)
            changed["word_exclusions"][word] = "excluded"
            with self.assertRaisesRegex(ValueError, "пересекается"):
                tool.original_config(changed)

    def test_candidates_require_only_diminutive_reasons_and_are_frequency_sorted(self):
        reasons = {"предметик": ["diminutive"], "образчик": ["ambiguous_diminutive_usage"],
                   "котик": ["diminutive", "offensive"], "домик": ["diminutive", "narrow_term"],
                   "сокращение": ["abbreviation"], "корзинка": ["ambiguous_diminutive_usage"]}
        records = {w: {"sources": [{}]} for w in reasons}
        decisions = {w: {"decision": "exclude", "frequency": i, "freq_rank": 50 - i, "reasons": r}
                     for i, (w, r) in enumerate(reasons.items(), 1)}
        rows = tool.candidates(records, decisions, set())
        self.assertEqual([r["word"] for r in rows], ["образчик", "предметик"])
        self.assertEqual([], tool.candidates(records, decisions, {"образчик", "предметик"}))
        records["предметик"]["sources"] = []
        self.assertEqual([r["word"] for r in tool.candidates(records, decisions, set())], ["образчик"])

    def test_candidates_limit_is_200(self):
        records = {str(i): {"sources": [{}]} for i in range(250)}
        decisions = {w: {"decision": "exclude", "frequency": int(w), "freq_rank": 251 - int(w),
                         "reasons": ["diminutive"]} for w in records}
        rows = tool.candidates(records, decisions, set())
        self.assertEqual(200, len(rows))
        self.assertEqual("249", rows[0]["word"])

    def test_pool_loss_is_rejected(self):
        campaign = {"countMinLength": 3, "levels": [{"number": 1, "word": "скот",
                     "tasks": [{"id": "test", "type": "T1", "n": 1}]}]}
        with self.assertRaisesRegex(ValueError, "теряет запас"):
            tool.campaign_pools(campaign, {"кот"}, set())


class RunTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = self.root / "data"
        self.data.mkdir()
        self.dump = self.root / "dump.jsonl"
        self.frequency = self.root / "frequency.txt"
        entries = [{"word": word, "lang": "Русский", "lang_code": "ru", "pos": "noun",
                    "senses": [{"glosses": ["уменьшительное от " + base]}]}
                   for word, base in tool.APPROVED_BASES.items()]
        self.dump.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries), encoding="utf-8")
        self.frequency.write_text("\n".join(word + " 500" for word in tool.APPROVED_BASES), encoding="utf-8")
        (self.data / "words.txt").write_text("кот\n", encoding="utf-8")
        dictionary.write_word_sources(self.data / "word_sources.jsonl.gz", {
            "кот": [{"title": "кот", "line": 100, "source": "https://example.invalid/кот"}]})
        atomic_json(self.data / "golden_search.json", tool.golden_counts(["кот"]))
        atomic_json(self.data / "campaign.json", {"countMinLength": 3, "levels": [{
            "number": 1, "word": "скот", "tasks": [{"id": "test", "type": "T1", "n": 1}]}]})
        for name in ("levels.json", "owned_riddles.json"):
            atomic_json(self.data / name, {})
        atomic_json(self.data / "manifest.json", {
            "words": 1, "sha256_words_txt": dictionary.sha256_file(self.data / "words.txt"),
            "sha256_word_sources": dictionary.sha256_file(self.data / "word_sources.jsonl.gz"),
            "sha256_golden_search": dictionary.sha256_file(self.data / "golden_search.json"),
            "campaign_source": {"sha256": dictionary.sha256_file(self.data / "campaign.json")},
            "owned_riddles": {}, "source": {"sha256_input": dictionary.sha256_file(self.dump)},
            "selection": {"frequency_source": {"snapshot": REVISION, "sha256": dictionary.sha256_file(self.frequency)}}})
        self.config = tool.ROOT / "tools/selection_config.json"
        self.reports = self.root / "reports"
        for constant, path in (("BASE_WORDS_SHA", "words.txt"), ("BASE_SOURCES_SHA", "word_sources.jsonl.gz")):
            patcher = patch.object(tool, constant, dictionary.sha256_file(self.data / path))
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_tool(self):
        return tool.run(self.data, self.dump, self.frequency, self.config, self.reports)

    def snapshot(self):
        return {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}

    def test_exact_additions_provenance_immutable_inputs_and_repeatability(self):
        before = self.snapshot()
        result = self.run_tool()
        self.assertEqual(7, result["words"])
        self.assertEqual(sorted(tool.APPROVED_WORDS), result["added"])
        self.assertEqual([], result["skipped"])
        sources = tool.read_sources(self.data / "word_sources.jsonl.gz")
        for word in tool.APPROVED_WORDS:
            self.assertEqual(word, sources[word][0]["title"])
            self.assertGreater(sources[word][0]["line"], 0)
        for name in ("campaign.json", "golden_search.json", "levels.json", "owned_riddles.json"):
            self.assertEqual(before[Path("data") / name], (self.data / name).read_bytes())
        after = self.snapshot()
        self.run_tool()
        self.assertEqual(after, self.snapshot())

    def test_missing_or_corrupt_inputs_write_nothing(self):
        for path in (self.dump, self.frequency):
            original = path.read_bytes()
            path.write_bytes(original + b" ")
            before = self.snapshot()
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                self.run_tool()
            self.assertEqual(before, self.snapshot())
            path.unlink()
            before = self.snapshot()
            with self.assertRaises(OSError):
                self.run_tool()
            self.assertEqual(before, self.snapshot())
            path.write_bytes(original)

    def test_words_excluded_for_other_reasons_are_skipped(self):
        words = sorted(tool.APPROVED_WORDS)
        records = {w: {"sources": [{}]} for w in words}
        before = {w: {"decision": "exclude", "reasons": ["diminutive"]} for w in words}
        after = {w: {"decision": "keep"} for w in words}
        for reason in ("offensive", "narrow_term", "abbreviation"):
            before[words[0]]["reasons"] = ["diminutive", reason]
            added, skipped = tool.select_additions(records, before, after)
            self.assertNotIn(words[0], added)
            self.assertEqual([{"word": words[0], "reasons": ["diminutive", reason]}], skipped)
