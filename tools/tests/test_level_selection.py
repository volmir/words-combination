"""Synthetic GEN-02 inputs: no article texts, network, or repository data changes."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import pick_level_words as profiles
import select_level_words as selection
from game_data_providers import json_digest


class LevelSelectionTest(unittest.TestCase):
    def setUp(self):
        self.config = selection.load_config()
        self.config.update(levels=3, retain_min=1, retain_max=2)
        self.config['bands'] = [dict(first=1, last=3, min=2, max=5)]
        self.config['search'].update(restarts=2, max_iterations=3, shortlist_per_slot=8,
                                      refinement_restarts=2, refinement_iterations=200)
        self.sources = ['аааааааб', 'ааааааба', 'ааааабаа', 'аааабааа']
        groups = [['мама', 'папа', 'рама'], ['лапа', 'липа', 'репа'],
                  ['река', 'рука', 'мука'], ['гора', 'нора', 'кора']]
        self.words = set(sum(groups, []))
        self.metadata = {w: dict(category='plants', category_source='rules:synthetic',
                                observed_frequency=dict(frequency=100, freq_rank=1)) for w in self.words}
        config = profiles.load_config(profiles.CONFIG)
        self.measured = {}
        for word, answers in zip(self.sources, groups):
            self.measured[word] = dict(source_eligible=True, answers=answers,
                recognizable_answers=answers, assigned_targets=answers, riddle_targets=answers,
                recognizable=profiles.metrics(answers, self.metadata, config),
                all=profiles.metrics(answers, self.metadata, config))
        self.previous = [{'index': i + 1, 'word': w} for i, w in enumerate(self.sources[:2])]

    def selector(self, alphabet='аб'):
        return selection.Selector(dict(profiles=self.measured, words=self.metadata),
                                  self.words, self.previous, self.config, alphabet)

    def synthetic_campaign(self):
        """30 slots: 26 low-band sources and four 250+ candidates for slots 27-30."""
        config = selection.load_config()
        config.update(levels=30, retain_min=0, retain_max=0)
        config['bands'] = [dict(first=1, last=26, min=2, max=5),
                           dict(first=27, last=30, min=250, max=700)]
        config['search'].update(restarts=1, max_iterations=1,
                                refinement_restarts=1, refinement_iterations=600)
        config['mean_jaccard_max'] = 1.0
        config['max_jaccard_max'] = 1.0
        letters = 'вгдежзиклмнопрстуфхцчшщъыьэюя'
        counter = [0]

        def answer():
            number = counter[0]
            counter[0] += 1
            result = []
            for _ in range(4):
                result.append(letters[number % len(letters)])
                number //= len(letters)
            return ''.join(result)

        def source(number):
            return ''.join('аб'[int(bit)] for bit in format(number, '08b'))

        common = [answer(), answer()]
        metadata = {}

        def remember(word, category):
            metadata[word] = dict(category=category, category_source='rules:synthetic',
                                  observed_frequency=dict(frequency=100, freq_rank=1))

        for word in common:
            remember(word, 'common')
        low_sources = [source(i) for i in range(30)]
        low_answers = []
        for i in range(30):
            unique = [answer(), answer()]
            for word in unique:
                remember(word, f'theme-low-{i}')
            low_answers.append(common + unique)
        high_sources = [source(i) for i in range(30, 34)]
        high_answers = []
        for i in range(4):
            answers = [answer() for _ in range(250)]
            for word in answers:
                remember(word, f'theme-high-{i}')
            high_answers.append(answers)
        target_letters = 'гдежзиклмнопрстуфхцчшщъыьэюя'
        targets = []
        for number in range(60):
            value, word = number, []
            for _ in range(6):
                word.append(target_letters[value % len(target_letters)])
                value //= len(target_letters)
            targets.append(''.join(word))
        profile_config = profiles.load_config(profiles.CONFIG)

        def profile(answers):
            metrics = profiles.metrics(answers, metadata, profile_config)
            return dict(source_eligible=True, answers=answers, recognizable_answers=answers,
                        assigned_targets=targets, riddle_targets=targets,
                        recognizable=metrics, all=metrics)

        measured = {}
        for sources, answers in ((low_sources, low_answers), (high_sources, high_answers)):
            for word, rows in zip(sources, answers):
                measured[word] = profile(rows)
        selector = selection.Selector(dict(profiles=measured, words=metadata),
                                      set(metadata), [], config, 'аб')
        return selector, measured, metadata, low_sources, high_sources, config

    def test_incomplete_26_of_30_improves_after_pool_expansion(self):
        full, measured, metadata, low_sources, high_sources, config = self.synthetic_campaign()
        partial = selection.Selector(
            dict(profiles={word: measured[word] for word in low_sources}, words=metadata),
            set(metadata), [], config, 'аб')
        selected = low_sources[:26]
        before = partial.refine_jaccard(selected)
        self.assertTrue(before['hard_infeasible_by_matching'])
        self.assertFalse(before['search_exhausted'])
        self.assertFalse(before['valid'])
        self.assertEqual(26, sum(word is not None for word in before['best_found']))
        before_mean = partial.measure(before['best_found'])['mean_jaccard']

        after = full.refine_jaccard(selected)
        self.assertFalse(after['hard_infeasible_by_matching'])
        self.assertFalse(after['search_exhausted'])
        self.assertTrue(after['valid'])
        self.assertFalse(full.evaluate(after['best_found'])[2])
        self.assertEqual(30, sum(word is not None for word in after['best_found']))
        self.assertTrue(set(high_sources) <= set(after['best_found']))
        after_mean = full.measure(after['best_found'])['mean_jaccard']
        self.assertLess(after_mean, before_mean)

        # The same incomplete result may arrive as 30 filled slots, four of
        # them outside the 250+ band; refinement must still be able to repair it.
        full_length = low_sources[:26] + low_sources[26:30]
        repaired = full.refine_jaccard(full_length)
        self.assertTrue(repaired['valid'])
        self.assertTrue(set(high_sources) <= set(repaired['best_found']))

        # The final validator still rejects a 250+ band filled with a low-band word.
        invalid = after['best_found'].copy()
        invalid[26] = low_sources[0]
        codes = {row['code'] for row in full.evaluate(invalid)[2]}
        self.assertIn('recognizable_range', codes)

        proposal = selection.make_proposal(full, after['best_found'], after['trace'], {})
        self.assertTrue(proposal['installable'])
        self.assertFalse(proposal['hard_infeasible_by_matching'])
        self.assertFalse(proposal['search_exhausted'])
        self.assertEqual(after['best_found'], proposal['best_found'])

    def test_one_250_plus_candidate_cannot_fill_30_slots(self):
        full, measured, metadata, _, high_sources, config = self.synthetic_campaign()
        candidate = high_sources[0]
        self.assertEqual(250, len(measured[candidate]['recognizable_answers']))
        config = deepcopy(config)
        config['bands'] = [dict(first=1, last=30, min=250, max=700)]
        config['search'].update(refinement_iterations=20)
        selector = selection.Selector(
            dict(profiles={candidate: measured[candidate]}, words=metadata),
            set(metadata), [], config, 'аб')
        result = selector.refine_jaccard([candidate] + [None] * 29)
        self.assertTrue(result['hard_infeasible_by_matching'])
        self.assertFalse(result['search_exhausted'])
        self.assertFalse(result['valid'])
        self.assertTrue(selector.impossibility()['hard_infeasible_by_matching'])
        self.assertEqual(1, result['matching']['matched'])
        self.assertEqual(1, sum(word is not None for word in result['best_found']))

        proposal = selection.make_proposal(selector, result['best_found'], result['trace'], {})
        self.assertEqual('infeasible', proposal['status'])
        self.assertFalse(proposal['installable'])
        self.assertTrue(proposal['hard_infeasible_by_matching'])
        self.assertFalse(proposal['search_exhausted'])
        self.assertEqual(1, proposal['feasibility']['distinct_source_matching']['matched'])

    def test_simple_jaccard_and_empty_sets(self):
        self.assertEqual(1 / 3, selection.jaccard(['кот', 'ток', 'кот'], ['ток', 'сок']))
        self.assertEqual(0, selection.jaccard([], []))
        self.assertEqual(1, selection.jaccard(['мир'], ['мир']))
        s = self.selector()
        for a in self.sources:
            for b in self.sources:
                self.assertEqual(selection.jaccard(self.measured[a]['recognizable_answers'],
                                                   self.measured[b]['recognizable_answers']), s.pair(a, b))

    def test_t3_preliminary_target_requires_a_useful_hint(self):
        self.config['planning'] = dict(self.config['planning'], long_min_length=4)
        source = self.sources[0]
        self.measured[source]['assigned_targets'] = ['мама']
        self.measured[source]['riddle_targets'] = []
        self.assertEqual(set(), self.selector().options[source]['T3'])
        self.measured[source]['riddle_targets'] = ['мама']
        self.assertEqual({'мама'}, self.selector().options[source]['T3'])

    def test_real_alphabet_includes_all_32_characters(self):
        alphabet = selection.read_alphabet()
        self.assertEqual('абвгдежзийклмнопрстуфхцчшщъыьэюя', alphabet)
        self.assertTrue(set('йъьыэю') <= set(alphabet))
        self.assertNotIn('ё', alphabet)
        s = self.selector(alphabet)
        metrics = s.measure(self.sources[:3])
        self.assertEqual('аб', metrics['covered_letters'])
        self.assertEqual('вгдежзийклмнопрстуфхцчшщъыьэюя', metrics['missing_letters'])

    def test_feasible_selection_and_stable_order(self):
        first = self.selector()
        selected, trace = first.search()
        self.assertEqual(3, len(set(selected)))
        self.assertFalse(first.evaluate(selected)[2])
        self.measured = dict(reversed(list(self.measured.items())))
        self.metadata = dict(reversed(list(self.metadata.items())))
        second = self.selector()
        self.assertEqual((selected, trace), second.search())

    def test_no_solution_diagnostic_cannot_be_installed(self):
        self.config['bands'][0].update(min=20, max=30)
        s = self.selector()
        selected, trace = s.search()
        proposal = selection.make_proposal(s, selected, trace, {})
        self.assertFalse(proposal['installable'])
        self.assertTrue(proposal['diagnostic_only'])
        self.assertEqual('infeasible', proposal['status'])
        self.assertEqual(0, proposal['feasibility']['distinct_source_matching']['matched'])
        self.assertTrue(any(v['code'] == 'recognizable_range' for v in proposal['violations']))
        self.assertTrue(all(not row['spares'] for row in proposal['slots']))

    def test_missing_assessments_are_not_reported_as_complete(self):
        coverage = self.selector().semantic_coverage
        self.assertFalse(coverage['recorded'])
        self.assertIsNone(coverage['pending'])

    def test_pending_assessments_explain_the_limited_input_without_relaxing_bounds(self):
        self.config['bands'][0].update(min=20, max=30)
        payload = dict(profiles=self.measured, words=self.metadata, assessments=[
            dict(word=self.sources[0], role='source', assessment_source='pending'),
            dict(word='мама', role='target', assessment_source='pending'),
            dict(word='папа', role='target', assessment_source='rules'),
            # A completed AI decision may still be insufficient_data. It must
            # not be counted as a missing provider response.
            dict(word='рама', role='target', assessment_source='ai',
                 assessment=dict(status='insufficient_data'))])
        s = selection.Selector(payload, self.words, self.previous, self.config, 'аб')
        selected, trace = s.search()
        proposal = selection.make_proposal(s, selected, trace, {})
        coverage = proposal['semantic_coverage']
        self.assertTrue(coverage['recorded'])
        self.assertEqual(2, coverage['pending'])
        self.assertEqual(dict(rules=0, ai=0, pending=1), coverage['by_role']['source'])
        self.assertEqual(dict(rules=1, ai=1, pending=1), coverage['by_role']['target'])
        self.assertEqual(3, proposal['feasibility']['max_recognizable_answers'])
        self.assertFalse(proposal['installable'])
        self.assertEqual('infeasible', proposal['status'])
        with tempfile.TemporaryDirectory() as directory:
            selection.write_reports(s, proposal, Path(directory))
            report = (Path(directory) / 'LEVEL_WORDS_REPORT.md').read_text()
        self.assertIn('Оценок без ответа ИИ: **2**', report)
        self.assertIn('не доказывает невозможность после завершения оценок', report)

    def test_exhausted_search_is_not_an_impossibility_proof(self):
        for word in self.sources[1:]:
            self.measured[word] = deepcopy(self.measured[self.sources[0]])
        self.config['search'].update(max_iterations=1, restarts=1)
        s = self.selector()
        selected, trace = s.search()
        proposal = selection.make_proposal(s, selected, trace, {})
        self.assertEqual('search_exhausted', proposal['status'])
        self.assertFalse(proposal['installable'])
        self.assertFalse(proposal['feasibility']['proven_infeasible'])
        self.assertFalse(proposal['hard_infeasible_by_matching'])
        self.assertTrue(proposal['search_exhausted'])
        greedy = [row for row in trace if row.get('phase') != 'jaccard_refinement']
        refinement = [row for row in trace if row.get('phase') == 'jaccard_refinement']
        self.assertEqual(1, len(greedy))
        self.assertLessEqual(len(refinement), 2*self.config['search']['refinement_restarts'])
        self.assertTrue(all(row['iterations'] <= self.config['search']['refinement_iterations'] for row in refinement))
        self.assertLessEqual(trace[0]['local_changes'], 1)

    def test_wrong_number_of_slots_cannot_pass_final_validation(self):
        s = self.selector()
        self.assertTrue(any(v['code'] == 'slot_count' for v in s.evaluate(self.sources[:2])[2]))

    def test_local_replacement_resolves_collision(self):
        # Bad initial source has exactly the second source's answers. Replacing
        # it with the fourth removes the collision and still retains one old word.
        self.measured[self.sources[0]] = deepcopy(self.measured[self.sources[1]])
        s = self.selector()
        selected = self.sources[:3]
        self.assertTrue(s.evaluate(selected)[2])
        changed, improved = s.improve(selected)
        self.assertTrue(improved)
        self.assertEqual(self.sources[3], changed[0])
        self.assertFalse(s.evaluate(changed)[2])

    def test_refinement_uses_full_pool_and_remains_deterministic(self):
        self.measured[self.sources[0]] = deepcopy(self.measured[self.sources[1]])
        results = []
        for _ in range(2):
            s = self.selector()
            s.shortlists = [self.sources[:3] for _ in range(3)]
            selected, trace = s.refine_jaccard(self.sources[:3])
            self.assertIn(self.sources[3], selected)
            self.assertFalse(s.evaluate(selected)[2])
            self.assertTrue(trace)
            results.append((selected, trace))
        self.assertEqual(results[0], results[1])

    def test_refinement_does_not_relax_other_hard_constraints(self):
        s = self.selector('абв')
        selected, trace = s.refine_jaccard(self.sources[:3])
        self.assertEqual(self.sources[:3], selected)
        self.assertFalse(trace)
        self.assertTrue(any(row['code']=='alphabet_coverage' for row in s.evaluate(selected)[2]))

    def test_refinement_promotes_feasible_preferences_before_optimizing_overlap(self):
        with patch.object(selection, 'PREFERRED_SOURCES', {self.sources[3]}):
            s = self.selector()
            selected, trace = s.refine_jaccard(self.sources[:3])
            self.assertIn(self.sources[3], selected)
            self.assertFalse(s.evaluate(selected)[2])
            self.assertEqual('preferred_seed', trace[0]['phase'])

    def test_local_slot_swap_resolves_ranges(self):
        s = self.selector()
        a, b = self.sources[:2]
        # Make the swapped arrangement locally invalid, while both sources
        # remain necessary to retain the configured minimum.
        self.config['bands'] = [dict(first=1, last=1, min=3, max=3),
                                dict(first=2, last=3, min=2, max=2)]
        self.config.update(retain_min=2, retain_max=2)
        for word in self.sources[1:]:
            self.measured[word]['recognizable_answers'] = self.measured[word]['recognizable_answers'][:2]
        s = self.selector()
        selected = [b, a, self.sources[2]]
        changed, improved = s.improve(selected)
        self.assertTrue(improved)
        self.assertEqual([a, b, self.sources[2]], changed)
        self.assertFalse(s.evaluate(changed)[2])

    def test_owner_exclusions_and_preference_do_not_bypass_ranges(self):
        for word in selection.ROLE_EXCLUSIONS | selection.PREFERRED_SOURCES:
            self.measured[word] = deepcopy(self.measured[self.sources[0]])
        s = self.selector(selection.read_alphabet())
        self.assertFalse(set(s.candidates) & selection.ROLE_EXCLUSIONS)
        self.config['bands'][0].update(min=120, max=300)
        s = self.selector(selection.read_alphabet())
        for word in selection.PREFERRED_SOURCES:
            self.assertIn('recognizable_range', s.local_failures(0, word))
        self.measured['хлопкопункт']['source_eligible'] = False
        self.assertNotIn('хлопкопункт', self.selector(selection.read_alphabet()).candidates)

    def test_matching_finds_alternative_and_detects_shared_namespace_collision(self):
        result = selection.matching({'11_T3': {'слово', 'другое'}, '16_T4': {'слово'}})
        self.assertEqual(2, result['matched'])
        self.assertEqual('другое', result['witness']['11_T3'])
        self.assertEqual(1, selection.matching({'11_T3': {'слово'}, '16_T4': {'слово'}})['matched'])
        # Hall failure despite union size equaling request count.
        self.assertEqual(2, selection.matching({'a': {'x'}, 'b': {'x'}, 'c': {'y', 'z'}})['matched'])

    def test_global_future_target_collisions_are_hard(self):
        self.config.update(levels=30, retain_min=0)
        self.config['bands'] = [dict(first=1, last=30, min=2, max=5)]
        target = 'синтетика'
        for p in self.measured.values():
            p['assigned_targets'] = [target]
            p['riddle_targets'] = [target]
        s = self.selector()
        selected = [None] * 30
        selected[10], selected[15] = self.sources[:2]
        reservations, _ = s.reservations(selected)
        self.assertEqual(3, reservations['targets_T3_T4']['required'])
        self.assertEqual(1, reservations['targets_T3_T4']['matched'])
        self.assertTrue(any(v['code'] == 'targets_T3_T4' for v in s.evaluate(selected)[2]))

    def test_top5000_uses_game_lemmas_with_observed_counts_and_stable_ties(self):
        self.words = {'а' + str(i).translate(str.maketrans('0123456789', 'абвгдежзик'))
                      for i in range(5100)}
        self.metadata = {w: dict(observed_frequency=dict(frequency=100, freq_rank=99999))
                         for w in self.words}
        observed = sorted(self.words)
        self.metadata[observed[0]]['observed_frequency']['frequency'] = 0
        self.metadata['внеигроваялемма'] = dict(observed_frequency=dict(frequency=999999, freq_rank=1))
        s = self.selector()
        self.assertEqual(5099, s.observed_count)
        self.assertEqual(5000, len(s.top))
        self.assertEqual(set(observed[1:5001]), s.top)
        self.assertNotIn('внеигроваялемма', s.top)

    def test_small_frequency_denominator_and_general_union_are_explicit(self):
        s = self.selector()
        metrics = s.measure(self.sources[:3])
        self.assertEqual(12, metrics['top5000_denominator'])
        self.assertEqual(9, metrics['top5000_recognizable_covered'])
        self.assertEqual(9, metrics['all_answers_union'])
        self.assertEqual(12, metrics['game_dictionary_size'])

    def test_spares_pass_the_whole_campaign_and_have_limit_five(self):
        s = self.selector()
        selected = self.sources[:3]
        spares = s.spares(selected)
        self.assertTrue(spares[0])
        for slot, rows in enumerate(spares):
            self.assertLessEqual(len(rows), 5)
            for row in rows:
                changed = selected.copy()
                changed[slot] = row['word']
                self.assertFalse(s.evaluate(changed)[2])

    def test_all_three_artifacts_are_byte_identical(self):
        s = self.selector()
        selected, trace = s.search()
        proposal = selection.make_proposal(s, selected, trace, {'synthetic': True})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            selection.write_reports(s, proposal, root / 'a')
            selected2, trace2 = self.selector().search()
            second = selection.make_proposal(self.selector(), selected2, trace2, {'synthetic': True})
            selection.write_reports(s, second, root / 'b')
            for filename in ('level_words_candidates.csv', 'level_words_proposal.json', 'LEVEL_WORDS_REPORT.md'):
                self.assertEqual((root / 'a' / filename).read_bytes(), (root / 'b' / filename).read_bytes())

    def test_config_rejects_changed_contract_and_invalid_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            for change in ('retain_min', 'bands', 'search', 'master_types'):
                c = selection.load_config()
                if change == 'retain_min':
                    c[change] = 9
                elif change == 'bands':
                    c[change][0]['min'] = 119
                elif change == 'search':
                    c[change]['max_iterations'] = 0
                else:
                    c[change][0] = ['T1'] * 5
                path.write_text(json.dumps(c), encoding='utf-8')
                with self.assertRaises(ValueError):
                    selection.load_config(path)

    def test_snapshot_rejects_corruption_stale_dictionary_and_measurement_code(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            words, previous, snapshot = root / 'words.txt', root / 'levels.json', root / 'cache.json.gz'
            words.write_text('слово\n', encoding='utf-8')
            previous.write_text('[]\n', encoding='utf-8')
            payload = {'synthetic': True}
            document = dict(schema=profiles.SCHEMA, payload=payload, payload_sha256=json_digest(payload),
                            inputs=dict(words=selection.sha256_file(words), levels=selection.sha256_file(previous)))
            profiles.write_cache(snapshot, document)
            self.assertEqual(payload, selection.load_snapshot(snapshot, words, previous)['payload'])
            words.write_text('изменено\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Словарь/история'):
                selection.load_snapshot(snapshot, words, previous)
            words.write_text('слово\n', encoding='utf-8')
            document['inputs']['code:pick_level_words.py'] = '0' * 64
            profiles.write_cache(snapshot, document)
            with self.assertRaisesRegex(ValueError, 'Устаревшие измерения'):
                selection.load_snapshot(snapshot, words, previous)
            document['payload_sha256'] = '0' * 64
            profiles.write_cache(snapshot, document)
            with self.assertRaisesRegex(ValueError, 'Повреждённый'):
                selection.load_snapshot(snapshot, words, previous)

    def test_snapshot_does_not_depend_on_downstream_selector_code(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            words, previous, snapshot = root/'words.txt', root/'levels.json', root/'cache.json.gz'
            words.write_text('слово\n', encoding='utf-8')
            previous.write_text('[]\n', encoding='utf-8')
            payload = dict(synthetic=True)
            document = dict(schema=profiles.SCHEMA, payload=payload, payload_sha256=json_digest(payload),
                            inputs=dict(words=selection.sha256_file(words), levels=selection.sha256_file(previous),
                                        **{'code:select_level_words.py': '0'*64}))
            profiles.write_cache(snapshot, document)
            self.assertEqual(payload, selection.load_snapshot(snapshot, words, previous)['payload'])


if __name__ == '__main__':
    unittest.main()
