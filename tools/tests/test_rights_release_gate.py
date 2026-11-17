"""RIGHTS-04: exercise the actual strict CLI in isolated package copies."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class RightsReleaseGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(ROOT / 'tools', self.root / 'tools',
                        ignore=shutil.ignore_patterns('tests', '__pycache__', 'cache'))
        for folder, names in {
            'docs': ('owned_hints.json', 'OWN_HINTS_OWNER_APPROVAL.json',
                     'OWN_HINTS_MANUAL_AUDIT.json'),
            'data': ('campaign.json', 'words.txt', 'manifest.json', 'owned_riddles.json'),
        }.items():
            (self.root / folder).mkdir()
            for name in names:
                shutil.copyfile(ROOT / folder / name, self.root / folder / name)
        pinned_sqlite = ROOT / '.local/owned-hints-toolchain/lib/libsqlite3.so.0'
        if pinned_sqlite.is_file():
            target = self.root / '.local/owned-hints-toolchain/lib/libsqlite3.so.0'
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(pinned_sqlite, target)

    def change_json(self, relative, change):
        path = self.root / relative
        value = json.loads(path.read_bytes())
        change(value)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')

    def run_gate(self):
        # No waiver, environment changes, patched validators, or alternate CLI.
        return subprocess.run(
            [sys.executable, 'tools/build_game_data.py', '--check-release', '--out', 'data'],
            cwd=self.root, capture_output=True, text=True, timeout=60)

    def assert_blocked(self):
        result = self.run_gate()
        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertNotIn('"publishable": true', result.stdout)
        self.assertTrue(result.stderr.strip(), 'Expected explicit rejection reason')

    def test_approved_package_passes_actual_cli(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'release_audit': 'ok', 'publishable': True}, json.loads(result.stdout))

    def test_missing_owner_act_blocks(self):
        (self.root / 'docs/OWN_HINTS_OWNER_APPROVAL.json').unlink()
        self.assert_blocked()

    def test_missing_manual_audit_blocks(self):
        (self.root / 'docs/OWN_HINTS_MANUAL_AUDIT.json').unlink()
        self.assert_blocked()

    def test_one_hint_letter_blocks(self):
        path = self.root / 'docs/owned_hints.json'
        original = path.read_text(encoding='utf-8')
        hint = json.loads(original)['entries'][0]['hint']
        replacement = ('Н' if hint[0] != 'Н' else 'М') + hint[1:]
        changed = original.replace(hint, replacement, 1)
        self.assertEqual(1, sum(a != b for a, b in zip(original, changed)))
        self.assertEqual(len(original), len(changed))
        path.write_text(changed, encoding='utf-8')
        self.assert_blocked()

    def test_changed_owner_license_blocks(self):
        self.change_json('docs/OWN_HINTS_OWNER_APPROVAL.json',
                         lambda v: v.update(license='MIT'))
        self.assert_blocked()

    def test_changed_campaign_bytes_block(self):
        with (self.root / 'data/campaign.json').open('ab') as stream:
            stream.write(b'\n')
        self.assert_blocked()

    def test_changed_manifest_database_sha_blocks(self):
        self.change_json('data/manifest.json',
                         lambda v: v['owned_riddles'].update(sha256='0' * 64))
        self.assert_blocked()

    def test_changed_audit_database_sha_blocks(self):
        self.change_json('docs/OWN_HINTS_MANUAL_AUDIT.json',
                         lambda v: v.update(database_sha256='0' * 64))
        self.assert_blocked()

    def test_changed_database_license_even_with_updated_sha_blocks(self):
        path = self.root / 'data/owned_riddles.json'
        self.change_json('data/owned_riddles.json', lambda value: value.update(license='MIT'))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        self.change_json('data/manifest.json',
                         lambda v: v['owned_riddles'].update(sha256=digest))
        self.assert_blocked()


if __name__ == '__main__':
    unittest.main()
