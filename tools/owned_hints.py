"""Offline LCH-02 candidate validation; never installs data or prints imported text.

Authored input lives in docs/owned_hints.json, outside generated data/. The old
Historical screening remains in the immutable lexical provenance index.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import unicodedata

from tools.hint_text import prepare
from tools.install_short15_package import verify_installed

ROOT = Path(__file__).resolve().parents[1]


def norm(value):
    return unicodedata.normalize('NFC', value).lower().replace('ё', 'е').replace('\u0301', '').replace('\u0300', '')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def targets(campaign):
    """All designated semantic targets; no arbitrary truncation of T6.words."""
    result = defaultdict(list)
    for level in campaign['levels']:
        for task in level['tasks']:
            kind = task['type']
            field = {'T3': 'hintTarget', 'T4': 'targets', 'T6': 'words'}.get(kind)
            if not field:
                continue
            words = [task[field]] if kind == 'T3' else task[field]
            if len(set(map(norm, words))) != len(words):
                raise ValueError('Duplicate target in ' + task['id'])
            for word in words:
                result[norm(word)].append(dict(level=level['number'], task_id=task['id'],
                                              type=kind, selection_source='campaign.' + field))
    return dict(sorted(result.items()))


def validate(candidate, campaign, campaign_sha, words, metadata):
    def require(condition, message):
        if not condition:
            raise ValueError(message)
    require(candidate['schema'] == 'owned-game-hints' and candidate['version'] == 1, 'schema/version')
    require(set(candidate) == {'schema', 'version', 'campaign_sha256', 'status', 'provenance',
                               'license_review', 'proposed_license', 'authoring_inputs', 'entries'},
            'Unexpected candidate fields')
    require(candidate['authoring_inputs'] == ['word', 'part_of_speech=noun', 'task_type',
                                             'general-linguistic-knowledge'], 'Authoring inputs')
    require(candidate['campaign_sha256'] == campaign_sha, 'Stale campaign SHA')
    # This immutable authoring snapshot stays pending even after approval.
    # Rights are validated separately against its byte SHA, never edited here.
    require(candidate['status'] == 'candidate-not-installed', 'Candidate status')
    require(candidate['proposed_license'] == 'CC0-1.0', 'Proposed license')
    require(candidate['provenance'] == 'newly-authored-for-game', 'Candidate provenance')
    require(candidate['license_review'] == 'pending-owner-approval', 'Candidate license review')
    expected = targets(campaign)
    entries = candidate['entries']
    keys = [e['word'] for e in entries]
    require(keys == sorted(set(keys)), 'Duplicate or unsorted words')
    require(set(keys) == set(expected), 'Missing or extra semantic target')
    levels = {level['number']: level for level in campaign['levels']}
    for entry in entries:
        require(set(entry) == {'word', 'normalized', 'part_of_speech', 'hint', 'roles', 'provenance',
                              'license_review', 'review_note', 'owner_text_approval', 'owner_license_approval'},
                'Unexpected entry fields')
        word, hint = entry['word'], entry['hint']
        require(word == norm(word) == entry['normalized'], 'Non-normalized word')
        require(word in words and len(word) >= 3, 'Word not in index: ' + word)
        require(entry['part_of_speech'] == 'noun', 'Expected noun')
        require(entry['roles'] == expected[word], 'Roles differ: ' + word)
        require(entry['review_note'].strip(), 'Missing review note')
        for key, value in [('provenance', 'newly-authored-for-game'),
                           ('license_review', 'pending-owner-approval'),
                           ('owner_text_approval', 'pending'), ('owner_license_approval', 'pending')]:
            require(entry[key] == value, key + ': ' + word)
        require(15 <= len(hint) <= 140 and hint == hint.strip(), 'Empty/long hint: ' + word)
        require(not re.search(r'https?://|www\.|[<>]|\*\*\*|TODO|TBD|заглушк', hint, re.I), 'Unwanted text')
        forms, related = metadata.get(word, {}).get('forms', []), metadata.get(word, {}).get('related', [])
        require(prepare(word, hint, forms, related) == hint, 'Hint masked/rejected: ' + word)
        # Explicit non-prefix families and constituents of compounds missed by the heuristic.
        roots = {'километр': ['кило', 'метр'], 'антитело': ['антител', 'тельц'],
                 'бортник': ['борт'], 'радист': ['радио'], 'дантист': ['дант'],
                 'акционер': ['акци'], 'адресок': ['адрес']}.get(word, [])
        require(not any(root in norm(hint) for root in roots), 'Related root: ' + word)
        for role in entry['roles']:
            level = levels[role['level']]
            task = next(t for t in level['tasks'] if t['id'] == role['task_id'])
            require(word != norm(level['word']) and not (Counter(word) - Counter(norm(level['word']))),
                    'Not buildable: ' + word)
            if role['type'] == 'T3':
                require(len(word) >= task['minLength'], 'T3 length')
            if role['type'] == 'T6':
                require(metadata[word]['category'] == task['category'], 'T6 category: ' + word)
                require(metadata[word]['category_source'] and not metadata[word]['category_source'].startswith('unknown:'),
                        'Unverifiable category: ' + word)
    require(len({norm(e['hint']) for e in entries}) == len(entries), 'Duplicate hints')
    return expected


def tokens(text):
    return re.findall('[а-я]+', norm(text))


def compare_imported(entries, old_rows):
    """Detect verbatim copying across ALL imported senses, never return their text.

    Flag exact normalized equality, a full short hint (>=3 tokens) embedded in
    an old sense, or any shared run of >=5 tokens. Generic shorter overlaps
    are not proof of copying; this is a screening rule, not a rights audit.
    """
    index = defaultdict(set)
    full = defaultdict(set)
    short = defaultdict(set)
    count = 0
    for old_word, old_text in old_rows:
        count += 1
        ts = tokens(old_text)
        full[tuple(ts)].add(old_word)
        for size, dest in [(5, index), (3, short), (4, short)]:
            for i in range(len(ts) - size + 1):
                dest[tuple(ts[i:i + size])].add(old_word)
    matches = []
    for entry in entries:
        ts = tokens(entry['hint'])
        hits = set(full.get(tuple(ts), ()))
        if 3 <= len(ts) <= 4:
            hits.update(short.get(tuple(ts), ()))
        for i in range(len(ts) - 4):
            hits.update(index.get(tuple(ts[i:i + 5]), ()))
        if hits:
            matches.append({'word': entry['word'], 'matched_old_words': sorted(hits)})
    return {'old_rows_checked': count, 'threshold': 'exact/full-short-3-4-tokens/or-consecutive-5-tokens',
            'suspicious_matches': matches}


def check(root=ROOT, write_docs=False):
    from tools.build_owned_hints import verify, inputs
    root = Path(root)
    result = verify(root / 'data', root / 'docs/owned_hints.json', root / 'tools/owned_hint_index.json')
    candidate, index, _, _ = inputs(root / 'data', root / 'docs/owned_hints.json', root / 'tools/owned_hint_index.json')
    return dict(unique_targets=len(candidate['entries']), assignments=sum(len(e['roles']) for e in candidate['entries']),
                t4=result['t4'], campaign_sha256=sha(root / 'data/campaign.json'), **index['screening'])


if __name__ == '__main__':
    print(json.dumps(check(), ensure_ascii=False))
