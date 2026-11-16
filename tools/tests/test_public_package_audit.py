"""Archive attacks use only synthetic payloads, never external dictionary prose."""
import gzip
import hashlib
import io
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

from tools.audit_public_package import check_assets, version_markers, screen_legacy


class PublicPackageAuditTest(unittest.TestCase):
    def archive(self, assets):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as archive:
            for name, value in assets.items():
                archive.writestr(name, value)
        return zipfile.ZipFile(io.BytesIO(raw.getvalue()))

    def test_uncompressed_provenance_is_checked_by_content(self):
        expected = {'word_sources.jsonl.gz': gzip.compress(b'synthetic provenance'), 'definitions.db': b'own db'}
        with self.archive({'assets/word_sources.jsonl': b'synthetic provenance',
                           'assets/definitions.db': b'own db'}) as archive:
            check_assets(archive, expected)

    def test_hidden_backup_and_replaced_payload_are_rejected(self):
        expected = {'word_sources.jsonl.gz': gzip.compress(b'synthetic provenance'), 'definitions.db': b'own db'}
        assets = {'assets/word_sources.jsonl.gz': expected['word_sources.jsonl.gz'], 'assets/definitions.db': b'own db'}
        for mutation in ({**assets, 'assets/legacy.zip': b'hidden synthetic text'},
                         {**assets, 'assets/definitions.db': b'synthetic foreign db'}):
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
            db_path = root / 'synthetic.db'
            text = 'Синтетическое описание выдуманного предмета для проверки сканера.'
            with sqlite3.connect(db_path) as db:
                db.execute('CREATE TABLE definitions(word,text,origin)')
                db.execute('INSERT INTO definitions VALUES(?,?,?)', ('фикстура', text, 'wiktionary'))
            apk = root / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'prefix\x00' + text.encode() + b'\x00suffix')
            result = screen_legacy(root, apk, [], db_path, hashlib.sha256(db_path.read_bytes()).hexdigest(), None)
            self.assertEqual([dict(path='APK:classes.dex', old_words=['фикстура'])], result['matches'])
            self.assertNotIn(text, str(result))
