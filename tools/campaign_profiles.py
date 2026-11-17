"""Версионированный реестр профилей кампании — единый источник чисел для GEN-02..05.

Все генераторы, валидация, симуляция и перебалансировка обязаны получать числа
профиля отсюда, а не из собственных копий констант. Поведение без профиля
(release-30, 30 уровней / 100 звёзд) остаётся в действующих конфигах и не
затрагивается этим модулем.
"""
import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path

from game_data_providers import json_digest

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'tools/campaign_profiles.json'
LEVEL_PROFILE_CONFIG = ROOT / 'tools/level_profile_config.json'
CATEGORIES = ROOT / 'tools/categories.json'

# Служебные и подозрительные слова, которые нельзя назначать загадками T4.
# Список не заменяет частотный/тематический фильтр, а закрывает известные
# ложные «существительные» очищенного словаря (местоимения, формы, шум).
T4_STOPWORDS = frozenset('''
есть они она оно така так том рад вид дело раз три два один это все всё как что кто где когда там тут уже еще ещё
или но да нет не ни же ли бы мы вы я он его ее её их нас вас себя свой своя свое своё этот тот та то те весь вся
какой такой чей который которые потому поэтому также тоже только даже ведь вот вон ну уж мол де ага ого под пот лет
эта эти этих тех этого эту этой этом этим ими нам вами нами мной тобой кем чем кого чего кому чему перед через между
над без для при про около после до от по из у к о об во на за с со а и абы дабы либо нежели чем
'''.split())


def sha256_file(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def load(path=CONFIG):
    document = json.loads(Path(path).read_text(encoding='utf-8'))
    if document.get('schema') != 'campaign-profiles-v1':
        raise ValueError('Неизвестная схема реестра профилей')
    if not isinstance(document.get('profiles'), dict) or not document['profiles']:
        raise ValueError('Реестр профилей пуст')
    for key, profile in document['profiles'].items():
        if not isinstance(profile, dict) or profile.get('id') != key:
            raise ValueError('Профиль не совпадает с ключом реестра: ' + str(key))
    return document


def get(name, path=CONFIG):
    """Проверенный профиль по ID; возвращает независимую копию."""
    document = load(path)
    if name not in document['profiles']:
        raise ValueError('Неизвестный профиль кампании: ' + str(name))
    profile = deepcopy(document['profiles'][name])
    validate_profile(profile)
    return profile


def names(path=CONFIG):
    return list(load(path)['profiles'])


def profiles_file_sha256(path=CONFIG):
    return sha256_file(path)


def _stages_cover(stages, levels):
    return ([s['first'] for s in stages] == [1, 4, 6, 9, 11, 14] and
            [s['last'] for s in stages] == [3, 5, 8, 10, 13, levels] and
            [s['number'] for s in stages] == list(range(1, 7)))


def _bands_cover(bands, levels):
    return ([b['first'] for b in bands] == [1, 4, 6, 9, 11, 14] and
            [b['last'] for b in bands] == [3, 5, 8, 10, 13, levels])


def expanded_stages(stages):
    result = []
    for stage in stages:
        result.extend((number, stage['number']) for number in
                      range(stage['first'], stage['last'] + 1))
    return result


def validate_profile(profile):
    """Полная проверка профиля включая вывод согласованности расписания."""
    levels = profile['levels']
    if type(levels) is not int or levels != 15:
        raise ValueError('Профиль поддерживает ровно 15 уровней')
    if type(profile.get('max_stars')) is not int or profile['max_stars'] != 49:
        raise ValueError('Ожидается 49 звёзд')
    stages = profile['stages']
    if (len(stages) != 6 or [s['number'] for s in stages] != list(range(1, 7)) or
            [s['last'] - s['first'] + 1 for s in stages] != [3, 2, 3, 2, 3, 2] or
            not _stages_cover(stages, levels)):
        raise ValueError('Нужны 6 этапов с длинами [3,2,3,2,3,2], покрывающие 1..15')
    for stage in stages:
        for key in ('number', 'first', 'last'):
            if type(stage[key]) is not int:
                raise ValueError('Неверная граница этапа: ' + key)
    bands = profile['bands']
    expected_bands = [(40, 60), (70, 85), (100, 120), (130, 160), (160, 200), (200, 260)]
    if (len(bands) != 6 or not _bands_cover(bands, levels) or
            [(b['min'], b['max']) for b in bands] != expected_bands):
        raise ValueError('Полосы узнаваемых ответов должны быть 40-60/70-85/100-120/130-160/160-200/200-260')
    for band in bands:
        if any(type(band[k]) is not int for k in ('first', 'last', 'min', 'max')) or not 0 < band['min'] <= band['max']:
            raise ValueError('Неверная полоса')
    rows = profile['schedule']
    if [r['first'] for r in rows] != [1, 4, 6, 9, 11, 14, 15] or [r['last'] for r in rows] != [3, 5, 8, 10, 13, 14, 15]:
        raise ValueError('Расписание должно покрывать уровни 1..15 без пропусков')
    total = 0
    counts = {}
    for row in rows:
        if any(type(row[k]) is not int for k in ('first', 'last', 'required')):
            raise ValueError('Неверная строка расписания')
        if not row['first'] <= row['last']:
            raise ValueError('Нарушен порядок уровней расписания')
        types = row['types']
        if not types or len(types) > 5 or len(set(types)) != len(types):
            raise ValueError('M должен быть 1..5 без повторений типов')
        if not set(types) <= {f'T{i}' for i in range(1, 8)}:
            raise ValueError('Неизвестный тип задания')
        if not 1 <= row['required'] <= len(types):
            raise ValueError('R должен быть 1..M')
        for t in types:
            counts[t] = counts.get(t, 0) + row['last'] - row['first'] + 1
        total += len(types) * (row['last'] - row['first'] + 1)
    if total != profile['max_stars']:
        raise ValueError('Число заданий не равно числу звёзд')
    if counts != {'T1': 15, 'T2': 12, 'T3': 9, 'T4': 6, 'T5': 5, 'T6': 1, 'T7': 1}:
        raise ValueError('Ожидались появления T1x15 T2x12 T3x9 T4x6 T5x5 T6x1 T7x1: ' + json.dumps(counts))
    required = [next(r['required'] for r in rows if r['first'] <= number <= r['last'])
                for number in range(1, levels + 1)]
    if required != [1, 1, 1, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4]:
        raise ValueError('Неверная схема R/S')
    master = [r['types'] for r in rows[-2:]]
    if sorted(master) != [['T1', 'T2', 'T3', 'T5', 'T7'], ['T1', 'T2', 'T4', 'T5', 'T6']]:
        # T3 финала живёт на уровне 15 (документалист), T4 — на уровне 14
        # (контрабандист): на контрабандисте нет цели T3 длины 9 с подсказкой.
        raise ValueError('Уровень 14 обязан нести T6 и T4, уровень 15 — T7 и T3, по 5 различных типов')
    if profile.get('t1_minimum') != [9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 24]:
        raise ValueError('Неверный ряд T1 minimum')
    t2 = profile['t2_minimum']
    if t2.get('first') != 4 or t2.get('values') != [3, 3, 4, 4, 5, 5, 5, 6, 6, 6, 7, 7]:
        raise ValueError('Неверный ряд T2 minimum для уровней 4..15')
    if profile.get('t3_min_lengths') != [6, 6, 6, 7, 7, 7, 8, 8, 8]:
        raise ValueError('Неверные ориентировочные длины T3')
    if profile.get('t4_riddles_by_stage') != {'4': 1, '5': 2, '6': 3}:
        raise ValueError('Неверное число целей T4 по этапам')
    if profile.get('t5_min_lengths') != [5, 5, 6, 7, 7]:
        raise ValueError('Неверные длины T5')
    if profile.get('t5_endings') != ['ок', 'ка', 'ние', 'ик', 'ист']:
        raise ValueError('T5 15K обязан использовать окончания [ок, ка, ние, ик, ист]')
    t4 = profile.get('t4') or {}
    if (t4.get('min_length') != 4 or t4.get('max_frequency_rank') != 5000 or
            t4.get('min_hint_length') != 20):
        raise ValueError('Неверные пороги качества T4')
    allowed = t4.get('allowed_targets') or []
    if (len(allowed) != 11 or len(set(allowed)) != 11 or
            any(not isinstance(w, str) or w != w.strip().lower() or len(w) < t4['min_length']
                for w in allowed)):
        raise ValueError('T4 обязан иметь 11 уникальных канонических целей 15K')
    t6 = profile.get('t6') or {}
    if t6.get('n') != 4 or t6.get('reserve_min') != 5 or not isinstance(t6.get('category'), str) or not t6['category'].strip():
        raise ValueError('T6 обязан требовать n=4, резерв 5 и реальную тему профессий')
    categories = json.loads(CATEGORIES.read_text(encoding='utf-8'))
    if t6['category'] not in categories:
        raise ValueError('Тема T6 отсутствует в tools/categories.json: ' + t6['category'])
    t7 = profile.get('t7') or {}
    if t7.get('n') != 8:
        raise ValueError('T7 обязан требовать n=8')
    jaccard = profile.get('jaccard') or {}
    if not jaccard.get('mean_max') == 0.07 or not jaccard.get('max_max') == 0.30 or jaccard.get('pairs') != 105:
        raise ValueError('Нужны raw Jaccard mean<=0.07, max<=0.30 на 105 парах')
    retain = profile.get('retain') or {}
    if retain.get('min') != 3 or retain.get('max') != 5:
        raise ValueError('Нужно сохранение 3..5 старых исходников')
    if profile.get('alphabet_required') is not False:
        raise ValueError('Покрытие алфавита отчётное, без требования 32/32')
    if profile.get('count_min_length') != 4 or profile.get('category_global_min') != 40:
        raise ValueError('Правила ввода и темы без изменений: count_min_length=4, category_global_min=40')
    reserve = profile.get('reserve_bands') or []
    if [(b['first'], b['last']) for b in reserve] != [(1, 5), (6, 10), (11, 15)] or \
            [(b['numerator'], b['denominator']) for b in reserve] != [(27, 20), (27, 20), (27, 20)]:
        raise ValueError('Неверные резервные доли 15K')
    overrides = profile.get('reserve_type_overrides') or {}
    if overrides != {'T5': {'numerator': 1, 'denominator': 1},
                     'T6': {'numerator': 1, 'denominator': 1}}:
        raise ValueError('T5/T6 должны иметь резерв ровно n')
    thresholds = profile.get('thresholds') or {}
    reference = json.loads(LEVEL_PROFILE_CONFIG.read_text(encoding='utf-8'))
    for key in ('source_min_length', 'source_max_length', 'answer_min_length', 'counted_min_length',
                'min_observed_occurrences', 'semantic_common_min_occurrences', 'semantic_common_max_rank'):
        if thresholds.get(key) != reference[key]['value']:
            raise ValueError('Порог профиля расходится с level_profile_config.json: ' + key)
    targets = profile.get('simulation_targets') or []
    if [t['level'] for t in targets] != list(range(1, levels + 1)):
        raise ValueError('Нужны явные цели симуляции для всех 15 уровней')
    if (targets[0]['required_min'] != .95 or targets[-1]['required_min'] != .8 or
            targets[0]['master_min'] != .70 or targets[-1]['master_max'] != .25):
        raise ValueError('Края целей симуляции расходятся с планкой финала 4/5')
    for t in targets:
        if not 0 <= t['master_min'] <= t['master_max'] <= 1 or not 0 <= t['required_min'] <= 1:
            raise ValueError('Неверная целевая вероятность')
        if t['level'] <= 5 and t['master_max'] < t['required_min']:
            raise ValueError('M=R: потолок Мастера не может быть ниже пола R')
    if any(a['required_min'] < b['required_min'] for a, b in zip(targets, targets[1:])):
        raise ValueError('Цели R должны убывать по уровням')
    if any(a['master_min'] < b['master_min'] for a, b in zip(targets, targets[1:])):
        raise ValueError('Цели Мастера должны монотонно убывать')
    return True


def stage_of(profile, level):
    for stage in profile['stages']:
        if stage['first'] <= level <= stage['last']:
            return stage['number']
    raise ValueError('Уровень вне профиля: ' + str(level))


def row_of(profile, level):
    for row in profile['schedule']:
        if row['first'] <= level <= row['last']:
            return row
    raise ValueError('Уровень вне расписания: ' + str(level))


def types_of(profile, level):
    return row_of(profile, level)['types']


def required_of(profile, level):
    return row_of(profile, level)['required']


def band_of(profile, level):
    for band in profile['bands']:
        if band['first'] <= level <= band['last']:
            return band['min'], band['max']
    raise ValueError('Уровень вне полос: ' + str(level))


def t2_minimum(profile, level):
    minimum = profile['t2_minimum']
    index = level - minimum['first']
    if index < 0 or index >= len(minimum['values']):
        raise ValueError('T2 minimum определён только для уровней 4..15')
    return minimum['values'][index]


def reserve_fraction(profile, level, task_type=None):
    overrides = profile.get('reserve_type_overrides') or {}
    if task_type in overrides:
        item = overrides[task_type]
        return item['numerator'], item['denominator']
    for band in profile['reserve_bands']:
        if band['first'] <= level <= band['last']:
            return band['numerator'], band['denominator']
    raise ValueError('Уровень вне резервных полос: ' + str(level))


def reserve_size(profile, level, needed, task_type=None):
    numerator, denominator = reserve_fraction(profile, level, task_type)
    return (needed * numerator + denominator - 1) // denominator


def t3_length(profile, appearance_zero_based):
    return profile['t3_min_lengths'][appearance_zero_based]


def t5_length(profile, appearance_zero_based):
    return profile['t5_min_lengths'][appearance_zero_based]


def t5_endings(profile):
    return list(profile.get('t5_endings') or [])


def t4_policy(profile):
    return deepcopy(profile.get('t4') or {})


def t6_reserve(profile):
    return int((profile.get('t6') or {}).get('reserve_min', profile['t6']['n']))


def expected_stages(profile):
    return [dict(number=s['number'], firstLevel=s['first'], lastLevel=s['last'])
            for s in profile['stages']]


def expected_required(profile):
    return [required_of(profile, number) for number in range(1, profile['levels'] + 1)]


def digest(profile):
    return json_digest(profile)


def selection_config_for(name, base, path=CONFIG):
    """Эффективный конфиг GEN-02 из профиля + неизменные лимиты поиска/планирования.

    Возвращает копию; базовый конфиг (tools/level_selection_config.json) не меняется.
    Числа заданий (T1/T2/T3/T5/T6/T7) вкладываются в конфиг, чтобы сгенерированное
    предложение GEN-02 оставалось воспроизводимым вместе с профилем.
    """
    profile = get(name, path)
    config = deepcopy(base)
    config['profile_id'] = profile['id']
    config['profile_sha256'] = digest(profile)
    config['profile_thresholds'] = deepcopy(profile['thresholds'])
    config['profile_citations'] = dict(
        t6_category=profile['t6']['category'], t6_reserve_min=profile['t6'].get('reserve_min'),
        t7_n=profile['t7']['n'], t1_minimum=profile['t1_minimum'],
        t2_minimum=profile['t2_minimum'], t3_min_lengths=profile['t3_min_lengths'],
        t5_min_lengths=profile['t5_min_lengths'], t5_endings=profile.get('t5_endings'),
        t4_riddles_by_stage=profile['t4_riddles_by_stage'],
        t4_allowed_targets=(profile.get('t4') or {}).get('allowed_targets'))
    config['levels'] = 15
    config['bands'] = [dict(first=b['first'], last=b['last'], min=b['min'], max=b['max'])
                       for b in profile['bands']]
    config['retain_min'] = profile['retain']['min']
    config['retain_max'] = profile['retain']['max']
    config['mean_jaccard_max'] = profile['jaccard']['mean_max']
    config['max_jaccard_max'] = profile['jaccard']['max_max']
    config['master_types'] = [deepcopy(row['types']) for row in profile['schedule'][-2:]]
    config['level_types'] = [deepcopy(types_of(profile, number))
                             for number in range(1, profile['levels'] + 1)]
    config['alphabet_required'] = profile['alphabet_required']
    # GEN-02 планирует T3/T5 заранее. Берём самые длинные требования профиля:
    # исходник, прошедший планирование, выдержит и ранние, и поздние появления.
    if 'planning' not in config or 'search' not in config:
        raise ValueError('Базовый конфиг подбора неполный')
    config['planning'] = deepcopy(config['planning'])
    config['planning']['long_min_length'] = max(profile['t3_min_lengths'])
    config['planning']['suffix_min_length'] = max(profile['t5_min_lengths'])
    config['planning']['profile_rule'] = ('T3/T5 планируются по максимальной длине профиля; '
                                         'сами задания получают точные длины из schedule.')
    return config


def simulation_config_for(name, base, path=CONFIG):
    """Эффективный конфиг GEN-04/05: общая модель и бюджеты, цели из профиля."""
    profile = get(name, path)
    config = deepcopy(base)
    config['profile'] = dict(id=profile['id'], digest=digest(profile),
                             levels=profile['levels'], max_stars=profile['max_stars'],
                             alphabet_required=profile['alphabet_required'],
                             thresholds=deepcopy(profile['thresholds']))
    config['targets'] = deepcopy(profile['simulation_targets'])
    config['levels'] = profile['levels']
    config['max_stars'] = profile['max_stars']
    return config


def source_hashes(path=CONFIG, words=None, levels=None, manifest=None, lexical_data=None):
    """SHA-256 входов, от которых зависит профиль и его проверки.

    Пути можно переопределить для staged-словаря; по умолчанию остаются data/.
    """
    paths = {
        'campaign_profiles': Path(path),
        'level_profile_config': LEVEL_PROFILE_CONFIG,
        'level_selection_config': ROOT / 'tools/level_selection_config.json',
        'campaign_config': ROOT / 'tools/campaign_config.json',
        'campaign_simulation_config': ROOT / 'tools/campaign_simulation_config.json',
        'categories': CATEGORIES,
        'words': Path(words) if words is not None else ROOT / 'data/words.txt',
        'levels': Path(levels) if levels is not None else ROOT / 'data/levels.json',
        'manifest': Path(manifest) if manifest is not None else ROOT / 'data/manifest.json',
        'lexical_data': (Path(lexical_data) if lexical_data is not None
                           else ROOT / 'data/lexical_data.json'),
    }
    return {key: sha256_file(file) for key, file in paths.items() if Path(file).exists()}


def diagnostics(name, path=CONFIG):
    """Воспроизводимый JSON готовности профиля: ID, digest, SHA входов и пороги."""
    profile = get(name, path)
    thresholds = profile['thresholds']
    return {
        'schema': 'campaign-profile-diagnostic-v1',
        'profile': {
            'id': profile['id'],
            'digest': digest(profile),
            'levels': profile['levels'],
            'max_stars': profile['max_stars'],
            'stages': [dict(first=s['first'], last=s['last'], length=s['last'] - s['first'] + 1)
                       for s in profile['stages']],
        },
        'source_sha256': source_hashes(path),
        'thresholds': {
            'source': {
                'min_length': thresholds['source_min_length'],
                'max_length': thresholds['source_max_length'],
            },
            'target': {
                'answer_min_length': thresholds['answer_min_length'],
                'counted_min_length': thresholds['counted_min_length'],
                'min_observed_occurrences': thresholds['min_observed_occurrences'],
            },
        },
        'schedule': [dict(first=row['first'], last=row['last'], types=row['types'],
                          required=row['required']) for row in profile['schedule']],
        'official_gen02': {
            'status': 'profile_ready_for_official_gen02',
            'candidate_installed': False,
            'campaign_validated': False,
            'next_task': 'SHORT15-02',
            'note': 'Профиль проверен; официальный прогон GEN-02 на закреплённом словаре — SHORT15-02. '
                    'Кампания не выпущена и не установлена.',
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--diagnostics', metavar='PROFILE', choices=names())
    args = parser.parse_args()
    if args.diagnostics:
        print(json.dumps(diagnostics(args.diagnostics), ensure_ascii=False, indent=2, sort_keys=True))
    else:
        print(json.dumps(load(), ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
