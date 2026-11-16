#!/usr/bin/env python3
"""SHORT15-05: apply the owner's exact editorial changes in staging only.

The dictionary and definitions must already have been regenerated from the pinned
local sources. This tool never installs data and never edits definition texts.
"""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import sqlite3
import shutil

import campaign_profiles
import word_categories
from build_dictionary import sha256_file
from game_data_providers import atomic_json

PROFESSIONS = 'артист артистка банкир бариста батрак радист акробат актриса ботаник дантист диктор санитар танкист каратист бортник канонир таксатор диакон бонна кантор'.split()
ENDING_WORDS = 'акмеист аметист атомист дантист дуалист каноист окулист танкист медалист меланист мелодист методист моделист уклонист дуатлонист колумнист талмудист анекдотист'.split()


def campaign_candidate(baseline):
    candidate = deepcopy(baseline)
    profile = campaign_profiles.get('short15-release-v1')
    expected = json.loads(Path(__file__).with_name('short15_candidate.json').read_text())['words']
    if [level['word'] for level in candidate['levels']] != expected:
        raise ValueError('Unexpected baseline sources/order')
    for level in candidate['levels']:
        number = level['number']
        for task in level['tasks']:
            kind = task['type']
            if kind == 'T1':
                task['n'] = profile['t1_minimum'][number - 1]
            elif kind == 'T2':
                task['n'] = campaign_profiles.t2_minimum(profile, number)
            elif number == 14 and kind == 'T6':
                task.update(n=4, category='professions', words=sorted(PROFESSIONS))
            elif number == 15:
                if kind == 'T3':
                    task.update(minLength=8, hintTarget='антитело')
                elif kind == 'T5':
                    task.update(minLength=7, ending='ист')
                elif kind == 'T7':
                    task.update(n=8, letter='у')
    return candidate


def reviewed_categories(index, baseline_db):
    result = deepcopy(index)
    # Keep existing independently sourced classifications for unchanged lexemes.
    # Forms/relatives always come from the newly extracted source index.
    with sqlite3.connect(Path(baseline_db).resolve().as_uri() + '?mode=ro', uri=True) as db:
        for word, category, source in db.execute('SELECT word,category,category_source FROM word_metadata'):
            if word in result['word_metadata']:
                result['word_metadata'][word].update(category=category, category_source=source)
    if not set(PROFESSIONS) <= result['word_metadata'].keys():
        raise ValueError('Missing approved profession noun')
    for word in PROFESSIONS:
        result['word_metadata'][word].update(category='professions', category_source='owner:SHORT15-05:2026-11-16')
    rebuilt = word_categories.category_index(result['word_metadata'], word_categories.load_taxonomy())
    result['categories'] = rebuilt['categories']
    result['classification_features'] = {
        w: features for w, features in result.get('classification_features', {}).items()
        if result['word_metadata'][w]['category'] == 'other'
    }
    return result


def preserve_history(staging, baseline):
    manifest = json.loads((staging / 'manifest.json').read_text())
    old = json.loads((baseline / 'manifest.json').read_text())
    # Historical v1 levels are a byte-for-byte comparison input, not a campaign.
    shutil.copyfile(baseline / 'levels.json', staging / 'levels.json')
    manifest['sha256_levels_json'] = sha256_file(staging / 'levels.json')
    manifest['levels_source'] = old['levels_source']
    for key in ('source_candidates', 'source_only'):
        manifest['selection'][key] = old['selection'][key]
    atomic_json(staging / 'manifest.json', manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staging', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--campaign', type=Path)
    parser.add_argument('--category-index', type=Path)
    parser.add_argument('--baseline-database', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.staging:
        if not args.baseline:
            parser.error('--baseline is required')
        preserve_history(args.staging, args.baseline)
        return
    if bool(args.campaign) == bool(args.category_index):
        parser.error('Choose --campaign or --category-index')
    if args.campaign:
        result = campaign_candidate(json.loads(args.campaign.read_text()))
    else:
        if not args.baseline_database:
            parser.error('--baseline-database is required for categories')
        result = reviewed_categories(json.loads(args.category_index.read_text()), args.baseline_database)
    atomic_json(args.output, result)
    print(json.dumps({'output': str(args.output), 'sha256': sha256_file(args.output)}))


if __name__ == '__main__':
    main()
