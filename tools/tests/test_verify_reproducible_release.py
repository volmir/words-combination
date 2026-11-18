"""Synthetic evidence and subprocess tests; never read an owner's release key."""

import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.verify_reproducible_release import (
    OWNER_CERT_SHA256,
    check_release_checksum,
    find_apksigner,
    verify_release,
)


class ReproducibleReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.signed = root / 'WordsCombination-v1.0.1.apk'
        self.unsigned = root / 'app-release-unsigned.apk'
        self.sums = root / 'SHA256SUMS.txt'
        self.signed.write_bytes(b'synthetic signed apk bytes')
        self.unsigned.write_bytes(b'independently compiled unsigned apk')
        digest = hashlib.sha256(self.signed.read_bytes()).hexdigest()
        self.sums.write_text(f'{digest}  {self.signed.name}\n', encoding='utf-8')

    def fake_signature(self, cert=OWNER_CERT_SHA256, valid=True):
        return subprocess.CompletedProcess(
            [], 0 if valid else 1,
            stdout=('Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
                    f'Number of signers: 1\nSigner #1 certificate SHA-256 digest: {cert}\n'),
            stderr='',
        )

    def run_verification(self, side_effects):
        with patch('tools.verify_reproducible_release.subprocess.run', side_effect=side_effects) as runner:
            result = verify_release(self.signed, self.unsigned, self.sums,
                                    '/sdk/build-tools/36.0.0/apksigner', '/usr/bin/apksigcopier')
        return result, runner.call_args_list

    def test_accepts_identical_apk_after_copying_approved_v2_signature(self):
        result, calls = self.run_verification([
            self.fake_signature(),
            subprocess.CompletedProcess([], 1, '', 'unsigned'),
            subprocess.CompletedProcess([], 0, '', ''),
        ])
        self.assertEqual('LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED', result['status'])
        self.assertFalse(result['fdroid_buildserver_verified'])
        self.assertEqual(OWNER_CERT_SHA256, result['signer_certificate_sha256'])
        self.assertEqual(['/usr/bin/apksigcopier', 'compare', '--unsigned',
                          str(self.signed), str(self.unsigned)], calls[2].args[0])
        self.assertTrue(calls[2].kwargs['env']['PATH'].startswith('/sdk/build-tools/36.0.0:'))

    def test_rejects_changed_published_apk(self):
        self.signed.write_bytes(b'tampered bytes')
        with self.assertRaisesRegex(ValueError, 'does not match'):
            check_release_checksum(self.signed, self.sums)

    def test_rejects_misnamed_or_multiple_checksum_entries(self):
        for content in ('0' * 64 + '  wrong.apk\n',
                        self.sums.read_text() * 2,
                        'not a checksum\n'):
            with self.subTest(content=content):
                self.sums.write_text(content)
                with self.assertRaises(ValueError):
                    check_release_checksum(self.signed, self.sums)

    def test_rejects_wrong_signing_certificate(self):
        with patch('tools.verify_reproducible_release.subprocess.run', return_value=self.fake_signature('f' * 64)):
            with self.assertRaisesRegex(ValueError, 'approved owner certificate'):
                verify_release(self.signed, self.unsigned, self.sums, 'apksigner', 'apksigcopier')

    def test_rejects_invalid_published_signature(self):
        with patch('tools.verify_reproducible_release.subprocess.run', return_value=self.fake_signature(valid=False)):
            with self.assertRaisesRegex(ValueError, 'signature does not verify'):
                verify_release(self.signed, self.unsigned, self.sums, 'apksigner', 'apksigcopier')

    def test_rejects_missing_v2_signature(self):
        fake = self.fake_signature()
        fake.stdout = fake.stdout.replace('APK Signature Scheme v2): true', 'APK Signature Scheme v2): false')
        with patch('tools.verify_reproducible_release.subprocess.run', return_value=fake):
            with self.assertRaisesRegex(ValueError, 'no verified v2 signature'):
                verify_release(self.signed, self.unsigned, self.sums, 'apksigner', 'apksigcopier')

    def test_rejects_second_apk_when_signed(self):
        with patch('tools.verify_reproducible_release.subprocess.run', side_effect=[
            self.fake_signature(), subprocess.CompletedProcess([], 0, '', ''),
        ]):
            with self.assertRaisesRegex(ValueError, 'Second APK is signed'):
                verify_release(self.signed, self.unsigned, self.sums, 'apksigner', 'apksigcopier')

    def test_rejects_apksigcopier_mismatch(self):
        with patch('tools.verify_reproducible_release.subprocess.run', side_effect=[
            self.fake_signature(), subprocess.CompletedProcess([], 1, '', ''),
            subprocess.CompletedProcess([], 1, '', 'synthetic byte mismatch'),
        ]):
            with self.assertRaisesRegex(ValueError, 'synthetic byte mismatch'):
                verify_release(self.signed, self.unsigned, self.sums, 'apksigner', 'apksigcopier')

    def test_rejects_reusing_same_apk_or_missing_file(self):
        with self.assertRaisesRegex(ValueError, 'different files'):
            verify_release(self.signed, self.signed, self.sums, 'apksigner', 'apksigcopier')
        self.unsigned.unlink()
        with self.assertRaisesRegex(ValueError, 'Required file is missing'):
            verify_release(self.signed, self.unsigned, self.sums, 'apksigner', 'apksigcopier')

    def test_locates_apksigner_inside_android_home(self):
        root = Path(self.temp.name) / 'sdk'
        signer = root / 'build-tools' / '36.0.0' / 'apksigner'
        signer.parent.mkdir(parents=True)
        signer.write_text('synthetic executable')
        signer.chmod(0o700)
        with patch('tools.verify_reproducible_release.shutil.which', return_value=None):
            with patch.dict('tools.verify_reproducible_release.os.environ',
                            {'ANDROID_HOME': str(root), 'ANDROID_SDK_ROOT': str(root)}):
                self.assertEqual(str(signer), find_apksigner())


if __name__ == '__main__':
    unittest.main()
