"""Локальные определения: только синтетические статьи, без сети."""
import argparse
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import unquote

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_dictionary as dictionary
import build_local_definitions as local
from build_game_data import require_release_audit


def article(word, text, **extra):
    return {"word": word, "pos": "noun", "lang_code": "ru", "lang": "Русский",
            "senses": [{"glosses": [text], "examples": [{"text": "НЕ_КОПИРОВАТЬ_ПРИМЕР"}]}], **extra}


class LocalDefinitionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "input"
        self.source.mkdir()
        self.dump = self.root / "dump.jsonl"
        self.remove = self.root / "remove_definitions.txt"
        self.remove.write_text("")
        self.settings = local.load_settings(local.TOOLS / "definition_import_config.json")
        self.cleanup = local.clean.load_config(local.TOOLS / "selection_config.json")
        self.args = argparse.Namespace(dump=self.dump, manifest=self.source / "manifest.json",
            config=local.TOOLS / "definition_import_config.json", selection_config=local.TOOLS / "selection_config.json",
            cache=self.root / "cache", reports=self.root / "reports", out=self.root / "output",
            remove_definitions=self.remove, prepare_only=False)

    def write_source(self, entries, words=("портал", "рот", "тор")):
        self.dump.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries))
        sources={w: [{"title": w, "line": 1, "source": "https://ru.wiktionary.org/wiki/" + w}] for w in words}
        dictionary.write_game_data(self.source, sources, "2026-11-01", dictionary.sha256_file(self.dump),
                                   levels_spec=[("портал", 1, 1, "р", 1)] if "портал" in words else [])
        manifest=json.loads(self.args.manifest.read_text())
        manifest["selection"]={"mode": "game-cleanup-v1", "unchanged": "word-policy"}
        self.args.manifest.write_text(json.dumps(manifest))

    def test_plain_text_removes_markup_examples_and_preserves_cyrillic(self):
        self.assertEqual("Синтетический текст со знаком &.", local.plain_text("<b>Синтетический</b>\nтекст со знаком &amp;."))
        self.assertEqual("Только текст.", local.plain_text("Только текст.◆Литературный пример"))
        self.assertEqual("Текст.", local.plain_text("<script>лишний код</script>Текст."))

    def test_short_meaning_is_kept_independently_of_hint_quality(self):
        self.write_source([article("портал", "Тест.")])
        records=local.extract_articles(self.dump,self.cleanup)
        rows,failures,_=local.select_definitions({"портал"},records,self.settings,set())
        self.assertEqual("Тест.",rows[0]["text"])
        self.assertFalse(failures)

    def test_forms_and_only_observed_nearest_cognates_are_saved_and_reversed(self):
        self.write_source([article("портал", "Синтетическое значение для проверки.", forms=[{"form": "порта́ла"}],
            related=[{"word": "портальный", "raw_tags": ["Ближайшее родство"]},
                     {"word": "рот", "raw_tags": ["Ближайшее родство"]}, {"word": "чужой", "raw_tags": ["См. также"]}],
            synonyms=[{"word": "синоним"}]), article("рот", "Другое синтетическое значение.")])
        data=local.extract_articles(self.dump,self.cleanup)
        self.assertIn("портала",data["портал"]["forms"])
        self.assertIn("портальный",data["портал"]["related"])
        self.assertIn("портал",data["рот"]["related"])
        self.assertNotIn("чужой",data["портал"]["related"])
        self.assertNotIn("синоним",data["портал"]["related"])
        self.assertNotIn("НЕ_КОПИРОВАТЬ_ПРИМЕР", json.dumps(data,ensure_ascii=False))

    def test_resolves_reference_outside_game_and_skips_cycles_without_dropping_words(self):
        self.write_source([article("портал", "то же, что образец"), article("образец", "Синтетический предмет для опытной проверки."),
                           article("рот", "то же, что тор"), article("тор", "то же, что рот")])
        records=local.extract_articles(self.dump,self.cleanup)
        rows,failures,resolved=local.select_definitions({"портал","рот","тор"},records,self.settings,set())
        self.assertEqual({"портал"},{r["word"] for r in rows})
        self.assertIn("образец",unquote(rows[0]["source"]))
        self.assertIn("рот",failures)
        self.assertTrue(resolved)

    def test_build_preserves_dictionary_populates_source_forms_and_is_deterministic(self):
        self.write_source([article("портал","Портальный проём служит синтетическим образцом.", forms=[{"form":"портала"}]),
                           article("рот","Полость для синтетической проверки.")])
        before={name:(self.source/name).read_bytes() for name in local.WORD_ARTIFACTS}
        summary=local.run(self.args)
        self.assertEqual(3,summary["words"])
        self.assertEqual(2,summary["words_with_definitions"])
        for name,value in before.items(): self.assertEqual(value,(self.args.out/name).read_bytes())
        manifest=json.loads((self.args.out/'manifest.json').read_text())
        self.assertEqual({"mode":"game-cleanup-v1","unchanged":"word-policy"},manifest["selection"])
        self.assertFalse(manifest["definitions"]["audit"]["passed"])
        with sqlite3.connect(self.args.out/'definitions.db') as db:
            self.assertGreaterEqual(db.execute('PRAGMA user_version').fetchone()[0],5)
            self.assertIn('портала',json.loads(db.execute('SELECT forms FROM word_metadata WHERE word=?',('портал',)).fetchone()[0]))
            self.assertEqual(0,db.execute("SELECT count(*) FROM definitions WHERE origin!='wiktionary' OR source='' OR example!=''").fetchone()[0])
        snapshot={p.name:p.read_bytes() for p in self.args.out.iterdir()}
        with patch.object(local,'extract_articles',side_effect=AssertionError('cache not used')): local.run(self.args)
        self.assertEqual(snapshot,{p.name:p.read_bytes() for p in self.args.out.iterdir()})
        with self.assertRaisesRegex(ValueError,'Release'):require_release_audit(self.args.out)

    def test_definition_removal_changes_db_version_but_never_words(self):
        self.write_source([article('портал','Синтетический предмет для опытной проверки.')])
        local.run(self.args)
        with sqlite3.connect(self.args.out/'definitions.db') as db: version=db.execute('PRAGMA user_version').fetchone()[0]
        self.remove.write_text('портал\n')
        local.run(self.args)
        with sqlite3.connect(self.args.out/'definitions.db') as db:
            self.assertGreater(db.execute('PRAGMA user_version').fetchone()[0],version)
            self.assertEqual(0,db.execute('SELECT count(*) FROM definitions').fetchone()[0])
        self.assertIn('портал',dictionary.read_list(self.args.out/'words.txt'))

    def test_categories_use_matching_sense_and_keep_hidden_homonyms_out_of_t6(self):
        bird = {"glosses": ["Синтетический летающий образец."], "topics": ["ornithology"]}
        tool = {"glosses": ["Синтетический рабочий образец."], "raw_tags": ["инструменты"]}
        self.write_source([
            article("портал", "", categories=["ru:Животные"], senses=[bird, dict(bird), tool]),
            article("рот", "Птица для синтетического опыта.", topics=["ornithology"]),
        ])
        local.run(self.args)
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(("other", "unknown:ambiguous_senses"), db.execute(
                "SELECT category,category_source FROM word_metadata WHERE word='портал'").fetchone())
            self.assertEqual(("other", "unknown:no_usable_sense"), db.execute(
                "SELECT category,category_source FROM word_metadata WHERE word='тор'").fetchone())
            self.assertEqual([("birds",), ("tools",)], db.execute(
                "SELECT category FROM definitions WHERE word='портал' ORDER BY ord").fetchall())
        index = json.loads((self.args.reports / "category_index.json").read_text())
        self.assertEqual(["рот"], index["categories"]["birds"])
        self.assertNotIn("other", index["categories"])
        self.assertEqual(dictionary.sha256_file(self.args.out / "definitions.db"), index["database_sha256"])
        self.assertEqual({"reason": "ambiguous_senses", "sense_count": 3,
                          "candidate_categories": ["birds", "tools"]}, index["classification_features"]["портал"])
        self.assertEqual({"reason": "no_usable_sense", "sense_count": 0,
                          "candidate_categories": []}, index["classification_features"]["тор"])
        self.assertNotIn("рот", index["classification_features"])
        # Repeated gloss text must not hide differently labelled meanings.
        self.write_source([article("портал", "", senses=[bird, dict(tool, glosses=bird["glosses"])])])
        local.run(self.args)
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual("other", db.execute("SELECT category FROM word_metadata WHERE word='портал'").fetchone()[0])

    def test_meaning_beyond_display_limit_and_uncertain_reference_are_not_t6_targets(self):
        birds = [{"glosses": [f"Птица для синтетического опыта номер {i}."]} for i in range(3)]
        self.write_source([article("портал", "", senses=birds + [{"glosses": ["Инструмент для опыта."]}]),
                           article("рот", "то же, что портал")])
        local.run(self.args)
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(2, db.execute("SELECT count(*) FROM definitions WHERE word='портал'").fetchone()[0])
            self.assertEqual([("other",), ("other",)], db.execute(
                "SELECT category FROM word_metadata WHERE word IN ('портал','рот') ORDER BY word").fetchall())
        index = json.loads((self.args.reports / "category_index.json").read_text())
        self.assertFalse(any(index["categories"].values()))

    def test_v7_upgrade_and_fresh_cache_rebuild_have_stable_v8_bytes(self):
        self.write_source([article("портал", "Птица для синтетического опыта.", forms=[{"form": "портала"}],
                                   related=[{"word": "портальный", "raw_tags": ["Ближайшее родство"]}])])
        self.args.out.mkdir()
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            db.executescript("""
                PRAGMA user_version=7;
                CREATE TABLE word_metadata(word TEXT PRIMARY KEY, category TEXT, forms TEXT, related TEXT);
                CREATE TABLE build_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                INSERT INTO build_metadata VALUES('content_sha256','previous-v7-content');
            """)
        local.run(self.args)
        with sqlite3.connect(self.args.out / "definitions.db") as db:
            self.assertEqual(8, db.execute("PRAGMA user_version").fetchone()[0])
            category, source, forms, related = db.execute(
                "SELECT category,category_source,forms,related FROM word_metadata WHERE word='портал'").fetchone()
            self.assertEqual(("birds", "gloss_head:birds"), (category, source))
            self.assertIn("портала", json.loads(forms))
            self.assertEqual(["портальный"], json.loads(related))
        snapshot = (self.args.out / "definitions.db").read_bytes()
        index = (self.args.reports / "category_index.json").read_bytes()
        local.run(self.args)
        self.assertEqual(snapshot, (self.args.out / "definitions.db").read_bytes())
        self.assertEqual(index, (self.args.reports / "category_index.json").read_bytes())
        self.args.cache = self.root / "fresh-cache"
        local.run(self.args)
        self.assertEqual(snapshot, (self.args.out / "definitions.db").read_bytes())
        self.assertEqual(index, (self.args.reports / "category_index.json").read_bytes())

    def test_bad_dump_or_dictionary_hash_stops_before_publication(self):
        self.write_source([article('портал','Синтетический предмет для опытной проверки.')])
        self.dump.write_text('{}')
        with self.assertRaisesRegex(ValueError,'SHA-256'):local.run(self.args)
        self.assertFalse(self.args.out.exists())
        self.write_source([article('портал','Синтетический предмет для опытной проверки.')])
        (self.source/'words.txt').write_text('другое\n')
        with self.assertRaisesRegex(ValueError,'words.txt'):local.run(self.args)
        self.assertFalse(self.args.out.exists())


if __name__=='__main__':unittest.main()
