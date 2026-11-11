"""GEN-05 uses synthetic words/metadata only; no dictionary article fixtures."""
from copy import deepcopy
import json
from pathlib import Path
import random
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import simulate_campaign as sim
import rebalance_campaign as balance


class SimulationTest(unittest.TestCase):
    def setUp(self):
        self.config = sim.load_config()
        self.words = ['кот', 'ток', 'нота', 'танк', 'канат', 'такса', 'такт', 'константа']
        self.metadata = {w: dict(labels=[], observed_frequency=dict(frequency=(i+1)*10)) for i, w in enumerate(self.words)}
        self.policy = deepcopy(self.config['player'])

    def model(self, tasks=None, r=1, stage=1, hints=None, words=None):
        tasks = tasks or [dict(id='a', type='T1', n=2)]
        return sim.LevelModel(dict(number=1, word='синтетика', stage=stage, requiredStars=r, tasks=tasks),
                              words or self.words, self.metadata, set(self.words), hints or {})

    def test_deterministic_and_independent_of_answer_iteration_order(self):
        a = sim.trial(self.model(), lambda _: .45, self.policy, 123)
        b = sim.trial(self.model(words=list(reversed(self.words))), lambda _: .45, self.policy, 123)
        self.assertEqual(a, b)
        self.assertEqual(sim.simulate(self.model(), lambda _: .45, self.config),
                         sim.simulate(self.model(), lambda _: .45, self.config))

    def test_calibration_global_mean_and_monotonic_frequency(self):
        k = sim.Knowledge(self.metadata, self.words, self.config['knowledge'])
        self.assertAlmostEqual(.45, sum(k(w) for w in self.words)/len(self.words), places=12)
        values = [k.at_frequency(f) for f in (0, 1, 10, 100, 10000, 100000000)]
        self.assertEqual(sorted(values), values)
        self.assertGreater(values[0], .05)
        self.assertLess(values[-1], .95)
        self.assertEqual(k.pool_sha256, sim.Knowledge(self.metadata, reversed(self.words), self.config['knowledge']).pool_sha256)
        with self.assertRaises(ValueError):
            sim.Knowledge(self.metadata, [], self.config['knowledge'])

    def test_once_sampled_knowledge_reused_for_all_tasks(self):
        calls = []
        model = self.model([dict(id='a', type='T1', n=2), dict(id='b', type='T7', letter='т', n=2)])
        def probability(w):
            calls.append(w)
            return .45
        sim.trial(model, probability, self.policy, 2)
        self.assertEqual(model.answers, calls)

    def test_no_knowledge_no_hints_no_stars(self):
        result = sim.trial(self.model(), lambda _: 0, self.policy, 1, use_hints=False)
        self.assertEqual((0, 0, []), (result['stars'], result['attempts'], result['found']))

    def test_full_knowledge_no_hints_all_stars(self):
        result = sim.trial(self.model(), lambda _: 1, self.policy, 1, use_hints=False)
        self.assertEqual(1, result['stars'])
        self.assertEqual(0, result['paid'])

    def test_known_words_still_require_input_budget(self):
        self.policy['input_attempts'] = 0
        result = sim.trial(self.model(), lambda _: 1, self.policy, 1)
        self.assertEqual(0, result['stars'])
        self.assertEqual([], result['found'])

    def test_search_and_input_budgets_finite(self):
        self.policy.update(search_words=2, input_attempts=1, hint_requests=100)
        result = sim.trial(self.model(), lambda _: 1, self.policy, 1)
        self.assertLessEqual(result['search_words'], 2)
        self.assertEqual(1, result['attempts'])
        self.assertEqual(0, result['stars'])

    def test_overlap_one_answer_completes_multiple_tasks_once(self):
        tasks = [dict(id='a', type='T1', n=1), dict(id='b', type='T2', n=1, letter='т'),
                 dict(id='c', type='T7', n=1, letter='т')]
        model = self.model(tasks)
        self.assertEqual({'a', 'b', 'c'}, model.complete({'такт'}))
        self.assertEqual({'a', 'b', 'c'}, model.complete({'такт', 'танк'}))
        self.assertEqual({'c'}, model.complete({'кот'}))

    def test_only_r_is_not_m(self):
        model = self.model([dict(id='a', type='T1', n=1), dict(id='b', type='T4', targets=['константа'])], r=1)
        self.policy['hint_requests'] = 0
        result = sim.trial(model, lambda w: float(w == 'нота'), self.policy, 1)
        self.assertEqual(1, result['stars'])
        c = deepcopy(self.config)
        c['player'] = self.policy
        row = sim.simulate(model, lambda w: float(w == 'нота'), c)
        self.assertEqual(1, row['p_required']['probability'])
        self.assertEqual(0, row['p_master']['probability'])

    def test_first_level_m_equals_r_rates_identical(self):
        result = sim.simulate(self.model(), lambda _: .45, self.config)
        self.assertEqual(result['p_required'], result['p_master'])
        self.assertEqual(500, result['runs'])

    def test_three_letters_and_t3_alternative_and_t5_joint_predicate(self):
        self.assertFalse(sim.accepts(dict(type='T1'), 'кот'))
        self.assertFalse(sim.accepts(dict(type='T2', letter='к'), 'кот'))
        self.assertTrue(sim.accepts(dict(type='T3', minLength=5, hintTarget='канат'), 'такса'))
        self.assertTrue(sim.accepts(dict(type='T5', minLength=3, ending='от'), 'кот'))
        self.assertFalse(sim.accepts(dict(type='T5', minLength=4, ending='от'), 'кот'))
        self.assertTrue(sim.accepts(dict(type='T6', words=['кот']), 'кот'))

    def test_t4_needs_all_fixed_targets_t7_counts_words(self):
        model = self.model([dict(id='a', type='T4', targets=['кот', 'ток']), dict(id='b', type='T7', n=2, letter='т')])
        self.assertEqual(set(), model.complete({'такт'}))
        self.assertEqual({'b'}, model.complete({'кот', 'такт'}))
        self.assertEqual({'a', 'b'}, model.complete({'кот', 'ток'}))

    def test_target_priority_quality_t3_fixed_target_and_exclusion(self):
        model = self.model([dict(id='a', type='T3', minLength=5, hintTarget='канат'),
                            dict(id='b', type='T4', targets=['кот'])])
        self.assertEqual('кот', model.target(set(), random.Random(1))[0])
        self.assertEqual('канат', model.target({'кот'}, random.Random(1))[0])
        model.quality['кот'] = 0
        self.assertEqual('кот', model.target(set(), random.Random(1))[0])
        forbidden = self.model(words=['праймериз'], tasks=[dict(id='a', type='T1', n=2)])
        self.assertIsNone(forbidden.target(set(), random.Random(1))[0])

    def test_hint_ladder_known_positions_and_t7_priority(self):
        t2 = sim.ladder('канат', dict(type='T2', letter='к'), {}, random.Random(1))
        self.assertNotIn('FIRST_LETTER', [s for s, _ in t2])
        self.assertEqual(('LENGTH', frozenset({0})), t2[0])
        t5 = sim.ladder('константа', dict(type='T5', ending='анта'), {}, random.Random(1))
        self.assertFalse(any(s == 'PATTERN' for s, _ in t5))
        self.assertEqual(frozenset({5, 6, 7, 8}), t5[0][1])
        t7 = sim.ladder('такт', dict(type='T7', letter='т'), {}, random.Random(1))
        self.assertEqual(frozenset({0, 3}), next(p for s, p in t7 if s == 'PATTERN'))

    def test_hint_ladder_skips_same_theme_and_duplicates(self):
        data = dict(theme='Синтетическая тема', definition='Синтетическая тема', category='synthetic')
        stages = [s for s, _ in sim.ladder('канат', dict(type='T6', category='synthetic'), data, random.Random(1))]
        self.assertNotIn('THEME', stages)
        self.assertNotIn('DEFINITION', stages)

    def test_theme_protection(self):
        self.assertIsNone(sim.safe_theme('кот', 'Коты'))
        self.assertIsNone(sim.safe_theme('кот', 'К о т'))
        self.assertIsNone(sim.safe_theme('канат', 'Синтетика', related=['синтетик']))
        self.assertEqual('Дом и быт', sim.safe_theme('канат', 'Дом и быт'))

    def test_ladder_text_dedup_uses_core_alphabet_normalization(self):
        steps = sim.ladder('канат', {}, dict(theme='Дом и быт', definition='Дом, и быт!'), random.Random(1))
        self.assertEqual(['THEME', 'LENGTH', 'FIRST_LETTER', 'PATTERN', 'ANSWER'], [s for s, _ in steps])

    def test_budget_all_stages_and_earned_each_four_unique_inputs(self):
        self.policy['hint_requests'] = 0
        for stage, initial in enumerate([5, 5, 4, 4, 3, 3], 1):
            model = self.model(tasks=[dict(id='a', type='T1', n=100)], stage=stage)
            result = sim.trial(model, lambda _: 1, self.policy, 1)
            self.assertEqual(initial, result['initial_budget'])
            self.assertEqual(len(self.words)//4, result['earned'])

    def test_free_t4_steps_and_answer_requires_explicit_input(self):
        self.policy.update(search_words=0, hint_requests=100)
        self.policy['guess_success'] = {s: float(s == 'ANSWER') for s in self.policy['guess_success']}
        model = self.model([dict(id='a', type='T4', targets=['кот'])], hints={'кот': dict(theme='Тема', definition='Подсказка')})
        result = sim.trial(model, lambda _: 0, self.policy, 1)
        self.assertEqual((5, 2, 5, 1, 1), (result['hint_requests'], result['paid'], result['attempts'], result['answer_shown'], result['stars']))
        self.assertEqual(['кот'], result['found'])
        self.policy['input_attempts'] = 4
        result = sim.trial(model, lambda _: 0, self.policy, 1)
        self.assertEqual(0, result['stars'])
        self.assertEqual([], result['found'])
        self.assertEqual(0, result['answer_shown'])

    def test_exhausted_hints_cannot_reveal_unknown_long_word(self):
        self.policy.update(search_words=0, hint_requests=100)
        self.policy['guess_success'] = {s: float(s == 'ANSWER') for s in self.policy['guess_success']}
        model = self.model([dict(id='a', type='T3', minLength=9, hintTarget='константа')], stage=6,
                           hints={'константа': dict(theme='Тема', definition='Подсказка')})
        result = sim.trial(model, lambda _: 0, self.policy, 1)
        self.assertEqual((0, 3, 0), (result['stars'], result['paid'], result['remaining']))
        self.assertEqual([], result['found'])

    def test_hint_budget_shared_between_targets_and_free_steps_at_zero(self):
        self.policy.update(search_words=0, hint_requests=100)
        self.policy['guess_success'] = {s: float(s == 'ANSWER') for s in self.policy['guess_success']}
        model = self.model([dict(id='a', type='T4', targets=['кот', 'ток'])], stage=6)
        result = sim.trial(model, lambda _: 0, self.policy, 1)
        self.assertEqual((0, 3, 0, 5, 1), (result['stars'], result['paid'], result['remaining'],
                                         result['hint_requests'], len(result['found'])))

    def test_model_predicates_match_independent_gen04_vectors(self):
        import validate_campaign as independent
        tasks = [dict(type='T1', n=1), dict(type='T2', n=1, letter='т'),
                 dict(type='T3', minLength=5, hintTarget='канат'), dict(type='T4', targets=['кот', 'ток']),
                 dict(type='T5', minLength=4, ending='ат'), dict(type='T6', words=['кот', 'нота'], category='synthetic'),
                 dict(type='T7', n=1, letter='т')]
        category = lambda w: 'synthetic' if w in ('кот', 'нота') else None
        for task in tasks:
            self.assertEqual(independent.task_answers(task, set(self.words), category),
                             {w for w in self.words if sim.accepts(task, w)})

    def test_master_ceiling_cannot_contradict_r_on_warmup(self):
        config = deepcopy(self.config)
        config['targets'][3]['master_max'] = .1
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'config.json'
            path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):
                sim.load_config(path)

    def test_wilson_extremes_nonzero_uncertainty_and_point_comparison(self):
        self.assertGreater(sim.estimate(0, 500)['wilson95'][1], 0)
        self.assertLess(sim.estimate(500, 500)['wilson95'][0], 1)
        row = dict(p_required=sim.estimate(475, 500), p_master=sim.estimate(350, 500))
        self.assertEqual([], sim.deviations(row, dict(required_min=.95, master_min=.70, master_max=.70)))

    def test_invalid_config_cannot_lower_run_count_or_endpoint(self):
        for key, value in [('runs', 499), ('comparison', 'always_pass')]:
            config = deepcopy(self.config)
            config[key] = value
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp)/'config.json'
                path.write_text(json.dumps(config))
                with self.assertRaises(ValueError):
                    sim.load_config(path)


class RebalanceTest(unittest.TestCase):
    def setUp(self):
        self.config = sim.load_config()
        self.config['rebalance']['max_candidates'] = 3

    def audit(self, good=True):
        return dict(installable=good, jvm_checked=good, unreachable=[], balance_deviations=[], parity_deviations=[])

    def row(self, fail=True):
        return dict(level=1, deviations=[dict(gap=.1, rule='min')] if fail else [])

    def test_each_candidate_revalidates_and_finite_budget_is_not_success(self):
        seen = []
        def evaluate(c, i):
            seen.append(c['value'])
            return [self.row()], self.audit()
        def alternatives(c, rows, i):
            yield dict(value=i+1), dict(kind='synthetic')
        result = balance.search(dict(value=0), self.config, evaluate, alternatives)
        self.assertEqual([0, 1, 2], seen)
        self.assertEqual('exhausted', result['status'])
        self.assertFalse(result['installable'])
        self.assertEqual('candidate_budget', result['exhaustion'])

    def test_no_model_success_bypasses_gen04_and_no_repeated_candidate(self):
        calls = []
        def evaluate(c, i):
            calls.append(i)
            return [self.row(False)], self.audit(False)
        result = balance.search(dict(value=0), self.config, evaluate,
                                lambda c, rows, i: [(c, dict(kind='duplicate'))])
        self.assertEqual([0], calls)
        self.assertFalse(result['installable'])
        self.assertEqual('admissible_alternatives', result['exhaustion'])

    def test_valid_balanced_candidate_stops_search(self):
        def evaluate(c, i):
            return [self.row(i == 0)], self.audit()
        result = balance.search(dict(value=0), self.config, evaluate,
                                lambda c, rows, i: [(dict(value=1), dict(kind='synthetic'))])
        self.assertTrue(result['installable'])
        self.assertEqual(2, len(result['attempts']))
        self.assertEqual(1, result['selected_attempt'])

    def test_stale_success_copy_is_removed_on_invalid_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            copy = output / 'balanced_campaign.json'
            copy.write_text('{"stale":true}')
            argv = ['rebalance_campaign.py', '--output', str(output), '--profiles', str(output/'missing.json.gz')]
            with patch.object(sys, 'argv', argv), self.assertRaises(SystemExit) as error:
                balance.main()
            self.assertEqual(2, error.exception.code)
            self.assertFalse(copy.exists())
            self.assertEqual('input_error', json.loads((output/'simulation_report.json').read_text())['status'])


if __name__ == '__main__':
    unittest.main()
