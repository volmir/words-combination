#!/usr/bin/env python3
"""GEN-02: bounded deterministic source selection from a verified GEN-01 snapshot."""
import argparse
import csv
from functools import lru_cache
import gzip
import json
import math
from pathlib import Path
import random
import re

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
# Search-only penalty per missing/out-of-band slot. It ranks intermediate
# candidates; it never turns a band violation into an installable result.
DEFICIT_WEIGHT = 100.0


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
    def __init__(self, payload, words, previous, config, alphabet):
        self.profiles = dict(payload['profiles'])
        self.metadata = payload['words']
        self.words = set(map(normalize_form, words))
        self.previous = previous
        self.old = {normalize_form(row['word']) for row in previous}
        self.config, self.alphabet = config, alphabet
        self.size = config['levels']
        by_role = {role: dict(rules=0, ai=0, pending=0) for role in ('source', 'target')}
        for row in payload.get('assessments', []):
            by_role[row['role']][row['assessment_source']] += 1
        self.semantic_coverage = {
            'recorded': 'assessments' in payload,
            'pending': sum(counts['pending'] for counts in by_role.values())
                if 'assessments' in payload else None,
            'by_role': by_role}
        self.candidates = sorted(w for w, p in self.profiles.items() if p['source_eligible'] and
                                 w not in ROLE_EXCLUSIONS and 8 <= len(w) <= 13 and
                                 normalize_form(w) == w and set(w) <= set(alphabet))
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
        self.shortlists = [self.shortlist(slot) for slot in range(self.size)]

    def band(self, slot):
        return next(b for b in self.config['bands'] if b['first'] <= slot + 1 <= b['last'])

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

    def local_failures(self, slot, word):
        if word not in self.eligible:
            return ['source_ineligible']
        b, failures = self.band(slot), []
        count = len(self.profiles[word]['recognizable_answers'])
        if not b['min'] <= count <= b['max']:
            failures.append('recognizable_range')
        failures.extend(t + '_empty' for t in self.types(slot) if not self.options[word][t])
        return failures

    def range_distance(self, slot, word):
        count, b = len(self.profiles[word]['recognizable_answers']), self.band(slot)
        return max(b['min'] - count, count - b['max'], 0) / b['min']

    def shortlist(self, slot):
        def rank(w):
            return (len(self.local[slot, w]), self.range_distance(slot, w),
                    -int(w in self.old), -int(w in PREFERRED_SOURCES),
                    -(self.rec_bits[w] & self.top_bits).bit_count(), w)
        ordered = sorted(self.candidates, key=rank)
        chosen = set(ordered[:self.config['search']['shortlist_per_slot']])
        chosen.update(self.eligible & (self.old | PREFERRED_SOURCES))
        for letter in self.alphabet:
            chosen.update([w for w in ordered if letter in w][:self.config['search']['carriers_per_letter']])
        return sorted(chosen)

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

    def slot_deficit(self, slot, word):
        """Band shortfall of one slot; empty slots cost a full slot."""
        return 1.0 if word is None else self.range_distance(slot, word)

    def reservations(self, selected):
        groups = {'targets_T3_T4': {}, 'suffixes_T5': {}, 'themes_T6': {}}
        reserve = 0
        for slot, word in enumerate(selected):
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

    def measure(self, selected):
        all_bits = rec_bits = 0
        letters = set()
        for w in selected:
            if w:
                letters.update(w)
                all_bits |= self.all_bits.get(w, 0)
                rec_bits |= self.rec_bits.get(w, 0)
        pairs = [(self.pair(a, b), i + 1, j + 1) for i, a in enumerate(selected) if a
                 for j, b in enumerate(selected[:i]) if b]
        return {'selected': sum(w is not None for w in selected),
                'unique_sources': len(set(selected) - {None}),
                'retained': len(set(selected) & self.old),
                'preferred': sorted(set(selected) & PREFERRED_SOURCES),
                'alphabet': self.alphabet,
                'covered_letters': ''.join(c for c in self.alphabet if c in letters),
                'missing_letters': ''.join(c for c in self.alphabet if c not in letters),
                'jaccard_pairs': len(pairs),
                'mean_jaccard': sum(p[0] for p in pairs) / len(pairs) if pairs else 0.0,
                'max_jaccard': max((p[0] for p in pairs), default=0.0),
                'top5000_denominator': len(self.top), 'top5000_requested': 5000,
                'observed_game_lemmas': self.observed_count,
                'top5000_recognizable_covered': (rec_bits & self.top_bits).bit_count(),
                'top5000_all_covered': (all_bits & self.top_bits).bit_count(),
                'recognizable_union': rec_bits.bit_count(), 'all_answers_union': all_bits.bit_count(),
                'game_dictionary_size': len(self.words)}

    def evaluate(self, selected, partial=False):
        m = self.measure(selected)
        failures, cost = [], 0.0

        def fail(code, amount=1, **details):
            nonlocal cost
            cost += amount
            failures.append({'code': code, **details})

        if not partial and len(selected) != self.size:
            fail('slot_count', abs(len(selected) - self.size), actual=len(selected), expected=self.size)
        for slot, word in enumerate(selected):
            if word is None:
                if not partial:
                    fail('empty_slot', slot=slot + 1)
                continue
            local = self.local.get((slot, word), ['source_ineligible'])
            for code in local:
                fail(code, slot=slot + 1, word=word)
            if word in self.eligible:
                cost += self.range_distance(slot, word)
        if m['selected'] > m['unique_sources']:
            fail('duplicate_sources', m['selected'] - m['unique_sources'])
        if m['retained'] > self.config['retain_max']:
            fail('retained_max', m['retained'] - self.config['retain_max'])
        remaining = self.size - m['selected'] if partial else 0
        if m['retained'] + remaining < self.config['retain_min']:
            fail('retained_min', self.config['retain_min'] - m['retained'] - remaining)
        if not partial and m['missing_letters']:
            fail('alphabet_coverage', len(m['missing_letters']), missing=m['missing_letters'])
        for metric, limit in [('mean_jaccard', 'mean_jaccard_max'), ('max_jaccard', 'max_jaccard_max')]:
            if m[metric] > self.config[limit] + 1e-12:
                fail(metric, (m[metric] - self.config[limit]) / self.config[limit],
                     actual=m[metric], limit=self.config[limit])
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
            score += (len(m['missing_letters']), -min(m['retained'], self.config['retain_min']))
        return score, m, failures, reservations

    def optimization_score(self, selected):
        """Order intermediate candidates; evaluate() remains the final validator.

        Missing slots and range shortfalls are explicit penalties so refinement
        can improve a 26/30 candidate. They are never removed from final
        validation: a returned candidate is valid only when evaluate() has no
        failures at all.
        """
        _, metrics, failures, _ = self.evaluate(selected)
        gap = len(selected) < self.size or any(word is None for word in selected)
        allowed = {'mean_jaccard', 'max_jaccard', 'recognizable_range'}
        if gap:
            allowed |= {'slot_count', 'empty_slot', 'alphabet_coverage', 'retained_min'}
        other = sum(row['code'] not in allowed for row in failures)
        missing = self.size - sum(word is not None for word in selected)
        deficit = missing + sum(self.slot_deficit(slot, word)
                                for slot, word in enumerate(selected) if word is not None)

        def excess(value, limit):
            return max(value - limit, 0.0) / limit

        jaccard = (excess(metrics['mean_jaccard'], self.config['mean_jaccard_max']) +
                   excess(metrics['max_jaccard'], self.config['max_jaccard_max']))
        return (other, round(deficit, 12), round(jaccard, 12),
                -len(metrics['preferred']), -metrics['top5000_recognizable_covered'],
                -metrics['all_answers_union'], len(metrics['missing_letters']),
                -min(metrics['retained'], self.config['retain_min']))

    def improve(self, selected):
        selected = list(selected)
        before = tuple(selected)
        best_score = self.evaluate(selected)[0]
        # Best improvement, stable tie: score, slot, normalized word.
        best = None
        used = set(selected)
        for slot in range(self.size):
            for word in self.shortlists[slot]:
                if word in used:
                    continue
                proposal = selected.copy()
                proposal[slot] = word
                score = self.evaluate(proposal)[0]
                if score < best_score:
                    best_score, best = score, proposal
        # Also move sources between slots; two candidates can unblock each other.
        for a in range(self.size):
            for b in range(a):
                if selected[a] == selected[b]:
                    continue
                proposal = selected.copy()
                proposal[a], proposal[b] = proposal[b], proposal[a]
                if proposal[a] is not None and proposal[b] is not None:
                    score = self.evaluate(proposal)[0]
                    if score < best_score:
                        best_score, best = score, proposal
        return (best if best is not None else selected), before != tuple(best or selected)

    def refine_jaccard(self, selected):
        """Optimize overlap while allowing explicitly penalized band deficits.

        An intermediate 26/30 candidate or a full-length candidate with empty
        ranges may enter refinement. The result reports hard matching
        infeasibility, search exhaustion and the best found candidate
        separately. Only evaluate() decides installability; it is never
        weakened here.
        """
        original = list(selected)
        words = original + [None] * max(0, self.size - len(original))
        if len(words) > self.size:
            words = words[:self.size]
        _, _, failures, _ = self.evaluate(words)
        gap = len(original) < self.size or any(word is None for word in words)
        allowed = {'mean_jaccard', 'max_jaccard', 'recognizable_range'}
        if gap:
            allowed |= {'slot_count', 'empty_slot', 'alphabet_coverage', 'retained_min'}
        feasibility = self.impossibility()
        source_matching = feasibility['distinct_source_matching']
        hard_by_matching = source_matching['matched'] < self.size

        def result(best, trace):
            _, _, final_failures, _ = self.evaluate(best)
            valid = not final_failures
            return RefinementResult(
                best_found=list(best), trace=trace,
                hard_infeasible_by_matching=hard_by_matching,
                hard_infeasible=feasibility['proven_infeasible'],
                search_exhausted=not valid and not feasibility['proven_infeasible'],
                valid=valid, violations=final_failures,
                matching=source_matching, seed=self.config['seed'])

        if any(row['code'] not in allowed for row in failures):
            return result(original, [])

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
        pools = [sorted(w for w in self.candidates if not self.local[i, w]) for i in range(self.size)]
        old_pools = [[w for w in pool if w in self.old] for pool in pools]
        masks = {w: sum(1 << self.alphabet.index(c) for c in set(w)) for w in self.candidates}
        full_mask = (1 << len(self.alphabet)) - 1
        pair_limit = self.config['max_jaccard_max']
        sum_limit = self.size*(self.size-1)/2*self.config['mean_jaccard_max']
        limits = self.config['search']
        preferred = set(seed_words) & PREFERRED_SOURCES

        def matrix(words):
            return [[self.pair(a, b) if i != j and a and b else 0.0
                     for j, b in enumerate(words)] for i, a in enumerate(words)]

        def deficit_energy(words):
            return sum(self.slot_deficit(slot, word) for slot, word in enumerate(words))

        # First reduce total overlap, then remove the last excessive pairs.
        # The deficit term keeps filling missing bands while Jaccard is reduced.
        # These weights guide search; the final validator still uses exact bounds.
        for penalty in (1000, 1000000):
            rng = random.Random(self.config['seed'])

            def pair_cost(value):
                return value + penalty*max(value-pair_limit, 0)**2

            def energy(values, words):
                return (sum(pair_cost(values[i][j]) for i in range(self.size) for j in range(i)) +
                        DEFICIT_WEIGHT*deficit_energy(words))

            lowest_energy = energy(matrix(seed_words), seed_words)
            for restart in range(limits['refinement_restarts']):
                words = seed_words.copy()
                values = matrix(words)
                current = energy(values, words)
                retained = len(set(words) & self.old)
                accepted = 0
                steps = limits['refinement_iterations']
                for step in range(steps):
                    slot = rng.randrange(self.size)
                    if step % 50 == 0:
                        other = rng.randrange(self.size)
                        if (words[slot] is not None and words[other] is not None and
                                not self.local[slot, words[other]] and
                                not self.local[other, words[slot]]):
                            words[slot], words[other] = words[other], words[slot]
                            values = matrix(words)
                            current = energy(values, words)
                        continue
                    # Preferences stay soft: the final restart can replace them.
                    if (penalty == 1000 or restart+1 < limits['refinement_restarts']) and words[slot] in preferred:
                        continue
                    pool = old_pools[slot] if words[slot] in self.old and retained == self.config['retain_min'] else pools[slot]
                    if not pool:
                        continue
                    word = rng.choice(pool)
                    if word in words:
                        continue
                    new_retained = retained + (word in self.old) - (words[slot] in self.old)
                    if not self.config['retain_min'] <= new_retained <= self.config['retain_max']:
                        continue
                    mask = masks[word]
                    for i, current_word in enumerate(words):
                        if i != slot and current_word is not None:
                            mask |= masks[current_word]
                    if mask != full_mask:
                        continue
                    row = [self.pair(word, current_word) if j != slot and current_word else 0.0
                           for j, current_word in enumerate(words)]
                    delta = (sum(pair_cost(v)-pair_cost(old) for v, old in zip(row, values[slot])) +
                             DEFICIT_WEIGHT*(self.slot_deficit(slot, word) -
                                              self.slot_deficit(slot, words[slot])))
                    start_temperature = .3 if penalty == 1000 else 1.0
                    temperature = start_temperature*(.002/start_temperature)**(step/steps)
                    if delta > 0 and rng.random() >= math.exp(-delta/temperature):
                        continue
                    words[slot], retained = word, new_retained
                    for j, value in enumerate(row):
                        values[slot][j] = values[j][slot] = value
                    current += delta
                    accepted += 1
                    if current <= sum_limit+1e-10 and max(map(max, values)) <= pair_limit+1e-12:
                        _, _, candidate_failures, _ = self.evaluate(words)
                        if not candidate_failures:
                            trace.append(dict(phase='jaccard_refinement', penalty=penalty, restart=restart,
                                              iterations=step+1, accepted=accepted,
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
                                  iterations=steps, accepted=accepted, score=best_score))
        return result(best, trace)

    def search(self):
        rng, best, trace = random.Random(self.config['seed']), None, []
        limits = self.config['search']
        for restart in range(limits['restarts']):
            selected = [None] * self.size
            order = sorted(range(self.size), key=lambda slot: (
                sum(not self.local[slot, w] for w in self.candidates), -slot))
            if restart:
                rng.shuffle(order)
            for slot in order:
                choices = []
                for word in self.shortlists[slot]:
                    if word in selected:
                        continue
                    proposal = selected.copy()
                    proposal[slot] = word
                    choices.append((self.evaluate(proposal, partial=True)[0], word))
                if choices:
                    selected[slot] = min(choices)[1]
            changes = 0
            for _ in range(limits['max_iterations']):
                selected, changed = self.improve(selected)
                if not changed:
                    break
                changes += 1
            score = self.evaluate(selected)[0]
            trace.append({'restart': restart, 'local_changes': changes, 'score': score})
            key = (score, tuple(w or '' for w in selected))
            if best is None or key < best[0]:
                best = key, selected
        refinement = self.refine_jaccard(best[1])
        return refinement['best_found'], trace+refinement['trace']

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


def make_proposal(selector, selected, trace, inputs):
    score, metrics, failures, reservations = selector.evaluate(selected)
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
            'hard_infeasible_by_matching': hard_by_matching,
            'search_exhausted': status == 'search_exhausted',
            'best_found': list(selected),
            'diagnostic_only': not installable, 'inputs': inputs, 'config': selector.config,
            'score': score, 'search_trace': trace, 'feasibility': feasibility,
            'semantic_coverage': selector.semantic_coverage,
            'metrics': metrics, 'violations': failures, 'slots': slots,
            'previous_metrics': selector.measure(old_selected),
            'previous_collisions': collision_rows(selector, old_selected),
            'collisions': collision_rows(selector, selected),
            'preferred_sources': preferred, 'planning_reservations': reservations,
            'planning_note': 'Предварительные свидетели ёмкости; параметры заданий не назначены. GEN-03 обязан повторно проверить достижимость и уникальность.',
            'top5000_definition': 'Первые min(5000, N) игровых лемм с наблюдаемой частотой > 0, порядок (-frequency, normalized_word). Основная метрика — узнаваемые ответы; общий словарный ввод показан отдельно.',
            'search_note': 'Лучший по опубликованному score среди проверенных вариантов ограниченного жадного поиска. Это не доказательство глобальной оптимальности. Порядок score: наличие нарушений, нормированная стоимость нарушений, предпочтительные слова, топ-5000 узнаваемых, запас, общий словарь.'}


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
    lines = ['# Подбор исходников — GEN-02', '',
             f"Статус: **{proposal['status']}**. Установка разрешена числовой проверкой: **{proposal['installable']}**.",
             'Вариант с нарушениями сохраняется исключительно для диагностики; data/ не изменяется.', '',
             proposal['search_note'], '',
             f"Жёсткие границы: 30 разных исходников; {selector.config['retain_min']}–{selector.config['retain_max']} старых; все 32 знака Alphabet.LETTERS; диапазон каждого слота; средний Jaccard ≤3%, максимальный ≤15% по умолчанию. Нет требования «большинство».",
             'Мягкие критерии: три предпочтительных слова, максимизация топ-5000, запас будущих заданий и охват всего словаря.', '',
             '| Метрика | Старая кампания | Новый вариант |', '|---|---:|---:|']
    for key, label in [('selected', 'Исходников'), ('unique_sources', 'Разных исходников'),
                       ('retained', 'Старых исходников'), ('recognizable_union', 'Объединение узнаваемых'),
                       ('all_answers_union', 'Объединение всех ответов'), ('game_dictionary_size', 'Размер игрового словаря'),
                       ('top5000_recognizable_covered', 'Топ-5000: узнаваемые'),
                       ('top5000_all_covered', 'Топ-5000: все ответы')]:
        lines.append(f'| {label} | {old[key]} | {m[key]} |')
    for key in ('mean_jaccard', 'max_jaccard'):
        lines.append(f'| {key} | {old[key]:.4%} | {m[key]:.4%} |')
    lines.extend(['', proposal['top5000_definition'],
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
                  f"Seed: {selector.config['seed']}; лимиты: {json.dumps(selector.config['search'], ensure_ascii=False, sort_keys=True)}.",
                  'Нет времени, абсолютных путей или случайных идентификаторов в артефактах. Исходные хэши, конфиг, trace и score сохранены в JSON. При исчерпании поиска без доказательства невозможности статус search_exhausted, не infeasible.', ''])
    (directory / 'LEVEL_WORDS_REPORT.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def run(args):
    config, alphabet = load_config(args.config), read_alphabet(args.alphabet)
    profile_path = args.profiles
    if profile_path is None:
        summary = json.loads((ROOT / 'build/reports/level_profiles/summary.json').read_text())
        profile_path = Path(summary['cache_path'])
    document = load_snapshot(profile_path, args.words, args.previous_levels)
    words = read_list(args.words)
    previous = json.loads(args.previous_levels.read_text(encoding='utf-8'))
    selector = Selector(document['payload'], words, previous, config, alphabet)
    selected, trace = selector.search()
    inputs = {'profile_payload_sha256': document['payload_sha256'], 'profile_inputs': document['inputs'],
              'profiles_sha256': sha256_file(profile_path), 'words_sha256': sha256_file(args.words),
              'previous_levels_sha256': sha256_file(args.previous_levels),
              'config_sha256': sha256_file(args.config), 'alphabet_sha256': sha256_file(args.alphabet),
              'selector_sha256': sha256_file(Path(__file__))}
    proposal = make_proposal(selector, selected, trace, inputs)
    write_reports(selector, proposal, args.reports)
    return proposal


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profiles', type=Path, help='Проверенный gzip-кэш GEN-01; по умолчанию его summary.json')
    parser.add_argument('--words', type=Path, default=ROOT / 'data/words.txt')
    parser.add_argument('--previous-levels', type=Path, default=ROOT / 'data/levels.json')
    parser.add_argument('--config', type=Path, default=CONFIG)
    parser.add_argument('--alphabet', type=Path, default=ALPHABET)
    parser.add_argument('--reports', type=Path, default=ROOT / 'build/reports')
    args = parser.parse_args()
    try:
        proposal = run(args)
        print(json.dumps({'status': proposal['status'], 'installable': proposal['installable'],
                          'metrics': proposal['metrics'], 'violations': proposal['violations']},
                         ensure_ascii=False, sort_keys=True))
        if not proposal['installable']:
            parser.exit(2, 'Нет варианта, проходящего границы; сохранена только диагностика.\n')
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, 'Ошибка подбора: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
