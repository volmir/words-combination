#!/usr/bin/env python3
"""Deterministic offline runtime subset of the owner's 39 approved CC0 texts."""
import argparse
from collections import Counter
import json
import re
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
sys.path.insert(0, str(ROOT))
from tools.check_text_storage import check as check_text_storage
from tools.owned_hints import validate, sha
from tools.owned_hints_rights import owner_decision, publication_fields, exact, read_json
from build_game_data import publish
from game_data_providers import atomic_json

INDEX = ROOT / 'tools/owned_hint_index.json'
CANDIDATE = ROOT / 'docs/owned_hints.json'
CONTRACT = ROOT / 'tools/owned_riddles_contract.json'
EXPECTED_COVERAGE = dict(unique_targets=39, assignments=40, T3=9, T4=11, T6=20)
ASSET = 'owned_riddles.json'


def coverage(candidate, campaign):
    roles = Counter(r['type'] for e in candidate['entries'] for r in e['roles'])
    actual = dict(unique_targets=len(candidate['entries']), assignments=sum(roles.values()), **roles)
    t4 = [t for level in campaign['levels'] for t in level['tasks'] if t['type'] == 'T4']
    if (actual != EXPECTED_COVERAGE or len(t4) != 6 or
            sum(len(t['targets']) for t in t4) != 11):
        raise ValueError('LCH-02 whitelist requires 39 targets / 40 roles and exactly 11 covered T4')
    return actual


def inputs(data, candidate_path=None, index_path=None):
    candidate_path, index_path = candidate_path or CANDIDATE, index_path or INDEX
    candidate = read_json(candidate_path)
    index = read_json(index_path)
    manifest = json.loads((data / 'manifest.json').read_text())
    campaign = json.loads((data / 'campaign.json').read_text())
    words = set((data / 'words.txt').read_text().splitlines())
    if set(index) != {'schema', 'words_sha256', 'source_database_sha256', 'source_dump_sha256',
                      'license', 'source', 'candidate_sha256', 'screening', 'word_metadata'}:
        raise ValueError('Unexpected lexical index fields; no hidden prose permitted')
    if (manifest.get('sha256_words_txt') != sha(data / 'words.txt') or manifest.get('words') != len(words) or
            manifest.get('campaign_source', {}).get('sha256') != sha(data / 'campaign.json') or
            index['schema'] != 'owned-hint-category-index-v1' or index['license'] != 'CC-BY-SA-4.0' or
            index['words_sha256'] != sha(data / 'words.txt') or
            index['source_dump_sha256'] != manifest['source']['sha256_input'] or
            index['candidate_sha256'] != sha(candidate_path) or index['screening']['suspicious_matches']):
        raise ValueError('Stale/invalid lexical index or candidate screening')
    compact = index['word_metadata']
    taxonomy = json.loads((ROOT / 'tools/categories.json').read_text())
    for word, info in compact.items():
        if (word not in words or set(info) != {'category', 'category_source', 'forms', 'related'} or
                info['category'] not in taxonomy or
                not re.fullmatch(r'(?:sense\.categories:[а-я]+|leading_label:[а-я]+|gloss_head:[a-z_]+|'
                                 r'unknown:[a-z_]+|ai:[a-z0-9_:/\.\-#]+|owner:SHORT15-05:2026-11-16)'
                                 r'(?:;(?:sense\.categories:[а-я]+|leading_label:[а-я]+|gloss_head:[a-z_]+))*',
                                 info['category_source']) or
                not all(isinstance(v, str) and v == v.lower().replace('ё', 'е') and v.isalpha()
                        for v in info['forms'] + info['related']) or
                (info['category'] != 'other' and info['category_source'].startswith('unknown:'))):
            raise ValueError('Invalid category/lexical metadata: ' + word)
    metadata = {word: compact.get(word, dict(category='other', category_source='unknown:no_thematic_evidence',
                                            forms=[], related=[])) for word in sorted(words)}
    validate(candidate, campaign, sha(data / 'campaign.json'), words, metadata)
    coverage(candidate, campaign)
    for level in campaign['levels']:
        for task in level['tasks']:
            if task['type'] == 'T6':
                pool = {w for w in words if w != level['word'] and not (Counter(w) - Counter(level['word']))
                        and metadata[w]['category'] == task['category']}
                if pool != set(task['words']):
                    raise ValueError('T6 pool differs from full category')
    return candidate, index, metadata, manifest



def approved_inputs(data, candidate_path=None, index_path=None):
    candidate_path, index_path = candidate_path or CANDIDATE, index_path or INDEX
    candidate, index, metadata, manifest = inputs(data, candidate_path, index_path)
    contract = read_json(CONTRACT)
    if (contract['schema'] != 'owned-riddles-source-contract-v1' or
            sha(candidate_path) != contract['candidate_sha256'] or sha(index_path) != contract['index_sha256']):
        raise ValueError('Authored source/index differ from approved contract')
    decision = owner_decision(candidate_path, data / 'campaign.json')
    rights = publication_fields(candidate_path, data / 'campaign.json', decision)
    if (rights['publication_status'] != 'approved' or
            rights['rights_review']['approval_sha256'] != contract['approval_sha256'] or
            rights['audit']['report_sha256'] != contract['audit_sha256']):
        raise ValueError('Owner approval and full human audit are required; no waiver')
    keys = sorted({w for l in read_json(data / 'campaign.json')['levels']
                   for t in l['tasks'] if t['type'] == 'T4' for w in t['targets']})
    if keys != contract['t4_keys'] or len(keys) != 11:
        raise ValueError('Exactly 11 approved T4 targets are required')
    payload = dict(schema='owned-riddles', version=1, license='CC0-1.0',
                   riddles={e['word']: e['hint'] for e in candidate['entries'] if e['word'] in keys})
    return payload, manifest, rights


def encoded(payload):
    return (json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def riddles_block(data, output, candidate_path, index_path, rights):
    return dict(schema='owned-riddles', version=1, sha256=sha(output), count=11, tasks=6,
                license='CC0-1.0', provenance='newly-authored-for-game',
                campaign_sha256=sha(data / 'campaign.json'), dictionary_words_sha256=sha(data / 'words.txt'),
                candidate_sha256=sha(candidate_path), category_index_sha256=sha(index_path), **rights)


def generate(data, output, candidate_path=None, index_path=None):
    payload, _, _ = approved_inputs(data, candidate_path, index_path)
    output.write_bytes(encoded(payload))
    return payload


def verify(data, candidate_path=None, index_path=None):
    candidate_path, index_path = candidate_path or CANDIDATE, index_path or INDEX
    payload, manifest, rights = approved_inputs(data, candidate_path, index_path)
    output = data / ASSET
    if not output.is_file():
        raise ValueError('Missing required owned_riddles.json')
    actual = read_json(output)
    if not exact(actual, payload) or output.read_bytes() != encoded(payload):
        raise ValueError('Riddles differ from exact deterministic approved subset')
    block = riddles_block(data, output, candidate_path, index_path, rights)
    if 'definitions' in manifest or not exact(manifest.get('owned_riddles'), block):
        raise ValueError('Riddles manifest differs from verified evidence/state')
    return dict(own=11, wiktionary=0, t4=11, tasks=6, sha256=sha(output), publication_status='approved')


def install(data, candidate_path=None, index_path=None):
    candidate_path, index_path = candidate_path or CANDIDATE, index_path or INDEX
    payload, manifest, rights = approved_inputs(data, candidate_path, index_path)
    with tempfile.TemporaryDirectory() as temp:
        staging = Path(temp)
        output = staging / ASSET
        output.write_bytes(encoded(payload))
        manifest.pop('definitions', None)
        manifest['owned_riddles'] = riddles_block(data, output, candidate_path, index_path, rights)
        for name in ('words.txt', 'campaign.json'):
            shutil.copyfile(data / name, staging / name)
        atomic_json(staging / 'manifest.json', manifest)
        verify(staging, candidate_path, index_path)
        publish(staging, data, (ASSET, 'manifest.json'))
    return verify(data, candidate_path, index_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=ROOT / 'data')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    try:
        check_text_storage(ROOT)
        print(json.dumps(verify(args.data) if args.check else install(args.data), ensure_ascii=False))
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, str(error) + '\n')


if __name__ == '__main__':
    main()
