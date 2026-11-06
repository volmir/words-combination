"""Консервативные проверки собственных определений без морфоанализатора."""
import re
import unicodedata

from build_dictionary import fold


def shares_root(left, right):
    left, right = fold(left), fold(right)
    shorter = min(len(left), len(right))
    if shorter < 3:
        return left == right
    common = 0
    for a, b in zip(left, right):
        if a != b:
            break
        common += 1
    return common >= 4 or common / shorter >= 0.7


def normalize_form(text):
    # NFC сохраняет й; удаляются только отдельные знаки ударения/диакритики.
    return fold("".join(ch for ch in unicodedata.normalize("NFC", text) if not unicodedata.combining(ch)))


def tokens(text):
    return re.findall(r"[а-яё]+", normalize_form(text))


def leaks_answer(word, text, forms=()):
    forms = {normalize_form(form) for form in forms} | {fold(word)}
    return any(token in forms or shares_root(word, token) for token in tokens(text))


def shingles(text):
    text = " ".join(fold(text).split())
    return {text[index:index + 5] for index in range(max(0, len(text) - 4))}


def gloss_similarity(text, glosses):
    candidate = shingles(text)
    if not candidate:
        return 0.0
    return max((len(candidate & shingles(gloss)) / len(candidate) for gloss in glosses), default=0.0)


def definition_issues(word, text, forms, glosses, config, wiktionary=False):
    if text is None or not text.strip():
        return ["missing"]
    issues = []
    low, high = (15, 140) if wiktionary else (config["definition_min_chars"], config["definition_max_chars"])
    if not low <= len(text) <= high:
        issues.append("length")
    if not re.search(r"[а-яё]", text, re.I):
        issues.append("no_cyrillic")
    if any(ch.isalpha() and ch.lower() not in "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" for ch in text):
        issues.append("foreign_letters")
    if re.search(r"\d|[<>\r\n]", text):
        issues.append("digits_html_or_newline")
    if leaks_answer(word, text, forms):
        issues.append("answer_leak")
    if not wiktionary and gloss_similarity(text, glosses) >= config["gloss_similarity_cutoff"]:
        issues.append("wiktionary_overlap")
    return issues
