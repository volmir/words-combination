"""RIGHTS-02: all decisions/audits here are synthetic TEMPORARY fixtures.

Never create or edit the real owner's approval or human audit in docs/.
"""
import copy
import hashlib
from itertools import product
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from tools import build_owned_hints as owned
from tools.build_game_data import require_release_audit
from tools.owned_hints import sha, targets
from tools.owned_hints_rights import CONFIRMATIONS, PLACEHOLDER, owner_decision


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True) + '\n', encoding='utf-8')


class OwnedHintsRightsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.data = root / 'data'
        docs = root / 'docs'
        self.data.mkdir()
        docs.mkdir()
        self.candidate = docs / 'owned_hints.json'
        self.approval = docs / 'OWN_HINTS_OWNER_APPROVAL.json'
        self.audit = docs / 'OWN_HINTS_MANUAL_AUDIT.json'
        self.index = root / 'index.json'
        words = [''.join(p) for p in list(product('абвг', repeat=3))[:39]]
        tasks = [dict(id=f't3-{i}', type='T3', minLength=3, hintTarget=w) for i, w in enumerate(words[:9])]
        for i, start in enumerate(range(9, 20, 2)):
            tasks.append(dict(id=f't4-{i}', type='T4', targets=words[start:min(start + 2, 20)]))
        tasks.append(dict(id='t6', type='T6', n=4, category='professions', words=words[19:]))
        campaign = dict(levels=[dict(number=1, word='ааабббвввггг', tasks=tasks)])
        write(self.data / 'campaign.json', campaign)
        (self.data / 'words.txt').write_text('\n'.join(words) + '\n', encoding='utf-8')
        roles = targets(campaign)
        entries = [dict(word=w, normalized=w, part_of_speech='noun',
                        hint=f'Условный предмет для синтетической проверки номер {i}.', roles=roles[w],
                        provenance='newly-authored-for-game', license_review='pending-owner-approval',
                        owner_text_approval='pending', owner_license_approval='pending', review_note='Синтетика.')
                   for i, w in enumerate(words)]
        write(self.candidate, dict(schema='owned-game-hints', version=1, entries=entries,
              status='candidate-not-installed', proposed_license='CC0-1.0',
              campaign_sha256=sha(self.data / 'campaign.json'), provenance='newly-authored-for-game',
              license_review='pending-owner-approval',
              authoring_inputs=['word', 'part_of_speech=noun', 'task_type', 'general-linguistic-knowledge']))
        write(self.index, dict(schema='owned-hint-category-index-v1', words_sha256=sha(self.data / 'words.txt'),
              source_database_sha256='1' * 64, source_dump_sha256='2' * 64, license='CC-BY-SA-4.0',
              source='word_metadata:categories-and-observed-forms-only', candidate_sha256=sha(self.candidate),
              screening=dict(old_rows_checked=0, suspicious_matches=[]),
              word_metadata={w: dict(category='professions' if w in words[19:] else 'other',
                                     category_source='ai:synthetic', forms=[], related=[]) for w in words}))
        write(self.data / 'manifest.json', dict(words=39, sha256_words_txt=sha(self.data / 'words.txt'),
              campaign_source=dict(sha256=sha(self.data / 'campaign.json')), source=dict(sha256_input='2' * 64)))
        # Production still uses the one fixed docs/ filename. Patch only the
        # candidate/index roots, so install, publish and release share this fixture.
        for name, path in [('CANDIDATE', self.candidate), ('INDEX', self.index)]:
            context = patch.object(owned, name, path)
            context.start()
            self.addCleanup(context.stop)
        owned.install(self.data)

    def approval_value(self):
        return dict(schema='owned-hints-owner-approval', version=1, candidate_path='docs/owned_hints.json',
                    candidate_sha256=sha(self.candidate), campaign_sha256=sha(self.data / 'campaign.json'),
                    expected_unique_hints=39, expected_role_bindings=40, license='CC0-1.0',
                    decision='approved', approved_on='2026-11-16', **dict.fromkeys(CONFIRMATIONS, True))

    def approve_fixture(self):
        write(self.approval, self.approval_value())
        owned.install(self.data)

    def audit_value(self):
        entries = json.loads(self.candidate.read_bytes())['entries']
        return dict(schema='owned-hints-human-audit', version=1, reviewer='Synthetic fixture reviewer',
                    reviewed_on='2026-11-17', candidate_sha256=sha(self.candidate),
                    campaign_sha256=sha(self.data / 'campaign.json'), database_sha256=sha(self.data / 'definitions.db'),
                    owner_approval_sha256=sha(self.approval), checked=39, bad=0,
                    entries=[dict(word=e['word'], hint_sha256=hashlib.sha256(e['hint'].encode('utf-8')).hexdigest(),
                                  quality='pass', originality='pass', correctness='pass', no_answer_leak='pass')
                             for e in entries])

    def block(self):
        return json.loads((self.data / 'manifest.json').read_bytes())['definitions']

    def assert_release_blocked(self):
        for waiver in (False, True):
            with self.assertRaises(ValueError):
                require_release_audit(self.data, allow_unaudited=waiver)

    def test_missing_and_exact_template_are_pending_byte_identical_and_block_release(self):
        before = {n: sha(self.data / n) for n in ('manifest.json', 'definitions.db')}
        for template in (False, True):
            if template:
                value = self.approval_value()
                value.update(decision='pending', approved_on=None, **dict.fromkeys(CONFIRMATIONS, False))
                write(self.approval, value)
            self.assertIsNone(owner_decision(self.candidate, self.data / 'campaign.json'))
            owned.install(self.data)
            self.assertEqual(before, {n: sha(self.data / n) for n in before})
            self.assertEqual(PLACEHOLDER, self.block()['own_license'])
            self.assertFalse(self.block()['audit']['passed'])
            self.assertFalse(self.block()['rights_review']['passed'])
            self.assertEqual('draft-not-for-public-release', self.block()['publication_status'])
            self.assert_release_blocked()

    def test_invalid_owner_acts_fail_closed_and_install_changes_nothing(self):
        good = self.approval_value()
        cases = [dict(decision='pending'), dict(decision=False), dict(approved=False),
                 dict(candidate_sha256='0' * 64), dict(campaign_sha256='0' * 64),
                 dict(expected_unique_hints=38), dict(expected_role_bindings=39),
                 dict(expected_role_bindings=40.0), dict(version=True), dict(license='MIT'),
                 dict(candidate_path='somewhere/else'), dict(approved_on=None), dict(approved_on=''),
                 dict(approved_on='2026-02-30'), dict(approved_on='2026-1-01'),
                 dict(approved_on='0000-01-01'), dict(approved_on='2026-11-16T00:00:00')]
        cases += [{field: value} for field in CONFIRMATIONS for value in (False, 1, 'true', None)]
        before = {n: sha(self.data / n) for n in ('manifest.json', 'definitions.db')}
        for change in cases:
            with self.subTest(change=change):
                write(self.approval, dict(good, **change))
                with self.assertRaises(ValueError):
                    owned.install(self.data)
                self.assert_release_blocked()
                self.assertEqual(before, {n: sha(self.data / n) for n in before})
        for invalid in ('', '{}', 'null', '[]', '{"decision":"pending","decision":"approved"}'):
            self.approval.write_text(invalid)
            with self.assertRaises(ValueError):
                owned.verify(self.data)
            self.assert_release_blocked()

    def test_owner_approval_alone_cannot_pass_human_audit(self):
        self.approve_fixture()
        block = self.block()
        self.assertEqual('CC0-1.0', block['own_license'])
        self.assertEqual('pending-manual-audit', block['rights_review']['status'])
        self.assertFalse(block['rights_review']['passed'])
        self.assertFalse(block['audit']['passed'])
        self.assert_release_blocked()

    def test_approved_requires_both_evidence_files_rebuilds_deterministically_and_never_edits_candidate(self):
        candidate_bytes = self.candidate.read_bytes()
        with sqlite3.connect(self.data / 'definitions.db') as db:
            pending_rows = db.execute('SELECT * FROM definitions').fetchall()
            pending_lexicon = db.execute('SELECT * FROM word_metadata').fetchall()
            pending_metadata = dict(db.execute('SELECT * FROM build_metadata'))
        self.approve_fixture()
        write(self.audit, self.audit_value())
        owned.install(self.data)
        before = {n: sha(self.data / n) for n in ('definitions.db', 'manifest.json')}
        owned.install(self.data)
        self.assertEqual(before, {n: sha(self.data / n) for n in before})
        self.assertEqual(candidate_bytes, self.candidate.read_bytes())
        self.assertEqual('approved', owned.verify(self.data)['publication_status'])
        self.assertTrue(self.block()['audit']['passed'])
        self.assertTrue(self.block()['rights_review']['passed'])
        self.assertTrue(require_release_audit(self.data)['publishable'])
        with sqlite3.connect(self.data / 'definitions.db') as db:
            self.assertEqual(pending_rows, db.execute('SELECT * FROM definitions').fetchall())
            self.assertEqual(pending_lexicon, db.execute('SELECT * FROM word_metadata').fetchall())
            approved_metadata = dict(db.execute('SELECT * FROM build_metadata'))
            self.assertEqual(dict(pending_metadata, own_license='CC0-1.0',
                                  owner_approval_sha256=sha(self.approval)), approved_metadata)
        for evidence in (self.audit, self.approval):
            original = evidence.read_bytes()
            evidence.unlink()
            with self.assertRaises(ValueError):
                owned.verify(self.data)
            self.assert_release_blocked()
            evidence.write_bytes(original)

    def test_partial_fake_or_stale_human_audit_is_rejected(self):
        self.approve_fixture()
        good = self.audit_value()
        cases = [dict(checked=38), dict(checked=39.0), dict(bad=1), dict(bad=False),
                 dict(database_sha256='0' * 64), dict(candidate_sha256='0' * 64),
                 dict(campaign_sha256='0' * 64), dict(owner_approval_sha256='0' * 64),
                 dict(reviewed_on='2026-02-30'), dict(reviewed_on='2026-11-15'), dict(reviewer='  '),
                 dict(entries=good['entries'][:-1]), dict(passed=True)]
        for field in ('quality', 'originality', 'correctness', 'no_answer_leak', 'hint_sha256', 'word'):
            rows = copy.deepcopy(good['entries'])
            rows[0][field] = 'wrong'
            cases.append(dict(entries=rows))
        for change in cases:
            with self.subTest(change=list(change)):
                write(self.audit, dict(good, **change))
                with self.assertRaises(ValueError):
                    owned.install(self.data)
                self.assert_release_blocked()

    def test_forged_manifest_flags_license_status_and_evidence_are_rejected(self):
        for phase in ('pending', 'approved'):
            if phase == 'approved':
                self.approve_fixture()
                write(self.audit, self.audit_value())
                owned.install(self.data)
            good = json.loads((self.data / 'manifest.json').read_bytes())
            cases = [dict(audit=dict(good['definitions']['audit'], passed=phase != 'approved')),
                     dict(rights_review=dict(good['definitions']['rights_review'], passed=phase != 'approved')),
                     dict(audit=dict(passed=True)), dict(rights_review=dict(passed=True)),
                     dict(publication_status='wrong'), dict(own_license='MIT')]
            if phase == 'pending':
                cases.append(dict(publication_status='approved'))
            for change in cases:
                with self.subTest(phase=phase, change=change):
                    altered = copy.deepcopy(good)
                    altered['definitions'].update(change)
                    write(self.data / 'manifest.json', altered)
                    with self.assertRaises(ValueError):
                        owned.verify(self.data)
                    self.assert_release_blocked()
            write(self.data / 'manifest.json', good)

    def test_changed_candidate_or_campaign_revokes_approval_even_if_index_is_updated(self):
        self.approve_fixture()
        write(self.audit, self.audit_value())
        owned.install(self.data)
        for path in (self.candidate, self.data / 'campaign.json'):
            original = path.read_bytes()
            # Same JSON, different REAL bytes must revoke the old act too.
            path.write_bytes(original + b'\n')
            with self.assertRaises(ValueError):
                owner_decision(self.candidate, self.data / 'campaign.json')
            self.assert_release_blocked()
            path.write_bytes(original)
        candidate = json.loads(self.candidate.read_bytes())
        candidate['entries'][0]['hint'] += ' Изменение.'
        write(self.candidate, candidate)
        index = json.loads(self.index.read_bytes())
        index['candidate_sha256'] = sha(self.candidate)
        write(self.index, index)
        with self.assertRaises(ValueError):
            owned.install(self.data)
        self.assert_release_blocked()

    def test_stale_database_or_extra_wiktionary_row_cannot_pass_even_with_rebound_hashes(self):
        self.approve_fixture()
        write(self.audit, self.audit_value())
        owned.install(self.data)
        original = (self.data / 'definitions.db').read_bytes()
        manifest = json.loads((self.data / 'manifest.json').read_bytes())
        audit = json.loads(self.audit.read_bytes())
        for sql in ["UPDATE definitions SET text='Синтетическая подмена' WHERE id=1",
                    "INSERT INTO definitions(word,display,ord,text,origin,category,guessable) "
                    "VALUES('чужой','чужой',1,'Синтетическая строка','wiktionary','other',0)"]:
            with self.subTest(sql=sql):
                (self.data / 'definitions.db').write_bytes(original)
                with sqlite3.connect(self.data / 'definitions.db') as db:
                    db.execute(sql)
                digest = sha(self.data / 'definitions.db')
                changed_audit = dict(audit, database_sha256=digest)
                write(self.audit, changed_audit)
                altered = copy.deepcopy(manifest)
                altered['definitions']['sha256'] = digest
                altered['definitions']['audit'].update(database_sha256=digest, report_sha256=sha(self.audit))
                write(self.data / 'manifest.json', altered)
                with self.assertRaisesRegex(ValueError, 'reproducible'):
                    owned.verify(self.data)
                self.assert_release_blocked()


if __name__ == '__main__':
    unittest.main()
