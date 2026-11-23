"""Deletion-only updates remain cryptographically bound to the approved index."""
import hashlib
import unittest
from tools.build_owned_hints import dictionary_matches_index


class DictionaryDeltaTest(unittest.TestCase):
    def setUp(self):
        self.words = {'кот', 'лапочка', 'печенька'}
        self.sha = hashlib.sha256(('\n'.join(sorted(self.words)) + '\n').encode()).hexdigest()

    def manifest(self, excluded):
        return {'selection': {'word_cleanup': {'excluded': excluded}}}

    def test_original_dictionary_matches_without_delta(self):
        self.assertTrue(dictionary_matches_index(self.sha, self.words, {}))

    def test_exact_deletion_matches_original_approved_hash(self):
        self.assertTrue(dictionary_matches_index(self.sha, {'кот'}, self.manifest(['лапочка', 'печенька'])))

    def test_addition_or_undeclared_deletion_fails(self):
        for words in ({'кот', 'река'}, set()):
            self.assertFalse(dictionary_matches_index(self.sha, words, self.manifest(['лапочка', 'печенька'])))

    def test_malformed_or_inexact_delta_fails(self):
        for removed in (None, [], ['лапочка'], ['лапочка', 'печенька', 'печенька'],
                        ['лапочка', 'печенька', 'кот'], ['лапочка', 'печенька', 'чужое']):
            self.assertFalse(dictionary_matches_index(self.sha, {'кот'}, self.manifest(removed)))
