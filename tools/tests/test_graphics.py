"""Synthetic provenance mutations: reject false licenses, geometry and unregistered raster payloads."""
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from tools.verify_graphics import check, SOURCES, REVISION, svg_paths

MENU = '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M3,6h18v2H3zM3,11h18v2H3zM3,16h18v2H3z" />
</vector>
'''


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


class GraphicsGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.registry = dict(schema='graphics-provenance-v2', vectors=[], upstream_license=dict(local_path='legal/Apache-2.0.txt', sha256=sha(b'synthetic license')))
        self.write('legal/Apache-2.0.txt', b'synthetic license')
        for i, (name, source) in enumerate(SOURCES.items()):
            d = f'M{i} 0L12 12L0 12Z'
            transform = dict(scaleX='0.025', scaleY='0.025', translateY='24') if name == 'ic_crown' else None
            group = ' '.join(f'android:{k}="{v}"' for k,v in transform.items()) if transform else ''
            body = f'<path android:fillColor="#FF000000" android:pathData="{d}" />'
            if transform:
                body = '<group '+group+'>'+body+'</group>'
            xml = f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">{body}</vector>'
            if name == 'ic_menu':
                xml = MENU
                d = 'M3,6h18v2H3zM3,11h18v2H3zM3,16h18v2H3z'
            raw = xml.encode()
            svg = f'<svg xmlns="http://www.w3.org/2000/svg"><path d="{d}"/></svg>'
            path = 'app/src/main/res/drawable/'+name+'.xml'
            self.write(path, raw)
            self.registry['vectors'].append(dict(path=path, sha256=sha(raw), previous_sha256=sha(raw), license='Apache-2.0', status='PASS', source=dict(revision=REVISION, path=source, url=f'https://github.com/google/material-design-icons/blob/{REVISION}/'+source, svg=svg, sha256=sha(svg.encode())), conversion=dict(paths=[d], fill_color='#FFFFFFFF' if name=='ic_menu' else '#FF000000', fill_type='nonZero', group_transform=transform)))
        self.images = []
        for i in range(11):
            raw = b'\x89PNG\r\n\x1a\n'+b'\0'*8+struct.pack('>II',1,1)+bytes([i])
            path = f'app/src/main/res/drawable/synthetic_{i}.png'
            self.write(path, raw)
            self.images.append(dict(path=path, sha256=sha(raw), pixels=[1,1], license='CC0-1.0'))
        self.approval = dict(decision='approved', license='CC0-1.0', images=[dict(path=r['path'],sha256=r['sha256']) for r in sorted(self.images,key=lambda r:r['path'])])
        self.raster = dict(images=self.images, excluded=[dict(path='app/src/main/res/drawable/old.jpg')])
        notices = 'Google Material Icons Google Material Symbols Copyright Google LLC '+REVISION+' CC0-1.0 Викисловар FrequencyWords CC BY-SA 4.0 MIT '+' '.join(SOURCES)
        self.write('legal/THIRD_PARTY_NOTICES.txt', notices.encode())
        self.save()

    def write(self, name, raw):
        path = self.root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)

    def save(self):
        raw = (json.dumps(self.approval)+'\n').encode()
        self.write('docs/RASTER_GRAPHICS_OWNER_APPROVAL.json', raw)
        self.raster['owner_approval_sha256'] = sha(raw)
        for path, data in [('docs/audits/XML_ICON_PROVENANCE.json',self.registry),('docs/audits/RASTER_GRAPHICS_PROVENANCE.json',self.raster)]:
            self.write(path, (json.dumps(data)+'\n').encode())

    def test_valid_offline_package(self):
        check(self.root)

    def test_changing_local_geometry_and_its_declared_hash_does_not_forge_source(self):
        row = self.registry['vectors'][0]
        path = self.root/row['path']
        raw = path.read_bytes().replace(b'L12 12',b'L11 12')
        path.write_bytes(raw)
        row['sha256'] = sha(raw)
        row['conversion']['paths'] = [row['conversion']['paths'][0].replace('L12 12','L11 12')]
        self.save()
        with self.assertRaisesRegex(ValueError, 'upstream geometry'):
            check(self.root)

    def test_raster_approval_cannot_be_replaced_by_pending_or_wrong_image(self):
        self.approval['decision'] = 'pending'
        self.save()
        with self.assertRaisesRegex(ValueError, 'raster-license-owner-approval'):
            check(self.root)
        self.approval['decision'] = 'approved'
        self.approval['images'][0]['sha256'] = '0'*64
        self.save()
        with self.assertRaisesRegex(ValueError, 'exact images'):
            check(self.root)

    def test_unregistered_raster_fails(self):
        self.write('app/src/main/res/drawable/unreviewed.webp',b'synthetic')
        with self.assertRaisesRegex(ValueError, 'Unregistered'):
            check(self.root)

    def test_crown_transform_cannot_be_dropped(self):
        row = next(r for r in self.registry['vectors'] if r['path'].endswith('ic_crown.xml'))
        row['conversion']['group_transform'] = None
        self.save()
        with self.assertRaisesRegex(ValueError, 'groups'):
            check(self.root)

    def test_removed_jpg_cannot_reappear(self):
        self.write('app/src/main/res/drawable/old.jpg', b'synthetic')
        with self.assertRaisesRegex(ValueError, 'Unregistered'):
            check(self.root)

    def test_menu_cannot_be_changed_even_with_updated_local_hash(self):
        row = next(r for r in self.registry['vectors'] if r['path'].endswith('ic_menu.xml'))
        path = self.root/row['path']
        raw = path.read_bytes().replace(b'#FFFFFFFF', b'#FF000000')
        path.write_bytes(raw)
        row['sha256'] = sha(raw)
        row['conversion']['fill_color'] = '#FF000000'
        self.save()
        with self.assertRaisesRegex(ValueError, 'menu must remain unchanged'):
            check(self.root)

    def test_invisible_bounds_and_rect_conversion_preserve_source(self):
        raw = '<svg xmlns="http://www.w3.org/2000/svg"><path fill="none" d="M0 0H24V24H0Z"/><rect x="4" y="9" width="4" height="11"/></svg>'
        self.assertEqual(['M4 9H8V20H4Z'], svg_paths(raw))
