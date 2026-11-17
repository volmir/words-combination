"""Recovery and writer isolation use synthetic artifacts; no network in tests."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools import owned_hints_toolchain as toolchain


class OwnedHintsToolchainTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.data = self.root / 'data'
        self.data.mkdir()
        self.content = b'synthetic database bytes'
        self.digest = hashlib.sha256(self.content).hexdigest()
        self.lock = copy.deepcopy(toolchain.load_lock())
        self.lock['database']['sha256'] = self.digest
        (self.data / 'manifest.json').write_text(json.dumps({'definitions': {'sha256': self.digest}}))

    def test_missing_database_restores_exact_git_bytes_without_network(self):
        with patch.object(toolchain, 'git_database', return_value=self.content), \
                patch.object(toolchain, 'download') as download:
            toolchain.restore_database(self.data, self.lock)
        self.assertEqual(self.content, (self.data / 'definitions.db').read_bytes())
        download.assert_not_called()

    def test_recovery_reads_pinned_revision_from_git(self):
        path = self.data / 'definitions.db'
        path.write_bytes(self.content)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', 'data/definitions.db'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Synthetic',
                        '-c', 'user.email=synthetic@example.test', 'commit', '-qm', 'fixture'], check=True)
        self.lock['database']['git_revision'] = subprocess.check_output(
            ['git', '-C', str(self.root), 'rev-parse', 'HEAD'], text=True).strip()
        path.unlink()
        toolchain.restore_database(self.data, self.lock)
        self.assertEqual(self.content, path.read_bytes())

    def test_existing_modified_database_is_never_overwritten(self):
        path = self.data / 'definitions.db'
        path.write_bytes(b'changed')
        with patch.object(toolchain, 'git_database') as git, self.assertRaisesRegex(ValueError, 'SHA-256'):
            toolchain.restore_database(self.data, self.lock)
        self.assertEqual(b'changed', path.read_bytes())
        git.assert_not_called()

    def test_wrong_git_bytes_cannot_be_installed(self):
        with patch.object(toolchain, 'git_database', return_value=b'wrong'), \
                self.assertRaisesRegex(ValueError, 'SHA-256'):
            toolchain.restore_database(self.data, self.lock)
        self.assertFalse((self.data / 'definitions.db').exists())

    def test_manifest_must_match_pinned_artifact(self):
        (self.data / 'manifest.json').write_text(json.dumps({'definitions': {'sha256': '0' * 64}}))
        with patch.object(toolchain, 'git_database') as git, self.assertRaisesRegex(ValueError, 'manifest'):
            toolchain.restore_database(self.data, self.lock)
        git.assert_not_called()

    def test_no_git_requires_explicit_network_flag(self):
        with patch.object(toolchain, 'git_database', return_value=None), \
                patch.object(toolchain, 'download') as download, \
                self.assertRaisesRegex(ValueError, '--download'):
            toolchain.restore_database(self.data, self.lock)
        download.assert_not_called()

    def test_explicit_download_recovers_missing_database(self):
        with patch.object(toolchain, 'git_database', return_value=None), \
                patch.object(toolchain, 'download', return_value=self.content) as download:
            toolchain.restore_database(self.data, self.lock, allow_download=True)
        download.assert_called_once_with(self.lock['database'])
        self.assertEqual(self.content, (self.data / 'definitions.db').read_bytes())

    def test_corrupt_download_is_rejected_even_before_publication(self):
        with patch.object(toolchain, 'git_database', return_value=None), \
                patch.object(toolchain, 'download', return_value=b'corrupt'), \
                self.assertRaisesRegex(ValueError, 'SHA-256'):
            toolchain.restore_database(self.data, self.lock, allow_download=True)
        self.assertFalse((self.data / 'definitions.db').exists())

    def test_corrupt_cached_library_is_rejected(self):
        library = self.root / 'libsqlite3.so.0'
        library.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            toolchain.check_library(library, self.lock)

    def test_different_sqlite_requires_prepared_library_without_network(self):
        with patch.object(toolchain, 'runtime_profile', return_value={}), \
                patch.object(toolchain, 'LIBRARY', self.root / 'missing.so'), \
                patch.object(toolchain, 'download') as download, \
                self.assertRaisesRegex(ValueError, 'prepare_owned_hints.py --download'):
            toolchain.build_database(self.root / 'output.db', [], {}, {}, 10)
        download.assert_not_called()

    def test_matching_runtime_reuses_existing_generator(self):
        with patch.object(toolchain, 'runtime_profile', return_value=self.lock['sqlite']), \
                patch('tools.build_game_data.build_database') as build:
            toolchain.build_database(self.root / 'output.db', [], {}, {}, 10)
        build.assert_called_once_with(self.root / 'output.db', [], {}, {}, 10)

    def test_worker_checks_profile_before_creating_database(self):
        output = self.root / 'output.db'
        with patch.object(toolchain, 'runtime_profile', return_value={}), \
                self.assertRaisesRegex(ValueError, 'SQLite'):
            toolchain.worker(output, dict(rows=[], categories={}, metadata={}, version=10))
        self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
