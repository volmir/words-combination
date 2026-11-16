"""Minimal descriptions: synthetic mutations, no imported article prose."""
import copy
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest

from tools import build_owned_hints as owned
from tools.owned_hints import sha
from tools.build_game_data import require_release_audit, publish

ROOT = Path(__file__).resolve().parents[2]


class OwnedHintsInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.data = Path(self.temp.name) / 'data'
        self.data.mkdir()
        for name in ('campaign.json', 'words.txt', 'manifest.json', 'definitions.db'):
            shutil.copyfile(ROOT / 'data' / name, self.data / name)

    def test_rebuild_is_byte_identical_with_no_imported_columns_or_free_pages(self):
        before = {name: sha(self.data / name) for name in ('campaign.json', 'words.txt', 'definitions.db', 'manifest.json')}
        self.assertEqual(39, owned.verify(self.data)['own'])
        owned.install(self.data)
        self.assertEqual(before, {name: sha(self.data / name) for name in before})
        with sqlite3.connect(self.data / 'definitions.db') as db:
            self.assertEqual([('own', 39)], db.execute('SELECT origin,count(*) FROM definitions GROUP BY origin').fetchall())
            self.assertEqual(0, db.execute("SELECT count(*) FROM definitions WHERE notes!='' OR example!='' OR source!='' OR antonym!=''").fetchone()[0])
            self.assertEqual(20120, db.execute('SELECT count(*) FROM word_metadata').fetchone()[0])
            self.assertIsNone(db.execute("SELECT text FROM definitions WHERE word='бык'").fetchone())

    def test_mutations_reject_text_origin_notes_metadata_and_deletion_even_with_new_hash(self):
        original = (self.data / 'definitions.db').read_bytes()
        for sql in ["UPDATE definitions SET text='Синтетическая подмена' WHERE id=1",
                    "UPDATE definitions SET origin='wiktionary' WHERE id=1",
                    "UPDATE definitions SET notes='Синтетический скрытый текст' WHERE id=1",
                    "UPDATE word_metadata SET related='[\"Синтетическое предложение\"]' WHERE word='артист'",
                    'DELETE FROM definitions WHERE id=1']:
            with self.subTest(sql=sql):
                (self.data / 'definitions.db').write_bytes(original)
                with sqlite3.connect(self.data / 'definitions.db') as db:
                    db.execute(sql)
                manifest = json.loads((self.data / 'manifest.json').read_text())
                manifest['definitions']['sha256'] = sha(self.data / 'definitions.db')
                (self.data / 'manifest.json').write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    owned.verify(self.data)
                with self.assertRaises(ValueError):
                    require_release_audit(self.data, allow_unaudited=True)

    def test_missing_or_leaking_mandatory_t4_and_stale_screening_are_rejected(self):
        candidate = json.loads(owned.CANDIDATE.read_text())
        for mode in ('missing', 'leak', 'changed'):
            altered = copy.deepcopy(candidate)
            entry = next(e for e in altered['entries'] if any(r['type'] == 'T4' for r in e['roles']))
            if mode == 'missing':
                altered['entries'].remove(entry)
            elif mode == 'leak':
                entry['hint'] = entry['word']
            else:
                entry['hint'] = 'Новый синтетический текст, который ещё не прошёл проверку происхождения.'
            path = Path(self.temp.name) / 'candidate.json'
            path.write_text(json.dumps(altered))
            with self.assertRaises(ValueError):
                owned.inputs(self.data, path)

    def test_release_waiver_never_bypasses_owned_only_or_coverage_check(self):
        with self.assertRaisesRegex(ValueError, 'не утверждены'):
            require_release_audit(self.data)
        self.assertFalse(require_release_audit(self.data, allow_unaudited=True)['publishable'])
        (self.data / 'definitions.db').unlink()
        with self.assertRaises(ValueError):
            require_release_audit(self.data, allow_unaudited=True)

    def test_installer_cannot_restore_provisional_definitions_over_minimal_data(self):
        stage = Path(self.temp.name) / 'old'
        stage.mkdir()
        manifest = json.loads((self.data / 'manifest.json').read_text())
        manifest['definitions']['rights_policy'] = 'wiktionary-provisional-v1'
        (stage / 'manifest.json').write_text(json.dumps(manifest))
        (stage / 'definitions.db').write_bytes(b'synthetic-old-database')
        before = sha(self.data / 'definitions.db')
        with self.assertRaises((ValueError, FileNotFoundError)):
            publish(stage, self.data, ('definitions.db', 'manifest.json'))
        self.assertEqual(before, sha(self.data / 'definitions.db'))

    def test_categories_have_provenance_and_all_twenty_t6_words_remain_available(self):
        candidate, index, metadata, _ = owned.inputs(self.data)
        campaign = json.loads((self.data / 'campaign.json').read_text())
        task = next(t for l in campaign['levels'] for t in l['tasks'] if t['type'] == 'T6')
        self.assertEqual(4, task['n'])
        self.assertEqual(20, len(task['words']))
        self.assertTrue(all(metadata[w]['category'] == 'professions' and
                            metadata[w]['category_source'].startswith('owner:SHORT15-05') for w in task['words']))
        self.assertEqual('CC-BY-SA-4.0', index['license'])
        self.assertEqual([], index['screening']['suspicious_matches'])

    def test_standalone_release_cli_checks_minimal_data_with_existing_waiver(self):
        result = subprocess.run([sys.executable, str(ROOT / 'tools/build_game_data.py'), '--check-release',
                                 '--out', str(self.data), '--allow-unaudited-release'],
                                cwd=self.temp.name, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(json.loads(result.stdout)['publishable'])


if __name__ == '__main__':
    unittest.main()
