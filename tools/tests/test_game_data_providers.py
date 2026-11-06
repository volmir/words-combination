"""Протоколы провайдеров проверяются без сетевых запросов и настоящих ключей."""
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_game_data as game
from game_data_providers import ApiProvider, FileProvider, OllamaProvider


class ProviderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name)
        self.config = game.load_config(game.TOOLS / "selection_config.json")
        self.validate = game.validators({"other": "Другое"})
        self.items = [{"word": "портал", "diminutive_candidate": False}, {"word": "кот", "diminutive_candidate": False}]

    def result(self, word):
        return {"word": word, "familiarity": 4, "reject": [], "category": "other", "definition": None, "unsure": True}

    def test_partial_file_import_resumes_and_prompt_and_model_are_isolated(self):
        first = FileProvider(self.cache, "model-a", self.validate, self.config)
        self.assertEqual({}, first.exchange("definition", "JSON prompt", self.items))
        request_path = next((self.cache / "requests").glob("*.jsonl"))
        request = json.loads(request_path.read_text())
        response = {"request_id": request["request_id"], "model": "model-a", "results": [self.result("кот")]}
        (self.cache / "responses" / "partial.jsonl").write_text(json.dumps(response))
        second = FileProvider(self.cache, "model-a", self.validate, self.config)
        self.assertEqual({"кот"}, set(second.exchange("definition", "JSON prompt", self.items)))
        self.assertEqual(1, len(list((self.cache / "requests").glob("*.jsonl"))))
        self.assertEqual({}, second.exchange("definition", "Another JSON prompt", self.items))
        other = FileProvider(self.cache, "model-b", self.validate, self.config)
        self.assertEqual({}, other.exchange("definition", "JSON prompt", self.items))

    def test_bad_schema_and_unsolicited_words_are_not_cached(self):
        provider = FileProvider(self.cache, "model", self.validate, self.config)
        provider.exchange("definition", "JSON", self.items)
        request = next(iter(provider.requests.values()))
        for result in (dict(self.result("кот"), familiarity=True), dict(self.result("кот"), category="unknown"), self.result("собака")):
            with self.assertRaises(ValueError):
                provider.store(request, [result])
        self.assertEqual([], list((self.cache / "answers").glob("*.json")))

    def test_export_rejects_article_fields_and_detects_modified_requests(self):
        provider = FileProvider(self.cache, "model", self.validate, self.config)
        with self.assertRaisesRegex(ValueError, "полей|поля"):
            provider.exchange("definition", "JSON", [dict(self.items[0], glosses=["Синтетическая строка"])])
        provider.exchange("definition", "JSON", self.items)
        path = next((self.cache / "requests").glob("*.jsonl"))
        request = json.loads(path.read_text())
        request["prompt"] = "Изменённый JSON prompt"
        path.write_text(json.dumps(request))
        with self.assertRaisesRegex(ValueError, "Хэш"):
            FileProvider(self.cache, "model", self.validate, self.config)

    def test_network_is_disabled_without_explicit_flag(self):
        with self.assertRaisesRegex(ValueError, "allow-network"):
            ApiProvider(self.cache, "model", self.validate, self.config, "openai")

    def test_api_payloads_use_temperature_zero_and_store_no_keys(self):
        for service, variable in (("openai", "OPENAI_API_KEY"), ("anthropic", "ANTHROPIC_API_KEY")):
            with self.subTest(service=service), tempfile.TemporaryDirectory() as directory:
                text = json.dumps({"results": [self.result("портал"), self.result("кот")]})
                data = ({"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": text}]}]}
                        if service == "openai" else {"stop_reason": "end_turn", "content": [{"type": "text", "text": text}]})
                with patch.dict(os.environ, {variable: "synthetic-secret"}), patch("urllib.request.urlopen", return_value=io.StringIO(json.dumps(data))) as call:
                    provider = ApiProvider(directory, "synthetic-model", self.validate, self.config, service, True)
                    self.assertEqual(2, len(provider.exchange("definition", "JSON", self.items)))
                    payload = json.loads(call.call_args.args[0].data)
                    self.assertEqual(0, payload["temperature"])
                    if service == "openai":
                        self.assertFalse(payload["store"])
                for path in Path(directory).rglob("*.json*"):
                    self.assertNotIn("synthetic-secret", path.read_text())

    def test_api_retries_transient_failure_without_real_wait(self):
        text = json.dumps({"results": [self.result("портал"), self.result("кот")]})
        reply = {"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": text}]}]}
        error = urllib.error.HTTPError("https://example.invalid", 429, "rate limit", {}, None)
        with patch.dict(os.environ, {"OPENAI_API_KEY": "synthetic-secret"}), patch("time.sleep"), patch(
                "urllib.request.urlopen", side_effect=[error, io.StringIO(json.dumps(reply))]) as call:
            provider = ApiProvider(self.cache, "model", self.validate, self.config, "openai", True)
            self.assertEqual(2, len(provider.exchange("definition", "JSON", self.items)))
            self.assertEqual(2, call.call_count)

    def test_luna_uses_none_reasoning_and_records_usage_without_repeating_cached_calls(self):
        text = json.dumps({"results": [self.result("портал"), self.result("кот")]})
        reply = {"status": "completed", "usage": {"input_tokens": 100, "output_tokens": 50,
                 "input_tokens_details": {"cached_tokens": 20}},
                 "output": [{"type": "message", "content": [{"type": "output_text", "text": text}]}]}
        with patch.dict(os.environ, {"OPENAI_API_KEY": "synthetic-secret"}), patch(
                "urllib.request.urlopen", return_value=io.StringIO(json.dumps(reply))) as call:
            provider = ApiProvider(self.cache, "gpt-6-luna", self.validate, self.config, "openai", True)
            provider.exchange("definition", "JSON", self.items)
            payload = json.loads(call.call_args.args[0].data)
            self.assertEqual({"effort": "none"}, payload["reasoning"])
            self.assertEqual(0, payload["temperature"])
            self.assertEqual(100, provider.usage_summary()["input_tokens"])
            self.assertEqual(50, provider.usage_summary()["output_tokens"])
            self.assertEqual(20, provider.usage_summary()["cached_input_tokens"])
            resumed = ApiProvider(self.cache, "gpt-6-luna", self.validate, self.config, "openai", True)
            resumed.exchange("definition", "JSON", self.items)
            self.assertEqual(1, call.call_count)
            self.assertEqual(1, resumed.usage_summary()["responses"])

    def test_incomplete_api_output_does_not_create_answer(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": "synthetic-secret"}), patch(
                "urllib.request.urlopen", return_value=io.StringIO('{"status":"incomplete"}')):
            provider = ApiProvider(self.cache, "model", self.validate, self.config, "openai", True)
            with self.assertRaisesRegex(ValueError, "неполный"):
                provider.exchange("definition", "JSON", self.items)
        self.assertEqual([], list((self.cache / "answers").glob("*.json")))

    def test_local_provider_rejects_network_without_flag_remote_urls_and_cloud_models(self):
        with self.assertRaisesRegex(ValueError, "allow-network"):
            OllamaProvider(self.cache, "local-model", self.validate, self.config)
        for address in ("https://example.invalid", "http://192.168.1.2:11434", "http://user:secret@localhost:11434",
                        "http://localhost:11434/remote", "http://localhost:11434?remote=true"):
            with self.subTest(address=address), self.assertRaisesRegex(ValueError, "локальному"):
                OllamaProvider(self.cache, "local-model", self.validate, self.config, True, address)
        with self.assertRaisesRegex(ValueError, "Облачные"):
            OllamaProvider(self.cache, "qwen3.5:397b-cloud", self.validate, self.config, True)

    def test_local_provider_requires_no_keys_and_preserves_cache_and_usage(self):
        reply = {"done": True, "done_reason": "stop", "prompt_eval_count": 23, "eval_count": 45,
                 "message": {"content": json.dumps({"results": [self.result("портал"), self.result("кот")]})}}
        with patch.dict(os.environ, {}, clear=True), patch("urllib.request.OpenerDirector.open",
                return_value=io.StringIO(json.dumps(reply))) as call:
            provider = OllamaProvider(self.cache, "local-model", self.validate, self.config, True)
            self.assertEqual(2, len(provider.exchange("definition", "JSON prompt", self.items)))
            payload = json.loads(call.call_args.args[0].data)
            self.assertEqual("http://127.0.0.1:11434/api/chat", call.call_args.args[0].full_url)
            self.assertEqual(0, payload["options"]["temperature"])
            self.assertFalse(payload["think"])
            self.assertFalse(payload["stream"])
            self.assertEqual("json", payload["format"])
            self.assertEqual(23, provider.usage_summary()["input_tokens"])
            self.assertEqual(45, provider.usage_summary()["output_tokens"])
            resumed = OllamaProvider(self.cache, "local-model", self.validate, self.config, True)
            resumed.exchange("definition", "JSON prompt", self.items)
            self.assertEqual(1, call.call_count)

    def test_local_incomplete_response_and_missing_words_do_not_poison_cache(self):
        for reply in ({"done": False}, {"done": True, "done_reason": "length"},
                      {"done": True, "done_reason": "stop", "message": {"content": '{"results":[]}'}}):
            with self.subTest(reply=reply), patch("urllib.request.OpenerDirector.open", return_value=io.StringIO(json.dumps(reply))):
                provider = OllamaProvider(self.cache, "local-model", self.validate, self.config, True)
                with self.assertRaises(ValueError):
                    provider.exchange("definition", "JSON prompt", self.items)
                self.assertEqual([], list((self.cache / "answers").glob("*.json")))
