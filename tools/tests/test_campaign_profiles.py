"""Единый short15-профиль: расписание, числа, конфиги и воспроизводимый digest."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import build_campaign as generation
import campaign_profiles as profiles
import simulate_campaign as simulation


class CampaignProfilesTest(unittest.TestCase):
    def setUp(self):
        self.profile = profiles.get('short15-release-v1')

    def test_short15_schedule_has_fifteen_levels_six_stages_and_forty_nine_tasks(self):
        plan = generation.schedule(generation.load_config(), self.profile)
        self.assertEqual(15, len(plan))
        self.assertEqual(49, sum(len(row['tasks']) for row in plan))
        self.assertEqual([3, 2, 3, 2, 3, 2],
                         [sum(1 for p in plan if p['stage'] == number) for number in range(1, 7)])
        self.assertEqual([1, 1, 1, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4],
                         [row['requiredStars'] for row in plan])
        self.assertEqual([10, 12, 14, 16, 18, 20, 22, 25, 27, 29, 31, 33, 35, 37, 40],
                         [p['tasks'][0]['n'] for p in plan])
        self.assertEqual([f'T{i}' for i in range(1, 8)],
                         sorted({t['type'] for row in plan for t in row['tasks']}))
        self.assertTrue(any(t['type'] == 'T6' for t in plan[13]['tasks']))
        self.assertTrue(any(t['type'] == 'T7' for t in plan[14]['tasks']))
        self.assertEqual(4, plan[14]['requiredStars'])
        self.assertEqual(5, len(plan[14]['tasks']))

    def test_t2_t3_t4_t5_t6_t7_numbers_come_from_the_same_profile(self):
        plan = generation.schedule(generation.load_config(), self.profile)
        t2 = [t['n'] for row in plan for t in row['tasks'] if t['type'] == 'T2']
        t3 = [t['minLength'] for row in plan for t in row['tasks'] if t['type'] == 'T3']
        t4 = [t['targetCount'] for row in plan for t in row['tasks'] if t['type'] == 'T4']
        t5 = [t['minLength'] for row in plan for t in row['tasks'] if t['type'] == 'T5']
        t6 = [t for row in plan for t in row['tasks'] if t['type'] == 'T6']
        t7 = [t for row in plan for t in row['tasks'] if t['type'] == 'T7']
        self.assertEqual([5, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15], t2)
        self.assertEqual([6, 6, 6, 7, 7, 7, 8, 8, 9], t3)
        self.assertEqual([1, 1, 2, 2, 2, 3], t4)
        self.assertEqual([5, 5, 6, 7, 8], t5)
        self.assertEqual([{'id': 'l14-t6', 'type': 'T6', 'n': 5, 'category': 'professions', 'introduce': True}], t6)
        self.assertEqual([{'id': 'l15-t7', 'type': 'T7', 'n': 10, 'introduce': True}], t7)

    def test_effective_selection_and_simulation_configs_are_derived_from_profile(self):
        base_selection = generation.selection.load_config()
        selected = profiles.selection_config_for('short15-release-v1', base_selection)
        self.assertEqual(15, selected['levels'])
        self.assertEqual(15, len(selected['level_types']))
        self.assertEqual([4, 6], [b['first'] for b in selected['bands'][1:3]])
        self.assertEqual((3, 5), (selected['retain_min'], selected['retain_max']))
        self.assertEqual((.07, .30), (selected['mean_jaccard_max'], selected['max_jaccard_max']))
        self.assertFalse(selected['alphabet_required'])
        self.assertEqual(9, selected['planning']['long_min_length'])
        self.assertEqual(8, selected['planning']['suffix_min_length'])
        self.assertEqual('short15-release-v1', selected['profile_id'])
        self.assertEqual(profiles.digest(self.profile), selected['profile_sha256'])

        sim = simulation.load_config(profile=self.profile)
        self.assertEqual(15, sim['levels'])
        self.assertEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
                         [row['level'] for row in sim['targets']])
        self.assertEqual('short15-release-v1', sim['profile']['id'])

    def test_short15_reserves_are_1_35x_except_closed_t5_t6(self):
        self.assertEqual(14, profiles.reserve_size(self.profile, 1, 10, 'T1'))
        self.assertEqual(2, profiles.reserve_size(self.profile, 15, 1, 'T3'))
        self.assertEqual(1, profiles.reserve_size(self.profile, 15, 1, 'T5'))
        self.assertEqual(5, profiles.reserve_size(self.profile, 14, 5, 'T6'))
        self.assertEqual(5, profiles.t6_reserve(self.profile))
        self.assertEqual(['ок', 'ка', 'ние', 'ик', 'ина'], profiles.t5_endings(self.profile))

    def test_legacy_thirty_profile_is_still_the_default(self):
        plan = generation.schedule(generation.load_config())
        self.assertEqual(100, sum(len(row['tasks']) for row in plan))
        self.assertEqual(30, len(plan))
        self.assertEqual('release-30', profiles.load()['legacy_default']['id'])
        simulation_config = simulation.load_config()
        self.assertEqual(30, len(simulation_config['targets']))
        self.assertEqual(30, generation.selection.load_config()['levels'])

    def test_diagnostics_has_profile_id_sha_and_source_target_thresholds(self):
        report = profiles.diagnostics('short15-release-v1')
        self.assertEqual('campaign-profile-diagnostic-v1', report['schema'])
        self.assertEqual('short15-release-v1', report['profile']['id'])
        self.assertEqual(49, report['profile']['max_stars'])
        self.assertEqual(profiles.digest(self.profile), report['profile']['digest'])
        for name in ('campaign_profiles', 'level_profile_config', 'categories', 'words', 'levels'):
            self.assertRegex(report['source_sha256'][name], r'^[0-9a-f]{64}$')
        self.assertEqual({'min_length': 8, 'max_length': 13}, report['thresholds']['source'])
        self.assertEqual(3, report['thresholds']['target']['answer_min_length'])
        self.assertEqual(4, report['thresholds']['target']['counted_min_length'])
        self.assertEqual('profile_ready_for_official_gen02', report['official_gen02']['status'])
        self.assertFalse(report['official_gen02']['campaign_validated'])

    def test_unknown_profile_and_tampered_profile_are_rejected(self):
        with self.assertRaises(ValueError):
            profiles.get('missing')
        broken = deepcopy(self.profile)
        broken['max_stars'] = 50
        with self.assertRaises(ValueError):
            profiles.validate_profile(broken)

    def test_profile_file_sha_is_stable_and_recorded(self):
        first = profiles.profiles_file_sha256()
        second = profiles.profiles_file_sha256()
        self.assertEqual(first, second)
        self.assertRegex(first, r'^[0-9a-f]{64}$')
        self.assertEqual(self.profile, json.loads((TOOLS / 'campaign_profiles.json').read_text())['profiles']['short15-release-v1'])


if __name__ == '__main__':
    unittest.main()
