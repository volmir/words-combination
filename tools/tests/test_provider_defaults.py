"""Исполнитель LEX по умолчанию берётся из окружения оркестратора, без сети."""
import os
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import assess_word_suitability as assess
import classify_word_categories as classify
import prepare_v2 as pipe

ENVIRONMENT = {"CATEGORY_PROVIDER": "opencode",
               "CATEGORY_MODEL": "opencode/deepseek-v4.1-flash#max",
               "SEMANTIC_CACHE": "/tmp/semantic-cache"}


class ProviderDefaultsTest(unittest.TestCase):
    def test_environment_sets_provider_model_and_cache(self):
        with patch.dict(os.environ, ENVIRONMENT):
            prepare = pipe.build_parser().parse_args(
                ["prepare", "--dump", "dump.jsonl.gz", "--selective"])
            self.assertEqual((prepare.provider, prepare.model),
                             ("opencode", "opencode/deepseek-v4.1-flash#max"))
            self.assertEqual(prepare.cache, Path("/tmp/semantic-cache"))
            self.assertEqual(classify.build_parser().parse_args([]).provider, "opencode")
            self.assertEqual(assess.build_parser().parse_args(["--input", "index.json"]).model,
                             "opencode/deepseek-v4.1-flash#max")

    def test_explicit_flags_override_environment(self):
        with patch.dict(os.environ, ENVIRONMENT):
            prepare = pipe.build_parser().parse_args(
                ["prepare", "--dump", "dump.jsonl.gz", "--selective",
                 "--provider", "codex", "--model", "gpt-6.1-sol", "--cache", "cache/codex"])
            self.assertEqual((prepare.provider, prepare.model, prepare.cache),
                             ("codex", "gpt-6.1-sol", Path("cache/codex")))

    def test_defaults_without_environment(self):
        for key in ENVIRONMENT:
            os.environ.pop(key, None)
        prepare = pipe.build_parser().parse_args(
            ["prepare", "--dump", "dump.jsonl.gz", "--selective"])
        self.assertEqual(prepare.provider, "codex")
        self.assertIsNone(prepare.model)
        self.assertEqual(prepare.cache, pipe.ROOT / ".local/v2-cache")


if __name__ == "__main__":
    unittest.main()
