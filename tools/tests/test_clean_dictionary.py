"""Синтетические данные для нового отбора; чужих толкований и сети нет."""
import argparse
import json
import sys
import tempfile
import unittest
import io
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_dictionary as dictionary
import clean_dictionary as clean


def article(word, gloss="Синтетический предмет для проверки.", **extra):
    return {"word": word, "lang_code": "ru", "lang": "Русский", "pos": "noun",
            "senses": [{"glosses": [gloss]}], **extra}


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dump = self.root / "dump.jsonl"
        self.config = clean.load_config(clean.TOOLS / "selection_config.json")

    def records(self, *entries):
        self.dump.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries), encoding="utf-8")
        return clean.extract_records(self.dump, {dictionary.fold(e["word"]) for e in entries}, self.config)

    def decide(self, records, frequencies=None, protected=()):
        frequencies = frequencies or {}
        ranks = {w: {"frequency": frequencies.get(w, 500), "freq_rank": 1} for w in records}
        return clean.decide(records, ranks, self.config, set(protected))

    def test_labels_in_glosses_are_sense_specific_and_mentions_do_not_exclude(self):
        records = self.records(article("котик", "уменьш.-ласк. к предмет"),
            article("старина", "устар., разг. Синтетическое значение."),
            article("говор", "диал. Синтетическое значение."),
            article("арготизм", "проф. Синтетическое значение."),
            article("новинка", "неол. Синтетическое значение."),
            article("каша", senses=[{"glosses": ["устар. Синтетическое значение."]},
                                     {"glosses": ["Синтетический предмет."]}]),
            article("учитель", "Работник, обсуждающий устар. помету в примере."))
        decisions = self.decide(records)
        for word, reason in (("котик", "diminutive"), ("старина", "obsolete"), ("говор", "dialect"),
                             ("арготизм", "jargon"), ("новинка", "neologism")):
            self.assertEqual("exclude", decisions[word]["decision"], word)
            self.assertIn(reason, decisions[word]["reasons"])
        for word in ("каша", "учитель"):
            self.assertEqual("keep", decisions[word]["decision"])

    def test_suffix_length_topic_and_missing_usage_are_not_definitive_rejection(self):
        long_word = "абвгдежзийклмнопр"
        records = self.records(article("механик"), article("ножик"), article(long_word),
            article("двигатель", "техн. Синтетический механизм."), article("неизвестность"))
        decisions = self.decide(records, {long_word: 500, "двигатель": 500, "неизвестность": 0})
        for word in ("механик", "ножик", long_word, "двигатель"):
            self.assertEqual("keep", decisions[word]["decision"])
        self.assertEqual("review", decisions["неизвестность"]["decision"])
        self.assertEqual(["usage_unconfirmed"], decisions["неизвестность"]["reasons"])

    def test_reference_diminutive_needs_both_suffix_and_marked_target_and_prefixes_continue(self):
        records = self.records(article("предметик", "уменьш. к предмет"),
            article("предметица", "разг. то же, что предметик"), article("орудие", "то же, что предметик"),
            article("механик", "то же, что работник"), article("работник"),
            article("мелочишка", "собир., прост. уничиж. к предмет"))
        decisions = self.decide(records)
        self.assertIn("diminutive", decisions["предметица"]["reasons"])
        self.assertIn("slang", decisions["мелочишка"]["reasons"])
        self.assertEqual("keep", decisions["орудие"]["decision"])
        self.assertEqual("keep", decisions["механик"]["decision"])

    def test_structural_reasons_variants_typos_and_specialist_frequency(self):
        records = self.records(article("читать", pos="verb"), article("Автор"),
            article("сокращение", "аббревиатура от условного сочетания"),
            article("ошибочка", "ошибочное написание условного слова"),
            article("вариант", senses=[{"glosses": ["Синтетический текст"], "alt_of": [{"word": "образец"}]}]),
            article("вещество", "хим. Синтетическое соединение."),
            article("ножичек", senses=[{"glosses": ["Синтетический текст"], "tags": ["diminutive"]}]))
        decisions = self.decide(records, {"вещество": 20, "сокращение": 20})
        for word, reason in (("читать", "not_noun"), ("автор", "proper_name"),
                             ("сокращение", "abbreviation"), ("ошибочка", "typo"),
                             ("вариант", "form_variant"), ("вещество", "narrow_term"), ("ножичек", "diminutive")):
            self.assertIn(reason, decisions[word]["reasons"], word)
        self.assertEqual("keep", self.decide(records, {"вещество": 500})["вещество"]["decision"])

    def test_frequent_noun_abbreviation_is_kept_but_limited_abbreviation_is_excluded(self):
        records = self.records(article("зарплата", "аббревиатура от синтетического сочетания"),
                               article("условник", "аббревиатура от другого условного сочетания"))
        decisions = self.decide(records, {"зарплата": 500, "условник": 20})
        self.assertEqual("keep", decisions["зарплата"]["decision"])
        self.assertEqual(["abbreviation"], decisions["условник"]["reasons"])

    def test_level_exception_is_reported_but_cannot_override_hard_game_limits(self):
        records = self.records(article("старина", "устар. Синтетический предмет."), article("котик", "уменьш. к предмет"))
        decisions = self.decide(records, {"старина": 0}, {"старина"})
        self.assertEqual("keep", decisions["старина"]["decision"])
        self.assertIn("obsolete", decisions["старина"]["would_reject"])
        self.assertEqual("exclude", decisions["котик"]["decision"])
        invalid = self.records(article("два слова"))
        with self.assertRaisesRegex(ValueError, "правил"):
            self.decide(invalid, protected={"два слова"})

    def test_label_chains_full_formulas_and_raw_fields(self):
        for text in ("разг., а также уменьшительное и ласкательное от мама",
                     "(разг.; иногда шутл.) уменьшительно-ласкательное к мама",
                     "ласкательное от мама", "уменьшительное от мама"):
            with self.subTest(text=text):
                self.assertIn("diminutive", clean.gloss_labels(text, self.config)[0])
        records = self.records(article("мамочка", senses=[{
            "raw_tags": ["разг., экспр., уменьшительно-ласкательное"],
            "glosses": ["Синтетическое значение."]}]),
            article("грубиян", senses=[{"tags": ["vulgar"], "glosses": ["Тест."]}]))
        self.assertIn("diminutive", self.decide(records)["мамочка"]["reasons"])
        self.assertIn("offensive", self.decide(records)["грубиян"]["reasons"])

    def test_glosses_of_one_sense_cannot_rescue_each_other(self):
        records = self.records(article("котик", senses=[{
            "glosses": ["уменьшительное от кот", "Синтетическое пояснение."]}]),
            article("замочек", senses=[{"glosses": ["уменьшительное от замок"]},
                                        {"glosses": ["Синтетическое самостоятельное устройство."]}]))
        self.assertEqual(1, len(records["котик"]["senses"]))
        self.assertEqual("exclude", self.decide(records)["котик"]["decision"])
        self.assertEqual(1, self.decide(records)["замочек"]["usable_sense_count"])
        self.assertEqual(["ambiguous_diminutive_usage"], self.decide(records)["замочек"]["reasons"])

    def test_owner_lexicalization_requires_source_and_does_not_override_bad_labels(self):
        records = self.records(*(article(w, "уменьшительное от " + base) for w, base in
            (("ручка", "рука"), ("ножка", "нога"), ("рыбка", "рыба"),
             ("мамочка", "мама"), ("котик", "кот"), ("зайчик", "заяц"))),
            article("очки"), article("макароны"), article("механик"), article("цепочка"))
        decisions = self.decide(records)
        for word in ("ручка", "ножка", "рыбка", "очки", "макароны", "механик", "цепочка"):
            self.assertEqual("keep", decisions[word]["decision"], word)
        for word in ("мамочка", "котик", "зайчик"):
            self.assertEqual("exclude", decisions[word]["decision"], word)
        self.assertTrue(decisions["рыбка"]["roles"]["answer"]["exception"])
        records = self.records(article("рыбка", "диал. уменьшительное от рыба"),
                               article("ручка", pos="verb"))
        self.assertNotEqual("keep", self.decide(records)["рыбка"]["decision"])
        self.assertNotEqual("keep", self.decide(records)["ручка"]["decision"])

    def test_reference_uses_stem_all_senses_and_fixed_point(self):
        entries = [article("предметик", "уменьшительное от предмет"),
                   article("предметица", "то же, что предметик"),
                   article("предметишка", "то же, что предметица"),
                   article("домик", "то же, что предметик"),
                   article("замочек", senses=[{"glosses": ["уменьшительное от замок"]},
                                                {"glosses": ["Синтетический механизм."]}]),
                   article("замочечка", "то же, что замочек")]
        records = self.records(*entries)
        decisions = self.decide(records)
        for word in ("предметица", "предметишка"):
            self.assertIn("diminutive", decisions[word]["reasons"])
        self.assertEqual("keep", decisions["домик"]["decision"])
        self.assertEqual("review", decisions["замочечка"]["decision"])
        entries[4]["senses"].reverse()
        self.assertEqual(decisions, self.decide(self.records(*reversed(entries))))

    def test_abbreviations_need_lexicalization_and_compounds_need_review(self):
        records = self.records(article("зарплата", "сокр. от синтетического сочетания"),
            article("профсоюз", "сложносокращённое слово от синтетического сочетания"),
            article("спецотдел", "сложносокращённое слово от синтетического сочетания"),
            article("госотдел"), article("отдел"),
            article("условник", "сокращённое наименование синтетического сочетания"))
        decisions = self.decide(records)
        for word in ("зарплата", "профсоюз"):
            self.assertEqual("keep", decisions[word]["decision"])
        for word in ("спецотдел", "условник"):
            self.assertIn("abbreviation", decisions[word]["reasons"])
        self.assertEqual("review", decisions["госотдел"]["decision"])
        self.assertIn("compound_abbreviation_candidate", decisions["госотдел"]["reasons"])

    def test_restricted_semantics_frequency_and_independent_senses(self):
        records = self.records(article("летопись", "истор., редк. Синтетический предмет."),
            article("обряд", "религ. Синтетический предмет."),
            article("авторка", "женск. к автор"), article("дежурный", "субстантивир. Синтетическое лицо."),
            article("старина", "устар. Синтетический предмет."))
        high = self.decide(records)
        for word in ("летопись", "обряд", "авторка", "дежурный"):
            self.assertEqual("keep", high[word]["decision"], word)
            self.assertNotEqual("keep", self.decide(records, {word: 20})[word]["decision"], word)
        self.assertEqual("exclude", high["старина"]["decision"])

    def test_v2_roles_do_not_inherit_v1_exceptions(self):
        records = self.records(*(article(w) for w in ("прелюбодейка", "брудершафт", "праймериз",
            "хлопкопункт", "реввоенсовет", "селитроварня", "неизвестность")))
        decisions = self.decide(records, {w: 0 for w in ("хлопкопункт", "реввоенсовет", "селитроварня", "неизвестность")},
                                {"неизвестность"})
        for word in ("прелюбодейка", "брудершафт", "праймериз"):
            self.assertEqual("keep", decisions[word]["roles"]["answer"]["decision"])
            for role in ("source", "target"):
                self.assertIn("campaign_role_exclusion", decisions[word]["roles"][role]["reasons"])
        for word in ("хлопкопункт", "реввоенсовет", "селитроварня"):
            self.assertEqual("keep", decisions[word]["roles"]["source"]["decision"])
            self.assertNotEqual("keep", decisions[word]["roles"]["target"]["decision"])
        self.assertEqual("keep", decisions["неизвестность"]["decision"])
        self.assertEqual("review", decisions["неизвестность"]["roles"]["answer"]["decision"])
        self.assertTrue(decisions["неизвестность"]["level_exception"])

    def test_broad_input_retains_only_diminutive_entries_for_owner_policy(self):
        self.records(article("рыбка", senses=[{"tags": ["diminutive"], "glosses": ["Тест."]}]),
                     article("рыба"), article("Заголовок"), article("бежать", pos="verb"))
        records = clean.extract_records(self.dump, None, self.config)
        self.assertEqual({"рыбка", "рыба"}, set(records))
        self.assertEqual("keep", self.decide(records)["рыбка"]["decision"])

    def test_inherited_fields_and_independent_plural_sense(self):
        records = self.records(article("предмет", tags=["obsolete"], senses=[
            {"glosses": ["Синтетический предмет."]}, {"glosses": ["Синтетический механизм."]}]),
            article("очки"), article("очки", senses=[{"tags": ["form-of"], "form_of": [{"word": "очко"}]}]),
            article("макароны", tags=["plural-only"]))
        decisions = self.decide(records)
        self.assertEqual("exclude", decisions["предмет"]["decision"])
        for word in ("очки", "макароны"):
            self.assertEqual("keep", decisions[word]["decision"])

    def test_unknown_label_does_not_hide_forbidden_chain_and_body_mentions_are_ignored(self):
        reasons, _, _ = clean.gloss_labels("неизв., разг.-сниж., диал. Синтетический предмет.", self.config)
        self.assertTrue({"unknown_label", "slang", "dialect"} <= reasons)
        self.assertEqual(set(), clean.gloss_labels("Синтетический прибор с пометой уменьшительное от образец.", self.config)[0])
        self.assertEqual(set(), clean.gloss_labels("Синтетический прибор из словаря автора.", self.config)[0])

    def test_obsolete_diminutive_and_affectionate_usage_do_not_hide_independent_sense(self):
        records = self.records(article("бабочка", senses=[{"glosses": ["устар. уменьшительное от баба"]},
                                                          {"glosses": ["энтомол. Синтетический вид."]}]),
            article("сокол", senses=[{"glosses": ["ласк. Синтетическое обращение."]},
                                     {"glosses": ["орнитол. Синтетический вид."]}]),
            article("компьютер"), article("пьютер", "техн. Синтетический материал."))
        for word in ("бабочка", "сокол", "компьютер"):
            self.assertEqual("keep", self.decide(records)[word]["decision"], word)

    def test_source_preference_does_not_override_forbidden_senses(self):
        for label in ("диал.", "устар.", "груб.", "обсц."):
            with self.subTest(label=label):
                records = self.records(article("хлопкопункт", label + " Синтетический предмет."))
                self.assertEqual("exclude", self.decide(records)["хлопкопункт"]["roles"]["source"]["decision"])
        records = self.records(article("реввоенсовет", "аббревиатура от синтетического сочетания"))
        decision = self.decide(records, {"реввоенсовет": 0})["реввоенсовет"]
        self.assertEqual("exclude", decision["roles"]["answer"]["decision"])
        self.assertEqual("keep", decision["roles"]["source"]["decision"])

    def test_audit_only_reports_roles_funnel_golden_and_never_builds_package(self):
        self.records(article("рыбка", "уменьшительное от рыба"), article("котик", "уменьшительное от кот"),
                     article("праймериз"), article("реввоенсовет"))
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"source": {"dump_date": "2026-11-01",
            "sha256_input": dictionary.sha256_file(self.dump)}}))
        corpus = self.root / "freq.txt"
        corpus.write_text("рыбка 1000\nкотик 1000\nпраймериз 100\n")
        golden = self.root / "golden.txt"
        golden.write_text("рыбка\nкотик\nнетслова\n")
        args = argparse.Namespace(dump=self.dump, manifest=manifest, source_words=None,
            frequency_list=corpus, config=clean.TOOLS / "selection_config.json", cache=self.root / "cache",
            reports=self.root / "reports", out=self.root / "out", golden=golden, prepare_only=False, audit_only=True)
        with patch.object(clean, "publish", side_effect=AssertionError("audit must not publish")), \
             patch.object(clean, "prepare_levels", side_effect=AssertionError("audit must not build levels")):
            result = clean.run(args)
            before = {p.name: p.read_bytes() for p in args.reports.iterdir()}
            with patch.object(clean, "extract_records", side_effect=AssertionError("cache must be used")):
                self.assertEqual(result, clean.run(args))
        self.assertEqual(before, {p.name: p.read_bytes() for p in args.reports.iterdir()})
        self.assertEqual("audited", result["status"])
        self.assertFalse(args.out.exists())
        self.assertFalse((args.cache / "builds").exists())
        self.assertEqual({"котик": ["diminutive"], "нетслова": ["outside_input"]}, result["reference_checks"]["golden_excluded"])
        self.assertEqual({}, result["reference_checks"]["level_exceptions"])
        self.assertEqual(4, json.loads((args.reports / "funnel.json").read_text())["input"])
        self.assertIn('"frequency": 1000', (args.reports / "rejection_examples.json").read_text())
        self.assertIn("owner-confirmed-lexicalization", (args.reports / "role_exceptions.csv").read_text())
        self.assertEqual("рыбка\nкотик\nнетслова\n", golden.read_text())

    def test_replacements_preserve_numbers_and_unapproved_unreachable_levels_fail(self):
        selected = {"рот", "тор", "торт", "трот"}
        old = [("исходник", 1, 2, "и", 1)]
        levels, changes = clean.prepare_levels(selected, old, {"исходник": {"word": "торт", "letter": "т"}})
        self.assertEqual([("торт", 1, 2, "т", 1)], levels)
        self.assertEqual("исходник", changes[0]["old_word"])
        with self.assertRaisesRegex(ValueError, "недостижим"):
            clean.prepare_levels(selected, old, {})
        with self.assertRaisesRegex(ValueError, "повторные"):
            clean.prepare_levels(selected, old + [("торт", 1, 2, "т", 1)], {"исходник": {"word": "торт", "letter": "т"}})
        self.assertEqual([("исходник", 1, 2, "и", 1)], old)

    def test_full_build_ignores_old_overrides_definitions_and_cap_and_is_deterministic(self):
        self.records(article("торт"), article("рот"), article("тор"), article("котик", "уменьш. к предмет"))
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"source": {"dump_date": "2026-11-01",
            "sha256_input": dictionary.sha256_file(self.dump)}}))
        corpus = self.root / "freq.txt"
        corpus.write_text("торт 20\nрот 100\nтор 100\nкотик 1000\n")
        config = json.loads((clean.TOOLS / "selection_config.json").read_text())
        config["cleanup"]["level_replacements"] = {}
        config["target_size"] = config["llm_candidates"] = 1
        config_path = self.root / "config.json"
        config_path.write_text(json.dumps(config))
        golden = self.root / "golden.txt"
        golden.write_text("котик\nрот\n")
        source = self.root / "source.txt"
        source.write_text("котик\nрот\nтор\nторт\n")
        args = argparse.Namespace(dump=self.dump, manifest=manifest, source_words=source,
            frequency_list=corpus, config=config_path, cache=self.root / "cache", reports=self.root / "reports",
            out=self.root / "out", golden=golden, prepare_only=False)
        with patch.object(dictionary, "LEVELS", [("торт", 1, 2, "т", 1)]):
            result = clean.run(args)
            before = {p.name: p.read_bytes() for p in args.out.iterdir()}
            with patch.object(clean, "extract_records", side_effect=AssertionError("cache not used")):
                clean.run(args)
        self.assertEqual(before, {p.name: p.read_bytes() for p in args.out.iterdir()})
        self.assertEqual({"рот", "тор", "торт"}, set(dictionary.read_list(args.out / "words.txt")))
        self.assertEqual(3, result["selected"])
        self.assertFalse((args.out / "definitions.db").exists())
        metadata = json.loads((args.out / "manifest.json").read_text())["selection"]
        self.assertEqual("game-cleanup-v1", metadata["mode"])
        self.assertIn("котик", metadata["reference_checks"]["golden_excluded"])
        self.assertFalse(metadata["audit"]["passed"])
        self.assertNotIn("license", metadata["frequency_source"])
        with patch.object(dictionary, "LEVELS", [("торт", 100, 100, "т", 1)]):
            with self.assertRaisesRegex(ValueError, "недостижим"):
                clean.run(args)
        self.assertEqual(before, {p.name: p.read_bytes() for p in args.out.iterdir()})

    def test_hash_mismatch_and_broken_levels_do_not_replace_existing_data(self):
        self.records(article("кот"))
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"source": {"dump_date": "2026-11-01", "sha256_input": "0" * 64}}))
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            clean.run(argparse.Namespace(manifest=manifest, dump=self.dump, frequency_list=self.dump))
        with patch.object(dictionary, "LEVELS", [("нетслов", 1, 2, "н", 1)]):
            with self.assertRaisesRegex(ValueError, "недостижим"):
                clean.prepare_levels({"кот"}, dictionary.LEVELS, {})
        with self.assertRaisesRegex(ValueError, "--dump"):
            clean.run(argparse.Namespace(dump=None))
        with self.assertRaisesRegex(ValueError, "--frequency-list"):
            clean.run(argparse.Namespace(dump=self.dump, frequency_list=self.root / "missing"))

    def test_frequency_provenance_checks_known_source_and_does_not_invent_license(self):
        path = self.root / "freq.txt"
        path.write_text("кот 10\n")
        digest = dictionary.sha256_file(path)
        self.assertEqual({"kind": "frequency-list", "sha256": digest}, clean.frequency_provenance(path, digest))
        metadata = {"files": [{"file": path.name, "sha256": digest}], "dataset": "Synthetic corpus",
                    "content_license_declared_by_upstream": "Synthetic terms"}
        (self.root / "source.json").write_text(json.dumps(metadata))
        self.assertEqual("Synthetic terms", clean.frequency_provenance(path, digest)["license"])
        with self.assertRaisesRegex(ValueError, "source.json"):
            clean.frequency_provenance(path, "0" * 64)

    def test_main_pipeline_defaults_to_cleanup_and_never_calls_ai(self):
        import build_game_data as game
        with patch.object(sys, "argv", ["build_game_data.py", "--dump", str(self.dump),
                "--frequency-list", "frequency.txt", "--prepare-only"]), \
                patch.object(clean, "run", return_value={"status": "prepared"}) as build, \
                patch.object(game, "FileProvider", side_effect=AssertionError("AI must not be used")), \
                patch("sys.stdout", new_callable=io.StringIO):
            game.main()
        self.assertEqual("clean", build.call_args.args[0].definitions_source)
        self.assertTrue(build.call_args.args[0].prepare_only)


if __name__ == "__main__":
    unittest.main()
