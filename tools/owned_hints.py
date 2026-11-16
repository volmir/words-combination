"""Offline LCH-02 candidate validation; never installs data or prints imported text.

Authored input lives in docs/owned_hints.json, outside generated data/. The old
SQLite text is read only by compare_imported(), after validation of that input.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata

from tools.hint_text import prepare
from tools.install_short15_package import verify_installed

ROOT = Path(__file__).resolve().parents[1]


def norm(value):
    return unicodedata.normalize('NFC', value).lower().replace('ё', 'е').replace('\u0301', '').replace('\u0300', '')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def targets(campaign):
    """All designated semantic targets; no arbitrary truncation of T6.words."""
    result = defaultdict(list)
    for level in campaign['levels']:
        for task in level['tasks']:
            kind = task['type']
            field = {'T3': 'hintTarget', 'T4': 'targets', 'T6': 'words'}.get(kind)
            if not field:
                continue
            words = [task[field]] if kind == 'T3' else task[field]
            if len(set(map(norm, words))) != len(words):
                raise ValueError('Duplicate target in ' + task['id'])
            for word in words:
                result[norm(word)].append(dict(level=level['number'], task_id=task['id'],
                                              type=kind, selection_source='campaign.' + field))
    return dict(sorted(result.items()))


def validate(candidate, campaign, campaign_sha, words, metadata):
    def require(condition, message):
        if not condition:
            raise ValueError(message)
    require(candidate['schema'] == 'owned-game-hints' and candidate['version'] == 1, 'schema/version')
    require(set(candidate) == {'schema', 'version', 'campaign_sha256', 'status', 'provenance',
                               'license_review', 'proposed_license', 'authoring_inputs', 'entries'},
            'Unexpected candidate fields')
    require(candidate['authoring_inputs'] == ['word', 'part_of_speech=noun', 'task_type',
                                             'general-linguistic-knowledge'], 'Authoring inputs')
    require(candidate['campaign_sha256'] == campaign_sha, 'Stale campaign SHA')
    require(candidate['status'] == 'candidate-not-installed', 'Candidate status')
    require(candidate['proposed_license'] == 'CC0-1.0', 'Proposed license')
    require(candidate['provenance'] == 'newly-authored-for-game', 'Candidate provenance')
    require(candidate['license_review'] == 'pending-owner-approval', 'Candidate license review')
    expected = targets(campaign)
    entries = candidate['entries']
    keys = [e['word'] for e in entries]
    require(keys == sorted(set(keys)), 'Duplicate or unsorted words')
    require(set(keys) == set(expected), 'Missing or extra semantic target')
    levels = {level['number']: level for level in campaign['levels']}
    for entry in entries:
        require(set(entry) == {'word', 'normalized', 'part_of_speech', 'hint', 'roles', 'provenance',
                              'license_review', 'review_note', 'owner_text_approval', 'owner_license_approval'},
                'Unexpected entry fields')
        word, hint = entry['word'], entry['hint']
        require(word == norm(word) == entry['normalized'], 'Non-normalized word')
        require(word in words and len(word) >= 3, 'Word not in index: ' + word)
        require(entry['part_of_speech'] == 'noun', 'Expected noun')
        require(entry['roles'] == expected[word], 'Roles differ: ' + word)
        require(entry['review_note'].strip(), 'Missing review note')
        for key, value in [('provenance', 'newly-authored-for-game'),
                           ('license_review', 'pending-owner-approval'),
                           ('owner_text_approval', 'pending'), ('owner_license_approval', 'pending')]:
            require(entry[key] == value, key + ': ' + word)
        require(15 <= len(hint) <= 140 and hint == hint.strip(), 'Empty/long hint: ' + word)
        require(not re.search(r'https?://|www\.|[<>]|\*\*\*|TODO|TBD|заглушк', hint, re.I), 'Unwanted text')
        forms, related = metadata.get(word, {}).get('forms', []), metadata.get(word, {}).get('related', [])
        require(prepare(word, hint, forms, related) == hint, 'Hint masked/rejected: ' + word)
        # Explicit non-prefix families and constituents of compounds missed by the heuristic.
        roots = {'километр': ['кило', 'метр'], 'антитело': ['антител', 'тельц'],
                 'бортник': ['борт'], 'радист': ['радио'], 'дантист': ['дант'],
                 'акционер': ['акци'], 'адресок': ['адрес']}.get(word, [])
        require(not any(root in norm(hint) for root in roots), 'Related root: ' + word)
        for role in entry['roles']:
            level = levels[role['level']]
            task = next(t for t in level['tasks'] if t['id'] == role['task_id'])
            require(word != norm(level['word']) and not (Counter(word) - Counter(norm(level['word']))),
                    'Not buildable: ' + word)
            if role['type'] == 'T3':
                require(len(word) >= task['minLength'], 'T3 length')
            if role['type'] == 'T6':
                require(metadata[word]['category'] == task['category'], 'T6 category: ' + word)
                require(metadata[word]['category_source'] and not metadata[word]['category_source'].startswith('unknown:'),
                        'Unverifiable category: ' + word)
    require(len({norm(e['hint']) for e in entries}) == len(entries), 'Duplicate hints')
    return expected


def tokens(text):
    return re.findall('[а-я]+', norm(text))


def compare_imported(entries, old_rows):
    """Detect verbatim copying across ALL imported senses, never return their text.

    Flag exact normalized equality, a full short hint (>=3 tokens) embedded in
    an old sense, or any shared run of >=5 tokens. Generic shorter overlaps
    are not proof of copying; this is a screening rule, not a rights audit.
    """
    index = defaultdict(set)
    full = defaultdict(set)
    short = defaultdict(set)
    count = 0
    for old_word, old_text in old_rows:
        count += 1
        ts = tokens(old_text)
        full[tuple(ts)].add(old_word)
        for size, dest in [(5, index), (3, short), (4, short)]:
            for i in range(len(ts) - size + 1):
                dest[tuple(ts[i:i + size])].add(old_word)
    matches = []
    for entry in entries:
        ts = tokens(entry['hint'])
        hits = set(full.get(tuple(ts), ()))
        if 3 <= len(ts) <= 4:
            hits.update(short.get(tuple(ts), ()))
        for i in range(len(ts) - 4):
            hits.update(index.get(tuple(ts[i:i + 5]), ()))
        if hits:
            matches.append({'word': entry['word'], 'matched_old_words': sorted(hits)})
    return {'old_rows_checked': count, 'threshold': 'exact/full-short-3-4-tokens/or-consecutive-5-tokens',
            'suspicious_matches': matches}


def load_metadata(db):
    return {w: dict(category=c, category_source=s, forms=json.loads(f), related=json.loads(r))
            for w, c, s, f, r in db.execute('SELECT word, category, category_source, forms, related FROM word_metadata')}


def render(candidate, campaign, words, metadata, comparison, hashes):
    entries = candidate['entries']
    scope = ['# LCH-02 — реестр целей', '',
             'Источник истины — установленный `data/campaign.json`, не curation_snapshot.json.', '',
             'SHORT15-05 и CUR-03 завершены; SHA установленного пакета совпадают с CUR03_ACCEPTANCE.md. '
             'Свежая verify_installed проверяет связи manifest и файлов; production-тесты проверяют действующую кампанию.', '',
             '39 уникальных целей / 40 назначений: 9 T3, 11 T4, 20 T6. '
             '«Артист» объединяет T4/T6. Ориентир 25/11 не использован как квота: '
             'HintRules.selectTarget допускает любую из 20 профессий, поэтому каждая включена один раз. '
             'T6 n=4 не ограничивает список допустимых слов и покрытие текстами. '
             'T1/T2/T5/T7 — открытые задания без назначенного смыслового ответа; их словарь сюда не включён.', '',
             'Набор — кандидат вне data/, он не подключён к DefinitionsRepository и не устанавливает кампанию. '
             'Действующая БД и поведение HintLadder остаются прежними.', '',
             '## Проверенные входы', '', '| Файл | SHA-256 |', '|---|---|']
    scope += [f'| `{name}` | `{value}` |' for name, value in hashes.items()]
    scope += ['', '## Полный перечень', '',
              '| Слово | Нормализовано | Уровень / задание | Тип | T4 обязательна | Источник выбора | В WordIndex |',
              '|---|---|---|---|---|---|---|']
    for e in entries:
        roles = e['roles']
        scope.append(f"| {e['word']} | {e['normalized']} | " + ', '.join(f"{r['level']} / {r['task_id']}" for r in roles) +
                     ' | ' + ', '.join(r['type'] for r in roles) + ' | ' + ('да' if any(r['type']=='T4' for r in roles) else 'нет') +
                     ' | ' + ', '.join(r['selection_source'] for r in roles) + ' | да |')
    scope += ['', '## Полная категория T6', '',
              'Уровень 14, `l14-t6`, `professions`, n=4. Таблица независима от числа подсказок. '
              'Все слова составимы из «контрабандист», включены в индекс и проверены по word_metadata. '
              'Категория — принятое владельцем множество занятий; оно включает исторические занятия, '
              'духовные саны и спортивные специализации.', '',
              '| Слово | Категория | Проверяемый источник | Есть текст |', '|---|---|---|---|']
    for l in campaign['levels']:
        for t in l['tasks']:
            if t['type'] == 'T6':
                scope += [f"| {w} | {metadata[w]['category']} | {metadata[w]['category_source']} | да |" for w in sorted(t['words'])]
    review = ['# LCH-02 — тексты для проверки владельцем', '',
              'Все тексты заново составлены в этой сессии по слову, части речи (существительное), типу задания '
              'и общим языковым знаниям. Старые raw_text/safe_preview и статьи не использовались как вход сочинения. '
              'Сначала записан полный docs/owned_hints.json; только затем запущено программное сравнение с БД. '
              'Импортированные формулировки не выводятся моделью или валидатором.', '',
              'Статус каждого текста и лицензии: pending-owner-approval. CC0-1.0 предложена как вариант для подтверждения владельцем; '
              'юридическое авторство и окончательная лицензия не утверждаются. audit.passed и rights_review.passed не менялись. '
              'Этот документ не разрешает публикацию.', '',
              'Автоматическая проверка подтверждает составимость, присутствие в индексе, роли, нормализацию, длину, '
              'сохранение текста обработчиком DefinitionHint без масок, формы/родство и отдельные сложные корни. '
              'Редакторская проверка не нашла оскорбительных формулировок или ненужных оценок. '
              'Это не доказательство единственности ответа: в игре текст дополняется буквами исходника и лестницей подсказок. '
              'Пограничные значения отмечены ниже; ручное утверждение владельца не выполнено.', '',
              f"Сравнено {comparison['old_rows_checked']} старых записей по всей БД; подозрительных совпадений: {len(comparison['suspicious_matches'])}. "
              'Критерий: равенство токенов, включение короткого текста целиком (3–4 токена) или общая последовательность ≥5 токенов. '
              'Порог обнаруживает дословные фрагменты, но не доказывает правовой статус и не обнаруживает все парафразы.', '',
              '| Цель | Роли | Предлагаемый текст | Пограничный случай | Текст: решение / кто / дата | Лицензия: решение / кто / дата |',
              '|---|---|---|---|---|---|']
    for e in entries:
        review.append(f"| {e['word']} | " + ', '.join(r['task_id'] for r in e['roles']) + f" | {e['hint']} | {e['review_note']} | pending / — / — | pending / — / — |")
    review += ['', '## Воспроизведение', '',
               '`python3 -m tools.owned_hints --write-docs` — проверка кандидата, read-only сравнение со старой БД и детерминированная запись этих двух документов. '
               'Без --write-docs документы лишь сверяются с ожидаемыми байтами. Сочинение текстов команда не выполняет.', '',
               'Фикстуры: `python3 -m unittest tools.tests.test_owned_hints`. Полный gate выполняет внешний оркестратор. '
               'Подключение кандидата к игре, замена БД, аудит прав и публикация находятся за пределами LCH-02.']
    return '\n'.join(scope)+'\n', '\n'.join(review)+'\n'


def check(root=ROOT, write_docs=False):
    root = Path(root)
    verify_installed(root/'data')
    paths = {name: root/'data'/name for name in ['campaign.json', 'words.txt', 'manifest.json', 'definitions.db']}
    hashes = {name: sha(path) for name, path in paths.items()}
    manifest = json.loads(paths['manifest.json'].read_text(encoding='utf-8'))
    if manifest.get('definitions', {}).get('rights_policy') == 'owned-minimal-hints-v1':
        # LCH-02 documents are historical evidence bound to the previous DB.
        # Current data is checked against the same authored candidate, lexical
        # snapshot and recorded screening; never compare our text with itself.
        from tools.build_owned_hints import verify, inputs
        verify(root / 'data', root / 'docs/owned_hints.json', root / 'tools/owned_hint_index.json')
        candidate, index, _, _ = inputs(root / 'data', root / 'docs/owned_hints.json', root / 'tools/owned_hint_index.json')
        acceptance = (root / 'docs/CUR03_ACCEPTANCE.md').read_text(encoding='utf-8')
        if index['source_database_sha256'] not in acceptance:
            raise ValueError('Category snapshot not bound to CUR-03')
        return dict(unique_targets=len(candidate['entries']), assignments=sum(len(e['roles']) for e in candidate['entries']),
                    t4=sum(r['type'] == 'T4' for e in candidate['entries'] for r in e['roles']),
                    campaign_sha256=hashes['campaign.json'], **index['screening'])
    # CUR-03 evidence is bound to this installed package, not an old workbook snapshot.
    acceptance = (root/'docs/CUR03_ACCEPTANCE.md').read_text(encoding='utf-8')
    if not all(value in acceptance for value in hashes.values()):
        raise ValueError('Installed package differs from CUR-03; reassess acceptance')
    campaign = json.loads(paths['campaign.json'].read_text(encoding='utf-8'))
    words = set(paths['words.txt'].read_text(encoding='utf-8').splitlines())
    if 'менталист' in words or campaign['levels'][14]['tasks'][2]['hintTarget'] != 'антитело':
        raise ValueError('SHORT15-05 conditions not installed')
    candidate = json.loads((root/'docs/owned_hints.json').read_text(encoding='utf-8'))
    with sqlite3.connect(paths['definitions.db'].resolve().as_uri()+'?mode=ro', uri=True) as db:
        metadata = load_metadata(db)
        for level in campaign['levels']:
            for task in level['tasks']:
                if task['type'] != 'T6':
                    continue
                local_category = {word for word in words if word != level['word']
                                  and not (Counter(word) - Counter(level['word']))
                                  and metadata.get(word, {}).get('category') == task['category']}
                if local_category != set(task['words']):
                    raise ValueError('T6 differs from full independently recounted category')
        validate(candidate, campaign, hashes['campaign.json'], words, metadata)
        comparison = compare_imported(candidate['entries'], db.execute('SELECT word, text FROM definitions'))
    if comparison['suspicious_matches']:
        raise ValueError(json.dumps(comparison, ensure_ascii=False))
    scope, review = render(candidate, campaign, words, metadata, comparison, hashes)
    for name, content in [('OWN_HINTS_SCOPE.md', scope), ('OWN_HINTS_REVIEW.md', review)]:
        path = root/'docs'/name
        if write_docs:
            path.write_text(content, encoding='utf-8')
        elif not path.exists() or path.read_text(encoding='utf-8') != content:
            raise ValueError('Stale generated document: ' + name)
    if any(sha(paths[name]) != value for name, value in hashes.items()):
        raise ValueError('Input changed during validation')
    return dict(unique_targets=len(candidate['entries']), assignments=sum(len(e['roles']) for e in candidate['entries']),
                t4=sum(r['type']=='T4' for e in candidate['entries'] for r in e['roles']),
                campaign_sha256=hashes['campaign.json'], **comparison)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write-docs', action='store_true')
    args = parser.parse_args()
    print(json.dumps(check(write_docs=args.write_docs), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
