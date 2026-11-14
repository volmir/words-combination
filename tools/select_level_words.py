#!/usr/bin/env python3
"""GEN-02: bounded deterministic source selection from a verified GEN-01 snapshot."""
import argparse
from collections import Counter
from copy import deepcopy
import csv
from functools import lru_cache
import gzip
import hashlib
import json
import math
from pathlib import Path
import random
import re
import time

import pick_level_words as measurements
from assess_word_suitability import PREFERRED_SOURCES
from build_dictionary import read_list, sha256_file
from game_data_providers import atomic_json, json_digest
from game_data_quality import normalize_form
from word_categories import ROLE_EXCLUSIONS

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'tools/level_selection_config.json'
ALPHABET = ROOT / 'core/src/main/kotlin/com/example/sostavslova/core/Alphabet.kt'
SCHEMA = 'level-word-proposal-v1'
# Search-only penalties. They rank intermediate candidates; they never turn a
# band or Jaccard violation into an installable result. The final validator in
# evaluate() still uses only the explicit policy thresholds.
DEFICIT_WEIGHT = 100.0
BAND_MISS_WEIGHT = 1000.0
ALPHABET_MISS_WEIGHT = 1000000.0
RETAIN_WEIGHT = 500.0
MEAN_JACCARD_WEIGHT = 100.0
TIME_CHECK_INTERVAL = 512
# OPT-05: temporary explicit inputs for the owner-approved policy ladder.
# OPT-09 will move these named policies into a versioned tools/ config. Until
# then load_config() and tools/level_selection_config.json keep the STRICT
# values as global defaults; no caller gets a softer threshold implicitly.
PROVISIONAL_POLICY_ORDER = ('STRICT', 'SOFT-A', 'SOFT-B')
PROVISIONAL_POLICIES = {
    'STRICT': dict(top_band_min=250, mean_jaccard_max=0.03,
                   max_jaccard_max=0.15, retain_min=8),
    'SOFT-A': dict(top_band_min=230, mean_jaccard_max=0.04,
                   max_jaccard_max=0.18, retain_min=8),
    'SOFT-B': dict(top_band_min=210, mean_jaccard_max=0.045,
                   max_jaccard_max=0.20, retain_min=6),
}


def stable_seed(*parts):
    """Process-independent integer seed; never use Python's salted hash()."""
    text = '|'.join(str(part) for part in parts)
    return int.from_bytes(hashlib.sha256(text.encode('utf-8')).digest()[:8], 'big')


def policy_thresholds(name):
    """Temporary OPT-05 policy inputs; OPT-09 replaces this by versioned config."""
    if name not in PROVISIONAL_POLICIES:
        raise ValueError('Неизвестная политика: ' + str(name))
    return dict(PROVISIONAL_POLICIES[name])


def effective_config(config, thresholds=None, policy=None):
    """Return a config copy with explicit thresholds, never mutate the default."""
    result = deepcopy(config)
    if thresholds is not None:
        validate_thresholds(thresholds, result)
        result['mean_jaccard_max'] = thresholds['mean_jaccard_max']
        result['max_jaccard_max'] = thresholds['max_jaccard_max']
        result['retain_min'] = thresholds['retain_min']
        result['retain_max'] = config['retain_max']
        for band in result['bands']:
            if band['first'] == 26 and band['last'] == 30:
                band['min'] = thresholds['top_band_min']
                band['max'] = 700
    return result


def validate_thresholds(thresholds, config):
    required = {'top_band_min', 'mean_jaccard_max', 'max_jaccard_max', 'retain_min'}
    if set(thresholds) != required:
        raise ValueError('Пороги политики должны содержать ровно: ' + ', '.join(sorted(required)))
    if type(thresholds['top_band_min']) is not int or not 1 <= thresholds['top_band_min'] <= 700:
        raise ValueError('Неверная нижняя граница верхней полосы')
    for key in ('mean_jaccard_max', 'max_jaccard_max'):
        if type(thresholds[key]) not in (int, float) or not 0 < thresholds[key] <= 1:
            raise ValueError('Неверная граница Jaccard: ' + key)
    if thresholds['mean_jaccard_max'] > thresholds['max_jaccard_max']:
        raise ValueError('Средняя граница Jaccard выше максимальной')
    if type(thresholds['retain_min']) is not int or not 0 <= thresholds['retain_min'] <= config['retain_max']:
        raise ValueError('Неверная нижняя граница сохранённых исходников')


class RefinementResult(dict):
    """Structured refinement outcome; legacy (best_found, trace) unpacking works."""

    def __iter__(self):
        yield self['best_found']
        yield self['trace']

    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError as error:
            raise AttributeError(name) from error


class SearchTrace(list):
    """Deterministic trace rows plus run statistics for re-reporting."""

    stats = {}


def read_alphabet(path=ALPHABET):
    text = Path(path).read_text(encoding='utf-8')
    match = re.search(r'const val LETTERS = "([^"]+)"', text)
    if not match or len(match[1]) != 32 or len(set(match[1])) != 32 or 'ё' in match[1]:
        raise ValueError('Ожидались 32 знака Alphabet.LETTERS после ё→е')
    return match[1]


def load_config(path=CONFIG):
    c = json.loads(Path(path).read_text(encoding='utf-8'))
    if (c.get('schema') != 'level-selection-config-v1' or c['levels'] != 30 or
            c['retain_min'] != 8 or c['retain_max'] != 15):
        raise ValueError('Нужны 30 уровней и сохранение 8–15 старых исходников')
    for key in ('seed', 'levels', 'retain_min', 'retain_max'):
        if type(c[key]) is not int:
            raise ValueError('Ожидалось целое: ' + key)
    for key in ('mean_jaccard_max', 'max_jaccard_max'):
        if type(c[key]) not in (int, float) or not 0 < c[key] <= 1:
            raise ValueError('Неверная граница Jaccard')
    if c['mean_jaccard_max'] > c['max_jaccard_max']:
        raise ValueError('Средняя граница Jaccard выше максимальной')
    expanded = []
    for b in c['bands']:
        if (any(type(b[k]) is not int for k in ('first', 'last', 'min', 'max')) or
                not 1 <= b['first'] <= b['last'] <= 30 or not 0 < b['min'] <= b['max']):
            raise ValueError('Неверный диапазон ответов')
        expanded.extend((n, b['min'], b['max']) for n in range(b['first'], b['last'] + 1))
    if ([x[0] for x in expanded] != list(range(1, 31)) or
            any(lo != 40 or hi != 300 for _, lo, hi in expanded[:5]) or
            any(lo != 250 or hi != 700 for _, lo, hi in expanded[25:])):
        raise ValueError('Диапазоны должны покрывать 1–30: ранние 40–300, поздние 250–700')
    rotation = c['master_types']
    if (len(rotation) != 5 or any(len(types) != 5 or len(set(types)) != 5 or
            not set(types) <= {f'T{i}' for i in range(1, 8)} for types in rotation) or
            not {'T6', 'T7'} <= set().union(*map(set, rotation))):
        raise ValueError('Неверная предварительная ротация этапа Мастер')
    for key in ('long_min_length', 'suffix_min_length', 'reserve_per_task'):
        if type(c['planning'][key]) is not int or not 1 <= c['planning'][key] <= 13:
            raise ValueError('Неверный параметр планирования: ' + key)
    for key in ('restarts', 'max_iterations', 'shortlist_per_slot', 'carriers_per_letter',
                'refinement_restarts', 'refinement_iterations'):
        if type(c['search'][key]) is not int or c['search'][key] <= 0:
            raise ValueError('Неверный лимит поиска: ' + key)
    return c


def jaccard(left, right):
    left, right = set(left), set(right)
    return len(left & right) / len(left | right) if left or right else 0.0


def matching(options):
    """Stable augmenting paths; shared keys enforce T3/T4's common namespace."""
    owner = {}

    def augment(request, seen):
        for value in sorted(options[request]):
            if value in seen:
                continue
            seen.add(value)
            if value not in owner or augment(owner[value], seen):
                owner[value] = request
                return True
        return False

    for request in sorted(options, key=lambda k: (len(options[k]), k)):
        augment(request, set())
    assigned = {request: value for value, request in sorted(owner.items())}
    return {'required': len(options), 'matched': len(assigned),
            'unmatched': sorted(set(options) - set(assigned)), 'witness': assigned}


def load_snapshot(path, words_path, previous_path):
    """Consume an immutable measurement artifact; do not replay the dictionary build."""
    with gzip.open(path, 'rt', encoding='utf-8') as source:
        document = json.load(source)
    if (document.get('schema') != measurements.SCHEMA or
            document.get('payload_sha256') != json_digest(document['payload'])):
        raise ValueError('Повреждённый или несовместимый кэш профилей')
    inputs = document['inputs']
    if inputs['words'] != sha256_file(words_path) or inputs['levels'] != sha256_file(previous_path):
        raise ValueError('Словарь/история изменились: повторите GEN-01')
    # New downstream tools do not invalidate upstream measurements. All recorded
    # measurement code/configs must, however, still have exactly the same bytes.
    paths = {'config': ROOT / 'tools/level_profile_config.json',
             'selection': ROOT / 'tools/selection_config.json',
             'definition_config': ROOT / 'tools/definition_import_config.json',
             'removed_definitions': ROOT / 'tools/overrides/remove_definitions.txt',
             'taxonomy': ROOT / 'tools/categories.json', 'vectors': ROOT / 'tools/hint_vectors.json'}
    for name in inputs:
        if name.startswith('code:') and name != 'code:select_level_words.py':
            paths[name] = ROOT / 'tools' / name.removeprefix('code:')
        elif name.startswith('core:'):
            filename = name.removeprefix('core:')
            directory = 'test' if filename == 'DefinitionHintDatasetAudit.kt' else 'main'
            paths[name] = ROOT / f'core/src/{directory}/kotlin/com/example/sostavslova/core' / filename
    for name, file in paths.items():
        if name in inputs and sha256_file(file) != inputs[name]:
            raise ValueError('Устаревшие измерения GEN-01: ' + name)
    return document


class Selector:
    def __init__(self, payload, words, previous, config, alphabet, thresholds=None,
                 policy=None, time_limit_seconds=None, max_checks=None, seed=None, clock=None):
        self.profiles = dict(payload['profiles'])
        self.metadata = payload['words']
        self.words = set(map(normalize_form, words))
        self.previous = previous
        self.old = {normalize_form(row['word']) for row in previous}
        # Keep the unmodified caller config for the strict cross-check, and use
        # a copy with explicit policy thresholds for search/validation.
        self.default_config = deepcopy(config)
        self.config = effective_config(config, thresholds, policy)
        self.policy = policy or ('STRICT' if thresholds is None else 'CUSTOM')
        self.alphabet = alphabet
        self.size = self.config['levels']
        self.seed = self.config['seed'] if seed is None else seed
        self.time_limit_seconds = time_limit_seconds
        self.max_checks = max_checks
        self.clock = clock or time.monotonic
        self.deadline = None
        self.checks = 0
        self.timed_out = False
        self.search_stats = {}
        by_role = {role: dict(rules=0, ai=0, pending=0) for role in ('source', 'target')}
        for row in payload.get('assessments', []):
            by_role[row['role']][row['assessment_source']] += 1
        self.semantic_coverage = {
            'recorded': 'assessments' in payload,
            'pending': sum(counts['pending'] for counts in by_role.values())
                if 'assessments' in payload else None,
            'by_role': by_role}
        rejected = Counter()
        self.candidates = []
        for word in sorted(self.profiles):
            profile = self.profiles[word]
            if not profile['source_eligible']:
                rejected['source_ineligible'] += 1
            elif word in ROLE_EXCLUSIONS:
                rejected['role_exclusion'] += 1
            elif not 8 <= len(word) <= 13:
                rejected['length'] += 1
            elif normalize_form(word) != word:
                rejected['normalization'] += 1
            elif not set(word) <= set(alphabet):
                rejected['alphabet'] += 1
            else:
                self.candidates.append(word)
        self.eligible = set(self.candidates)
        observed = [w for w in self.words if self.metadata[w]['observed_frequency']['frequency'] > 0]
        self.top = set(sorted(observed, key=lambda w: (
            -self.metadata[w]['observed_frequency']['frequency'], w))[:5000])
        self.observed_count = len(observed)
        self.word_bits = {word: 1 << i for i, word in enumerate(sorted(self.words))}
        self.all_bits, self.rec_bits = {}, {}
        self.pair = lru_cache(maxsize=200000)(self._pair)
        # Reconstruct historical sources absent from profiles, for an honest baseline.
        absent = self.old - self.profiles.keys()
        if absent:
            index = measurements.MultisetIndex(self.words)
            if 'assessments' in payload:
                threshold = measurements.load_config(measurements.CONFIG)['min_observed_occurrences']
                recognizable = {row['word'] for row in payload['assessments'] if row['role'] == 'target'
                                and row['assessment']['status'] == 'suitable'
                                and row['features']['frequency'] >= threshold
                                and row['word'] not in ROLE_EXCLUSIONS}
            else:
                recognizable = set().union(*(set(p['recognizable_answers']) for p in self.profiles.values()))
            for w in sorted(absent):
                answers = index.search(w)
                self.profiles[w] = {'source_eligible': False, 'answers': answers,
                    'recognizable_answers': sorted(set(answers) & recognizable),
                    'assigned_targets': [], 'riddle_targets': [], 'recognizable': {
                        'count': len(set(answers) & recognizable)}, 'all': {'count': len(answers)}}
        for w, p in self.profiles.items():
            for table, key in ((self.all_bits, 'answers'), (self.rec_bits, 'recognizable_answers')):
                table[w] = sum(self.word_bits[a] for a in set(p[key]) if a in self.word_bits)
        self.top_bits = sum(self.word_bits[w] for w in self.top)
        self.options = {w: self.task_options(w) for w in self.candidates}
        self.local = {(slot, w): self.local_failures(slot, w)
                      for slot in range(self.size) for w in self.candidates}
        # Full search pools after only slot-independent hard pruning. All
        # locally admissible candidates are retained; range-only candidates are
        # kept for diagnostics and band repair, and the exact loss is reported.
        self.admissible_pools = {
            slot: tuple(sorted(w for w in self.candidates if not self.local[slot, w]))
            for slot in range(self.size)}
        self.range_pools = {
            slot: tuple(sorted(w for w in self.candidates
                               if self.local[slot, w] == ['recognizable_range']))
            for slot in range(self.size)}
        self.search_pools = {
            slot: tuple(sorted(set(self.admissible_pools[slot]) | set(self.range_pools[slot])))
            for slot in range(self.size)}
        self.refine_pools = {
            slot: (self.admissible_pools[slot] if self.admissible_pools[slot]
                   else self.range_pools[slot])
            for slot in range(self.size)}
        self.ranked_pools = {slot: tuple(self.ranked_candidates(slot)) for slot in range(self.size)}
        self.shortlists = [list(self.refine_pools[slot] or self.ranked_pools[slot])
                           for slot in range(self.size)]
        locally_inadmissible = Counter()
        for slot in range(self.size):
            for word in self.candidates:
                failures = self.local[slot, word]
                if failures and failures != ['recognizable_range']:
                    locally_inadmissible.update(failures)
        admissible_sets = {slot: set(self.admissible_pools[slot])
                           for slot in range(self.size)}
        admissible_omitted = sum(
            1 for slot in range(self.size) for word in self.candidates
            if not self.local[slot, word] and word not in admissible_sets[slot])
        self.pool_report = {
            'profiles_total': len(self.profiles),
            'eligible_source_count': len(self.candidates),
            'rejected_by_reason': dict(sorted(rejected.items())),
            'admissible_pool_total': sum(len(pool) for pool in self.admissible_pools.values()),
            'admissible_omitted_without_report': admissible_omitted,
            'range_only_pool_total': sum(len(pool) for pool in self.range_pools.values()),
            'locally_inadmissible_total': sum(
                len(self.candidates) - len(self.admissible_pools[slot]) -
                len(self.range_pools[slot]) for slot in range(self.size)),
            'locally_inadmissible_by_reason': dict(sorted(locally_inadmissible.items())),
            'fallback_slots': [slot + 1 for slot in range(self.size)
                               if not self.search_pools[slot]],
            'per_slot': {f'{slot + 1:02}': {
                'admissible': len(self.admissible_pools[slot]),
                'range_only': len(self.range_pools[slot]),
                'locally_inadmissible': (len(self.candidates) -
                                         len(self.admissible_pools[slot]) -
                                         len(self.range_pools[slot])),
                'search_pool': len(self.search_pools[slot]),
                'combined_profile_pool': len(self.candidates)}
                for slot in range(self.size)},
            'note': ('Жёсткое предварительное отсечение выполняется только по '
                     'независимым от слота условиям и пулам заданий. Все локально '
                     'допустимые кандидаты входят в поиск; сокращения shortlist=64 нет. '
                     'Кандидаты только с нарушением диапазона сохранены отдельно для '
                     'исправления полос и диагностики. Остальные локальные нарушения '
                     'перечислены в locally_inadmissible_by_reason; каждый кандидат '
                     'отнесён ровно к одной из трёх групп в отчёте.')}
        self.search_stats = {'seed': self.seed, 'policy': self.policy,
                             'time_limit_seconds': self.time_limit_seconds,
                             'max_checks': self.max_checks, 'checks': 0,
                             'timed_out': False, 'restarts': [], 'refinement': []}

    def band(self, slot, config=None):
        config = config or self.config
        return next(b for b in config['bands'] if b['first'] <= slot + 1 <= b['last'])

    def types(self, slot):
        return ([f'T{i}' for i in range(1, slot // 5 + 2)] if slot < 25
                else self.config['master_types'][slot - 25])

    def task_options(self, word):
        p, plan = self.profiles[word], self.config['planning']
        r = p['recognizable']
        return {
            'T1': {'count'} if r['count_4plus'] else set(),
            'T2': {k for k, v in r['initial_4plus'].items() if v},
            'T3': {w for w in p['riddle_targets'] if len(w) >= plan['long_min_length'] and w not in ROLE_EXCLUSIONS},
            'T4': set(p['riddle_targets']) - ROLE_EXCLUSIONS,
            'T5': {suffix for suffix, lengths in r['suffix_lengths'].items()
                   if any(int(n) >= plan['suffix_min_length'] and count for n, count in lengths.items())},
            'T6': {theme for theme, count in r['themes'].items() if count and theme != 'other'},
            'T7': {letter for letter, count in r['containing'].items() if count}}

    def local_failures(self, slot, word, config=None):
        if word not in self.eligible:
            return ['source_ineligible']
        b, failures = self.band(slot, config), []
        count = len(self.profiles[word]['recognizable_answers'])
        if not b['min'] <= count <= b['max']:
            failures.append('recognizable_range')
        failures.extend(t + '_empty' for t in self.types(slot) if not self.options[word][t])
        return failures

    def range_distance(self, slot, word, config=None):
        count, b = len(self.profiles[word]['recognizable_answers']), self.band(slot, config)
        return max(b['min'] - count, count - b['max'], 0) / b['min']

    def range_position(self, slot, word, config=None):
        """0 at the band minimum, 1 at its maximum; used only as a late tie-break."""
        count, b = len(self.profiles[word]['recognizable_answers']), self.band(slot, config)
        if word is None or not b['min'] <= count <= b['max']:
            return 0.0
        return ((count - b['min']) / (b['max'] - b['min'])
                if b['max'] > b['min'] else 1.0)

    def ranked_candidates(self, slot):
        """Complete ranked profile pool. No fixed 64-word cut is applied."""
        def rank(w):
            return (len(self.local[slot, w]), self.range_distance(slot, w),
                    -int(w in self.old), -int(w in PREFERRED_SOURCES),
                    -(self.rec_bits[w] & self.top_bits).bit_count(), w)
        return sorted(self.candidates, key=rank)

    def _pair(self, left, right):
        if not left or not right:
            return 0.0
        a, b = self.rec_bits[left], self.rec_bits[right]
        union = (a | b).bit_count()
        return (a & b).bit_count() / union if union else 0.0

    @staticmethod
    def word_key(words):
        """Stable tie-break for candidates that may include unfilled slots."""
        return tuple(word or '' for word in words)

    def slot_deficit(self, slot, word, config=None):
        """Band shortfall of one slot; empty slots cost a full slot."""
        return 1.0 if word is None else self.range_distance(slot, word, config)

    def reservations(self, selected):
        groups = {'targets_T3_T4': {}, 'suffixes_T5': {}, 'themes_T6': {}}
        reserve = 0
        for slot, word in enumerate(selected):
            if slot >= self.size:
                continue
            if word not in self.eligible:
                continue
            for t in self.types(slot):
                options = self.options[word][t]
                reserve += min(len(options), self.config['planning']['reserve_per_task'])
                group = ('targets_T3_T4' if t in ('T3', 'T4') else
                         'suffixes_T5' if t == 'T5' else 'themes_T6' if t == 'T6' else None)
                if group:
                    groups[group][f'{slot + 1:02}_{t}'] = options
        return {key: matching(options) for key, options in groups.items()}, reserve

    def measure(self, selected, config=None):
        config = config or self.config
        all_bits = rec_bits = 0
        letters = set()
        band_valid, band_misses, positions = 0, 0, []
        for slot, w in enumerate(selected):
            if w:
                letters.update(w)
                all_bits |= self.all_bits.get(w, 0)
                rec_bits |= self.rec_bits.get(w, 0)
                if slot < self.size:
                    b = self.band(slot, config)
                    count = len(self.profiles[w]['recognizable_answers'])
                    if b['min'] <= count <= b['max']:
                        band_valid += 1
                        positions.append((count - b['min']) / (b['max'] - b['min'])
                                         if b['max'] > b['min'] else 1.0)
                    else:
                        band_misses += 1
        pairs = [(self.pair(a, b), i + 1, j + 1) for i, a in enumerate(selected) if a
                 for j, b in enumerate(selected[:i]) if b]
        selected_count = sum(w is not None for w in selected)
        return {'selected': selected_count,
                'unique_sources': len(set(selected) - {None}),
                'retained': len(set(selected) & self.old),
                'preferred': sorted(set(selected) & PREFERRED_SOURCES),
                'alphabet': self.alphabet,
                'covered_letters': ''.join(c for c in self.alphabet if c in letters),
                'missing_letters': ''.join(c for c in self.alphabet if c not in letters),
                'band_valid_slots': band_valid,
                'band_misses': band_misses,
                'empty_slots': self.size - selected_count,
                'range_position_mean': sum(positions) / len(positions) if positions else 0.0,
                'mean_jaccard_max': config['mean_jaccard_max'],
                'max_jaccard_max': config['max_jaccard_max'],
                'jaccard_pairs': len(pairs),
                'mean_jaccard': sum(p[0] for p in pairs) / len(pairs) if pairs else 0.0,
                'max_jaccard': max((p[0] for p in pairs), default=0.0),
                'top5000_denominator': len(self.top), 'top5000_requested': 5000,
                'observed_game_lemmas': self.observed_count,
                'top5000_recognizable_covered': (rec_bits & self.top_bits).bit_count(),
                'top5000_all_covered': (all_bits & self.top_bits).bit_count(),
                'recognizable_union': rec_bits.bit_count(), 'all_answers_union': all_bits.bit_count(),
                'game_dictionary_size': len(self.words)}

    def evaluate(self, selected, partial=False, config=None):
        config = config or self.config
        self.checks += 1
        m = self.measure(selected, config)
        failures, cost = [], 0.0

        def fail(code, amount=1, **details):
            nonlocal cost
            cost += amount
            failures.append({'code': code, **details})

        if not partial and len(selected) != self.size:
            fail('slot_count', abs(len(selected) - self.size), actual=len(selected), expected=self.size)
        for slot, word in enumerate(selected):
            if slot >= self.size:
                # The explicit slot_count failure below is the only verdict for
                # an overlong list; never index bands/tasks past the campaign.
                continue
            if word is None:
                if not partial:
                    fail('empty_slot', slot=slot + 1)
                continue
            if config is self.config:
                local = self.local.get((slot, word), ['source_ineligible'])
            else:
                local = self.local_failures(slot, word, config)
            for code in local:
                fail(code, slot=slot + 1, word=word)
            if word in self.eligible:
                cost += self.range_distance(slot, word, config)
        if m['selected'] > m['unique_sources']:
            fail('duplicate_sources', m['selected'] - m['unique_sources'])
        if m['retained'] > config['retain_max']:
            fail('retained_max', m['retained'] - config['retain_max'])
        remaining = self.size - m['selected'] if partial else 0
        if m['retained'] + remaining < config['retain_min']:
            fail('retained_min', config['retain_min'] - m['retained'] - remaining)
        if not partial and m['missing_letters']:
            fail('alphabet_coverage', len(m['missing_letters']), missing=m['missing_letters'])
        for metric, limit in [('mean_jaccard', 'mean_jaccard_max'), ('max_jaccard', 'max_jaccard_max')]:
            if m[metric] > config[limit] + 1e-12:
                fail(metric, (m[metric] - config[limit]) / config[limit],
                     actual=m[metric], limit=config[limit])
        reservations, reserve = self.reservations(selected)
        for name, result in reservations.items():
            if result['unmatched']:
                fail(name, len(result['unmatched']), unmatched=result['unmatched'])
        # Hard cost is zero iff all boundaries pass. Coverage and preferred words
        # can never buy permission to install an invalid campaign.
        score = (bool(failures), round(cost, 12), -len(m['preferred']),
                 -m['top5000_recognizable_covered'], -reserve, -m['all_answers_union'])
        if partial:
            # Reward carriers and retention before the remaining slots are filled.
            score += (len(m['missing_letters']), -min(m['retained'], config['retain_min']))
        return score, m, failures, reservations

    def optimization_score(self, selected, config=None):
        """Order intermediate candidates; evaluate() remains the final validator.

        Missing slots, uncovered bands and excessive maximum Jaccard are explicit
        penalties so refinement can improve a 26/30 candidate. They are never
        removed from final validation: a returned candidate is valid only when
        evaluate() has no failures at all.
        """
        config = config or self.config
        _, metrics, failures, _ = self.evaluate(selected, config=config)
        gap = len(selected) < self.size or any(word is None for word in selected)
        allowed = {'mean_jaccard', 'max_jaccard', 'recognizable_range'}
        if gap:
            allowed |= {'slot_count', 'empty_slot', 'alphabet_coverage', 'retained_min'}
        other = sum(row['code'] not in allowed for row in failures)
        missing = self.size - sum(word is not None for word in selected)
        deficit = missing + sum(self.slot_deficit(slot, word, config)
                                for slot, word in enumerate(selected) if word is not None)
        band_misses = metrics['band_misses'] + metrics['empty_slots']

        def excess(value, limit):
            return max(value - limit, 0.0) / limit

        mean_excess = excess(metrics['mean_jaccard'], config['mean_jaccard_max'])
        max_excess = excess(metrics['max_jaccard'], config['max_jaccard_max'])
        retained_deficit = max(0, config['retain_min'] - metrics['retained'] -
                               metrics['empty_slots'])
        # The upper part of an allowed band is a late tie-break only: quality
        # (violations, bands, Jaccard, coverage, reserves) is compared first.
        # Alphabet coverage and retained minimum are hard final boundaries.
        # They are ranked before Jaccard during search; otherwise a rare-letter
        # replacement or an old-source restoration can never be accepted and a
        # formally valid campaign is unreachable.
        return (other, band_misses, len(metrics['missing_letters']), retained_deficit,
                round(max_excess, 12), round(mean_excess, 12), round(deficit, 12),
                -len(metrics['preferred']), -metrics['top5000_recognizable_covered'],
                -metrics['all_answers_union'],
                -min(metrics['retained'], config['retain_min']),
                -round(metrics['range_position_mean'], 12))

    def improve(self, selected):
        selected = list(selected)
        before = tuple(selected)
        best_score = self.optimization_score(selected)
        # Best improvement, stable tie: score, slot, normalized word.
        best = None
        used = set(selected)
        for slot in range(self.size):
            for word in self.shortlists[slot]:
                if self._expired():
                    break
                if word in used:
                    continue
                proposal = selected.copy()
                proposal[slot] = word
                score = self.optimization_score(proposal)
                if score < best_score:
                    best_score, best = score, proposal
        # Also exchange sources between slots; two candidates can unblock each
        # other's band, task pool or coverage constraints.
        for a in range(self.size):
            if self._expired():
                break
            for b in range(a):
                if selected[a] == selected[b]:
                    continue
                proposal = selected.copy()
                proposal[a], proposal[b] = proposal[b], proposal[a]
                if proposal[a] is not None and proposal[b] is not None:
                    score = self.optimization_score(proposal)
                    if score < best_score:
                        best_score, best = score, proposal
        return (best if best is not None else selected), before != tuple(best or selected)

    def _expired(self):
        if self.max_checks is not None and self.checks >= self.max_checks:
            self.timed_out = True
            return True
        if self.deadline is not None and self.clock() >= self.deadline:
            self.timed_out = True
            return True
        return False

    def _start_budget(self):
        self.deadline = (self.clock() + self.time_limit_seconds
                         if self.time_limit_seconds is not None else None)

    def letter_state(self, words):
        counts = {letter: 0 for letter in self.alphabet}
        for word in words:
            if word:
                for letter in set(word):
                    counts[letter] += 1
        return counts, sum(1 for letter in self.alphabet if not counts[letter])

    @staticmethod
    def missing_delta(counts, old_word, new_word):
        old_letters, new_letters = set(old_word or ''), set(new_word or '')
        delta = 0
        for letter in old_letters | new_letters:
            before = counts[letter] == 0
            after = counts[letter] - (letter in old_letters) + (letter in new_letters) == 0
            delta += int(after) - int(before)
        return delta

    def fill_empty(self, selected):
        """Deterministically fill empty slots before overlap refinement.

        Uses the complete per-slot pool, then a range-only fallback when no
        fully admissible candidate exists. The final validator still decides
        whether the filled campaign is installable.
        """
        words = list(selected)
        words += [None] * max(0, self.size - len(words))
        words = words[:self.size]
        while True:
            empty = [slot for slot, word in enumerate(words) if word is None]
            if not empty or self._expired():
                break
            current_key = (self.optimization_score(words), self.word_key(words))
            used = set(words)
            choice = None
            for slot in empty:
                primary = self.admissible_pools[slot]
                # A slot with no admissible source at all is left empty in a
                # partial diagnostic; range-only repair is used only when the
                # slot's own admissible pool exists but is already consumed.
                if not primary:
                    continue
                pool = primary if any(word not in used for word in primary) else self.range_pools[slot]
                for word in pool:
                    if word in used:
                        continue
                    proposal = words.copy()
                    proposal[slot] = word
                    if len(set(proposal) & self.old) > self.config['retain_max']:
                        continue
                    key = (self.optimization_score(proposal), self.word_key(proposal))
                    if key < current_key and (choice is None or key < choice[0]):
                        choice = key, proposal
                    if self._expired():
                        break
                if self._expired():
                    break
            if choice is None:
                break
            words = choice[1]
        return words

    def refine_jaccard(self, selected):
        """Optimize overlap while allowing explicitly penalized band deficits.

        An intermediate 26/30 candidate or a full-length candidate with empty
        ranges may enter refinement. The result reports hard matching
        infeasibility, search exhaustion and the best found candidate
        separately. Only evaluate() decides installability; it is never
        weakened here. Search uses the complete admissible pool, independent
        seeded restarts, explicit source exchanges and explicit penalties for
        uncovered bands and max Jaccard above the selected policy threshold.
        """
        checks_start = self.checks
        original = list(selected)
        words = original + [None] * max(0, self.size - len(original))
        if len(words) > self.size:
            words = words[:self.size]
        _, _, failures, _ = self.evaluate(words)
        gap = len(original) < self.size or any(word is None for word in words)
        # Alphabet coverage and retained minimum are searchrepairable: they are
        # penalized in the energy and rechecked by the final validator. Other
        # hard failures (empty task pools, duplicates, etc.) still stop early.
        allowed = {'mean_jaccard', 'max_jaccard', 'recognizable_range',
                   'alphabet_coverage', 'retained_min'}
        if gap:
            allowed |= {'slot_count', 'empty_slot'}
        feasibility = self.impossibility()
        source_matching = feasibility['distinct_source_matching']
        hard_by_matching = source_matching['matched'] < self.size

        def result(best, trace, timed_out=False):
            _, _, final_failures, _ = self.evaluate(best)
            valid = not final_failures
            stats = dict(seed=self.seed, policy=self.policy,
                         checks=self.checks - checks_start,
                         timed_out=bool(timed_out or self.timed_out),
                         limits=dict(mean_jaccard_max=self.config['mean_jaccard_max'],
                                     max_jaccard_max=self.config['max_jaccard_max'],
                                     top_band_min=self.band(self.size - 1)['min'],
                                     retain_min=self.config['retain_min'],
                                     retain_max=self.config['retain_max']),
                         restarts=len([row for row in trace
                                       if row.get('phase') == 'jaccard_refinement']))
            return RefinementResult(
                best_found=list(best), trace=trace,
                hard_infeasible_by_matching=hard_by_matching,
                hard_infeasible=feasibility['proven_infeasible'],
                search_exhausted=not valid and not feasibility['proven_infeasible'],
                valid=valid, violations=final_failures,
                matching=source_matching, seed=self.seed, stats=stats)

        if any(row['code'] not in allowed for row in failures):
            return result(original, [])

        words = self.fill_empty(words)
        best = words.copy()
        best_key = self.word_key(best)
        best_score = self.optimization_score(best)
        seed_words, trace = words.copy(), []
        for word in sorted(PREFERRED_SOURCES & self.eligible - set(seed_words)):
            choices = []
            for slot, current_word in enumerate(seed_words):
                if current_word in PREFERRED_SOURCES or self.local[slot, word]:
                    continue
                proposal = seed_words.copy()
                proposal[slot] = word
                _, _, proposal_failures, _ = self.evaluate(proposal)
                if all(row['code'] in allowed for row in proposal_failures):
                    choices.append((self.optimization_score(proposal),
                                    self.word_key(proposal), proposal, slot))
            if choices:
                proposal_score, _, proposal, slot = min(choices)
                seed_words = proposal
                trace.append(dict(phase='preferred_seed', word=word, slot=slot+1, score=proposal_score))
        _, _, seed_failures, _ = self.evaluate(seed_words)
        if not seed_failures:
            return result(seed_words, trace)
        seed_score, seed_key = self.optimization_score(seed_words), self.word_key(seed_words)
        if (seed_score, seed_key) < (best_score, best_key):
            best, best_score, best_key = seed_words.copy(), seed_score, seed_key
        # Complete per-slot search pools: every locally admissible source, with
        # range-only repairs used when a slot has no fully admissible source.
        # No fixed shortlist cut is applied.
        pools = [list(self.refine_pools[i]) for i in range(self.size)]
        old_pools = [[w for w in pool if w in self.old] for pool in pools]
        range_pools = [list(self.range_pools[i]) for i in range(self.size)]
        old_range_pools = [[w for w in pool if w in self.old] for pool in range_pools]
        pair_limit = self.config['max_jaccard_max']
        sum_limit = self.size*(self.size-1)/2*self.config['mean_jaccard_max']
        limits = self.config['search']
        preferred = set(seed_words) & PREFERRED_SOURCES
        timed_out = False

        def matrix(words):
            return [[self.pair(a, b) if i != j and a and b else 0.0
                     for j, b in enumerate(words)] for i, a in enumerate(words)]

        def deficit_energy(words):
            return sum(self.slot_deficit(slot, word) for slot, word in enumerate(words))

        def band_energy(words):
            # Empty slots count as uncovered bands. Otherwise filling an empty
            # slot with a range-only diagnostic candidate would look worse than
            # leaving the campaign incomplete.
            misses = sum(1 for slot, word in enumerate(words)
                         if word is None or self.range_distance(slot, word) > 0)
            return BAND_MISS_WEIGHT*misses + DEFICIT_WEIGHT*deficit_energy(words)

        # First reduce total overlap, then remove the last excessive pairs.
        # Band misses are an explicit penalty, so a 26/30 candidate can still be
        # improved while Jaccard is reduced. These weights guide search only; the
        # final validator still uses exact policy bounds.
        for penalty in (1000, 1000000):
            def pair_cost(value):
                return value + penalty*max(value-pair_limit, 0)**2

            def energy(values, words, total=None, missing=0, retained=0):
                if total is None:
                    total = sum(values[i][j] for i in range(self.size) for j in range(i))
                mean_excess = max(total - sum_limit, 0.0) / max(sum_limit, 1e-12)
                empty = sum(1 for word in words if word is None)
                retain_penalty = max(0, self.config['retain_min'] - retained - empty)
                return (sum(pair_cost(values[i][j]) for i in range(self.size) for j in range(i)) +
                        MEAN_JACCARD_WEIGHT*mean_excess + band_energy(words) +
                        ALPHABET_MISS_WEIGHT*missing + RETAIN_WEIGHT*retain_penalty)

            seed_counts, seed_missing = self.letter_state(seed_words)
            seed_retained = len(set(seed_words) & self.old)
            lowest_energy = energy(matrix(seed_words), seed_words, missing=seed_missing,
                                   retained=seed_retained)
            for restart in range(limits['refinement_restarts']):
                if self._expired():
                    timed_out = True
                    break
                seed_i = stable_seed(self.seed, 'jaccard_refinement', self.policy, penalty, restart)
                rng = random.Random(seed_i)
                words = seed_words.copy()
                values = matrix(words)
                total = sum(values[i][j] for i in range(self.size) for j in range(i))
                counts, missing = self.letter_state(words)
                retained = len(set(words) & self.old)
                current = energy(values, words, total, missing, retained)
                accepted = exchanges = 0
                steps = limits['refinement_iterations']
                for step in range(steps):
                    if step % TIME_CHECK_INTERVAL == 0 and self._expired():
                        timed_out = True
                        break
                    slot = rng.randrange(self.size)
                    if step % 50 == 0:
                        other = rng.randrange(self.size)
                        if (words[slot] is not None and words[other] is not None and
                                not self.local[slot, words[other]] and
                                not self.local[other, words[slot]]):
                            words[slot], words[other] = words[other], words[slot]
                            values = matrix(words)
                            total = sum(values[i][j] for i in range(self.size) for j in range(i))
                            counts, missing = self.letter_state(words)
                            retained = len(set(words) & self.old)
                            current = energy(values, words, total, missing, retained)
                            exchanges += 1
                        continue
                    # Preferences stay soft: the final restart can replace them.
                    if (penalty == 1000 or restart+1 < limits['refinement_restarts']) and words[slot] in preferred:
                        continue
                    full = all(word is not None for word in words)
                    # An old source may be exchanged even at the retained
                    # minimum while the alphabet is incomplete; the retained
                    # penalty then guides a later restoration from the old pool.
                    restrict_old = (full and words[slot] in self.old and
                                    retained == self.config['retain_min'] and missing == 0)
                    pool = old_pools[slot] if restrict_old else pools[slot]
                    fallback = old_range_pools[slot] if restrict_old else range_pools[slot]
                    if not pool and not fallback:
                        continue
                    # Prefer a fully admissible replacement; fall back to a
                    # range-only repair only when the admissible pool is used or
                    # empty. This keeps the band penalty active without letting
                    # range-only candidates crowd out admissible ones.
                    word = None
                    for _ in range(3):
                        if not pool:
                            break
                        candidate = rng.choice(pool)
                        if candidate not in words:
                            word = candidate
                            break
                    if word is None:
                        for _ in range(3):
                            if not fallback:
                                break
                            candidate = rng.choice(fallback)
                            if candidate not in words:
                                word = candidate
                                break
                    if word is None or word in words:
                        continue
                    new_retained = retained + (word in self.old) - (words[slot] in self.old)
                    # Below-minimum retention is an explicit search penalty, not
                    # a veto: a rare-letter repair may temporarily exchange an
                    # old source and a later move can restore it from old_pools.
                    # The final validator still enforces the real minimum.
                    if new_retained > self.config['retain_max']:
                        continue
                    # Alphabet coverage is a final-validator boundary. During
                    # partial search it must not veto filling empty high-band
                    # slots: the sum of replacements, not one word, may be what
                    # completes the 32-letter union.
                    row = [self.pair(word, current_word) if j != slot and current_word else 0.0
                           for j, current_word in enumerate(words)]
                    old_row = values[slot]
                    old_total = total
                    new_total = total - sum(old_row) + sum(row)
                    old_mean_excess = max(old_total - sum_limit, 0.0) / max(sum_limit, 1e-12)
                    new_mean_excess = max(new_total - sum_limit, 0.0) / max(sum_limit, 1e-12)
                    old_deficit = self.slot_deficit(slot, words[slot])
                    new_deficit = self.slot_deficit(slot, word)
                    old_miss = int(words[slot] is None or
                                   self.range_distance(slot, words[slot]) > 0)
                    new_miss = int(self.range_distance(slot, word) > 0)
                    new_missing = missing + self.missing_delta(counts, words[slot], word)
                    empty_before = sum(1 for current_word in words if current_word is None)
                    empty_after = empty_before - (1 if words[slot] is None else 0)
                    old_retain_penalty = max(0, self.config['retain_min'] - retained - empty_before)
                    new_retain_penalty = max(0, self.config['retain_min'] - new_retained - empty_after)
                    delta = (sum(pair_cost(v)-pair_cost(old) for v, old in zip(row, old_row)) +
                             MEAN_JACCARD_WEIGHT*(new_mean_excess-old_mean_excess) +
                             BAND_MISS_WEIGHT*(new_miss-old_miss) +
                             DEFICIT_WEIGHT*(new_deficit-old_deficit) +
                             ALPHABET_MISS_WEIGHT*(new_missing-missing) +
                             RETAIN_WEIGHT*(new_retain_penalty-old_retain_penalty))
                    start_temperature = .3 if penalty == 1000 else 1.0
                    temperature = start_temperature*(.002/start_temperature)**(step/steps)
                    if delta > 0 and rng.random() >= math.exp(-delta/temperature):
                        continue
                    old_word = words[slot]
                    words[slot], retained = word, new_retained
                    for letter in set(old_word or ''):
                        counts[letter] -= 1
                    for letter in set(word):
                        counts[letter] += 1
                    missing = new_missing
                    for j, value in enumerate(row):
                        values[slot][j] = values[j][slot] = value
                    total = new_total
                    current += delta
                    accepted += 1
                    if current <= sum_limit+1e-10 and max(map(max, values)) <= pair_limit+1e-12:
                        _, _, candidate_failures, _ = self.evaluate(words)
                        if not candidate_failures:
                            trace.append(dict(phase='jaccard_refinement', penalty=penalty,
                                              restart=restart, seed=seed_i,
                                              iterations=step+1, accepted=accepted,
                                              exchanges=exchanges, checks=self.checks-checks_start,
                                              score=self.optimization_score(words)))
                            return result(words, trace)
                    if current < lowest_energy-1e-10:
                        reservations, _ = self.reservations(words)
                        if all(not reservation['unmatched'] for reservation in reservations.values()):
                            seed_words, lowest_energy = words.copy(), current
                            candidate_score = self.optimization_score(words)
                            candidate_key = self.word_key(words)
                            if (candidate_score, candidate_key) < (best_score, best_key):
                                best, best_score, best_key = words.copy(), candidate_score, candidate_key
                trace.append(dict(phase='jaccard_refinement', penalty=penalty, restart=restart,
                                  seed=seed_i, iterations=step + (1 if 'step' in locals() else 0),
                                  accepted=accepted, exchanges=exchanges,
                                  checks=self.checks-checks_start, timed_out=timed_out,
                                  score=best_score))
                if timed_out:
                    break
            if timed_out:
                break
        return result(best, trace, timed_out)

    def search(self):
        self.checks = 0
        self.timed_out = False
        self._start_budget()
        best, trace = None, []
        limits = self.config['search']
        empty = [None] * self.size
        empty_score = self.evaluate(empty)[0]
        best = ((empty_score, self.word_key(empty)), empty)
        for restart in range(limits['restarts']):
            if self._expired():
                break
            seed_i = stable_seed(self.seed, 'greedy', self.policy, restart)
            rng = random.Random(seed_i)
            selected = [None] * self.size
            order = sorted(range(self.size), key=lambda slot: (
                len(self.admissible_pools[slot]), -slot))
            if restart:
                rng.shuffle(order)
            for slot in order:
                if self._expired():
                    break
                choices = []
                for word in self.shortlists[slot]:
                    if word in selected:
                        continue
                    proposal = selected.copy()
                    proposal[slot] = word
                    choices.append((self.evaluate(proposal, partial=True)[0], word))
                    if self.checks % TIME_CHECK_INTERVAL == 0 and self._expired():
                        break
                if choices:
                    selected[slot] = min(choices)[1]
            changes = 0
            for _ in range(limits['max_iterations']):
                if self._expired():
                    break
                selected, changed = self.improve(selected)
                if not changed:
                    break
                changes += 1
            score = self.evaluate(selected)[0]
            trace.append({'restart': restart, 'seed': seed_i, 'local_changes': changes,
                          'checks': self.checks, 'timed_out': self.timed_out, 'score': score})
            key = (score, self.word_key(selected))
            if key < best[0]:
                best = key, selected
            if self.timed_out:
                break
        refinement = self.refine_jaccard(best[1])
        self.search_stats = {
            'seed': self.seed, 'policy': self.policy,
            'policy_limits': dict(mean_jaccard_max=self.config['mean_jaccard_max'],
                                  max_jaccard_max=self.config['max_jaccard_max'],
                                  top_band_min=self.band(self.size - 1)['min'],
                                  retain_min=self.config['retain_min'],
                                  retain_max=self.config['retain_max']),
            'time_limit_seconds': self.time_limit_seconds, 'max_checks': self.max_checks,
            'checks': self.checks, 'timed_out': self.timed_out,
            'restarts_requested': limits['restarts'],
            'restarts_completed': len([row or {} for row in trace
                                       if 'restart' in row and 'phase' not in row]),
            'refinement_restarts_requested': 2 * limits['refinement_restarts'],
            'refinement_restarts_completed': refinement['stats']['restarts'],
            'pool': self.pool_report}
        result_trace = SearchTrace(trace + refinement['trace'])
        result_trace.stats = self.search_stats
        return refinement['best_found'], result_trace

    def impossibility(self):
        feasible = {f'{slot + 1:02}': {w for w in self.candidates if not self.local[slot, w]}
                    for slot in range(self.size)}
        source_matching = matching(feasible)
        possible = set().union(*feasible.values())
        letters = set(''.join(sorted(possible)))
        old_possible = possible & self.old
        reasons = []
        if source_matching['matched'] < self.size:
            reasons.append(f"По диапазонам и пулам заданий можно заполнить не более {source_matching['matched']}/{self.size} разных слотов.")
        if len(old_possible) < self.config['retain_min']:
            reasons.append(f"Старых исходников, подходящих хотя бы одному слоту: {len(old_possible)}; требуется минимум {self.config['retain_min']}.")
        missing = ''.join(c for c in self.alphabet if c not in letters)
        if missing:
            reasons.append('В объединении подходящих кандидатур отсутствуют буквы: ' + missing + '.')
        return {'proven_infeasible': bool(reasons),
                'hard_infeasible_by_matching': source_matching['matched'] < self.size,
                'reasons': reasons,
                'slot_candidate_counts': {s: len(v) for s, v in feasible.items()},
                'distinct_source_matching': source_matching, 'old_eligible_any_slot': sorted(old_possible),
                'eligible_source_count': len(self.candidates),
                'max_recognizable_answers': max(
                    (len(self.profiles[w]['recognizable_answers']) for w in self.candidates), default=0),
                'eligible_old_before_slot_constraints': sorted(self.eligible & self.old),
                'missing_letters_in_feasible_pool': missing}

    def spares(self, selected):
        """Only replacements passing ALL campaign boundaries are suitable spares."""
        result = []
        for slot in range(self.size):
            fitting = []
            for word in self.candidates:
                if word in selected or self.local[slot, word]:
                    continue
                proposal = selected.copy()
                proposal[slot] = word
                score, metrics, failures, _ = self.evaluate(proposal)
                if not failures:
                    fitting.append((score, word, metrics))
            result.append([{'word': w, 'metrics_after_replacement': m}
                           for _, w, m in sorted(fitting)[:5]])
        return result


def collision_rows(selector, selected):
    rows = []
    for i, a in enumerate(selected):
        if not a:
            continue
        for j, b in enumerate(selected[:i]):
            if b:
                rows.append({'slots': [j + 1, i + 1], 'words': [b, a],
                             'intersection': (selector.rec_bits[a] & selector.rec_bits[b]).bit_count(),
                             'union': (selector.rec_bits[a] | selector.rec_bits[b]).bit_count(),
                             'jaccard': selector.pair(a, b)})
    return sorted(rows, key=lambda r: (-r['jaccard'], r['slots']))


def proposal_search_stats(selector, trace):
    """Use the live run stats, or reconstruct identical stats from a trace.

    make_proposal() may be called with a freshly constructed selector in tests
    or reporting code; the trace still contains enough deterministic counters
    to describe the original search without a wall-clock timestamp.
    """
    stats = dict(selector.search_stats)
    if not stats.get('checks'):
        stats = dict(getattr(trace, 'stats', None) or stats)
    if stats.get('checks') or not trace:
        return stats
    refinement = [row for row in trace if row.get('phase') == 'jaccard_refinement']
    greedy = [row for row in trace if 'restart' in row and row.get('phase') != 'jaccard_refinement']
    return {
        'seed': selector.seed, 'policy': selector.policy,
        'policy_limits': dict(mean_jaccard_max=selector.config['mean_jaccard_max'],
                              max_jaccard_max=selector.config['max_jaccard_max'],
                              top_band_min=selector.band(selector.size - 1)['min'],
                              retain_min=selector.config['retain_min'],
                              retain_max=selector.config['retain_max']),
        'time_limit_seconds': selector.time_limit_seconds,
        'max_checks': selector.max_checks,
        'checks': max([row.get('checks', 0) for row in trace] + [0]),
        'timed_out': any(row.get('timed_out') for row in trace),
        'restarts_requested': selector.config['search']['restarts'],
        'restarts_completed': len(greedy),
        'refinement_restarts_requested': 2 * selector.config['search']['refinement_restarts'],
        'refinement_restarts_completed': len(refinement),
        'pool': selector.pool_report}


def make_proposal(selector, selected, trace, inputs):
    score, metrics, failures, reservations = selector.evaluate(selected)
    strict_failures = selector.evaluate(selected, config=selector.default_config)[2]
    strict_installable = not strict_failures
    search_stats = proposal_search_stats(selector, trace)
    feasibility = selector.impossibility()
    installable = not failures
    status = 'valid' if installable else 'infeasible' if feasibility['proven_infeasible'] else 'search_exhausted'
    hard_by_matching = feasibility['distinct_source_matching']['matched'] < selector.size
    spares = selector.spares(selected)
    preferred = []
    for word in sorted(PREFERRED_SOURCES):
        p = selector.profiles.get(word)
        fitting = [slot + 1 for slot in range(selector.size) if not selector.local_failures(slot, word)]
        reasons = []
        if not fitting:
            reasons.append('Не проходит ни один слот: ' + ', '.join(sorted(set(
                reason for slot in range(selector.size) for reason in selector.local_failures(slot, word)))))
            if p:
                count = len(p['recognizable_answers'])
                lower = min(b['min'] for b in selector.config['bands'])
                upper = max(b['max'] for b in selector.config['bands'])
                if count < lower:
                    reasons.append(f'{count} узнаваемых < {lower}; нехватка минимум {lower - count}.')
                elif count > upper:
                    reasons.append(f'{count} узнаваемых > {upper}.')
        elif word not in selected:
            reasons.append('Уступил в поиске по общим ограничениям/покрытию; сохранение мягкое.')
        preferred.append({'word': word, 'selected': word in selected,
                          'all_answers': len(p['answers']) if p else None,
                          'recognizable_answers': len(p['recognizable_answers']) if p else None,
                          'recognizable_4plus': p['recognizable'].get('count_4plus') if p else None,
                          'locally_feasible_slots': fitting,
                          'reasons': reasons})
    old_selected = [normalize_form(row['word']) for row in selector.previous]
    slots = []
    for slot, word in enumerate(selected):
        p = selector.profiles.get(word)
        old = old_selected[slot] if slot < len(old_selected) else None
        old_failures = selector.local_failures(slot, old) if old else ['absent_old_source']
        slots.append({'index': slot + 1, 'word': word, 'previous_word': old,
                      'retained_from_old_campaign': word in selector.old,
                      'range': selector.band(slot), 'planned_types': selector.types(slot),
                      'task_pool_sizes': {t: len(selector.options[word][t]) for t in selector.types(slot)}
                          if word in selector.eligible else {},
                      'all_answers': len(p['answers']) if p else 0,
                      'recognizable_answers': len(p['recognizable_answers']) if p else 0,
                      'local_violations': selector.local_failures(slot, word),
                      'replacement_reason': ('На прежнем слоте' if word == old else
                          'Прежний исходник: ' + ', '.join(old_failures) if old_failures else
                          'Автоматическая замена/перестановка для общих границ, покрытия и запаса.'),
                      'spares': spares[slot]})
    return {'schema': SCHEMA, 'status': status, 'installable': installable,
            'policy': selector.policy,
            'policy_limits': dict(mean_jaccard_max=selector.config['mean_jaccard_max'],
                                  max_jaccard_max=selector.config['max_jaccard_max'],
                                  top_band_min=selector.band(selector.size - 1)['min'],
                                  retain_min=selector.config['retain_min'],
                                  retain_max=selector.config['retain_max']),
            'strict_installable': strict_installable,
            'strict_violations': strict_failures,
            'hard_infeasible_by_matching': hard_by_matching,
            'search_exhausted': status == 'search_exhausted',
            'best_found': list(selected),
            'diagnostic_only': not installable, 'inputs': inputs, 'config': selector.config,
            'score': score, 'search_trace': trace, 'search_stats': search_stats,
            'pool': selector.pool_report, 'feasibility': feasibility,
            'semantic_coverage': selector.semantic_coverage,
            'metrics': metrics, 'violations': failures, 'slots': slots,
            'jaccard_target': dict(mean=selector.config['mean_jaccard_max'],
                                   max=selector.config['max_jaccard_max']),
            'jaccard_actual': dict(mean=metrics['mean_jaccard'], max=metrics['max_jaccard']),
            'previous_metrics': selector.measure(old_selected),
            'previous_collisions': collision_rows(selector, old_selected),
            'collisions': collision_rows(selector, selected),
            'preferred_sources': preferred, 'planning_reservations': reservations,
            'planning_note': 'Предварительные свидетели ёмкости; параметры заданий не назначены. GEN-03 обязан повторно проверить достижимость и уникальность.',
            'top5000_definition': 'Первые min(5000, N) игровых лемм с наблюдаемой частотой > 0, порядок (-frequency, normalized_word). Основная метрика — узнаваемые ответы; общий словарный ввод показан отдельно.',
            'search_note': 'Лучший по опубликованному score среди проверенных вариантов детерминированного поиска по полному профильному пулу. Это не доказательство глобальной оптимальности. Порядок score: наличие нарушений, непокрытые полосы, непокрытые буквы, превышение max/mean Jaccard, дефицит полос, предпочтительные слова, топ-5000 узнаваемых, общий словарь, сохранение старых слов, затем предпочтение верхней части допустимого диапазона.'}


def write_reports(selector, proposal, directory):
    directory.mkdir(parents=True, exist_ok=True)
    atomic_json(directory / 'level_words_proposal.json', proposal)
    with (directory / 'level_words_candidates.csv').open('w', encoding='utf-8', newline='') as target:
        writer = csv.writer(target, lineterminator='\n')
        writer.writerow(['word', 'source_eligible', 'old', 'preferred', 'all_answers', 'recognizable_answers',
                         'recognizable_4plus', 'feasible_slots', 'selected_slots', 'exclusion_reasons'])
        selected = {row['word']: row['index'] for row in proposal['slots']}
        for word, p in sorted(selector.profiles.items()):
            feasible = [str(slot + 1) for slot in range(selector.size) if not selector.local_failures(slot, word)]
            reasons = sorted({reason for slot in range(selector.size)
                              for reason in selector.local_failures(slot, word)}) if not feasible else []
            writer.writerow([word, word in selector.eligible, word in selector.old, word in PREFERRED_SOURCES,
                             len(p['answers']), len(p['recognizable_answers']), p['recognizable'].get('count_4plus', ''),
                             ' '.join(feasible), selected.get(word, ''), ' '.join(reasons)])
    m, old = proposal['metrics'], proposal['previous_metrics']
    policy = proposal.get('policy', 'STRICT')
    limits = proposal.get('policy_limits', {})
    stats = proposal.get('search_stats', {})
    pool = proposal.get('pool', {})
    strict = 'да' if proposal.get('strict_installable') else 'нет'
    lines = ['# Подбор исходников — GEN-02', '',
             f"Статус: **{proposal['status']}**. Политика: **{policy}**. Установка разрешена числовой проверкой: **{proposal['installable']}**.",
             f"Полная проверка по STRICT: **{strict}**. Вариант с нарушениями сохраняется исключительно для диагностики; data/ не изменяется.", '',
             proposal['search_note'], '',
             f"Жёсткие границы: 30 разных исходников; {selector.config['retain_min']}–{selector.config['retain_max']} старых; все 32 знака Alphabet.LETTERS; диапазон каждого слота; средний Jaccard ≤{limits.get('mean_jaccard_max', 0):.2%}, максимальный ≤{limits.get('max_jaccard_max', 0):.2%}. Нет требования «большинство».",
             'Мягкие критерии: предпочтительные слова, максимизация топ-5000, запас будущих заданий, охват всего словаря и только затем верхняя часть допустимого диапазона ответов.', '',
             '| Метрика | Старая кампания | Новый вариант |', '|---|---:|---:|']
    for key, label in [('selected', 'Исходников'), ('unique_sources', 'Разных исходников'),
                       ('retained', 'Старых исходников'), ('recognizable_union', 'Объединение узнаваемых'),
                       ('all_answers_union', 'Объединение всех ответов'), ('game_dictionary_size', 'Размер игрового словаря'),
                       ('top5000_recognizable_covered', 'Топ-5000: узнаваемые'),
                       ('top5000_all_covered', 'Топ-5000: все ответы')]:
        lines.append(f'| {label} | {old[key]} | {m[key]} |')
    for key in ('mean_jaccard', 'max_jaccard'):
        lines.append(f'| {key} | {old[key]:.4%} | {m[key]:.4%} |')
    lines.extend(['', f"Jaccard цель / факт: средний **{limits.get('mean_jaccard_max', 0):.4%} / {m['mean_jaccard']:.4%}**, "
                  f"максимальный **{limits.get('max_jaccard_max', 0):.4%} / {m['max_jaccard']:.4%}**; "
                  f"валидных полос: **{m['band_valid_slots']}/{m['selected']}**, пустых слотов: {m['empty_slots']}.",
                  proposal['top5000_definition'],
                  f"Знаменатель топ-5000: **{m['top5000_denominator']}**; наблюдаемых игровых лемм: {m['observed_game_lemmas']}. Все ответы / словарь: {m['all_answers_union']}/{m['game_dictionary_size']}.",
                  f"Alphabet.LETTERS ({len(selector.alphabet)}): `{selector.alphabet}`.",
                  f"Старая кампания: `{old['covered_letters']}`; отсутствуют: `{old['missing_letters'] or 'нет'}`.",
                  f"Новый вариант: `{m['covered_letters']}`; отсутствуют: `{m['missing_letters'] or 'нет'}`.", '',
                  '## Полнота входа GEN-01', ''])
    coverage = proposal['semantic_coverage']
    if coverage['recorded']:
        lines.extend([f"Оценок без ответа ИИ: **{coverage['pending']}**.", '',
                      '| Роль | Правила | ИИ | Ожидают ИИ |', '|---|---:|---:|---:|'])
        for role, counts in coverage['by_role'].items():
            lines.append(f"| {role} | {counts['rules']} | {counts['ai']} | {counts['pending']} |")
        if coverage['pending']:
            lines.extend(['', 'Текущий пул неполный: pending не считается пригодным словом. '
                          'Невыполнимость на этом входе не доказывает невозможность после завершения оценок. '
                          'Сначала завершите LEX-04 на suitability_input.json GEN-01, затем пересоберите '
                          'профили с --suitability и повторите подбор. Числовые границы сохраняются.'])
    else:
        lines.append('Происхождение семантических оценок не записано; полнота неизвестна.')
    lines.extend(['', f"Максимум подтверждённых узнаваемых ответов у допустимого исходника: "
                  f"**{proposal['feasibility']['max_recognizable_answers']}**. Это максимум текущего пула.",
                  '', '## Диапазоны и объективные препятствия', ''])
    for band in selector.config['bands']:
        slot = band['first'] - 1
        count = proposal['feasibility']['slot_candidate_counts'][f'{slot + 1:02}']
        lines.append(f"- Слоты {band['first']}–{band['last']}: {band['min']}–{band['max']} узнаваемых ответов (включая 3-буквенные); кандидатов для первого слота: {count}.")
    lines.extend(['', *proposal['feasibility']['reasons'], '',
                  f"Предварительно пригодных старых исходников до диапазонов: {len(proposal['feasibility']['eligible_old_before_slot_constraints'])}.",
                  f"Нарушения найденного варианта ({len(proposal['violations'])}):", ''])
    lines.extend('- ' + json.dumps(v, ensure_ascii=False, sort_keys=True) for v in proposal['violations'])
    lines.extend(['', '## Политика и поиск', '',
                  f"Политика: **{policy}**; пороги mean/max J {limits.get('mean_jaccard_max', 0):.4f}/{limits.get('max_jaccard_max', 0):.4f}; "
                  f"нижняя граница верхней полосы {limits.get('top_band_min')}; retained {limits.get('retain_min')}–{limits.get('retain_max')}. "
                  'STRICT проверен отдельным финальным валидатором без ослабления.',
                  f"Полный профильный пул: {pool.get('profiles_total')} профилей, {pool.get('eligible_source_count')} допустимых исходников; "
                  f"отсечено по причинам: {json.dumps(pool.get('rejected_by_reason', {}), ensure_ascii=False, sort_keys=True)}. "
                  f"Локально допустимых кандидатов в пулах: {pool.get('admissible_pool_total')}; "
                  f"потеряно без отчёта: {pool.get('admissible_omitted_without_report')}.",
                  f"Seed: **{stats.get('seed')}**; проверок evaluate: **{stats.get('checks')}**; "
                  f"жадных стартов: {stats.get('restarts_completed')}/{stats.get('restarts_requested')}; "
                  f"уточняющих стартов: {stats.get('refinement_restarts_completed')}/{stats.get('refinement_restarts_requested')}; "
                  f"таймаут: **{stats.get('timed_out')}**" +
                  (f" (лимит {stats.get('time_limit_seconds')} с)" if stats.get('time_limit_seconds') is not None else '') + '.',
                  'Кандидаты только с нарушением диапазона сохранены отдельно и используются как штрафуемый резерв для исправления полос; финальный валидатор их не принимает.', ''])
    attempts = proposal.get('policy_attempts', [])
    if attempts:
        lines.extend(['### Попытки политик', '', '| Политика | Статус | Установка | retained | Нет букв | mean / max J | Цель mean / max | Проверок | Таймаут |', '|---|---|---|---:|---|---:|---:|---:|---|'])
        for row in attempts:
            lines.append(f"| {row['policy']} | {row['status']} | {row['installable']} | "
                         f"{row['metrics'].get('retained')} | "
                         f"{row['metrics'].get('missing_letters') or 'нет'} | "
                         f"{row['metrics']['mean_jaccard']:.4%} / {row['metrics']['max_jaccard']:.4%} | "
                         f"{row['policy_limits']['mean_jaccard_max']:.4%} / {row['policy_limits']['max_jaccard_max']:.4%} | "
                         f"{row['checks']} | {row['timed_out']} |")
        lines.append('')
    lines.extend(['', '## Пожелания владельца', '', '| Исходник | Все ответы | Узнаваемые | Узнаваемые 4+ | Результат |', '|---|---:|---:|---:|---|'])
    for p in proposal['preferred_sources']:
        result = ('В диагностическом варианте; ' if p['selected'] and not proposal['installable'] else
                  'Выбран; ' if p['selected'] else 'Не выбран; ') + '; '.join(p['reasons'])
        lines.append(f"| {p['word']} | {p['all_answers']} | {p['recognizable_answers']} | {p['recognizable_4plus']} | {result} |")
    lines.extend(['', '## Слоты и причины замен', '', '| Слот | Было | Предложено | Все / узнаваемые | Причина | Запасные |', '|---:|---|---|---:|---|---|'])
    for row in proposal['slots']:
        spares = ', '.join(p['word'] for p in row['spares']) or 'нет проходящих все границы'
        lines.append(f"| {row['index']} | {row['previous_word']} | {row['word'] or '—'} | {row['all_answers']} / {row['recognizable_answers']} | {row['replacement_reason']} | {spares} |")
    lines.extend(['', 'Запасные проверены заменой в полном варианте, включая сохранение старых слов, покрытие, Jaccard и паросочетания. До пяти на слот; отсутствие подходящих не подменяется неподходящими кандидатами.', '',
                  '## Коллизии узнаваемых ответов', '',
                  'Jaccard(A,B) = |A ∩ B| / |A ∪ B|. Пример: {кот, ток} и {ток, сок}: 1/3. Среднее по всем неупорядоченным парам (435 для 30 слов); пустые объединения дают 0.',
                  'Все пары и их числители/знаменатели обеих кампаний сохранены в JSON. Здесь десять максимальных новых пересечений:', '',
                  '| Слоты | Слова | Пересечение / объединение | Jaccard |', '|---|---|---:|---:|'])
    for row in proposal['collisions'][:10]:
        lines.append(f"| {row['slots']} | {', '.join(row['words'])} | {row['intersection']} / {row['union']} | {row['jaccard']:.4%} |")
    lines.extend(['', '## Предварительный запас заданий', '', proposal['planning_note'], '',
                  selector.config['planning']['rationale'],
                  'T3/T4 проверены паросочетанием в общем пространстве нормализованных целей; T5 — окончаний; T6 — тем. Остальные пулы проверены на непустоту. Эти свидетели не являются установленными заданиями.', ''])
    for name, result in proposal['planning_reservations'].items():
        lines.append(f"- {name}: {result['matched']}/{result['required']}; не обеспечены: {result['unmatched']}.")
    lines.extend(['', '## Воспроизводимость', '',
                  f"Начальный seed: {selector.config['seed']}; производные seed: sha256('seed|фаза|политика|restart'). "
                  f"Лимиты: {json.dumps(selector.config['search'], ensure_ascii=False, sort_keys=True)}. "
                  f"Проверок evaluate: {stats.get('checks')}; таймаут: {stats.get('timed_out')}.",
                  'В детерминированном режиме без --time-limit нет времени, абсолютных путей или случайных идентификаторов в артефактах; одинаковые входы дают одинаковые байты. '
                  'С включённым таймаутом фиксируются флаг и лимит, но объём проверок может зависеть от машины. Исходные хэши, конфиг, trace и score сохранены в JSON. '
                  'При исчерпании поиска без доказательства невозможности статус search_exhausted, не infeasible.', ''])
    (directory / 'LEVEL_WORDS_REPORT.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def requested_policies(args):
    policies = getattr(args, 'policies', None)
    if policies:
        return list(policies)
    policy = getattr(args, 'policy', None)
    if isinstance(policy, (list, tuple)):
        return list(policy)
    if isinstance(policy, str):
        return [policy]
    return ['STRICT']


def run(args):
    config, alphabet = load_config(args.config), read_alphabet(args.alphabet)
    profile_path = args.profiles
    if profile_path is None:
        summary = json.loads((ROOT / 'build/reports/level_profiles/summary.json').read_text())
        profile_path = Path(summary['cache_path'])
    document = load_snapshot(profile_path, args.words, args.previous_levels)
    words = read_list(args.words)
    previous = json.loads(args.previous_levels.read_text(encoding='utf-8'))
    inputs = {'profile_payload_sha256': document['payload_sha256'], 'profile_inputs': document['inputs'],
              'profiles_sha256': sha256_file(profile_path), 'words_sha256': sha256_file(args.words),
              'previous_levels_sha256': sha256_file(args.previous_levels),
              'config_sha256': sha256_file(args.config), 'alphabet_sha256': sha256_file(args.alphabet),
              'selector_sha256': sha256_file(Path(__file__))}
    policies = requested_policies(args)
    for name in policies:
        policy_thresholds(name)  # reject unknown names before touching the cache
    attempts = []
    for name in policies:
        selector = Selector(document['payload'], words, previous, config, alphabet,
                            thresholds=policy_thresholds(name), policy=name,
                            time_limit_seconds=getattr(args, 'time_limit_seconds', None),
                            max_checks=getattr(args, 'max_checks', None),
                            seed=getattr(args, 'seed', None))
        selected, trace = selector.search()
        proposal = make_proposal(selector, selected, trace, inputs)
        attempts.append((name, selector, proposal))
        if proposal['installable']:
            break
    chosen = next((row for row in attempts if row[2]['installable']), None)
    if chosen is None:
        # No policy produced an installable campaign. Keep the best diagnostic
        # deterministic: fewest violations, then most filled slots, then the
        # smallest actual Jaccard, and finally the policy order. This never
        # affects installability; the final validator still rejects every row.
        chosen = min(attempts, key=lambda row: (
            len(row[2]['violations']), -row[2]['metrics']['selected'],
            row[2]['metrics']['max_jaccard'], row[2]['metrics']['mean_jaccard'],
            PROVISIONAL_POLICY_ORDER.index(row[0])))
    chosen[2]['policy_attempts'] = [
        {'policy': name, 'status': proposal['status'], 'installable': proposal['installable'],
         'strict_installable': proposal['strict_installable'],
         'policy_limits': proposal['policy_limits'],
         'metrics': {'mean_jaccard': proposal['metrics']['mean_jaccard'],
                     'max_jaccard': proposal['metrics']['max_jaccard'],
                     'band_valid_slots': proposal['metrics']['band_valid_slots'],
                     'retained': proposal['metrics']['retained'],
                     'missing_letters': proposal['metrics']['missing_letters']},
         'checks': proposal['search_stats'].get('checks'),
         'timed_out': proposal['search_stats'].get('timed_out'),
         'seed': proposal['search_stats'].get('seed')}
        for name, _, proposal in attempts]
    write_reports(chosen[1], chosen[2], args.reports)
    if len(attempts) > 1:
        for name, selector, proposal in attempts:
            write_reports(selector, proposal, args.reports / 'policies' /
                          name.lower().replace('-', '_'))
    return chosen[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profiles', type=Path, help='Проверенный gzip-кэш GEN-01; по умолчанию его summary.json')
    parser.add_argument('--words', type=Path, default=ROOT / 'data/words.txt')
    parser.add_argument('--previous-levels', type=Path, default=ROOT / 'data/levels.json')
    parser.add_argument('--config', type=Path, default=CONFIG)
    parser.add_argument('--alphabet', type=Path, default=ALPHABET)
    parser.add_argument('--reports', type=Path, default=ROOT / 'build/reports')
    parser.add_argument('--policy', action='append', choices=list(PROVISIONAL_POLICY_ORDER),
                        help='STRICT или последовательность с повтором флага: сначала STRICT, затем SOFT-A/SOFT-B')
    parser.add_argument('--policy-sequence', action='store_true',
                        help='Сокращение для STRICT → SOFT-A → SOFT-B')
    parser.add_argument('--time-limit', dest='time_limit_seconds', type=float, default=None,
                        help='Мягкий лимит секунд на весь поиск; без него бюджет детерминирован лимитами итераций')
    parser.add_argument('--max-checks', type=int, default=None,
                        help='Детерминированный лимит числа вызовов evaluate')
    parser.add_argument('--seed', type=int, default=None, help='Переопределить начальный seed')
    args = parser.parse_args()
    if args.policy_sequence:
        if args.policy:
            parser.error('Используйте либо --policy, либо --policy-sequence, но не оба.')
        args.policies = list(PROVISIONAL_POLICY_ORDER)
    if args.time_limit_seconds is not None and args.time_limit_seconds < 0:
        parser.error('--time-limit не может быть отрицательным.')
    if args.max_checks is not None and args.max_checks < 1:
        parser.error('--max-checks должен быть положительным.')
    try:
        proposal = run(args)
        print(json.dumps({'status': proposal['status'], 'policy': proposal['policy'],
                          'installable': proposal['installable'],
                          'strict_installable': proposal.get('strict_installable'),
                          'search_stats': proposal.get('search_stats'),
                          'metrics': proposal['metrics'], 'violations': proposal['violations']},
                         ensure_ascii=False, sort_keys=True))
        if not proposal['installable']:
            parser.exit(2, 'Нет варианта, проходящего границы; сохранена только диагностика.\n')
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, 'Ошибка подбора: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
