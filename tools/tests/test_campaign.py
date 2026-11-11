"""Synthetic campaign fixtures, with no dictionary articles or network dependencies."""
from copy import deepcopy
from itertools import product
import json
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import build_campaign as campaign
from game_data_providers import json_digest


class CampaignTest(unittest.TestCase):
    def setUp(self):
        self.config = campaign.load_config()
        # Artificial nouns: enough different recognizable words and three global themes.
        self.words = [''.join(chars) + ending for ending in ('ник', 'ние', 'ость')
                      for chars in list(product('абвг', repeat=5))[::20][:50]]
        self.words += ['кот', 'ток', *campaign.ROLE_EXCLUSIONS]
        self.source = campaign.selection.read_alphabet() * 10
        self.metadata = {w: dict(category=['plants', 'birds', 'home'][min(i // 50, 2)],
                                category_source='rules:synthetic',
                                observed_frequency=dict(frequency=100)) for i, w in enumerate(self.words)}
        self.payload = dict(words=self.metadata,
            assessments=[dict(word=w, role='target', assessment=dict(status='suitable')) for w in self.words],
            hints={w: dict(useful=True, sha256=json_digest(['synthetic-hint', w])) for w in self.words},
            profiles={self.source: dict(assigned_targets=self.words, riddle_targets=self.words)})

    def builder(self, plans=None, words=None):
        b = campaign.CampaignBuilder(self.payload, words if words is not None else self.words, self.config)
        if plans is not None:
            b.plan = [dict(number=i, stage=1, requiredStars=1,
                           tasks=[dict(id=f'l{i:02d}-{t.lower()}', type=t, introduce=i == 1, **params)
                                  for t, params in tasks]) for i, tasks in enumerate(plans, 1)]
        return b

    def one(self, t, **params):
        b = self.builder([[(t, params)]])
        result, report = b.attempt([self.source])
        self.assertIsNotNone(result, report)
        return b, result['levels'][0]['tasks'][0], report['tasks'][0]

    def test_schedule_rotation_stars_and_introductions(self):
        plan = campaign.schedule(self.config)
        self.assertEqual(100, sum(len(p['tasks']) for p in plan))
        self.assertEqual(self.config['master_types'], [[t['type'] for t in p['tasks']] for p in plan[25:]])
        self.assertEqual([1]*10 + [2]*10 + [3]*9 + [4], [p['requiredStars'] for p in plan])
        intro = {t['type']: p['number'] for p in plan for t in p['tasks'] if t['introduce']}
        self.assertEqual(dict(T1=1, T2=6, T3=11, T4=16, T5=21, T6=26, T7=27), intro)
        for p in plan:
            self.assertEqual(len(p['tasks']), len({t['type'] for t in p['tasks']}))

    def test_curves_are_monotone_by_appearance_and_hit_end_only_on_last(self):
        plan = campaign.schedule(self.config)
        for t, (start, end) in campaign.CURVES.items():
            values = [task.get('n', task.get('minLength')) for p in plan for task in p['tasks'] if task['type'] == t]
            self.assertEqual(start, values[0])
            self.assertEqual(end, values[-1])
            self.assertEqual(sorted(values), values)
            self.assertLess(max(values[:-1]), end)
        for p in plan:
            for task in p['tasks']:
                if task['type'] == 'T4':
                    self.assertEqual({4: 1, 5: 2, 6: 3}[p['stage']], task['targetCount'])

    def test_ceil_reserves_at_all_band_edges(self):
        for number, expected in [(1, 9), (10, 9), (11, 8), (20, 8), (21, 6), (30, 6)]:
            self.assertEqual(expected, campaign.reserve_size(self.config, number, 3))
        self.assertEqual(3, campaign.reserve_size(self.config, 11, 1))

    def test_t1_counts_only_four_plus_and_banned_words_do_not_supply_reserve(self):
        _, task, evidence = self.one('T1', n=6)
        self.assertNotIn('кот', evidence['accepted_answers'])
        self.assertTrue(all(len(w) >= 4 for w in evidence['candidate_pool']))
        self.assertFalse(set(evidence['candidate_pool']) & campaign.ROLE_EXCLUSIONS)
        # The general-input predicate still accepts prohibited assignment words.
        self.assertTrue(campaign.ROLE_EXCLUSIONS <= set(evidence['accepted_answers']))
        self.assertEqual(18, evidence['reserve_required'])
        self.assertEqual(dict(id='l01-t1', type='T1', n=6), task)

    def test_t2_uses_four_plus_and_alternates_across_appearances(self):
        sources = [self.source, self.source[::-1], self.source + 'а']
        for source in sources:
            self.payload['profiles'][source] = deepcopy(self.payload['profiles'][self.source])
        b = self.builder([[('T2', dict(n=3))]] * 3)
        result, report = b.attempt(sources)
        self.assertIsNotNone(result, report)
        letters = [p['tasks'][0]['letter'] for p in result['levels']]
        self.assertTrue(all(a != b for a, b in zip(letters, letters[1:])))
        for row in report['tasks']:
            self.assertTrue(all(len(w) >= 4 and w[0] == row['task']['letter'] for w in row['accepted_answers']))
        # A greedy first choice would fail; DP must look ahead.
        self.assertEqual({'first': 'б', 'last': 'а'}, campaign.alternating_letters({'first': ['а', 'б'], 'last': ['а']}))
        self.assertIsNone(campaign.alternating_letters({'a': ['а'], 'b': ['а']}))

    def test_t3_accepts_every_long_answer_and_other_level_targets(self):
        b, task, row = self.one('T3', minLength=8)
        self.assertGreater(len(row['accepted_answers']), 1)
        self.assertIn(task['hintTarget'], row['accepted_answers'])
        self.assertTrue(all(len(w) >= 8 for w in row['accepted_answers']))
        other = dict(task, hintTarget=row['accepted_answers'][-1])
        self.assertEqual(row['accepted_answers'], b.accepted(self.source, other))

    def test_t4_reserve_before_assignment_all_fixed_targets_required(self):
        _, task, row = self.one('T4', targetCount=3)
        self.assertEqual(3, len(task['targets']))
        self.assertEqual(9, row['reserve_required'])
        self.assertEqual(sorted(task['targets']), row['accepted_answers'])
        self.assertEqual(3, row['accepted_count'])
        self.assertTrue(row['reserve_applies_before_assignment'])
        self.assertEqual(set(task['targets']), set(row['assigned_hint_hashes']))
        self.assertFalse(set(task['targets']) & campaign.ROLE_EXCLUSIONS)

    def test_t4_no_usable_masked_hint_means_shortage(self):
        for info in self.payload['hints'].values():
            info['useful'] = False
        b = self.builder([[('T4', dict(targetCount=1))]])
        result, report = b.attempt([self.source])
        self.assertIsNone(result)
        self.assertIn('reserve_shortage', {r['code'] for r in report['violations']})

    def test_t3_and_t4_share_unique_targets_even_on_same_level(self):
        sources = [self.source, self.source[::-1]]
        self.payload['profiles'][sources[1]] = deepcopy(self.payload['profiles'][self.source])
        b = self.builder([[('T3', dict(minLength=8)), ('T4', dict(targetCount=3))]] * 2)
        result, report = b.attempt(sources)
        self.assertIsNotNone(result, report)
        assigned = [w for level in result['levels'] for task in level['tasks']
                    for w in ([task['hintTarget']] if task['type'] == 'T3' else task['targets'])]
        self.assertEqual(8, len(set(assigned)))
        # Ordinary matching answers can still be shared across levels.
        self.assertEqual(b.accepted(sources[0], result['levels'][0]['tasks'][0]),
                         b.accepted(sources[1], result['levels'][1]['tasks'][0]))

    def test_global_target_deficit_detected_despite_local_reserve(self):
        limited = self.words[:3]
        self.payload['profiles'][self.source].update(assigned_targets=limited, riddle_targets=limited)
        b = self.builder([[('T3', dict(minLength=5)), ('T4', dict(targetCount=1))]] * 2)
        result, report = b.attempt([self.source] * 2)
        self.assertIsNone(result)
        self.assertFalse(any(r['code'] == 'reserve_shortage' for r in report['violations']))
        self.assertTrue(any(r['code'] == 'unique_targets' for r in report['violations']))

    def test_t5_meaningful_endings_joint_predicate_and_global_uniqueness(self):
        b = self.builder([[('T5', dict(minLength=8))]] * 3)
        result, report = b.attempt([self.source] * 3)
        self.assertIsNotNone(result, report)
        endings = [p['tasks'][0]['ending'] for p in result['levels']]
        self.assertEqual(3, len(set(endings)))
        for row in report['tasks']:
            self.assertTrue(row['ending_rationale'])
            self.assertTrue(all(len(w) >= 8 and w.endswith(row['task']['ending']) for w in row['candidate_pool']))
            self.assertGreaterEqual(row['candidate_count'], 3)
        self.config['endings'] = {'щик': 'Синтетическая проверка дефицита.'}
        b = self.builder([[('T5', dict(minLength=8))]])
        self.assertIsNone(b.attempt([self.source])[0])

    def test_t6_global_minimum_local_reserve_full_membership_and_unique_categories(self):
        b = self.builder([[('T6', dict(n=3))]] * 3)
        result, report = b.attempt([self.source] * 3)
        self.assertIsNotNone(result, report)
        self.assertEqual(3, len({p['tasks'][0]['category'] for p in result['levels']}))
        for row in report['tasks']:
            self.assertGreaterEqual(row['global_recognizable_count'], 40)
            self.assertGreaterEqual(row['candidate_count'], 9)
            self.assertEqual(row['accepted_answers'], row['task']['words'])
            self.assertEqual([w for w in b.answers(self.source) if b.category(w) == row['task']['category']], row['task']['words'])
        # An input word can be in the offline category list without supplying safe reserve.
        self.assertTrue(any(set(p['tasks'][0]['words']) & campaign.ROLE_EXCLUSIONS for p in result['levels']))

    def test_t6_global_shortage_unknown_and_other_are_not_themes(self):
        for i, w in enumerate(self.words):
            self.metadata[w]['category'] = f'theme_{i // 30}'
        b = self.builder([[('T6', dict(n=3))]])
        self.assertIsNone(b.attempt([self.source])[0])
        for meta in self.metadata.values():
            meta.update(category='plants', category_source='unknown:test')
        self.assertIsNone(self.builder([[('T6', dict(n=3))]]).attempt([self.source])[0])

    def test_t6_local_shortage_and_global_category_collision(self):
        # Many globally suitable words, only three locally composable.
        small_source = self.words[0] + self.words[1]
        self.payload['profiles'][small_source] = self.payload['profiles'][self.source]
        b = self.builder([[('T6', dict(n=5))]])
        result, report = b.attempt([small_source])
        self.assertIsNone(result)
        self.assertTrue(any(r['code'] == 'reserve_shortage' for r in report['violations']))
        for meta in self.metadata.values():
            meta.update(category='plants', category_source='rules:synthetic')
        b = self.builder([[('T6', dict(n=3))]] * 2)
        result, report = b.attempt([self.source] * 2)
        self.assertIsNone(result)
        self.assertTrue(any(r['code'] == 'unique_categories' for r in report['violations']))

    def test_t7_rarest_feasible_source_letter_and_dictionary_occurrence_frequency(self):
        b, task, row = self.one('T7', n=3)
        options = row['source_letter_options']
        self.assertEqual(task['letter'], next(r['letter'] for r in options if r['eligible']))
        self.assertIn(task['letter'], self.source)
        self.assertEqual(sorted(options, key=lambda r: (r['dictionary_occurrences'], r['letter'])), options)
        self.assertEqual(sum(w.count('а') for w in set(self.words)), b.letter_counts['а'])
        self.assertTrue(all(task['letter'] in w for w in row['candidate_pool']))
        self.assertTrue(any(not r['eligible'] for r in options))

    def test_dictionary_changes_recompute_counts_pools_and_letter_frequency(self):
        b, task, row = self.one('T1', n=6)
        remaining = [w for w in self.words if w not in row['candidate_pool'][:20]]
        changed = self.builder([[('T1', dict(n=6))]], remaining)
        result, report = changed.attempt([self.source])
        self.assertIsNotNone(result, report)
        self.assertEqual(row['candidate_count'] - 20, report['tasks'][0]['candidate_count'])
        self.assertNotEqual(b.letter_counts, changed.letter_counts)
        self.assertFalse(set(row['candidate_pool'][:20]) & set(report['tasks'][0]['accepted_answers']))
        tiny = self.builder([[('T1', dict(n=6))]], self.words[:17])
        self.assertIsNone(tiny.attempt([self.source])[0])

    def test_allocation_is_independent_of_input_order(self):
        plans = [[('T3', dict(minLength=8)), ('T4', dict(targetCount=2)), ('T5', dict(minLength=8)),
                  ('T6', dict(n=3)), ('T7', dict(n=3))]]
        first = self.builder(plans).attempt([self.source])
        self.payload['assessments'].reverse()
        self.payload['words'] = dict(reversed(list(self.payload['words'].items())))
        self.words.reverse()
        self.assertEqual(first, self.builder(plans).attempt([self.source]))

    def test_full_thirty_level_assignment_has_core01_shape_and_unique_namespaces(self):
        words = [''.join(chars) + ending for ending in self.config['endings']
                 for chars in list(product('абвг', repeat=5))[::20][:50]]
        words = sorted(set(words))
        sources = [self.source[i:] + self.source[:i] for i in range(30)]
        self.payload = dict(words={w: dict(category=['plants', 'birds', 'home'][i % 3],
                            category_source='rules:synthetic', observed_frequency=dict(frequency=100))
                            for i, w in enumerate(words)},
            assessments=[dict(word=w, role='target', assessment=dict(status='suitable')) for w in words],
            hints={w: dict(useful=True, sha256=json_digest(['synthetic', w])) for w in words},
            profiles={s: dict(assigned_targets=words, riddle_targets=words) for s in sources})
        self.words = words
        result, report = self.builder().attempt(sources)
        self.assertIsNotNone(result, report)
        self.assertEqual(30, len(result['levels']))
        self.assertEqual(100, sum(len(p['tasks']) for p in result['levels']))
        targets, endings, themes = [], [], []
        ids = []
        fields = {'T1': {'n'}, 'T2': {'letter', 'n'}, 'T3': {'minLength', 'hintTarget'},
                  'T4': {'targets'}, 'T5': {'ending', 'minLength'},
                  'T6': {'category', 'n', 'words'}, 'T7': {'letter', 'n'}}
        for level in result['levels']:
            self.assertEqual({'number', 'word', 'stage', 'requiredStars', 'tasks'}, set(level))
            for task in level['tasks']:
                self.assertEqual(fields[task['type']] | {'id', 'type'}, set(task))
                ids.append(task['id'])
                if task['type'] == 'T3':
                    targets.append(task['hintTarget'])
                elif task['type'] == 'T4':
                    targets.extend(task['targets'])
                elif task['type'] == 'T5':
                    endings.append(task['ending'])
                elif task['type'] == 'T6':
                    themes.append(task['category'])
        for values in [ids, targets, endings, themes]:
            self.assertEqual(len(values), len(set(values)))
        self.assertEqual(7, len(result['introductions']))
        for row in report['tasks']:
            self.assertEqual(len(row['candidate_pool']), row['candidate_count'])
            self.assertGreaterEqual(row['candidate_count'], row['reserve_required'])
            self.assertGreaterEqual(row['accepted_count'], row['required_answers'])

    def test_failure_removes_old_candidate_and_articles_are_never_serialized(self):
        _, result_task, _ = self.one('T4', targetCount=1)
        b = self.builder([[('T4', dict(targetCount=1))]])
        result, report = b.attempt([self.source])
        report.update(status='valid', attempts=1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            campaign.write_outputs(path, result, report)
            saved = json.loads((path / 'campaign.json').read_text())
            self.assertEqual(result_task, saved['levels'][0]['tasks'][0])
            self.assertEqual([dict(type='T4', taskId='l01-t4', levelNumber=1)], saved['introductions'])
            self.assertNotIn('definition', (path / 'campaign.json').read_text())
            self.assertNotIn('hint', (path / 'campaign.json').read_text())
            campaign.write_outputs(path, None, dict(status='search_exhausted', attempts=1, violations=[]))
            self.assertFalse((path / 'campaign.json').exists())

    def test_config_rejects_relaxed_contract_and_bad_budget(self):
        for change in (lambda c: c['curves']['T3'].__setitem__(1, 8),
                       lambda c: c.__setitem__('category_global_min', 39),
                       lambda c: c['search'].__setitem__('max_attempts', 0),
                       lambda c: c['reserve_bands'][0].__setitem__('numerator', 2)):
            config = deepcopy(self.config)
            change(config)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'config.json'
                path.write_text(json.dumps(config))
                with self.assertRaises(ValueError):
                    campaign.load_config(path)


class SearchTest(unittest.TestCase):
    class Builder:
        def __init__(self, wanted, max_attempts=100):
            self.wanted = wanted
            self.config = dict(search=dict(max_attempts=max_attempts, beam_width=4, replacements_per_slot=4))

        def local(self, number, source):
            return {'T1': dict(eligible={'ok': ['x']} if source == self.wanted[number - 1] else {})}

        def attempt(self, selected):
            failures = [dict(code='reserve_shortage', level=i + 1, type='T1', required=3, available=0)
                        for i, (got, wanted) in enumerate(zip(selected, self.wanted)) if got != wanted]
            return (None if failures else dict(levels=list(selected))), dict(violations=failures)

    class Selector:
        shortlists = [['а', 'б', 'в'], ['а', 'б', 'в']]

        @staticmethod
        def local_failures(slot, word):
            return []

        @staticmethod
        def evaluate(selected):
            failures = [dict(code='duplicate_sources')] if len(set(selected)) != len(selected) else []
            return (), {}, failures, {}

    def test_swap_and_replacement_recover_slot_conflicts_deterministically(self):
        proposal = dict(slots=[dict(word='а', spares=[]), dict(word='б', spares=[dict(word='в')])])
        for wanted in [('б', 'а'), ('а', 'в')]:
            first = campaign.search(self.Builder(wanted), self.Selector(), proposal)
            self.assertEqual(wanted, tuple(first[0]['levels']))
            self.assertEqual(first, campaign.search(self.Builder(wanted), self.Selector(), proposal))
            self.assertLessEqual(first[1]['attempts'], 100)

    def test_search_budget_never_accepts_invalid_source_collisions(self):
        proposal = dict(slots=[dict(word='а', spares=[]), dict(word='б', spares=[])])
        result, report = campaign.search(self.Builder(('а', 'а'), max_attempts=2), self.Selector(), proposal)
        self.assertIsNone(result)
        self.assertEqual('search_exhausted', report['status'])
        self.assertLessEqual(report['attempts'], 2)
        self.assertFalse(report['installable'])

    def test_empty_full_source_pool_is_a_proof_not_search_exhaustion(self):
        builder = self.Builder(('я', 'б'))
        builder.local = lambda number, source: {'T1': dict(
            eligible={'ok': ['x']} if source == builder.wanted[number - 1] else {},
            pools={'count': ['x']})}
        selector = self.Selector()
        selector.candidates = ['а', 'б', 'в']
        proposal = dict(slots=[dict(word='а', spares=[]), dict(word='б', spares=[])])
        result, report = campaign.search(builder, selector, proposal)
        self.assertIsNone(result)
        self.assertEqual('infeasible', report['status'])
        self.assertEqual(0, report['attempts'])
        self.assertEqual(3, report['violations'][0]['source_candidates'])
        self.assertEqual(1, report['violations'][0]['max_candidate_count'])


if __name__ == '__main__':
    unittest.main()
