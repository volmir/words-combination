"""Тематические признаки: только придуманные значения, без внешних статей."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import word_categories as thematic


class WordCategoriesTest(unittest.TestCase):
    def setUp(self):
        self.taxonomy = thematic.load_taxonomy()

    def classify(self, text, **fields):
        return thematic.classify_sense(dict(text=text, **fields), self.taxonomy)

    def test_structured_evidence_precedes_sense_marks_leading_marks_and_head(self):
        result = self.classify("ботан. Инструмент для синтетической проверки.",
                               categories=[{"name": "ru:Птицы"}], topics=["ornithology"],
                               raw_tags=["мед."], tags=["medicine"])
        self.assertEqual("birds", result["category"])
        self.assertEqual("sense.categories:птицы;sense.topics:ornithology", result["category_source"])
        result = self.classify("зоол. Инструмент для проверки.", raw_tags=["ботан."])
        self.assertEqual({"category": "plants", "category_source": "sense.raw_tags:ботан"}, result)
        self.assertEqual("plants", self.classify("Инструмент для проверки.", tags=["botany"])["category"])

    def test_conflicting_same_tier_does_not_fall_back_to_a_convenient_head(self):
        result = self.classify("Птица для проверки.", topics=["botany", "zoology"])
        self.assertEqual({"category": "other", "category_source": "unknown:conflicting_evidence"}, result)
        self.assertEqual("birds", self.classify("Тестовый текст.", topics=["zoology", "ornithology"])["category"])

    def test_leading_marks_and_closed_head_rules_are_anchored(self):
        cases = [
            ("разг., (зоол.) Инструмент для проверки.", "animals", "leading_label:зоол"),
            ("Небольшая птица для синтетического опыта.", "birds", "gloss_head:birds"),
            ("Музыкальный инструмент для синтетического опыта.", "music", "gloss_head:music"),
            ("Инструмент для опыта, на конце которого нарисована птица.", "tools", "gloss_head:tools"),
            ("Синтетический образец с длинным описанием, в конце которого упомянута рыба.",
             "other", "unknown:no_thematic_evidence"),
            ("Синтетическое описание; зоол. птица в конце строки.", "other", "unknown:no_thematic_evidence"),
            ("Рыбак для синтетического опыта.", "other", "unknown:no_thematic_evidence"),
        ]
        for text, category, source in cases:
            with self.subTest(text=text):
                self.assertEqual(dict(category=category, category_source=source), self.classify(text))

    def test_unknown_labels_are_not_invented_categories(self):
        result = self.classify("Синтетический образец.", categories=["ru:Несуществующая рубрика"], topics=["alien-topic"])
        self.assertEqual("other", result["category"])
        self.assertTrue(result["category_source"].startswith("unknown:"))
        self.assertEqual("other", self.classify("Синтетический образец.", categories=["ru:Птицы в литературе"])["category"])

    def test_word_requires_agreement_of_all_senses_and_index_excludes_unknown(self):
        bird = self.classify("Птица для опыта.")
        tool = self.classify("Инструмент для опыта.")
        unknown = self.classify("Синтетический образец.")
        self.assertEqual(bird, thematic.word_category([bird, bird]))
        self.assertEqual("unknown:ambiguous_senses", thematic.word_category([bird, tool])["category_source"])
        self.assertEqual("unknown:uncertain_sense", thematic.word_category([bird, unknown])["category_source"])
        self.assertEqual("unknown:no_usable_sense", thematic.word_category([])["category_source"])
        metadata = {"образец": unknown, "птица": bird, "омоним": thematic.word_category([bird, tool])}
        index = thematic.category_index(metadata, self.taxonomy)
        self.assertNotIn("other", index["categories"])
        self.assertEqual(["птица"], [word for words in index["categories"].values() for word in words])
        self.assertEqual(metadata, index["word_metadata"])


if __name__ == "__main__":
    unittest.main()
