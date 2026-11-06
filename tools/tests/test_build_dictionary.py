"""Синтетические данные: тесты не скачивают и не копируют чужие толкования."""
import gzip
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "build_dictionary.py"
spec = importlib.util.spec_from_file_location("build_dictionary", SCRIPT)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def entry(word="ёжик", glosses=None, **kwargs):
    return {"word": word, "lang_code": "ru", "lang": "Русский", "pos": "noun",
            "senses": [{"glosses": glosses or ["Синтетическое определение"]}], **kwargs}


class WordlistBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.jsonl"
        self.database = self.root / "definitions.db"

    def write_entries(self, *entries):
        self.source.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries), encoding="utf-8")


    def test_cli_rejects_legacy_definitions_before_reading_or_writing_data(self):
        self.write_entries(entry())
        result = subprocess.run([sys.executable, str(SCRIPT), "--sql", "missing.sql", "--with-definitions",
                                 "--wiktionary-jsonl", str(self.source), "--dump-date", "2026-11-01"],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertFalse(self.database.exists())
        self.assertIn("SQL-источник исключён", result.stderr)

    def test_definition_mode_redirects_to_new_pipeline_without_writing_data(self):
        self.write_entries(entry())
        result = subprocess.run([sys.executable, str(SCRIPT), "--with-definitions", "--out", str(self.root / "data"),
                                 "--wiktionary-jsonl", str(self.source), "--dump-date", "2026-11-01"],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("tools/build_game_data.py", result.stderr)
        self.assertFalse((self.root / "data").exists())


    def test_independent_wordlist_has_provenance_and_never_reads_legacy_wordlist(self):
        self.write_entries(entry(), entry(word="портал"), entry(word="несуществовавшеевстаромсписке"),
                           entry(word="Бренд"), entry(word="читать", pos="verb"), entry(word="сделка", tags=["form-of"]))
        sources, stats = builder.wiktionary_wordlist(self.source)
        self.assertEqual({"ежик", "портал", "несуществовавшеевстаромсписке"}, set(sources))
        self.assertEqual("ёжик", sources["ежик"][0]["title"])
        self.assertEqual(1, sources["ежик"][0]["line"])

    def test_author_mentions_do_not_discard_words_or_folded_variants(self):
        for mention in ("Ефремов", "кузнецов"):
            with self.subTest(mention=mention):
                self.write_entries(
                    entry(), entry(word="ежик", glosses=["Синтетический текст: " + mention]),
                    entry(word="портал", senses=[{"glosses": ["Тест"], "examples": [{"text": mention}]}]),
                )
                sources, _ = builder.wiktionary_wordlist(self.source)
                self.assertEqual({"ежик", "портал"}, set(sources))
                self.assertEqual(["ёжик", "ежик"], [row["title"] for row in sources["ежик"]])

    def test_other_wordlist_filters_are_preserved(self):
        rejected = [
            entry(word="еж"), entry(word="два слова"), entry(word="а" * 31),
            entry(word="Ежик"), entry(lang_code="uk"), entry(lang="Russian"), entry(pos="verb"),
            entry(senses=[]), entry(senses=[{"glosses": []}]),
        ]
        rejected.extend(entry(tags=[tag]) for tag in ("proper-noun", "abbreviation", "diminutive", "form-of"))
        rejected.extend(entry(senses=[{"glosses": ["Тест"], "tags": [tag]}])
                        for tag in ("form-of", "diminutive", "no-gloss"))
        self.write_entries(entry(word="портал"), *rejected)
        sources, _ = builder.wiktionary_wordlist(self.source)
        self.assertEqual({"портал"}, set(sources))

    def test_word_provenance_gzip_is_reproducible(self):
        self.write_entries(entry())
        sources, _ = builder.wiktionary_wordlist(self.source)
        a, b = self.root / "a.gz", self.root / "b.gz"
        builder.write_word_sources(a, sources)
        builder.write_word_sources(b, sources)
        self.assertEqual(a.read_bytes(), b.read_bytes())

    def test_unsatisfied_levels_do_not_replace_game_data(self):
        (self.root / "words.txt").write_text("original", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "данные не заменены"):
            builder.write_game_data(self.root, {}, "2026-11-01", "0" * 64)
        self.assertEqual("original", (self.root / "words.txt").read_text())

    def test_quarantine_removes_database_from_build_input_but_preserves_bytes(self):
        self.database.write_bytes(b"unreviewed")
        destination = builder.quarantine_definitions(self.database)
        self.assertFalse(self.database.exists())
        self.assertEqual(b"unreviewed", Path(destination).read_bytes())


if __name__ == "__main__":
    unittest.main()
