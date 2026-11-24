"""Offline checks for the proposed fdroiddata metadata contract."""

from pathlib import Path
import unittest
from unittest.mock import patch

from tools.check_fdroid_submission_candidate import verify_recipe, verify_tag, scalar


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
        changed = self.recipe.replace(verify_recipe(self.recipe)['commit'], 'abcd687b', 1)
        with self.assertRaisesRegex(ValueError, 'Builds.commit'):
            verify_recipe(changed)

    def test_unverified_binary_url_rejected(self):
        changed = self.recipe.replace('WordsCombination-v%v.apk', 'app-release.apk', 1)
        with self.assertRaisesRegex(ValueError, 'Binaries'):
            verify_recipe(changed)

    def test_downgraded_version_rejected(self):
        code = verify_recipe(self.recipe)['code']
        changed = self.recipe.replace(f'versionCode: {code}', f'versionCode: {code - 1}', 1)
        with self.assertRaisesRegex(ValueError, 'Builds.versionCode'):
            verify_recipe(changed)

    def test_no_scan_bypass(self):
        with self.assertRaisesRegex(ValueError, 'Scanner bypass'):
            verify_recipe(self.recipe + '\nscanignore:\n  - app\n')

    def test_all_scandelete_archives_required(self):
        changed = self.recipe.replace('      - docs/qa/pre04-17/manual-witness.zip\n', '', 1)
        with self.assertRaisesRegex(ValueError, 'Missing explicitly removable'):
            verify_recipe(changed)

    def test_obsolete_update_pattern_rejected(self):
        with self.assertRaisesRegex(ValueError, 'UpdateCheckData'):
            verify_recipe(self.recipe + '\nUpdateCheckData: obsolete-pattern\n')

    def test_auto_updates_must_remain_enabled(self):
        with self.assertRaisesRegex(ValueError, 'AutoUpdateMode'):
            verify_recipe(self.recipe.replace('AutoUpdateMode: Version', 'AutoUpdateMode: None'))

    def test_provenance_cannot_be_deleted(self):
        changed = self.recipe.replace('    scandelete:\n',
                                      '    scandelete:\n      - data/word_sources.jsonl.gz\n')
        with self.assertRaisesRegex(ValueError, 'provenance must be retained'):
            verify_recipe(changed)

    def test_only_exact_provenance_scanignore_is_allowed(self):
        changed = self.recipe.replace('    scanignore:\n', '    scanignore:\n      - app\n')
        with self.assertRaisesRegex(ValueError, 'Scanner bypass'):
            verify_recipe(changed)

    def test_tag_commit_must_match_yaml(self):
        recipe = verify_recipe(self.recipe)
        with patch('tools.check_fdroid_submission_candidate.subprocess.check_output',
                   return_value='0' * 40):
            with self.assertRaisesRegex(ValueError, 'different commit'):
                verify_tag(Path('/synthetic'), recipe)

    def test_consistent_new_versions_are_not_pinned_to_old_release(self):
        recipe = verify_recipe(self.recipe)
        changed = self.recipe.replace('versionName: ' + recipe['version'], 'versionName: 2.3.4')
        changed = changed.replace('CurrentVersion: ' + recipe['version'], 'CurrentVersion: 2.3.4')
        changed = changed.replace('versionCode: ' + str(recipe['code']), 'versionCode: 17')
        changed = changed.replace('CurrentVersionCode: ' + str(recipe['code']), 'CurrentVersionCode: 17')
        result = verify_recipe(changed)
        self.assertEqual('2.3.4', result['version'])
        self.assertEqual(17, result['code'])

    def test_wrapped_and_inline_binary_url_are_equivalent(self):
        url = scalar(self.recipe, 'Binaries')
        inline = self.recipe.replace('Binaries: \n  ' + url, 'Binaries: ' + url)
        self.assertEqual(verify_recipe(self.recipe), verify_recipe(inline))


if __name__ == '__main__':
    unittest.main()
