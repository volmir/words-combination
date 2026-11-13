"""GEN-01: hand-counted synthetic words; no external dictionary text or network."""
from copy import deepcopy
import gzip
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import pick_level_words as profiles
import assess_word_suitability as semantic
import clean_dictionary as clean
from game_data_providers import json_digest
from game_data_quality import normalize_form


class LevelProfilesTest(unittest.TestCase):
    def setUp(self):
        self.config = profiles.load_config(profiles.CONFIG)
        # самолетка has two а, but only one м and one о.
        self.words = {'самолётка', 'самолёт', 'самокат', 'ёлка', 'елка', 'атом', 'масло',
                      'лето', 'тело', 'ком', 'кот', 'ток', 'мама', 'молот', 'атака'}
        self.words = set(map(normalize_form, self.words))
        self.metadata = {w: {'noun': True, 'labels': [],
                            'observed_frequency': {'frequency': 100, 'freq_rank': 1, 'status': 'observed'},
                            'category': 'other', 'category_source': 'unknown:no_thematic_evidence'} for w in self.words}
        self.metadata['елка'].update(category='plants', category_source='rules:synthetic')
        self.assessments = {(w, role): {'assessment': {'status': 'suitable'}, 'assessment_source': 'rules'}
                            for w in self.words for role in ('source', 'target')}
        self.assessments['самокат', 'target']['assessment']['status'] = 'insufficient_data'
        self.hints = {w: {'useful': w in {'елка', 'самолет', 'самокат'}} for w in self.words}

    def build(self):
        return profiles.build_profiles(self.words, self.metadata, self.assessments, self.hints,
                                       [{'word': 'Самолётка', 'index': 2}, {'word': 'самолетка', 'index': 7}], self.config)

    def test_profile_matches_hand_calculation(self):
        row = self.build()['самолетка']
        expected = ['атом', 'елка', 'ком', 'кот', 'лето', 'масло', 'самокат', 'самолет', 'тело', 'ток']
        self.assertEqual(expected, row['answers'])
        self.assertEqual(10, row['all']['count'])
        self.assertEqual(7, row['all']['count_4plus'])
        self.assertEqual(9, row['recognizable']['count'])
        self.assertEqual(6, row['recognizable']['count_4plus'])
        self.assertEqual({'3': 3, '4': 4, '5': 1, '7': 2}, row['all']['lengths'])
        self.assertEqual({'а': 1, 'е': 1, 'к': 2, 'л': 1, 'м': 1, 'с': 1, 'т': 2}, row['recognizable']['initial'])
        self.assertEqual({'а': 1, 'е': 1, 'л': 1, 'м': 1, 'с': 1, 'т': 1}, row['recognizable']['initial_4plus'])
        self.assertEqual(5, row['all']['containing']['а'])  # а in самокат counted once.
        self.assertEqual(4, row['recognizable']['containing_4plus']['а'])
        self.assertEqual({'4': 1, '5': 1}, row['all']['suffix_lengths']['ло'])
        self.assertEqual({'4': 1}, row['all']['suffix_lengths']['елка'])
        self.assertEqual({'plants': 1}, row['recognizable']['themes'])
        self.assertEqual(['елка', 'самолет'], row['riddle_targets'])
        self.assertEqual([2, 7], row['previous_source_levels'])
        self.assertNotIn('самолетка', row['answers'])
        self.assertNotIn('мама', row['answers'])
        self.assertNotIn('молот', row['answers'])
        self.assertNotIn('атака', row['answers'])

    def test_multiset_anagrams_normalization_and_deduplication(self):
        index = profiles.MultisetIndex(['ёлка', 'елка', 'кола', 'лака', 'елкаа', 'акел'])
        self.assertEqual(['акел'], index.search('ёлка'))
        self.assertEqual(['акел', 'елка', 'лака'], index.search('ёлкаа'))

    def test_index_matches_independent_counter_oracle(self):
        index = profiles.MultisetIndex(self.words)
        from collections import Counter
        for source in self.words:
            expected = sorted(w for w in self.words if w != source and not (Counter(w) - Counter(source)))
            self.assertEqual(expected, index.search(source))

    def test_frequency_missing_is_corpus_status_and_never_ai_frequency(self):
        self.metadata['елка']['observed_frequency'].update(frequency=0, freq_rank=None, status='not_observed_in_this_corpus')
        self.assessments['елка', 'target']['assessment_source'] = 'ai'
        row = self.build()['самолетка']
        self.assertIn('елка', row['answers'])
        self.assertNotIn('елка', row['recognizable_answers'])
        self.assertNotIn('елка', row['riddle_targets'])
        self.assertEqual(0, self.metadata['елка']['observed_frequency']['frequency'])

    def test_minimum_frequency_boundary(self):
        self.metadata['елка']['observed_frequency']['frequency'] = 9
        self.assertNotIn('елка', self.build()['самолетка']['recognizable_answers'])
        self.metadata['елка']['observed_frequency']['frequency'] = 10
        self.assertIn('елка', self.build()['самолетка']['recognizable_answers'])

    def test_owner_exclusions_do_not_change_general_answers(self):
        banned = 'брудершафт'
        source = 'брудершафты'
        for w in (banned, source):
            self.words.add(w)
            self.metadata[w] = deepcopy(self.metadata['атом'])
            for role in ('source', 'target'):
                self.assessments[w, role] = {'assessment': {'status': 'suitable'}}
            self.hints[w] = {'useful': True}
        result = self.build()
        self.assertNotIn(banned, result)
        self.assertIn(banned, result[source]['answers'])
        self.assertNotIn(banned, result[source]['assigned_targets'])
        self.assertNotIn(banned, result[source]['riddle_targets'])

    def test_preferred_source_bypass_never_applies_to_answers(self):
        preferred = 'селитроварня'
        self.words.add(preferred)
        self.metadata[preferred] = deepcopy(self.metadata['атом'])
        self.metadata[preferred]['labels'] = ['historical']
        self.metadata[preferred]['observed_frequency'].update(frequency=0, freq_rank=None)
        for role in ('source', 'target'):
            self.assessments[preferred, role] = {'assessment': {'status': 'suitable'}}
        row = self.build()[preferred]
        self.assertTrue(row['source_eligible'])
        self.assertIsNotNone(row['bypass'])
        self.assertNotIn(preferred, row['assigned_targets'])
        self.metadata['елка']['observed_frequency']['frequency'] = 0
        self.assertNotIn('елка', self.build()['самолетка']['recognizable_answers'])
        self.metadata[preferred]['labels'] = ['religious']
        self.assertFalse(self.build()[preferred]['source_eligible'])

    def test_labels_require_semantics_and_diminutives_are_hard_excluded(self):
        for label in ('historical', 'religious', 'jargon', 'derived_feminine', 'abbreviation', 'diminutive'):
            f = {'noun': True, 'role_decision': 'keep', 'reachable': True,
                 'frequency': 10000, 'freq_rank': 1, 'labels': [label]}
            item = semantic.input_items({'schema': 'word-suitability-input-v1', 'candidates': [
                {'word': 'образец', 'role': 'target', 'features': f}]})[0]
            self.assertIsNone(semantic.rule_decision(item))
        cleanup = clean.load_config(TOOLS / 'selection_config.json')
        record = {'senses': [{'reasons': ['diminutive'], 'specialist': False}], 'labels': []}
        self.assertIn('productive_diminutive', profiles.labels_for('образочек', record, cleanup))
        self.assertEqual([], profiles.labels_for('ручка', record, cleanup))

    def test_structured_religious_label_cannot_be_lost_in_source_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'dump.jsonl'
            entry = {'word': 'селитроварня', 'pos': 'noun', 'lang_code': 'ru',
                     'tags': ['religious'], 'senses': [{'tags': ['historical', 'feminine-of']}]}
            dump.write_text(json.dumps(entry), encoding='utf-8')
            records = {'селитроварня': {'senses': [{'reasons': ['restricted_usage'], 'specialist': False}], 'labels': []}}
            profiles.preserve_role_labels(dump, records)
            labels = profiles.labels_for('селитроварня', records['селитроварня'],
                                         clean.load_config(TOOLS / 'selection_config.json'))
            self.assertEqual(['derived_feminine', 'historical', 'religious', 'restricted_usage'], labels)
            self.assertFalse(set(labels) <= semantic.PREFERRED_SOURCE_LABELS)

    def document(self, labels=()):
        return {'schema': 'word-suitability-input-v1', 'candidates': [{'word': 'образец', 'role': 'target',
            'features': {'noun': True, 'role_decision': 'keep', 'reachable': True,
                         'frequency': 100, 'freq_rank': 1, 'labels': list(labels)}}]}

    def test_semantic_rules_pending_and_validated_ai(self):
        document = self.document(['religious'])
        self.assertEqual('pending', profiles.assessments_for(document)['образец', 'target']['assessment_source'])
        item = semantic.input_items(document)[0]
        result = semantic.apply_decisions([item], {item['id']: semantic.decision(item, 'suitable', 'synthetic')},
                                          {'provider': 'test', 'model': 'test'})
        result['input_sha256'] = json_digest(document)
        self.assertEqual('ai', profiles.assessments_for(document, result)['образец', 'target']['assessment_source'])
        stale = deepcopy(document)
        stale['candidates'][0]['features']['frequency'] = 101
        with self.assertRaises(ValueError):
            profiles.assessments_for(stale, result)
        result['assessments'][0]['assessment']['status'] = 'unsuitable'
        with self.assertRaises(ValueError):
            profiles.assessments_for(document, result)

    def test_selective_semantic_output_keeps_unrequested_pairs_pending(self):
        document = {'schema': 'word-suitability-input-v1', 'candidates': [
            self.document(['religious'])['candidates'][0],
            dict(self.document(['religious'])['candidates'][0], word='портал')]}
        item = semantic.input_items(document)[0]
        selected = semantic.apply_decisions([item], {
            item['id']: semantic.decision(item, 'suitable', 'Синтетическая оценка')},
            {'provider': 'test', 'model': 'test'})
        selected.update(mode='selective', input_sha256=json_digest(document))
        assessed = profiles.assessments_for(document, selected)
        self.assertEqual('ai', assessed['образец', 'target']['assessment_source'])
        self.assertEqual('suitable', assessed['образец', 'target']['assessment']['status'])
        self.assertEqual('pending', assessed['портал', 'target']['assessment_source'])
        self.assertEqual('insufficient_data', assessed['портал', 'target']['assessment']['status'])
        selected['mode'] = 'full'
        with self.assertRaises(ValueError):
            profiles.assessments_for(document, selected)

    def test_cache_is_deterministic_and_detects_corruption(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'cache.gz'
            payload = {'word': 'елка'}
            body = dict(schema=profiles.SCHEMA, inputs={'words': 'hash'}, payload=payload,
                        payload_sha256=json_digest(payload))
            profiles.write_cache(path, body)
            first = path.read_bytes()
            profiles.write_cache(path, body)
            self.assertEqual(first, path.read_bytes())
            self.assertEqual(body, profiles.read_cache(path, body['inputs']))
            with self.assertRaises(ValueError):
                profiles.read_cache(path, {'words': 'changed'})
            body['payload']['word'] = 'изменено'
            profiles.write_cache(path, body)
            with self.assertRaises(ValueError):
                profiles.read_cache(path, body['inputs'])

    def test_length_buckets_survive_json_cache_roundtrip(self):
        metadata = {'аааааааааа': self.metadata['атом'], 'атом': self.metadata['атом']}
        measured = profiles.metrics(list(metadata), metadata, self.config)
        self.assertEqual({'4': 1, '10': 1}, measured['lengths'])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'cache.gz'
            body = dict(schema=profiles.SCHEMA, inputs={}, payload=measured,
                        payload_sha256=json_digest(measured))
            profiles.write_cache(path, body)
            self.assertEqual(body, profiles.read_cache(path, {}))

    def test_each_input_content_invalidates_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            names = ['words', 'frequency', 'categories', 'hints', 'suitability', 'levels', 'config',
                     'dump', 'database', 'manifest', 'code', 'vectors', 'core_processor']
            paths = {name: Path(directory) / name for name in names}
            for path in paths.values():
                path.write_text('original')
            initial = profiles.fingerprint(paths)
            for name, path in paths.items():
                path.write_text('changed')
                changed = profiles.fingerprint(paths)
                self.assertNotEqual(json_digest(initial), json_digest(changed), name)
                self.assertEqual({name}, {k for k in changed if initial[k] != changed[k]})
                path.write_text('original')

    def test_real_db_export_ignores_guessable_and_blocks_owner_words(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database, words, output = root / 'test.db', root / 'words.txt', root / 'input.jsonl'
            words.write_text('елка\nбрудершафт\n', encoding='utf-8')
            with sqlite3.connect(database) as db:
                db.execute('CREATE TABLE word_metadata(word TEXT,forms TEXT,related TEXT)')
                db.execute('CREATE TABLE definitions(id INTEGER,word TEXT,ord INTEGER,text TEXT,guessable INTEGER)')
                for i, word in enumerate(('елка', 'брудершафт')):
                    db.execute('INSERT INTO word_metadata VALUES (?, ?, ?)', (word, '[]', '[]'))
                    db.execute('INSERT INTO definitions VALUES (?, ?, 1, ?, 0)',
                               (i, word, 'Синтетический текст для проверки экспорта.'))
            profiles.export_hint_inputs(database, words, output)
            rows = [json.loads(line) for line in output.read_text().splitlines()]
            by_word = {r['word']: r for r in rows[1:]}
            self.assertEqual(1, len(by_word['елка']['definitions']))
            self.assertEqual([], by_word['брудершафт']['definitions'])
            self.assertIn('processor_sha256', rows[0])

    def test_jvm_hints_require_current_inputs_and_full_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'jvm.jsonl'
            header = {'schema': 'definition-hint-audit-v1', 'database_sha256': 'test', 'processor_sha256': {'a': 'b'}}
            rows = [header, {'word': 'елка', 'hint': 'Синтетический текст подсказки.'}]
            path.write_text('\n'.join(map(json.dumps, rows)))
            self.assertTrue(profiles.read_hints(path, header, {'елка'})['елка']['useful'])
            with self.assertRaises(ValueError):
                profiles.read_hints(path, dict(header, database_sha256='other'), {'елка'})
            with self.assertRaises(ValueError):
                profiles.read_hints(path, header, {'елка', 'атом'})
            rows.append(rows[1])
            path.write_text('\n'.join(map(json.dumps, rows)))
            with self.assertRaises(ValueError):
                profiles.read_hints(path, header, {'елка'})

    def test_full_pipeline_on_synthetic_db_and_warm_cache(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            words, dump, frequency = root / 'words.txt', root / 'dump.jsonl', root / 'frequency.txt'
            universe, database, manifest = root / 'universe.txt', root / 'defs.db', root / 'manifest.json'
            all_words = self.words | semantic.PREFERRED_SOURCES
            words.write_text('\n'.join(sorted(all_words)) + '\n', encoding='utf-8')
            universe.write_bytes(words.read_bytes())
            with dump.open('w', encoding='utf-8') as target:
                for word in sorted(all_words):
                    target.write(json.dumps({'word': word, 'lang': 'Русский', 'lang_code': 'ru', 'pos': 'noun',
                        'forms': [{'form': 'ёлки'}] if word == 'елка' else [],
                        'senses': [{'glosses': ['Синтетическое описание проверочного объекта.']}]}, ensure_ascii=False) + '\n')
            frequency.write_text('\n'.join(w + ' 100' for w in sorted(all_words)) + '\nёлки 7\n', encoding='utf-8')
            with sqlite3.connect(database) as db:
                db.execute('CREATE TABLE word_metadata(word TEXT,forms TEXT,related TEXT)')
                db.execute('CREATE TABLE definitions(id INTEGER,word TEXT,ord INTEGER,text TEXT,guessable INTEGER)')
                for i, word in enumerate(sorted(all_words)):
                    db.execute('INSERT INTO word_metadata VALUES (?, ?, ?)', (word, '[]', '[]'))
                    db.execute('INSERT INTO definitions VALUES (?, ?, 1, ?, 0)',
                               (i, word, 'Синтетическое описание проверочного объекта.'))
            digest = profiles.dictionary.sha256_file
            manifest.write_text(json.dumps({'source': {'sha256_input': digest(dump)},
                'sha256_words_txt': digest(words), 'definitions': {'sha256': digest(database)},
                'selection': {'input_words_sha256': digest(universe)}}))
            levels, hints = root / 'levels.json', root / 'hints.jsonl'
            levels.write_text('[{"word":"самолетка","index":1}]', encoding='utf-8')
            with hints.open('w', encoding='utf-8') as target:
                target.write(json.dumps(profiles.hint_header(database, words)) + '\n')
                for word in sorted(all_words):
                    target.write(json.dumps({'word': word, 'hint': 'Синтетическая подсказка проверочного объекта.'}) + '\n')
            args = Namespace(words=words, manifest=manifest, database=database, dump=dump,
                frequency_list=frequency, frequency_universe=universe, config=profiles.CONFIG,
                selection_config=TOOLS / 'selection_config.json', previous_levels=levels,
                categories=None, suitability=None, hints=hints, cache=root / 'cache',
                reports=root / 'reports', prepare_only=False)
            result = profiles.run(args)
            self.assertFalse(result['cache_hit'])
            cached = profiles.read_cache(Path(result['cache_path']), result['inputs'])['payload']
            self.assertEqual(107, cached['words']['елка']['observed_frequency']['frequency'])
            row = cached['profiles']['самолетка']
            self.assertEqual(10, row['all']['count'])
            self.assertEqual(7, row['recognizable']['count_4plus'])
            self.assertEqual([1], row['previous_source_levels'])
            before = Path(result['cache_path']).read_bytes()
            warm = profiles.run(args)
            self.assertTrue(warm['cache_hit'])
            self.assertEqual(before, Path(warm['cache_path']).read_bytes())
            self.assertTrue((args.reports / 'suitability_input.json').exists())
            self.assertIn('Наблюдаемая частота', (args.reports / 'LEVEL_PROFILES.md').read_text())
            args.prepare_only = True
            self.assertEqual(len(all_words), profiles.run(args)['prepared_words'])

    def test_config_requires_units_rationale_and_campaign_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.json'
            document = json.loads(profiles.CONFIG.read_text())
            del document['suffix_lengths']['unit']
            path.write_text(json.dumps(document))
            with self.assertRaises(ValueError):
                profiles.load_config(path)
            document = json.loads(profiles.CONFIG.read_text())
            document['counted_min_length']['value'] = 3
            path.write_text(json.dumps(document))
            with self.assertRaises(ValueError):
                profiles.load_config(path)


if __name__ == '__main__':
    unittest.main()
