"""LEX-03: synthetic structured inputs only; no model, credentials or network."""
import argparse
import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import classify_word_categories as classify
import word_categories as thematic
from game_data_providers import (CATEGORY_PROMPT, CategoryProvider, atomic_json, json_digest,
                                 validate_category_results)


def reply(request, schema):
    return {"results": [{"id": item["id"], "category": "tools", "confidence": 0.95}
                        for item in request["items"]]}


class CategoryClassificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.taxonomy = thematic.load_taxonomy()
        metadata = {word: {"category": "other", "category_source": "unknown:no_thematic_evidence",
                           "forms": [word], "related": []} for word in ("образец", "портал", "тор")}
        metadata["птица"] = {"category": "birds", "category_source": "gloss_head:birds"}
        self.index = dict(thematic.category_index(metadata, self.taxonomy), words_sha256="synthetic-input")
        self.items = classify.classification_items(self.index, self.taxonomy)

    def provider(self, **kwargs):
        options = dict(allow_network=True, batch_size=2, retries=0, executor=reply)
        options.update(kwargs)
        return CategoryProvider(self.root / "cache", "configured-model", self.taxonomy, **options)

    def args(self, **kwargs):
        atomic_json(self.root / "input.json", self.index)
        values = dict(input=self.root / "input.json", taxonomy=thematic.TAXONOMY_PATH,
                      cache=self.root / "cache", reports=self.root / "reports", model="configured-model",
                      provider="codex", allow_network=False, batch_size=2, timeout=30, retries=0,
                      min_confidence=None, snapshot=None, gold=None)
        values.update(kwargs)
        return argparse.Namespace(**values)

    def test_only_residual_words_and_closed_features_are_sent(self):
        self.index["word_metadata"]["портал"]["definition"] = "НЕ ПЕРЕДАВАТЬ"
        self.index["word_metadata"]["портал"]["source"] = "НЕ ПЕРЕДАВАТЬ"
        self.index["classification_features"] = {"портал": {
            "reason": "ambiguous_senses", "sense_count": 2, "candidate_categories": ["birds", "tools"]}}
        executor = Mock(side_effect=reply)
        provider = self.provider(executor=executor)
        provider.exchange(classify.classification_items(self.index, self.taxonomy))
        sent = json.dumps(executor.call_args_list[0].args[0], ensure_ascii=False)
        self.assertNotIn("НЕ ПЕРЕДАВАТЬ", sent)
        self.assertNotIn("definition", sent)
        self.assertNotIn("птица", sent)
        self.assertIn("candidate_categories", sent)
        self.assertEqual(2, executor.call_count)
        self.assertIn("Не создавай определений", CATEGORY_PROMPT)

    def test_article_text_cannot_enter_allowed_features(self):
        for features in ({"text": "НЕ ПЕРЕДАВАТЬ"},
                         {"reason": "no_thematic_evidence", "sense_count": 1,
                          "candidate_categories": ["НЕ ПЕРЕДАВАТЬ"]}):
            self.index["classification_features"] = {"портал": features}
            with self.assertRaises(ValueError):
                classify.classification_items(self.index, self.taxonomy)

    def test_hard_roles_three_bans_and_alphabet_have_priority(self):
        for word in (*thematic.ROLE_EXCLUSIONS, "не-слово", "ёж", "a" * 4):
            self.index["word_metadata"][word] = {"category": "other", "category_source": "unknown:no_thematic_evidence"}
        self.index["word_metadata"]["тор"]["roles"] = {"target": {"decision": "exclude", "reasons": ["test"]}}
        items = classify.classification_items(self.index, self.taxonomy)
        self.assertEqual({"образец", "портал"}, {item["word"] for item in items})
        # Even a confident local classification cannot turn these into T6 targets.
        for word in thematic.ROLE_EXCLUSIONS:
            self.index["word_metadata"][word].update(category="society", category_source="gloss_head:society")
        provider = self.provider()
        answers, _, _ = provider.exchange(items)
        output = classify.apply_decisions(self.index, self.taxonomy, items, answers, 0.8, provider.context)
        targeted = {word for words in output["categories"].values() for word in words}
        self.assertFalse(targeted & thematic.ROLE_EXCLUSIONS)
        self.assertNotIn("тор", targeted)
        self.assertEqual(self.index["word_metadata"]["тор"]["roles"], output["word_metadata"]["тор"]["roles"])

    def test_response_requires_exact_ids_enum_finite_confidence_and_shape(self):
        valid = reply({"items": self.items}, {})
        invalid = [None, [], {}, {"results": "junk"}, dict(valid, text="junk")]
        invalid += [{"results": valid["results"][:-1]}, {"results": valid["results"] * 2}]
        for field, value in (("id", "extra"), ("category", "alien"), ("category", []),
                             ("confidence", -0.1), ("confidence", 1.1), ("confidence", True),
                             ("confidence", float("nan")), ("confidence", float("inf")), ("confidence", "0.9")):
            payload = copy.deepcopy(valid)
            payload["results"][0][field] = value
            invalid.append(payload)
        payload = copy.deepcopy(valid)
        payload["results"][0]["definition"] = "unwanted"
        invalid.append(payload)
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                validate_category_results(payload, self.items, self.taxonomy)
        self.assertEqual(valid["results"], validate_category_results(valid, self.items, self.taxonomy))

    def test_invalid_whole_batch_does_not_poison_cache_and_retries_are_bounded(self):
        executor = Mock(return_value={"results": []})
        provider = self.provider(executor=executor, retries=2)
        answers, pending, error = provider.exchange(self.items)
        self.assertEqual({}, answers)
        self.assertEqual(3, pending)
        self.assertEqual(3, executor.call_count)
        self.assertTrue(error)
        self.assertEqual([], list((self.root / "cache").glob("*.json")))

    def test_partial_cache_resumes_with_only_unfinished_words(self):
        executor = Mock(side_effect=[reply({"items": self.items[:2]}, {}), TimeoutError("secret")])
        provider = self.provider(executor=executor)
        answers, pending, error = provider.exchange(self.items)
        self.assertEqual(2, len(answers))
        self.assertEqual(1, pending)
        self.assertNotIn("secret", error)
        resumed = Mock(side_effect=reply)
        answers, pending, error = self.provider(executor=resumed).exchange(self.items)
        self.assertEqual(3, len(answers))
        self.assertEqual(0, pending)
        self.assertIsNone(error)
        self.assertEqual([self.items[2]], resumed.call_args.args[0]["items"])

    def test_cache_key_changes_with_features_word_taxonomy_task_prompt_provider_model(self):
        provider = self.provider()
        item = self.items[0]
        original = provider.key(item)
        for field in provider.context:
            modified = self.provider()
            modified.context = dict(modified.context, **{field: "changed"})
            self.assertNotEqual(original, modified.key(item), field)
        changed = copy.deepcopy(item)
        changed["features"]["sense_count"] = 2
        self.assertNotEqual(original, provider.key(changed))
        changed = dict(item, word="образчик")
        self.assertNotEqual(original, provider.key(changed))
        provider.exchange(self.items)
        switched = CategoryProvider(self.root / "cache", "another-model", self.taxonomy)
        answers, pending, _ = switched.exchange(self.items)
        self.assertEqual({}, answers)
        self.assertEqual(3, pending)

    def test_cache_reads_validate_envelope_and_result(self):
        provider = self.provider()
        provider.exchange(self.items)
        path = provider.path(self.items[0])
        envelope = json.loads(path.read_text())
        envelope["result"]["confidence"] = 2
        envelope["sha256"] = json_digest({k: v for k, v in envelope.items() if k != "sha256"})
        atomic_json(path, envelope)
        with self.assertRaises(ValueError):
            provider.exchange(self.items)

    def test_no_network_flag_prevents_all_execution_even_on_empty_cache(self):
        executor = Mock(side_effect=AssertionError("must never run"))
        answers, pending, error = self.provider(allow_network=False, executor=executor).exchange(self.items)
        self.assertEqual(({}, 3), (answers, pending))
        self.assertIn("--allow-network", error)
        executor.assert_not_called()

    def test_cli_transport_has_schema_stdin_isolated_directory_model_and_timeout(self):
        provider = self.provider(executor=None)

        def execute(command, **kwargs):
            self.assertEqual("configured-model", command[command.index("--model") + 1])
            self.assertIn("--ignore-user-config", command)
            self.assertIn("--ephemeral", command)
            self.assertIn("read-only", command)
            self.assertEqual(120, kwargs["timeout"])
            request = json.loads(kwargs["input"])
            schema = json.loads(Path(command[command.index("--output-schema") + 1]).read_text())
            self.assertEqual(sorted(self.taxonomy), schema["properties"]["results"]["items"]["properties"]["category"]["enum"])
            self.assertNotEqual(classify.ROOT, kwargs["cwd"])
            output = Path(command[command.index("--output-last-message") + 1])
            atomic_json(output, reply(request, schema))

        with patch("game_data_providers.subprocess.run", side_effect=execute) as run:
            answers, pending, _ = provider.exchange(self.items)
        self.assertEqual((3, 0), (len(answers), pending))
        self.assertEqual(2, run.call_count)

    def test_opencode_cli_transport_selects_default_executor(self):
        provider = self.provider(provider="opencode", executor=None)
        self.assertEqual(CategoryProvider.opencode_complete, provider.executor.__func__)

    def test_opencode_cli_transport_denies_tools_and_parses_last_text(self):
        provider = self.provider(provider="opencode", executor=None)
        request = provider.request_payload(self.items[:2])
        schema = provider.schema_builder(self.taxonomy)
        answer = json.dumps(reply(request, schema), ensure_ascii=False)
        events = "\n".join([
            json.dumps({"type": "step_start", "part": {"type": "step-start"}}),
            json.dumps({"type": "text", "part": {"type": "text", "text": "рассуждение"}}),
            json.dumps({"type": "text", "part": {"type": "text", "text": "```json\n" + answer + "\n```"}}),
        ]) + "\n"

        def execute(command, **kwargs):
            self.assertEqual("opencode", command[0])
            self.assertIn("--agent", command)
            self.assertIn("--format", command)
            self.assertIn("json", command)
            self.assertEqual("configured-model", command[command.index("--model") + 1])
            self.assertNotEqual(classify.ROOT, kwargs["cwd"])
            body = json.loads((Path(kwargs["cwd"]) / "opencode.json").read_text(encoding="utf-8"))
            denied = {(rule["action"], rule["effect"]) for rule in body["permissions"]}
            self.assertIn(("bash", "deny"), denied)
            self.assertIn(("edit", "deny"), denied)
            self.assertIn(("webfetch", "deny"), denied)
            self.assertIn("Схема ответа", kwargs["input"])
            return subprocess.CompletedProcess(command, 0, stdout=events, stderr="")

        with patch("game_data_providers.subprocess.run", side_effect=execute) as run:
            payload = provider.opencode_complete(request, schema)
        self.assertEqual(reply(request, schema), payload)
        self.assertEqual(1, run.call_count)

    def test_resume_command_pins_provider(self):
        args = self.args()
        self.assertIn("--provider codex", classify.resume_command(args, "configured-model", "codex"))
        self.assertIn("--provider opencode", classify.resume_command(args, "configured-model", "opencode"))

    def test_cli_garbage_timeout_and_missing_executable_are_clean_failures(self):
        for error, message in (
                (subprocess.TimeoutExpired("secret", 1), "таймаут 120 с"),
                (FileNotFoundError("secret"), "не найден исполняемый файл или файл ответа"),
                (subprocess.CalledProcessError(7, "secret", stderr="secret"), "код завершения 7"),
                (ValueError("secret"), "невалидный JSON"),
                (PermissionError("secret"), "ошибка запуска или чтения ответа")):
            with self.subTest(error=error):
                executor = Mock(side_effect=error)
                answers, pending, reason = self.provider(executor=executor).exchange(self.items)
                self.assertEqual((0, 3), (len(answers), pending))
                self.assertIn(message, reason)
                self.assertNotIn("secret", reason)

    def test_snapshot_roundtrip_without_any_provider_or_credentials(self):
        provider = self.provider()
        answers, _, _ = provider.exchange(self.items)
        path = self.root / "delivery.json"
        snapshot = classify.export_snapshot(path, provider, self.index, self.items, answers, 0.93)
        self.assertNotIn("key", snapshot["context"])
        args = self.args(cache=self.root / "fresh-cache", snapshot=path, model=None)
        with patch.object(CategoryProvider, "codex_complete", side_effect=AssertionError("offline")):
            summary = classify.run(args)
        self.assertEqual(0, summary["pending"])
        self.assertEqual(0.93, args.min_confidence)
        self.assertEqual(snapshot, json.loads((args.reports / "category_decisions.json").read_text()))
        before = (args.reports / "category_index.json").read_bytes()
        classify.run(args)
        self.assertEqual(before, (args.reports / "category_index.json").read_bytes())

    def test_partial_snapshot_import_and_resume(self):
        provider = self.provider()
        item = self.items[0]
        answer = reply({"items": [item]}, {})["results"][0]
        snapshot = classify.export_snapshot(self.root / "partial.json", provider, self.index, self.items, {item["id"]: answer})
        resumed = CategoryProvider(self.root / "fresh-cache", "configured-model", self.taxonomy)
        classify.import_snapshot(snapshot, resumed, self.index, self.items)
        answers, pending, _ = resumed.exchange(self.items)
        self.assertEqual({item["id"]: answer}, answers)
        self.assertEqual(2, pending)

    def test_actual_cli_replays_complete_snapshot_without_credentials(self):
        provider = self.provider()
        answers, _, _ = provider.exchange(self.items)
        path = self.root / "delivery.json"
        classify.export_snapshot(path, provider, self.index, self.items, answers)
        args = self.args()
        command = [sys.executable, str(classify.ROOT / "tools/classify_word_categories.py"),
                   "--input", str(args.input), "--snapshot", str(path),
                   "--cache", str(self.root / "offline-cache"), "--reports", str(self.root / "offline-report")]
        environment = {key: value for key, value in os.environ.items()
                       if key not in {"CATEGORY_MODEL", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "CODEX_API_KEY"}}
        environment["CODEX_HOME"] = str(self.root / "no-auth")
        result = subprocess.run(command, capture_output=True, text=True, timeout=10, env=environment)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, json.loads(result.stdout)["pending"])
        self.assertEqual({"ai": 3, "rules": 1}, json.loads(result.stdout)["sources"])

    def test_snapshot_cannot_silently_change_confidence_policy(self):
        provider = self.provider()
        answers, _, _ = provider.exchange(self.items)
        path = self.root / "delivery.json"
        classify.export_snapshot(path, provider, self.index, self.items, answers, 0.9)
        with self.assertRaisesRegex(ValueError, "порог"):
            classify.run(self.args(snapshot=path, min_confidence=0.8))

    def test_empty_or_malformed_snapshot_never_falls_back_to_provider(self):
        path = self.root / "invalid.json"
        for invalid in ({}, [], None, {"context": [], "policy": {}}, {"context": {}, "policy": []}):
            atomic_json(path, invalid)
            with self.subTest(invalid=invalid), patch.object(CategoryProvider, "codex_complete") as executor:
                with self.assertRaises(ValueError):
                    classify.run(self.args(snapshot=path, allow_network=True))
                executor.assert_not_called()

    def test_general_provider_unavailability_exports_pending_and_exact_resume(self):
        args = self.args(allow_network=True, retries=1)
        with patch.object(CategoryProvider, "codex_complete", side_effect=FileNotFoundError("synthetic-secret")) as executor:
            summary = classify.run(args)
        self.assertEqual(2, executor.call_count)
        self.assertEqual(3, summary["pending"])
        self.assertIn(str(args.input.resolve()), summary["resume"])
        self.assertIn("--retries 1", summary["resume"])
        self.assertNotIn("synthetic-secret", json.dumps(summary))

    def test_snapshot_rejects_model_input_taxonomy_hash_duplicates_and_missing_ids(self):
        provider = self.provider()
        answers, _, _ = provider.exchange(self.items)
        snapshot = classify.export_snapshot(self.root / "delivery.json", provider, self.index, self.items, answers)
        for mutate in (lambda s: s["context"].update(model="other"),
                       lambda s: s.update(input_sha256="changed"),
                       lambda s: s["taxonomy"].update(other="changed"),
                       lambda s: s["results"].append(s["results"][0]),
                       lambda s: s["results"].pop(),
                       lambda s: s.update(sha256="bad")):
            altered = copy.deepcopy(snapshot)
            mutate(altered)
            if altered["sha256"] != "bad":
                altered["sha256"] = json_digest({k: v for k, v in altered.items() if k != "sha256"})
            with self.assertRaises(ValueError):
                classify.import_snapshot(altered, provider, self.index, self.items)

    def test_low_confidence_and_other_do_not_become_t6_targets(self):
        provider = self.provider()
        answers = {item["id"]: {"id": item["id"], "category": "tools", "confidence": 0.9} for item in self.items}
        answers[self.items[0]["id"]]["confidence"] = 0.3
        answers[self.items[1]["id"]]["category"] = "other"
        output = classify.apply_decisions(self.index, self.taxonomy, self.items, answers, 0.8, provider.context)
        self.assertEqual([self.items[2]["word"]], output["categories"]["tools"])
        self.assertEqual("unknown:ai_low_confidence", output["word_metadata"][self.items[0]["word"]]["category_source"])
        self.assertEqual("unknown:ai_other", output["word_metadata"][self.items[1]["word"]]["category_source"])
        self.assertEqual(self.index["word_metadata"]["птица"], output["word_metadata"]["птица"])

    def test_empty_cache_generates_exact_resume_command_and_honest_report(self):
        args = self.args(input=self.root / "input.json", reports=self.root / "reports with space")
        summary = classify.run(args)
        self.assertEqual(3, summary["pending"])
        self.assertEqual({"rules": 1, "unknown": 3}, summary["sources"])
        self.assertIn("--allow-network", summary["resume"])
        self.assertIn("--snapshot", summary["resume"])
        self.assertIn("--model configured-model", summary["resume"])
        report = (args.reports / "CATEGORIES.md").read_text()
        self.assertIn("независимая разметка отсутствует (размер 0)", report)
        self.assertIn("35%", report)
        self.assertIn("90%", report)
        self.assertIsNone(summary["accuracy"])

    def test_independent_gold_is_required_for_accuracy_and_reports_its_size(self):
        gold = self.root / "gold.json"
        labels = {"schema": "word-category-gold-v1", "independent": False, "annotator": "separate-reviewer",
                  "labels": {"птица": "birds", "тор": "tools"}}
        atomic_json(gold, labels)
        with self.assertRaises(ValueError):
            classify.independent_accuracy(gold, self.index["word_metadata"], self.taxonomy)
        labels["independent"] = True
        atomic_json(gold, labels)
        accuracy = classify.independent_accuracy(gold, self.index["word_metadata"], self.taxonomy)
        self.assertEqual(2, accuracy["size"])
        self.assertEqual(0.5, accuracy["fraction"])
        self.assertEqual(0, accuracy["ai_size"])

    def test_report_caps_examples_diagnostics_and_lists_large_categories(self):
        provider = self.provider()
        metadata = {"тест" + "а" * (number // 26) + chr(ord("а") + number % 26): {
            "category": "tools", "category_source": "ai:codex:configured-model"} for number in range(360)}
        output = thematic.category_index(metadata, self.taxonomy)
        output["classification"] = {"min_confidence": 0.8}
        report = self.root / "CATEGORIES.md"
        summary = classify.write_report(report, output, self.taxonomy, {"schema": "test", "sha256": "test"}, 0)
        text = report.read_text()
        examples_line = next(line for line in text.splitlines() if line.startswith("| tools —"))
        self.assertEqual(20, examples_line.count("тест"))
        self.assertEqual(300, text.split("## Диагностическая выборка")[1].count("| тест"))
        self.assertEqual(["tools"], summary["categories_at_least_40"])

    def test_configuration_bounds_are_enforced(self):
        for options in ({"batch_size": 0}, {"batch_size": 41}, {"timeout": 0}, {"timeout": 601},
                        {"retries": -1}, {"retries": 4}, {"provider": "arbitrary"}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.provider(**options)


if __name__ == "__main__":
    unittest.main()
