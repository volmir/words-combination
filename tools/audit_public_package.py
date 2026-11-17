#!/usr/bin/env python3
"""LCH-04 mechanical evidence, never a legal/publication PASS.

Checks every APK asset against its source, every runtime JSON byte against a fresh
approved-only build, all provenance rows, runtime version markers and ZIP members.
Produces hashes rather than copying dictionary prose into reports.
"""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools import lexical_json
from tools.build_owned_hints import verify, inputs, coverage
from tools.build_library_notices import generate
from tools.owned_hints import tokens


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def version_markers(raw):
    result = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for name in archive.namelist():
            if name.endswith('.version'):
                result[name] = archive.read(name).decode().strip()
            elif name.endswith('.jar'):
                result.update(version_markers(archive.read(name)))
    return result


def check_assets(archive, expected):
    """No additional assets, alternative databases, backups or compressed payloads."""
    actual = {name for name in archive.namelist() if name.startswith('assets/') and not name.endswith('/')}
    expected = {'assets/' + name: raw for name, raw in expected.items()}
    # AGP transparently uncompresses .gz assets on some versions.
    compressed = 'assets/word_sources.jsonl.gz'
    if compressed not in actual:
        expected['assets/word_sources.jsonl'] = gzip.decompress(expected.pop(compressed))
    if actual != set(expected):
        raise ValueError('Unexpected/missing APK assets: ' + repr(sorted(actual ^ set(expected))))
    for name, raw in expected.items():
        if archive.read(name) != raw:
            raise ValueError('APK/source mismatch: ' + name)


def screen_legacy(root, apk, source_files, database, expected_sha, resource_strings):
    """Screen every public source/ZIP payload for shared >=5-word prose runs.

    This is additional screening, not proof of authorship or a detector of all
    possible foreign paraphrases. Never exports old text, only matching words.
    """
    if sha(database.read_bytes()) != expected_sha:
        raise ValueError('Comparison JSON hash mismatch')
    grams = {}
    document = lexical_json.read(database)
    rows = [(r['word'],r['text']) for r in document['definitions'] if r['origin']=='wiktionary']
    for word, text in rows:
        ts = tokens(text)
        for i in range(len(ts) - 4):
            grams.setdefault(tuple(ts[i:i + 5]), set()).add(word)
    hits = []
    scanned = 0

    def scan(name, raw):
        nonlocal scanned
        scanned += 1
        found = set()
        # Russian in DEX uses UTF-8; Android resource strings may use UTF-16LE.
        for encoding, content in [('utf-8', raw), ('utf-16le', raw[:len(raw)//2*2])]:
            ts = tokens(content.decode(encoding, errors='replace'))
            for i in range(len(ts) - 4):
                found.update(grams.get(tuple(ts[i:i + 5]), ()))
        if found:
            hits.append(dict(path=name, old_words=sorted(found)))

    for row in source_files:
        path = root / row['path']
        raw = path.read_bytes()
        if path.suffix == '.gz':
            raw = gzip.decompress(raw)
        scan(row['path'], raw)
        if path.suffix == '.json':
            # Also check escaped JSON strings after decoding.
            scan(row['path'] + '#decoded-json', json.dumps(json.loads(raw), ensure_ascii=False).encode())
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith('/'):
                scan('APK:' + name, archive.read(name))
    if resource_strings:
        scan('aapt2:resource-strings', resource_strings.read_bytes())
    return dict(database_sha256=expected_sha, old_rows=len(rows), scanned_payloads=scanned,
                rule='shared consecutive >=5 Russian tokens, UTF-8 and UTF-16LE; decoded JSON; not rights proof',
                matches=hits)


def audit(root, apk, profile, runtime_inputs):
    data = root / 'data'
    minimal = verify(data)
    candidate, index, metadata, manifest = inputs(data)
    expected = {name: (data / name).read_bytes() for name in
                ('words.txt', 'word_sources.jsonl.gz', 'levels.json', 'campaign.json', 'owned_riddles.json')}
    for name in ('THIRD_PARTY_NOTICES.txt', 'Apache-2.0.txt', 'CC-BY-SA-4.0.txt',
                 'LIBRARY_NOTICES.txt', 'RUNTIME_LIBRARIES.json'):
        expected[name] = (root / 'legal' / name).read_bytes()
    expected['LICENSE'] = (root / 'LICENSE').read_bytes()
    sources = [json.loads(line) for line in gzip.decompress(expected['word_sources.jsonl.gz']).decode().splitlines()]
    words = set(expected['words.txt'].decode().splitlines())
    if len(sources) != len(words) or {r['word'] for r in sources} != words:
        raise ValueError('Provenance must cover every word exactly once')
    url_count = 0
    for row in sources:
        if set(row) != {'word', 'sources'} or not row['sources']:
            raise ValueError('Invalid provenance schema')
        for source in row['sources']:
            if (set(source) != {'title', 'line', 'source'} or
                    not source['source'].startswith('https://ru.wiktionary.org/wiki/')):
                raise ValueError('Foreign text/invalid source in word_sources')
            url_count += 1
    profiles = json.loads(runtime_inputs.read_text())
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    for name, content in generate(runtime_inputs, cache, root / 'legal').items():
        if (root / 'legal' / name).read_text() != content:
            raise ValueError('Stale notice ' + name)
    markers = {}
    native_sources = {}
    for row in profiles[profile]:
        markers.update(version_markers(Path(row['file']).read_bytes()))
        with zipfile.ZipFile(row['file']) as artifact:
            for name in artifact.namelist():
                if name.startswith('jni/') and name.endswith('.so'):
                    path = 'lib/' + name.removeprefix('jni/')
                    native_sources[(path, sha(artifact.read(name)))] = row['artifact']
    with zipfile.ZipFile(apk) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError('Duplicate ZIP member')
        for entry in archive.infolist():
            if (re.search(r'\.(?:db|sqlite[^/]*|deb)$|libsqlite', entry.filename, re.I) or
                    archive.read(entry).startswith(b'SQLite format 3\x00')):
                raise ValueError('Forbidden binary storage payload: ' + entry.filename)
        check_assets(archive, expected)
        actual_markers = {n: archive.read(n).decode().strip() for n in archive.namelist() if n.endswith('.version')}
        for name, value in actual_markers.items():
            if markers.get(name) != value:
                raise ValueError('Unknown/stale dependency version marker: ' + name)
        riddles = json.loads(archive.read('assets/owned_riddles.json'))['riddles']
        rows = [dict(word=w, text_sha256=sha(t.encode('utf8')), characters=len(t)) for w,t in sorted(riddles.items())]
        members = [dict(path=i.filename, bytes=i.file_size, sha256=sha(archive.read(i.filename)))
                   for i in sorted(archive.infolist(), key=lambda i: i.filename) if not i.is_dir()]
        for member in members:
            if member['path'].endswith('.so'):
                coordinate = native_sources.get((member['path'], member['sha256']))
                if coordinate is None:
                    raise ValueError('Unverified native library bytes: ' + member['path'])
                member['artifact'] = coordinate
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root)
    source_files = []
    for name in sorted(set(tracked.decode().strip('\0').split('\0'))):
        if (not name or name.startswith(('orchestrator/', 'docs/audits/')) or
                name == 'docs/OWN_HINTS_LICENSE_RELEASE_AUDIT.md'):
            continue
        path = root / name
        if path.is_file():
            source_files.append(dict(path=name, bytes=path.stat().st_size, sha256=sha(path.read_bytes())))
    return dict(status='MECHANICAL_CHECKS_PASS_PUBLIC_RELEASE_BLOCKED',
                head=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
                tree_note='Working tree; source_files hashes define this candidate. No clean-clone claim.',
                profile=profile, apk=dict(path=str(apk.relative_to(root)), bytes=apk.stat().st_size, sha256=sha(apk.read_bytes())),
                words=len(words), provenance_rows=len(sources), provenance_urls=url_count,
                coverage=coverage(candidate, json.loads(expected['campaign.json'])),
                riddles=dict(count=len(rows), license="CC0-1.0", sha256=sha(expected["owned_riddles.json"])),
                own_rows=rows, reproducibility=minimal, version_markers=actual_markers,
                apk_members=members, source_files=source_files,
                rights=dict(audit_passed=manifest['owned_riddles']['audit']['passed'],
                            rights_review_passed=manifest['owned_riddles']['rights_review']['passed'],
                            license=manifest['owned_riddles']['own_license'], status='BLOCKED_FOR_PUBLIC_RELEASE'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--profile', choices=('debug', 'release'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--comparison-json', type=Path)
    parser.add_argument('--resource-strings', type=Path)
    args = parser.parse_args()
    result = audit(ROOT, args.apk.resolve(), args.profile, ROOT / 'app/build/reports/licenses/runtime-inputs.json')
    if args.comparison_json:
        index = json.loads((ROOT / 'tools/owned_hint_index.json').read_text())
        result['legacy_screening'] = screen_legacy(ROOT, args.apk, result['source_files'], args.comparison_json,
                                                   sha(args.comparison_json.read_bytes()), args.resource_strings)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('status', 'apk', 'coverage', 'riddles')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
