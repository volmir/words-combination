import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from tools.build_library_notices import archive_notices, default_gradle_cache, generate, pom_licenses


class LibraryNoticesTest(unittest.TestCase):
    def test_json_datastore_notice_does_not_require_or_claim_protobuf(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cache = root / 'cache'
            pom = cache / 'androidx.datastore' / 'datastore-core-android' / '1.1.7' / 'hash' / 'lib.pom'
            pom.parent.mkdir(parents=True)
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                           '<licenses><license><name>Apache 2</name>'
                           '<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>'
                           '</license></licenses></project>')
            jar = root / 'library.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('META-INF/NOTICE', 'Synthetic notice\n')
            inputs = root / 'inputs.json'
            inputs.write_text(json.dumps({'debug': [{'artifact': 'androidx.datastore:datastore-core-android:1.1.7',
                                                     'file': str(jar)}]}))
            legal = root / 'legal'
            legal.mkdir()
            (legal / 'Apache-2.0.txt').write_text('Apache license\n')
            # No Protobuf license is present in this minimal JSON DataStore build.
            result = generate(inputs, cache, legal)
            self.assertNotIn('Protobuf BSD-3-Clause upstream notice', result['LIBRARY_NOTICES.txt'])
            self.assertNotIn('datastore-preferences-external-protobuf', result['RUNTIME_LIBRARIES.json'])

    def test_parent_license_is_resolved_with_both_pom_hashes(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            for name, body in [('parent', '<licenses><license><name>Apache-2.0</name>'
                                '<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>'),
                               ('child', '<parent><groupId>synthetic</groupId><artifactId>parent</artifactId>'
                                '<version>1</version></parent>')]:
                path = cache / 'synthetic' / name / '1' / 'hash' / (name + '.pom')
                path.parent.mkdir(parents=True)
                path.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">' + body + '</project>')
            licenses, evidence = pom_licenses(cache, 'synthetic:child:1')
            self.assertEqual('Apache-2.0', licenses[0]['spdx'])
            self.assertEqual(['synthetic:child:1', 'synthetic:parent:1'], [r['artifact'] for r in evidence])
            self.assertTrue(all(len(r['sha256']) == 64 for r in evidence))

    def test_unknown_or_missing_pom_does_not_become_apache_by_assumption(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(ValueError, 'Missing/ambiguous'):
                pom_licenses(Path(temp), 'synthetic:missing:1')

    def test_copyright_and_notice_from_nested_archive_are_preserved_verbatim(self):
        jar = io.BytesIO()
        text = 'Copyright Synthetic Test Holder\nSynthetic conditions\n'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('META-INF/NOTICE', text)
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, 'w') as archive:
            archive.writestr('classes.jar', jar.getvalue())
        self.assertEqual([('classes.jar!META-INF/NOTICE', text)], archive_notices(aar.getvalue()))

    def test_default_gradle_cache_respects_gradle_user_home(self):
        with patch.dict(os.environ, {'GRADLE_USER_HOME': '/tmp/custom-gradle-home'}, clear=False):
            self.assertEqual(
                Path('/tmp/custom-gradle-home/caches/modules-2/files-2.1'),
                default_gradle_cache(),
            )
        with patch.dict(os.environ, {'GRADLE_USER_HOME': ''}, clear=False):
            self.assertEqual(
                Path.home() / '.gradle/caches/modules-2/files-2.1',
                default_gradle_cache(),
            )
