#!/usr/bin/env python3
"""GEN-01: reproducible offline candidate measurements, without campaign selection."""
import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time

import assess_word_suitability as semantic
import build_dictionary as dictionary
import build_local_definitions as definitions
import clean_dictionary as clean
from build_game_data import frequencies
from game_data_providers import atomic_json, json_digest
from game_data_quality import normalize_form
from prepare_frequency import resolve_frequency_list
import word_categories as thematic

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'tools'
SCHEMA = 'level-word-profiles-v1'
CONFIG = TOOLS / 'level_profile_config.json'


def load_config(path):
    document = json.loads(Path(path).read_text(encoding='utf-8'))
    if document.get('schema') != 'level-profile-config-v1':
        raise ValueError('Неверная схема параметров профиля')
    names = {'source_min_length', 'source_max_length', 'answer_min_length', 'counted_min_length',
             'min_observed_occurrences', 'suffix_lengths', 'preferred_source_bypass',
             'semantic_common_min_occurrences', 'semantic_common_max_rank'}
    if set(document) != names | {'schema'}:
        raise ValueError('Неверный набор параметров профиля')
    for name in names:
        item = document[name]
        if (not isinstance(item, dict) or set(item) != {'value', 'unit', 'rationale'} or
                not all(isinstance(item[k], str) and item[k].strip() for k in ('unit', 'rationale'))):
            raise ValueError('Нужны единицы и обоснование: ' + name)
    config = {name: document[name]['value'] for name in names}
    for name in names - {'suffix_lengths', 'preferred_source_bypass'}:
        if type(config[name]) is not int or config[name] <= 0:
            raise ValueError('Неверный порог: ' + name)
    if (config['semantic_common_min_occurrences'] != semantic.POLICY['common_min_occurrences'] or
            config['semantic_common_max_rank'] != semantic.POLICY['common_max_rank']):
        raise ValueError('Пороги узнаваемости должны соответствовать LEX-04')
    if (config['source_min_length'] != 8 or config['source_max_length'] != 13 or
            config['answer_min_length'] != 3 or config['counted_min_length'] != 4 or
            type(config['preferred_source_bypass']) is not bool or
            not isinstance(config['suffix_lengths'], list) or not config['suffix_lengths'] or
            any(type(n) is not int or not 1 <= n <= 13 for n in config['suffix_lengths']) or
            config['suffix_lengths'] != sorted(set(config['suffix_lengths']))):
        raise ValueError('Параметры нарушают контракт GEN-01')
    return config


def read_universe(path):
    if path.suffix == '.json':
        value = json.loads(path.read_text(encoding='utf-8'))
        words = set(value['words'])
        digest = hashlib.sha256(('\n'.join(sorted(words)) + '\n').encode()).hexdigest()
        if value.get('sha256') != digest:
            raise ValueError('Повреждённый снимок частотного универсума')
        return words
    return set(dictionary.read_list(path))


def fingerprint(paths):
    """Names distinguish roles; bytes (not mtimes/locations) determine identity."""
    return {name: dictionary.sha256_file(path) for name, path in sorted(paths.items())}


def processor_identity():
    directory = ROOT / 'core/src/main/kotlin/com/example/sostavslova/core'
    result = {name: dictionary.sha256_file(directory / name) for name in ('DefinitionHint.kt', 'HintText.kt')}
    result['DefinitionHintDatasetAudit.kt'] = dictionary.sha256_file(
        ROOT / 'core/src/test/kotlin/com/example/sostavslova/core/DefinitionHintDatasetAudit.kt')
    return result


def hint_header(database, words_path):
    directory = ROOT / 'core/src/main/kotlin/com/example/sostavslova/core'
    versions = {name: re.search(r'const val VERSION = "([^"]+)"',
                (directory / file).read_text()).group(1)
                for name, file in [('masker_version', 'DefinitionHint.kt'), ('cleaner_version', 'HintText.kt')]}
    return dict(schema='definition-hint-audit-v1', database_sha256=dictionary.sha256_file(database),
                words_sha256=dictionary.sha256_file(words_path), processor_sha256=processor_identity(), **versions)


def export_hint_inputs(database, words_path, output):
    """Export the real DB for core:auditDefinitionHints. Never inspect guessable."""
    words = set(dictionary.read_list(words_path))
    rows = defaultdict(list)
    with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True) as db:
        metadata = {word: (json.loads(forms), json.loads(related)) for word, forms, related in
                    db.execute('SELECT word, forms, related FROM word_metadata')}
        for word, text in db.execute('SELECT word,text FROM definitions ORDER BY word,ord,id'):
            rows[word].append(text)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('w', encoding='utf-8') as target:
        target.write(json.dumps(hint_header(database, words_path), ensure_ascii=False) + '\n')
        for word in sorted(words):
            forms, related = metadata.get(word, ([word], []))
            target.write(json.dumps(dict(word=word, forms=forms, related=related,
                                         definitions=[] if word in thematic.ROLE_EXCLUSIONS else rows[word]), ensure_ascii=False) + '\n')


def read_hints(path, expected_header, words):
    with path.open(encoding='utf-8') as source:
        header = json.loads(next(source))
        if any(header.get(k) != v for k, v in expected_header.items()):
            raise ValueError('JVM-подсказки устарели: повторите экспорт и :core:auditDefinitionHints')
        hints = {}
        for line in source:
            row = json.loads(line)
            word, hint = row['word'], row['hint']
            if word not in words or word in hints or (word in thematic.ROLE_EXCLUSIONS and hint is not None) or (hint is not None and
                    (not isinstance(hint, str) or not hint.strip() or len(hint) > 140)):
                raise ValueError('Невалидный или повторный результат JVM')
            # Only suitability and hash are retained, never dictionary article text.
            hints[word] = {'useful': hint is not None, 'sha256': json_digest(hint)}
    if set(hints) != set(words):
        raise ValueError('JVM-экспорт не покрывает весь игровой словарь')
    return hints


class MultisetIndex:
    """Trie of sorted letters; one traversal per feasible signature, including anagrams."""
    def __init__(self, words):
        self.root = {}
        for word in sorted(set(map(normalize_form, words))):
            node = self.root
            for char in sorted(word):
                node = node.setdefault(char, {})
            node.setdefault('', []).append(word)

    def search(self, source):
        source = normalize_form(source)
        counts, result = Counter(source), []

        def visit(node):
            result.extend(node.get('', ()))
            for char, child in node.items():
                if char and counts[char]:
                    counts[char] -= 1
                    visit(child)
                    counts[char] += 1
        visit(self.root)
        return sorted(word for word in result if word != source)


def preserve_role_labels(dump, records):
    """Retain distinctions folded into restricted_usage by lexical cleanup.

    Only closed labels are retained; neither article text nor arbitrary raw tags.
    In particular religious usage must never become a preferred-source bypass.
    """
    tags = {'historical': 'historical', 'religious': 'religious', 'feminine-of': 'derived_feminine',
            'slang': 'slang', 'abbreviation': 'abbreviation', 'diminutive': 'diminutive'}
    patterns = [(r'истор|советск', 'historical'), (r'религ|церк|библ', 'religious'),
                (r'женск', 'derived_feminine')]
    collected = defaultdict(set)
    opener = gzip.open if str(dump).endswith('.gz') else open
    with opener(dump, 'rt', encoding='utf-8') as source:
        for line in source:
            if not line.strip():
                continue
            entry = json.loads(line)
            word = normalize_form(entry.get('word', ''))
            if (word not in records or entry.get('pos') != 'noun' or entry.get('lang_code') != 'ru'
                    or entry.get('word') != entry.get('word', '').lower()):
                continue
            for value in [entry, *entry.get('senses', [])]:
                collected[word].update(tags[t] for t in value.get('tags', []) if t in tags)
                for label in value.get('raw_tags', []):
                    if isinstance(label, str):
                        collected[word].update(name for pattern, name in patterns if re.search(pattern, label))
    for word, labels in collected.items():
        records[word]['semantic_labels'] = sorted(labels)


def labels_for(word, record, cleanup):
    labels = {reason for sense in record['senses'] for reason in sense['reasons']} | set(record.get('semantic_labels', []))
    if any(sense['specialist'] for sense in record['senses']):
        labels.add('specialist')
    # LEX's conservative restricted_usage also covers these distinctions.
    for label in record.get('labels', []):
        for pattern, value in [(r'истор|советск', 'historical'), (r'религ|церк|библ', 'religious'),
                               (r'женск', 'derived_feminine')]:
            if re.search(pattern, label):
                labels.add(value)
    if word in cleanup['lexicalized_diminutives']:
        labels.discard('diminutive')
    elif 'diminutive' in labels:
        labels.add('productive_diminutive')
    if labels - semantic.LABELS:
        labels = (labels & semantic.LABELS) | {'unknown_label'}
    return sorted(labels)


def measurement_inputs(words, records, ranks, lexical, categories, cleanup, source_candidates=(), source_only=()):
    """No article text in the semantic boundary; only preliminary reachability."""
    index = MultisetIndex(words)
    measured = words | semantic.PREFERRED_SOURCES | set(source_candidates)
    source_answers = {w: index.search(w) for w in sorted(measured)
                      if 8 <= len(w) <= 13 and w not in thematic.ROLE_EXCLUSIONS
                      and records.get(w, {}).get('sources')}
    reachable_targets = {answer for answers in source_answers.values() for answer in answers}
    metadata, candidates = {}, []
    for word in sorted(measured):
        if word not in records:
            continue
        record = records[word]
        noun = bool(record['sources'])
        labels = labels_for(word, record, cleanup)
        observed = ranks[word]
        category = categories.get(word, thematic.word_category([]))
        metadata[word] = {'noun': noun, 'labels': labels, 'observed_frequency': {
            **observed, 'status': 'observed' if observed['frequency'] else 'not_observed_in_this_corpus'},
            'category': category['category'], 'category_source': category['category_source']}
        roles = ['target'] if word in words else []
        if 8 <= len(word) <= 13:
            roles.append('source')
        for role in roles:
            role_decision = lexical[word]['roles'][role]['decision']
            if role == 'target' and word in source_only:
                role_decision = 'exclude'
            if (role == 'source' and word in source_candidates and noun and
                    lexical[word]['reasons'] and set(lexical[word]['reasons']) <= {
                        'usage_unconfirmed', 'low_frequency', 'narrow_term', 'restricted_usage_frequency',
                        'compound_candidate', 'compound_abbreviation_candidate', 'abbreviation', 'obsolete',
                        'rare', 'restricted_usage'}):
                # A candidate, not a keep decision: LEX-04 must justify it.
                role_decision = 'review'
            # Owner permits the source's historical/low-frequency exception only.
            if (role == 'source' and word in semantic.PREFERRED_SOURCES and noun and
                    set(labels) <= semantic.PREFERRED_SOURCE_LABELS):
                role_decision = 'keep'
            candidates.append({'word': word, 'role': role, 'features': {
                'noun': noun, 'labels': labels, 'role_decision': role_decision,
                'reachable': (any(len(a) >= 4 for a in source_answers.get(word, ()))
                              if role == 'source' else word in reachable_targets), **observed}})
    return metadata, {'schema': 'word-suitability-input-v1', 'candidates': candidates}


def assessments_for(document, supplied=None):
    items = semantic.input_items(document)
    answers, context = {}, {'provider': 'rules-only', 'model': None}
    if supplied is not None:
        if (supplied.get('schema') != semantic.VERSION or
                supplied.get('input_sha256') != json_digest(document)):
            raise ValueError('Семантические оценки относятся к другому входу')
        context = supplied['context']
        rows = supplied['assessments']
        if {row['id'] for row in rows} != {item['id'] for item in items} or len(rows) != len(items):
            raise ValueError('Неполный/повторный набор семантических оценок')
        by_id = {item['id']: item for item in items}
        for row in rows:
            item = by_id[row['id']]
            if any(row.get(key) != value for key, value in item.items()):
                raise ValueError('Устаревшие признаки семантической оценки')
            semantic.validate_results({'results': [row['assessment']]}, [item], semantic.POLICY)
            if row.get('assessment_sha256') != json_digest([context, item, row['assessment']]):
                raise ValueError('Повреждена семантическая оценка')
            if row['assessment_source'] == 'ai':
                answers[item['id']] = row['assessment']
    # Reapply hard exclusions, preferred-source policy and conservative pending states.
    output = semantic.apply_decisions(items, answers, context)
    return {(row['word'], row['role']): row for row in output['assessments']}


def metrics(words, metadata, config):
    lengths, starts, contains, suffixes, themes = Counter(), Counter(), Counter(), {}, Counter()
    starts4, contains4 = Counter(), Counter()
    for word in words:
        lengths[len(word)] += 1
        starts[word[0]] += 1
        contains.update(set(word))  # Number of answers, never number of repeated letters.
        if len(word) >= config['counted_min_length']:
            starts4[word[0]] += 1
            contains4.update(set(word))
        for size in config['suffix_lengths']:
            if len(word) >= size:
                suffixes.setdefault(word[-size:], Counter())[len(word)] += 1
        value = metadata[word]
        if value['category'] != 'other' and not value['category_source'].startswith('unknown:'):
            themes[value['category']] += 1
    return {'count': len(words), 'count_4plus': sum(v for n, v in lengths.items() if n >= 4),
            'lengths': {str(n): v for n, v in sorted(lengths.items())}, 'initial': dict(sorted(starts.items())),
            'initial_4plus': dict(sorted(starts4.items())), 'containing': dict(sorted(contains.items())),
            'containing_4plus': dict(sorted(contains4.items())),
            'suffix_lengths': {s: {str(n): count for n, count in sorted(v.items())} for s, v in sorted(suffixes.items())},
            'themes': dict(sorted(themes.items()))}


def build_profiles(words, metadata, assessments, hints, previous, config):
    words = {normalize_form(word) for word in words if len(normalize_form(word)) >= 3}
    index = MultisetIndex(words)
    previous_by_word = defaultdict(list)
    for level in previous:
        previous_by_word[normalize_form(level['word'])].append(level['index'])

    def allowed(word, role):
        row = assessments.get((word, role))
        if word in thematic.ROLE_EXCLUSIONS or not row or row['assessment']['status'] != 'suitable':
            return False
        bypass = (role == 'source' and config['preferred_source_bypass'] and word in semantic.PREFERRED_SOURCES
                  and set(metadata[word]['labels']) <= semantic.PREFERRED_SOURCE_LABELS)
        return bypass or metadata[word]['observed_frequency']['frequency'] >= config['min_observed_occurrences']

    recognizable = {w for w in words if allowed(w, 'target')}
    profiles = {}
    for word, value in sorted(metadata.items()):
        if not value['noun'] or not config['source_min_length'] <= len(word) <= config['source_max_length']:
            continue
        # Explicitly prohibited words have no source profile, even if present in dictionary input.
        if word in thematic.ROLE_EXCLUSIONS:
            continue
        answers = index.search(word)
        familiar = [w for w in answers if w in recognizable]
        riddles = [w for w in familiar if hints.get(w, {}).get('useful', False)]
        row = assessments.get((word, 'source'))
        profiles[word] = {'source_eligible': allowed(word, 'source') and any(len(w) >= 4 for w in familiar),
            'source_assessment': row, 'preferred_source': word in semantic.PREFERRED_SOURCES,
            'bypass': ('owner-preferred-source: low frequency / historical labels only; task reachability pending'
                       if word in semantic.PREFERRED_SOURCES and allowed(word, 'source') and
                       (value['labels'] or value['observed_frequency']['frequency'] < config['min_observed_occurrences']) else None),
            'previous_source_levels': sorted(previous_by_word[word]), 'answers': answers,
            'recognizable_answers': familiar, 'assigned_targets': familiar, 'riddle_targets': riddles,
            'all': metrics(answers, metadata, config), 'recognizable': metrics(familiar, metadata, config)}
    return profiles


def write_cache(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Deterministic gzip: no filename and no current timestamp in its header.
    temporary = path.with_suffix('.tmp')
    with temporary.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as zipped:
        zipped.write(json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode())
    temporary.replace(path)


def read_cache(path, inputs):
    with gzip.open(path, 'rt', encoding='utf-8') as source:
        result = json.load(source)
    if (result.get('schema') != SCHEMA or result.get('inputs') != inputs or
            result.get('payload_sha256') != json_digest(result['payload'])):
        raise ValueError('Повреждённый или несовместимый кэш профилей')
    return result


def prepare(args, paths, words):
    stage_inputs = {k: v for k, v in fingerprint(paths).items() if k not in {'hints', 'suitability', 'levels'}}
    stage = args.cache / 'inputs' / (json_digest(stage_inputs) + '.json.gz')
    if stage.exists():
        return read_cache(stage, stage_inputs)['payload']
    cleanup = clean.load_config(args.selection_config)
    universe = read_universe(args.frequency_universe)
    if not (words | semantic.PREFERRED_SOURCES) <= universe:
        raise ValueError('Частотный универсум не покрывает измеряемые слова')
    records = clean.extract_records(args.dump, universe, cleanup)
    preserve_role_labels(args.dump, records)
    frequency_path = resolve_frequency_list(args.frequency_list, args.manifest)
    ranks = frequencies({w: {'eligible': True, 'forms': r['forms']} for w, r in records.items()},
                        frequency_list=frequency_path)['words']
    lexical = clean.decide(records, ranks, cleanup)
    articles, _ = definitions.cached_articles(args.dump, stage_inputs['dump'], cleanup, args.cache / 'articles')
    settings = definitions.load_settings(paths['definition_config'])
    removed = set(dictionary.read_list(paths['removed_definitions']))
    rows, _, _ = definitions.select_definitions(words, articles, dict(settings, max_meanings=10**9),
                                                removed, thematic.load_taxonomy(), deduplicate=False)
    by_word = defaultdict(list)
    for row in rows:
        by_word[row['word']].append(row)
    categories = {w: thematic.word_category(by_word[w]) for w in words}
    if args.categories:
        index = json.loads(args.categories.read_text(encoding='utf-8'))
        if (index.get('schema') != 'word-category-index-v1' or
                index.get('words_sha256') != dictionary.sha256_file(args.words) or
                index.get('database_sha256') != dictionary.sha256_file(args.database) or
                set(index['word_metadata']) != words):
            raise ValueError('Категории относятся к другому словарю/БД')
        for word, row in index['word_metadata'].items():
            if row['category'] not in thematic.load_taxonomy() or (
                    (row['category'] == 'other') != row['category_source'].startswith('unknown:')):
                raise ValueError('Невалидная категория')
        categories = index['word_metadata']
    manifest = json.loads(args.manifest.read_text())
    candidates = manifest.get('selection', {}).get('source_candidates', [])
    source_only = manifest.get('selection', {}).get('source_only', [])
    metadata, document = measurement_inputs(words, records, ranks, lexical, categories, cleanup, candidates, source_only)
    payload = {'metadata': metadata, 'semantic_input': document}
    write_cache(stage, dict(schema=SCHEMA, inputs=stage_inputs, payload=payload, payload_sha256=json_digest(payload)))
    return payload


def run(args):
    started = time.perf_counter()
    config = load_config(args.config)
    manifest = json.loads(args.manifest.read_text())
    if args.frequency_universe is None:
        args.frequency_universe = TOOLS / 'cache/cleanup' / ('source-vocabulary-' + manifest['source']['sha256_input'] + '.json')
    frequency_path = resolve_frequency_list(args.frequency_list, args.manifest)
    paths = {'words': args.words, 'manifest': args.manifest, 'database': args.database, 'dump': args.dump,
             'frequency': frequency_path, 'frequency_universe': args.frequency_universe, 'config': args.config, 'selection': args.selection_config,
             'levels': args.previous_levels, 'definition_config': TOOLS / 'definition_import_config.json',
             'removed_definitions': TOOLS / 'overrides/remove_definitions.txt'}
    paths.update({'code:' + p.name: p for p in TOOLS.glob('*.py')})
    paths['core:DefinitionHintDatasetAudit.kt'] = ROOT / 'core/src/test/kotlin/com/example/sostavslova/core/DefinitionHintDatasetAudit.kt'
    paths.update({'taxonomy': thematic.TAXONOMY_PATH, 'vectors': TOOLS / 'hint_vectors.json'})
    for name in ('DefinitionHint.kt', 'HintText.kt'):
        paths['core:' + name] = ROOT / 'core/src/main/kotlin/com/example/sostavslova/core' / name
    if args.categories:
        paths['categories'] = args.categories
    if args.suitability:
        paths['suitability'] = args.suitability
    inputs = fingerprint(paths)
    if inputs['dump'] != manifest['source']['sha256_input'] or inputs['words'] != manifest['sha256_words_txt']:
        raise ValueError('Дамп/словарь не совпадает с манифестом')
    universe_sha = hashlib.sha256(('\n'.join(sorted(read_universe(args.frequency_universe))) + '\n').encode()).hexdigest()
    if universe_sha != manifest['selection']['input_words_sha256']:
        raise ValueError('Частотный универсум не совпадает с зафиксированным входом FREQ-01')
    if inputs['database'] != manifest['definitions']['sha256']:
        raise ValueError('БД не совпадает с манифестом')
    words = set(dictionary.read_list(args.words))
    prepared = prepare(args, paths, words)
    args.reports.mkdir(parents=True, exist_ok=True)
    atomic_json(args.reports / 'suitability_input.json', prepared['semantic_input'])
    if args.prepare_only:
        return {'prepared_words': len(prepared['metadata']), 'semantic_input': str(args.reports / 'suitability_input.json')}
    paths['hints'] = args.hints
    inputs['hints'] = dictionary.sha256_file(args.hints)
    hints = read_hints(args.hints, hint_header(args.database, args.words), words)
    supplied = json.loads(args.suitability.read_text()) if args.suitability else None
    assessments = assessments_for(prepared['semantic_input'], supplied)
    key = json_digest([SCHEMA, inputs])
    output = args.cache / (key + '.json.gz')
    hit = output.exists()
    if hit:
        cached = read_cache(output, inputs)
    else:
        profiles = build_profiles(words, prepared['metadata'], assessments, hints,
                                  json.loads(args.previous_levels.read_text()), config)
        payload = {'words': prepared['metadata'], 'assessments': list(assessments.values()),
                   'hints': hints, 'profiles': profiles}
        cached = dict(schema=SCHEMA, inputs=inputs, payload=payload, payload_sha256=json_digest(payload))
        write_cache(output, cached)
    profiles = cached['payload']['profiles']
    counts = Counter(row['assessment_source'] for row in assessments.values())
    summary = {'schema': SCHEMA, 'cache_key': key, 'cache_path': str(output), 'cache_hit': hit,
               'elapsed_seconds': round(time.perf_counter() - started, 3), 'cache_bytes': output.stat().st_size,
               'dictionary_words': len(words), 'profiles': len(profiles),
               'eligible_sources': sum(p['source_eligible'] for p in profiles.values()),
               'semantic_assessment_sources': dict(sorted(counts.items())),
               'jvm_useful_hints': sum(v['useful'] for v in hints.values()),
               'riddle_words': len({w for p in profiles.values() for w in p['riddle_targets']}),
               'inputs': inputs}
    atomic_json(args.reports / 'summary.json', summary)
    lines = ['# Профили исходников — GEN-01', '',
             f"Слов: {len(words)}; профилей: {len(profiles)}; пригодных исходников: {summary['eligible_sources']}.",
             f"Время полного вызова: {summary['elapsed_seconds']} с; gzip-кэш: {summary['cache_bytes']} байт; попадание: {hit}.",
             f"Ключ: `{key}`. Кэш: `{output}`.", '',
             'Наблюдаемая частота — сумма уникальных нормализованных словоформ FREQ-01 в корпусе.',
             'Ноль: «не наблюдалось в этом корпусе», а не оценка редкости или известности.',
             'ИИ-пригодность хранится отдельно (assessment_source=ai); частоту ИИ не заменяет.',
             f'Источники оценок: {dict(sorted(counts.items()))}. Pending не допускается к назначениям.',
             'Пригодность source предварительная: достижимость конкретных заданий ещё не проверялась.',
             'T1/T2: только recognizable.count_4plus и recognizable.initial_4plus.',
             'Подсказки: результат JVM на экспорте реальной БД; guessable не используется.', '',
             '| Исходник | Наблюдаемая частота | Статус корпуса | Оценка | Источник оценки | Пригоден как исходник | Все | Узнаваемые 4+ | Загадки | Был на уровнях |',
             '|---|---:|---|---|---|---|---:|---:|---:|---|']
    for word, p in profiles.items():
        observed = prepared['metadata'][word]['observed_frequency']
        row = assessments[(word, 'source')]
        lines.append(f"| {word} | {observed['frequency']} | {observed['status']} | {row['assessment']['status']} | "
                     f"{row['assessment_source']} | {p['source_eligible']} | {p['all']['count']} | {p['recognizable']['count_4plus']} | "
                     f"{len(p['riddle_targets'])} | {p['previous_source_levels']} |")
    (args.reports / 'LEVEL_PROFILES.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--words', type=Path, default=ROOT / 'data/words.txt')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'data/manifest.json')
    parser.add_argument('--database', type=Path, default=ROOT / 'data/definitions.db')
    parser.add_argument('--dump', type=Path, default=ROOT / '.local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz')
    parser.add_argument('--frequency-list', type=Path)
    parser.add_argument('--frequency-universe', type=Path, help='Фиксированный снимок FREQ-01 JSON либо words.txt; по умолчанию source-vocabulary из кэша cleanup')
    parser.add_argument('--config', type=Path, default=CONFIG)
    parser.add_argument('--selection-config', type=Path, default=TOOLS / 'selection_config.json')
    parser.add_argument('--previous-levels', type=Path, default=ROOT / 'data/levels.json')
    parser.add_argument('--categories', type=Path, help='Необязательный актуальный индекс LEX-03; иначе локальные правила LEX-02')
    parser.add_argument('--suitability', type=Path, help='word_suitability.json LEX-04 для экспортированного входа; иначе правила и pending')
    parser.add_argument('--hints', type=Path, default=ROOT / 'build/reports/level_profiles/hints.jvm.jsonl')
    parser.add_argument('--cache', type=Path, default=TOOLS / 'cache/level_profiles')
    parser.add_argument('--reports', type=Path, default=ROOT / 'build/reports/level_profiles')
    parser.add_argument('--export-hints', type=Path, help='Только экспорт БД для JVM-аудита')
    parser.add_argument('--prepare-only', action='store_true', help='Только признаки для LEX-04, без назначения уровней')
    args = parser.parse_args()
    try:
        if args.export_hints:
            export_hint_inputs(args.database, args.words, args.export_hints)
            print(str(args.export_hints))
        else:
            print(json.dumps(run(args), ensure_ascii=False, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError, StopIteration, sqlite3.Error) as error:
        parser.exit(2, 'Ошибка профилирования: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
