"""OPT-02: synthetic tests for the guarded v1 suitability importer."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace as NS

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import assess_word_suitability as assess
import import_v1_suitability as importer
from game_data_providers import json_digest, prompt_hash


def context(model="gpt-6.1-sol", provider="codex"):
    return {"task": assess.VERSION, "prompt_sha256": prompt_hash(assess.PROMPT),
            "taxonomy_sha256": json_digest(assess.POLICY),
            "schema_sha256": json_digest(assess.result_schema(assess.POLICY)),
            "provider": provider, "model": model}


def features(**changed):
    return dict(noun=True, role_decision="review", reachable=True,
                frequency=2, freq_rank=90000, labels=[]) | changed


def document(*rows):
    return {"schema": "word-suitability-input-v1",
            "candidates": [{"word": word, "role": role, "features": features(**changed)}
                           for word, role, changed in rows]}


def snapshot(document_value, statuses=None, ctx=None, items=None, results=None):
    items = items if items is not None else assess.input_items(document_value)
    if results is None:
        statuses = statuses or ["suitable"] * len(items)
        results = [assess.decision(item, status, "Синтетическая причина")
                   for item, status in zip(items, statuses)]
    body = {"schema": "word-suitability-decisions-v1", "context": ctx or context(),
            "taxonomy": assess.POLICY, "input_sha256": json_digest(document_value),
            "items": items, "results": results, "complete": len(results) == len(items),
            "policy": {"assessment_version": assess.VERSION}}
    return dict(body, sha256=json_digest(body))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")


class ImportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.cache, self.output = self.root / "cache", self.root / "report.json"
        self.snapshot_path, self.v2_path = self.root / "snapshot.json", self.root / "v2.json"

    def arguments(self, v1_document, v2_document, *, statuses=None, ctx=None,
                  target_context=None, apply=False, items=None, results=None,
                  manifests=False, v1_input=False):
        snapshot_value = snapshot(v1_document, statuses, ctx, items, results)
        write_json(self.snapshot_path, snapshot_value)
        write_json(self.v2_path, v2_document)
        context_path = None
        if target_context is not None:
            context_path = self.root / "context.json"
            write_json(context_path, {"context": target_context})
        v1_input_path = None
        if v1_input:
            v1_input_path = self.root / "v1-input.json"
            write_json(v1_input_path, v1_document)
        first_manifest = second_manifest = None
        if manifests:
            first_manifest, second_manifest = self.root / "v1-manifest.json", self.root / "v2-manifest.json"
            write_json(first_manifest, {"source": {"sha256_input": "d" * 64},
                                        "selection": {"frequency_source": {"sha256": "f" * 64}}})
            write_json(second_manifest, {"source": {"sha256_input": "d" * 64},
                                         "selection": {"frequency_source": {"sha256": "f" * 64}}})
        return NS(v1_snapshot=self.snapshot_path, v1_input=v1_input_path,
                  v2_input=self.v2_path, v2_context=context_path, v2_current=None,
                  cache=self.cache, v1_manifest=first_manifest, v2_manifest=second_manifest,
                  v1_words=None, output=self.output, apply=apply)

    def cache_files(self):
        return sorted(self.cache.glob("*.json")) if self.cache.exists() else []

    def test_zero_compatible_is_a_noop_report(self):
        first, second = document(("образец", "target", {})), document(("другой", "target", {}))
        report = importer.run(self.arguments(first, second, apply=True))
        self.assertTrue(report["no_op"])
        self.assertEqual("no-op", report["status"])
        self.assertEqual(0, report["counts"]["compatible"])
        self.assertEqual(1, report["reasons"][importer.NO_PAIR])
        self.assertEqual([], self.cache_files())
        self.assertTrue(self.output.exists())

    def test_empty_snapshot_is_a_noop_report(self):
        value = document(("образец", "target", {}))
        report = importer.run(self.arguments(value, value, items=[], results=[], apply=True))
        self.assertTrue(report["no_op"])
        self.assertEqual(0, report["counts"]["v1_items"])
        self.assertEqual(0, report["counts"]["compatible"])
        self.assertEqual([], self.cache_files())

    def test_compatible_record_preserves_context_item_result_and_sha(self):
        value = document(("образец", "target", {}))
        args = self.arguments(value, value, v1_input=True, manifests=True)
        dry = importer.run(args)
        self.assertEqual("dry-run", dry["status"])
        self.assertEqual(1, dry["counts"]["compatible"])
        self.assertEqual(0, dry["counts"]["written"])
        self.assertEqual([], self.cache_files())
        self.assertEqual(json_digest(value), dry["v1"]["input_sha256"])
        self.assertEqual(dry["v1"]["snapshot_sha256"],
                         json.loads(self.snapshot_path.read_text(encoding="utf-8"))["sha256"])

        args.apply = True
        report = importer.run(args)
        self.assertEqual("applied", report["status"])
        self.assertEqual(1, report["counts"]["written"])
        self.assertEqual(1, report["savings"]["new_ai_decisions_avoided"])
        self.assertEqual(1, report["savings"]["suitable_decisions_avoided"])
        self.assertTrue(report["manifests"]["dump_equal"])

        snap = json.loads(self.snapshot_path.read_text(encoding="utf-8"))
        item, result = snap["items"][0], snap["results"][0]
        key = json_digest([snap["context"], item])
        path = self.cache / (key + ".json")
        envelope = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual({"context", "item", "result", "sha256"}, set(envelope))
        self.assertEqual(snap["context"], envelope["context"])
        self.assertEqual(item, envelope["item"])
        self.assertEqual(result, envelope["result"])
        self.assertEqual(key, report["records"][0]["cache_key"])
        self.assertEqual(envelope["sha256"], report["records"][0]["envelope_sha256"])
        provider = assess.SuitabilityProvider(self.cache, snap["context"]["model"],
                                              provider=snap["context"]["provider"])
        self.assertEqual(result, provider.read(item))

    def test_changed_role_is_rejected(self):
        first, second = document(("образец", "target", {})), document(("образец", "source", {}))
        report = importer.run(self.arguments(first, second, apply=True))
        self.assertEqual(0, report["counts"]["compatible"])
        self.assertEqual(1, report["reasons"][importer.ROLE_CHANGED])
        self.assertEqual(["source"], report["rejected_examples"][0]["v2_roles"])
        self.assertEqual([], self.cache_files())

    def test_changed_input_is_rejected(self):
        first = document(("образец", "target", {}))
        second = document(("образец", "target", {"reachable": False}))
        report = importer.run(self.arguments(first, second, apply=True))
        self.assertEqual(0, report["counts"]["compatible"])
        self.assertEqual(1, report["reasons"][importer.INPUT_CHANGED])
        self.assertEqual(["reachable"], report["rejected_examples"][0]["changed_features"])
        self.assertEqual([], self.cache_files())

    def test_collision_of_two_v1_items_for_one_pair_is_rejected(self):
        value = document(("образец", "target", {}))
        first = assess.input_items(value)[0]
        second_features = dict(first["features"], reachable=False)
        second = {"id": json_digest([first["word"], first["role"], second_features]),
                  "word": first["word"], "role": first["role"], "features": second_features}
        assess.validate_item(second, assess.POLICY)
        results = [assess.decision(item, "suitable", "Синтетическая причина")
                   for item in (first, second)]
        args = self.arguments(value, value, items=[first, second], results=results, apply=True)
        with self.assertRaisesRegex(ValueError, "Неоднозначная пара"):
            importer.run(args)
        self.assertEqual([], self.cache_files())

    def test_ambiguous_v2_duplicate_pair_is_rejected(self):
        value = document(("образец", "target", {}))
        duplicated = {"schema": value["schema"], "candidates": value["candidates"] * 2}
        with self.assertRaisesRegex(ValueError, "Повтор слова"):
            importer.run(self.arguments(value, duplicated, apply=True))
        self.assertEqual([], self.cache_files())

    def test_repeat_run_is_idempotent(self):
        value = document(("образец", "target", {}))
        args = self.arguments(value, value, apply=True)
        first = importer.run(args)
        files = self.cache_files()
        before = files[0].read_bytes()
        second = importer.run(args)
        self.assertEqual(1, first["counts"]["written"])
        self.assertEqual(0, second["counts"]["written"])
        self.assertEqual(1, second["counts"]["already_present"])
        self.assertEqual("unchanged", second["status"])
        self.assertEqual([files[0]], self.cache_files())
        self.assertEqual(before, files[0].read_bytes())

    def test_active_context_is_noop_and_keeps_its_name(self):
        value = document(("образец", "target", {}))
        active = context(model="other-model", provider="opencode")
        report = importer.run(self.arguments(value, value, target_context=active, apply=True))
        self.assertTrue(report["no_op"])
        self.assertEqual(0, report["counts"]["compatible"])
        self.assertEqual(1, report["reasons"][importer.CONTEXT_CHANGED])
        self.assertEqual(active, report["context"])
        self.assertFalse(report["context_matches_v1"])
        self.assertEqual([], self.cache_files())

    def test_conflicting_cache_aborts_before_any_write(self):
        value = document(("образец", "target", {}), ("пример", "target", {}))
        snap = snapshot(value)
        provider = assess.SuitabilityProvider(self.cache, snap["context"]["model"],
                                              provider=snap["context"]["provider"])
        first, second = snap["items"]
        provider.store(first, assess.decision(first, "unsuitable", "Другой ответ"))
        conflict_path = provider.path(first)
        before = conflict_path.read_bytes()
        args = self.arguments(value, value, apply=True)
        with self.assertRaisesRegex(ValueError, "Конфликт кэша"):
            importer.run(args)
        self.assertEqual(before, conflict_path.read_bytes())
        self.assertFalse(provider.path(second).exists())

    def test_rule_decided_item_does_not_import_stale_ai_status(self):
        value = document(("образец", "target", {"role_decision": "keep", "frequency": 200,
                                                "freq_rank": 100}))
        snap = snapshot(value, statuses=["unsuitable"])
        args = self.arguments(value, value, items=snap["items"], results=snap["results"], apply=True)
        report = importer.run(args)
        self.assertEqual(0, report["counts"]["compatible"])
        self.assertEqual(1, report["reasons"][importer.RULE_DECIDED])
        self.assertEqual([], self.cache_files())

    def test_different_manifest_dump_is_rejected(self):
        value = document(("образец", "target", {}))
        args = self.arguments(value, value, manifests=True)
        manifest = json.loads(args.v2_manifest.read_text(encoding="utf-8"))
        manifest["source"]["sha256_input"] = "e" * 64
        write_json(args.v2_manifest, manifest)
        with self.assertRaisesRegex(ValueError, "разные исходные дампы"):
            importer.run(args)
        self.assertEqual([], self.cache_files())

    def test_snapshot_tampering_is_rejected(self):
        value = document(("образец", "target", {}))
        snap = snapshot(value)
        for changed in ("sha256", "context", "items"):
            tampered = copy.deepcopy(snap)
            if changed == "sha256":
                tampered["sha256"] = "0" * 64
            elif changed == "context":
                tampered["context"] = dict(snap["context"], model="renamed")
            else:
                tampered["items"][0]["word"] = "другой"
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                args = self.arguments(value, value)
                write_json(self.snapshot_path, tampered)
                importer.run(args)


if __name__ == "__main__":
    unittest.main()
