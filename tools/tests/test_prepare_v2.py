"""PIPE-01: synthetic inputs, real local stages; no SDK, provider or network."""
from contextlib import ExitStack
import gzip
import json
import multiprocessing
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace as NS
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_v2 as pipe
from game_data_providers import CategoryProvider, atomic_json, json_digest


def publish_worker(candidate, output, start):
    start.wait(10)
    pipe.game.publish(Path(candidate), Path(output), ('words.txt', 'manifest.json'))


def prepare_worker(arguments, start):
    start.wait(10)
    pipe.prepare(arguments)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.old, self.new = self.root / 'old', self.root / 'new'
        for directory, value in ((self.old, 'old'), (self.new, 'new')):
            directory.mkdir()
            (directory / 'words.txt').write_text(value)
            (directory / 'manifest.json').write_text(value)
        self.files = ('manifest.json', 'words.txt')  # deliberately wrong order

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.old.iterdir()}

    def test_manifest_is_last_and_repeat_is_identical(self):
        replace, calls = os.replace, []
        def record(source, target):
            if Path(target).parent == self.old:
                calls.append(Path(target).name)
            return replace(source, target)
        with patch.object(pipe.game.os, 'replace', side_effect=record):
            pipe.game.publish(self.new, self.old, self.files)
        self.assertEqual(['words.txt', 'manifest.json'], calls)
        before = self.snapshot()
        pipe.game.publish(self.new, self.old, self.files)
        self.assertEqual(before, self.snapshot())

    def test_exception_and_keyboard_interrupt_rollback_all_files(self):
        for error in (OSError('injected'), KeyboardInterrupt()):
            before, replace = self.snapshot(), os.replace
            def fail(source, target):
                if Path(source).name == 'manifest.json' and Path(target).parent == self.old:
                    raise error
                return replace(source, target)
            with patch.object(pipe.game.os, 'replace', side_effect=fail):
                with self.assertRaises(type(error)):
                    pipe.game.publish(self.new, self.old, self.files)
            self.assertEqual(before, self.snapshot())

    def test_kill_between_replacements_recovers_before_reading_bad_candidate(self):
        before = self.snapshot()
        script = '''
import os, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import build_game_data as game
replace = os.replace
output = Path(sys.argv[3])
def crash(source, target):
    replace(source, target)
    if Path(target) == output / 'words.txt':
        os._exit(73)
game.os.replace = crash
game.publish(Path(sys.argv[2]), output, ('words.txt', 'manifest.json'))
'''
        result = subprocess.run([sys.executable, '-c', script, str(pipe.TOOLS), str(self.new), str(self.old)])
        self.assertEqual(73, result.returncode)
        self.assertNotEqual(before, self.snapshot())
        # A missing new candidate still recovers the last complete working set.
        with self.assertRaises(FileNotFoundError):
            pipe.game.publish(self.root / 'missing', self.old, self.files)
        self.assertEqual(before, self.snapshot())
        pipe.game.publish(self.new, self.old, self.files)
        self.assertEqual(b'new', (self.old / 'words.txt').read_bytes())

    def test_rollback_of_new_files_and_two_simultaneous_writers(self):
        (self.new / 'extra').write_text('extra')
        replace = os.replace
        def fail(source, target):
            if Path(source).name == 'manifest.json' and Path(target).parent == self.old:
                raise OSError('injected')
            return replace(source, target)
        with patch.object(pipe.game.os, 'replace', side_effect=fail):
            with self.assertRaises(OSError):
                pipe.game.publish(self.new, self.old, ('extra', *self.files))
        self.assertFalse((self.old / 'extra').exists())
        alternate = self.root / 'alternate'
        alternate.mkdir()
        for n in self.files:
            (alternate / n).write_text('alternate')
        ctx = multiprocessing.get_context('fork')
        start = ctx.Event()
        jobs = [ctx.Process(target=publish_worker, args=(str(p), str(self.old), start))
                for p in (self.new, alternate)]
        for job in jobs:
            job.start()
        start.set()
        for job in jobs:
            job.join(10)
            if job.is_alive():
                job.kill()
                self.fail('publication deadlock')
            self.assertEqual(0, job.exitcode)
        self.assertEqual((self.old / 'words.txt').read_bytes(), (self.old / 'manifest.json').read_bytes())

    def test_stale_baseline_cannot_overwrite_newer_install_but_repeat_is_safe(self):
        baseline = pipe.sha(self.old / 'manifest.json')
        pipe.game.publish(self.new, self.old, self.files, expected_manifest_sha256=baseline)
        pipe.game.publish(self.new, self.old, self.files, expected_manifest_sha256=baseline)
        (self.new / 'words.txt').write_text('different')
        with self.assertRaisesRegex(ValueError, 'изменился'):
            pipe.game.publish(self.new, self.old, self.files, expected_manifest_sha256=baseline)
        self.assertEqual(b'new', (self.old / 'words.txt').read_bytes())


class ConvergenceTest(unittest.TestCase):
    def test_only_selected_exceptions_survive_and_final_bytes_are_rebuilt(self):
        visits = []
        def build(words, number):
            visits.append(set(words))
            return {'selected'}, set(words)
        result, history = pipe.converge({'base'}, {'selected': [], 'unused': []}, build, 4)
        self.assertEqual([{'base'}, {'base', 'selected'}], visits)
        self.assertEqual(visits[-1], result)
        self.assertEqual(2, len(history))

    def test_cycle_limit_and_unjustified_source_are_diagnosed(self):
        with self.assertRaisesRegex(ValueError, 'Цикл'):
            pipe.converge({'base'}, {'a': [], 'b': []},
                          lambda words, n: ({'a' if 'a' not in words else 'b'}, None), 4)
        with self.assertRaisesRegex(ValueError, 'лимит'):
            pipe.converge({'base'}, {'a': []}, lambda words, n: ({'a'}, None), 1)
        with self.assertRaisesRegex(ValueError, 'необоснованный'):
            pipe.converge({'base'}, {}, lambda words, n: ({'other'}, None), 4)

    def test_stale_snapshot_is_never_passed_as_current_and_pending_blocks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            atomic_json(root / 'input.json', {'schema': 'word-category-index-v1', 'word_metadata': {}})
            atomic_json(root / 'old.json', {'input_sha256': 'old', 'context': {'model': 'fixture'}})
            args = NS(snapshot=root / 'old.json', input=root / 'input.json', model=None,
                      cache=root / 'cache', taxonomy=pipe.TOOLS / 'categories.json', min_confidence=0.8)
            seen = []
            pipe.automatic_decisions(lambda a: seen.append(a.snapshot) or {'pending': 0}, args)
            self.assertEqual([None], seen)
            with self.assertRaisesRegex(ValueError, 'Не завершена'):
                pipe.automatic_decisions(lambda a: {'pending': 1}, args)

    def test_restore_cache_and_dump_fallback_use_same_pinned_universe(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dump = root / 'dump.jsonl'
            entries = [dict(word=w, lang='Русский', lang_code='ru', pos='noun',
                            senses=[{'glosses': ['Синтетический предмет.']}]) for w in ('кот', 'ток')]
            dump.write_text('\n'.join(json.dumps(e, ensure_ascii=False) for e in entries))
            manifest = {'source': {'sha256_input': pipe.sha(dump)}, 'selection': {
                'input_words': 2, 'input_words_sha256': __import__('hashlib').sha256(pipe.canonical({'кот', 'ток'})).hexdigest()}}
            output = root / 'words.txt'
            pipe.restore_source(dump, manifest, None, root, output)
            first = output.read_bytes()
            atomic_json(root / ('source-vocabulary-' + pipe.sha(dump) + '.json'),
                        {'words': ['ток', 'кот'], 'sha256': manifest['selection']['input_words_sha256']})
            with patch.object(pipe.dictionary, 'wiktionary_wordlist', side_effect=AssertionError('cache unused')):
                pipe.restore_source(dump, manifest, None, root, output)
            self.assertEqual(first, output.read_bytes())
            manifest['selection']['input_words'] = 3
            with self.assertRaisesRegex(ValueError, 'Базовый снимок'):
                pipe.restore_source(dump, manifest, None, root, output)


class LocalPipelineTest(unittest.TestCase):
    """Run cleanup/DB/categories/LEX/profiles/Bloom/publish on synthetic articles.

    Only search/balancing and JVM process are substitutes: mini dictionaries
    cannot meet the production 30-level/100-star contract. Validation invocation,
    final-byte identities and failure preservation remain exercised.
    """
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.words = {'самолетка', 'паровозка', 'самолет', 'елка', 'атом', 'лето'} | pipe.suitability.PREFERRED_SOURCES
        self.dump = self.root / 'dump.jsonl'
        entries = [dict(word=w, lang='Русский', lang_code='ru', pos='noun', senses=[{
            'glosses': ['Синтетический предмет для проверки конвейера.'],
            'tags': ['rare'] if w in {'самолетка', 'паровозка'} else []}]) for w in sorted(self.words)]
        self.dump.write_text('\n'.join(json.dumps(e, ensure_ascii=False) for e in entries))
        self.source = self.root / 'source.txt'
        self.source.write_bytes(pipe.canonical(self.words))
        self.freq = self.root / 'freq.txt'
        self.freq.write_text('\n'.join(w + (' 50' if w in {'самолетка', 'паровозка'} else ' 1000') for w in sorted(self.words)))
        self.legacy = self.root / 'legacy.json'
        atomic_json(self.legacy, [dict(index=i, word=w, answers=999, words=999, letter='я', letterCount=999)
                                 for i, w in enumerate(('самолетка', 'паровозка'), 1)])
        self.installed = self.root / 'installed'
        self.installed.mkdir()
        self.manifest = self.installed / 'manifest.json'
        atomic_json(self.manifest, dict(source={'sha256_input': pipe.sha(self.dump), 'dump_date': '2026-11-01'},
            selection=dict(input_words=len(self.words), input_words_sha256=pipe.sha(self.source),
                           frequency_source={'snapshot': pipe.frequency.REVISION, 'sha256': pipe.sha(self.freq)})))
        self.args = NS(dump=self.dump, manifest=self.manifest, source_words=self.source, frequency_list=self.freq,
            cache=self.root / 'cache', installed=self.installed, package=self.root / 'ready', model='fixture',
            category_snapshot=None, suitability_snapshot=None, allow_network=False, max_rounds=4,
            java='unavailable', jvm_classpath=None)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(pipe, 'LEGACY', self.legacy))
        self.stack.enter_context(patch.object(pipe, 'processor_inputs', return_value={'fixture': 'v1'}))
        self.allow_fixture_generation = True
        def cached_exchange(provider, items):
            return self.exchange(provider, items, self.allow_fixture_generation)
        self.stack.enter_context(patch.object(CategoryProvider, 'exchange', cached_exchange))
        self.stack.enter_context(patch.object(pipe, 'jvm_hints', side_effect=self.hints))
        self.stack.enter_context(patch.object(pipe.selection, 'run', side_effect=self.select))
        self.stack.enter_context(patch.object(pipe.campaign, 'run', side_effect=self.generate))
        self.stack.enter_context(patch.object(pipe.rebalance, 'run', side_effect=self.balance))
        self.validation = self.stack.enter_context(patch.object(pipe.validation, 'run', side_effect=self.validate))
        self.stack.enter_context(patch.object(pipe.rebalance, 'models', return_value=[object()]))
        self.simulation = self.stack.enter_context(patch.object(pipe.simulation, 'simulate', return_value={'deviations': []}))

    @staticmethod
    def exchange(provider, items, generate=True):
        answers = {}
        for item in items:
            previous = provider.read(item)
            if previous is not None:
                answers[item['id']] = previous
                continue
            if not generate:
                continue
            result = (pipe.suitability.decision(item, 'suitable', 'Синтетическая оценка')
                      if isinstance(provider, pipe.suitability.SuitabilityProvider) else
                      dict(id=item['id'], category='other', confidence=1.0))
            # Use the real validated per-item cache, without any provider process.
            provider.store(item, result)
            answers[item['id']] = result
        pending = len(items) - len(answers)
        return answers, pending, 'Нет офлайн-решения' if pending else None

    @staticmethod
    def hints(args, database, words, directory):
        target = directory / 'hints.jvm.jsonl'
        lines = [pipe.profiles.hint_header(database, words)]
        lines.extend(dict(word=w, hint='Синтетическая подсказка.') for w in pipe.dictionary.read_list(words))
        target.write_text('\n'.join(json.dumps(row, ensure_ascii=False) for row in lines))
        return target

    @staticmethod
    def select(args):
        # Real GEN-01 must contain both old source candidates, even when neither
        # has been admitted to words.txt. Source-only candidates cannot be targets.
        doc = pipe.selection.load_snapshot(args.profiles, args.words, args.previous_levels)
        self_profiles = doc['payload']['profiles']
        assert 'самолетка' in self_profiles and 'паровозка' in self_profiles
        assert self_profiles['самолетка']['source_eligible']
        assert not any(r['word'] == 'самолетка' and r['role'] == 'target' and r['assessment']['status'] == 'suitable'
                       for r in doc['payload']['assessments'])
        return {'installable': True, 'slots': [{'word': 'самолетка'}]}

    @staticmethod
    def generate(args):
        data = {'version': 2, 'countMinLength': 4, 'stages': [{'number': 1, 'firstLevel': 1, 'lastLevel': 1}],
                'levels': [dict(number=1, word='самолетка', stage=1, requiredStars=1,
                                tasks=[dict(id='l1-t1', type='T1', n=1)])]}
        atomic_json(args.output / 'campaign.json', data)
        return {'status': 'valid'}

    @staticmethod
    def balance(args):
        data = pipe.read_json(args.campaign)
        atomic_json(args.output / 'balanced_campaign.json', data)
        return {'installable': True, 'campaign': data}

    @staticmethod
    def validate(args):
        manifest = pipe.read_json(args.manifest)
        assert pipe.sha(args.words) == manifest['sha256_words_txt']
        assert pipe.sha(args.database) == manifest['definitions']['sha256']
        return pipe.read_json(args.campaign), {'installable': True, 'jvm_checked': True,
            'unreachable': [], 'balance_deviations': [], 'parity_deviations': []}

    def test_prepare_repeat_install_and_fresh_cache_reproduce_bytes(self):
        before = self.manifest.read_bytes()
        result = pipe.prepare(self.args)
        self.assertEqual(2, result['rounds'])
        self.assertEqual(before, self.manifest.read_bytes())
        ready = {n: (self.args.package / n).read_bytes() for n in pipe.PACKAGE_ARTIFACTS}
        words = set(pipe.dictionary.read_list(self.args.package / 'words.txt'))
        self.assertIn('самолетка', words)
        self.assertNotIn('паровозка', words)
        manifest = pipe.read_json(self.args.package / 'manifest.json')
        self.assertEqual({'самолетка'}, set(manifest['selection']['reference_checks']['level_exceptions']))
        self.assertIn('campaign_source', manifest)
        self.assertNotIn('license', manifest['campaign_source'])  # do not invent MIT for v2
        self.assertGreater(self.validation.call_count, 0)
        self.assertGreater(self.simulation.call_count, 0)
        pipe.prepare(self.args)
        self.assertEqual(ready, {n: (self.args.package / n).read_bytes() for n in pipe.PACKAGE_ARTIFACTS})
        self.args.category_snapshot = self.root / 'category-decisions.json'
        self.args.suitability_snapshot = self.root / 'suitability-decisions.json'
        self.args.category_snapshot.write_bytes(ready['category_decisions.json'])
        self.args.suitability_snapshot.write_bytes(ready['suitability_decisions.json'])
        self.args.cache = self.root / 'fresh-cache'
        self.allow_fixture_generation = False
        pipe.prepare(self.args)
        self.assertEqual(ready, {n: (self.args.package / n).read_bytes() for n in pipe.PACKAGE_ARTIFACTS})
        installing = NS(package=self.args.package, out=self.installed, java='unavailable', jvm_classpath=None)
        pipe.install(installing)
        pipe.install(installing)
        self.assertEqual(ready, {n: (self.installed / n).read_bytes() for n in pipe.PACKAGE_ARTIFACTS})
        # A new baseline must not increase user_version for identical final data.
        before_db = (self.installed / 'definitions.db').read_bytes()
        self.args.cache = self.root / 'cache-after-install'
        pipe.prepare(self.args)
        self.assertEqual(before_db, (self.args.package / 'definitions.db').read_bytes())

    def test_failed_rerun_and_corrupted_ready_package_preserve_working_data(self):
        pipe.prepare(self.args)
        ready = {n: (self.args.package / n).read_bytes() for n in pipe.PACKAGE_ARTIFACTS}
        with patch.object(pipe.rebalance, 'run', return_value={'installable': False}):
            with self.assertRaisesRegex(ValueError, 'GEN-04/GEN-05'):
                pipe.prepare(self.args)
        self.assertEqual(ready, {n: (self.args.package / n).read_bytes() for n in pipe.PACKAGE_ARTIFACTS})
        (self.args.package / 'words.txt').write_text('повреждение\n')
        before = self.manifest.read_bytes()
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            pipe.install(NS(package=self.args.package, out=self.installed, java='unavailable', jvm_classpath=None))
        self.assertEqual(before, self.manifest.read_bytes())

    def test_changed_frequency_and_dump_are_rejected_before_generation(self):
        self.freq.write_text('кот 12\n')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            pipe.prepare(self.args)
        self.dump.write_text('{}\n')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            pipe.prepare(self.args)

    def test_updated_frequency_pin_invalidates_profiles_and_frozen_decisions_automatically(self):
        pipe.prepare(self.args)
        package = self.args.package
        self.args.suitability_snapshot = self.root / 'frozen-suitability.json'
        self.args.suitability_snapshot.write_bytes((package / 'suitability_decisions.json').read_bytes())
        before = (package / 'lexical_profiles.json.gz').read_bytes()
        self.freq.write_text(self.freq.read_text().replace('атом 1000', 'атом 900'))
        manifest = pipe.read_json(self.manifest)
        manifest['selection']['frequency_source']['sha256'] = pipe.sha(self.freq)
        # New verified inputs can be pinned outside data/. Installation still
        # compares against the actual installed baseline, not this input pin.
        self.args.manifest = self.root / 'new-input-manifest.json'
        atomic_json(self.args.manifest, manifest)
        pipe.prepare(self.args)
        self.assertNotEqual(before, (package / 'lexical_profiles.json.gz').read_bytes())
        with gzip.open(package / 'lexical_profiles.json.gz', 'rt') as stream:
            profile = json.load(stream)
        self.assertEqual(900, profile['payload']['words']['атом']['observed_frequency']['frequency'])
        self.assertEqual(pipe.sha(self.freq), profile['inputs']['frequency'])
        pipe.install(NS(package=package, out=self.installed, java='unavailable', jvm_classpath=None))

    def test_two_preparations_with_one_cache_serialize_and_keep_data_untouched(self):
        before = self.manifest.read_bytes()
        ctx = multiprocessing.get_context('fork')
        start = ctx.Event()
        jobs = [ctx.Process(target=prepare_worker, args=(self.args, start)) for _ in range(2)]
        for job in jobs:
            job.start()
        start.set()
        for job in jobs:
            job.join(30)
            if job.is_alive():
                job.kill()
                self.fail('preparation deadlock')
            self.assertEqual(0, job.exitcode)
        self.assertEqual(before, self.manifest.read_bytes())
        pipe.verify_package(self.args.package)

    def test_updated_verified_dump_rebuilds_data_and_version_without_cache_cleanup(self):
        pipe.prepare(self.args)
        pipe.install(NS(package=self.args.package, out=self.installed, java='unavailable', jvm_classpath=None))
        before = pipe.read_json(self.installed / 'manifest.json')['definitions']['user_version']
        entries = [json.loads(line) for line in self.dump.read_text().splitlines()]
        next(row for row in entries if row['word'] == 'атом')['forms'] = [{'form': 'атома'}]
        self.dump.write_text('\n'.join(json.dumps(row, ensure_ascii=False) for row in entries))
        manifest = pipe.read_json(self.manifest)
        manifest['source'].update(sha256_input=pipe.sha(self.dump), dump_date='2026-11-02')
        self.args.manifest = self.root / 'new-dump-manifest.json'
        atomic_json(self.args.manifest, manifest)
        pipe.prepare(self.args)
        updated = pipe.read_json(self.args.package / 'manifest.json')
        self.assertEqual('2026-11-02', updated['source']['dump_date'])
        self.assertEqual(pipe.sha(self.dump), updated['source']['sha256_input'])
        self.assertEqual(before + 1, updated['definitions']['user_version'])

    def test_prepare_cannot_target_data_or_installed_directory(self):
        self.args.package = self.installed
        with self.assertRaisesRegex(ValueError, 'не пишет'):
            pipe.prepare(self.args)
