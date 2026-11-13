"""Independent GEN-04 checks on synthetic words and definitions only."""
from copy import deepcopy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_campaign as audit


def fixture():
    words = ['кот', 'ток', 'рак', 'карта', 'катер', 'тетка', 'крот', 'тара', 'тора', 'кара',
             'токток', 'тетет', 'картотека', 'картотекаа']
    tasks = [dict(id='count', type='T1', n=2), dict(id='initial', type='T2', n=1, letter='к'),
             dict(id='long', type='T3', minLength=5, hintTarget='катер'),
             dict(id='riddle', type='T4', targets=['карта']),
             dict(id='ending', type='T5', minLength=4, ending='ра')]
    campaign = dict(version=2, countMinLength=4,
                    stages=[dict(number=1, firstLevel=1, lastLevel=2)], levels=[
                        dict(number=1, stage=1, word='картотека', requiredStars=2, tasks=tasks),
                        dict(number=2, stage=1, word='картотекаа', requiredStars=1, tasks=[
                            dict(id='theme', type='T6', category='objects', n=1, words=[]),
                            dict(id='letter', type='T7', letter='р', n=2)])])
    payload = dict(words={w: dict(noun=True, labels=[], category='objects',
                         category_source='rules:synthetic', observed_frequency=dict(frequency=100)) for w in words},
                   assessments=[dict(word=w, role=r, assessment=dict(status='suitable'))
                                for w in words for r in ('source', 'target')], profiles={})
    hints = {w: dict(forms=[w], related=[], definitions=['Предмет для хранения небольших вещей.']) for w in words}
    hints['карта'] = dict(forms=['карта', 'карты'], related=['карточный'],
                         definitions=['Карта — карточный предмет для хранения небольших вещей.'])
    campaign['levels'][1]['tasks'][0]['words'] = sorted(audit.answers_for('картотекаа', words))
    return campaign, words, payload, hints


class IndependentCampaignValidationTest(unittest.TestCase):
    def setUp(self):
        self.campaign, self.words, self.payload, self.hints = fixture()

    def run_audit(self, production=False):
        return audit.validate(self.campaign, self.words, self.payload, self.hints,
                              previous=['картотека'], production=production)

    def assert_issue(self, report, code, level=None, task=None):
        rows = report['unreachable'] + report['balance_deviations'] + report['parity_deviations']
        self.assertTrue(any(r['code'] == code and (level is None or r['level'] == level)
                            and (task is None or r['task'] == task) and r['reason'] for r in rows), rows)
        self.assertFalse(report['installable'])

    def test_valid_and_ignores_generator_flags_and_pools(self):
        self.payload['profiles'] = {'картотека': dict(source_eligible=False, answers=[],
                                    recognizable_answers=[], assigned_targets=[], riddle_targets=[])}
        self.campaign['installable'] = False
        report = self.run_audit()
        self.assertTrue(report['installable'], report)
        self.assertEqual(12, report['r_combinations_checked'])
        self.assertNotIn('картотека', report['levels'][0]['answers'])

    def test_shared_jvm_fixture_and_masked_t4_definition(self):
        vectors = json.loads((Path(audit.__file__).parent / 'campaign_validation_vectors.json').read_text())
        self.assertEqual(self.campaign, vectors['campaign'])
        self.assertEqual(self.words, vectors['dictionary'])
        self.assertEqual(self.hints, vectors['hintInputs'])
        hint = audit.prepared_hint('карта', self.hints['карта'])
        self.assertEqual('*** — *** предмет для хранения небольших вещей.', hint)

    def test_jaccard_uses_recognizable_answers_without_generator_metrics(self):
        self.payload['assessments'] = [r for r in self.payload['assessments'] if r['word'] != 'картотека']
        result = self.run_audit()
        self.assertEqual(1.0, result['jaccard']['mean'])
        self.assertEqual(10, result['jaccard']['pairs'][0]['union'])

    def test_t7_does_not_count_repeated_letter_occurrences_as_words(self):
        task = dict(id='repeat', type='T7', n=3, letter='т')
        accepted = audit.task_answers(task, {'тетка'}, lambda _: None)
        self.assertEqual({'тетка'}, accepted)
        self.assertLess(len(accepted), task['n'])

    def test_repeated_letters_and_three_letter_counter_boundary(self):
        report = self.run_audit()
        answers = report['levels'][0]['answers']
        self.assertNotIn('токток', answers)
        self.assertNotIn('тетет', answers)
        self.assertIn('кот', answers)
        tasks = {t['id']: t for t in report['levels'][0]['tasks']}
        self.assertNotIn('кот', tasks['count']['accepted'])
        self.assertNotIn('кот', tasks['initial']['accepted'])
        self.assertIn('рак', report['levels'][1]['tasks'][1]['accepted'])
        self.assertEqual(['кара', 'тара', 'тора'], tasks['ending']['accepted'])
        self.assertIn('тетка', tasks['long']['accepted'])

    def test_individual_unreachable_even_when_r_remains_possible(self):
        self.campaign['levels'][0]['tasks'][1]['letter'] = 'я'
        report = self.run_audit()
        self.assert_issue(report, 'unreachable_task', 1, 'initial')
        self.assert_issue(report, 'letter', 1, 'initial')

    def test_missing_and_invalid_parameters_keep_task_address(self):
        del self.campaign['levels'][0]['tasks'][1]['letter']
        self.assert_issue(self.run_audit(), 'task_schema', 1, 'initial')
        self.setUp()
        self.campaign['levels'][0]['tasks'][3]['targets'] = 'карта'
        self.assert_issue(self.run_audit(), 'task_schema', 1, 'riddle')

    def test_not_dictionary_target_and_overused_letters(self):
        for target in ('токток', 'абракадабра', 'картотека'):
            with self.subTest(target=target):
                self.campaign['levels'][0]['tasks'][3]['targets'] = [target]
                self.assert_issue(self.run_audit(), 'assigned_target', 1, 'riddle')

    def test_duplicate_normalized_targets_types_ids_categories_and_endings(self):
        for key, changed, code in [
            ('targets', ['катёр'], 'unique_target'),
            ('id', 'long', 'task_id'), ('type', 'T3', 'task_types')]:
            with self.subTest(key=key):
                self.setUp()
                self.campaign['levels'][0]['tasks'][3][key] = changed
                if key == 'type':
                    self.campaign['levels'][0]['tasks'][3].update(minLength=5, hintTarget='карта')
                self.assert_issue(self.run_audit(), code, 1, 'long' if key == 'id' else None)
        for kind, params, code in [('T5', dict(ending='ра', minLength=4), 'unique_ending'),
                                    ('T6', dict(category='objects', n=1, words=[]), 'unique_category')]:
            self.setUp()
            self.campaign['levels'][1]['tasks'].append(dict(id='duplicate', type=kind, **params))
            self.campaign['levels'][0]['tasks'][4] = dict(id='first', type=kind, **params)
            self.assert_issue(self.run_audit(), code, 2, 'duplicate')

    def test_full_category_membership_and_unknown_category(self):
        self.campaign['levels'][1]['tasks'][0]['words'].pop()
        self.assert_issue(self.run_audit(), 'category_membership', 2, 'theme')
        self.campaign['levels'][1]['tasks'][0]['category'] = 'other'
        self.assert_issue(self.run_audit(), 'category', 2, 'theme')

    def test_bad_riddle_definition_not_useful_flag(self):
        self.payload['hints'] = {'карта': dict(useful=True)}
        self.hints['карта']['definitions'] = ['карта']
        self.assert_issue(self.run_audit(), 'riddle_hint', 1, 'riddle')

    def test_t3_hint_target_requires_useful_definition(self):
        self.hints['катер']['definitions'] = ['катер']
        self.assert_issue(self.run_audit(), 'hint_target', 1, 'long')

    def test_reserve_shortage_separate_from_reachability(self):
        self.payload['assessments'] = [r for r in self.payload['assessments'] if r['word'] != 'кара']
        report = self.run_audit()
        self.assert_issue(report, 'recognizable_reserve', 1, 'ending')
        self.assertFalse(any(r['task'] == 'ending' for r in report['unreachable']))

    def test_forbidden_source_target_and_hard_labels(self):
        self.campaign['levels'][0]['word'] = 'праймериз'
        self.assert_issue(self.run_audit(), 'source', 1)
        self.setUp()
        self.campaign['levels'][0]['tasks'][2]['hintTarget'] = 'праймериз'
        self.assert_issue(self.run_audit(), 'assigned_target', 1, 'long')
        self.setUp()
        self.payload['words']['карта']['labels'] = ['productive_diminutive']
        self.assert_issue(self.run_audit(), 'assigned_target', 1, 'riddle')

    def test_production_size_schedule_bounds_alphabet_and_retention(self):
        self.campaign['levels'][0]['requiredStars'] = 4
        self.campaign['levels'][0]['tasks'][0]['n'] = 23
        report = self.run_audit(production=True)
        for code in ('campaign_size', 'schedule', 'parameter_curve', 'alphabet', 'retained_sources', 'answer_band'):
            self.assert_issue(report, code)

    def test_cli_invalid_input_fails_and_removes_stale_validated_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / 'validated_campaign.json').write_text('stale')
            candidate = directory / 'bad.json'
            candidate.write_text('{broken')
            result = subprocess.run([sys.executable, str(Path(audit.__file__)), '--campaign', str(candidate),
                                     '--output', tmp], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((directory / 'validated_campaign.json').exists())
            self.assertFalse(json.loads((directory / 'validation_report.json').read_text())['installable'])

    def test_cli_corrupt_candidate_never_emits_validated_artifact(self):
        self.campaign['levels'][0]['tasks'][1]['letter'] = 'я'
        report = self.run_audit()
        report['jvm_checked'] = True
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'validated_campaign.json'
            target.write_text('old success')
            with patch.object(sys, 'argv', ['validate_campaign.py', '--output', tmp]), \
                    patch.object(audit, 'run', return_value=(self.campaign, report)), redirect_stdout(io.StringIO()):
                self.assertEqual(2, audit.main())
            self.assertFalse(target.exists())

    def test_python_only_success_cannot_emit_validated_artifact(self):
        report = self.run_audit()
        self.assertTrue(report['installable'])
        self.assertFalse(report['jvm_checked'])
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(sys, 'argv', ['validate_campaign.py', '--output', tmp]), \
                    patch.object(audit, 'run', return_value=(self.campaign, report)), redirect_stdout(io.StringIO()):
                self.assertEqual(2, audit.main())
            self.assertFalse((Path(tmp) / 'validated_campaign.json').exists())

    def test_runtime_is_mandatory_and_bad_per_word_counts_are_fatal(self):
        report = self.run_audit()
        jvm = dict(alphabet=audit.ALPHABET, levels=deepcopy(report['levels']), hints={})
        for level in jvm['levels']:
            for task in level['tasks']:
                task.update(target=task['needed'], singleton_progress=dict.fromkeys(task['accepted'], 1))
        jvm['levels'][1]['tasks'][1]['singleton_progress']['тара'] = 2
        audit.compare_jvm(report, jvm, {})
        self.assert_issue(report, 'jvm_progress', 2, 'letter')

    def test_hint_mismatch_keeps_t4_level_and_task_address(self):
        report = self.run_audit()
        report['levels'][0]['tasks'][3]['riddle_comparison_words'] = ['карта']
        jvm = dict(alphabet=audit.ALPHABET, levels=deepcopy(report['levels']), hints={'карта': None})
        audit.compare_jvm(report, jvm, {'карта': self.hints['карта']})
        self.assert_issue(report, 'jvm_hint', 1, 'riddle')

    def test_kotlin_mismatch_is_fatal_with_task_address(self):
        report = self.run_audit()
        jvm = dict(alphabet=audit.ALPHABET, levels=deepcopy(report['levels']), hints={w: audit.prepared_hint(w, data)
                                                          for w, data in self.hints.items()})
        for level in jvm['levels']:
            for task in level['tasks']:
                task.update(target=task['needed'], singleton_progress=dict.fromkeys(task['accepted'], 1))
        jvm['levels'][0]['tasks'][0]['accepted'].append('кот')
        audit.compare_jvm(report, jvm, self.hints)
        self.assert_issue(report, 'jvm_task_answers', 1, 'count')


if __name__ == '__main__':
    unittest.main()
