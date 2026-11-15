"""Проверки публикационной приватности на синтетических и реальных артефактах."""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "check_privacy_publication.py"
spec = importlib.util.spec_from_file_location("privacy_check", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
SANITIZER_SCRIPT = ROOT / "tools" / "sanitize_manifest_privacy.py"
sanitizer_spec = importlib.util.spec_from_file_location("manifest_privacy", SANITIZER_SCRIPT)
sanitizer = importlib.util.module_from_spec(sanitizer_spec)
sanitizer_spec.loader.exec_module(sanitizer)


class PrivacyPublicationTest(unittest.TestCase):
    def test_repository_has_no_forbidden_personal_markers(self):
        self.assertEqual([], checker.check(ROOT))

    def test_forbidden_token_detection_uses_hashes_not_plaintext(self):
        synthetic = "private-marker"
        digest = hashlib.sha256(checker.normalize(synthetic).encode("utf-8")).hexdigest()
        self.assertEqual({digest}, checker.find_forbidden_tokens("Текст: Private-Marker.", {digest}))
        self.assertEqual(set(), checker.find_forbidden_tokens("обычный текст", {digest}))

    def test_user_facing_urls_are_limited_to_license_and_dictionary_sources(self):
        allowed = {"ru.wiktionary.org", "kaikki.org", "creativecommons.org"}
        text = ("https://ru.wiktionary.org/wiki/портал "
                "https://kaikki.org/ruwiktionary/rawdata.html "
                "https://creativecommons.org/licenses/by-sa/4.0/")
        self.assertEqual([], checker.find_disallowed_urls(text, allowed))
        self.assertEqual(["https://github.com/example"],
                         checker.find_disallowed_urls("https://github.com/example", allowed))

    def test_email_detection_is_case_insensitive_enough_for_publication_scan(self):
        self.assertEqual(["person@example.test"], checker.find_emails("Контакт: person@example.test"))
        self.assertEqual([], checker.find_emails("Без контактов"))

    def test_about_strings_are_depersonalized_and_have_no_links(self):
        policy = checker.load_policy(ROOT)
        values = checker.parse_strings(ROOT / "app/src/main/res/values/strings.xml")
        text = values["about_code_sources"]
        self.assertEqual(policy["expected_user_strings"]["about_code_sources"], text)
        self.assertNotIn("http", text.lower())
        self.assertNotIn("@", text)
        self.assertNotIn("github", text.lower())

    def test_license_and_third_party_notices_are_preserved(self):
        policy = checker.load_policy(ROOT)
        license_text = (ROOT / "LICENSE").read_text(encoding="utf-8")
        notices = (ROOT / "legal/THIRD_PARTY_NOTICES.txt").read_text(encoding="utf-8")
        for marker in ("MIT License", "Правообладатель проекта «Составь слова»",
                       "Permission is hereby granted", "THE SOFTWARE IS PROVIDED"):
            self.assertIn(marker, license_text)
        for marker in policy["required_third_party_markers"]:
            self.assertIn(marker, notices)

    def test_manifest_keeps_data_license_and_drops_personal_upstream_url(self):
        manifest = json.loads((ROOT / "data" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("MIT", manifest["levels_source"]["license"])
        self.assertIsNone(manifest["levels_source"].get("url"))
        self.assertEqual("CC-BY-SA-4.0", manifest["source"]["license"])
        self.assertTrue(sanitizer.is_sanitized(manifest))

    def test_manifest_sanitizer_is_idempotent_and_keeps_license(self):
        manifest = {"levels_source": {"url": "https://example.test/old", "license": "MIT"}}
        sanitizer.sanitize(manifest)
        self.assertIsNone(manifest["levels_source"]["url"])
        self.assertEqual("MIT", manifest["levels_source"]["license"])
        self.assertTrue(sanitizer.is_sanitized(manifest))
        sanitizer.sanitize(manifest)
        self.assertTrue(sanitizer.is_sanitized(manifest))


if __name__ == "__main__":
    unittest.main()
