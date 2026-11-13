#!/usr/bin/env python3
"""PIPE-01: prepare a reproducible v2 package, or verify and install it offline."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import sqlite3
import subprocess
import tempfile
from types import SimpleNamespace as NS

import assess_word_suitability as suitability
import build_campaign as campaign
import build_dictionary as dictionary
import build_game_data as game
import build_known_words as bloom
import build_local_definitions as definitions
import classify_word_categories as classification
import clean_dictionary as clean
from game_data_providers import atomic_json, json_digest
import pick_level_words as profiles
import prepare_frequency as frequency
import rebalance_campaign as rebalance
import select_level_words as selection
import simulate_campaign as simulation
import validate_campaign as validation

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'tools'
LEGACY = ROOT / 'core/src/test/resources/legacy_levels_v1.json'
PACKAGE_ARTIFACTS = (*clean.ARTIFACTS[:-1], 'definitions.db', 'campaign.json', 'known_words.bloom',
                     'input_manifest.json', 'lexical_profiles.json.gz', 'previous_levels.json',
                     'category_decisions.json', 'suitability_decisions.json', 'manifest.json')


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def sha(path):
    return dictionary.sha256_file(path)


def canonical(words):
    return ('\n'.join(sorted(set(words))) + '\n').encode()


def recover_ready(output):
    with game.exclusive_lock(output.parent / ('.' + output.name + '.publish.lock')):
        journal = output.parent / ('.' + output.name + '.publish-transaction')
        if journal.exists():
            game.recover_publication(output, journal)


def processor_inputs():
    paths = list(TOOLS.glob('*.py')) + list(TOOLS.glob('*.json'))
    paths += list((ROOT / 'core/src/main/kotlin/com/example/sostavslova/core').glob('*.kt'))
    paths += [ROOT / 'core/src/test/kotlin/com/example/sostavslova/core' / n for n in
              ('CampaignDatasetAudit.kt', 'DefinitionHintDatasetAudit.kt')]
    paths += [TOOLS / 'overrides/remove_definitions.txt', LEGACY, ROOT / 'docs/CAMPAIGN.md']
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted(paths)}


def restore_source(dump, manifest, supplied, cache, destination):
    """Never substitute the cleaned game vocabulary for the FREQ-01 universe."""
    expected = manifest['selection']
    cached = cache / ('source-vocabulary-' + manifest['source']['sha256_input'] + '.json')
    source = supplied or (cached if cached.exists() else None)
    if source:
        words = profiles.read_universe(source)
    else:
        words = set(dictionary.wiktionary_wordlist(dump)[0])
    data = canonical(words)
    if (len(words) != expected['input_words'] or
            hashlib.sha256(data).hexdigest() != expected['input_words_sha256']):
        raise ValueError('Базовый снимок не совпадает с закреплённым входом 113 413 слов')
    destination.write_bytes(data)
    return words


def converge(base, candidates, build, max_rounds):
    """Rebuild every dependent stage on a changed dictionary; detect oscillation."""
    words, seen, history = set(base), set(), []
    for number in range(1, max_rounds + 1):
        key = hashlib.sha256(canonical(words)).hexdigest()
        if key in seen:
            raise ValueError('Цикл отбора исходников: ' + json.dumps(history, ensure_ascii=False))
        seen.add(key)
        selected, result = build(words, number)
        missing = set(selected) - base - set(candidates)
        if missing:
            raise ValueError('Выбран необоснованный исходник вне пула: ' + ', '.join(sorted(missing)))
        final = set(base) | (set(selected) & set(candidates))
        history.append(dict(round=number, words_sha256=key, exceptions=sorted(final - base)))
        if final == words:
            return result, history
        words = final
    raise ValueError('Исчерпан лимит пересчётов словаря: ' + json.dumps(history, ensure_ascii=False))


def jvm_hints(args, database, words, directory):
    source, target = directory / 'hint_inputs.jsonl', directory / 'hints.jvm.jsonl'
    profiles.export_hint_inputs(database, words, source)
    command = ([args.java, '-cp', args.jvm_classpath,
                'com.example.sostavslova.core.DefinitionHintDatasetAudit', str(source), str(target)]
               if args.jvm_classpath else
               ['bash', str(ROOT / 'gradlew'), '--offline', ':core:auditDefinitionHints', '--console=plain',
                '--args=' + shlex.join([str(source), str(target)])])
    subprocess.run(command, cwd=ROOT, check=True)
    profiles.read_hints(target, profiles.hint_header(database, words), set(dictionary.read_list(words)))
    return target


def ai_args(args, input_path, reports, cache, snapshot):
    return NS(input=input_path, reports=reports, cache=cache, snapshot=snapshot,
              model=args.model, provider=getattr(args, 'provider', 'codex'),
              allow_network=args.allow_network, batch_size=20, timeout=120, retries=2,
              taxonomy=TOOLS / 'categories.json', min_confidence=0.8, gold=None)


def seed_snapshot(snapshot, provider, current_items):
    """Reuse only unchanged items, never the obsolete input-level decision."""
    fields = {'schema', 'context', 'taxonomy', 'input_sha256', 'items', 'results', 'complete', 'policy', 'sha256'}
    body = {key: value for key, value in snapshot.items() if key != 'sha256'}
    if set(snapshot) != fields or snapshot['sha256'] != json_digest(body):
        raise ValueError('Повреждён замороженный снимок решений')
    items, results = snapshot['items'], snapshot['results']
    for item in items:
        provider.validate_item(item, provider.taxonomy)
    identities = {r['id'] for r in results}
    if len({i['id'] for i in items}) != len(items) or type(snapshot['complete']) is not bool:
        raise ValueError('Повторные элементы/неверная полнота снимка')
    provider.validate_results({'results': results}, [i for i in items if i['id'] in identities], provider.taxonomy)
    if snapshot['complete'] != (len(results) == len(items)):
        raise ValueError('Неверная полнота замороженного снимка')
    old, answers = {i['id']: i for i in items}, {r['id']: r for r in results}
    for item in current_items:
        if old.get(item['id']) == item and item['id'] in answers:
            provider.store(item, answers[item['id']])


def automatic_decisions(run, arguments):
    """An obsolete whole snapshot is never silently replaced by partial results.

    Per-item provider caches are independently keyed by context and features.
    On a changed input import only a compatible snapshot, then rebuild missing
    decisions automatically from that cache / explicitly enabled model.
    """
    if arguments.snapshot:
        frozen = read_json(arguments.snapshot)
        incoming = read_json(arguments.input)
        snapshots = frozen['snapshots'] if frozen.get('schema') == 'v2-decision-bundle-v1' else [frozen]
        if not snapshots:
            raise ValueError('Пустой пакет замороженных решений')
        model = arguments.model or snapshots[-1].get('context', {}).get('model')
        semantic = incoming.get('schema') == 'word-suitability-input-v1'
        provider = (suitability.SuitabilityProvider(arguments.cache, model, provider=getattr(arguments, 'provider', 'codex')) if semantic else
                    classification.CategoryProvider(arguments.cache, model, classification.thematic.load_taxonomy(arguments.taxonomy),
                                                    provider=getattr(arguments, 'provider', 'codex')))
        policy = getattr(provider, 'snapshot_policy', {'min_confidence': arguments.min_confidence})
        items = ([i for i in suitability.input_items(incoming) if suitability.rule_decision(i) is None]
                 if semantic else classification.classification_items(incoming, provider.taxonomy))
        for snapshot in snapshots:
            if (snapshot.get('context') == provider.context and snapshot.get('taxonomy') == provider.taxonomy and
                    snapshot.get('policy') == policy and
                    snapshot.get('schema') == getattr(provider, 'snapshot_schema', classification.SNAPSHOT_SCHEMA)):
                seed_snapshot(snapshot, provider, items)
        # The stage exports a NEW envelope bound to the current complete input.
        arguments.model = model
        arguments.snapshot = None
    result = run(arguments)
    if result.get('pending') or result.get('error'):
        raise ValueError('Не завершена автоматическая оценка: ' + json.dumps(result, ensure_ascii=False))
    return result


def make_round(args, workspace, base_manifest, base, candidates, universe):
    def build(words, number):
        directory = workspace / f'round-{number:02d}'
        directory.mkdir()
        package = directory / 'package'
        package.mkdir()
        # Provenance comes from the same verified dump as cleanup, also for
        # selected source-only exceptions; no manually fabricated source rows.
        cleanup = clean.load_config(TOOLS / 'selection_config.json')
        records = clean.extract_records(args.dump, words, cleanup)
        sources = {w: records[w]['sources'] for w in sorted(words)}
        dictionary.write_game_data(package, sources, base_manifest['source']['dump_date'],
                                   base_manifest['source']['sha256_input'], levels_spec=[])
        shutil.copyfile(LEGACY, package / 'levels.json')
        manifest = read_json(package / 'manifest.json')
        manifest['sha256_levels_json'] = sha(package / 'levels.json')
        manifest['levels_source'].update(role='historical-v1-comparison-only',
            snapshot_sha256=sha(LEGACY),
            changes='Delivered v1 snapshot including owner-authorized replacements; v2 tasks are generated separately')
        manifest['selection'] = dict(base_manifest['selection'], source_candidates=sorted(candidates),
                                      source_only=sorted(set(candidates) - base))
        manifest['selection']['reference_checks'] = dict(manifest['selection']['reference_checks'],
            level_exceptions={w: candidates[w] for w in sorted(words - base)})
        atomic_json(package / 'manifest.json', manifest)
        local_args = NS(dump=args.dump, manifest=package / 'manifest.json',
            config=TOOLS / 'definition_import_config.json', selection_config=TOOLS / 'selection_config.json',
            cache=args.cache / 'definitions', reports=directory / 'definitions', out=args.installed,
            remove_definitions=TOOLS / 'overrides/remove_definitions.txt', prepare_only=True)
        definitions.run(local_args)
        category_args = ai_args(args, local_args.reports / 'category_index.json', directory / 'categories',
                                args.cache / 'categories', args.category_snapshot)
        automatic_decisions(classification.run, category_args)
        category_index = category_args.reports / 'category_index.json'
        # Rebuild metadata so T6, hint categories and the shipped DB agree.
        local_args.category_index = category_index
        second = definitions.run(local_args)
        second_dir = Path(second['database']).parent
        for name in definitions.WORD_ARTIFACTS + ('definitions.db', 'manifest.json'):
            shutil.copyfile(second_dir / name, package / name)
        index = read_json(category_index)
        index['database_sha256'] = sha(package / 'definitions.db')
        atomic_json(category_index, index)
        hints = jvm_hints(args, package / 'definitions.db', package / 'words.txt', directory)
        previous = workspace / 'previous_levels.json'
        prof_args = NS(words=package / 'words.txt', manifest=package / 'manifest.json',
            database=package / 'definitions.db', dump=args.dump, frequency_list=args.frequency_list,
            frequency_universe=universe, config=profiles.CONFIG, selection_config=TOOLS / 'selection_config.json',
            previous_levels=previous, categories=category_index, suitability=None, hints=hints,
            cache=args.cache / 'profiles', reports=directory / 'profiles', prepare_only=True)
        profiles.run(prof_args)
        semantic_args = ai_args(args, prof_args.reports / 'suitability_input.json', directory / 'suitability',
                                args.cache / 'suitability', args.suitability_snapshot)
        automatic_decisions(suitability.run, semantic_args)
        prof_args.suitability = semantic_args.reports / 'word_suitability.json'
        prof_args.prepare_only = False
        measured = profiles.run(prof_args)
        snapshot = Path(measured['cache_path'])
        selected = selection.run(NS(profiles=snapshot, words=prof_args.words, previous_levels=previous,
                                    config=selection.CONFIG, alphabet=selection.ALPHABET,
                                    reports=directory / 'selection'))
        if not selected['installable']:
            raise ValueError('GEN-02 отклонил исходники: ' + selected['status'])
        selected_sources = {slot['word'] for slot in selected['slots']}
        if base | (selected_sources & set(candidates)) != words:
            return selected_sources, package
        proposal = directory / 'selection/level_words_proposal.json'
        common = dict(profiles=snapshot, words=prof_args.words, previous_levels=previous,
                      selection_config=selection.CONFIG, manifest=prof_args.manifest,
                      database=prof_args.database, dump=args.dump, hints=hints, proposal=proposal,
                      java=args.java, jvm_classpath=args.jvm_classpath)
        generated = campaign.run(NS(**common, config=campaign.CONFIG, output=directory / 'campaign'))
        if generated['status'] != 'valid':
            raise ValueError('GEN-03 отклонил кампанию: ' + generated['status'])
        balanced = rebalance.run(NS(**common, config=simulation.CONFIG,
            campaign=directory / 'campaign/campaign.json', output=directory / 'simulation'))
        if not balanced['installable']:
            raise ValueError('GEN-04/GEN-05 отклонили кампанию; см. ' + str(directory / 'simulation'))
        shutil.copyfile(directory / 'simulation/balanced_campaign.json', package / 'campaign.json')
        shutil.copyfile(snapshot, package / 'lexical_profiles.json.gz')
        shutil.copyfile(previous, package / 'previous_levels.json')
        return {l['word'] for l in balanced['campaign']['levels']}, package
    return build


def verify_package(package, *, java='java', jvm_classpath=None):
    """Recompute validation against the exact packaged bytes, including on install."""
    manifest = read_json(package / 'manifest.json')
    pipeline = manifest['pipeline']
    if pipeline['schema'] != 'v2-package-v1' or pipeline['processors'] != processor_inputs():
        raise ValueError('Пакет создан другими конфигами/генераторами; повторите подготовку')
    expected = set(PACKAGE_ARTIFACTS) - {'manifest.json'}
    if set(pipeline['artifacts']) != expected:
        raise ValueError('Неполный состав пакета')
    for name, digest in pipeline['artifacts'].items():
        if sha(package / name) != digest:
            raise ValueError('SHA-256 пакета не совпадает: ' + name)
    upstream = read_json(package / 'input_manifest.json')
    for key in ('sha256_words_txt', 'sha256_word_sources', 'sha256_levels_json', 'sha256_golden_search',
                'selection', 'source', 'definitions'):
        if upstream[key] != manifest[key]:
            raise ValueError('Конечный манифест расходится с входом независимой проверки: ' + key)
    checksums = dict(zip(definitions.WORD_ARTIFACTS, definitions.CHECKSUMS))
    for name, key in checksums.items():
        if sha(package / name) != manifest[key]:
            raise ValueError('SHA-256 игрового файла не совпадает: ' + name)
    words = dictionary.read_list(package / 'words.txt')
    if (package / 'words.txt').read_bytes() != canonical(words) or len(words) != manifest['words']:
        raise ValueError('Неканонический словарь')
    definitions.validate_database(package / 'definitions.db', set(words), definitions.load_settings(TOOLS / 'definition_import_config.json'))
    with sqlite3.connect(package / 'definitions.db') as db:
        metadata = dict(db.execute('SELECT key,value FROM build_metadata'))
        if (metadata['dictionary_words_sha256'] != sha(package / 'words.txt') or
                db.execute('PRAGMA user_version').fetchone()[0] != manifest['definitions']['user_version']):
            raise ValueError('БД относится к другому словарю/версии')
    if sha(package / 'definitions.db') != manifest['definitions']['sha256']:
        raise ValueError('Неверный SHA-256 определений')
    golden = read_json(package / 'golden_search.json')
    if set(golden) != set(dictionary.GOLDEN_WORDS):
        raise ValueError('Неполный golden_search')
    for name, value in golden.items():
        found = sorted(dictionary.search(words, name), key=lambda w: (len(w), w))
        if value != dict(count=len(found), words=found):
            raise ValueError('golden_search не соответствует конечному словарю')
    with gzip.open(package / 'word_sources.jsonl.gz', 'rt', encoding='utf-8') as stream:
        rows = [json.loads(line) for line in stream]
    if [r['word'] for r in rows] != words or any(not r['sources'] for r in rows):
        raise ValueError('Неполное происхождение слов')
    for row in rows:
        for source in row['sources']:
            if (set(source) != {'title', 'line', 'source'} or dictionary.fold(source['title']) != row['word'] or
                    type(source['line']) is not int or source['line'] <= 0 or
                    not source['source'].startswith('https://ru.wiktionary.org/wiki/')):
                raise ValueError('Неверное происхождение слова: ' + row['word'])
    if manifest['source']['license'] != 'CC-BY-SA-4.0' or manifest['levels_source']['license'] != 'MIT':
        raise ValueError('Отсутствует лицензионное происхождение словаря/истории v1')
    if (manifest['campaign_source']['sha256'] != sha(package / 'campaign.json') or
            manifest['levels_source']['snapshot_sha256'] != sha(package / 'previous_levels.json') or
            sha(package / 'levels.json') != sha(package / 'previous_levels.json')):
        raise ValueError('Перепутано происхождение новой и исторической кампании')
    # Source list itself is not needed on installation: the binary checksum and
    # full no-false-negative audit are bound in the preparation manifest.
    parsed = bloom.read((package / 'known_words.bloom').read_bytes())
    if (manifest['known_words']['sha256'] != sha(package / 'known_words.bloom') or
            manifest['known_words']['input_words_sha256'] != manifest['selection']['input_words_sha256'] or
            manifest['known_words']['game_words_sha256'] != sha(package / 'words.txt') or
            manifest['known_words']['false_negatives'] != 0):
        raise ValueError('Происхождение Bloom не соответствует пакету')
    if any(not bloom.might_contain(parsed, w) for w in words):
        raise ValueError('Bloom теряет игровые слова')
    document = selection.load_snapshot(package / 'lexical_profiles.json.gz', package / 'words.txt', package / 'previous_levels.json')
    args = NS(campaign=package / 'campaign.json', profiles=package / 'lexical_profiles.json.gz',
              words=package / 'words.txt', previous_levels=package / 'previous_levels.json',
              database=package / 'definitions.db', manifest=package / 'input_manifest.json',
              java=java, jvm_classpath=jvm_classpath)
    candidate, audit = validation.run(args)
    sources = {level['word'] for level in candidate['levels']}
    exceptions = set(manifest['selection']['reference_checks']['level_exceptions'])
    if not sources <= set(words) or not exceptions <= sources:
        raise ValueError('Исходники/исключения не соответствуют конечной кампании')
    only = set(manifest['selection']['source_only'])
    if any(row['role'] == 'target' and row['word'] in only and row['assessment']['status'] == 'suitable'
           for row in document['payload']['assessments']):
        raise ValueError('Исключение исходника стало желанной целью')
    source_assessments = {row['word']: row for row in document['payload']['assessments'] if row['role'] == 'source'}
    expected_exceptions = {w: dict(reasons=manifest['selection']['reference_checks']['level_exceptions'][w],
                                  assessment=source_assessments[w]) for w in sorted(exceptions)}
    if (pipeline['source_exceptions'] != expected_exceptions or
            any(row['assessment']['assessment']['status'] != 'suitable' for row in expected_exceptions.values())):
        raise ValueError('Нет обоснованной source-оценки выбранного исключения')
    if not rebalance.valid(audit) or not audit['jvm_checked']:
        raise ValueError('Независимая Python/JVM-проверка отклонила конечный пакет: ' + json.dumps(audit, ensure_ascii=False))
    config = simulation.load_config()
    builder = campaign.CampaignBuilder(document['payload'], words, campaign.load_config())
    assessments = {(r['word'], r['role']): r['assessment']['status'] for r in document['payload']['assessments']}
    pool = {w for w in words if w not in validation.BANNED and builder.metadata[w]['noun']
            and not set(builder.metadata[w]['labels']) & validation.HARD_LABELS
            and assessments.get((w, 'target')) == 'suitable'
            and builder.metadata[w]['observed_frequency']['frequency'] >= 10}
    knowledge = simulation.Knowledge(builder.metadata, pool, config['knowledge'])
    hints = validation.load_hint_inputs(args.database, words)
    for model in rebalance.models(candidate, builder, hints, read_json(TOOLS / 'categories.json')):
        if simulation.simulate(model, knowledge, config)['deviations']:
            raise ValueError('GEN-05 отклонил сложность конечного пакета')
    return manifest


def prepare(args):
    args.package = args.package.resolve()
    args.cache = args.cache.resolve()
    installed = args.installed.resolve()
    def overlaps(a, b):
        return a == b or a in b.parents or b in a.parents
    if overlaps(args.package, args.cache):
        raise ValueError('--package и --cache должны быть раздельными каталогами')
    for path in (args.package, args.cache):
        if overlaps(path, installed) or overlaps(path, ROOT / 'data'):
            raise ValueError('Подготовка не пишет в data/; выберите отдельные --package и --cache')
    args.cache.mkdir(parents=True, exist_ok=True)
    with game.exclusive_lock(args.cache / '.prepare.lock'):
        recover_ready(args.package)
        # Snapshot the version baseline under the installer's lock. A concurrent
        # install cannot change user_version halfway through preparation.
        baseline = args.cache / 'installed-baseline'
        baseline.mkdir(exist_ok=True)
        with game.exclusive_lock(installed.parent / ('.' + installed.name + '.publish.lock')):
            if (installed.parent / ('.' + installed.name + '.publish-transaction')).exists():
                raise ValueError('Незавершённая установка: запустите install для восстановления data/')
            manifest_bytes = args.manifest.read_bytes()
            baseline_sha = sha(installed / 'manifest.json') if (installed / 'manifest.json').exists() else 'absent'
            (baseline / 'manifest.json').write_bytes(manifest_bytes)
            database = baseline / 'definitions.db'
            database.unlink(missing_ok=True)
            if (installed / 'definitions.db').exists():
                shutil.copyfile(installed / 'definitions.db', database)
        original = json.loads(manifest_bytes)
        manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
        input_manifest = baseline / 'manifest.json'
        build_args = NS(**{**vars(args), 'manifest': input_manifest, 'installed': baseline})
        if sha(args.dump) != original['source']['sha256_input']:
            raise ValueError('SHA-256 локального дампа не совпадает с манифестом')
        if args.frequency_list:
            frequency.validate(args.frequency_list, frequency.expected_digest(input_manifest))
        else:
            args.frequency_list = frequency.prepare(allow_network=args.allow_network, manifest=input_manifest)
        build_args.frequency_list = args.frequency_list
        inputs = dict(dump=sha(args.dump), frequency=sha(args.frequency_list), processors=processor_inputs(),
                      manifest=manifest_sha, model=args.model, provider=getattr(args, 'provider', 'codex'))
        # Persistent workspace and per-item caches survive failure; never depend
        # on a previous build/ directory. Successful package remains untouched.
        workspace = args.cache / 'runs' / json_digest(inputs)
        if workspace.exists():
            shutil.rmtree(workspace)
        workspace.mkdir(parents=True)
        universe = workspace / 'source_words.txt'
        source_cache = args.cache / 'cleanup'
        cache_name = 'source-vocabulary-' + original['source']['sha256_input'] + '.json'
        if not (source_cache / cache_name).exists():
            source_cache = TOOLS / 'cache/cleanup'
        source = restore_source(args.dump, original, args.source_words, source_cache, universe)
        shutil.copyfile(LEGACY, workspace / 'previous_levels.json')
        cleaned = clean.run(NS(dump=args.dump, frequency_list=args.frequency_list, source_words=universe,
            manifest=input_manifest, config=TOOLS / 'selection_config.json', cache=args.cache / 'cleanup',
            reports=workspace / 'cleanup', out=args.installed, golden=ROOT / 'core/src/test/resources/frequent_nouns_golden.txt',
            prepare_only=True, audit_only=False, campaign_v2=True))
        base_directory = Path(cleaned['candidate'])
        base_manifest = read_json(base_directory / 'manifest.json')
        # An explicit frequency file need not have an adjacent source.json. Its
        # bytes were checked above; keep the pinned origin for the next rebuild.
        base_manifest['selection']['frequency_source'] = dict(original['selection']['frequency_source'],
                                                               kind='frequency-list', sha256=inputs['frequency'])
        base = set(dictionary.read_list(base_directory / 'words.txt'))
        wanted = {l['word'] for l in read_json(LEGACY)} | suitability.PREFERRED_SOURCES
        records = clean.extract_records(args.dump, source, clean.load_config(TOOLS / 'selection_config.json'))
        ranks = game.frequencies({w: {'eligible': True, 'forms': r['forms']} for w, r in records.items()},
                                 frequency_list=args.frequency_list)['words']
        lexical = clean.decide(records, ranks, clean.load_config(TOOLS / 'selection_config.json'))
        candidates = {w: lexical[w]['reasons'] or ['source_candidate'] for w in sorted(wanted & source)}
        package, history = converge(base, candidates,
            make_round(build_args, workspace, base_manifest, base, candidates, universe), args.max_rounds)
        # Freeze every pass, not just the last: a fresh machine must reproduce
        # intermediate feature sets without a lucky per-item provider cache.
        for task, name in (('categories', 'category_decisions.json'), ('suitability', 'suitability_decisions.json')):
            snapshots = [read_json(directory / task / name) for directory in sorted(workspace.glob('round-*'))]
            atomic_json(package / name, dict(schema='v2-decision-bundle-v1', snapshots=snapshots))
        report = bloom.generate(universe, package / 'manifest.json', package / 'words.txt', package)
        manifest = read_json(package / 'manifest.json')
        shutil.copyfile(package / 'manifest.json', package / 'input_manifest.json')
        manifest['campaign_source'] = dict(generator='tools/build_campaign.py + tools/rebalance_campaign.py',
            contract='docs/CAMPAIGN.md', previous_levels_role='historical comparison only; not v2 task source',
            sha256=sha(package / 'campaign.json'))
        manifest['known_words'] = report
        with gzip.open(package / 'lexical_profiles.json.gz', 'rt', encoding='utf-8') as stream:
            document = json.load(stream)
        source_assessments = {row['word']: row for row in document['payload']['assessments'] if row['role'] == 'source'}
        exceptions = manifest['selection']['reference_checks']['level_exceptions']
        manifest['pipeline'] = dict(schema='v2-package-v1', inputs=inputs, processors=processor_inputs(),
            baseline_manifest_sha256=baseline_sha, history=history,
            source_exceptions={w: dict(reasons=exceptions[w], assessment=source_assessments[w]) for w in sorted(exceptions)},
            artifacts={n: sha(package / n) for n in PACKAGE_ARTIFACTS if n != 'manifest.json'})
        atomic_json(package / 'manifest.json', manifest)
        verify_package(package, java=args.java, jvm_classpath=args.jvm_classpath)
        if inputs['dump'] != sha(args.dump) or inputs['frequency'] != sha(args.frequency_list):
            raise ValueError('Внешний вход изменился во время подготовки; повторите запуск')
        game.publish(package, args.package, PACKAGE_ARTIFACTS)
        return dict(status='prepared', package=str(args.package), words=manifest['words'], rounds=len(history))


def install(args):
    # Recover even if the newly supplied package is absent or invalid.
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=True)
    recover_ready(output)
    # Copy under the package's publication lock: a simultaneous prepare cannot
    # replace files between verification and installation.
    package = args.package.resolve()
    if package == output:
        raise ValueError('--package и --out должны различаться')
    with game.exclusive_lock(package.parent / ('.' + package.name + '.publish.lock')):
        journal = package.parent / ('.' + package.name + '.publish-transaction')
        if journal.exists():
            game.recover_publication(package, journal)
        with tempfile.TemporaryDirectory(prefix='v2-install-') as temporary:
            snapshot = Path(temporary)
            for name in PACKAGE_ARTIFACTS:
                shutil.copyfile(package / name, snapshot / name)
            manifest = verify_package(snapshot, java=args.java, jvm_classpath=args.jvm_classpath)
            game.publish(snapshot, output, PACKAGE_ARTIFACTS,
                         expected_manifest_sha256=manifest['pipeline']['baseline_manifest_sha256'])
    return dict(status='installed', out=str(output))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest='mode', required=True)
    preparing = modes.add_parser('prepare', help='Подготовка вне data/, без ручного утверждения')
    preparing.add_argument('--dump', type=Path, required=True)
    preparing.add_argument('--manifest', type=Path, default=ROOT / 'data/manifest.json')
    preparing.add_argument('--source-words', type=Path)
    preparing.add_argument('--frequency-list', type=Path)
    preparing.add_argument('--cache', type=Path, default=ROOT / '.local/v2-cache')
    preparing.add_argument('--installed', type=Path, default=ROOT / 'data', help='База версии БД, только чтение')
    preparing.add_argument('--model', default=os.environ.get('CATEGORY_MODEL'))
    preparing.add_argument('--provider', choices=('codex', 'opencode'), default='codex',
                           help='CLI исполнения ИИ-оценок LEX (Codex или OpenCode)')
    preparing.add_argument('--category-snapshot', type=Path)
    preparing.add_argument('--suitability-snapshot', type=Path)
    preparing.add_argument('--allow-network', action='store_true', help='Только FrequencyWords и модель LEX')
    preparing.add_argument('--max-rounds', type=int, choices=range(1, 9), default=4)
    installing = modes.add_parser('install', help='Автоматическая проверка и установка готового пакета, без сети')
    installing.add_argument('--out', type=Path, default=ROOT / 'data')
    for mode in (preparing, installing):
        mode.add_argument('--package', type=Path, default=ROOT / '.local/v2-package')
        mode.add_argument('--jvm-classpath', help='Свежесобранный core+audit classpath; иначе offline Gradle')
        mode.add_argument('--java', default='java')
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args) if args.mode == 'prepare' else install(args), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
