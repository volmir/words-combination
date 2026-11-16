"""Synthetic fixtures contain no imported dictionary text."""
import copy
import unittest

from tools.owned_hints import check, compare_imported, targets, validate


class OwnedHintsTest(unittest.TestCase):
    def fixture(self):
        campaign = {'levels': [{'number': 1, 'word': 'лампочка', 'tasks': [
            {'id': 'a', 'type': 'T3', 'minLength': 5, 'hintTarget': 'лампа'},
            {'id': 'b', 'type': 'T4', 'targets': ['лампа']},
            {'id': 'c', 'type': 'T6', 'n': 1, 'category': 'objects', 'words': ['лампа']},
        ]}]}
        entry = dict(word='лампа', normalized='лампа', part_of_speech='noun',
                     hint='Условный предмет для синтетической проверки обработки текста.',
                     roles=targets(campaign)['лампа'], provenance='newly-authored-for-game',
                     license_review='pending-owner-approval', owner_text_approval='pending',
                     owner_license_approval='pending', review_note='Синтетический пример.')
        candidate = dict(schema='owned-game-hints', version=1, campaign_sha256='fixture',
                         status='candidate-not-installed', proposed_license='CC0-1.0', entries=[entry],
                         provenance='newly-authored-for-game', license_review='pending-owner-approval')
        metadata = {'лампа': dict(category='objects', category_source='fixture:review', forms=[], related=[])}
        return candidate, campaign, metadata

    def test_fixture_shared_word_preserves_every_role(self):
        candidate, campaign, metadata = self.fixture()
        scope = validate(candidate, campaign, 'fixture', {'лампа'}, metadata)
        self.assertEqual(1, len(scope))
        self.assertEqual(['T3', 'T4', 'T6'], [r['type'] for r in scope['лампа']])

    def test_rejects_invalid_candidates(self):
        changes = [
            lambda d: d.update(campaign_sha256='stale'),
            lambda d: d.update(version=9),
            lambda d: d['entries'].append(copy.deepcopy(d['entries'][0])),
            lambda d: d['entries'].clear(),
            lambda d: d['entries'][0].update(hint=''),
            lambda d: d['entries'][0].update(hint='Лампа служит условным предметом для проверки.'),
            lambda d: d['entries'][0].update(hint='https://example.invalid/лампа'),
            lambda d: d['entries'][0].update(hint='<b>Условный предмет для проверки</b>'),
            lambda d: d['entries'][0].update(hint='я' * 141),
            lambda d: d['entries'][0].update(license_review='approved'),
            lambda d: d['entries'][0].update(owner_text_approval='approved'),
            lambda d: d['entries'][0].update(normalized='Лампа'),
            lambda d: d['entries'][0].update(roles=[]),
        ]
        for mutation in changes:
            with self.subTest(mutation=changes.index(mutation)):
                candidate, campaign, metadata = self.fixture()
                mutation(candidate)
                with self.assertRaises(ValueError):
                    validate(candidate, campaign, 'fixture', {'лампа'}, metadata)

    def test_rejects_absent_word_unbuildable_and_bad_category(self):
        candidate, campaign, metadata = self.fixture()
        with self.assertRaisesRegex(ValueError, 'index'):
            validate(candidate, campaign, 'fixture', set(), metadata)
        campaign['levels'][0]['word'] = 'кот'
        with self.assertRaisesRegex(ValueError, 'buildable'):
            validate(candidate, campaign, 'fixture', {'лампа'}, metadata)
        campaign['levels'][0]['word'] = 'лампочка'
        metadata['лампа']['category'] = 'other'
        with self.assertRaisesRegex(ValueError, 'category'):
            validate(candidate, campaign, 'fixture', {'лампа'}, metadata)
        metadata['лампа']['category'] = 'objects'
        metadata['лампа']['category_source'] = 'unknown:legacy'
        with self.assertRaisesRegex(ValueError, 'Unverifiable'):
            validate(candidate, campaign, 'fixture', {'лампа'}, metadata)

    def test_forms_and_nonprefix_related_words_are_checked(self):
        candidate, campaign, metadata = self.fixture()
        metadata['лампа']['related'] = ['предмет']
        with self.assertRaisesRegex(ValueError, 'masked'):
            validate(candidate, campaign, 'fixture', {'лампа'}, metadata)

    def test_t6_pool_is_never_truncated_to_n(self):
        _, campaign, _ = self.fixture()
        campaign['levels'][0]['tasks'][-1]['words'] = ['лампа', 'полка', 'лапка']
        self.assertEqual({'лампа', 'полка', 'лапка'}, set(targets(campaign)))
        campaign['levels'][0]['tasks'][-1]['words'].append('лампа')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            targets(campaign)

    def test_copy_screening_normalization_global_matches_and_short_text(self):
        entries = [{'word': 'фикстура', 'hint': 'Ёж идёт через условный выдуманный сад сегодня.'}]
        result = compare_imported(entries, [('другая-статья', 'Введение: еж идет через условный выдуманный сад вчера.')])
        self.assertEqual(['другая-статья'], result['suspicious_matches'][0]['matched_old_words'])
        short = compare_imported([{'word': 'пример', 'hint': 'Первый условный предмет.'}],
                                 [('другая', 'Начало. Первый условный предмет. Конец.')])
        self.assertEqual(1, len(short['suspicious_matches']))
        unique = compare_imported(entries, [('пример', 'Совсем иной синтетический текст для отрицательной проверки.')])
        self.assertEqual([], unique['suspicious_matches'])
        self.assertEqual(1, unique['old_rows_checked'])

    def test_active_targets_and_generated_documents(self):
        report = check()
        self.assertEqual(39, report['unique_targets'])
        self.assertEqual(40, report['assignments'])
        self.assertEqual(11, report['t4'])
        self.assertEqual([], report['suspicious_matches'])
