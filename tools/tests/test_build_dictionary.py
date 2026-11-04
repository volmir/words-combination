"""Синтетические данные: тесты не скачивают и не копируют чужие толкования."""
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
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


class DefinitionsImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.jsonl"
        self.database = self.root / "defs.db"

    def write_entries(self, *entries):
        self.source.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries), encoding="utf-8")

    def build(self, path=None):
        # Только синтетические записи считаются индивидуально проверенными в тестах.
        reviews = {}
        stats = __import__('collections').Counter()
        for _, display, text, notes, article in builder.wiktionary_definitions(path or self.source, {"ежик", "портал"}, stats):
            reviews[builder.definition_digest(display, text, notes, article)] = {"synthetic": True}
        return builder.build_definitions(path or self.source, {"ежик", "портал"}, self.database, "2026-11-01", reviews=reviews)

    def test_schema_provenance_normalization_and_plain_text(self):
        self.write_entries(entry(glosses=["<тест>\nВторая строка", "Третья строка"]))
        self.build()
        with sqlite3.connect(self.database) as db:
            self.assertEqual(3, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(["id", "word", "display", "ord", "text", "notes", "antonym", "example", "source"],
                             [r[1] for r in db.execute("PRAGMA table_info(definitions)")])
            row = db.execute("SELECT word,display,ord,text,antonym,example,source FROM definitions").fetchone()
            self.assertEqual(("ежик", "ёжик", 1, "<тест>\nВторая строка\nТретья строка", "", "",
                              "https://ru.wiktionary.org/wiki/%D1%91%D0%B6%D0%B8%D0%BA"), row)
            metadata = dict(db.execute("SELECT key,value FROM build_metadata"))
            self.assertEqual("CC BY-SA 4.0", metadata["license"])
            self.assertEqual("2026-11-01", metadata["dump_date"])
            self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(), metadata["sha256_input"])
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])

    def test_gzip_and_plain_input_have_same_definitions(self):
        self.write_entries(entry())
        self.build()
        with sqlite3.connect(self.database) as db:
            expected = db.execute("SELECT * FROM definitions").fetchall()
        compressed = self.root / "source.jsonl.gz"
        compressed.write_bytes(gzip.compress(self.source.read_bytes()))
        self.build(compressed)
        with sqlite3.connect(self.database) as db:
            self.assertEqual(expected, db.execute("SELECT * FROM definitions").fetchall())

    def test_filters_language_dictionary_membership_and_english_edition(self):
        self.write_entries(entry(), entry(word="лишнее"), entry(lang_code="uk"), entry(lang="Russian"))
        self.assertEqual(1, self.build()["definitions"])

    def test_keeps_order_across_senses_and_entries_for_folded_word(self):
        first = entry(senses=[{"glosses": ["Первое"]}, {"glosses": ["Второе"]}])
        self.write_entries(first, entry(word="ежик", glosses=["Третье"]))
        self.build()
        with sqlite3.connect(self.database) as db:
            self.assertEqual([(1, "Первое"), (2, "Второе"), (3, "Третье")],
                             db.execute("SELECT ord,text FROM definitions ORDER BY ord").fetchall())

    def test_excludes_citations_restricted_sources_and_missing_glosses(self):
        valid = entry(senses=[{"glosses": ["Тест"], "examples": [{"text": "Не копировать", "ref": "Автор"}],
                              "raw_tags": ["разг."]}])
        protected = entry(senses=[{"glosses": ["По словарю Ожегова"]}])
        restricted = entry(categories=["Несвободные материалы"])
        missing = entry(senses=[{"tags": ["no-gloss"]}])
        self.write_entries(valid, protected, restricted, missing)
        self.assertEqual(1, self.build()["definitions"])
        with sqlite3.connect(self.database) as db:
            self.assertEqual(("Тест", "разг.", ""), db.execute("SELECT text,notes,example FROM definitions").fetchone())

    def test_malformed_json_keeps_previous_database_and_cleans_staging(self):
        self.write_entries(entry())
        self.build()
        before = self.database.read_bytes()
        reviews = {builder.definition_digest("ёжик", "Синтетическое определение", "",
                    "https://ru.wiktionary.org/wiki/%D1%91%D0%B6%D0%B8%D0%BA"): {}}
        self.source.write_text(self.source.read_text() + "\n{broken", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "строка 2"):
            builder.build_definitions(self.source, {"ежик"}, self.database, "2026-11-01", reviews=reviews)
        self.assertEqual(before, self.database.read_bytes())
        self.assertEqual([], list(self.root.glob("defs-*.db*")))

    def test_empty_or_wrong_language_input_does_not_replace_database(self):
        self.database.write_bytes(b"existing")
        self.write_entries(entry(lang="Russian"))
        with self.assertRaisesRegex(ValueError, "Нет индивидуально проверенных определений"):
            self.build()
        self.assertEqual(b"existing", self.database.read_bytes())

    def test_cli_rejects_legacy_definitions_before_reading_or_writing_data(self):
        self.write_entries(entry())
        result = subprocess.run([sys.executable, str(SCRIPT), "--sql", "missing.sql", "--with-definitions",
                                 "--wiktionary-jsonl", str(self.source), "--dump-date", "2026-11-01"],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertFalse(self.database.exists())
        self.assertIn("SQL-источник исключён", result.stderr)

    def test_cli_unreviewed_definitions_are_rejected_without_mutating_game_data(self):
        self.write_entries(entry())
        data = self.root / "data"
        data.mkdir()
        (data / "words.txt").write_text("ежик\nпортал\n", encoding="utf-8")
        (data / "levels.json").write_text("unchanged", encoding="utf-8")
        before = {p.name: p.read_bytes() for p in data.iterdir()}
        result = subprocess.run([sys.executable, str(SCRIPT), "--definitions-only", "--out", str(data),
                                 "--definitions-jsonl", str(self.source), "--dump-date", "2026-11-01",
                                 "--db-out", str(self.database)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("Нет индивидуально проверенных определений", result.stderr)
        self.assertEqual(before, {p.name: p.read_bytes() for p in data.iterdir()})
        self.assertFalse(self.database.is_file())

    def test_no_reviews_means_no_definitions_even_without_source_markers(self):
        self.write_entries(entry())
        with self.assertRaisesRegex(ValueError, "Нет индивидуально проверенных"):
            builder.build_definitions(self.source, {"ежик"}, self.database, "2026-11-01")
        self.assertFalse(self.database.exists())

    def test_review_is_bound_to_exact_text_and_source(self):
        self.write_entries(entry(glosses=["Изменённый текст"]))
        reviews = {builder.definition_digest("ёжик", "Исходный текст", "", "https://ru.wiktionary.org/wiki/ёжик"): {}}
        with self.assertRaisesRegex(ValueError, "Не найдено проверенных"):
            builder.build_definitions(self.source, {"ежик"}, self.database, "2026-11-01", reviews=reviews)
        self.assertFalse(self.database.exists())

    def test_independent_wordlist_has_provenance_and_never_reads_legacy_wordlist(self):
        self.write_entries(entry(), entry(word="портал"), entry(word="несуществовавшеевстаромсписке"),
                           entry(word="Бренд"), entry(word="читать", pos="verb"), entry(word="сделка", tags=["form-of"]))
        sources, _, stats = builder.wiktionary_wordlist(self.source)
        self.assertEqual({"ежик", "портал", "несуществовавшеевстаромсписке"}, set(sources))
        self.assertEqual("ёжик", sources["ежик"][0]["title"])
        self.assertEqual(1, sources["ежик"][0]["line"])

    def test_rights_marker_excludes_entire_word_including_yo_variant(self):
        self.write_entries(entry(), entry(word="ежик", senses=[{"glosses": ["Ожегов"]}]), entry(word="портал"))
        sources, excluded, _ = builder.wiktionary_wordlist(self.source)
        self.assertNotIn("ежик", sources)
        self.assertIn("ежик", excluded)
        self.assertIn("портал", sources)

    def test_word_provenance_gzip_is_reproducible(self):
        self.write_entries(entry())
        sources, _, _ = builder.wiktionary_wordlist(self.source)
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

    def test_review_registry_rejects_missing_evidence(self):
        path = self.root / "reviews.json"
        path.write_text(json.dumps({"schema": 1, "definitions": [{"sha256": "0" * 64}]}))
        with self.assertRaisesRegex(ValueError, "доказательство"):
            builder.load_reviews(path)


if __name__ == "__main__":
    unittest.main()
