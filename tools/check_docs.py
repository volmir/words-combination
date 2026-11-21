#!/usr/bin/env python3
"""Лёгкие проверки документации для профиля docs.

Читаёт только файлы: кодировку Markdown, локальные ссылки на документы и
данные, а также карту документов docs/INDEX.md. Сгенерированный журнал и
очередь (PROGRESS.md, TASKS.md) не проверяются — их текст задаётся не вручную.
Без сети, SDK и внешних пакетов; команда профиля выполняется из корня
проекта: python3 tools/check_docs.py
"""
from pathlib import Path
import re
import sys

FENCE = re.compile(r"^\s*(?:```|~~~)")
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)\)")
INDEX_REFERENCE = re.compile(r"`([A-Za-z0-9_./-]+\.(?:md|json|txt))`")
EXTERNAL_PREFIXES = ("http://", "https://", "mailto:", "//")
CHECKED_SUFFIXES = (".md", ".json", ".txt")
IGNORED_NAMES = ("PROGRESS.md", "TASKS.md")
IGNORED_PARTS = (".runtime", "__pycache__")


def markdown_files(root):
    """Документы проекта: корневые, docs/ и orchestrator/, без служебных файлов."""
    candidates = [root / "README.md", root / "AGENTS.md"]
    for base in ("docs", "orchestrator"):
        directory = root / base
        if directory.is_dir():
            candidates.extend(sorted(directory.rglob("*.md")))
    return [path for path in candidates
            if path.is_file() and path.name not in IGNORED_NAMES
            and not set(IGNORED_PARTS) & set(path.parts)]


def without_code_blocks(text):
    lines, fenced = [], False
    for line in text.splitlines():
        if FENCE.match(line):
            fenced = not fenced
        elif not fenced:
            lines.append(line)
    return "\n".join(lines)


def link_problems(root, path, text):
    problems = []
    rel = path.relative_to(root)
    for match in LINK.finditer(without_code_blocks(text)):
        target = match.group(1).strip("<>")
        if target.startswith(EXTERNAL_PREFIXES) or target.startswith("#"):
            continue
        destination = target.split("#", 1)[0]
        if not destination or not destination.endswith(CHECKED_SUFFIXES):
            continue
        if not (path.parent / destination).exists():
            problems.append(f"{rel}: ссылка {target} не найдена")
    return problems


def index_problems(root):
    index = root / "docs" / "INDEX.md"
    if not index.is_file():
        return ["docs/INDEX.md: карта документов отсутствует"]
    try:
        text = index.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return []  # кодировка уже отмечена общей проверкой UTF-8
    problems = []
    for reference in sorted(set(INDEX_REFERENCE.findall(text))):
        if not (root / "docs" / reference).exists() and not (root / reference).exists():
            problems.append(f"docs/INDEX.md: {reference} не найден")
    return problems


def check(root):
    """Список проблем документации; пустой список — проверка пройдена."""
    root = Path(root)
    problems = []
    for path in markdown_files(root):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            problems.append(f"{path.relative_to(root)}: файл не в кодировке UTF-8")
            continue
        problems.extend(link_problems(root, path, text))
    problems.extend(index_problems(root))
    return sorted(problems)


def main():
    problems = check(Path(__file__).resolve().parent.parent)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        print(f"Провалено проверок документации: {len(problems)}", file=sys.stderr)
        return 1
    print("Документация в порядке: UTF-8, ссылки на документы и данные, docs/INDEX.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
