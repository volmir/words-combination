"""Owner-approved staging edits: synthetic data, no dictionary article texts."""
from copy import deepcopy
import json
from tools import lexical_json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import apply_short15_balance as balance
import build_campaign
import campaign_profiles


class Short15BalanceTest(unittest.TestCase):
    def test_only_approved_task_fields_change(self):
        profile = campaign_profiles.get('short15-release-v1')
        levels = build_campaign.schedule(build_campaign.load_config(), profile)
        names = json.loads(Path(balance.__file__).with_name('short15_candidate.json').read_text())['words']
        for level, word in zip(levels, names):
            level['word'] = word
            for task in level['tasks']:
                task['marker'] = 'preserved'
                if task['type'] == 'T2':
                    task['letter'] = 'к'
        baseline = dict(version=2, countMinLength=4, stages=campaign_profiles.expected_stages(profile), levels=levels)
        saved = deepcopy(baseline)
        result = balance.campaign_candidate(baseline)
        self.assertEqual(saved, baseline)
        for before, after in zip(baseline['levels'], result['levels']):
            self.assertEqual(before['word'], after['word'])
            self.assertEqual(before['requiredStars'], after['requiredStars'])
            for old, new in zip(before['tasks'], after['tasks']):
                self.assertEqual(old['id'], new['id'])
                self.assertEqual('preserved', new['marker'])
                if before['number'] < 14 and old['type'] not in ('T1', 'T2'):
                    self.assertEqual(old, new)
                if old['type'] == 'T2':
                    self.assertEqual('к', new['letter'])
        self.assertEqual(20, len(result['levels'][13]['tasks'][-1]['words']))
        final = {t['type']: t for t in result['levels'][14]['tasks']}
        self.assertEqual(('антитело', 8), (final['T3']['hintTarget'], final['T3']['minLength']))
        self.assertEqual(('ист', 7), (final['T5']['ending'], final['T5']['minLength']))
        self.assertEqual(('у', 8), (final['T7']['letter'], final['T7']['n']))

    def test_categories_preserve_source_features_and_mark_owner_decisions(self):
        index = {'word_metadata': {w: {'category': 'other', 'category_source': 'unknown:test',
                                      'forms': [w], 'related': []}
                                   for w in balance.PROFESSIONS + ['кот']}}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'baseline.json'
            lexical_json.build(path, [], {'кот':dict(category='animals',category_source='sense.categories:animals')}, {}, 8)
            result = balance.reviewed_categories(index, path)
        self.assertEqual('animals', result['word_metadata']['кот']['category'])
        for word in balance.PROFESSIONS:
            row = result['word_metadata'][word]
            self.assertEqual('professions', row['category'])
            self.assertTrue(row['category_source'].startswith('owner:SHORT15-05'))
            self.assertEqual([word], row['forms'])
        self.assertEqual('other', index['word_metadata']['кот']['category'])
