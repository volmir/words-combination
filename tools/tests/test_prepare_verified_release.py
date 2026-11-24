"""Offline release-gate tests with synthetic APKs and a disposable Git repo."""

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

from tools import prepare_verified_release as release


class VerifiedReleasePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        subprocess.run(['git', 'init', '--quiet', str(self.repo)], check=True)
        (self.repo / 'README').write_text('synthetic checkout\n', encoding='utf-8')
        subprocess.run(['git', 'add', 'README'], cwd=self.repo, check=True)
        subprocess.run(['git', '-c', 'user.name=Test',
                        '-c', 'user.email=test@example.invalid',
                        'commit', '--quiet', '-m', 'Synthetic'],
                       cwd=self.repo, check=True)
        subprocess.run(['git', '-c', 'user.name=Test',
                        '-c', 'user.email=test@example.invalid',
                        'tag', '-a', '1.0.2', '-m', 'Release'],
                       cwd=self.repo, check=True)
        self.key = self.root / 'private.jks'
        self.key.write_bytes(b'synthetic, never a real key')
        self.environment = {
            release.SIGNING_VARIABLES[0]: str(self.key),
            release.SIGNING_VARIABLES[1]: 'secret_store_password',
            release.SIGNING_VARIABLES[2]: 'release-alias',
            release.SIGNING_VARIABLES[3]: 'secret_key_password',
        }
        self.output = self.root / 'verified'

    def fake_build(self, command, *, cwd=None, env=None, version='1.0.2', code=3):
        if command[:2] == ['bash', './gradlew']:
            target = Path(cwd) / 'app/build/outputs/apk/release'
            target.mkdir(parents=True, exist_ok=True)
            signed = bool(env.get(release.SIGNING_VARIABLES[0]))
            marker = Path(cwd) / 'first-clone-build-marker'
            if signed:
                # The signed build must not inherit files from the first clone.
                self.assertFalse(marker.exists())
            else:
                marker.write_text('first clone', encoding='utf-8')
            filename = 'app-release.apk' if signed else 'app-release-unsigned.apk'
            commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=cwd, text=True).strip()
            with zipfile.ZipFile(target / filename, 'w') as archive:
                info = zipfile.ZipInfo('META-INF/version-control-info.textproto', (2026, 1, 1, 0, 0, 0))
                archive.writestr(info, f'revision: "{commit}"')
            (target / 'output-metadata.json').write_text(json.dumps({
                'elements': [{'versionName': version, 'versionCode': code}],
            }))
        elif command[:2] == ['apksigner', 'sign']:
            Path(command[command.index('--out') + 1]).write_bytes(Path(command[-1]).read_bytes())
        else:
            subprocess.run(command, cwd=cwd, env=env, check=True)

    def test_clean_git_clones_and_export_only_after_verification(self):
        environment_seen = []
        build_paths = []

        def build(command, **kwargs):
            if command[:2] == ['bash', './gradlew']:
                environment_seen.append(kwargs['env'])
                build_paths.append(kwargs['cwd'])
            self.fake_build(command, **kwargs)

        with patch.object(release, 'run', side_effect=build), \
             patch.object(release, 'verify_release', return_value={
                 'status': 'LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED',
             }) as verifier:
            result = release.prepare_release(
                self.repo, '1.0.2', self.output, expected_code=3,
                environ=self.environment, apksigner='apksigner', apksigcopier='apksigcopier',
            )
        self.assertEqual('LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED', result['status'])
        self.assertEqual('1.0.2', result['tag'])
        self.assertEqual(3, result['version_code'])
        self.assertEqual(2, len(environment_seen))
        self.assertNotEqual(build_paths[0], build_paths[1])
        self.assertNotIn(release.SIGNING_VARIABLES[0], environment_seen[0])
        self.assertNotIn(release.SIGNING_VARIABLES[0], environment_seen[1])
        self.assertTrue(verifier.called)
        self.assertEqual((self.output / 'unsigned-1.apk').read_bytes(),
                         (self.output / 'WordsCombination-v1.0.2.apk').read_bytes())
        self.assertIn('WordsCombination-v1.0.2.apk',
                      (self.output / 'SHA256SUMS.txt').read_text())
        self.assertEqual('LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED', json.loads(
            (self.output / 'reproducibility-evidence.json').read_text())['status'])

    def test_rejects_missing_or_relative_keystore_without_leaking_secrets(self):
        for name in release.SIGNING_VARIABLES:
            incomplete = dict(self.environment)
            del incomplete[name]
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'four'):
                release.require_signing_environment(incomplete)
        relative = dict(self.environment)
        relative[release.SIGNING_VARIABLES[0]] = 'relative.jks'
        with self.assertRaisesRegex(ValueError, 'absolute path') as caught:
            release.require_signing_environment(relative)
        self.assertNotIn('secret_', str(caught.exception))

    def test_requires_existing_annotated_tag(self):
        self.assertEqual(40, len(release.get_tag_commit(self.repo, '1.0.2')))
        with self.assertRaisesRegex(ValueError, 'semantic-version'):
            release.get_tag_commit(self.repo, '../main')
        # A lightweight tag must not depend on the global tag.gpgSign setting.
        subprocess.run(['git', '-c', 'tag.gpgSign=false', 'tag', '1.0.3'],
                       cwd=self.repo, check=True)
        with self.assertRaisesRegex(ValueError, 'annotated'):
            release.get_tag_commit(self.repo, '1.0.3')

    def test_bad_version_blocks_export(self):
        def build(command, **kwargs):
            self.fake_build(command, version='1.0.1', **kwargs)

        with patch.object(release, 'run', side_effect=build), \
             patch.object(release, 'verify_release') as verifier:
            with self.assertRaisesRegex(ValueError, 'versionName/versionCode'):
                release.prepare_release(
                    self.repo, '1.0.2', self.output, expected_code=3,
                    environ=self.environment, apksigner='apksigner', apksigcopier='apksigcopier',
                )
        self.assertFalse(self.output.exists())
        verifier.assert_not_called()

    def test_failed_comparison_blocks_export(self):
        with patch.object(release, 'run', side_effect=self.fake_build), \
             patch.object(release, 'verify_release', side_effect=ValueError('mismatch')):
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                release.prepare_release(
                    self.repo, '1.0.2', self.output, expected_code=3,
                    environ=self.environment, apksigner='apksigner', apksigcopier='apksigcopier',
                )
        self.assertFalse(self.output.exists())

    def test_bad_clone_commit_blocks_build(self):
        with self.assertRaisesRegex(ValueError, 'release commit'):
            release.clone_tag(self.repo, self.root / 'bad-clone', '1.0.2', '0' * 40)

    def test_unequal_unsigned_apks_block_signing_and_export(self):
        calls = []
        def build(command, **kwargs):
            self.fake_build(command, **kwargs)
            if command[:2] == ['bash', './gradlew']:
                calls.append(kwargs['cwd'])
                if len(calls) == 2:
                    with zipfile.ZipFile(Path(kwargs['cwd']) / 'app/build/outputs/apk/release/app-release-unsigned.apk', 'a') as archive:
                        archive.writestr('different', 'changed')
            self.assertNotEqual(['apksigner', 'sign'], command[:2])
        with patch.object(release, 'run', side_effect=build):
            with self.assertRaisesRegex(ValueError, 'SHA-256 values differ'):
                release.prepare_release(self.repo, '1.0.2', self.output, 3,
                                        environ=self.environment, apksigner='apksigner',
                                        apksigcopier='apksigcopier')
        self.assertFalse(self.output.exists())

    def test_refuses_overwriting_previous_candidate(self):
        self.output.mkdir()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            release.prepare_release(
                self.repo, '1.0.2', self.output, expected_code=3,
                environ=self.environment, apksigner='apksigner', apksigcopier='apksigcopier',
            )


if __name__ == '__main__':
    unittest.main()
