"""Offline checks for the proposed fdroiddata metadata contract."""

from pathlib import Path
import unittest

from tools.check_fdroid_submission_candidate import verify_recipe


CANDIDATE = (Path(__file__).resolve().parents[2] /
             'docs/fdroiddata/metadata/io.github.volmir.wordscombination.yml')


class FdroidSubmissionCandidateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.recipe = CANDIDATE.read_text(encoding='utf-8')

    def test_known_candidate(self):
        verify_recipe(self.recipe)

    def test_wrong_certificate_rejected(self):
        changed = self.recipe.replace('a10cdd4f', 'f00cdd4f', 1)
        with self.assertRaisesRegex(ValueError, 'AllowedAPKSigningKeys'):
            verify_recipe(changed)

    def test_wrong_revision_rejected(self):
        changed = self.recipe.replace('2ce9687b', 'abcd687b', 1)
        with self.assertRaisesRegex(ValueError, 'Builds.commit'):
            verify_recipe(changed)

    def test_unverified_binary_url_rejected(self):
        changed = self.recipe.replace('WordsCombination-v%v.apk', 'app-release.apk', 1)
        with self.assertRaisesRegex(ValueError, 'Binaries'):
            verify_recipe(changed)

    def test_downgraded_version_rejected(self):
        changed = self.recipe.replace('versionCode: 3', 'versionCode: 2', 1)
        with self.assertRaisesRegex(ValueError, 'Builds.versionCode'):
            verify_recipe(changed)

    def test_no_scan_bypass(self):
        with self.assertRaisesRegex(ValueError, 'Scanner bypass'):
            verify_recipe(self.recipe + '\nscanignore:\n  - app\n')

    def test_all_scandelete_archives_required(self):
        changed = self.recipe.replace('      - docs/qa/pre04-17/manual-witness.zip\n', '', 1)
        with self.assertRaisesRegex(ValueError, 'Missing explicitly removable'):
            verify_recipe(changed)

    def test_update_pattern_required(self):
        changed = self.recipe.replace('UpdateCheckData: ', 'WrongUpdateField: ', 1)
        with self.assertRaisesRegex(ValueError, 'UpdateCheckData'):
            verify_recipe(changed)


if __name__ == '__main__':
    unittest.main()
