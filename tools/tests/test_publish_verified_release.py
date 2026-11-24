"""Publication uses a pinned verified bundle and checks the actual public download."""
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from tools import publish_verified_release as publication
from tools.verify_reproducible_release import OWNER_CERT_SHA256, sha256_file


class VerifiedPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        (self.repo / 'README').write_text('fixture')
        subprocess.run(['git', 'add', '.'], cwd=self.repo, check=True)
        subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                        'commit', '-qm', 'Fixture'], cwd=self.repo, check=True)
        subprocess.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                        'tag', '-a', '1.1.1', '-m', 'Fixture'], cwd=self.repo, check=True)
        self.commit = publication.get_tag_commit(self.repo, '1.1.1')
        self.export = self.root / 'verified'
        self.export.mkdir()
        self.apk = self.export / 'WordsCombination-v1.1.1.apk'
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('META-INF/version-control-info.textproto', f'revision: "{self.commit}"')
        for index in (1, 2):
            (self.export / f'unsigned-{index}.apk').write_bytes(self.apk.read_bytes())
        digest = sha256_file(self.apk)
        self.evidence = dict(status='LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED',
                             tag='1.1.1', commit=self.commit, version_name='1.1.1', version_code=7,
                             independent_paths=True, signer_certificate_sha256=OWNER_CERT_SHA256,
                             signed_sha256=digest, unsigned_sha256s=[digest, digest])
        self.save_evidence()
        (self.export / 'SHA256SUMS.txt').write_text(f'{digest}  {self.apk.name}\n')

    def save_evidence(self):
        (self.export / 'reproducibility-evidence.json').write_text(json.dumps(self.evidence))

    def validate(self):
        return publication.validate_export(self.export, self.repo, 'apksigner', 'apksigcopier')

    def test_rejects_changed_signed_apk(self):
        with self.apk.open('ab') as target:
            target.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.validate()

    def test_rejects_unconfirmed_apk(self):
        (self.export / 'reproducibility-evidence.json').unlink()
        with self.assertRaises(FileNotFoundError):
            self.validate()

    def test_rejects_unverified_status_and_certificate_and_commit(self):
        for field, value in [('status', 'FAIL'), ('commit', '0' * 40),
                             ('independent_paths', False), ('signer_certificate_sha256', 'f' * 64)]:
            original = dict(self.evidence)
            with self.subTest(field=field):
                self.evidence[field] = value
                self.save_evidence()
                with self.assertRaises(ValueError):
                    self.validate()
            self.evidence = original

    def test_rejects_ordinary_gradle_output(self):
        directory = self.repo / 'app/build/outputs/apk/release'
        directory.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, 'Ordinary Gradle'):
            publication.validate_export(directory, self.repo, 'apksigner', 'apksigcopier')

    def test_crypto_failure_prevents_publication_validation(self):
        with patch.object(publication, 'verify_release', side_effect=ValueError('signature failed')):
            with self.assertRaisesRegex(ValueError, 'signature failed'):
                self.validate()

    def test_rejects_changed_download_before_signature_comparison(self):
        downloaded = self.root / self.apk.name
        downloaded.write_bytes(b'incorrect download')
        with patch.object(publication, 'verify_release') as verifier:
            with self.assertRaisesRegex(ValueError, 'Downloaded public APK differs'):
                publication.verify_download(downloaded, self.export, self.evidence,
                                            'apksigner', 'apksigcopier')
        verifier.assert_not_called()

    def test_publication_checks_download_and_records_failure_without_replacement(self):
        def gh(command, **kwargs):
            return subprocess.CompletedProcess(command, 1 if command[1] == 'api' else 0,
                                               stdout='', stderr='HTTP 404: Not Found')
        with patch.object(publication, 'find_apksigner', return_value='apksigner'), \
             patch.object(publication.shutil, 'which', return_value='apksigcopier'), \
             patch.object(publication, 'verify_release', return_value={}), \
             patch.object(publication, 'get_tag_commit', return_value=self.commit), \
             patch.object(publication.subprocess, 'check_output', return_value=self.commit + '\trefs/tags/1.1.1^{}'), \
             patch.object(publication.subprocess, 'run', side_effect=gh) as runner, \
             patch.object(publication.urllib.request, 'urlopen', return_value=io.BytesIO(b'wrong public bytes')):
            with self.assertRaisesRegex(ValueError, 'Downloaded public APK differs'):
                publication.publish_release(self.export, self.repo, 'test/project', publish=True)
        self.assertEqual('FAIL', json.loads((self.export / 'publication-verification.json').read_text())['status'])
        creates = [call for call in runner.call_args_list if call.args[0][1:3] == ['release', 'create']]
        self.assertEqual(1, len(creates))
        self.assertNotIn('--clobber', creates[0].args[0])


if __name__ == '__main__':
    unittest.main()
