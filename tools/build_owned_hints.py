#!/usr/bin/env python3
"""LCH-03: reproducible minimal descriptions; never imports dictionary prose."""
import argparse
from collections import Counter
import json
from pathlib import Path
import sqlite3
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
sys.path.insert(0, str(ROOT))
from tools.owned_hints import validate, load_metadata, sha, compare_imported
from build_game_data import build_database, publish
from game_data_providers import atomic_json

POLICY = 'owned-minimal-hints-v1'
INDEX = ROOT / 'tools/owned_hint_index.json'
CANDIDATE = ROOT / 'docs/owned_hints.json'
VERSION = 10


def extract_index(data, candidate_path, output, dump):
    """One-time text-free snapshot. Imported prose is screened, never exported."""
    manifest = json.loads((data / 'manifest.json').read_text())
    if sha(dump) != manifest['source']['sha256_input']:
        raise ValueError('Dump SHA mismatch')
    candidate = json.loads(candidate_path.read_text())
    with sqlite3.connect(data / 'definitions.db') as db:
        metadata = load_metadata(db)
        validate(candidate, json.loads((data / 'campaign.json').read_text()), sha(data / 'campaign.json'),
                 set((data / 'words.txt').read_text().splitlines()), metadata)
        comparison = compare_imported(candidate['entries'], db.execute('SELECT word,text FROM definitions'))
    if comparison['suspicious_matches']:
        raise ValueError('Suspected imported prose')
    own_words = {e['word'] for e in candidate['entries']}
    # Only category keys/provenance and observed lexical forms for masking 39 texts.
    # No glosses, labels copied from prose, examples, or other free text fields.
    compact = {word: dict(category=info['category'], category_source=info['category_source'],
                          forms=info['forms'] if word in own_words else [],
                          related=info['related'] if word in own_words else [])
               for word, info in sorted(metadata.items())
               if info['category'] != 'other' or word in own_words}
    atomic_json(output, dict(schema='owned-hint-category-index-v1', words_sha256=manifest['sha256_words_txt'],
                            source_database_sha256=sha(data / 'definitions.db'),
                            source_dump_sha256=sha(dump), license='CC-BY-SA-4.0',
                            source='word_metadata:categories-and-observed-forms-only',
                            candidate_sha256=sha(candidate_path), screening=comparison, word_metadata=compact))


def inputs(data, candidate_path=CANDIDATE, index_path=INDEX):
    candidate = json.loads(candidate_path.read_text())
    index = json.loads(index_path.read_text())
    manifest = json.loads((data / 'manifest.json').read_text())
    campaign = json.loads((data / 'campaign.json').read_text())
    words = set((data / 'words.txt').read_text().splitlines())
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
                not all(isinstance(v, str) and v == v.lower().replace('ё', 'е') and v.isalpha()
                        for v in info['forms'] + info['related']) or
                (info['category'] != 'other' and info['category_source'].startswith('unknown:'))):
            raise ValueError('Invalid category/lexical metadata: ' + word)
    metadata = {word: compact.get(word, dict(category='other', category_source='unknown:no_thematic_evidence',
                                            forms=[], related=[])) for word in sorted(words)}
    validate(candidate, campaign, sha(data / 'campaign.json'), words, metadata)
    for level in campaign['levels']:
        for task in level['tasks']:
            if task['type'] == 'T6':
                pool = {w for w in words if w != level['word'] and not (Counter(w) - Counter(level['word']))
                        and metadata[w]['category'] == task['category']}
                if pool != set(task['words']):
                    raise ValueError('T6 pool differs from full category')
    return candidate, index, metadata, manifest


def generate(data, output, candidate_path=CANDIDATE, index_path=INDEX):
    candidate, index, metadata, manifest = inputs(data, candidate_path, index_path)
    rows = [dict(word=e['word'], display=e['word'], text=e['hint'], source='', origin='own',
                 category=metadata[e['word']]['category'], guessable=1) for e in candidate['entries']]
    build_database(output, rows, metadata, dict(rights_policy=POLICY,
                   own_license='<ЛИЦЕНЗИЯ — решает владелец>',
                   dictionary_words_sha256=sha(data / 'words.txt'), candidate_sha256=sha(candidate_path),
                   category_index_sha256=sha(index_path)), VERSION)
    return candidate, index, metadata, manifest


def verify(data, candidate_path=CANDIDATE, index_path=INDEX):
    candidate, index, metadata, manifest = inputs(data, candidate_path, index_path)
    block = manifest.get('definitions', {})
    if (block.get('rights_policy') != POLICY or block.get('sha256') != sha(data / 'definitions.db') or
            block.get('user_version') != VERSION or
            block.get('generator') != dict(provider='local-owned-hints', candidate_sha256=sha(candidate_path),
                                           category_index_sha256=sha(index_path)) or
            block.get('dictionary_words_sha256') != sha(data / 'words.txt') or
            block.get('counts') != dict(own=len(candidate['entries']), wiktionary=0) or
            block.get('coverage') != dict(words=len(candidate['entries']), total=len(metadata))):
        raise ValueError('Minimal descriptions manifest mismatch')
    # Byte equality with a fresh rebuild covers ALL columns/tables, including
    # notes/examples, SQLite free pages and word_metadata. No hidden old prose.
    with tempfile.TemporaryDirectory() as temp:
        expected = Path(temp) / 'definitions.db'
        generate(data, expected, candidate_path, index_path)
        if sha(expected) != block['sha256']:
            raise ValueError('Database differs from reproducible owned-only build')
    return dict(own=len(candidate['entries']), wiktionary=0, t4=sum(r['type'] == 'T4' for e in candidate['entries'] for r in e['roles']),
                category_index_sha256=sha(index_path))


def install(data, candidate_path=CANDIDATE, index_path=INDEX):
    # Read and validate every input before replacing installed files.
    with tempfile.TemporaryDirectory() as temp:
        output = Path(temp) / 'definitions.db'
        candidate, index, metadata, manifest = generate(data, output, candidate_path, index_path)
        manifest['definitions'] = dict(rights_policy=POLICY, sha256=sha(output), user_version=VERSION,
            counts=dict(own=len(candidate['entries']), wiktionary=0),
            coverage=dict(words=len(candidate['entries']), total=len(metadata)),
            dictionary_words_sha256=sha(data / 'words.txt'),
            generator=dict(provider='local-owned-hints', candidate_sha256=sha(candidate_path),
                           category_index_sha256=sha(index_path)),
            audit=dict(passed=False), rights_review=dict(passed=False, status='pending-owner-approval',
                                                         replacement_required=False),
            own_license='<ЛИЦЕНЗИЯ — решает владелец>')
        staging = Path(temp)
        for name in ('words.txt', 'campaign.json'):
            shutil.copyfile(data / name, staging / name)
        atomic_json(staging / 'manifest.json', manifest)
        verify(staging, candidate_path, index_path)
        publish(staging, data, ('definitions.db', 'manifest.json'))
    return verify(data, candidate_path, index_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=ROOT / 'data')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--extract-index', action='store_true')
    parser.add_argument('--dump', type=Path)
    args = parser.parse_args()
    if args.extract_index:
        if args.dump is None:
            parser.error('--extract-index requires --dump')
        extract_index(args.data, CANDIDATE, INDEX, args.dump)
        return
    print(json.dumps(verify(args.data) if args.check else install(args.data), ensure_ascii=False))


if __name__ == '__main__':
    main()
