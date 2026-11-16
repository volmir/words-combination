"""RIGHTS-01: pin the unsigned review packet; never author an owner decision.

Read only the project's own hints, without embedding imported article prose.
"""
from collections import Counter
import hashlib
import json
from pathlib import Path
import unittest

from tools.build_owned_hints import inputs, verify
from tools.owned_hints import norm, targets

ROOT = Path(__file__).resolve().parents[2]
CANDIDATE_SHA = '061633244ec968aeec2e0dc50aca1b61ecb8ca735590c5510938ab5085b711b0'
CAMPAIGN_SHA = '1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e'


class OwnedHintsOwnerReviewTest(unittest.TestCase):
    def test_exact_snapshot_and_all_campaign_bindings(self):
        for path, expected in [('docs/owned_hints.json', CANDIDATE_SHA),
                               ('data/campaign.json', CAMPAIGN_SHA)]:
            self.assertEqual(expected, hashlib.sha256((ROOT / path).read_bytes()).hexdigest())
        candidate, index, _, _ = inputs(ROOT / 'data')
        campaign = json.loads((ROOT / 'data/campaign.json').read_bytes())
        entries = candidate['entries']
        self.assertEqual(39, len(entries))
        self.assertEqual(39, len({norm(e['word']) for e in entries}))
        self.assertEqual(39, len({norm(e['hint']) for e in entries}))
        self.assertEqual(targets(campaign), {e['word']: e['roles'] for e in entries})
        self.assertEqual(Counter(T3=9, T4=11, T6=20),
                         Counter(r['type'] for e in entries for r in e['roles']))
        self.assertEqual(31369, index['screening']['old_rows_checked'])
        self.assertEqual([], index['screening']['suspicious_matches'])
        installed = verify(ROOT / 'data')
        self.assertEqual(39, installed['own'])
        self.assertEqual(0, installed['wiktionary'])

    def test_all_39_review_rows_are_verbatim_and_complete(self):
        candidate = json.loads((ROOT / 'docs/owned_hints.json').read_bytes())
        review = (ROOT / 'docs/OWN_HINTS_OWNER_REVIEW.md').read_text(encoding='utf-8')
        rows = [line for line in review.splitlines()
                if line.startswith('| ') and line.split(' | ')[0][2:].isdigit()]
        self.assertEqual(39, len(rows))
        for number, (entry, row) in enumerate(zip(candidate['entries'], rows), 1):
            with self.subTest(word=entry['word']):
                cells = row[2:-2].split(' | ')
                roles = '; '.join(f"{r['level']} / {r['task_id']} / {r['type']} / {r['selection_source']}"
                                  for r in entry['roles'])
                self.assertEqual([str(number), entry['word'], roles,
                                  entry['hint'], entry['review_note']], cells)
        self.assertEqual(2928, sum(len(e['hint']) for e in candidate['entries']))
        self.assertEqual(99, max(len(e['hint']) for e in candidate['entries']))
        self.assertIn(CANDIDATE_SHA, review)
        self.assertIn(CAMPAIGN_SHA, review)
        self.assertIn('Решение владельца пока НЕ получено', review)
        self.assertIn('READY_FOR_OWNER_REVIEW', review)

    def test_template_is_exact_and_unsigned(self):
        template = json.loads((ROOT / 'docs/OWN_HINTS_OWNER_APPROVAL.template.json').read_bytes())
        self.assertEqual(dict(
            schema='owned-hints-owner-approval', version=1,
            candidate_path='docs/owned_hints.json', candidate_sha256=CANDIDATE_SHA,
            campaign_sha256=CAMPAIGN_SHA, expected_unique_hints=39,
            expected_role_bindings=40, license='CC0-1.0',
            owner_reviewed_all_39=False, owner_confirms_rights=False,
            owner_approves_exact_texts=False, owner_approves_cc0_dedication=False,
            approved_on=None, decision='pending'), template)


if __name__ == '__main__':
    unittest.main()
