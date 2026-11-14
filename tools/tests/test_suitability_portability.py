"""OPT-01: synthetic tests for the read-only v1-to-v2 suitability matcher."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import assess_word_suitability as assess
import audit_suitability_portability as audit
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


def snapshot(document_value, statuses, ctx=None):
    items = assess.input_items(document_value)
    results = [assess.decision(item, status, "Синтетическая причина")
               for item, status in zip(items, statuses)]
    body = {"schema": "word-suitability-decisions-v1", "context": ctx or context(),
            "taxonomy": assess.POLICY, "input_sha256": json_digest(document_value),
            "items": items, "results": results, "complete": len(results) == len(items),
            "policy": {"assessment_version": assess.VERSION}}
    return dict(body, sha256=json_digest(body))


def load(document_value):
    v2_document, v2_items, v2_by_key = audit.load_v2_input_value(document_value)
    return v2_document, v2_items, v2_by_key


class PortabilityTest(unittest.TestCase):
    def records(self, v1_document, statuses, v2_document=None, active=None):
        snap = snapshot(v1_document, statuses)
        items = {item["id"]: item for item in snap["items"]}
        results = {result["id"]: result for result in snap["results"]}
        _, v2_items, v2_by_key = audit.load_v2_input_value(v2_document or v1_document)
        return audit.classify(snap, items, results, v2_by_key, active or context())

    def test_full_key_match_is_exact_in_both_contexts(self):
        document_value = document(("образец", "target", {}))
        record, = self.records(document_value, ["suitable"])
        self.assertEqual(audit.EXACT, record["active_class"])
        self.assertEqual(audit.EXACT, record["pinned_class"])
        self.assertEqual("suitable", record["status"])
        self.assertEqual([], record["context_diff"])

    def test_context_change_is_recheck_for_active_and_exact_for_pinned_v1(self):
        document_value = document(("образец", "target", {}))
        record, = self.records(document_value, ["suitable"],
                               active=context(model="other-model", provider="opencode"))
        self.assertEqual(audit.NEEDS, record["active_class"])
        self.assertEqual(audit.EXACT, record["pinned_class"])
        self.assertEqual(["model", "provider"], record["context_diff"])

    def test_changed_role_and_missing_pair_are_unavailable(self):
        v1_document = document(("образец", "target", {}))
        v2_document = document(("образец", "source", {}))
        record, = self.records(v1_document, ["suitable"], v2_document)
        self.assertEqual(audit.UNAVAILABLE, record["active_class"])
        self.assertEqual(audit.UNAVAILABLE, record["pinned_class"])
        self.assertEqual("no_v2_pair", record["classification_reason"])

    def test_changed_features_are_recheck_even_under_pinned_context(self):
        v1_document = document(("образец", "target", {}))
        v2_document = document(("образец", "target", {"reachable": False}))
        record, = self.records(v1_document, ["suitable"], v2_document)
        self.assertEqual(audit.NEEDS, record["active_class"])
        self.assertEqual(audit.NEEDS, record["pinned_class"])
        self.assertEqual(["reachable"], record["changed_features"])

    def test_tampered_snapshot_sha_taxonomy_or_context_is_rejected(self):
        document_value = document(("образец", "target", {}))
        snap = snapshot(document_value, ["suitable"])
        for changed in ("sha256", "taxonomy", "context"):
            tampered = copy.deepcopy(snap)
            if changed == "sha256":
                tampered["sha256"] = "0" * 64
            elif changed == "taxonomy":
                tampered["taxonomy"] = dict(assess.POLICY, version="old")
            else:
                tampered["context"] = dict(snap["context"], prompt_sha256="0" * 64)
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                audit.load_snapshot_value(tampered)

    def test_duplicate_v2_pair_is_rejected_as_ambiguous(self):
        document_value = document(("образец", "target", {}), ("образец", "target", {}))
        with self.assertRaises(ValueError):
            audit.load_v2_input_value(document_value)

    def test_examples_are_deterministic(self):
        document_value = document(("образец", "target", {}))
        records = self.records(document_value, ["suitable"])
        self.assertEqual(audit.examples(records), audit.examples(records))


if __name__ == "__main__":
    unittest.main()
