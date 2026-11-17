"""Exact evidence validation in temporary copies; real owner records never change."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from tools import build_owned_hints as owned
from tools.owned_hints import sha
from tools.owned_hints_rights import CONFIRMATIONS, owner_decision
from tools.build_game_data import require_release_audit

ROOT=Path(__file__).resolve().parents[2]

def write(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,sort_keys=True)+'\n',encoding='utf8')

class OwnedHintsRightsTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        self.data=Path(temp.name)/'data';self.data.mkdir()
        docs=Path(temp.name)/'docs';docs.mkdir()
        for name in ('words.txt','campaign.json','manifest.json','owned_riddles.json'):
            shutil.copyfile(ROOT/'data'/name,self.data/name)
        for name in ('owned_hints.json','OWN_HINTS_OWNER_APPROVAL.json','OWN_HINTS_MANUAL_AUDIT.json'):
            shutil.copyfile(ROOT/'docs'/name,docs/name)
        self.candidate=docs/'owned_hints.json';self.approval=docs/'OWN_HINTS_OWNER_APPROVAL.json';self.audit=docs/'OWN_HINTS_MANUAL_AUDIT.json'

    def assert_blocked(self):
        before={n:sha(self.data/n) for n in ('manifest.json','owned_riddles.json')}
        with self.assertRaises(ValueError): owned.install(self.data,self.candidate)
        with self.assertRaises(ValueError): owned.verify(self.data,self.candidate)
        self.assertEqual(before,{n:sha(self.data/n) for n in before})

    def test_missing_template_and_approval_alone_never_install(self):
        original=self.approval.read_bytes()
        self.approval.unlink();self.assert_blocked();self.approval.write_bytes(original)
        value=json.loads(original);value.update(decision='pending',approved_on=None,**dict.fromkeys(CONFIRMATIONS,False))
        write(self.approval,value);self.assertIsNone(owner_decision(self.candidate,self.data/'campaign.json'));self.assert_blocked()
        self.approval.write_bytes(original);self.audit.unlink();self.assert_blocked()

    def test_invalid_owner_acts_fail_closed(self):
        good=json.loads(self.approval.read_bytes())
        cases=[dict(decision='pending'),dict(decision=False),dict(approved=False),dict(candidate_sha256='0'*64),dict(campaign_sha256='0'*64),dict(expected_unique_hints=38),dict(expected_role_bindings=39),dict(expected_role_bindings=40.0),dict(version=True),dict(license='MIT'),dict(candidate_path='elsewhere'),dict(approved_on=None),dict(approved_on=''),dict(approved_on='2026-02-30'),dict(approved_on='2026-1-01'),dict(approved_on='0000-01-01'),dict(approved_on='2026-11-16T00:00:00')]
        cases += [{k:v} for k in CONFIRMATIONS for v in (False,1,'true',None)]
        for change in cases:
            with self.subTest(change=change):write(self.approval,dict(good,**change));self.assert_blocked()
        for raw in ('','{}','null','[]','{"decision":"pending","decision":"approved"}'):
            self.approval.write_text(raw);self.assert_blocked()

    def test_partial_fake_or_stale_human_audit_is_rejected(self):
        good=json.loads(self.audit.read_bytes())
        cases=[dict(checked=38),dict(checked=39.0),dict(bad=1),dict(bad=False),dict(database_sha256='0'*64),dict(candidate_sha256='0'*64),dict(campaign_sha256='0'*64),dict(owner_approval_sha256='0'*64),dict(reviewed_on='2026-02-30'),dict(reviewed_on='2026-11-15'),dict(reviewer=' '),dict(entries=good['entries'][:-1]),dict(passed=True)]
        for key in ('quality','originality','correctness','no_answer_leak','hint_sha256','word'):
            rows=copy.deepcopy(good['entries']);rows[0][key]='wrong';cases.append(dict(entries=rows))
        for change in cases:
            with self.subTest(change=list(change)):write(self.audit,dict(good,**change));self.assert_blocked()

    def test_exact_evidence_and_source_bytes_are_immutable_and_rebuild_identical(self):
        evidence={p:p.read_bytes() for p in (self.candidate,self.approval,self.audit,self.data/'campaign.json')}
        before={n:sha(self.data/n) for n in ('manifest.json','owned_riddles.json')}
        owned.install(self.data,self.candidate);owned.install(self.data,self.candidate)
        self.assertEqual(before,{n:sha(self.data/n) for n in before})
        self.assertEqual(evidence,{p:p.read_bytes() for p in evidence})
        self.assertEqual('approved',owned.verify(self.data,self.candidate)['publication_status'])
        self.assertTrue(require_release_audit(self.data)['publishable'])

    def test_forged_manifest_cannot_substitute_for_evidence(self):
        good=json.loads((self.data/'manifest.json').read_bytes())
        cases=[dict(audit=dict(passed=True)),dict(rights_review=dict(passed=True)),dict(publication_status='wrong'),dict(own_license='MIT'),dict(sha256='0'*64)]
        for change in cases:
            m=copy.deepcopy(good);m['owned_riddles'].update(change);write(self.data/'manifest.json',m)
            with self.assertRaises(ValueError):owned.verify(self.data,self.candidate)
            with self.assertRaises(ValueError):require_release_audit(self.data,allow_unaudited=True)

    def test_changed_candidate_or_campaign_revokes_approval(self):
        for path in (self.candidate,self.data/'campaign.json'):
            raw=path.read_bytes();path.write_bytes(raw+b'\n')
            with self.assertRaises(ValueError):owner_decision(self.candidate,self.data/'campaign.json')
            self.assert_blocked();path.write_bytes(raw)
