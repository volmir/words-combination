"""Archive attacks use only synthetic payloads, never external dictionary prose."""
import gzip
from tools import lexical_json
import hashlib
import io
from pathlib import Path
import re
import tempfile
import unittest
import warnings
import zipfile
import zlib

from tools.audit_public_package import check_assets, version_markers, screen_legacy


class PublicPackageAuditTest(unittest.TestCase):
    def test_release_application_id_is_owner_approved_and_namespace_is_preserved(self):
        root = Path(__file__).resolve().parents[2]
        gradle = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
        default_config = re.search(r'defaultConfig\s*\{([^}]+)\}', gradle)
        self.assertIsNotNone(default_config)
        self.assertEqual(['io.github.volmir.wordscombination'],
                         re.findall(r'^\s*applicationId\s*=\s*"([^"]+)"',
                                    default_config.group(1), re.MULTILINE))
        self.assertEqual(['com.example.sostavslova'],
                         re.findall(r'^\s*namespace\s*=\s*"([^"]+)"', gradle, re.MULTILINE))

    def archive(self, assets):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as archive:
            for name, value in assets.items():
                archive.writestr(name, value)
        return zipfile.ZipFile(io.BytesIO(raw.getvalue()))

    @staticmethod
    def art_profile(header, payload=b'synthetic ART profile data', dex_count_size=1):
        packed = zlib.compress(payload)
        return (header + (1).to_bytes(dex_count_size, 'little') +
                len(payload).to_bytes(4, 'little') + len(packed).to_bytes(4, 'little') + packed)

    def test_release_accepts_only_valid_compiled_art_profiles(self):
        expected = {'owned_riddles.json': b'approved test JSON'}
        files = {
            'assets/owned_riddles.json': expected['owned_riddles.json'],
            'assets/dexopt/baseline.prof': self.art_profile(b'pro\x00010\x00'),
            'assets/dexopt/baseline.profm': self.art_profile(b'prm\x00002\x00', dex_count_size=2),
        }
        with self.archive(files) as archive:
            check_assets(archive, expected, profile='release')
            with self.assertRaisesRegex(ValueError, 'Unexpected/missing'):
                check_assets(archive, expected, profile='debug')

    def test_release_still_rejects_hidden_assets_and_misnamed_profiles(self):
        expected = {'owned_riddles.json': b'approved test JSON'}
        original = {'assets/owned_riddles.json': expected['owned_riddles.json'],
                    'assets/dexopt/baseline.prof': self.art_profile(b'pro\x00010\x00')}
        for name in ('assets/dexopt/baseline.prof.bak', 'assets/dexopt/other.prof',
                     'assets/dexopt/deep/baseline.prof', 'assets/hidden.json'):
            with self.subTest(name=name), self.archive({**original, name: b'not an ART profile'}) as archive:
                with self.assertRaisesRegex(ValueError, 'Unexpected/missing'):
                    check_assets(archive, expected, profile='release')

    def test_release_rejects_duplicate_asset_entries(self):
        expected = {'owned_riddles.json': b'approved test JSON'}
        raw = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(raw, 'w') as zipped:
                zipped.writestr('assets/owned_riddles.json', expected['owned_riddles.json'])
                zipped.writestr('assets/owned_riddles.json', b'synthetic replacement')
        with zipfile.ZipFile(io.BytesIO(raw.getvalue())) as archive:
            with self.assertRaisesRegex(ValueError, 'Duplicate APK asset'):
                check_assets(archive, expected, profile='release')

    def test_release_rejects_malformed_or_oversized_art_profiles(self):
        expected = {'owned_riddles.json': b'approved test JSON'}
        valid = self.art_profile(b'pro\x00010\x00')
        bad = [
            b'arbitrary bytes masquerading as a profile',
            self.art_profile(b'pro\x00015\x00'),  # unsupported on-disk asset version
            valid[:12],
            valid + b'extra data',
            valid[:-1] + b'X',
            b'pro\x00010\x00' + b'\x00' * 1_500_000,
        ]
        for number, payload in enumerate(bad):
            with self.subTest(number=number), self.archive({
                'assets/owned_riddles.json': expected['owned_riddles.json'],
                'assets/dexopt/baseline.prof': payload,
            }) as archive:
                with self.assertRaisesRegex(ValueError, 'Invalid Android ART profile'):
                    check_assets(archive, expected, profile='release')

    def test_release_rejects_corrupt_art_profile_metadata(self):
        expected = {'owned_riddles.json': b'approved test JSON'}
        for header, width in ((b'prm\x00001\x00', 1), (b'prm\x00002\x00', 2)):
            with self.subTest(header=header), self.archive({
                'assets/owned_riddles.json': expected['owned_riddles.json'],
                'assets/dexopt/baseline.profm': self.art_profile(header, dex_count_size=width) + b'garbage',
            }) as archive:
                with self.assertRaisesRegex(ValueError, 'Invalid Android ART profile'):
                    check_assets(archive, expected, profile='release')

    def test_uncompressed_provenance_is_checked_by_content(self):
        expected = {'word_sources.jsonl.gz': gzip.compress(b'synthetic provenance'), 'owned_riddles.json': b'own db'}
        with self.archive({'assets/word_sources.jsonl': b'synthetic provenance',
                           'assets/owned_riddles.json': b'own db'}) as archive:
            check_assets(archive, expected)

    def test_hidden_backup_and_replaced_payload_are_rejected(self):
        expected = {'word_sources.jsonl.gz': gzip.compress(b'synthetic provenance'), 'owned_riddles.json': b'own db'}
        assets = {'assets/word_sources.jsonl.gz': expected['word_sources.jsonl.gz'], 'assets/owned_riddles.json': b'own db'}
        for mutation in ({**assets, 'assets/legacy.zip': b'hidden synthetic text'},
                         {**assets, 'assets/owned_riddles.json': b'synthetic foreign db'}):
            with self.subTest(mutation=list(mutation)), self.archive(mutation) as archive:
                with self.assertRaises(ValueError):
                    check_assets(archive, expected)

    def test_markers_include_nested_aar_classes(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('META-INF/synthetic.version', '1.2.3')
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, 'w') as archive:
            archive.writestr('classes.jar', jar.getvalue())
        self.assertEqual({'META-INF/synthetic.version': '1.2.3'}, version_markers(aar.getvalue()))

    def test_legacy_screening_detects_prose_in_binary_payload_without_exporting_it(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            db_path = root / 'synthetic.json'
            text = 'Синтетическое описание выдуманного предмета для проверки сканера.'
            lexical_json.build(db_path,[dict(word='фикстура',text=text,origin='wiktionary')],{}, {}, 8)
            apk = root / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'prefix\x00' + text.encode() + b'\x00suffix')
            result = screen_legacy(root, apk, [], db_path, hashlib.sha256(db_path.read_bytes()).hexdigest(), None)
            self.assertEqual([dict(path='APK:classes.dex', old_words=['фикстура'])], result['matches'])
            self.assertNotIn(text, str(result))
