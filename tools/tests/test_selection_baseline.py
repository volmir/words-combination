"""OPT-03: synthetic tests for the read-only selection baseline."""
import copy
import json
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import audit_selection_baseline as baseline
import pick_level_words as measurements
import select_level_words as selection
from game_data_providers import json_digest


ALPHABET = selection.read_alphabet()


def metric_metadata(answers, theme="plants"):
    return {word: {"category": theme, "category_source": "rules:synthetic",
                   "observed_frequency": {"frequency": 100, "freq_rank": 1}}
            for word in answers}


def profile_for(answers, theme):
    metadata = metric_metadata(answers, theme)
    config = measurements.load_config(measurements.CONFIG)
    measured = measurements.metrics(answers, metadata, config)
    return {
        "source_eligible": True,
        "answers": list(answers),
        "recognizable_answers": list(answers),
        "assigned_targets": list(answers),
        "riddle_targets": [word for word in answers if len(word) >= 6],
        "recognizable": measured,
        "all": measured,
    }


def token(number, length):
    letters = []
    for _ in range(length):
        letters.append(ALPHABET[number % 32])
        number //= 32
    return "".join(letters)


def synthetic_sources():
    """30 unique normalized sources covering all 32 letters."""
    sources = []
    for index in range(30):
        word = "".join([
            ALPHABET[index % 32],
            ALPHABET[(index + 1) % 32],
            ALPHABET[(index + 2) % 32],
            *[ALPHABET[(index * 7 + step) % 32] for step in range(5)],
        ])
        while word in sources:
            word += ALPHABET[(len(word) + index) % 32]
        sources.append(word)
    return sources


def synthetic_case(*, band_min=2, band_max=100, retain_min=2):
    sources = synthetic_sources()
    profiles = {}
    for index, source in enumerate(sources):
        short = [ALPHABET[(index + step) % 32] + ALPHABET[(index + step * 2 + 3) % 32]
                 + ALPHABET[(index + step * 3 + 5) % 32] for step in range(3)]
        four = "".join(ALPHABET[(index + step) % 32] for step in range(4))
        six, six_other = token(index, 6), token(index + 30, 6)
        answers = sorted(set(short + [four, six, six_other]))
        profiles[source] = profile_for(answers, f"theme{index}")
    payload = {"profiles": profiles, "words": metric_metadata(
        [answer for value in profiles.values() for answer in value["recognizable_answers"]])}
    profile_document = {"schema": baseline.PROFILE_SCHEMA, "payload": payload,
                        "payload_sha256": json_digest(payload), "inputs": {}}
    proposal = {
        "schema": baseline.PROPOSAL_SCHEMA,
        "status": "search_exhausted",
        "installable": False,
        "inputs": {},
        "slots": [{"index": index + 1, "word": word, "local_violations": []}
                  for index, word in enumerate(sources)],
        "collisions": [],
    }
    previous = [{"index": index + 1, "word": word}
                for index, word in enumerate(sources[:retain_min])]
    config = selection.load_config()
    config.update(levels=30, retain_min=retain_min, retain_max=retain_min + 2,
                  mean_jaccard_max=1.0, max_jaccard_max=1.0)
    config["bands"] = [dict(first=first, last=last, min=band_min, max=band_max)
                       for first, last in ((1, 5), (6, 10), (11, 15),
                                           (16, 20), (21, 25), (26, 30))]
    return profile_document, proposal, previous, config


class SelectionBaselineTest(unittest.TestCase):
    def report(self, case=None, **kwargs):
        profile_document, proposal, previous, config = case or synthetic_case()
        return baseline.build_report(profile_document, proposal, previous, config,
                                     ALPHABET, **kwargs)

    def test_thirty_slots_produce_exactly_435_pairs(self):
        report = self.report()
        pairs = report["jaccard"]["pairs"]
        self.assertEqual(435, report["jaccard"]["pairs_expected"])
        self.assertEqual(435, report["jaccard"]["pairs_observed"])
        self.assertEqual(435, len(pairs))
        self.assertEqual(435, len({tuple(pair["slots"]) for pair in pairs}))
        self.assertTrue(report["jaccard"]["pairs_complete"])
        self.assertAlmostEqual(sum(pair["jaccard"] for pair in pairs) / 435,
                               report["jaccard"]["mean"])
        self.assertEqual(30, report["candidate"]["selected_slots"])
        self.assertEqual(30, report["candidate"]["unique_sources"])

    def test_pair_formula_uses_intersection_over_union_and_empty_union_is_zero(self):
        metrics = baseline.pair_metrics([
            {"slot": 1, "word": "один", "recognizable_answers": ["кот", "ток"]},
            {"slot": 2, "word": "два", "recognizable_answers": ["ток", "сок"]},
            {"slot": 3, "word": "три", "recognizable_answers": []},
        ])
        self.assertEqual(3, metrics["pairs_observed"])
        first = next(row for row in metrics["pairs"] if row["slots"] == [1, 2])
        self.assertEqual(1, first["intersection"])
        self.assertEqual(3, first["union"])
        self.assertEqual(1 / 3, first["jaccard"])
        empty = next(row for row in metrics["pairs"] if row["slots"] == [1, 3])
        self.assertEqual(0, empty["jaccard"])

    def test_changed_r_is_a_new_version_and_expected_sha_mismatch_is_explicit(self):
        profile_document, proposal, previous, config = synthetic_case()
        first = baseline.build_report(profile_document, proposal, previous, config, ALPHABET)
        changed = copy.deepcopy(profile_document)
        word = proposal["slots"][0]["word"]
        changed["payload"]["profiles"][word]["recognizable_answers"] = \
            changed["payload"]["profiles"][word]["recognizable_answers"][:-1]
        changed["payload_sha256"] = json_digest(changed["payload"])
        second = baseline.build_report(changed, proposal, previous, config, ALPHABET,
                                       expected_r_sha256=first["r"]["sha256"])
        self.assertNotEqual(first["r"]["sha256"], second["r"]["sha256"])
        self.assertTrue(second["r"]["version_mismatch"])
        self.assertEqual("r_version_mismatch", second["status"])

    def test_stale_proposal_pairs_are_an_r_version_mismatch(self):
        profile_document, proposal, previous, config = synthetic_case()
        clean = baseline.build_report(profile_document, proposal, previous, config, ALPHABET)
        proposal = copy.deepcopy(proposal)
        proposal["collisions"] = [dict(row) for row in clean["jaccard"]["pairs"]]
        proposal["collisions"][0]["intersection"] += 1
        report = baseline.build_report(profile_document, proposal, previous, config, ALPHABET)
        self.assertFalse(report["proposal_pair_check"]["matches"])
        self.assertTrue(report["r"]["proposal_pair_mismatch"])
        self.assertEqual("r_version_mismatch", report["status"])

    def test_profile_version_mismatch_is_not_silently_accepted(self):
        profile_document, proposal, previous, config = synthetic_case()
        proposal = copy.deepcopy(proposal)
        proposal["inputs"] = {"profiles_sha256": "0" * 64,
                              "profile_payload_sha256": "1" * 64}
        report = baseline.build_report(
            profile_document, proposal, previous, config, ALPHABET,
            input_hashes={"profile_file_sha256": "a" * 64,
                          "profile_payload_sha256": "b" * 64})
        self.assertTrue(report["inputs"]["profile_version_mismatch"])
        self.assertEqual("r_version_mismatch", report["status"])

    def test_best_found_and_proven_impossibility_are_separate(self):
        feasible = self.report()
        self.assertFalse(feasible["evaluation"]["proven_impossible"]["proven_infeasible"])
        self.assertEqual("best_found_valid", feasible["candidate"]["classification"])
        self.assertEqual("best_found", feasible["candidate"]["kind"])
        self.assertEqual("valid", feasible["candidate"]["validation"])
        self.assertFalse(feasible["evaluation"]["best_found"]["is_lower_bound"])
        self.assertFalse(feasible["evaluation"]["best_found"]["is_strict_minimum"])

        impossible = self.report(synthetic_case(band_min=100, band_max=200))
        self.assertTrue(impossible["evaluation"]["proven_impossible"]["proven_infeasible"])
        self.assertEqual("best_found_with_proven_impossibility",
                         impossible["candidate"]["classification"])
        self.assertEqual("proven_impossible", impossible["candidate"]["validation"])
        self.assertTrue(impossible["candidate"]["violations"])
        self.assertEqual(30, impossible["evaluation"]["best_found"]["metrics"]["selected"])
        self.assertIn("не означает невозможность", impossible["evaluation"]["note"])

    def test_reference_r_with_different_order_is_not_a_proven_minimum(self):
        profile_document, proposal, previous, config = synthetic_case()
        sources = [row["word"] for row in proposal["slots"]]
        reference = {"label": "synthetic-floor", "sources": list(reversed(sources)),
                     "reported_mean": 0.0534577047104283,
                     "reported_sum": 23.25410154903631}
        report = baseline.build_report(profile_document, proposal, previous, config,
                                       ALPHABET, reference=reference)
        self.assertFalse(report["reference"]["r"]["same_as_candidate"])
        self.assertFalse(report["reference"]["proven_minimum"])
        self.assertEqual("best_found_measured_composition",
                         report["reference"]["kind"])
        self.assertEqual(435, report["reference"]["recomputed_full_pairs"]["pairs"])

    def test_report_is_deterministic_and_contains_no_clock_or_absolute_paths(self):
        first = self.report()
        second = self.report()
        self.assertEqual(json.dumps(first, ensure_ascii=False, sort_keys=True),
                         json.dumps(second, ensure_ascii=False, sort_keys=True))
        text = json.dumps(first, ensure_ascii=False, sort_keys=True)
        self.assertNotIn("/var/www", text)
        self.assertNotIn("generated_at", text)
        self.assertEqual(0, first["new_ai_calls"])
        self.assertFalse(first["publication"])

    def test_thirty_slot_contract_rejects_a_short_candidate(self):
        profile_document, proposal, previous, config = synthetic_case()
        proposal = copy.deepcopy(proposal)
        proposal["slots"] = proposal["slots"][:-1]
        with self.assertRaisesRegex(ValueError, "ровно 30 слотов"):
            baseline.build_report(profile_document, proposal, previous, config, ALPHABET)

    def test_short_word_and_band_metrics_are_reported_per_level(self):
        report = self.report()
        self.assertEqual(30, len(report["short_words"]["per_level"]))
        self.assertEqual(30, len(report["levels"]["slot_metrics"]))
        self.assertEqual(6, len(report["bands"]))
        self.assertEqual(435, report["jaccard"]["pairs_observed"])
        self.assertEqual(32, report["alphabet"]["covered_count"])
        self.assertEqual(2, report["levels"]["retained"]["count"])


if __name__ == "__main__":
    unittest.main()
