#!/usr/bin/env python3
"""GEN-05: bounded reallocation + mandatory fresh GEN-04 for every candidate."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace

import build_campaign as generation
from game_data_providers import atomic_json
import simulate_campaign as simulation
import validate_campaign as validation

ROOT = Path(__file__).resolve().parents[1]


def models(candidate, builder, hints, categories):
    data = {}
    for word, metadata in builder.metadata.items():
        row = hints.get(word, {})
        category = builder.category(word)
        theme = simulation.safe_theme(word, categories[category], row.get('forms', []), row.get('related', [])) if category in categories else None
        data[word] = dict(category=category, theme=theme, definition=validation.prepared_hint(word, row))
    return [simulation.LevelModel(level, builder.answers(level['word']), builder.metadata,
                                  builder.familiar, data, candidate['countMinLength']) for level in candidate['levels']]


def assignment_alternatives(candidate, row, builder, knowledge, limit):
    """Change only allowed assignments; numeric appearance curves stay untouched."""
    number = row['level']
    level = candidate['levels'][number - 1]
    local = builder.local(number, level['word'])
    harden = any(d['rule'] == 'max' for d in row['deviations'])
    used_targets = {w for l in candidate['levels'] for t in l['tasks']
                    for w in ([t['hintTarget']] if t['type'] == 'T3' else t.get('targets', []))}
    used_endings = {t['ending'] for l in candidate['levels'] for t in l['tasks'] if t['type'] == 'T5'}
    used_themes = {t['category'] for l in candidate['levels'] for t in l['tasks'] if t['type'] == 'T6'}
    options = []
    for index, task in enumerate(level['tasks']):
        kind = task['type']
        eligible = local[kind]['eligible']
        variants = []
        if kind == 'T2':
            appearances = [t for l in candidate['levels'] for t in l['tasks'] if t['type'] == 'T2']
            position = next(i for i, t in enumerate(appearances) if t['id'] == task['id'])
            neighbors = {appearances[i]['letter'] for i in (position-1, position+1) if 0 <= i < len(appearances)}
            for letter, pool in eligible.items():
                if letter != task['letter'] and letter not in neighbors:
                    variants.append((dict(task, letter=letter), sum(map(knowledge, pool))/task['n']))
        elif kind in ('T3', 'T4'):
            pool = eligible.get('length' if kind == 'T3' else 'riddles', [])
            for word in pool:
                if word in used_targets:
                    continue
                if kind == 'T3':
                    variants.append((dict(task, hintTarget=word), knowledge(word)))
                else:
                    for position, old in enumerate(task['targets']):
                        targets = task['targets'].copy()
                        targets[position] = word
                        variants.append((dict(task, targets=targets), knowledge(word) - knowledge(old)))
        elif kind == 'T5':
            for ending, pool in eligible.items():
                if ending not in used_endings:
                    variants.append((dict(task, ending=ending), sum(map(knowledge, pool))))
        elif kind == 'T6':
            for category, pool in eligible.items():
                if category not in used_themes:
                    words = [w for w in builder.answers(level['word']) if builder.category(w) == category]
                    variants.append((dict(task, category=category, words=words), sum(map(knowledge, pool))/task['n']))
        # Prioritize predicates that can change success; T3 changes hint selection only.
        for changed, proxy in variants:
            options.append((kind == 'T3', proxy if harden else -proxy, index, changed))
    options.sort(key=lambda o: (o[0], o[1], o[2], validation.digest(o[3])))
    for _, _, index, changed in options[:limit]:
        result = deepcopy(candidate)
        result['levels'][number - 1]['tasks'][index] = changed
        yield result, dict(kind='assignment', level=number, task=changed['id'], parameters=changed)


def source_alternatives(candidate, row, builder, knowledge, limit):
    number = row['level']
    stage = candidate['levels'][number-1]['stage']
    lower, upper = validation.BANDS[stage-1]
    selected = [l['word'] for l in candidate['levels']]
    harden = any(d['rule'] == 'max' for d in row['deviations'])
    options = []
    for source, profile in sorted(builder.payload['profiles'].items()):
        recognizable = profile['recognizable_answers']
        if (source in selected or not profile['source_eligible'] or not lower <= len(recognizable) <= upper
                or any(not info['eligible'] for info in builder.local(number, source).values())):
            continue
        proxy = sum(map(knowledge, recognizable)) / len(recognizable)
        options.append((proxy if harden else -proxy, source))
    for _, source in sorted(options)[:limit]:
        changed = selected.copy()
        changed[number-1] = source
        # Reallocate all global namespaces, rather than borrowing a conflicting target.
        result, report = builder.attempt(changed)
        if result is not None:
            yield result, dict(kind='source_slot', level=number, old=selected[number-1], new=source)


def score(rows):
    return sum(d['gap'] for row in rows for d in row['deviations'])


def valid(report):
    return report['installable'] and report['jvm_checked']


def search(candidate, config, evaluate, alternatives):
    """The evaluator ALWAYS runs GEN-04, including rejected balance alternatives.

    Dependency injection makes the budget, fail-closed and revalidation contracts
    testable on small synthetic fixtures without launching Gradle.
    """
    visited, trace, best = set(), [], None
    pending = (candidate, dict(kind='initial'))
    for attempt in range(config['rebalance']['max_candidates']):
        if pending is None:
            break
        current, change = pending
        visited.add(validation.digest(current))
        rows, audit = evaluate(current, attempt)
        entry = dict(attempt=attempt, change=change, candidate_sha256=validation.digest(current),
                     gen04_valid=valid(audit), balance_gap=score(rows),
                     failing_levels=[r['level'] for r in rows if r['deviations']],
                     validation_errors={k: len(audit[k]) for k in ('unreachable', 'balance_deviations', 'parity_deviations')})
        trace.append(entry)
        rank = (not valid(audit), score(rows), sum(entry['validation_errors'].values()))
        if best is None or rank < best[0]:
            best = rank, current, rows, audit, attempt
        if valid(audit) and score(rows) == 0:
            return dict(status='balanced', installable=True, campaign=current, levels=rows,
                        validation=audit, selected_attempt=attempt, attempts=trace)
        _, chosen, chosen_rows, _, _ = best
        # Alternate parameter reallocation and source-slot rebuilding.
        pending = next(((c, reason) for c, reason in alternatives(chosen, chosen_rows, attempt)
                        if validation.digest(c) not in visited), None)
    _, chosen, rows, audit, selected_attempt = best
    return dict(status='exhausted', installable=False, campaign=chosen, levels=rows, validation=audit,
                selected_attempt=selected_attempt, attempts=trace,
                exhaustion='candidate_budget' if len(trace) == config['rebalance']['max_candidates'] else 'admissible_alternatives')


def markdown(report):
    lines = ['# CAMPAIGN_REPORT — GEN-05', '',
             'Это оценки фиксированной модели, не измерения аудитории. Лимиты симуляции не ограничивают игрока.', '',
             f"Статус: **{report['status']}**, installable={report['installable']}. "
             f"Проверено кандидатов: {len(report['attempts'])}; выбран №{report['selected_attempt']}. Только полный GEN-04 разрешает проверенную копию.", '',
             'Модель, бюджеты и правило сравнения: [CAMPAIGN_SIMULATION.md](CAMPAIGN_SIMULATION.md), '
             '[конфиг](../tools/campaign_simulation_config.json).', '',
             f"Seed {report['config']['seed']}; {report['config']['runs']} прогонов на уровень/кандидат; "
             '95% интервалы Уилсона (поточечные, без поправки на множественные сравнения). '
             'Сравнение включительное по точечной оценке; интервалы показывают неопределённость, а не меняют пороги.', '',
             f"Калибровочный пул: {report['knowledge']['pool_size']} слов; среднее p={report['knowledge']['mean']:.6f}; "
             f"intercept={report['knowledge']['intercept']:.12f}; SHA-256 пула `{report['knowledge']['pool_sha256']}`.", '',
             f"Входной кандидат SHA-256 `{report['inputs']['campaign']}`; "
             f"выбранный (канонический JSON) `{validation.digest(report['campaign'])}`.", '',
             '| № | Исходник | M/R | Прогоны | P(≥R), 95% ДИ | P(=M), 95% ДИ | Цели: ≥R мин.; M мин.–макс. | Отклонения |',
             '|---|---|---|---|---|---|---|---|']
    def probability(item):
        lo, hi = item['wilson95']
        return f"{item['probability']:.1%} [{lo:.1%}; {hi:.1%}]"
    for row in report['levels']:
        target = row['targets']
        deviation = '; '.join(f"{d['metric']} {d['actual']:.1%}: {d['rule']} {d['target']:.1%}" for d in row['deviations']) or 'нет'
        lines.append(f"| {row['level']} | {row['word']} | {row['M']}/{row['R']} | {row['runs']} | "
                     f"{probability(row['p_required'])} | {probability(row['p_master'])} | "
                     f"{target['required_min']:.2%}; {target['master_min']:.2%}–{target['master_max']:.2%} | {deviation} |")
    lines += ['', '## Задания и независимо пересчитанные резервы', '',
              'Резерв T4 измеряется до назначения. Параметры и цели приведены полностью; определения в отчёт не копируются.', '',
              '| № | Тип / ID | Параметры | Резерв / минимум |', '|---|---|---|---|']
    evidence = {r['number']: r for r in report['validation']['levels']}
    for level in report['campaign']['levels']:
        by_id = {t['id']: t for t in evidence.get(level['number'], {}).get('tasks', [])}
        for task in level['tasks']:
            params = {k: v for k, v in task.items() if k not in ('id', 'type', 'words')}
            if 'words' in task:
                params['words_count'] = len(task['words'])
                params['words_sha256'] = validation.digest(task['words'])
            row = by_id.get(task['id'])
            reserve = f"{len(row['reserve'])} / {row['reserve_required']}" if row else 'не проверен'
            lines.append(f"| {level['number']} | {task['type']} / {task['id']} | {json.dumps(params, ensure_ascii=False)} | {reserve} |")
    lines += ['', '## Перебалансировка', '', '| Кандидат | Изменение | GEN-04 | Сумма отклонений | Уровни вне целей |', '|---|---|---|---|---|']
    for entry in report['attempts']:
        reason = {k: v for k, v in entry['change'].items() if k != 'parameters'}
        lines.append(f"| {entry['attempt']} | {json.dumps(reason, ensure_ascii=False)} | {entry['gen04_valid']} | "
                     f"{entry['balance_gap']:.6f} | {entry['failing_levels']} |")
    lines += ['', '## Отказ GEN-04', '']
    for key in ('unreachable', 'balance_deviations', 'parity_deviations'):
        for error in report['validation'][key]:
            lines.append(f"- {key}: уровень {error['level']}, задание {error['task']}, {error['code']}: {error['reason']}")
    lines += ['', 'При исчерпании бюджета модель знания, целевые вероятности и проверки не меняются. '
              'Кандидат не установлен в data/; release и аудит прав этим отчётом не разрешаются.', '']
    return '\n'.join(lines)


def run(args):
    config = simulation.load_config(args.config)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'balanced_campaign.json').unlink(missing_ok=True)
    # Verify the same upstream artifacts as GEN-03, including local dump SHA-256.
    if args.profiles is None:
        args.profiles = Path(json.loads((ROOT / 'build/reports/level_profiles/summary.json').read_text())['cache_path'])
    document = generation.selection.load_snapshot(args.profiles, args.words, args.previous_levels)
    proposal = json.loads(args.proposal.read_text(encoding='utf-8'))
    generation.verify_inputs(args, document, proposal)
    builder = generation.CampaignBuilder(document['payload'], args.words.read_text().splitlines(), generation.load_config())
    # Pool is exactly the global recognizable target rule, not just words occurring in selected levels.
    assessments = {(r['word'], r['role']): r['assessment']['status'] for r in document['payload']['assessments']}
    pool = {w for w in builder.words if w not in validation.BANNED and builder.metadata[w]['noun']
            and not set(builder.metadata[w]['labels']) & validation.HARD_LABELS
            and assessments.get((w, 'target')) == 'suitable'
            and builder.metadata[w]['observed_frequency']['frequency'] >= 10}
    knowledge = simulation.Knowledge(builder.metadata, pool, config['knowledge'])
    hints = validation.load_hint_inputs(args.database, builder.words)
    categories = json.loads((ROOT / 'tools/categories.json').read_text())
    candidate = json.loads(args.campaign.read_text(encoding='utf-8'))
    simulation_cache = {}

    def evaluate(current, attempt):
        directory = args.output / f'attempt-{attempt:02d}'
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / 'campaign.json'
        atomic_json(path, current)
        _, audit = validation.run(SimpleNamespace(**{**vars(args), 'campaign': path}))
        audit['status'] = 'valid' if valid(audit) else 'invalid'
        atomic_json(directory / 'validation_report.json', audit)
        rows = []
        for model in models(current, builder, hints, categories):
            key = validation.digest(model.level)
            if key not in simulation_cache:
                simulation_cache[key] = simulation.simulate(model, knowledge, config)
            rows.append(simulation_cache[key])
        atomic_json(directory / 'simulation.json', rows)
        print(json.dumps(dict(attempt=attempt, gen04_valid=valid(audit), balance_gap=score(rows),
                              failing_levels=[r['level'] for r in rows if r['deviations']]), ensure_ascii=False), flush=True)
        return rows, audit

    def alternatives(current, rows, attempt):
        failed = sorted((r for r in rows if r['deviations']), key=lambda r: (-sum(d['gap'] for d in r['deviations']), r['level']))
        # Also try source rebuilding for GEN-04 failures when balance alone passes.
        if not failed:
            failed = rows
        modes = [source_alternatives, assignment_alternatives] if attempt % 2 else [assignment_alternatives, source_alternatives]
        for mode in modes:
            for row in failed:
                limit = config['rebalance']['source_slots' if mode is source_alternatives else 'alternatives_per_level']
                yield from mode(current, row, builder, knowledge, limit)

    report = search(candidate, config, evaluate, alternatives)
    report.update(schema='campaign-simulation-report-v1', config=config,
                  knowledge=dict(intercept=knowledge.intercept, mean=knowledge.mean, pool_size=knowledge.pool_size, pool_sha256=knowledge.pool_sha256),
                  inputs={name: validation.file_hash(getattr(args, name)) for name in
                          ('campaign', 'config', 'profiles', 'words', 'database', 'manifest', 'dump', 'hints')})
    report['inputs']['processors'] = {name: validation.file_hash(Path(__file__).with_name(name)) for name in
                                     ('rebalance_campaign.py', 'simulate_campaign.py', 'validate_campaign.py', 'hint_text.py')}
    atomic_json(args.output / 'simulation_report.json', report)
    (args.output / 'CAMPAIGN_REPORT.md').write_text(markdown(report), encoding='utf-8')
    if report['installable']:
        atomic_json(args.output / 'balanced_campaign.json', report['campaign'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=simulation.CONFIG)
    parser.add_argument('--campaign', type=Path, default=ROOT / 'build/reports/campaign/campaign.json')
    parser.add_argument('--profiles', type=Path)
    parser.add_argument('--proposal', type=Path, default=ROOT / 'build/reports/level_words_proposal.json')
    parser.add_argument('--words', type=Path, default=ROOT / 'data/words.txt')
    parser.add_argument('--previous-levels', type=Path, default=ROOT / 'data/levels.json')
    parser.add_argument('--selection-config', type=Path, default=generation.selection.CONFIG)
    parser.add_argument('--database', type=Path, default=ROOT / 'data/definitions.db')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'data/manifest.json')
    parser.add_argument('--dump', type=Path, default=ROOT / '.local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz')
    parser.add_argument('--hints', type=Path, default=ROOT / 'build/reports/level_profiles/hints.jvm.jsonl')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/reports/campaign_simulation')
    parser.add_argument('--jvm-classpath')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    try:
        report = run(args)
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / 'balanced_campaign.json').unlink(missing_ok=True)
        atomic_json(args.output / 'simulation_report.json', dict(status='input_error', installable=False, reason=str(error)))
        parser.exit(2, str(error) + '\n')
    print(json.dumps({k: report[k] for k in ('status', 'installable', 'selected_attempt')}, ensure_ascii=False))
    return 0 if report['installable'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
