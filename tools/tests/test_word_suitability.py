"""LEX-04: synthetic structured evidence, no articles or live provider."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import assess_word_suitability as assess
from classify_word_categories import export_snapshot, import_snapshot
from game_data_providers import CategoryProvider, atomic_json, json_digest


def row(word="образец", role="target", **features):
    return {"word": word, "role": role, "features": dict(
        noun=True, role_decision="review", reachable=True, frequency=2, freq_rank=90000, labels=[]) | features}


def items(*rows):
    return assess.input_items({"schema": "word-suitability-input-v1", "candidates": list(rows)})


def reply(request, schema):
    return {"results": [assess.decision(item, "suitable", "Понятное слово") for item in request["items"]]}


class WordSuitabilityTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def provider(self, **options):
        return assess.SuitabilityProvider(self.root / "cache", "configured-model", **(
            dict(allow_network=True, executor=reply, batch_size=2, retries=0) | options))

    def test_three_owner_bans_override_model_for_both_roles(self):
        candidates = items(*(row(word, role, role_decision="keep", frequency=1000, freq_rank=1)
                             for word in assess.ROLE_EXCLUSIONS for role in ("source", "target")))
        answers = {item["id"]: assess.decision(item, "suitable", "Model disagrees") for item in candidates}
        output = assess.apply_decisions(candidates, answers, self.provider().context)
        self.assertEqual({"source": [], "target": []}, output["pools"])
        self.assertTrue(all(r["assessment"]["status"] == "unsuitable" for r in output["assessments"]))

    def test_three_preferences_only_apply_to_reachable_source_not_target(self):
        for word in assess.PREFERRED_SOURCES:
            source, = items(row(word, "source", labels=["historical"], frequency=0, freq_rank=None))
            target, = items(row(word, "target", labels=["historical"], frequency=0, freq_rank=None))
            self.assertEqual("suitable", assess.rule_decision(source)["status"])
            self.assertIsNone(assess.rule_decision(target))
            output = assess.apply_decisions([source, target], {}, self.provider().context)
            self.assertEqual([word], output["pools"]["source"])
            self.assertEqual([], output["pools"]["target"])
            for reachable, expected in ((False, "unsuitable"), (None, "insufficient_data")):
                candidate, = items(row(word, "source", reachable=reachable))
                self.assertEqual(expected, assess.rule_decision(candidate)["status"])
            for change in ({"noun": False}, {"role_decision": "exclude"}, {"labels": ["offensive"]}):
                candidate, = items(row(word, "source", **change))
                self.assertEqual("unsuitable", assess.rule_decision(candidate)["status"])

    def test_preference_does_not_bypass_unrelated_lexical_uncertainty(self):
        for word in assess.PREFERRED_SOURCES:
            for label in ("unknown_label", "diminutive", "derived_feminine", "jargon"):
                candidate, = items(row(word, "source", labels=[label]))
                self.assertIsNone(assess.rule_decision(candidate))
                output = assess.apply_decisions([candidate], {}, {})
                self.assertEqual([], output["pools"]["source"])

    def test_source_preference_overrides_negative_model_without_promoting_target(self):
        for word in assess.PREFERRED_SOURCES:
            candidates = items(row(word, "source"), row(word, "target"))
            answers = {i["id"]: assess.decision(i, "unsuitable", "Слишком редкое слово") for i in candidates}
            output = assess.apply_decisions(candidates, answers, {})
            self.assertEqual([word], output["pools"]["source"])
            self.assertEqual([], output["pools"]["target"])

    def test_hard_role_reachability_and_length_always_win(self):
        candidates = items(row("образец", "source"), row("портал", reachable=False),
                           row("тор", noun=False), row("трава", labels=["productive_diminutive"]),
                           row("птица", role_decision="exclude"), row("лодка", reachable=None))
        answers = {item["id"]: assess.decision(item, "suitable", "Yes") for item in candidates}
        output = assess.apply_decisions(candidates, answers, {})
        self.assertEqual([], output["pools"]["target"])
        self.assertEqual([], output["pools"]["source"])

    def test_common_neutral_rules_and_model_unknown_exclusion(self):
        candidates = items(row("трава", role_decision="keep", frequency=100, freq_rank=30000), row("образец"))
        disputed = [i for i in candidates if assess.rule_decision(i) is None]
        self.assertEqual(["образец"], [i["word"] for i in disputed])
        for status in ("insufficient_data", "unsuitable", "suitable"):
            answers = {i["id"]: assess.decision(i, status, "Синтетическая причина") for i in disputed}
            output = assess.apply_decisions(candidates, answers, {})
            self.assertEqual(["образец", "трава"] if status == "suitable" else ["трава"], output["pools"]["target"])
            # This tool does not alter the ordinary answer dictionary or incoming role decisions.
            self.assertEqual("review", disputed[0]["features"]["role_decision"])

    def test_structured_input_cannot_contain_article_text(self):
        invalid = [dict(row(), definition="do not send"), row(text="do not send"),
                   row(labels=["do not send"]), row(frequency=True), row(freq_rank=0), row(reachable="yes")]
        for candidate in invalid:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                items(candidate)
        with self.assertRaises(ValueError):
            items(row(), row())
        provider = self.provider(executor=Mock(side_effect=reply))
        provider.exchange(items(row()))
        request = provider.executor.call_args.args[0]
        self.assertEqual(assess.VERSION, request["task"])
        self.assertIn("policy", request)
        self.assertIn("Не оценивай качество текстов", request["prompt"])
        self.assertNotEqual(CategoryProvider.task, provider.task)

    def test_model_response_shape_ids_version_reason_and_enum(self):
        candidates = items(row(), row("портал"))
        valid = reply({"items": candidates}, {})
        invalid = [{}, {"results": []}, {"results": valid["results"] * 2}]
        for field, value in (("id", "alien"), ("status", "maybe"), ("status", []),
                             ("reason", ""), ("reason", "x" * 161), ("reason", "x\ny"),
                             ("assessment_version", "old"), ("definition", "unwanted")):
            payload = copy.deepcopy(valid)
            payload["results"][0][field] = value
            invalid.append(payload)
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                assess.validate_results(payload, candidates, assess.POLICY)

    def test_invalid_batch_retries_without_poisoning_cache(self):
        executor = Mock(return_value={"results": []})
        provider = self.provider(executor=executor, retries=2)
        answers, pending, error = provider.exchange(items(row(), row("портал")))
        self.assertEqual(({}, 2), (answers, pending))
        self.assertTrue(error)
        self.assertEqual(3, executor.call_count)
        self.assertEqual([], list(self.root.glob("cache/*.json")))

    def test_partial_batches_resume_only_unfinished_and_replay_without_network(self):
        candidates = items(row(), row("портал"), row("тор"))
        executor = Mock(side_effect=[reply({"items": candidates[:2]}, {}), TimeoutError("private")])
        answers, pending, error = self.provider(executor=executor).exchange(candidates)
        self.assertEqual((2, 1), (len(answers), pending))
        self.assertNotIn("private", error)
        executor = Mock(side_effect=reply)
        self.provider(executor=executor).exchange(candidates)
        self.assertEqual(candidates[2:], executor.call_args.args[0]["items"])
        offline = Mock(side_effect=AssertionError("network"))
        answers, pending, error = self.provider(allow_network=False, executor=offline).exchange(candidates)
        self.assertEqual((3, 0, None), (len(answers), pending, error))
        offline.assert_not_called()

    def test_cache_identity_changes_with_each_feature_role_and_context(self):
        provider = self.provider()
        candidate, = items(row("магнитофон"))
        provider.exchange([candidate])
        changes = [{"noun": False}, {"role_decision": "keep"}, {"reachable": False},
                   {"frequency": 3}, {"freq_rank": 123}, {"labels": ["rare"]}]
        for change in changes:
            changed, = items(row("магнитофон", **change))
            self.assertNotEqual(provider.key(candidate), provider.key(changed))
            self.assertIsNone(provider.read(changed))
        source, = items(row("магнитофон", "source"))
        self.assertNotEqual(provider.key(candidate), provider.key(source))
        for field in provider.context:
            changed = self.provider()
            changed.context = dict(provider.context, **{field: "new"})
            self.assertNotEqual(provider.key(candidate), changed.key(candidate))

    def test_cache_corruption_is_detected(self):
        provider = self.provider()
        candidate, = items(row())
        provider.exchange([candidate])
        envelope = json.loads(provider.path(candidate).read_text())
        envelope["result"]["status"] = "maybe"
        envelope["sha256"] = json_digest({k: v for k, v in envelope.items() if k != "sha256"})
        atomic_json(provider.path(candidate), envelope)
        with self.assertRaises(ValueError):
            provider.exchange([candidate])

    def test_cli_offline_snapshot_fresh_cache_and_repeat_are_identical(self):
        document = {"schema": "word-suitability-input-v1", "candidates": [row(), row("портал")]}
        candidates = assess.input_items(document)
        provider = self.provider()
        answers, _, _ = provider.exchange(candidates)
        path = self.root / "snapshot.json"
        snapshot = export_snapshot(path, provider, document, candidates, answers)
        atomic_json(self.root / "input.json", document)
        command = [sys.executable, str(assess.ROOT / "tools/assess_word_suitability.py"),
                   "--input", str(self.root / "input.json"), "--snapshot", str(path),
                   "--cache", str(self.root / "offline-cache"), "--reports", str(self.root / "reports")]
        environment = dict(os.environ, CATEGORY_MODEL="", CODEX_HOME=str(self.root / "no-auth"), PATH="")
        for key in ("OPENAI_API_KEY", "CODEX_API_KEY"):
            environment.pop(key, None)
        first = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, first.returncode, first.stderr)
        output = (self.root / "reports/word_suitability.json").read_bytes()
        second = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(output, (self.root / "reports/word_suitability.json").read_bytes())
        changed = copy.deepcopy(document)
        changed["candidates"][0]["features"]["frequency"] = 99
        with self.assertRaises(ValueError):
            import_snapshot(snapshot, provider, changed, assess.input_items(changed))

    def test_snapshot_rejects_policy_model_task_and_partial_resume(self):
        document = {"schema": "word-suitability-input-v1", "candidates": [row(), row("портал")]}
        candidates = assess.input_items(document)
        provider = self.provider()
        answer = assess.decision(candidates[0], "insufficient_data", "Не уверен")
        snapshot = export_snapshot(self.root / "partial.json", provider, document, candidates,
                                   {answer["id"]: answer})
        offline = assess.SuitabilityProvider(self.root / "fresh", "configured-model")
        import_snapshot(snapshot, offline, document, candidates)
        answers, pending, _ = offline.exchange(candidates)
        self.assertEqual((1, 1), (len(answers), pending))
        for mutate in (lambda s: s["policy"].update(assessment_version="old"),
                       lambda s: s["context"].update(model="other"),
                       lambda s: s.update(schema="word-category-decisions-v1"),
                       lambda s: s["results"].append(s["results"][0])):
            changed = copy.deepcopy(snapshot)
            mutate(changed)
            changed["sha256"] = json_digest({k: v for k, v in changed.items() if k != "sha256"})
            with self.assertRaises(ValueError):
                import_snapshot(changed, offline, document, candidates)

    def test_no_network_flag_and_missing_model_leave_honest_pending(self):
        executor = Mock(side_effect=AssertionError("network"))
        self.assertEqual(({}, 1), self.provider(allow_network=False, executor=executor).exchange(items(row()))[:2])
        executor.assert_not_called()
        with self.assertRaises(ValueError):
            assess.SuitabilityProvider(self.root / "empty", None)

    def test_deterministic_ties_and_source_preference(self):
        candidates = items(row("магнитофон", "source"), row("хлопкопункт", "source", freq_rank=None),
                           row("программист", "source"))
        answers = {i["id"]: assess.decision(i, "suitable", "Yes") for i in candidates}
        output = assess.apply_decisions(candidates, answers, {})
        self.assertEqual(["хлопкопункт", "магнитофон", "программист"], assess.rank_suitable(output, "source"))
        self.assertEqual(assess.rank_suitable(output, "source"), assess.rank_suitable(
            assess.apply_decisions(list(reversed(candidates)), answers, {}), "source"))


if __name__ == "__main__":
    unittest.main()
