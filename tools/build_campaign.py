#!/usr/bin/env python3
"""GEN-03: deterministic task allocation; candidate artifacts only, no data install."""
import argparse
from collections import Counter, defaultdict
from functools import lru_cache
import json
from pathlib import Path

import pick_level_words as profiles
import select_level_words as selection
from build_dictionary import read_list, sha256_file
from game_data_providers import atomic_json, json_digest
from game_data_quality import normalize_form
from word_categories import ROLE_EXCLUSIONS

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'tools/campaign_config.json'
CURVES = {'T1': [6, 22], 'T2': [3, 7], 'T3': [5, 9],
          'T5': [5, 8], 'T6': [3, 5], 'T7': [3, 5]}
RESERVES = [dict(first=1, last=10, numerator=3, denominator=1),
            dict(first=11, last=20, numerator=5, denominator=2),
            dict(first=21, last=30, numerator=2, denominator=1)]


def load_config(path=CONFIG):
    c = json.loads(Path(path).read_text(encoding='utf-8'))
    if (c.get('schema') != 'campaign-config-v1' or c['count_min_length'] != 4 or
            c['curves'] != CURVES or c['reserve_bands'] != RESERVES or
            c['riddles_by_stage'] != {'4': 1, '5': 2, '6': 3} or c['category_global_min'] != 40):
        raise ValueError('Параметры кампании расходятся с GEN-03')
    rotation = c['master_types']
    if (len(rotation) != 5 or len({tuple(row) for row in rotation}) != 5 or
            any(len(row) != 5 or len(set(row)) != 5 or
                not set(row) <= {f'T{i}' for i in range(1, 8)} for row in rotation) or
            not {'T6', 'T7'} <= set().union(*map(set, rotation))):
        raise ValueError('Неверная ротация Мастера')
    for key in ('max_attempts', 'beam_width', 'replacements_per_slot'):
        if type(c['search'][key]) is not int or c['search'][key] <= 0:
            raise ValueError('Неверный бюджет поиска: ' + key)
    if not c['endings'] or any(normalize_form(s) != s or not 2 <= len(s) <= 4 or
            not set(s) <= set(selection.read_alphabet()) or not isinstance(reason, str) or
            not reason.strip() for s, reason in c['endings'].items()):
        raise ValueError('Нужны нормализованные осмысленные окончания с обоснованиями')
    return c


def schedule(config):
    result = []
    seen = Counter()
    types = [[f'T{i}' for i in range(1, (n - 1) // 5 + 2)] if n <= 25
             else config['master_types'][n - 26] for n in range(1, 31)]
    totals = Counter(t for row in types for t in row)
    for number, row in enumerate(types, 1):
        stage = (number - 1) // 5 + 1
        tasks = []
        for t in row:
            task = dict(id=f'l{number:02d}-{t.lower()}', type=t)
            if t == 'T4':
                task['targetCount'] = config['riddles_by_stage'][str(stage)]
            else:
                start, end = config['curves'][t]
                task['minLength' if t in ('T3', 'T5') else 'n'] = (
                    start + (end - start) * seen[t] // (totals[t] - 1))
            task['introduce'] = seen[t] == 0
            seen[t] += 1
            tasks.append(task)
        result.append(dict(number=number, stage=stage,
                           requiredStars=4 if number == 30 else [1, 1, 2, 2, 3, 3][stage - 1],
                           tasks=tasks))
    return result


def reserve_size(config, number, needed):
    b = next(b for b in config['reserve_bands'] if b['first'] <= number <= b['last'])
    return (needed * b['numerator'] + b['denominator'] - 1) // b['denominator']


def alternating_letters(options):
    """Dynamic programming across T2 appearances, including gaps in the rotation."""
    keys = list(options)

    @lru_cache(None)
    def visit(index, previous):
        if index == len(keys):
            return ()
        for letter in options[keys[index]]:
            if letter != previous:
                tail = visit(index + 1, letter)
                if tail is not None:
                    return (letter,) + tail
        return None

    values = visit(0, '')
    return None if values is None else dict(zip(keys, values))


class CampaignBuilder:
    def __init__(self, payload, words, config):
        self.config = config
        self.payload = payload
        self.words = {normalize_form(w) for w in words}
        self.metadata = payload['words']
        self.index = profiles.MultisetIndex(w for w in self.words if len(w) >= 3)
        self.plan = schedule(config)
        self.familiar = {row['word'] for row in payload['assessments']
                         if row['role'] == 'target' and row['assessment']['status'] == 'suitable'
                         and self.metadata[row['word']]['observed_frequency']['frequency'] >= 10}
        self.familiar &= self.words - ROLE_EXCLUSIONS
        self.global_categories = defaultdict(set)
        for w in sorted(self.familiar):
            category = self.category(w)
            if category:
                self.global_categories[category].add(w)
        self.letter_counts = Counter(''.join(sorted(self.words)))
        self.local = lru_cache(None)(self._local)
        self.answers = lru_cache(None)(self.index.search)

    def category(self, word):
        meta = self.metadata.get(word, {})
        category = meta.get('category')
        if category and category != 'other' and not meta.get('category_source', 'unknown:').startswith('unknown:'):
            return category
        return None

    def _local(self, number, source):
        """Recount predicates from actual dictionary answers, never from stored metrics."""
        answers = self.answers(source)
        safe = sorted(set(answers) & self.familiar)
        measured = self.payload['profiles'][source]
        assigned = set(measured['assigned_targets']) & set(safe)
        result = {}
        for task in self.plan[number - 1]['tasks']:
            t = task['type']
            needed = task.get('n', task.get('targetCount', 1))
            minimum = reserve_size(self.config, number, needed)
            pools = {}
            if t == 'T1':
                pools['count'] = [w for w in safe if len(w) >= self.config['count_min_length']]
            elif t == 'T2':
                for letter in sorted(set(source)):
                    pools[letter] = [w for w in safe if len(w) >= self.config['count_min_length'] and w[0] == letter]
            elif t == 'T3':
                # The target is the hint anchor, so it must have a useful safely
                # masked definition; the star itself still accepts every long answer.
                pools['length'] = sorted(w for w in assigned if len(w) >= task['minLength']
                                         and self.payload['hints'].get(w, {}).get('useful', False))
            elif t == 'T4':
                pools['riddles'] = sorted(w for w in assigned & set(measured['riddle_targets'])
                    if self.payload['hints'].get(w, {}).get('useful', False))
            elif t == 'T5':
                for ending in sorted(self.config['endings']):
                    pools[ending] = [w for w in safe if len(w) >= task['minLength'] and w.endswith(ending)]
            elif t == 'T6':
                for category, global_words in sorted(self.global_categories.items()):
                    if len(global_words) >= self.config['category_global_min']:
                        pools[category] = sorted(set(safe) & global_words)
            elif t == 'T7':
                for letter in sorted(set(source), key=lambda ch: (self.letter_counts[ch], ch)):
                    pools[letter] = [w for w in safe if letter in w]
            eligible = {key: value for key, value in pools.items() if len(value) >= minimum}
            result[t] = dict(task=task, pools=pools, eligible=eligible, minimum=minimum, needed=needed)
        return result

    def attempt(self, selected):
        failures, local = [], {}
        groups = {'targets': {}, 'endings': {}, 'categories': {}}
        for number, source in enumerate(selected, 1):
            for t, info in self.local(number, source).items():
                task, pools = info['task'], info['eligible']
                local[task['id']] = info
                if not pools:
                    failures.append(dict(code='reserve_shortage', level=number, type=t,
                                         required=info['minimum'], available=max(map(len, info['pools'].values()), default=0)))
                if t == 'T3':
                    groups['targets'][task['id']] = pools.get('length', [])
                elif t == 'T4':
                    for i in range(task['targetCount']):
                        groups['targets'][task['id'] + f'/{i}'] = pools.get('riddles', [])
                elif t in ('T5', 'T6'):
                    groups['endings' if t == 'T5' else 'categories'][task['id']] = list(pools)
        assignments = {}
        for group, options in groups.items():
            match = selection.matching(options)
            assignments.update(match['witness'])
            if match['unmatched']:
                failures.append(dict(code='unique_' + group, tasks=match['unmatched'],
                                     matched=match['matched'], required=match['required']))
        initials = {key: sorted(info['eligible'], key=lambda letter: (-len(info['eligible'][letter]), letter))
                    for key, info in local.items() if info['task']['type'] == 'T2'}
        alternating = alternating_letters(initials)
        if alternating is None:
            failures.append(dict(code='initial_rotation', tasks=list(initials)))
        if failures:
            return None, dict(violations=failures)
        assignments.update(alternating)
        levels, evidence, introductions = [], [], []
        for number, source in enumerate(selected, 1):
            plan = self.plan[number - 1]
            tasks = []
            for planned in plan['tasks']:
                key, t = planned['id'], planned['type']
                info = local[key]
                task = {k: v for k, v in planned.items() if k not in ('introduce', 'targetCount')}
                choice = None
                if t == 'T1':
                    choice = 'count'
                elif t == 'T2':
                    task['letter'] = choice = assignments[key]
                elif t == 'T3':
                    task['hintTarget'] = assignments[key]
                    choice = 'length'
                elif t == 'T4':
                    task['targets'] = [assignments[key + f'/{i}'] for i in range(planned['targetCount'])]
                    choice = 'riddles'
                elif t == 'T5':
                    task['ending'] = choice = assignments[key]
                elif t == 'T6':
                    task['category'] = choice = assignments[key]
                    task['words'] = [w for w in self.answers(source) if self.category(w) == choice]
                elif t == 'T7':
                    # Rarest source letter WITH enough recognizable answers; never a letter absent from source.
                    task['letter'] = choice = next(iter(info['eligible']))
                pool = info['eligible'][choice]
                accepted = self.accepted(source, task)
                row = dict(level=number, source=source, task=task, required_answers=info['needed'],
                           reserve_required=info['minimum'], candidate_pool=pool, candidate_count=len(pool),
                           accepted_answers=accepted, accepted_count=len(accepted))
                if t == 'T4':
                    row['assigned_hint_hashes'] = {w: self.payload['hints'][w]['sha256'] for w in task['targets']}
                    row['reserve_applies_before_assignment'] = True
                elif t == 'T5':
                    row['ending_rationale'] = self.config['endings'][choice]
                elif t == 'T6':
                    row['global_recognizable_count'] = len(self.global_categories[choice])
                    row['global_recognizable_words'] = sorted(self.global_categories[choice])
                elif t == 'T7':
                    row['source_letter_options'] = [dict(letter=ch, dictionary_occurrences=self.letter_counts[ch],
                        candidate_count=len(pool), eligible=ch in info['eligible']) for ch, pool in info['pools'].items()]
                evidence.append(row)
                if planned['introduce']:
                    introductions.append(dict(type=t, taskId=key, levelNumber=number))
                tasks.append(task)
            levels.append(dict(number=number, word=source, stage=plan['stage'],
                               requiredStars=plan['requiredStars'], tasks=tasks))
        campaign = dict(version=2, countMinLength=self.config['count_min_length'],
                        stages=[dict(number=i, firstLevel=5*i-4, lastLevel=5*i) for i in range(1, 7)],
                        levels=levels, introductions=introductions)
        return campaign, dict(violations=[], tasks=evidence)

    def accepted(self, source, task):
        """The runtime predicate, deliberately independent of uniqueness of assignments."""
        t, words = task['type'], self.answers(source)
        if t == 'T1':
            return [w for w in words if len(w) >= self.config['count_min_length']]
        if t == 'T2':
            return [w for w in words if len(w) >= self.config['count_min_length'] and w.startswith(task['letter'])]
        if t == 'T3':
            return [w for w in words if len(w) >= task['minLength']]
        if t == 'T4':
            return sorted(task['targets'])
        if t == 'T5':
            return [w for w in words if len(w) >= task['minLength'] and w.endswith(task['ending'])]
        if t == 'T6':
            return [w for w in words if self.category(w) == task['category']]
        return [w for w in words if task['letter'] in w]


def search(builder, selector, proposal):
    """Bounded beam of swaps/replacements. No candidate bypasses the final GEN-02 check."""
    initial = tuple(row['word'] for row in proposal['slots'])
    limits = builder.config['search']
    visited, trace, attempts = set(), [], 0
    frontier = [initial]
    best_diagnostic = None
    # Check the most demanding initially deficient slots against the FULL source
    # pool before heuristic search. A zero pool is a proof for this measured
    # input, unlike exhausting a beam or a shortlist.
    _, initial_report = builder.attempt(initial)
    feasibility = []
    shortages = [v for v in initial_report['violations'] if v['code'] == 'reserve_shortage']
    if hasattr(selector, 'candidates'):
        for failure in sorted(shortages, key=lambda v: (-v['level'], v['type'])):
            number, t = failure['level'], failure['type']
            sources = [w for w in selector.candidates if not selector.local_failures(number - 1, w)]
            sizes = {w: max(map(len, builder.local(number, w)[t]['pools'].values()), default=0) for w in sources}
            fitting = [w for w in sources if builder.local(number, w)[t]['eligible']]
            row = dict(level=number, type=t, required=failure['required'],
                       source_candidates=len(sources), fitting_sources=fitting,
                       max_candidate_count=max(sizes.values(), default=0),
                       candidate_counts=dict(sorted(sizes.items())))
            feasibility.append(row)
            if not fitting:
                return None, dict(status='infeasible', installable=False, attempts=0,
                    violations=[dict(code='empty_full_source_pool', **row)],
                    feasibility=feasibility, search_trace=[],
                    note='Нет исходника для обязательного задания в полном допустимом пуле GEN-02; перестановки не устраняют дефицит.')
    replacement_cache = {}
    while frontier and attempts < limits['max_attempts']:
        next_states = []
        for selected in frontier:
            if selected in visited:
                continue
            visited.add(selected)
            attempts += 1
            campaign, report = builder.attempt(selected)
            _, metrics, source_failures, _ = selector.evaluate(selected)
            failures = report['violations'] + source_failures
            score = (len(failures), sum(v.get('required', 0) - v.get('available', v.get('matched', 0))
                                        for v in report['violations']), selected)
            if best_diagnostic is None or score < best_diagnostic[0]:
                best_diagnostic = score, failures
                trace.append(dict(attempt=attempts, selected=list(selected), violations=failures))
            if not failures:
                report.update(status='valid', installable=True, attempts=attempts, search_trace=trace,
                              source_metrics=metrics, original_sources=list(initial), final_sources=list(selected),
                              feasibility=feasibility)
                return campaign, report
            # Work on affected slots first, then all slots to repair global source constraints.
            affected = {v['level'] - 1 for v in report['violations'] if 'level' in v}
            for v in report['violations']:
                affected.update(int(k[1:3]) - 1 for k in v.get('tasks', []))
            order = sorted(affected) + [i for i in range(len(selected)) if i not in affected]
            for i in order:
                for j in range(len(selected)):
                    if (i == j or selector.local_failures(i, selected[j]) or
                            selector.local_failures(j, selected[i])):
                        continue
                    changed = list(selected)
                    changed[i], changed[j] = changed[j], changed[i]
                    next_states.append((score, tuple(changed)))
                if i not in replacement_cache:
                    spare = [r['word'] for r in proposal['slots'][i].get('spares', [])]
                    full_pool = getattr(selector, 'candidates', [])
                    choices = list(dict.fromkeys(spare + selector.shortlists[i] + full_pool))
                    choices = [w for w in choices if not selector.local_failures(i, w)]
                    # Re-rank GEN-02's full local pool by final task feasibility;
                    # its preliminary shortlist does not know GEN-03 thresholds.
                    choices.sort(key=lambda w: sum(not info['eligible'] for info in builder.local(i + 1, w).values()))
                    replacement_cache[i] = choices
                choices = replacement_cache[i]
                for word in choices[:limits['replacements_per_slot']]:
                    if word not in selected and not selector.local_failures(i, word):
                        changed = list(selected)
                        changed[i] = word
                        next_states.append((score, tuple(changed)))
            if attempts >= limits['max_attempts']:
                break
        # Cheap task feasibility ranks the frontier; source constraints are checked before accepting.
        ranked = []
        for parent_score, selected in next_states:
            if selected in visited:
                continue
            local_penalty = sum(not info['eligible'] for n, w in enumerate(selected, 1)
                                for info in builder.local(n, w).values())
            ranked.append((local_penalty, parent_score, selected))
        unique = dict.fromkeys(row[2] for row in sorted(ranked))
        frontier = list(unique)[:limits['beam_width']]
    return None, dict(status='search_exhausted', installable=False, attempts=attempts,
                      violations=best_diagnostic[1], search_trace=trace,
                      feasibility=feasibility,
                      note='Бюджет исчерпан; это не доказательство невозможности. Требования не изменены.')


def verify_inputs(args, document, proposal):
    """Bind the proposal to measurements and the full, still available hint package."""
    expected = {'profile_payload_sha256': document['payload_sha256'], 'profile_inputs': document['inputs'],
                'profiles_sha256': sha256_file(args.profiles), 'words_sha256': sha256_file(args.words),
                'previous_levels_sha256': sha256_file(args.previous_levels),
                'config_sha256': sha256_file(args.selection_config),
                'alphabet_sha256': sha256_file(selection.ALPHABET),
                'selector_sha256': sha256_file(Path(selection.__file__))}
    if (proposal.get('schema') != selection.SCHEMA or proposal.get('status') != 'valid' or
            proposal.get('installable') is not True or proposal['inputs'] != expected):
        raise ValueError('Нужно актуальное проверенное предложение GEN-02')
    if ([r['index'] for r in proposal['slots']] != list(range(1, 31)) or
            proposal['config'] != selection.load_config(args.selection_config)):
        raise ValueError('Слоты/конфигурация GEN-02 изменились')
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    for name, path in [('manifest', args.manifest), ('database', args.database),
                       ('dump', args.dump), ('hints', args.hints)]:
        if sha256_file(path) != document['inputs'][name]:
            raise ValueError('Изменён вход GEN-01: ' + name)
    if (document['inputs']['dump'] != manifest['source']['sha256_input'] or
            document['inputs']['words'] != manifest['sha256_words_txt'] or
            document['inputs']['database'] != manifest['definitions']['sha256']):
        raise ValueError('Входы не совпадают с манифестом')
    hints = profiles.read_hints(args.hints, profiles.hint_header(args.database, args.words), set(read_list(args.words)))
    if hints != document['payload']['hints']:
        raise ValueError('Подсказки полного пакета расходятся с профилями')


def write_outputs(directory, campaign, report):
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / 'campaign.json'
    if campaign is None:
        # A failed rerun must never leave an old candidate looking current.
        target.unlink(missing_ok=True)
    else:
        report['campaign_sha256'] = json_digest(campaign)
        atomic_json(target, campaign)
    atomic_json(directory / 'campaign_report.json', report)
    lines = ['# Генерация кампании — GEN-03', '', f"Статус: {report['status']}; проб: {report['attempts']}.", '']
    if campaign:
        lines += ['| Уровень | Исходник | Задание | Параметры | Запас / минимум |', '|---:|---|---|---|---:|']
        for row in report['tasks']:
            params = {k: v for k, v in row['task'].items() if k not in ('id', 'type', 'words')}
            lines.append(f"| {row['level']} | {row['source']} | {row['task']['type']} | {json.dumps(params, ensure_ascii=False)} | {row['candidate_count']} / {row['reserve_required']} |")
        lines += ['', 'У T4 запас измерен до назначения; после назначения все цели обязательны для одной звезды.',
                  'Первые появления перечислены в campaign.introductions: UI выбирает пояснение по TaskType из ресурсов.',
                  'Тексты статей и подготовленных подсказок в артефакты не скопированы.']
    else:
        lines += [json.dumps(report['violations'], ensure_ascii=False)]
    (directory / 'CAMPAIGN_REPORT.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def run(args):
    config = load_config(args.config)
    if args.profiles is None:
        args.profiles = Path(json.loads((ROOT / 'build/reports/level_profiles/summary.json').read_text())['cache_path'])
    document = selection.load_snapshot(args.profiles, args.words, args.previous_levels)
    proposal = json.loads(args.proposal.read_text(encoding='utf-8'))
    verify_inputs(args, document, proposal)
    if config['master_types'] != proposal['config']['master_types']:
        raise ValueError('Ротация расходится с планированием GEN-02')
    words = read_list(args.words)
    selector = selection.Selector(document['payload'], words,
        json.loads(args.previous_levels.read_text(encoding='utf-8')), proposal['config'], selection.read_alphabet())
    builder = CampaignBuilder(document['payload'], words, config)
    campaign, report = search(builder, selector, proposal)
    report.update(schema='campaign-generation-v1', config=config,
                  inputs=dict(proposal_sha256=sha256_file(args.proposal), profile_inputs=document['inputs'],
                              profile_payload_sha256=document['payload_sha256'],
                              config_sha256=sha256_file(args.config), generator_sha256=sha256_file(Path(__file__))),
                  letter_frequency=dict(rule=config['letter_frequency'], denominator=sum(builder.letter_counts.values()),
                                        counts=dict(sorted(builder.letter_counts.items()))),
                  curve_appearances={t: [dict(level=p['number'], value=task.get('n', task.get('minLength', task.get('targetCount'))))
                      for p in builder.plan for task in p['tasks'] if task['type'] == t] for t in [f'T{i}' for i in range(1, 8)]})
    write_outputs(args.output, campaign, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=CONFIG)
    parser.add_argument('--profiles', type=Path)
    parser.add_argument('--proposal', type=Path, default=ROOT / 'build/reports/level_words_proposal.json')
    parser.add_argument('--words', type=Path, default=ROOT / 'data/words.txt')
    parser.add_argument('--previous-levels', type=Path, default=ROOT / 'data/levels.json')
    parser.add_argument('--selection-config', type=Path, default=selection.CONFIG)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'data/manifest.json')
    parser.add_argument('--database', type=Path, default=ROOT / 'data/definitions.db')
    parser.add_argument('--dump', type=Path, default=ROOT / '.local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz')
    parser.add_argument('--hints', type=Path, default=ROOT / 'build/reports/level_profiles/hints.jvm.jsonl')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/reports/campaign')
    args = parser.parse_args()
    try:
        report = run(args)
        print(json.dumps({k: report[k] for k in ('status', 'installable', 'attempts', 'violations')}, ensure_ascii=False))
        if not report['installable']:
            parser.exit(2, ('В полном пуле нет исходника для обязательного задания; сохранена диагностика.\n'
                            if report['status'] == 'infeasible' else
                            'Назначения не найдены в заданном бюджете; сохранена диагностика.\n'))
    except (OSError, ValueError, KeyError, TypeError, StopIteration) as error:
        # Also invalidate old candidates on stale/missing inputs, not only exhausted search.
        write_outputs(args.output, None, dict(status='input_error', installable=False, attempts=0,
                                             violations=[dict(code='invalid_input', reason=str(error))]))
        parser.exit(2, 'Ошибка генерации кампании: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
