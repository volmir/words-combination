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

import math
import time

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
import plan_semantic_requests as planner
import prepare_frequency as frequency
import rebalance_campaign as rebalance
import select_level_words as selection
import simulate_campaign as simulation
import termination_guard as termination
import validate_campaign as validation

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'tools'
LEGACY = ROOT / 'core/src/test/resources/legacy_levels_v1.json'
SELECTIVE_CONFIG = TOOLS / 'selective_config.json'
PACKAGE_ARTIFACTS = (*clean.ARTIFACTS[:-1], 'definitions.db', 'campaign.json', 'known_words.bloom',
                     'input_manifest.json', 'lexical_profiles.json.gz', 'previous_levels.json',
                     'category_decisions.json', 'suitability_decisions.json', 'manifest.json')
SELECTIVE_REPORT_SCHEMA = 'selective-report-v1'
SELECTIVE_JOURNAL_SCHEMA = 'selective-run-journal-v1'


def load_selective_config(path=SELECTIVE_CONFIG):
    """Explicit resource boundaries for the selective mode (SEL-05).

    Numbers are validated against the provider CLI limits, never against the
    campaign thresholds: GEN-02/03/04/05 constraints are not relaxed here.
    """
    document = json.loads(Path(path).read_text(encoding='utf-8'))
    if document.get('schema') != 'selective-mode-config-v1':
        raise ValueError('Неверная схема конфигурации выборочного режима')
    required = {'iterations_per_round', 'max_new_decisions_per_iteration', 'max_batches_per_iteration',
                'time_budget_per_iteration_s', 'total_new_decisions', 'total_time_budget_s',
                'batch_size', 'timeout', 'retries', 'resume'}
    if not required <= set(document):
        raise ValueError('Неполный набор параметров выборочного режима: '
                         + ', '.join(sorted(required - set(document))))
    for name in ('iterations_per_round', 'max_new_decisions_per_iteration', 'max_batches_per_iteration',
                 'total_new_decisions', 'batch_size', 'timeout', 'retries'):
        if type(document[name]) is not int or document[name] <= 0:
            raise ValueError('Ожидалось положительное целое: ' + name)
    if not 1 <= document['batch_size'] <= 40:
        raise ValueError('batch_size должен быть в диапазоне 1–40')
    if not 1 <= document['timeout'] <= 600:
        raise ValueError('timeout должен быть в диапазоне 1–600')
    if not 0 <= document['retries'] <= 3:
        raise ValueError('retries должен быть в диапазоне 0–3')
    for name in ('time_budget_per_iteration_s', 'total_time_budget_s'):
        value = document[name]
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise ValueError(name + ' должен быть неотрицательным числом секунд')
        document[name] = float(value)
    if not document['total_time_budget_s'] >= document['time_budget_per_iteration_s'] > 0:
        raise ValueError('Совокупный лимит времени должен покрывать лимит одной итерации')
    if document['resume'] != 'cache-and-journal':
        raise ValueError('Политика возобновления: cache-and-journal')
    return document


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


def round_structural(args, workspace, number, words, base_manifest, base, candidates, universe):
    """Local stages shared by full and selective rounds, up to the LEX-02 index.

    No model, decisions cache or network is touched here. The first local
    category index covers every word; unknowns stay `unknown:*`, never `other`.
    """
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
    previous = workspace / 'previous_levels.json'
    return directory, package, local_args, previous


def rebind_definitions(package, local_args, category_index):
    """Rebuild metadata so T6, hint categories and the shipped DB agree."""
    local_args.category_index = category_index
    second = definitions.run(local_args)
    second_dir = Path(second['database']).parent
    for name in definitions.WORD_ARTIFACTS + ('definitions.db', 'manifest.json'):
        shutil.copyfile(second_dir / name, package / name)
    index = read_json(category_index)
    index['database_sha256'] = sha(package / 'definitions.db')
    atomic_json(category_index, index)
    return second


def gen02(args, directory, snapshot, prof_args, previous):
    selected = selection.run(NS(profiles=snapshot, words=prof_args.words, previous_levels=previous,
                                config=selection.CONFIG, alphabet=selection.ALPHABET,
                                reports=directory / 'selection'))
    return selected, {slot['word'] for slot in selected['slots']}


def gen345(args, directory, snapshot, prof_args, previous, hints):
    proposal = directory / 'selection/level_words_proposal.json'
    common = dict(profiles=snapshot, words=prof_args.words, previous_levels=previous,
                  selection_config=selection.CONFIG, manifest=prof_args.manifest,
                  database=prof_args.database, dump=args.dump, hints=hints, proposal=proposal,
                  java=args.java, jvm_classpath=args.jvm_classpath)
    generated = campaign.run(NS(**common, config=campaign.CONFIG, output=directory / 'campaign'))
    balanced = rebalance.run(NS(**common, config=simulation.CONFIG,
        campaign=directory / 'campaign/campaign.json', output=directory / 'simulation'))
    return generated, balanced


def finalize_package(package, directory, balanced, snapshot, previous):
    shutil.copyfile(directory / 'simulation/balanced_campaign.json', package / 'campaign.json')
    shutil.copyfile(snapshot, package / 'lexical_profiles.json.gz')
    shutil.copyfile(previous, package / 'previous_levels.json')
    return {l['word'] for l in balanced['campaign']['levels']}


def make_round(args, workspace, base_manifest, base, candidates, universe):
    def build(words, number):
        directory, package, local_args, previous = round_structural(
            args, workspace, number, words, base_manifest, base, candidates, universe)
        category_args = ai_args(args, local_args.reports / 'category_index.json', directory / 'categories',
                                args.cache / 'categories', args.category_snapshot)
        automatic_decisions(classification.run, category_args)
        category_index = category_args.reports / 'category_index.json'
        # Rebuild metadata so T6, hint categories and the shipped DB agree.
        rebind_definitions(package, local_args, category_index)
        hints = jvm_hints(args, package / 'definitions.db', package / 'words.txt', directory)
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
        selected, selected_sources = gen02(args, directory, snapshot, prof_args, previous)
        if not selected['installable']:
            raise ValueError('GEN-02 отклонил исходники: ' + selected['status'])
        if base | (selected_sources & set(candidates)) != words:
            return selected_sources, package
        generated, balanced = gen345(args, directory, snapshot, prof_args, previous, hints)
        if generated['status'] != 'valid':
            raise ValueError('GEN-03 отклонил кампанию: ' + generated['status'])
        if not balanced['installable']:
            raise ValueError('GEN-04/GEN-05 отклонили кампанию; см. ' + str(directory / 'simulation'))
        return finalize_package(package, directory, balanced, snapshot, previous), package
    return build


def decision_provider(arguments, semantic, model):
    """Provider mirrored from the stage: same context, cache keys and validators."""
    if semantic:
        return suitability.SuitabilityProvider(arguments.cache, model,
                                               provider=getattr(arguments, 'provider', 'codex'))
    return classification.CategoryProvider(arguments.cache, model,
                                           classification.thematic.load_taxonomy(arguments.taxonomy),
                                           provider=getattr(arguments, 'provider', 'codex'))


def requested_decision_items(arguments, provider, semantic):
    """The exact item subset a LEX stage will process under its request file."""
    incoming = read_json(arguments.input)
    if semantic:
        pairs_file = getattr(arguments, 'pairs_file', None)
        pairs = suitability.read_pairs_file(pairs_file) if pairs_file is not None else None
        return suitability.input_items(incoming, pairs)
    words_file = getattr(arguments, 'words_file', None)
    requested = classification.read_words_file(words_file) if words_file is not None else None
    return classification.classification_items(incoming, provider.taxonomy, requested)


def selective_decisions(run, arguments, semantic):
    """Import compatible snapshot elements for the REQUESTED subset, then run the stage.

    Full `automatic_decisions` demands every disputed item be resolved; here only
    the explicitly requested items matter and a `pending` result (budget/offline)
    is a legitimate outcome handed to the selective driver. Snapshot elements are
    reused only on exact item match; different inputs invalidate them item-wise.
    """
    if arguments.snapshot:
        frozen = read_json(arguments.snapshot)
        snapshots = frozen['snapshots'] if frozen.get('schema') == 'v2-decision-bundle-v1' else [frozen]
        if not snapshots:
            raise ValueError('Пустой пакет замороженных решений')
        model = arguments.model or snapshots[-1].get('context', {}).get('model')
        provider = decision_provider(arguments, semantic, model)
        policy = getattr(provider, 'snapshot_policy', None)
        if policy is None:  # only for providers without a fixed snapshot policy
            policy = {'min_confidence': arguments.min_confidence}
        items = requested_decision_items(arguments, provider, semantic)
        for snapshot in snapshots:
            if (snapshot.get('context') == provider.context and snapshot.get('taxonomy') == provider.taxonomy and
                    snapshot.get('policy') == policy and
                    snapshot.get('schema') == getattr(provider, 'snapshot_schema', classification.SNAPSHOT_SCHEMA)):
                seed_snapshot(snapshot, provider, items)
        arguments.model = model
        arguments.snapshot = None
    return run(arguments)


def selective_ai_args(args, kind, input_path, reports, cache, snapshot, request_file, budget):
    """Bounded LEX invocation: network only under --allow-network, per-call limits."""
    common = dict(input=input_path, reports=reports, cache=cache, snapshot=snapshot,
                  model=args.model, provider=getattr(args, 'provider', 'codex'),
                  allow_network=args.allow_network, batch_size=budget['batch_size'],
                  timeout=budget['timeout'], retries=budget['retries'],
                  max_new_decisions=budget['max_new'], max_batches=budget['max_batches'],
                  time_budget=budget['time_budget'])
    if kind == 'categories':
        return NS(**common, taxonomy=TOOLS / 'categories.json', min_confidence=0.8,
                  words_file=request_file, gold=None)
    return NS(**common, pairs_file=request_file)


def rehead_hints(source, target, database, words):
    """Hint rows do not depend on categories; only the header binds the DB bytes.

    The expensive JVM audit runs once per round. Rebuilding the header around the
    same validated rows keeps profile inputs consistent with each rebind of the
    category-enriched database without re-running Gradle/JVM per iteration.
    """
    lines = source.read_text(encoding='utf-8').splitlines()
    if not lines:
        raise ValueError('Файл подсказок пуст')
    header = json.dumps(profiles.hint_header(database, words), ensure_ascii=False, sort_keys=True)
    rows = [line for line in lines[1:] if line.strip()]
    target.write_text(header + '\n' + '\n'.join(rows) + ('\n' if rows else ''), encoding='utf-8')
    return target


def plan_iteration(args, directory, iteration, words_path, suitability_input, categories, hints,
                   previous, suitability_decisions):
    """Deterministic SEL-04 plan for the round's current state; AI is never called."""
    plan_dir = directory / f'plan-{iteration:02d}'
    plan_args = NS(words=words_path, suitability_input=suitability_input, categories=categories,
                   hints=hints, previous_levels=previous, output=plan_dir / 'request_plan.json',
                   config=getattr(args, 'plan_config', None) or planner.CONFIG,
                   profiles_config=profiles.CONFIG,
                   selection_config=getattr(args, 'plan_selection_config', None) or selection.CONFIG,
                   campaign_config=campaign.CONFIG, alphabet=planner.ALPHABET_FILE,
                   suitability_decisions=suitability_decisions,
                   cache_suitability=args.cache / 'suitability', cache_categories=args.cache / 'categories',
                   model=args.model, provider=getattr(args, 'provider', 'codex'))
    planner.run(plan_args)
    return plan_dir


def record_deficits(plan, gen_failures):
    """Explainable diagnostic: plan-level deficiencies plus stage failure codes."""
    deficits = []
    for row in plan.get('expansions', []):
        deficits.append({'source': 'plan', 'reason_code': row.get('reason_code'),
                         'detail': row.get('detail')})
    if plan.get('status') == 'proven_infeasible':
        deficits.extend({'source': 'plan', 'reason_code': 'infeasible', 'detail': reason}
                        for reason in plan.get('infeasible', {}).get('reasons', []))
    if gen_failures:
        deficits.append({'source': 'gen', 'reason_code': 'stage_failure', 'detail': gen_failures})
    return deficits


def empty_selective_journal(inputs):
    return dict(schema=SELECTIVE_JOURNAL_SCHEMA, inputs=inputs, status='running',
                cumulative=dict(new_ai_categories=0, new_ai_suitability=0, cached_categories=0,
                                cached_suitability=0, iterations=0, rounds=0, elapsed_s=0.0))


def load_journal(path, inputs):
    if not path.exists():
        return empty_selective_journal(inputs)
    journal = read_json(path)
    if (journal.get('schema') != SELECTIVE_JOURNAL_SCHEMA or journal.get('inputs') != inputs or
            not isinstance(journal.get('cumulative'), dict)):
        raise ValueError('Повреждён журнал выборочного режима: ' + str(path))
    return journal


def calc_iteration_budget(config, journal):
    """Per-LEX-call budget clipped by the cumulative DATA-01 limits."""
    remaining_new = max(0, config['total_new_decisions'] -
                        journal['cumulative']['new_ai_categories'] -
                        journal['cumulative']['new_ai_suitability'])
    remaining_time = max(0.0, config['total_time_budget_s'] - journal['cumulative']['elapsed_s'])
    return dict(max_new=min(config['max_new_decisions_per_iteration'], remaining_new),
                max_batches=config['max_batches_per_iteration'],
                time_budget=min(config['time_budget_per_iteration_s'], remaining_time),
                batch_size=config['batch_size'], timeout=config['timeout'], retries=config['retries'])


def selective_round(args, config, journal, journal_path, workspace, number, words,
                    base_manifest, base, candidates, universe):
    """One converge-round of selective preparation: plan -> bounded LEX -> GEN-02..05.

    Returns a validated package, the dictionary expansion set, or an incomplete
    outcome with per-iteration diagnostics. AI work stops the moment GEN-02..05
    accept the campaign. An iteration without new decisions cannot spawn further
    duplicate batches; structurally proven infeasibility never spends AI budget.
    """
    directory, package, local_args, previous = round_structural(
        args, workspace, number, words, base_manifest, base, candidates, universe)
    local_index = local_args.reports / 'category_index.json'
    rebind_definitions(package, local_args, local_index)
    hints = jvm_hints(args, package / 'definitions.db', package / 'words.txt', directory)
    prof_args = NS(words=package / 'words.txt', manifest=package / 'manifest.json',
        database=package / 'definitions.db', dump=args.dump, frequency_list=args.frequency_list,
        frequency_universe=universe, config=profiles.CONFIG, selection_config=TOOLS / 'selection_config.json',
        previous_levels=previous, categories=local_index, suitability=None, hints=hints,
        cache=args.cache / 'profiles', reports=directory / 'profiles', prepare_only=True)
    profiles.run(prof_args)
    suitability_input = prof_args.reports / 'suitability_input.json'
    carry_index, carry_ws, iterations = local_index, None, []
    snapshots = [directory / 'categories', directory / 'suitability']
    for name in snapshots:
        (name / 'snapshots').mkdir(parents=True, exist_ok=True)
    last_infeasible = False
    for iteration in range(1, config['iterations_per_round'] + 1):
        started = time.monotonic()
        budget = calc_iteration_budget(config, journal)
        plan_dir = plan_iteration(args, directory, iteration, package / 'words.txt', suitability_input,
                                  carry_index, hints, previous, carry_ws)
        plan = read_json(plan_dir / 'request_plan.json')
        last_infeasible = plan.get('status') == 'proven_infeasible'
        if last_infeasible:
            # Structurally proven impossibility: AI cannot add missing carriers,
            # bands or historical sources; never spend budget on that.
            record = dict(round=number, iteration=iteration,
                          plan=dict(status=plan['status']),
                          category=dict(ai_new=0, cached=0, pending=0, unknown=0, ai_other=0,
                                        rejected=0, spent_time=0),
                          suitability=dict(ai_new=0, cached=0, pending=0,
                                           role_counters={}, spent_time=0),
                          budget=dict(budget), gen={},
                          deficits=record_deficits(plan, []))
            iterations.append(record)
            return dict(kind='incomplete', iterations=iterations,
                        status='IMPOSSIBLE_UNDER_CONSTRAINTS', last_infeasible=True)
        pairs_file, words_file = plan_dir / 'requested_pairs.txt', plan_dir / 'requested_words.txt'
        for request_file in (pairs_file, words_file):
            if not request_file.exists():
                request_file.write_text('', encoding='utf-8')
        category_args = selective_ai_args(args, 'categories', carry_index, directory / 'categories',
                                          args.cache / 'categories', args.category_snapshot,
                                          words_file, budget)
        category_summary = selective_decisions(classification.run, category_args, False)
        carry_index = category_args.reports / 'category_index.json'
        suitability_args = selective_ai_args(args, 'suitability', suitability_input, directory / 'suitability',
                                             args.cache / 'suitability', args.suitability_snapshot,
                                             pairs_file, budget)
        suitability_summary = selective_decisions(suitability.run, suitability_args, True)
        carry_ws = suitability_args.reports / 'word_suitability.json'
        # Compact snapshots of exactly the decisions used this iteration.
        shutil.copyfile(directory / 'categories' / 'category_decisions.json',
                        snapshots[0] / 'snapshots' / ('iter-%02d.json' % iteration))
        shutil.copyfile(directory / 'suitability' / 'suitability_decisions.json',
                        snapshots[1] / 'snapshots' / ('iter-%02d.json' % iteration))
        new_categories = category_summary.get('ai_new', 0)
        new_suitability = suitability_summary.get('ai_new', 0)
        category_pending = category_summary.get('pending', 0)
        suitability_pending = suitability_summary.get('pending', 0)
        gen_failures, deficits = [], []
        record = dict(round=number, iteration=iteration,
                      plan=dict(status=plan['status'],
                                pairs_requested=plan['suitability']['requested'],
                                words_requested=plan['category']['requested'],
                                cache_hits_pairs=plan['suitability']['cache_resolved'],
                                cache_hits_words=plan['category']['cache_resolved']),
                      category=dict(ai_new=new_categories, cached=category_summary.get('cached', 0),
                                    pending=category_pending, unknown=category_summary.get('unknown', 0),
                                    ai_other=category_summary.get('ai_other', 0),
                                    rejected=category_summary.get('rejected', 0),
                                    spent_time=category_summary.get('spent_time', 0)),
                      suitability=dict(ai_new=new_suitability, cached=suitability_summary.get('cache_hit', 0),
                                       pending=suitability_pending,
                                       role_counters=suitability_summary.get('role_counters', {}),
                                       spent_time=suitability_summary.get('spent_time', 0)),
                      budget=dict(budget), gen={}, deficits=[])
        journal['cumulative']['new_ai_categories'] += new_categories
        journal['cumulative']['new_ai_suitability'] += new_suitability
        journal['cumulative']['cached_categories'] += record['category']['cached']
        journal['cumulative']['cached_suitability'] += record['suitability']['cached']
        journal['cumulative']['iterations'] += 1
        journal['cumulative']['elapsed_s'] = round(journal['cumulative']['elapsed_s'] + time.monotonic() - started, 6)
        save_journal(journal_path, journal)
        # Recompute profiles against the updated semantic inputs.
        rebind_definitions(package, local_args, carry_index)
        prof_args.categories, prof_args.suitability = carry_index, carry_ws
        prof_args.hints = rehead_hints(hints, directory / 'hints-current.jsonl',
                                       package / 'definitions.db', package / 'words.txt')
        prof_args.prepare_only = False
        measured = profiles.run(prof_args)
        snapshot = Path(measured['cache_path'])
        selected, selected_sources = gen02(args, directory, snapshot, prof_args, previous)
        if not selected['installable']:
            gen_failures.append('GEN-02: ' + selected['status'])
            record['gen']['gen02'] = selected['status']
            record['deficits'] = record_deficits(plan, gen_failures)
            iterations.append(record)
            if new_categories + new_suitability == 0:
                return dict(kind='incomplete', iterations=iterations,
                            status='NEEDS_MORE_EVIDENCE', last_infeasible=False)
            continue
        if base | (selected_sources & set(candidates)) != words:
            record['gen']['gen02'] = 'expand'
            iterations.append(record)
            return dict(kind='expand', selected=selected_sources, iterations=iterations)
        generated, balanced = gen345(args, directory, snapshot, prof_args, previous, prof_args.hints)
        if generated['status'] == 'valid' and balanced['installable']:
            levels = finalize_package(package, directory, balanced, snapshot, previous)
            record['gen'] = dict(gen02='ok', gen03='valid', gen04='installable')
            iterations.append(record)
            # VALID: no further AI calls this round.
            return dict(kind='valid', package=package, levels=levels, iterations=iterations)
        if generated['status'] != 'valid':
            gen_failures.append('GEN-03: ' + generated['status'])
            record['gen']['gen03'] = generated['status']
        else:
            gen_failures.append('GEN-04/GEN-05: installable=false')
            record['gen']['gen04'] = 'not-installable'
        record['deficits'] = record_deficits(plan, gen_failures)
        if new_categories + new_suitability == 0:
            # No progress is possible: save the diagnosis and stop instead of
            # repeating the same pending batch.
            record['deficits'].append({'source': 'budget', 'reason_code': 'no_progress',
                                       'detail': 'Новых решений нет, GEN-стадии отклонили кандидата'
                                                 ' и часть запрошенного осталась pending;'
                                                 ' нужны бюджет/сеть либо совместимый кэш/снимок.'})
            iterations.append(record)
            return dict(kind='incomplete', iterations=iterations,
                        status='NEEDS_MORE_EVIDENCE', last_infeasible=False)
        iterations.append(record)
    return dict(kind='incomplete', iterations=iterations,
                status='IMPOSSIBLE_UNDER_CONSTRAINTS' if last_infeasible else 'NEEDS_MORE_EVIDENCE',
                last_infeasible=last_infeasible)


def selective_journal_dir(args, inputs):
    """Persistent resume state for the selective mode (keyed by verified inputs)."""
    return args.cache / 'selective-runs' / json_digest(dict(inputs, mode='selective'))


def selective_rounds(args, workspace, base_manifest, base, candidates, universe, inputs):
    """Converge-style driver for the selective mode, bounded by explicit resources.

    Returns (package, history, iterations, config, status): `package` is None for
    NEEDS_MORE_EVIDENCE/IMPOSSIBLE_UNDER_CONSTRAINTS; only VALID has bytes.
    """
    config = load_selective_config(getattr(args, 'selective_config', None) or SELECTIVE_CONFIG)
    apply_selective_overrides(config, args)
    journal_dir = selective_journal_dir(args, inputs)
    journal_path = journal_dir / 'journal.json'
    journal = load_journal(journal_path, dict(inputs, mode='selective'))
    words, seen, history = set(base), set(), []
    for number in range(1, args.max_rounds + 1):
        key = hashlib.sha256(canonical(words)).hexdigest()
        if key in seen:
            raise ValueError('Цикл отбора исходников: ' + json.dumps(history, ensure_ascii=False))
        seen.add(key)
        outcome = selective_round(args, config, journal, journal_path, workspace, number, words,
                                  base_manifest, base, candidates, universe)
        journal['cumulative']['rounds'] += 1
        save_journal(journal_path, journal)
        if outcome['kind'] == 'valid':
            journal['status'] = 'VALID'
            history.append(dict(round=number, words_sha256=key, exceptions=sorted(set(words) - base)))
            save_journal(journal_path, journal)
            return outcome['package'], history, outcome['iterations'], config, 'VALID'
        if outcome['kind'] == 'expand':
            final = set(base) | (outcome['selected'] & set(candidates))
            if final == words:
                raise ValueError('Выбран необоснованный исходник вне пула: ' + ', '.join(sorted(outcome['selected'] - words)))
            history.append(dict(round=number, words_sha256=key, exceptions=sorted(final - base)))
            words = final
            continue
        journal['status'] = outcome['status']
        save_journal(journal_path, journal)
        return None, history, outcome['iterations'], config, outcome['status']
    raise ValueError('Исчерпан лимит пересчётов словаря: ' + json.dumps(history, ensure_ascii=False))


def apply_selective_overrides(config, args):
    """CLI limits refine the config; values are validated against provider bounds."""
    for key, attribute, name in (
            ('iterations_per_round', 'iterations', 'iterations'),
            ('max_new_decisions_per_iteration', 'max_new_decisions', 'max-new-decisions'),
            ('max_batches_per_iteration', 'max_batches', 'max-batches'),
            ('total_new_decisions', 'total_new_decisions', 'total-new-decisions')):
        value = getattr(args, attribute, None)
        if value is not None:
            if type(value) is not int or value <= 0:
                raise ValueError(name + ' должен быть положительным целым')
            config[key] = value
    for key, attribute, name in (('time_budget_per_iteration_s', 'time_budget', 'time-budget'),
                                 ('total_time_budget_s', 'total_time_budget', 'total-time-budget')):
        value = getattr(args, attribute, None)
        if value is not None:
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError(name + ' должен быть неотрицательным числом секунд')
            config[key] = float(value)


def save_journal(path, journal):
    path.parent.mkdir(parents=True, exist_ok=True)
    atomic_json(path, journal)


def collect_selective_decisions(workspace, task):
    """Compact, versioned snapshot of exactly the decisions actually used.

    Iteration-level LEX exports carry their own context/taxonomy/input/policy/SHA,
    so a strict offline replay can import them item by item; enlarging or changing
    the inputs invalidates affected items instead of silently reusing them.
    """
    return [read_json(path) for directory in sorted(workspace.glob('round-*'))
            for path in sorted((directory / task / 'snapshots').glob('*.json'))]


def build_selective_report(args, workspace, history, iterations, status, config, inputs,
                           published=False, hashes=None, gen_checks=None):
    """Machine-readable report: iterations, AI work by kind, time, deficits, checks."""
    totals = dict(new_ai_categories=sum(i['category']['ai_new'] for i in iterations),
                  new_ai_suitability=sum(i['suitability']['ai_new'] for i in iterations),
                  new_ai_suitability_source=sum(i['suitability']['role_counters'].get('source', {}).get('ai_new', 0)
                                                for i in iterations),
                  new_ai_suitability_target=sum(i['suitability']['role_counters'].get('target', {}).get('ai_new', 0)
                                               for i in iterations),
                  cached_categories=sum(i['category']['cached'] for i in iterations),
                  cached_suitability=sum(i['suitability']['cached'] for i in iterations),
                  pending_categories=sum(i['category']['pending'] for i in iterations),
                  pending_suitability=sum(i['suitability']['pending'] for i in iterations),
                  iterations=len(iterations),
                  spent_time_total_s=round(sum(i['category']['spent_time'] + i['suitability']['spent_time']
                                               for i in iterations), 6))
    failures, deficits, seen = [], [], set()
    stage_names = {'gen02': 'GEN-02', 'gen03': 'GEN-03', 'gen04': 'GEN-04/GEN-05'}
    for iteration in iterations:
        for key, label in stage_names.items():
            value = iteration['gen'].get(key)
            if value and value not in ('ok', 'valid', 'installable', 'expand'):
                failures.append(label + ': ' + str(value))
        for row in iteration['deficits']:
            detail = row.get('detail')
            if isinstance(detail, list):
                detail = '; '.join(str(part) for part in detail)
            identity = (row.get('reason_code'), json.dumps(detail, ensure_ascii=False, sort_keys=True))
            if identity in seen:
                continue
            seen.add(identity)
            deficits.append({'reason_code': row.get('reason_code'), 'detail': detail})
    report = dict(schema=SELECTIVE_REPORT_SCHEMA, status=status,
                  inputs=dict(dump=inputs.get('dump'), frequency=inputs.get('frequency'),
                              manifest=inputs.get('manifest'), model=inputs.get('model'),
                              provider=inputs.get('provider')),
                  budget=dict(iterations_per_round=config['iterations_per_round'],
                              max_new_per_iteration=config['max_new_decisions_per_iteration'],
                              max_batches_per_iteration=config['max_batches_per_iteration'],
                              time_budget_per_iteration_s=config['time_budget_per_iteration_s'],
                              total_new_decisions=config['total_new_decisions'],
                              total_time_budget_s=config['total_time_budget_s'],
                              resume=config['resume']),
                  rounds=history, iterations=iterations, totals=totals,
                  checks=dict(gen02=gen_checks.get('gen02') if gen_checks else None,
                              gen04_valid=gen_checks.get('gen04_valid') if gen_checks else False,
                              gen05_valid=gen_checks.get('gen05_valid') if gen_checks else False,
                              verify_package=gen_checks.get('verify_package') if gen_checks else False,
                              published=published),
                  failures=failures, deficits=deficits, hashes=hashes or {},
                  resume=selective_resume_command(args), accuracy=None,
                  notes=['Новые и повторные (кэш/снимок) решения считаются раздельно по категориям и пригодности.',
                         'Ни одна неизвестная категория не считалась other: незапрошенные слова остаются unknown:*.',
                         'Точность модели не измерена: независимой разметки нет.',
                         'Поштучные кэши и ключи снимков сохраняют версии контекста, модели и провайдера.',
                         'Подготовка не пишет в data/; опубликован только проверенный VALID-пакет.'])
    path = workspace / 'selective_report.json'
    atomic_json(path, report)
    return path


def selective_resume_command(args):
    parts = ['python3', str(ROOT / 'tools/prepare_v2.py'), 'prepare', '--selective',
             '--dump', str(Path(args.dump).resolve()),
             '--manifest', str(Path(args.manifest).resolve()),
             '--cache', str(Path(args.cache).resolve()),
             '--package', str(Path(args.package).resolve())]
    if args.source_words:
        parts += ['--source-words', str(Path(args.source_words).resolve())]
    if getattr(args, 'frequency_list', None):
        parts += ['--frequency-list', str(Path(args.frequency_list).resolve())]
    if args.model:
        parts += ['--model', args.model]
    parts += ['--provider', getattr(args, 'provider', 'codex')]
    if args.category_snapshot:
        parts += ['--category-snapshot', str(Path(args.category_snapshot).resolve())]
    if args.suitability_snapshot:
        parts += ['--suitability-snapshot', str(Path(args.suitability_snapshot).resolve())]
    if args.allow_network:
        parts += ['--allow-network']
    parts += flatten_override_flags(args)
    return shlex.join(parts)


def flatten_override_flags(args):
    parts = []
    for attribute, flag in (('selective_config', '--selective-config'),
                            ('plan_config', '--plan-config'),
                            ('plan_selection_config', '--plan-selection-config'),
                            ('iterations', '--iterations'),
                            ('max_new_decisions', '--max-new-decisions'),
                            ('max_batches', '--max-batches'),
                            ('time_budget', '--time-budget'),
                            ('total_new_decisions', '--total-new-decisions'),
                            ('total_time_budget', '--total-time-budget')):
        value = getattr(args, attribute, None)
        if value is not None:
            parts += [flag, str(value)]
    return parts


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
        if getattr(args, 'selective', False):
            package, history, selective_iterations, selective_config, selective_status = selective_rounds(
                build_args, workspace, base_manifest, base, candidates, universe, inputs)
            if package is None:
                # Never publish an incomplete package and never touch data/.
                report_path = build_selective_report(args, workspace, history, selective_iterations,
                                                     selective_status, selective_config, inputs)
                report = read_json(report_path)
                report['resume'] = selective_resume_command(args)
                atomic_json(report_path, report)
                # Диагностика переживает очистку рабочего каталога следующего запуска.
                atomic_json(selective_journal_dir(args, inputs) / 'last-report.json', report)
                return dict(status=selective_status, report=str(report_path),
                            iterations=len(selective_iterations), rounds=len(history),
                            resume=selective_resume_command(args))
        else:
            package, history = converge(base, candidates,
                make_round(build_args, workspace, base_manifest, base, candidates, universe), args.max_rounds)
        # Freeze every pass, not just the last: a fresh machine must reproduce
        # intermediate feature sets without a lucky per-item provider cache.
        for task, name in (('categories', 'category_decisions.json'), ('suitability', 'suitability_decisions.json')):
            if getattr(args, 'selective', False):
                snapshots = collect_selective_decisions(workspace, task)
            else:
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
        if getattr(args, 'selective', False):
            report_path = build_selective_report(args, workspace, history, selective_iterations, 'VALID',
                                                 selective_config, inputs, published=True,
                                                 hashes={name: sha(package / name) for name in PACKAGE_ARTIFACTS},
                                                 gen_checks=dict(gen02='ok', gen04_valid=True, gen05_valid=True,
                                                                 verify_package=True))
            atomic_json(selective_journal_dir(args, inputs) / 'last-report.json',
                        read_json(report_path))
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
    preparing.add_argument('--selective', action='store_true',
                           help='Выборочный режим SEL-05: план SEL-04, совместимый кэш/снимок, '
                                'ограниченные ИИ-батчи LEX только под --allow-network, GEN-02..05, '
                                'VALID/NEEDS_MORE_EVIDENCE/IMPOSSIBLE_UNDER_CONSTRAINTS')
    preparing.add_argument('--selective-config', type=Path,
                           help='Границы ресурсов выборочного режима; по умолчанию tools/selective_config.json')
    preparing.add_argument('--plan-config', type=Path,
                           help='Конфиг планировщика SEL-04; по умолчанию tools/request_plan_config.json')
    preparing.add_argument('--plan-selection-config', type=Path,
                           help='Служебный конфиг полос отбора для планировщика; по умолчанию реальные полосы GEN-02')
    preparing.add_argument('--iterations', type=int,
                           help='Циклов план/ИИ/GEN в раунде; по умолчанию из --selective-config')
    preparing.add_argument('--max-new-decisions', type=int,
                           help='Максимум НОВЫХ ИИ-решений одного вызова LEX (категории и пригодность отдельно)')
    preparing.add_argument('--max-batches', type=int, help='Максимум батчей модели одного вызова LEX')
    preparing.add_argument('--time-budget', type=float, help='Лимит секунд одного вызова LEX')
    preparing.add_argument('--total-new-decisions', type=int,
                           help='Совокупный лимит новых ИИ-решений всех вызовов DATA-01')
    preparing.add_argument('--total-time-budget', type=float,
                           help='Совокупный лимит времени вызова prepare; < task_timeout=3600 оркестратора')
    installing = modes.add_parser('install', help='Автоматическая проверка и установка готового пакета, без сети')
    installing.add_argument('--out', type=Path, default=ROOT / 'data')
    for mode in (preparing, installing):
        mode.add_argument('--package', type=Path, default=ROOT / '.local/v2-package')
        mode.add_argument('--jvm-classpath', help='Свежесобранный core+audit classpath; иначе offline Gradle')
        mode.add_argument('--java', default='java')
    args = parser.parse_args()
    selective_only = ('selective_config', 'plan_config', 'plan_selection_config', 'iterations',
                      'max_new_decisions', 'max_batches', 'time_budget', 'total_new_decisions',
                      'total_time_budget')
    if args.mode == 'prepare' and not args.selective and \
            any(getattr(args, name, None) is not None for name in selective_only):
        parser.error('Флаги выборочного режима требуют --selective')
    termination.become_group_leader()
    termination.install_termination_handler()
    try:
        print(json.dumps(prepare(args) if args.mode == 'prepare' else install(args), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
