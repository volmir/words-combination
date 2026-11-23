"""Runtime validation uses synthetic vocabulary and authored placeholder text only."""
from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_game_vocabulary as vocabulary
import validate_campaign as validator


class InstalledValidationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.words = sorted(['карта', 'катер', 'крот', 'тара', 'тора', 'кара'])
        self.campaign = dict(version=2, countMinLength=4, levels=[dict(
            number=1, word='картотека', tasks=[dict(id='t1', type='T1', n=1),
                dict(id='t4', type='T4', targets=['карта']),
                dict(id='t6', type='T6', category='synthetic', n=1, words=self.words)])])
        self.args = SimpleNamespace(campaign=self.root / 'campaign.json', words=self.root / 'words.txt',
                                    manifest=self.root / 'manifest.json', profile='short15-release-v1')
        self.args.words.write_text('\n'.join(self.words) + '\n', encoding='utf-8')
        self.write_json(self.args.campaign, self.campaign)
        self.riddles = self.root / 'owned_riddles.json'
        self.write_json(self.riddles, dict(riddles={'карта': 'Авторский синтетический пример.'}))
        self.refresh_manifest()

    def write_json(self, path, value):
        path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

    def refresh_manifest(self):
        self.write_json(self.args.manifest, dict(words=len(self.words),
            sha256_words_txt=validator.file_hash(self.args.words),
            campaign_source=dict(sha256=validator.file_hash(self.args.campaign)),
            owned_riddles=dict(sha256=validator.file_hash(self.riddles))))

    def jvm_result(self):
        answers = vocabulary.answers_for('картотека', set(self.words))
        return dict(errors=[], r_combinations_checked=3, all_answer_stars=3, finale_reached=True,
                    levels=[dict(number=1, answers=sorted(answers), tasks=[dict(
                        id=t['id'], accepted=sorted(vocabulary.task_accepted(t, answers, 4)),
                        target=vocabulary.task_needed(t)) for t in self.campaign['levels'][0]['tasks']])])

    def run_validation(self, jvm=None, returncode=0):
        def run(command, **kwargs):
            # JVM execution itself is covered by the real installed-data integration run.
            import shlex
            paths = shlex.split(command[-1].removeprefix('--args='))
            if not returncode:
                self.write_json(Path(paths[1]), self.jvm_result() if jvm is None else jvm)
            return SimpleNamespace(returncode=returncode, stdout='', stderr='synthetic failure')
        with patch.object(validator.subprocess, 'run', side_effect=run):
            return validator.validate_installed(self.args)[1]

    def test_parity_and_runtime_reserves_pass(self):
        report = self.run_validation()
        self.assertTrue(report['installable'])
        self.assertTrue(report['jvm_checked'])
        self.assertEqual(report['levels'][0]['tasks'][2]['reserve_required'], 5)

    def test_lost_reserve_is_rejected(self):
        self.campaign['levels'][0]['tasks'][0]['n'] = 6
        self.write_json(self.args.campaign, self.campaign)
        self.refresh_manifest()
        report = self.run_validation()
        self.assertFalse(report['installable'])
        self.assertEqual(report['unreachable'][0]['code'], 'runtime_reserve')

    def test_missing_t4_text_is_rejected(self):
        self.write_json(self.riddles, dict(riddles={}))
        self.refresh_manifest()
        self.assertFalse(self.run_validation()['installable'])

    def test_dictionary_checksum_mismatch_is_rejected(self):
        self.args.words.write_text('карта\n', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.run_validation()

    def test_jvm_disagreement_is_rejected(self):
        jvm = deepcopy(self.jvm_result())
        jvm['levels'][0]['tasks'][0]['accepted'] = []
        self.assertFalse(self.run_validation(jvm)['installable'])

    def test_missing_jvm_task_is_rejected(self):
        jvm = deepcopy(self.jvm_result())
        jvm['levels'][0]['tasks'].pop()
        self.assertFalse(self.run_validation(jvm)['installable'])

    def test_jvm_unavailable_is_rejected(self):
        report = self.run_validation(returncode=1)
        self.assertFalse(report['installable'])
        self.assertFalse(report['jvm_checked'])
