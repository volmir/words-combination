#!/usr/bin/env python3
"""Offline graphics provenance gate. No downloads and no license inference from names."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
REVISION = '10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7'
CLASSIC = dict(ic_arrow_back='navigation/arrow_back', ic_close='navigation/close',
               ic_help='action/help', ic_lock='action/lock', ic_star_filled='toggle/star',
               ic_star_outline='toggle/star_border', ic_statistics='editor/bar_chart', ic_menu='navigation/menu')
SOURCES = {k: f'src/{v}/materialicons/24px.svg' for k, v in CLASSIC.items()}
SOURCES['ic_crown'] = 'symbols/web/crown/materialsymbolsoutlined/crown_24px.svg'
RASTER_SUFFIXES = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.avif', '.bmp', '.ico'}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def require(value, message):
    if not value:
        raise ValueError(message)


def read(root, path):
    return json.loads((root / path).read_text(encoding='utf-8'))


def svg_paths(raw):
    """Convert only the pinned sources' path/rect syntax, rejecting unreviewed SVG features."""
    paths = []
    def visit(e, fill='black'):
        require('transform' not in e.attrib, 'Unexpected upstream SVG transform')
        fill = e.get('fill', fill)
        if fill == 'none':
            return
        tag = e.tag.split('}')[-1]
        if tag == 'path':
            paths.append(e.attrib['d'])
        elif tag == 'rect':
            x, y, w, h = [int(e.get(k, '0')) for k in ('x', 'y', 'width', 'height')]
            require(not (e.get('rx') or e.get('ry')), 'Unexpected rounded source rect')
            paths.append(f'M{x} {y}H{x+w}V{y+h}H{x}Z')
        else:
            require(tag in ('svg', 'g'), 'Unreviewed SVG element: ' + tag)
        for child in e:
            visit(child, fill)
    visit(ET.fromstring(raw))
    return paths


def check(root):
    root = Path(root)
    registry = read(root, 'docs/audits/XML_ICON_PROVENANCE.json')
    require(registry['schema'] == 'graphics-provenance-v2', 'Graphics registry schema')
    rows = registry['vectors']
    require(len(rows) == 9 and {Path(r['path']).stem for r in rows} == set(SOURCES), 'Exactly nine registered vectors required')
    expected = {r['path'] for r in rows}
    actual = {p.relative_to(root).as_posix() for p in (root / 'app/src').rglob('*.xml') if ET.parse(p).getroot().tag == 'vector'}
    require(actual == expected, 'Unregistered/missing project vector')
    for row in rows:
        p = root / row['path']
        require(row['path'] == 'app/src/main/res/drawable/' + p.name, 'Unexpected vector path')
        require(row['license'] == 'Apache-2.0' and row['status'] == 'PASS', 'Vector license/status')
        require(digest(p.read_bytes()) == row['sha256'], 'Vector SHA mismatch: ' + p.name)
        source = row['source']
        require(source['revision'] == REVISION and source['path'] == SOURCES[p.stem], 'Unreviewed icon source')
        require(source['url'] == f'https://github.com/google/material-design-icons/blob/{REVISION}/' + source['path'], 'Unpinned icon URL')
        require(digest(source['svg'].encode()) == source['sha256'], 'Upstream SVG SHA mismatch')
        xml = ET.parse(p).getroot()
        require(all(xml.get(ANDROID+k) == '24' for k in ('viewportWidth', 'viewportHeight')), 'Vector viewport')
        require(all(xml.get(ANDROID+k) == '24dp' for k in ('width', 'height')), 'Vector dp size')
        paths = [n.get(ANDROID+'pathData') for n in xml.iter('path')]
        conversion = row['conversion']
        require(paths == conversion['paths'], 'Unregistered vector geometry')
        require(all(n.get(ANDROID+'fillColor') == conversion['fill_color'] and
                    n.get(ANDROID+'fillType', 'nonZero') == conversion['fill_type'] for n in xml.iter('path')), 'Vector paint')
        if p.stem == 'ic_menu':
            require(row['sha256'] == row['previous_sha256'] == '020f03900ae32fdffc8433c3552297c98e384f113770835e4fb835b2f1847497', 'Approved menu must remain unchanged')
        else:
            require(paths == svg_paths(source['svg']), 'Conversion changed upstream geometry')
        groups = list(xml.iter('group'))
        transform = conversion['group_transform']
        require(len(groups) == (1 if transform else 0), 'Unexpected vector groups')
        if transform:
            require(p.stem == 'ic_crown' and transform == dict(scaleX='0.025', scaleY='0.025', translateY='24'), 'Crown normalization')
            require(groups[0].attrib == {ANDROID+k:v for k,v in transform.items()}, 'Crown transform mismatch')
        require(p.stem != 'ic_crown' or transform is not None, 'Crown viewBox requires transform')
    raster = read(root, 'docs/audits/RASTER_GRAPHICS_PROVENANCE.json')
    approval_path = root / 'docs/RASTER_GRAPHICS_OWNER_APPROVAL.json'
    approval = json.loads(approval_path.read_text())
    require(digest(approval_path.read_bytes()) == raster['owner_approval_sha256'], 'Raster approval SHA mismatch')
    require(approval['decision'] == 'approved' and approval['license'] == 'CC0-1.0', 'BLOCKED: raster-license-owner-approval')
    images = raster['images']
    require(len(images) == 11 and len({r['path'] for r in images}) == 11, 'Exactly 11 approved project PNGs required')
    actual = {p.relative_to(root).as_posix() for p in (root / 'app/src').rglob('*') if p.is_file() and p.suffix.lower() in RASTER_SUFFIXES}
    require(actual == {r['path'] for r in images}, 'Unregistered/missing project raster')
    require(approval['images'] == [dict(path=r['path'], sha256=r['sha256']) for r in sorted(images, key=lambda r:r['path'])], 'Raster approval not bound to exact images')
    for row in images:
        raw = (root / row['path']).read_bytes()
        require(row['license'] == 'CC0-1.0' and digest(raw) == row['sha256'], 'Raster license/SHA mismatch')
        require(raw.startswith(b'\x89PNG\r\n\x1a\n') and list(struct.unpack('>II', raw[16:24])) == row['pixels'], 'PNG dimensions mismatch')
    for row in raster['excluded']:
        require(not (root / row['path']).exists(), 'Excluded raster reintroduced')
    license_row = registry['upstream_license']
    require(digest((root / license_row['local_path']).read_bytes()) == license_row['sha256'], 'Apache license changed')
    notices = (root / 'legal/THIRD_PARTY_NOTICES.txt').read_text()
    for marker in ('Google Material Icons', 'Google Material Symbols', 'Copyright Google LLC', REVISION,
                   'CC0-1.0', 'Викисловар', 'FrequencyWords', 'CC BY-SA 4.0', 'MIT'):
        require(marker in notices, 'Missing graphics/data notice: ' + marker)
    for row in rows:
        require(Path(row['path']).stem in notices, 'Missing icon notice')
    return registry, raster


def png_payload(flat):
    start = flat.find(b'\x89PNG\r\n\x1a\n')
    require(start >= 0, 'No PNG in compiled resource')
    pos = start + 8
    while pos + 12 <= len(flat):
        size = struct.unpack_from('>I', flat, pos)[0]
        kind = flat[pos+4:pos+8]
        pos += size + 12
        require(pos <= len(flat), 'Truncated compiled PNG')
        if kind == b'IEND':
            return flat[start:pos]
    raise ValueError('No IEND in compiled PNG')


def png_provenance(root, inputs, aapt2):
    """Reproduce AAPT2 PNG processing offline; verify bytes even after R8 resource renaming."""
    by_hash = {}
    with tempfile.TemporaryDirectory(prefix='graphics-png-') as temp:
        directory = Path(temp)
        originals = {}
        def add(name, raw, record):
            require('..' not in Path(name).parts and '\\' not in name, 'Unsafe resource path')
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            require(not path.exists() or path.read_bytes() == raw, 'Conflicting raster source: ' + name)
            path.write_bytes(raw)
            originals[name] = record
            by_hash.setdefault(digest(raw), []).append(record | dict(verification='exact_source_bytes'))
        for entry in inputs:
            with zipfile.ZipFile(entry['file']) as archive:
                for name in archive.namelist():
                    if name.startswith('res/') and name.endswith('.png'):
                        raw = archive.read(name)
                        add(name, raw, dict(artifact=entry['artifact'], source=name, source_sha256=digest(raw)))
        for path in (root / 'app/src/main/res').rglob('*.png'):
            raw = path.read_bytes()
            add('res/' + path.relative_to(root / 'app/src/main/res').as_posix(), raw,
                dict(artifact='project', source=path.relative_to(root).as_posix(), source_sha256=digest(raw)))
        compiled = directory / 'compiled.zip'
        subprocess.run([str(aapt2), 'compile', '--dir', str(directory/'res'), '-o', str(compiled)], check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        with zipfile.ZipFile(compiled) as archive:
            for name in archive.namelist():
                if not name.endswith('.png.flat'):
                    continue
                matches = [r for path,r in originals.items() if name == path.split('/')[1]+'_'+Path(path).name+'.flat']
                require(matches, 'Unknown compiled PNG source: ' + name)
                by_hash.setdefault(digest(png_payload(archive.read(name))), []).extend(
                    r | dict(verification='recompiled_aapt2_png_bytes') for r in matches)
    return by_hash


def check_apk(root, apk, profile, aapt2):
    registry, raster = check(root)
    dump = subprocess.check_output([str(aapt2), 'dump', 'resources', str(apk)], text=True)
    resources = {}
    current = None
    for line in dump.splitlines():
        match = re.search(r'\bresource 0x[0-9a-f]+ (\S+)', line)
        if match:
            current = match[1]
        match = re.search(r'\(file\) (\S+) type=', line)
        if match and current:
            resources.setdefault(current, []).append(match[1])
    files = []
    with zipfile.ZipFile(apk) as archive:
        for name in ('THIRD_PARTY_NOTICES.txt', 'Apache-2.0.txt', 'LIBRARY_NOTICES.txt', 'RUNTIME_LIBRARIES.json'):
            require(archive.read('assets/'+name) == (root / 'legal' / name).read_bytes(), 'APK notice mismatch: ' + name)
        for row in registry['vectors']:
            paths = resources.get(row['resource'], [])
            require(paths or profile not in row['profiles'], 'Required APK vector missing: ' + row['resource'])
            for path in paths:
                tree = subprocess.check_output([str(aapt2), 'dump', 'xmltree', str(apk), '--file', path], text=True)
                data = re.findall(r':pathData\([^)]*\)="([^"]+)"', tree)
                require(data == row['conversion']['paths'], 'Packaged vector geometry differs: ' + path)
                colors = re.findall(r':fillColor\([^)]*\)=(#[0-9a-fA-F]+)', tree)
                require(colors == [row['conversion']['fill_color'].lower()] * len(data), 'Packaged vector color differs')
                if row['conversion']['group_transform']:
                    for key, value in row['conversion']['group_transform'].items():
                        match = re.search(':'+key+r'\([^)]*\)=([-0-9.]+)', tree)
                        require(match and float(match[1]) == float(value), 'Packaged crown transform differs')
        expected_project = set()
        for row in raster['images']:
            paths = resources.get(row['resource'], [])
            if row['density'] == 'default':
                require(len(paths) == 1, 'Adaptive launcher art missing/ambiguous')
            else:
                require(len(paths) >= 6, 'Launcher density/adaptive variants missing')
            expected_project.update(p for p in paths if p.endswith('.png'))
        for excluded in raster['excluded']:
            require('drawable/'+Path(excluded['path']).stem not in resources, 'Excluded JPG in APK resource table')
        # Enumerate all packaged raster bytes, including AndroidX library resources.
        inputs = read(root, 'app/build/reports/licenses/runtime-inputs.json')[profile]
        png_sources = png_provenance(root, inputs, aapt2)
        inventory = {r['artifact']:r for r in read(root, 'legal/RUNTIME_LIBRARIES.json')}
        reverse = {path:resource for resource, paths in resources.items() for path in paths}
        for name in archive.namelist():
            if Path(name).suffix.lower() not in RASTER_SUFFIXES:
                continue
            raw = archive.read(name)
            row = dict(path=name, bytes=len(raw), sha256=digest(raw), resource=reverse.get(name),
                       pixels=list(struct.unpack('>II', raw[16:24])) if raw.startswith(b'\x89PNG') else None)
            if name in expected_project:
                candidates = [r for r in png_sources.get(row['sha256'], []) if r['artifact']=='project']
                require(candidates, 'Packaged project raster differs from approved source: ' + name)
                row.update(origin='project launcher PNG', license='CC0-1.0', source_candidates=candidates)
            else:
                candidates = [r for r in png_sources.get(row['sha256'], []) if r['artifact']!='project']
                require(candidates, 'Unattributed packaged raster: ' + name)
                require(all(c['artifact'] in inventory and all(l['spdx']=='Apache-2.0' for l in inventory[c['artifact']]['licenses']) for c in candidates), 'Unreviewed library image license')
                row.update(origin='resolved AndroidX AAR resource; AAPT2 lossless processing', license='Apache-2.0', source_candidates=candidates)
            files.append(row)
        require(len(expected_project) == 11, 'Exactly 11 project PNGs expected in APK')
    return dict(apk=str(apk), sha256=digest(Path(apk).read_bytes()), profile=profile, raster_files=files,
                vectors='PASS', notices='PASS', excluded_jpg='ABSENT', raster_provenance='PASS')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--profile', choices=('debug', 'release'))
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    check(args.root)
    if args.apk:
        require(args.profile and args.aapt2, '--apk requires --profile and --aapt2')
        result = check_apk(args.root, args.apk, args.profile, args.aapt2)
        if args.output:
            args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
        print(json.dumps({k:v for k,v in result.items() if k!='raster_files'}))
    else:
        print('GRAPHICS_CHECK=PASS (9 Apache-2.0 vectors, 11 owner-approved CC0-1.0 PNGs, excluded JPG absent)')


if __name__ == '__main__':
    main()
