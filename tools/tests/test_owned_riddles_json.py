"""Runtime riddles are an exact, approved subset; every corruption fails closed."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from tools import build_owned_hints as owned
from tools.owned_hints import sha

ROOT = Path(__file__).resolve().parents[2]
KEYS = {'работа', 'утро', 'отель', 'десерт', 'проблема', 'билет', 'ковер', 'весна', 'стадион', 'банк', 'артист'}

class OwnedRiddlesJsonTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.data = Path(self.temp.name)
        for name in ('words.txt', 'campaign.json', 'manifest.json'):
            shutil.copyfile(ROOT / 'data' / name, self.data / name)

    def test_exact_approved_subset_and_byte_determinism(self):
        before = (self.data / 'campaign.json').read_bytes()
        owned.install(self.data)
        payload = (self.data / 'owned_riddles.json').read_bytes()
        result = json.loads(payload)
        self.assertEqual('owned-riddles', result['schema'])
        self.assertEqual(1, result['version'])
        self.assertEqual('CC0-1.0', result['license'])
        self.assertEqual(KEYS, set(result['riddles']))
        authored = json.loads((ROOT / 'docs/owned_hints.json').read_text())
        expected = {e['word']: e['hint'] for e in authored['entries'] if e['word'] in KEYS}
        self.assertEqual(expected, result['riddles'])
        self.assertEqual(before, (self.data / 'campaign.json').read_bytes())
        manifest = json.loads((self.data / 'manifest.json').read_text())
        self.assertNotIn('definitions', manifest)
        self.assertEqual(sha(self.data / 'owned_riddles.json'), manifest['owned_riddles']['sha256'])
        owned.install(self.data)
        self.assertEqual(payload, (self.data / 'owned_riddles.json').read_bytes())
        self.assertEqual(11, owned.verify(self.data)['t4'])

    def test_corruption_is_rejected_even_with_updated_manifest_hash(self):
        owned.install(self.data)
        original = json.loads((self.data / 'owned_riddles.json').read_bytes())
        changes = []
        for key, value in [('schema','unknown'),('version',True),('license','MIT')]:
            changed = copy.deepcopy(original); changed[key] = value; changes.append(json.dumps(changed,ensure_ascii=False))
        for mode in ('missing','extra','empty','changed','not_normalized'):
            changed = copy.deepcopy(original)
            if mode == 'missing': del changed['riddles']['банк']
            if mode == 'extra': changed['riddles']['дом'] = 'Посторонний текст.'
            if mode == 'empty': changed['riddles']['банк'] = ''
            if mode == 'changed': changed['riddles']['банк'] += '!'
            if mode == 'not_normalized': changed['riddles']['ковёр'] = changed['riddles'].pop('ковер')
            changes.append(json.dumps(changed,ensure_ascii=False))
        changes += ['{broken', (self.data / 'owned_riddles.json').read_text().replace('"банк":','"банк":"duplicate","банк":')]
        for raw in changes:
            with self.subTest(raw=raw[:50]):
                (self.data / 'owned_riddles.json').write_text(raw,encoding='utf8')
                m=json.loads((self.data / 'manifest.json').read_text());m['owned_riddles']['sha256']=sha(self.data/'owned_riddles.json')
                (self.data/'manifest.json').write_text(json.dumps(m))
                with self.assertRaises(ValueError): owned.verify(self.data)

    def test_missing_rights_or_changed_text_cannot_be_installed(self):
        docs=self.data/'docs';docs.mkdir()
        for name in ('owned_hints.json','OWN_HINTS_OWNER_APPROVAL.json','OWN_HINTS_MANUAL_AUDIT.json'):
            shutil.copyfile(ROOT/'docs'/name,docs/name)
        for name in ('OWN_HINTS_OWNER_APPROVAL.json','OWN_HINTS_MANUAL_AUDIT.json'):
            path=docs/name; raw=path.read_bytes();path.unlink()
            with self.assertRaises(ValueError): owned.install(self.data,docs/'owned_hints.json')
            path.write_bytes(raw)
        c=json.loads((docs/'owned_hints.json').read_text());c['entries'][0]['hint']+='!'
        (docs/'owned_hints.json').write_text(json.dumps(c,ensure_ascii=False))
        with self.assertRaises(ValueError): owned.install(self.data,docs/'owned_hints.json')
