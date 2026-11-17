"""Text-only preparation data. Never packaged as a runtime asset.

The campaign generation tools may inspect extracted senses and metadata in
this explicit JSON schema; the app ships only the separate approved riddles.
"""
import json
from pathlib import Path


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def read(path):
    value = json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique)
    if (set(value) != {'schema', 'version', 'revision', 'definitions', 'word_metadata', 'build_metadata'} or
            value['schema'] != 'lexical-preparation' or type(value['version']) is not int or value['version'] != 1 or
            type(value['revision']) is not int or value['revision'] < 1 or
            not isinstance(value['definitions'], list) or not isinstance(value['word_metadata'], dict) or
            not isinstance(value['build_metadata'], dict)):
        raise ValueError('Invalid lexical preparation JSON')
    return value


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n', encoding='utf8')


def build(path, rows, categories, metadata, revision):
    from collections import Counter
    order = Counter()
    definitions = []
    for row in rows:
        order[row['word']] += 1
        definitions.append(dict(id=len(definitions)+1, ord=order[row['word']], notes='', antonym='', example='', **row))
    word_metadata = {}
    for word, info in sorted(categories.items()):
        info = info if isinstance(info, dict) else dict(category=info)
        word_metadata[word] = dict(category=info['category'], category_source=info.get('category_source', 'unknown:legacy_input'),
                                   forms=sorted(set(info.get('forms', []))), related=sorted(set(info.get('related', []))))
    write(path, dict(schema='lexical-preparation', version=1, revision=revision, definitions=definitions,
                     word_metadata=word_metadata, build_metadata={k:str(v) for k,v in metadata.items()}))


def review_data(path):
    """Runtime riddles + independent category provenance, for editorial tools."""
    path = Path(path)
    payload = json.loads(path.read_text(encoding='utf8'), object_pairs_hook=unique)
    if payload.get('schema') == 'lexical-preparation':
        return read(path)
    if (payload.get('schema') != 'owned-riddles' or type(payload.get('version')) is not int or
            payload['version'] != 1 or payload.get('license') != 'CC0-1.0' or not payload.get('riddles')):
        raise ValueError('Invalid riddle JSON')
    index_path = path.parent.parent / 'tools/owned_hint_index.json'
    metadata = json.loads(index_path.read_text(encoding='utf8'), object_pairs_hook=unique)['word_metadata']
    rows = [dict(id=i+1, word=word, ord=1, text=text, display=word, source='', origin='own', category=metadata.get(word, {}).get('category','other'), guessable=1)
            for i, (word,text) in enumerate(sorted(payload['riddles'].items()))]
    return dict(definitions=rows, word_metadata=metadata)
