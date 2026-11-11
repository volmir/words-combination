#!/usr/bin/env python3
"""GEN-04: independent dictionary recount and JVM parity; never installs into data/."""
import argparse
from collections import Counter
import gzip
import hashlib
from itertools import combinations
import json
from pathlib import Path
import shlex
import sqlite3
import subprocess
import tempfile

from hint_text import prepare

ROOT = Path(__file__).resolve().parents[1]
ALPHABET = 'абвгдежзийклмнопрстуфхцчшщъыьэюя'
BANNED = {'прелюбодейка', 'брудершафт', 'праймериз'}
PREFERRED = {'хлопкопункт', 'реввоенсовет', 'селитроварня'}
HARD_LABELS = {'offensive', 'proper_name', 'productive_diminutive'}
PREFERRED_LABELS = {'rare', 'historical', 'obsolete', 'restricted_usage', 'abbreviation', 'specialist'}
CURVES = {'T1': (6, 22), 'T2': (3, 7), 'T3': (5, 9), 'T5': (5, 8), 'T6': (3, 5), 'T7': (3, 5)}
BANDS = [(40, 300), (70, 380), (100, 460), (130, 540), (160, 620), (250, 700)]


def normalize(word):
    return ''.join(c for c in word.lower().replace('ё', 'е') if c in ALPHABET)


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                      separators=(',', ':')).encode()).hexdigest()


def file_hash(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def answers_for(source, dictionary):
    """Counter subtraction, independent of GEN-01 index and GEN-03 predicates."""
    source = normalize(source)
    counts = Counter(source)
    return {w for raw in dictionary if 3 <= len(w := normalize(raw)) <= 30
            and w != source and not (Counter(w) - counts)}


def prepared_hint(word, row):
    return next((text for definition in row.get('definitions', [])
                 if (text := prepare(word, definition, row.get('forms', []), row.get('related', []))) is not None), None)


def task_answers(task, answers, category):
    kind = task['type']
    if kind == 'T1':
        return {w for w in answers if len(w) >= 4}
    if kind == 'T2':
        return {w for w in answers if len(w) >= 4 and w.startswith(task['letter'])}
    if kind == 'T3':
        return {w for w in answers if len(w) >= task['minLength']}
    if kind == 'T4':
        return answers & set(map(normalize, task['targets']))
    if kind == 'T5':
        return {w for w in answers if len(w) >= task['minLength'] and w.endswith(task['ending'])}
    if kind == 'T6':
        return {w for w in answers if category(w) == task['category']}
    if kind == 'T7':
        return {w for w in answers if task['letter'] in w}
    raise ValueError('Unknown task type: ' + str(kind))


def issue(report, bucket, code, reason, level=None, task=None):
    report[bucket].append(dict(code=code, level=level, task=task, reason=reason))
    report['installable'] = False


def validate(campaign, dictionary, payload, hints, previous, production=True, endings=None, categories=None):
    """Only lexical observations/assessments are evidence; profile pools are ignored."""
    report = dict(schema='campaign-validation-v1', installable=True, unreachable=[],
                  balance_deviations=[], parity_deviations=[], levels=[], r_combinations_checked=0, jvm_checked=False)
    fields = {'T1': {'n': int}, 'T2': {'n': int, 'letter': str},
              'T3': {'minLength': int, 'hintTarget': str}, 'T4': {'targets': list},
              'T5': {'minLength': int, 'ending': str},
              'T6': {'category': str, 'n': int, 'words': list}, 'T7': {'n': int, 'letter': str}}
    for level in campaign['levels']:
        for task in level['tasks']:
            key, kind = task.get('id', '<missing>'), task.get('type', '<missing>')
            required = {'id': str, 'type': str, **fields.get(kind, {})}
            malformed = [name for name, expected in required.items() if type(task.get(name)) is not expected]
            malformed += [name for name in ('targets', 'words') if name in task
                          and (not isinstance(task[name], list) or any(type(w) is not str for w in task[name]))]
            if malformed:
                issue(report, 'unreachable', 'task_schema', 'Missing/invalid fields: ' + ', '.join(malformed),
                      level.get('number'), str(key))
    if not report['installable']:
        return report
    metadata = payload['words']
    assessments = {(row['word'], row['role']): row['assessment']['status'] for row in payload['assessments']}

    def suitable(word, role):
        m = metadata.get(word, {})
        labels = set(m.get('labels', []))
        bypass = role == 'source' and word in PREFERRED and labels <= PREFERRED_LABELS
        return (word not in BANNED and m.get('noun', False) and not labels & HARD_LABELS
                and assessments.get((word, role)) == 'suitable'
                and (bypass or m.get('observed_frequency', {}).get('frequency', 0) >= 10))

    def category(word):
        m = metadata.get(word, {})
        key = m.get('category')
        return key if key and key != 'other' and not m.get('category_source', 'unknown:').startswith('unknown:') else None

    words = {normalize(w) for w in dictionary}
    familiar = {w for w in words if suitable(w, 'target')}
    useful = {w for w in familiar if prepared_hint(w, hints.get(w, {})) is not None}
    frequencies = Counter(''.join(sorted(words)))
    levels = campaign['levels']
    bad = lambda code, reason, n=None, t=None: issue(report, 'balance_deviations', code, reason, n, t)
    impossible = lambda code, reason, n=None, t=None: issue(report, 'unreachable', code, reason, n, t)
    if campaign.get('version') != 2 or campaign.get('countMinLength') != 4:
        bad('version', 'Expected version=2 and countMinLength=4')
    expected_stages = [dict(number=n, firstLevel=n * 5 - 4, lastLevel=n * 5) for n in range(1, 7)]
    if production and (len(levels) != 30 or sum(len(l['tasks']) for l in levels) != 100
                       or campaign['stages'] != expected_stages):
        bad('campaign_size', 'Expected 30 levels, 6 five-level stages, 100 tasks/stars')
    if not levels:
        impossible('empty_campaign', 'No path to finale')
    sources = [normalize(l['word']) for l in levels]
    retained = len(set(sources) & {normalize(w) for w in previous})
    report['retained_sources'] = retained
    if production and not 8 <= retained <= 15:
        bad('retained_sources', f'Expected 8..15 old sources, got {retained} (owner amendment)')
    missing = sorted(set(ALPHABET) - set(''.join(sources)))
    report['missing_letters'] = missing
    if production and missing:
        bad('alphabet', 'Missing letters: ' + ''.join(missing))
    if len(set(sources)) != len(sources):
        bad('unique_source', 'Source words repeat after normalization')
    totals = Counter(t['type'] for l in levels for t in l['tasks'])
    seen = Counter()
    targets, used_endings, used_categories, ids = set(), set(), set(), set()
    previous_initial = None
    master_rows, recognizable_sets = [], []
    for position, level in enumerate(levels, 1):
        n, source, tasks = level['number'], level['word'], level['tasks']
        stage = (position - 1) // 5 + 1
        r = level['requiredStars']
        if n != position:
            bad('level_number', f'Expected ordered level {position}', n)
        if source != normalize(source) or not 8 <= len(source) <= 13 or not suitable(source, 'source'):
            bad('source', 'Noncanonical, prohibited or unsuitable source (expected length 8..13)', n)
        if not tasks or len(tasks) > 5 or type(r) is not int or not 1 <= r <= len(tasks):
            impossible('threshold', 'M must be 1..5, R must be 1..M', n)
        if production:
            expected_r = 4 if n == 30 else [1, 1, 2, 2, 3, 3][min(stage, 6) - 1]
            if level['stage'] != stage or len(tasks) != min(stage, 5) or r != expected_r:
                bad('schedule', f'Expected stage={stage} M={min(stage, 5)} R={expected_r}', n)
        kinds = [t['type'] for t in tasks]
        if len(set(kinds)) != len(kinds):
            bad('task_types', 'Types must be different within a level', n)
        if production and stage < 6 and set(kinds) != {f'T{i}' for i in range(1, stage + 1)}:
            bad('task_types', 'Unexpected types for stage', n)
        if stage == 6:
            master_rows.append(set(kinds))
        answers = answers_for(source, words)
        safe = answers & familiar
        recognizable_sets.append(safe)
        if production and not BANDS[min(stage, 6) - 1][0] <= len(safe) <= BANDS[min(stage, 6) - 1][1]:
            bad('answer_band', f'Recognizable={len(safe)}, expected {BANDS[min(stage, 6) - 1]}', n)
        row = dict(number=n, answers=sorted(answers), recognizable_count=len(safe), tasks=[])
        report['levels'].append(row)
        for task in tasks:
            kind, key = task['type'], task['id']
            def fail(code, reason):
                bad(code, reason, n, key)
            if not isinstance(key, str) or not key.strip() or key != key.strip() or key in ids:
                fail('task_id', 'Expected globally unique nonblank ID')
            ids.add(key)
            if kind not in {f'T{i}' for i in range(1, 8)}:
                fail('task_type', 'Unknown type: ' + str(kind))
                continue
            needed = task.get('n', len(task.get('targets', [])) if kind == 'T4' else 1)
            param = task.get('minLength' if kind in ('T3', 'T5') else 'n')
            if kind != 'T4':
                start, end = CURVES[kind]
                expected = start + (end - start) * seen[kind] // max(1, totals[kind] - 1)
                if production and param != expected:
                    fail('parameter_curve', f'Expected appearance {seen[kind] + 1}: {expected}, got {param}')
                if type(param) is not int or param <= 0 or (kind in ('T3', 'T5') and not 3 <= param <= len(source)):
                    fail('parameter_bounds', 'Invalid n/minLength')
                    continue
            seen[kind] += 1
            if type(needed) is not int or needed <= 0:
                fail('parameter_bounds', 'Task must require at least one answer')
                continue
            if kind == 'T4' and production and len(task['targets']) != {4: 1, 5: 2, 6: 3}.get(stage):
                fail('riddle_count', 'Expected 1/2/3 riddle targets in stages 4/5/6')
            if kind in ('T2', 'T7'):
                letter = task['letter']
                if len(letter) != 1 or letter not in ALPHABET or letter not in source:
                    fail('letter', 'Expected canonical letter present in source')
                if kind == 'T2':
                    if letter == previous_initial:
                        fail('initial_rotation', 'Consecutive T2 appearances repeat initial')
                    previous_initial = letter
            assigned = [task['hintTarget']] if kind == 'T3' else task['targets'] if kind == 'T4' else []
            for target in assigned:
                normalized = normalize(target)
                if normalized in targets:
                    fail('unique_target', 'T3/T4 target repeats after normalization: ' + target)
                targets.add(normalized)
                if target != normalized or target not in answers or target not in familiar or (
                        kind == 'T3' and len(target) < task['minLength']):
                    fail('assigned_target', 'Not a canonical, distinct, buildable, recognizable target: ' + target)
                if kind == 'T4' and target not in useful:
                    fail('riddle_hint', 'No useful safely masked definition: ' + target)
            if kind == 'T5':
                ending = task['ending']
                if ending in used_endings:
                    fail('unique_ending', 'Ending repeats: ' + ending)
                used_endings.add(ending)
                if ending != normalize(ending) or not 2 <= len(ending) <= 4 or (endings is not None and ending not in endings):
                    fail('ending', 'Expected meaningful canonical ending from configured inventory')
            if kind == 'T6':
                theme = task['category']
                if theme in used_categories:
                    fail('unique_category', 'Category repeats: ' + theme)
                used_categories.add(theme)
                if (not theme or theme == 'other' or (categories is not None and theme not in categories)
                        or not any(category(w) == theme for w in words)):
                    fail('category', 'Unknown/non-thematic category: ' + theme)
                expected_words = {w for w in answers if category(w) == theme}
                if set(task['words']) != expected_words or len(task['words']) != len(set(task['words'])):
                    fail('category_membership', 'T6 words must equal complete independently recounted local set')
                global_count = sum(category(w) == theme for w in familiar)
                if production and global_count < 40:
                    fail('category_global_reserve', f'Global recognizable category={global_count}, need 40')
            accepted = task_answers(task, answers, category)
            # T4 reserve is measured BEFORE assigning fixed targets, not just targets themselves.
            pool = (safe & useful) if kind == 'T4' else safe & accepted
            numerator, denominator = (3, 1) if n <= 10 else (5, 2) if n <= 20 else (2, 1)
            minimum = (needed * numerator + denominator - 1) // denominator
            if len(accepted) < needed:
                impossible('unreachable_task', f'Only {len(accepted)} legal answers; need {needed}', n, key)
            if len(pool) < minimum:
                fail('recognizable_reserve', f'Recognizable reserve={len(pool)}, need {minimum}')
            if kind == 'T7':
                choices = [ch for ch in set(source) if sum(ch in w for w in safe) >= minimum]
                rarest = min(choices, key=lambda ch: (frequencies[ch], ch), default=None)
                if task['letter'] != rarest:
                    fail('rare_letter', f'Expected rarest feasible letter={rarest}, got {task["letter"]}')
            row['tasks'].append(dict(id=key, accepted=sorted(accepted), needed=needed,
                                     reserve=sorted(pool), reserve_required=minimum,
                                     witness=sorted(accepted)[:needed]))
        # Monotone predicates: union of individual witnesses completes ANY chosen R;
        # simultaneous completions are allowed, so exact equality to R is not required.
        if type(r) is int and 1 <= r <= len(row['tasks']):
            for chosen in combinations(row['tasks'], r):
                found = set().union(*(set(t['witness']) for t in chosen))
                report['r_combinations_checked'] += 1
                for t in chosen:
                    if len(found & set(t['accepted'])) < t['needed']:
                        impossible('r_chain', 'R subset cannot unlock next level/finale', n, t['id'])
    if production and (len(master_rows) != 5 or len({tuple(sorted(s)) for s in master_rows}) < 2
                       or not {'T6', 'T7'} <= set().union(set(), *master_rows)):
        bad('master_rotation', 'Need five master levels with rotation including T6 and T7')
    collisions = [dict(left=i + 1, right=j + 1, intersection=len(a & b), union=len(a | b),
                       jaccard=len(a & b) / len(a | b) if a | b else 0.0)
                  for i, a in enumerate(recognizable_sets) for j, b in enumerate(recognizable_sets) if i < j]
    mean = sum(p['jaccard'] for p in collisions) / max(1, len(collisions))
    maximum = max((p['jaccard'] for p in collisions), default=0.0)
    report['jaccard'] = dict(basis='independently recounted recognizable answer sets',
                             mean=mean, maximum=maximum, pairs=collisions)
    if production and (mean > 0.03 or maximum > 0.15):
        bad('jaccard', f'Mean={mean:.8f} (max .03), maximum={maximum:.8f} (max .15)')
    report['preferred_sources'] = sorted(set(sources) & PREFERRED)
    return report


def compare_jvm(report, jvm, hint_inputs):
    def mismatch(code, reason, n=None, task=None):
        issue(report, 'parity_deviations', code, reason, n, task)
    for error in jvm.get('errors', []):
        mismatch('jvm_contract', error['reason'], error.get('level'), error.get('task'))
    report['jvm'] = {key: jvm.get(key) for key in ('r_combinations_checked', 'all_answer_stars', 'finale_reached', 'runtime')}
    if (jvm.get('r_combinations_checked') != report['r_combinations_checked'] or
            jvm.get('all_answer_stars') != sum(len(l['tasks']) for l in report['levels']) or
            jvm.get('finale_reached') is not True):
        mismatch('jvm_chain', 'JVM did not confirm every R subset, full M and finale')
    if jvm.get('alphabet') != ALPHABET:
        mismatch('jvm_alphabet', 'Python/JVM alphabets differ')
    rows = {l['number']: l for l in jvm['levels']}
    if set(rows) != {l['number'] for l in report['levels']}:
        mismatch('jvm_levels', 'Python/JVM level sets differ')
    for level in report['levels']:
        other = rows.get(level['number'], {})
        if level['answers'] != other.get('answers'):
            mismatch('jvm_answers', 'WordIndex/Python legal answer sets differ', level['number'])
        tasks = {t['id']: t for t in other.get('tasks', [])}
        if set(tasks) != {t['id'] for t in level['tasks']}:
            mismatch('jvm_tasks', 'Python/JVM task sets differ', level['number'])
        for task in level['tasks']:
            if task['accepted'] != tasks.get(task['id'], {}).get('accepted'):
                mismatch('jvm_task_answers', 'TaskRules/Python accepted sets differ', level['number'], task['id'])
            runtime = tasks.get(task['id'], {})
            if (runtime.get('target') != task['needed'] or
                    runtime.get('singleton_progress') != dict.fromkeys(task['accepted'], 1)):
                mismatch('jvm_progress', 'Python/JVM target or per-answer progress differs', level['number'], task['id'])
    for word, row in hint_inputs.items():
        if word not in jvm['hints'] or prepared_hint(word, row) != jvm['hints'].get(word):
            # Preserve the task address for every assigned T4; reserve-only words use no task.
            addresses = [(l['number'], t['id']) for l in report['levels'] for t in l['tasks']
                         if word in t.get('riddle_comparison_words', t.get('riddle_targets', []))]
            for n, key in addresses or [(None, None)]:
                mismatch('jvm_hint', 'DefinitionHint/Python text or suitability differs: ' + word, n, key)
    report['jvm_checked'] = True


def load_hint_inputs(database, words):
    """Read-only DB; full texts exist only in temporary JVM input, never in reports."""
    result = {w: dict(forms=[w], related=[], definitions=[]) for w in words}
    with sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True) as db:
        for word, forms, related in db.execute('SELECT word,forms,related FROM word_metadata'):
            if word in result:
                result[word].update(forms=json.loads(forms), related=json.loads(related))
        for word, text in db.execute('SELECT word,text FROM definitions ORDER BY word,ord,id'):
            if word in result and word not in BANNED:
                result[word]['definitions'].append(text)
    return result


def run(args):
    candidate = json.loads(args.campaign.read_text(encoding='utf-8'))
    profile_path = args.profiles or Path(json.loads((ROOT / 'build/reports/level_profiles/summary.json').read_text())['cache_path'])
    with gzip.open(profile_path, 'rt', encoding='utf-8') as stream:
        document = json.load(stream)
    if document['payload_sha256'] != digest(document['payload']):
        raise ValueError('Corrupted lexical snapshot')
    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    actual = dict(words=file_hash(args.words), database=file_hash(args.database),
                  levels=file_hash(args.previous_levels), manifest=file_hash(args.manifest))
    if any(document['inputs'].get(k) != v for k, v in actual.items()):
        raise ValueError('Stale lexical snapshot: words/database/old levels/manifest hash mismatch')
    if actual['words'] != manifest['sha256_words_txt'] or actual['database'] != manifest['definitions']['sha256']:
        raise ValueError('Dictionary/database disagree with manifest')
    # The lexical snapshot stores observations, not an independent semantic accuracy audit.
    words = args.words.read_text(encoding='utf-8').splitlines()
    if len(words) != len(set(words)) or any(w != normalize(w) or not 3 <= len(w) <= 30 for w in words):
        raise ValueError('Noncanonical/duplicate dictionary entries')
    previous = [l['word'] for l in json.loads(args.previous_levels.read_text(encoding='utf-8'))]
    hints = load_hint_inputs(args.database, words)
    endings = json.loads((ROOT / 'tools/campaign_config.json').read_text(encoding='utf-8'))['endings']
    categories = json.loads((ROOT / 'tools/categories.json').read_text(encoding='utf-8'))
    report = validate(candidate, words, document['payload'], hints, previous, endings=endings, categories=categories)
    report['inputs'] = dict(**actual, candidate=file_hash(args.campaign), lexical_snapshot=file_hash(profile_path),
                           validator=file_hash(Path(__file__)))
    report['inputs']['core_processors'] = {name: file_hash(ROOT / 'core/src/main/kotlin/com/example/sostavslova/core' / name)
        for name in ('Alphabet.kt', 'WordIndex.kt', 'GameState.kt', 'TaskRules.kt', 'CampaignRules.kt',
                     'CampaignValidator.kt', 'DefinitionHint.kt', 'HintText.kt', 'HintRules.kt', 'HintLadder.kt')}
    report['inputs']['jvm_audit'] = file_hash(ROOT / 'core/src/test/kotlin/com/example/sostavslova/core/CampaignDatasetAudit.kt')
    # Compare every possible T4 reserve definition, not only the lucky assigned targets.
    relevant = set()
    for level, evidence in zip(candidate['levels'], report['levels']):
        for task, row in zip(level['tasks'], evidence['tasks']):
            if task['type'] == 'T4':
                row['riddle_targets'] = task['targets']
                row['riddle_comparison_words'] = evidence['answers']
                relevant.update(evidence['answers'])
    hint_inputs = {w: hints[w] for w in sorted(relevant)}
    with tempfile.TemporaryDirectory(prefix='campaign-audit-') as tmp:
        bundle, output = Path(tmp) / 'input.json', Path(tmp) / 'output.json'
        bundle.write_text(json.dumps(dict(campaign=candidate, dictionary=words, production=True,
                                         hintInputs=hint_inputs), ensure_ascii=False), encoding='utf-8')
        command = ([args.java, '-cp', args.jvm_classpath,
                    'com.example.sostavslova.core.CampaignDatasetAudit', str(bundle), str(output)]
                   if args.jvm_classpath else
                   ['bash', str(ROOT / 'gradlew'), '--offline', ':core:auditCampaign', '--console=plain',
                    '--args=' + shlex.join([str(bundle), str(output)])])
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
        if not output.exists():
            issue(report, 'parity_deviations', 'jvm_execution',
                  'JVM audit failed/unavailable: ' + (result.stdout + result.stderr)[-4000:])
        else:
            compare_jvm(report, json.loads(output.read_text(encoding='utf-8')), hint_inputs)
            if result.returncode:
                issue(report, 'parity_deviations', 'jvm_execution', 'JVM returned nonzero exit code')
    return candidate, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--campaign', type=Path, default=ROOT / 'build/reports/campaign/campaign.json')
    parser.add_argument('--profiles', type=Path)
    parser.add_argument('--words', type=Path, default=ROOT / 'data/words.txt')
    parser.add_argument('--previous-levels', type=Path, default=ROOT / 'data/levels.json')
    parser.add_argument('--database', type=Path, default=ROOT / 'data/definitions.db')
    parser.add_argument('--manifest', type=Path, default=ROOT / 'data/manifest.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/reports/campaign_validation')
    parser.add_argument('--jvm-classpath', help='Optional freshly compiled core+audit runtime classpath; JVM check remains mandatory')
    parser.add_argument('--java', default='java', help='Java executable for --jvm-classpath')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / 'validated_campaign.json'
    # Remove old success BEFORE parsing or starting JVM. A failed rerun is fail-closed.
    target.unlink(missing_ok=True)
    try:
        candidate, report = run(args)
    except (OSError, ValueError, KeyError, TypeError, IndexError, sqlite3.Error) as error:
        candidate = None
        report = dict(schema='campaign-validation-v1', installable=False, jvm_checked=False,
                      unreachable=[dict(code='input_error', level=None, task=None, reason=str(error))],
                      balance_deviations=[], parity_deviations=[])
    report['status'] = 'valid' if report['installable'] and report['jvm_checked'] else 'invalid'
    report['installable'] = report['status'] == 'valid'
    (args.output / 'validation_report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for name in ('unreachable', 'balance_deviations', 'parity_deviations'):
        (args.output / (name + '.json')).write_text(json.dumps(report[name], ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if report['installable']:
        # This is a checked COPY in build/, not an application installation.
        target.write_text(json.dumps(candidate, ensure_ascii=False, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('status', 'installable', 'jvm_checked', 'unreachable',
                                           'balance_deviations', 'parity_deviations')}, ensure_ascii=False))
    return 0 if report['installable'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
