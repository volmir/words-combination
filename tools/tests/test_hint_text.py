"""Тот же синтетический контракт, что в JVM HintTextTest; дамп не нужен."""
import json
from pathlib import Path
import sys
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from hint_text import clean, prepare


class HintTextTest(unittest.TestCase):
    def test_shared_vectors(self):
        vectors = json.loads((TOOLS / 'hint_vectors.json').read_text(encoding='utf-8'))
        for row in vectors:
            with self.subTest(row['id']):
                self.assertEqual(row['clean'], clean(row['text']))
                hint = prepare(row['answer'], row['text'], row.get('forms', ()), row.get('related', ()))
                self.assertEqual(row['result'], hint)
                if hint is not None:
                    self.assertLessEqual(len(hint), 140)
