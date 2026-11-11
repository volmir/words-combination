#!/usr/bin/env python3
"""Finite offline player model, mirroring core TaskRules/HintRules/HintLadder.

This is a model, not audience telemetry. No runtime player limits are introduced.
"""
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import random
import re

import hint_text
from validate_campaign import BANNED, normalize

CONFIG = Path(__file__).with_name('campaign_simulation_config.json')


def load_config(path=CONFIG):
    c = json.loads(Path(path).read_text(encoding='utf-8'))
    if c.get('schema') != 'campaign-simulation-v1':
        raise ValueError('Unknown simulation schema')
    if type(c['runs']) is not int or c['runs'] < 500 or type(c['seed']) is not int:
        raise ValueError('Need >=500 runs and an integer seed')
    k = c['knowledge']
    if (k['curve'] != 'bounded_logistic_log1p_frequency' or not 0 < k['baseline_mean'] < 1
            or abs(k['baseline_mean'] - .45) > .01 or k['slope'] <= 0
            or not 0 <= k['floor'] < k['baseline_mean'] < k['ceiling'] <= 1):
        raise ValueError('Invalid monotone knowledge curve')
    if k['pool'] != 'GEN01_recognizable_targets':
        raise ValueError('Unknown calibration pool')
    for key in ('search_words', 'input_attempts', 'hint_requests'):
        if type(c['player'][key]) is not int or c['player'][key] < 0:
            raise ValueError('Invalid finite player budget: ' + key)
    if c['player']['policy'] != 'uniform_search_then_fixed_target_ladder':
        raise ValueError('Unknown guessing policy')
    if set(c['player']['guess_success']) != {'THEME', 'DEFINITION', 'LENGTH', 'FIRST_LETTER', 'PATTERN', 'ANSWER'}:
        raise ValueError('Missing guessing probabilities')
    if any(not 0 <= p <= 1 for p in c['player']['guess_success'].values()) or c['player']['guess_success']['ANSWER'] != 1:
        raise ValueError('Invalid guessing probabilities')
    if c['comparison'] != 'inclusive_point_estimate' or c['uncertainty'] != 'wilson_95':
        raise ValueError('Unknown probability comparison/uncertainty')
    targets = c['targets']
    if [t['level'] for t in targets] != list(range(1, 31)):
        raise ValueError('Need explicit targets for all 30 levels')
    if (targets[0]['required_min'] != .95 or targets[-1]['required_min'] != .80
            or targets[0]['master_min'] != .70 or targets[-1]['master_max'] != .25):
        raise ValueError('Endpoint targets disagree with GEN-05')
    for t in targets:
        if not 0 <= t['master_min'] <= t['master_max'] <= 1 or not 0 <= t['required_min'] <= 1:
            raise ValueError('Invalid probability target')
        if t['level'] <= 5 and t['master_max'] < t['required_min']:
            raise ValueError('M=R: full-star ceiling cannot be below required-star floor')
    if any(a['required_min'] < b['required_min'] for a, b in zip(targets, targets[1:])):
        raise ValueError('Required-star targets must decrease')
    for key in ('max_candidates', 'alternatives_per_level', 'source_slots'):
        if type(c['rebalance'][key]) is not int or c['rebalance'][key] < 1:
            raise ValueError('Invalid bounded rebalance budget')
    return c


class Knowledge:
    """One global calibration; never refit to a level or to simulation outcomes."""
    def __init__(self, metadata, pool, config):
        self.config = config
        self.frequencies = {w: m.get('observed_frequency', {}).get('frequency', 0) for w, m in metadata.items()}
        pool = sorted(pool)
        if not pool:
            raise ValueError('Empty GEN-01 recognizable calibration pool')
        lo, hi = -100., 100.
        for _ in range(80):
            mid = (lo + hi) / 2
            mean = sum(self.at_frequency(self.frequencies[w], mid) for w in pool) / len(pool)
            if mean < config['baseline_mean']:
                lo = mid
            else:
                hi = mid
        self.intercept = (lo + hi) / 2
        self.mean = sum(self(w) for w in pool) / len(pool)
        self.pool_size = len(pool)
        self.pool_sha256 = hashlib.sha256(('\n'.join(pool) + '\n').encode()).hexdigest()

    def at_frequency(self, frequency, intercept=None):
        if frequency < 0 or not math.isfinite(frequency):
            raise ValueError('Invalid observed frequency')
        c = self.config
        z = (self.intercept if intercept is None else intercept) + c['slope'] * math.log1p(frequency)
        logistic = 1 / (1 + math.exp(-max(-700, min(700, z))))
        return c['floor'] + (c['ceiling'] - c['floor']) * logistic

    def __call__(self, word):
        return self.at_frequency(self.frequencies.get(word, 0))


def accepts(task, word, count_min_length=4):
    kind = task['type']
    if kind == 'T1':
        return len(word) >= count_min_length
    if kind == 'T2':
        return len(word) >= count_min_length and word.startswith(task['letter'])
    if kind == 'T3':
        return len(word) >= task['minLength']
    if kind == 'T4':
        return word in task['targets']
    if kind == 'T5':
        return len(word) >= task['minLength'] and word.endswith(task['ending'])
    if kind == 'T6':
        return word in task['words']
    if kind == 'T7':
        return task['letter'] in word
    raise ValueError('Unknown task: ' + kind)


def needed(task):
    return len(task['targets']) if task['type'] == 'T4' else task.get('n', 1)


def safe_theme(word, text, forms=(), related=()):
    clean = hint_text.clean(text)
    if clean is None:
        return None
    normalized = normalize(clean)
    tokens = [normalize(w) for w in re.findall(r'[А-Яа-яЁё\u0300\u0301]+', clean)]
    for relative in map(normalize, [word, *forms, *related]):
        if relative and (relative in normalized or any(hint_text._shares_root(relative, token) or
                (len(relative) >= 3 and len(token) >= 3 and relative[:3] == token[:3]) for token in tokens)):
            return None
    return clean


def ladder(word, task, data, rng):
    """Positions, skipped/repeated text, T2/T5 known letters: HintLadder.build."""
    kind = task.get('type')
    known = {0} if kind == 'T2' else set(range(len(word) - len(task['ending']), len(word))) if kind == 'T5' else set()
    positions = set(known)
    steps, texts = [], set()
    for stage, text in [('THEME', data.get('theme')), ('DEFINITION', data.get('definition'))]:
        if text is not None and normalize(text) not in texts:
            texts.add(normalize(text))
            if not (stage == 'THEME' and kind == 'T6' and data.get('category') == task['category']):
                steps.append((stage, frozenset(positions)))
    steps.append(('LENGTH', frozenset(positions)))
    if 0 not in positions:
        positions.add(0)
        steps.append(('FIRST_LETTER', frozenset(positions)))
    while len(positions) < len(word) // 2:
        hidden = sorted(set(range(len(word))) - positions)
        preferred = [i for i in hidden if word[i] == task.get('letter')]
        positions.add(rng.choice(preferred or hidden))
        steps.append(('PATTERN', frozenset(positions)))
    steps.append(('ANSWER', frozenset(range(len(word)))))
    return steps


class LevelModel:
    def __init__(self, level, answers, metadata, familiar, hint_data, count_min_length=4):
        self.level, self.tasks = level, level['tasks']
        self.answers = sorted(set(answers))
        self.hint_data = hint_data
        self.quality = {w: 2 * (not metadata.get(w, {}).get('labels', [])) + (w in familiar) for w in self.answers}
        self.matches = {w: {t['id'] for t in self.tasks if accepts(t, w, count_min_length)} for w in self.answers}
        self.needs = {t['id']: needed(t) for t in self.tasks}

    def complete(self, found):
        counts = Counter(key for w in found for key in self.matches[w])
        return {key for key, n in self.needs.items() if counts[key] >= n}

    def target(self, found, rng):
        candidates = [w for w in self.answers if w not in found and w not in BANNED]
        completed = self.complete(found)
        unfinished = [t for t in self.tasks if t['id'] not in completed]
        ordered = [t for t in unfinished if t['type'] == 'T4'] + [t for t in unfinished if t['type'] != 'T4']
        def choose(words):
            best = max(self.quality[w] for w in words)
            return rng.choice([w for w in words if self.quality[w] == best])
        for task in ordered:
            eligible = [w for w in candidates if task['id'] in self.matches[w]
                        and (task['type'] != 'T3' or w == task['hintTarget'])]
            if eligible:
                return choose(eligible), task
        return (choose(candidates), {}) if candidates else (None, {})


def trial(model, probability, policy, seed, use_hints=True):
    # Separate streams keep a player's once-sampled knowledge independent of task/hint changes.
    knowledge_rng = random.Random(str(seed) + ':knowledge')
    rng = random.Random(str(seed) + ':actions')
    known = {w for w in model.answers if knowledge_rng.random() < probability(w)}
    found = set()
    completed, counts = set(), Counter()
    def enter(word):
        if word not in found:
            found.add(word)
            for key in model.matches[word]:
                counts[key] += 1
                if counts[key] >= model.needs[key]:
                    completed.add(key)
    attempts = paid = requests = reveals = failed_guesses = 0
    initial = [5, 5, 4, 4, 3, 3][model.level['stage'] - 1]  # HintRules.budget
    order = list(model.answers)
    rng.shuffle(order)
    inspected = 0
    for word in order[:policy['search_words']]:
        if attempts >= policy['input_attempts'] or len(completed) == len(model.tasks):
            break
        inspected += 1
        if word in known:
            attempts += 1
            enter(word)  # Only explicit input changes progress/earned budget.
    target, task, steps, step_index = None, {}, [], 0
    while (use_hints and requests < policy['hint_requests'] and attempts < policy['input_attempts']
           and len(completed) < len(model.tasks)):
        if target is None or target in found:
            target, task = model.target(found, rng)
            if target is None:
                break
            steps = ladder(target, task, model.hint_data.get(target, {}), rng)
            step_index = 0
        if step_index == len(steps):
            break
        stage, _ = steps[step_index]
        free = task.get('type') == 'T4' and stage in ('THEME', 'DEFINITION', 'LENGTH')
        if not free and initial + len(found) // 4 - paid <= 0:
            break
        paid += not free
        requests += 1
        step_index += 1
        reveals += stage == 'ANSWER'
        # A clue prompts one explicit attempt, including wrong guesses. The answer
        # step does not add found automatically; it consumes the next input attempt.
        attempts += 1
        success = target in known or rng.random() < policy['guess_success'][stage]
        if success:
            enter(target)
        else:
            failed_guesses += 1
    return dict(stars=len(completed), completed=sorted(completed), found=sorted(found),
                attempts=attempts, search_words=inspected, hint_requests=requests,
                initial_budget=initial, earned=len(found) // 4, paid=paid,
                remaining=initial + len(found) // 4 - paid, answer_shown=reveals,
                failed_guesses=failed_guesses)


def estimate(successes, runs):
    p = successes / runs
    z = 1.959963984540054
    denominator = 1 + z*z/runs
    center = (p + z*z/(2*runs)) / denominator
    half = z * math.sqrt(p*(1-p)/runs + z*z/(4*runs*runs)) / denominator
    return dict(successes=successes, runs=runs, probability=p,
                wilson95=[max(0., center-half), min(1., center+half)])


def deviations(row, target):
    result = []
    for field, direction, threshold in [('p_required', 'min', target['required_min']),
                                         ('p_master', 'min', target['master_min']),
                                         ('p_master', 'max', target['master_max'])]:
        value = row[field]['probability']
        gap = threshold - value if direction == 'min' else value - threshold
        if gap > 1e-12:
            result.append(dict(metric=field, rule=direction, target=threshold, actual=value, gap=gap))
    return result


def simulate(model, probability, config):
    runs = config['runs']
    histogram = [0] * (len(model.tasks) + 1)
    totals = Counter()
    for index in range(runs):
        result = trial(model, probability, config['player'], f"{config['seed']}:{model.level['number']}:{index}")
        histogram[result['stars']] += 1
        totals.update({k: result[k] for k in ('attempts', 'search_words', 'hint_requests', 'paid', 'earned', 'answer_shown')})
    row = dict(level=model.level['number'], word=model.level['word'], M=len(model.tasks), R=model.level['requiredStars'],
               runs=runs, p_required=estimate(sum(histogram[model.level['requiredStars']:]), runs),
               p_master=estimate(histogram[-1], runs), stars_histogram=histogram,
               mean_actions={k: v/runs for k, v in sorted(totals.items())})
    row['targets'] = config['targets'][row['level'] - 1]
    row['deviations'] = deviations(row, row['targets'])
    return row
