"""LEX-04: synthetic structured evidence, no articles or live provider."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import assess_word_suitability as assess
import pick_level_words as profiles
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

    def args(self, document, **kwargs):
        atomic_json(self.root / "input.json", document)
        values = dict(input=self.root / "input.json", cache=self.root / "cache",
                      reports=self.root / "reports", snapshot=None, pairs_file=None,
                      model="configured-model", provider="codex", allow_network=False,
                      batch_size=2, timeout=30, retries=0, max_new_decisions=None,
                      max_batches=None, time_budget=None)
        values.update(kwargs)
        return argparse.Namespace(**values)

    def pairs_file(self, pairs, name="pairs.txt"):
        path = self.root / name
        path.write_text("\n".join(f"{word} {role}" for word, role in pairs) + ("\n" if pairs else ""),
                        encoding="utf-8")
        return path

    def document(self, *rows):
        return {"schema": "word-suitability-input-v1", "candidates": list(rows)}

    def selective_run(self, document, pairs, **kwargs):
        args = self.args(document, pairs_file=self.pairs_file(pairs), **kwargs)
        with patch.object(assess.SuitabilityProvider, "codex_complete", side_effect=reply) as executor:
            summary = assess.run(args)
        return summary, executor, args

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

    def test_selective_thousand_background_words_make_zero_model_requests(self):
        ordinary = ["тест" + chr(ord("а") + number // 32) + chr(ord("а") + number % 32)
                    for number in range(1000)]
        candidates = [row(word, "target", role_decision="review", reachable=True,
                          frequency=1, freq_rank=50000) for word in ordinary]
        candidates.append(row("образец", "target", role_decision="review", reachable=True,
                              frequency=1, freq_rank=50000))
        summary, executor, args = self.selective_run(
            self.document(*candidates), [("образец", "target")], allow_network=True, batch_size=40)
        self.assertEqual(1, executor.call_count)
        self.assertEqual(["образец"], [item["word"] for item in executor.call_args.args[0]["items"]])
        self.assertEqual(1, summary["requested_pairs"])
        self.assertEqual(1, summary["selected_pairs"])
        self.assertEqual(1, summary["ai_new"])
        self.assertEqual({"rule_decided": 0, "cache_hit": 0, "ai_new": 1, "unresolved": 0},
                         summary["role_counters"]["target"])
        output = json.loads((args.reports / "word_suitability.json").read_text())
        self.assertEqual(["образец"], output["pools"]["target"])
        self.assertEqual(["образец"], [row["word"] for row in output["assessments"]])
        self.assertTrue(set(ordinary).isdisjoint(row["word"] for row in output["assessments"]))

    def test_selective_bans_and_preferred_sources_are_rule_decided_without_model(self):
        candidates = [row(word, role, role_decision="keep", frequency=1000, freq_rank=1)
                      for word in sorted(assess.ROLE_EXCLUSIONS) for role in ("source", "target")]
        candidates += [row(word, "source", labels=["historical"], frequency=0, freq_rank=None,
                           role_decision="review") for word in sorted(assess.PREFERRED_SOURCES)]
        pairs = [(word, role) for word in sorted(assess.ROLE_EXCLUSIONS) for role in ("source", "target")]
        pairs += [(word, "source") for word in sorted(assess.PREFERRED_SOURCES)]
        args = self.args(self.document(*candidates), pairs_file=self.pairs_file(pairs), allow_network=True)
        with patch.object(assess.SuitabilityProvider, "codex_complete",
                          side_effect=AssertionError("rules must not call the model")) as executor:
            summary = assess.run(args)
        executor.assert_not_called()
        output = json.loads((args.reports / "word_suitability.json").read_text())
        self.assertEqual(sorted(assess.PREFERRED_SOURCES), output["pools"]["source"])
        self.assertEqual([], output["pools"]["target"])
        self.assertEqual({"rule_decided": 6, "cache_hit": 0, "ai_new": 0, "unresolved": 0},
                         summary["role_counters"]["source"])
        self.assertEqual({"rule_decided": 3, "cache_hit": 0, "ai_new": 0, "unresolved": 0},
                         summary["role_counters"]["target"])

    def test_model_cannot_override_hard_rules_in_selective_mode(self):
        banned, preferred, common = "прелюбодейка", "хлопкопункт", "трава"
        document = self.document(
            row(banned, "target", role_decision="keep", frequency=1000, freq_rank=1),
            row(preferred, "source", labels=["historical"], frequency=0, freq_rank=None),
            row(common, "target", role_decision="keep", frequency=100, freq_rank=30000))
        candidates = assess.selected_items(assess.input_items(document), [
            (banned, "target"), (preferred, "source"), (common, "target")])
        answers = {item["id"]: assess.decision(
            item, "suitable" if item["word"] == banned else "unsuitable", "Синтетическое противоречие")
            for item in candidates}
        output = assess.apply_decisions(candidates, answers, {})
        self.assertEqual([preferred], output["pools"]["source"])
        self.assertEqual([common], output["pools"]["target"])
        statuses = {row["word"]: row["assessment"]["status"] for row in output["assessments"]}
        self.assertEqual({"прелюбодейка": "unsuitable", "хлопкопункт": "suitable",
                          "трава": "suitable"}, statuses)

    def test_unknown_target_stays_pending_and_is_never_assigned(self):
        document = self.document(row("образец", "target", role_decision="review",
                                     frequency=1, freq_rank=50000))
        args = self.args(document, pairs_file=self.pairs_file([("образец", "target")]),
                         allow_network=False)
        summary = assess.run(args)
        self.assertEqual(1, summary["pending"])
        self.assertEqual({"rule_decided": 0, "cache_hit": 0, "ai_new": 0, "unresolved": 1},
                         summary["role_counters"]["target"])
        output = json.loads((args.reports / "word_suitability.json").read_text())
        self.assertEqual("selective", output["mode"])
        self.assertEqual([], output["pools"]["target"])
        self.assertEqual("pending", output["assessments"][0]["assessment_source"])
        assessed = profiles.assessments_for(document, output)
        self.assertEqual("insufficient_data", assessed[("образец", "target")]["assessment"]["status"])

    def test_ai_insufficient_target_stays_unresolved_and_is_not_assigned(self):
        document = self.document(row("образец", "target", role_decision="review",
                                     frequency=1, freq_rank=50000))
        args = self.args(document, pairs_file=self.pairs_file([("образец", "target")]),
                         allow_network=True)
        def unsure(request, schema):
            return {"results": [assess.decision(item, "insufficient_data", "Недостаточно данных")
                                for item in request["items"]]}
        with patch.object(assess.SuitabilityProvider, "codex_complete", side_effect=unsure):
            summary = assess.run(args)
        self.assertEqual((1, 0, 0), (summary["ai_new"], summary["cache_hit"], summary["pending"]))
        self.assertEqual({"rule_decided": 0, "cache_hit": 0, "ai_new": 1, "unresolved": 1},
                         summary["role_counters"]["target"])
        output = json.loads((args.reports / "word_suitability.json").read_text())
        self.assertEqual([], output["pools"]["target"])
        self.assertEqual("insufficient_data", output["assessments"][0]["assessment"]["status"])

    def test_selective_repeat_uses_cache_without_new_calls(self):
        document = self.document(row("образец"), row("портал"))
        pairs = [("образец", "target"), ("портал", "target")]
        first, executor, args = self.selective_run(document, pairs, allow_network=True)
        self.assertEqual((2, 0), (first["ai_new"], first["cache_hit"]))
        output = (args.reports / "word_suitability.json").read_bytes()
        second_args = self.args(document, pairs_file=self.pairs_file(pairs), allow_network=True,
                                cache=args.cache, reports=args.reports)
        with patch.object(assess.SuitabilityProvider, "codex_complete",
                          side_effect=AssertionError("cached decisions must not be requested")) as offline:
            second = assess.run(second_args)
        offline.assert_not_called()
        self.assertEqual((0, 2), (second["ai_new"], second["cache_hit"]))
        self.assertEqual({"rule_decided": 0, "cache_hit": 2, "ai_new": 0, "unresolved": 0},
                         second["role_counters"]["target"])
        self.assertEqual(output, (args.reports / "word_suitability.json").read_bytes())

    def test_roles_have_separate_cache_entries_and_counters(self):
        word = "магнитофон"
        document = self.document(
            row(word, "source", role_decision="review", frequency=1, freq_rank=50000),
            row(word, "target", role_decision="review", frequency=1, freq_rank=50000))
        first, _, args = self.selective_run(document, [(word, "source")], allow_network=True)
        self.assertEqual(1, first["role_counters"]["source"]["ai_new"])
        self.assertEqual(0, sum(first["role_counters"]["target"].values()))
        second_args = self.args(document, pairs_file=self.pairs_file([(word, "target")]),
                                allow_network=True, cache=args.cache, reports=args.reports)
        with patch.object(assess.SuitabilityProvider, "codex_complete", side_effect=reply):
            second = assess.run(second_args)
        self.assertEqual(1, second["role_counters"]["target"]["ai_new"])
        self.assertEqual(0, sum(second["role_counters"]["source"].values()))
        self.assertEqual(0, second["cache_hit"])
        both = self.args(document, pairs_file=self.pairs_file([(word, "source"), (word, "target")]),
                         allow_network=True, cache=args.cache, reports=args.reports)
        with patch.object(assess.SuitabilityProvider, "codex_complete",
                          side_effect=AssertionError("both roles are already cached")) as cached:
            third = assess.run(both)
        cached.assert_not_called()
        self.assertEqual(1, third["role_counters"]["source"]["cache_hit"])
        self.assertEqual(1, third["role_counters"]["target"]["cache_hit"])

    def test_selective_model_provider_or_feature_change_is_a_cache_miss(self):
        document = self.document(row("магнитофон", "target", role_decision="review",
                                     frequency=1, freq_rank=50000))
        pairs = [("магнитофон", "target")]
        first, _, args = self.selective_run(document, pairs, allow_network=True)
        self.assertEqual(1, first["ai_new"])
        for number, changed in enumerate(({"model": "other-model"}, {"provider": "opencode"})):
            with self.subTest(changed=changed):
                missed = self.args(document, pairs_file=self.pairs_file(pairs), allow_network=False,
                                   cache=args.cache, reports=self.root / f"reports-{number}", **changed)
                summary = assess.run(missed)
                self.assertEqual(1, summary["pending"])
                self.assertEqual(0, summary["cache_hit"])
                self.assertEqual(1, summary["role_counters"]["target"]["unresolved"])
        changed_document = self.document(row("магнитофон", "target", role_decision="review",
                                             frequency=99, freq_rank=50000))
        missed = self.args(changed_document, pairs_file=self.pairs_file(pairs), allow_network=False,
                           cache=args.cache, reports=self.root / "reports-feature")
        summary = assess.run(missed)
        self.assertEqual(1, summary["pending"])
        self.assertEqual(0, summary["cache_hit"])

    def test_selective_new_decision_budget_caps_the_batch(self):
        document = self.document(row("образец"), row("портал"), row("тор"))
        pairs = [("образец", "target"), ("портал", "target"), ("тор", "target")]
        summary, executor, _ = self.selective_run(document, pairs, allow_network=True,
                                                  batch_size=2, max_new_decisions=1)
        self.assertEqual(1, executor.call_count)
        self.assertEqual(1, len(executor.call_args.args[0]["items"]))
        self.assertEqual(1, summary["ai_new"])
        self.assertEqual(2, summary["pending"])
        self.assertEqual(1, summary["batches"])
        self.assertEqual(2, summary["role_counters"]["target"]["unresolved"])
        self.assertIn("лимит новых", summary["error"])

    def test_selective_empty_pairs_make_zero_calls(self):
        document = self.document(row("образец"))
        args = self.args(document, pairs_file=self.pairs_file([]), allow_network=True)
        with patch.object(CategoryProvider, "exchange",
                          side_effect=AssertionError("empty list must not touch the provider")) as exchange:
            summary = assess.run(args)
        exchange.assert_not_called()
        self.assertEqual("selective", summary["mode"])
        self.assertEqual((0, 0, 0), (summary["requested_pairs"], summary["selected_pairs"], summary["pending"]))
        self.assertEqual({"rule_decided": 0, "cache_hit": 0, "ai_new": 0, "unresolved": 0},
                         summary["role_counters"]["target"])

    def test_selective_snapshot_reuses_only_explicit_compatible_pairs(self):
        document = self.document(row("образец"), row("портал"))
        first, _, args = self.selective_run(document, [("образец", "target")], allow_network=True)
        self.assertEqual(1, first["ai_new"])
        snapshot = args.reports / "suitability_decisions.json"
        subset = self.args(document, pairs_file=self.pairs_file([("образец", "target")]),
                           snapshot=snapshot, cache=self.root / "fresh-cache",
                           reports=self.root / "fresh-reports", allow_network=False)
        summary = assess.run(subset)
        self.assertEqual((1, 0, 0), (summary["cache_hit"], summary["ai_new"], summary["pending"]))
        self.assertEqual(1, summary["role_counters"]["target"]["cache_hit"])
        both = self.args(document, pairs_file=self.pairs_file(
            [("образец", "target"), ("портал", "target")]), snapshot=snapshot,
            cache=self.root / "fresh-cache-2", reports=self.root / "fresh-reports-2",
            allow_network=False)
        resumed = assess.run(both)
        self.assertEqual((1, 1), (resumed["cache_hit"], resumed["pending"]))
        self.assertEqual(1, resumed["role_counters"]["target"]["cache_hit"])
        self.assertEqual(1, resumed["role_counters"]["target"]["unresolved"])


if __name__ == "__main__":
    unittest.main()
