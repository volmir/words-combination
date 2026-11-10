"""Офлайн-эквивалент core HintText/DefinitionHint для подготовки данных.

Контракт закреплён общими синтетическими hint_vectors.json. В приложении
источник истины — Kotlin; этот модуль не меняет полные определения.
"""
import re
import unicodedata

MAX_LENGTH = 140
_LINK = re.compile(r"(?:https?://|www\.)\S+", re.I)
_LATIN_PARENTHESES = re.compile(r"\([^()]*[A-Za-z][^()]*\)")
_LABEL = re.compile(
    r"^(?:зоол|бот|биол|кул|кулин|разг|перен|шутл|книжн|устар|спец|собир|истор|поэт|прост|"
    r"неодобр|пренебр|ирон|ласк|обл|диал|тех|мед|хим|физ|мат|воен|жарг)\.\s*[,;]?\s*", re.I)
_TEMPLATE = re.compile(r"^(?:то же,?\s+что(?![А-Яа-яЁё])|(?:действие|свойство|состояние|качество)\s+по значению(?![А-Яа-яЁё]))", re.I)
_TOKEN = re.compile(r"[А-Яа-яЁё]+(?:[\u0300\u0301][А-Яа-яЁё]*)*")
_RELATIVE = re.compile(r"котор(?:ый|ая|ое|ые|ого|ой|ых|ому|ым|ыми|ую)")
_LEGACY_LABELS = re.compile(r"^(?:(?:разг|перен|книжн|устар|спец|собир|истор|поэт|прост)\.\s*[,;]?\s*)+")
_REFERENCE = re.compile(r"^(?:то же,? что\b|см\.|(?:уменьш|ласк|увелич)\.|форма\b|действие по значению|женск\.)")
_STOP = set("для при или это его еще как что так тот эта эти тем чем под над без между около возле через после перед также обычно один одна одно одного либо того этого когда если чтобы свой своей разг перен спец собир истор поэт книжн".split())


def clean(text):
    if not text.strip() or len(text) > 4000 or '<' in text or '>' in text or _LINK.search(text):
        return None
    result = _LATIN_PARENTHESES.sub(' ', unicodedata.normalize('NFC', text))
    result = re.sub(r'\s+', ' ', result).strip()
    for _ in range(4):
        result = _LABEL.sub('', result, count=1)
    while _TEMPLATE.match(result):
        result = result.partition(';')[2].strip()
        if not result:
            return None
        for _ in range(4):
            result = _LABEL.sub('', result, count=1)
    if not result:
        return None
    result = result[0].title() + result[1:]
    if len(result) > MAX_LENGTH:
        boundary = result.rfind(' ', 0, MAX_LENGTH)
        if boundary <= 0:
            return None
        result = result[:boundary].rstrip(' ,;:.!?—–-') + '…'
    return result


def _normalize(text):
    return unicodedata.normalize('NFC', text).replace('\u0300', '').replace('\u0301', '').lower().replace('ё', 'е')


def _shares_root(a, b):
    shorter = min(len(a), len(b))
    if shorter < 3:
        return bool(a) and a == b
    common = 0
    for left, right in zip(a, b):
        if left != right:
            break
        common += 1
    return common >= 4 or common / shorter >= 0.7


def prepare(answer, text, forms=(), related=()):
    result = clean(text)
    if result is None or _REFERENCE.match(_LEGACY_LABELS.sub("", _normalize(result))):
        return None
    family = {_normalize(word) for word in [answer, *forms, *related] if word}
    masked = 0

    def mask(match):
        nonlocal masked
        token = _normalize(match.group())
        if token in family or (token not in _STOP and not _RELATIVE.fullmatch(token)
                               and any(_shares_root(word, token) for word in family)):
            masked += 1
            return '***'
        return match.group()

    result = _TOKEN.sub(mask, result)
    remaining = [token for match in _TOKEN.finditer(result)
                 if len(token := _normalize(match.group())) >= 3
                 and token not in _STOP and not _RELATIVE.fullmatch(token)]
    if len(remaining) < 2 or sum(map(len, remaining)) < 12 or masked > len(remaining):
        return None
    return result
