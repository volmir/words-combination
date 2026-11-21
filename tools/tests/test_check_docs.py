"""Проверки лёгкого проверильщика документации: синтетические данные и репозиторий."""
import tempfile
import unittest
from pathlib import Path

from tools.check_docs import check

ROOT = Path(__file__).resolve().parents[2]


def write(root, name, text):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


class DocsCheckTest(unittest.TestCase):
    def test_repository_documentation_passes(self):
        self.assertEqual([], check(ROOT))

    def test_valid_tree_has_no_problems(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write(root, "README.md", "Правила — в [SPEC](docs/SPEC.md); данные — [campaign](data/campaign.json).\n")
            write(root, "data/campaign.json", "{}")
            write(root, "docs/INDEX.md", "# Индекс\n\n| `SPEC.md` | действует |\n")
            write(root, "docs/SPEC.md", "См. [карту](INDEX.md) и [README](../README.md).\n")
            self.assertEqual([], check(root))

    def test_broken_document_and_data_links_are_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write(root, "README.md", "[нет](docs/MISSING.md) и [данные](data/GONE.json)\n")
            write(root, "docs/INDEX.md", "# Индекс\n")
            problems = check(root)
            self.assertEqual(2, len(problems), problems)
            self.assertTrue(any("docs/MISSING.md" in problem for problem in problems), problems)
            self.assertTrue(any("data/GONE.json" in problem for problem in problems), problems)

    def test_missing_index_map_and_reference_are_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual(["docs/INDEX.md: карта документов отсутствует"], check(root))
            write(root, "docs/INDEX.md", "# Индекс\n\n| `GONE.md` | действует |\n")
            self.assertEqual(["docs/INDEX.md: GONE.md не найден"], check(root))

    def test_invalid_utf8_is_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "README.md"
            path.write_bytes(b"\xff\xfe broken")
            write(root, "docs/INDEX.md", "# Индекс\n")
            problems = check(root)
            self.assertEqual(1, len(problems), problems)
            self.assertIn("UTF-8", problems[0])

    def test_external_anchors_and_code_blocks_are_ignored(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write(root, "README.md",
                  "[внешняя](https://example.invalid/docs/GONE.md) и [якорь](#раздел)\n\n"
                  "```markdown\n[внутри кода](docs/GONE.md)\n```\n")
            write(root, "docs/INDEX.md", "# Индекс\n")
            self.assertEqual([], check(root))

    def test_queue_and_journal_files_are_not_documentation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            write(root, "docs/INDEX.md", "# Индекс\n")
            write(root, "orchestrator/PROGRESS.md", "[журнал](docs/GONE.md)\n")
            write(root, "orchestrator/TASKS.md", "## A-1: Задача\ngate: skip\n```prompt\n[очередь](docs/GONE.md)\n```\n")
            self.assertEqual([], check(root))


if __name__ == "__main__":
    unittest.main()
