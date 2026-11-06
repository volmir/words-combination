"""Только синтетические статьи, ответы, корпус и аудит; сеть не используется."""
import argparse
import csv
import io
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_dictionary as dictionary
import build_game_data as game
from game_data_providers import FileProvider, json_digest
from game_data_quality import definition_issues, gloss_similarity, normalize_form, shares_root


def entry(word="портал", **kwargs):
    return {"word": word, "lang_code": "ru", "lang": "Русский", "pos": "noun",
            "senses": [{"glosses": ["СИНТЕТИЧЕСКИЙ_МАРКЕР_ГЛОССЫ"]}], **kwargs}


class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.dump = self.root / "source.jsonl"
        self.config = game.load_config(game.TOOLS / "selection_config.json")
        self.config.update(target_size=5, target_min_size=1, llm_candidates=10)
        self.config_path = self.root / "config.json"
        self.config_path.write_text(json.dumps(self.config))
        self.golden = self.root / "golden.txt"
        self.golden.write_text("кот\n")
        self.overrides = self.root / "overrides"
        self.overrides.mkdir()
        self.args = argparse.Namespace(check_release=False, verify_audit=None, reviewer="", dump=self.dump, source_words=None,
            corpus=None, manifest=self.root / "source-manifest.json", config=self.config_path,
            categories=game.TOOLS / "categories.json", golden=self.golden,
            prompt=game.TOOLS / "prompts/definition_v1.txt", provider="file", model="synthetic-v1", allow_network=False,
            cache=self.root / "cache", reports=self.root / "reports", out=self.root / "data", overrides=self.overrides,
            with_wiktionary_glosses=False, generation_date="2026-11-06")
        self.levels = patch.object(dictionary, "LEVELS", [("портал", 1, 1, "р", 1)])
        self.levels.start()
        self.addCleanup(self.levels.stop)
        self.texts = {"портал": "Большой вход с выразительным архитектурным оформлением.",
                      "рот": "Полость в нижней части лица для приёма пищи и произнесения звуков.",
                      "тор": "Геометрическое тело в форме кольца с круглым сечением.",
                      "дом": "Здание с помещениями для постоянного проживания людей.",
                      "кот": "Домашнее животное с мягкой шерстью и острыми когтями."}
        self.write_entries(*(entry(word) for word in self.texts))

    def write_entries(self, *entries):
        self.dump.write_text("\n".join(json.dumps(value, ensure_ascii=False) for value in entries), encoding="utf-8")
        self.args.manifest.write_text(json.dumps({"source": {"sha256_input": dictionary.sha256_file(self.dump), "dump_date": "2026-11-01"}}))

    def answer(self, word, **kwargs):
        return {"word": word, "familiarity": 5, "reject": [], "category": "other",
                "definition": self.texts.get(word), "unsure": False, **kwargs}

    def fill(self, task, transform=None):
        for path in (self.args.cache / "requests").glob(task + "-*.jsonl"):
            request = json.loads(path.read_text())
            if task == "definition":
                results = [(transform(item) if transform else self.answer(item["word"])) for item in request["items"]]
            else:
                results = [{"id": item["id"], "guess": next((word for word, text in self.texts.items() if text == item["definition"]), None)}
                           for item in request["items"]]
            response = {"request_id": request["request_id"], "model": request["model"], "results": results}
            (self.args.cache / "responses" / path.name).write_text(json.dumps(response, ensure_ascii=False))

    def complete(self):
        first = game.run(self.args)
        self.assertEqual("awaiting_definition_responses", first["status"])
        self.fill("definition")
        second = game.run(self.args)
        self.assertEqual("awaiting_guess_responses", second["status"])
        self.fill("guess")
        return game.run(self.args)

    def snapshot(self):
        return {path.name: path.read_bytes() for path in self.args.out.iterdir() if path.is_file()}

    def test_file_roundtrip_resume_privacy_schema_and_determinism(self):
        self.assertEqual("built_awaiting_manual_audit", self.complete()["status"])
        before = self.snapshot()
        with patch.object(game, "prefilter", side_effect=AssertionError("cache was not used")), patch.object(
                game, "select", side_effect=AssertionError("selection cache was not used")), patch.object(
                game, "quality_stage", side_effect=AssertionError("quality cache was not used")):
            game.run(self.args)
        self.assertEqual(before, self.snapshot())
        for path in (self.args.cache / "requests").glob("*.jsonl"):
            request = json.loads(path.read_text())
            self.assertNotIn("СИНТЕТИЧЕСКИЙ_МАРКЕР_ГЛОССЫ", path.read_text())
            for item in request["items"]:
                expected = {"word", "diminutive_candidate"} if request["task"] == "definition" else {"id", "category", "definition", "length", "first_letter"}
                self.assertEqual(expected, set(item))
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(4, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(5, db.execute("SELECT COUNT(*) FROM definitions WHERE origin='own' AND source='' AND guessable=1").fetchone()[0])
            self.assertEqual(5, db.execute("SELECT COUNT(*) FROM word_metadata").fetchone()[0])
            self.assertEqual(["id", "word", "display", "ord", "text", "notes", "antonym", "example", "source", "origin", "category", "guessable"],
                             [row[1] for row in db.execute("PRAGMA table_info(definitions)")])
        manifest = json.loads((self.args.out / "manifest.json").read_text())
        self.assertFalse(manifest["definitions"]["audit"]["passed"])
        self.assertTrue(manifest["selection"]["frequency_skipped"])
        self.assertEqual({"own": 5, "wiktionary": 0}, manifest["definitions"]["counts"])
        with self.assertRaisesRegex(ValueError, "Release"):
            game.require_release_audit(self.args.out)

    def test_pilot_samples_groups_deterministically_without_exceeding_limit(self):
        records = {}
        for index in range(400):
            word = "слово" + chr(1072 + index // 32) + chr(1072 + index % 32)
            records[word] = {"protected": index < 100, "suffix_candidate": 100 <= index < 250}
        words, groups = game.pilot_candidates(list(records), records, 200, 17)
        again, _ = game.pilot_candidates(list(reversed(records)), records, 200, 17)
        self.assertEqual(words, again)
        self.assertEqual(200, len(set(words)))
        self.assertEqual(50, sum(value == "protected" for value in groups.values()))
        self.assertEqual(75, sum(value == "suffix_candidate" for value in groups.values()))
        self.assertEqual(75, sum(value == "other" for value in groups.values()))
        with self.assertRaisesRegex(ValueError, "pilot-size"):
            game.pilot_candidates(list(records), records, 0, 17)

    def test_pilot_file_roundtrip_does_not_publish_or_check_full_levels(self):
        self.complete()
        before = self.snapshot()
        self.args.pilot_size = 3
        with patch.object(game, "check_levels", side_effect=AssertionError("pilot must not validate full levels")), patch.object(
                game, "publish", side_effect=AssertionError("pilot must not publish")):
            summary = game.run(self.args)
        self.assertEqual("pilot_complete", summary["status"])
        self.assertEqual(3, summary["llm_candidates"])
        self.assertEqual(before, self.snapshot())
        self.assertFalse(summary["published"])
        self.assertFalse(summary["levels_checked"])
        self.assertTrue(Path(summary["pilot_results"]).is_file())
        with Path(summary["pilot_results"]).open() as source:
            self.assertEqual(3, len(list(csv.DictReader(source))))
        self.assertNotEqual(self.args.reports / "funnel.json", Path(summary["pilot_results"]).parent / "funnel.json")

    def test_dump_mode_needs_no_model_excludes_words_without_definition_and_records_provenance(self):
        self.args.definitions_source = "dump"
        self.args.model = None
        self.write_entries(*(entry(word, senses=[{"glosses": ["см. предмет"] if word == "дом" else [text]}])
                             for word, text in self.texts.items()))
        with patch.object(game, "FileProvider", side_effect=AssertionError("AI must not be used")), patch.object(
                game, "ApiProvider", side_effect=AssertionError("AI must not be used")):
            summary = game.run(self.args)
        self.assertEqual("built_provisional_definitions", summary["status"])
        self.assertEqual(5, summary["candidates"])
        self.assertEqual(4, summary["D"])
        self.assertNotIn("дом", dictionary.read_list(self.args.out / "words.txt"))
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(4, db.execute("SELECT COUNT(DISTINCT word) FROM definitions").fetchone()[0])
            self.assertEqual(4, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM definitions WHERE origin!='wiktionary' OR source=''").fetchone()[0])
        manifest = json.loads((self.args.out / "manifest.json").read_text())
        self.assertFalse(manifest["definitions"]["audit"]["passed"])
        self.assertFalse(manifest["definitions"]["rights_review"]["passed"])
        self.assertIsNone(manifest["definitions"]["generator"]["model"])
        self.assertEqual("definition-coverage-v1", manifest["selection"]["mode"])
        self.assertFalse(manifest["selection"]["ai_used"])
        with self.assertRaisesRegex(ValueError, "Release"):
            game.require_release_audit(self.args.out)

    def test_dump_mode_keeps_collected_database_but_does_not_publish_broken_levels_or_missing_golden(self):
        self.complete()
        before = self.snapshot()
        self.args.definitions_source = "dump"
        self.write_entries(*(entry(word, senses=[{"glosses": ["см. предмет"] if word in {"портал", "кот"} else [text]}])
                             for word, text in self.texts.items()))
        with self.assertRaisesRegex(ValueError, "защищённые|Уровни"):
            game.run(self.args)
        self.assertEqual(before, self.snapshot())
        report = json.loads((self.args.reports / "funnel.json").read_text())
        self.assertEqual("blocked_protected_words_or_levels", report["status"])
        self.assertEqual(["кот", "портал"], report["missing_protected_words"])
        with sqlite3.connect(report["database"]) as db:
            self.assertEqual(3, db.execute("SELECT COUNT(DISTINCT word) FROM definitions").fetchone()[0])
        self.assertTrue((self.args.reports / "unreachable_levels.json").exists())

    def test_complaints_remove_definitions_and_increment_version_even_on_revert(self):
        self.complete()
        path = self.overrides / "remove_definitions.txt"
        path.write_text("дом\n")
        summary = game.run(self.args)
        self.assertEqual(1, summary["without_definition"])
        self.assertIn("дом", dictionary.read_list(self.args.out / "words.txt"))
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(5, db.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(0, db.execute("SELECT COUNT(*) FROM definitions WHERE word='дом'").fetchone()[0])
            self.assertEqual("other", db.execute("SELECT category FROM word_metadata WHERE word='дом'").fetchone()[0])
        path.write_text("")
        game.run(self.args)
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(6, db.execute("PRAGMA user_version").fetchone()[0])
        (self.overrides / "remove.txt").write_text("дом\n")
        game.run(self.args)
        self.assertNotIn("дом", dictionary.read_list(self.args.out / "words.txt"))

    def test_unreachable_levels_leave_previous_artifacts_untouched(self):
        self.complete()
        before = self.snapshot()
        (self.overrides / "remove.txt").write_text("рот\n")
        with self.assertRaisesRegex(ValueError, "Уровни недостижимы"):
            game.run(self.args)
        self.assertEqual(before, self.snapshot())
        self.assertTrue((self.args.reports / "unreachable_levels.json").exists())

    def test_protected_words_survive_ai_reject_and_complaint_conflict_is_reported(self):
        game.run(self.args)
        self.fill("definition", lambda item: self.answer(item["word"], familiarity=1, reject=["rare"]) if item["word"] == "кот" else self.answer(item["word"]))
        game.run(self.args)
        self.fill("guess")
        game.run(self.args)
        self.assertIn("кот", dictionary.read_list(self.args.out / "words.txt"))
        self.assertIn("rare", (self.args.reports / "protected_words.csv").read_text())
        (self.overrides / "remove.txt").write_text("кот\n")
        with self.assertRaisesRegex(ValueError, "защищёнными"):
            game.run(self.args)

    def test_hash_mismatch_and_missing_corpus_stop_before_requests_or_data(self):
        self.dump.write_text("{}")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            game.run(self.args)
        self.assertFalse(self.args.cache.exists())
        self.write_entries(entry())
        self.args.corpus = self.root / "missing.txt"
        with self.assertRaisesRegex(ValueError, "corpus"):
            game.run(self.args)

    def test_prefilter_uses_structural_tags_and_preserves_common_sense_and_protected(self):
        self.write_entries(entry("ножик"), entry("механик"), entry("мастерская"),
            entry("старик", senses=[{"glosses": ["Тест"], "tags": ["obsolete"]}]),
            entry("каша", senses=[{"tags": ["form-of"]}, {"glosses": ["Тест"]}]),
            entry("кот", senses=[{"tags": ["diminutive"]}]),
            entry("дом", senses=[{"topics": ["chemistry"]}]),
            entry("корпус", senses=[{"raw_tags": ["[диал.]"]}]),
            entry("Автор"), entry("читать", pos="verb"), entry("колечко", senses=[{"form_of": [{"word": "кольцо"}]}]))
        records = game.prefilter(self.dump, self.config, {"кот"})["records"]
        for word in ("ножик", "механик", "мастерская", "каша", "кот"):
            self.assertTrue(records[word]["eligible"], word)
        self.assertTrue(records["ножик"]["suffix_candidate"])
        for word, reason in (("старик", "obsolete"), ("дом", "narrow_term"), ("корпус", "dialect"),
                             ("автор", "proper_name"), ("читать", "not_noun"), ("колечко", "form_variant")):
            self.assertFalse(records[word]["eligible"], word)
            self.assertIn(reason, records[word]["reasons"])
        self.assertIn("diminutive", records["кот"]["reasons"])

    def test_frequency_sums_unique_inflected_forms_and_does_not_rank_zero_counts(self):
        records = {"кот": {"eligible": True, "forms": ["кот", "кота", "кота"]}, "дом": {"eligible": True, "forms": ["дом"]}}
        corpus = self.root / "corpus.txt"
        corpus.write_text("КОТ кота кота кот\nдругое слово")
        result = game.frequencies(records, corpus)
        self.assertEqual({"frequency": 4, "freq_rank": 1}, result["words"]["кот"])
        self.assertIsNone(result["words"]["дом"]["freq_rank"])

    def test_frequency_list_sums_counts_normalizes_forms_and_ignores_unrelated_tokens(self):
        records = {"елка": {"eligible": True, "forms": ["елка", "елки", "елки"]},
                   "йод": {"eligible": True, "forms": []},
                   "дом": {"eligible": True, "forms": ["дом"]},
                   "кот": {"eligible": False, "forms": ["кот"]}}
        path = self.root / "frequency.txt"
        path.write_text("\ufeffЁЛКА 2\nе́лки\t7\nелки 3\nЙОД 4\nкот 100\nлатынь 50\nword 500\n\n",
                        encoding="utf-8")
        result = game.frequencies(records, frequency_list=path)
        self.assertFalse(result["skipped"])
        self.assertEqual(3, result["B"])
        self.assertEqual({"frequency": 12, "freq_rank": 1}, result["words"]["елка"])
        self.assertEqual({"frequency": 4, "freq_rank": 2}, result["words"]["йод"])
        self.assertEqual({"frequency": 0, "freq_rank": None}, result["words"]["дом"])
        self.assertNotIn("кот", result["words"])

    def test_frequency_list_matches_corpus_counts_and_has_deterministic_ties(self):
        records = {word: {"eligible": True, "forms": [word]} for word in ("кот", "дом", "рот")}
        corpus, path = self.root / "corpus.txt", self.root / "frequency.txt"
        corpus.write_text("КОТ кот дом дом\n", encoding="utf-8")
        path.write_text("кот 2\nрот 0\nдом 2\n", encoding="utf-8")
        self.assertEqual(game.frequencies(records, corpus), game.frequencies(records, frequency_list=path))
        self.assertEqual(1, game.frequencies(records, frequency_list=path)["words"]["дом"]["freq_rank"])

    def test_frequency_list_rejects_bad_rows_even_for_unrelated_words_and_empty_input(self):
        records = {"кот": {"eligible": True, "forms": ["кот"]}}
        path = self.root / "frequency.txt"
        for invalid in ("слово", "слово -1", "слово 1.5", "слово 1 extra", "слово +1"):
            with self.subTest(invalid=invalid):
                path.write_text("кот 1\n" + invalid + "\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "frequency-list.*:2"):
                    game.frequencies(records, frequency_list=path)
        path.write_text("\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "frequency-list.*пуст"):
            game.frequencies(records, frequency_list=path)
        with self.assertRaisesRegex(ValueError, "corpus.*frequency-list"):
            game.frequencies(records, corpus=path, frequency_list=path)

    def test_frequency_list_missing_and_conflicting_inputs_stop_before_cache_or_requests(self):
        self.args.frequency_list = self.root / "missing-frequency.txt"
        with self.assertRaisesRegex(ValueError, "frequency-list"):
            game.run(self.args)
        self.assertFalse(self.args.cache.exists())
        self.args.frequency_list.write_text("кот 1\n", encoding="utf-8")
        self.args.corpus = self.args.frequency_list
        with self.assertRaisesRegex(ValueError, "corpus.*frequency-list"):
            game.run(self.args)
        self.assertFalse(self.args.cache.exists())
        with patch.object(sys, "argv", ["build_game_data.py", "--corpus", "a", "--frequency-list", "b"]), \
                patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit) as error:
            game.main()
        self.assertEqual(2, error.exception.code)

    def test_frequency_list_is_cached_by_contents_and_invalidates_selection_and_manifest(self):
        self.args.frequency_list = self.root / "frequency.txt"
        self.args.frequency_list.write_text("кот 3\nдом 2\nрот 1\n", encoding="utf-8")
        self.complete()
        before = json.loads((self.args.out / "manifest.json").read_text())
        digest = dictionary.sha256_file(self.args.frequency_list)
        self.assertEqual(digest, before["selection"]["frequency_list_sha256"])
        self.assertEqual({"kind": "frequency-list", "sha256": digest}, before["selection"]["frequency_source"])
        self.assertIsNone(before["selection"]["corpus_sha256"])
        self.assertFalse(before["selection"]["frequency_skipped"])
        with patch.object(game, "frequencies", side_effect=AssertionError("frequency cache was not used")):
            game.run(self.args)
        self.args.frequency_list.write_text("кот 1\nдом 2\nрот 3\n", encoding="utf-8")
        with patch.object(game, "frequencies", wraps=game.frequencies) as frequency, \
                patch.object(game, "select", wraps=game.select) as selection:
            summary = game.run(self.args)
        frequency.assert_called_once()
        selection.assert_called_once()
        after = json.loads((self.args.out / "manifest.json").read_text())
        self.assertNotEqual(digest, after["selection"]["frequency_list_sha256"])
        self.assertNotEqual(before["definitions"]["sha256"], after["definitions"]["sha256"])
        self.assertEqual(after["selection"]["frequency_source"], summary["frequency_source"])
        # Одни байты имеют разный смысл в корпусе и списке готовых счётчиков.
        self.args.corpus, self.args.frequency_list = self.args.frequency_list, None
        with patch.object(game, "frequencies", wraps=game.frequencies) as frequency, \
                patch.object(game, "select", wraps=game.select) as selection:
            game.run(self.args)
        frequency.assert_called_once()
        selection.assert_called_once()
        corpus_selection = json.loads((self.args.out / "manifest.json").read_text())["selection"]
        self.assertEqual(after["selection"]["frequency_list_sha256"], corpus_selection["corpus_sha256"])
        self.assertEqual("corpus", corpus_selection["frequency_source"]["kind"])
        self.assertIsNone(corpus_selection["frequency_list_sha256"])

    def test_dump_mode_records_frequency_source_and_reuses_cache_without_ai(self):
        self.args.definitions_source = "dump"
        self.args.frequency_list = self.root / "frequency.txt"
        self.args.frequency_list.write_text("кот 3\nдом 2\nрот 1\n", encoding="utf-8")
        self.write_entries(*(entry(word, senses=[{"glosses": [text]}]) for word, text in self.texts.items()))
        with patch.object(game, "FileProvider", side_effect=AssertionError("AI must not be used")):
            summary = game.run(self.args)
        selection = json.loads((self.args.out / "manifest.json").read_text())["selection"]
        self.assertFalse(selection["frequency_skipped"])
        self.assertEqual(dictionary.sha256_file(self.args.frequency_list), selection["frequency_list_sha256"])
        self.assertEqual(selection["frequency_source"], summary["frequency_source"])
        with patch.object(game, "frequencies", side_effect=AssertionError("frequency cache was not used")):
            game.run(self.args)

    def test_forms_remove_stress_without_losing_letter_short_i(self):
        self.write_entries(entry("кот", forms=[{"form": "ко́та"}, {"form": "кота"}]))
        record = game.prefilter(self.dump, self.config, set())["records"]["кот"]
        self.assertEqual(["кот", "кота"], record["forms"])
        self.assertEqual("йод", normalize_form("йо́д"))

    def test_selection_uses_frequency_or_high_familiarity_and_retains_category_without_definition(self):
        records = {word: {"eligible": True, "protected": False, "reasons": []} for word in ("дом", "кот", "рот")}
        ranks = {"дом": {"freq_rank": 1}, "кот": {"freq_rank": None}, "рот": {"freq_rank": None}}
        answers = {word: self.answer(word, familiarity=familiarity) for word, familiarity in (("дом", 3), ("кот", 3), ("рот", 4))}
        selected, reasons, _ = game.select(records, ranks, answers, set(records), self.config, set())
        self.assertEqual({"дом", "рот"}, selected)
        self.assertIn("frequency_or_familiarity", reasons["кот"])

    def test_duplicate_definitions_keep_only_one_row(self):
        failures = {}
        rows = [{"word": word, "origin": "own", "text": "Одинаковый синтетический текст для проверки дублей."} for word in ("дом", "кот")]
        self.assertEqual(1, len(game.deduplicate(rows, failures)))
        self.assertEqual({"кот": ["duplicate"]}, failures)

    def test_wiktionary_track_stays_in_cache_until_human_audit(self):
        self.args.with_wiktionary_glosses = True
        gloss = "Синтетический предмет для условной проверки фильтров."
        self.write_entries(*(entry(word, senses=[{"glosses": ["То же, что предмет", gloss]}]) for word in self.texts))
        summary = self.complete()
        self.assertEqual("awaiting_hybrid_audit", summary["status"])
        self.assertFalse(self.args.out.exists())
        sample = Path(summary["audit_sample"])
        with self.assertRaisesRegex(ValueError, ">=300"):
            game.verify_audit(sample, self.args.out, "Тестировщик", self.args.overrides, self.args.config)
        binding = json.loads(sample.with_suffix(".csv.meta.json").read_text())
        with sqlite3.connect(Path(binding["candidate"]) / "definitions.db") as db:
            rows = db.execute("SELECT source FROM definitions WHERE origin='wiktionary'").fetchall()
            self.assertEqual(1, len(rows))
            self.assertTrue(rows[0][0].startswith("https://ru.wiktionary.org/wiki/"))

    def test_database_validator_rejects_leaks_membership_and_unknown_categories(self):
        self.complete()
        records = game.prefilter(self.dump, self.config, set())["records"]
        taxonomy = json.loads(self.args.categories.read_text())
        path = self.args.out / "definitions.db"
        for column, value in (("word", "случай"), ("category", "unknown"), ("text", "Домашний дом для дома и других домашних дел.")):
            with sqlite3.connect(path) as db:
                original = db.execute("SELECT " + column + " FROM definitions WHERE word='дом'").fetchone()[0]
                db.execute("UPDATE definitions SET " + column + "=? WHERE id=(SELECT id FROM definitions WHERE word='дом')", (value,))
            with self.assertRaises(ValueError):
                game.validate_database(path, records, taxonomy, self.config)
            with sqlite3.connect(path) as db:
                db.execute("UPDATE definitions SET " + column + "=? WHERE " + column + "=?", (original, value))

    def test_gate_invalidates_definition_but_keeps_word_and_category(self):
        game.run(self.args)
        self.fill("definition", lambda item: self.answer(item["word"], definition="Дом для дома и домашнего отдыха с домочадцами.")
                  if item["word"] == "дом" else self.answer(item["word"]))
        game.run(self.args)
        self.fill("guess")
        summary = game.run(self.args)
        self.assertEqual(1, summary["without_definition"])
        self.assertEqual({"answer_leak": 1}, summary["definition_reasons"])

    def test_overlap_regeneration_never_sends_source_and_waits_for_each_attempt(self):
        self.write_entries(*(entry(word, senses=[{"glosses": [text]}]) for word, text in self.texts.items()))
        game.run(self.args)
        self.fill("definition")
        summary = game.run(self.args)
        self.assertEqual("awaiting_rewrite_responses", summary["status"])
        requests = [json.loads(path.read_text()) for path in (self.args.cache / "requests").glob("*.jsonl")]
        self.assertEqual(2, len(requests))
        for request in requests:
            self.assertNotIn(self.texts["дом"], json.dumps(request, ensure_ascii=False))


class QualityTest(unittest.TestCase):
    def setUp(self):
        self.config = game.load_config(game.TOOLS / "selection_config.json")

    def test_root_rules_and_short_function_words(self):
        for left, right in (("дом", "домашний"), ("дерево", "деревянный"), ("шахта", "шахтер"), ("КОТ", "коты")):
            self.assertTrue(shares_root(left, right))
        for left, right in (("кот", "комар"), ("слово", "с"), ("игра", "и"), ("дом", "донор")):
            self.assertFalse(shares_root(left, right))

    def test_similarity_uses_five_character_shingles_and_candidate_denominator(self):
        self.assertEqual(1, gloss_similarity("Синтетическая строка.", ["Синтетическая строка."]))
        self.assertEqual(0, gloss_similarity("абвгд", ["ежзик"]))
        self.assertGreaterEqual(gloss_similarity("Синтетическая строка.", ["Длинная синтетическая строка."]), 0.5)

    def test_quality_rejects_forms_foreign_letters_markup_and_multiline(self):
        cases = [("Место для льва и других зверей с большими клетками.", ["льва"], "answer_leak"),
                 ("Синтетический предмет для хранения двух вещей test.", [], "foreign_letters"),
                 ("Синтетический предмет для хранения вещей <тест>.", [], "digits_html_or_newline"),
                 ("Синтетический предмет\nдля хранения разных вещей.", [], "digits_html_or_newline")]
        for text, forms, reason in cases:
            self.assertIn(reason, definition_issues("лев", text, forms, [], self.config))

    def test_invalid_config_cannot_relax_game_limits(self):
        for key, value in (("min_len", 2), ("max_len", 31), ("batch_size", 41), ("definition_retries", 3)):
            with tempfile.TemporaryDirectory() as root:
                path = Path(root) / "config.json"
                path.write_text(json.dumps(dict(self.config, **{key: value})))
                with self.assertRaises(ValueError):
                    game.load_config(path)


class AuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.candidate, self.out = self.root / "candidate", self.root / "out"
        self.candidate.mkdir()
        words = ["слово" + chr(1072 + i // 32) + chr(1072 + i % 32) for i in range(310)]
        rows = [{"word": word, "display": word, "text": "Синтетическая строка номер " + str(i), "origin": "own", "source": "", "category": "other", "guessable": 1} for i, word in enumerate(words)]
        game.build_database(self.candidate / "definitions.db", rows, dict.fromkeys(words, "other"), {}, 4)
        block = {"sha256": dictionary.sha256_file(self.candidate / "definitions.db"), "rights_policy": "own-generated-v1", "audit": {"passed": False}}
        with patch.object(dictionary, "LEVELS", []):
            dictionary.write_game_data(self.candidate, dict.fromkeys(words, []), "2026-11-01", "a" * 64, block)
        manifest_path = self.candidate / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["selection"] = {"config_file_sha256": dictionary.sha256_file(game.TOOLS / "selection_config.json"),
                                 "remove_sha256": json_digest([]), "remove_definitions_sha256": json_digest([])}
        manifest_path.write_text(json.dumps(manifest))
        self.sample = game.audit_sample(self.candidate, self.out, self.root, 1)

    def fill(self, bad=0):
        with self.sample.open(newline="") as source:
            rows = list(csv.DictReader(source))
        for index, row in enumerate(rows):
            row["ok"], row["bad"] = ("0", "1") if index < bad else ("1", "0")
        game.write_csv(self.sample, list(rows[0]), rows)

    def test_unreviewed_audit_rejected_and_release_not_blocked_without_database(self):
        with self.assertRaisesRegex(ValueError, ">=300"):
            game.verify_audit(self.sample, self.out, "Тестировщик")
        game.require_release_audit(self.out)
        self.assertFalse(self.out.exists())

    def test_exact_three_percent_is_accepted_and_binds_release_to_db_bytes(self):
        self.fill(bad=9)
        self.assertTrue(game.verify_audit(self.sample, self.out, "Тестировщик")["passed"])
        game.require_release_audit(self.out)
        with (self.out / "definitions.db").open("ab") as output:
            output.write(b"tampered")
        with self.assertRaisesRegex(ValueError, "Release"):
            game.require_release_audit(self.out)

    def test_more_than_three_percent_is_rejected(self):
        self.fill(bad=10)
        with self.assertRaisesRegex(ValueError, "bad=10"):
            game.verify_audit(self.sample, self.out, "Тестировщик")

    def test_audit_cannot_replace_sample_or_target_another_output(self):
        self.fill()
        with self.assertRaisesRegex(ValueError, "другой"):
            game.verify_audit(self.sample, self.root / "different", "Тестировщик")
        self.sample.write_text(self.sample.read_text().replace("Синтетическая", "Подменённая"))
        with self.assertRaisesRegex(ValueError, "выборки"):
            game.verify_audit(self.sample, self.out, "Тестировщик")

    def test_creating_sample_twice_preserves_human_marks(self):
        self.fill()
        before = self.sample.read_bytes()
        game.audit_sample(self.candidate, self.out, self.root, 1)
        self.assertEqual(before, self.sample.read_bytes())

    def test_changed_complaint_lists_cannot_be_approved_with_old_audit(self):
        self.fill()
        overrides = self.root / "overrides"
        overrides.mkdir()
        (overrides / "remove_definitions.txt").write_text("словоаа\n")
        with self.assertRaisesRegex(ValueError, "списки жалоб"):
            game.verify_audit(self.sample, self.out, "Тестировщик", overrides)


if __name__ == "__main__":
    unittest.main()
