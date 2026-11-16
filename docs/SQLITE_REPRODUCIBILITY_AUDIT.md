# PRE-03: диагностика воспроизводимости SQLite

Дата: 2026-11-16. Режим: только анализ. Исследуемый Git commit:
`e38db5c319557aa278e8e987a81ed24ead26ea5d` (до добавления этого отчёта).
Рабочее дерево перед диагностикой было чистым.

## Вывод

В текущей среде утверждённая БД воспроизводится побайтно, `--check` и строгий
Python release-аудит проходят. При выполнении той же последовательности SQL
другой сборкой SQLite получена логически идентичная БД с другим бинарным SHA.
Это подтверждает класс A (представление), а не класс B (изменение схемы/данных).
Историческое несовпадение на SQLite 3.40.1 отдельно не воспроизведено.

Для сохранения существующего строгого gate рекомендуется вариант A: закрепить
проверенную среду Python + SQLite, включая сборку библиотеки и настройки.
Изменять утверждённую БД, её SHA или политику аудита для этого не требуется.
Вариант B полезен как дополнительная диагностика содержимого, но сам по себе
не устраняет несовместимость строгого `--check` с другими версиями SQLite.
Никакие изменения сборочного процесса этим заданием не внесены.

## Среды

| Компонент | Значение / статус |
| --- | --- |
| ОС | Linux x86_64, kernel 6.8.0-149-generic, glibc 2.35 |
| Python | `/usr/bin/python3`, 3.10.12 |
| Python-модуль `sqlite3` | 2.6.0 (`sqlite3.version`; это версия обёртки) |
| SQLite, используемый Python | 3.37.2 (`sqlite3.sqlite_version`) |
| SQLite source id в Python | `2022-01-06 13:25:41 872ba256cbf61d9290b571c0e6d82a20c224ca3ad82971edc46b29818d5dalt1` |
| Системный CLI | `/usr/bin/sqlite3`, 3.37.2, тот же source id |
| CLI из Android SDK | `/home/vladimir/.local/share/words-combination/android-sdk/platform-tools/sqlite3`, 3.50.6, 64-bit |
| SQLite source id SDK CLI | `2025-09-22 18:50:24 9cc554372371e07bf01d8070a64d461ac85b91be6eef0e642ed129a0c7fa8105` |
| SQLite 3.40.1 | **NOT RUN**: отдельная среда этой версии не была доступна для проверки |
| Python, связанный с SQLite 3.50.6 | **NOT RUN**: второй Python runtime не использовался; выполнен SQL replay через CLI |

Важна версия библиотеки в Python, а не первая `sqlite3` в PATH: `command -v sqlite3`
здесь указывает на SDK 3.50.6, хотя генератор использует 3.37.2.
`PRAGMA compile_options` у системной библиотеки сообщает, в частности,
`DEFAULT_PAGE_SIZE=4096`, `SECURE_DELETE`, `THREADSAFE=1`, `COMPILER=gcc-11.4.0`.
SDK CLI возвращает пустой список compile options; одинаковость конфигураций
сборки не установлена. `secure_delete` в обеих проверенных средах равен 1.

## Выполненные команды и метод

Команды запускались из корня проекта. `pwd` разрешает путь проекта как
`/home/vladimir/www/words-combination` (путь задания `/var/www/words-combination`).

```bash
git rev-parse HEAD
git status --short
python3 --version
command -v sqlite3
sqlite3 --version
/usr/bin/sqlite3 --version
python3 -c 'import sqlite3,sys,platform; print(sys.executable); print(platform.platform()); print(sqlite3.version); print(sqlite3.sqlite_version); print(sqlite3.connect(":memory:").execute("select sqlite_source_id()").fetchone()[0])'
sha256sum data/definitions.db
python3 tools/build_owned_hints.py --check
python3 tools/build_game_data.py --check-release --out data
mktemp -d /tmp/pre03-sqlite-XXXXXXXX
python3 /tmp/pre03-sqlite-UuG1Fzjs/audit.py
```

Последняя команда выполнялась дважды: сначала для основной пары сред, затем
с дополнительными контролями CLI 3.37.2 и `auto_vacuum=0` для CLI 3.50.6.
Перед повторным SQL replay удалялись только `cli.db` и `cli-repeat.db` в `/tmp`.
Исходная БД открывалась только с `mode=ro`; `generate(data, output)` записывал
новые БД исключительно в изолированный `/tmp/pre03-sqlite-UuG1Fzjs`.
Режим установки `build_owned_hints.py` без `--check` не запускался.
Дамп Викисловаря и сеть для генерации не требовались и не использовались.

`--check`: exit 0, результат:

```json
{"own":39,"wiktionary":0,"t4":11,"category_index_sha256":"81d4c21302c54a969466c289fa950e7d255d3eb35a5b7bb641b4f8facb392335","publication_status":"approved"}
```

`--check-release --out data`: exit 0,
`{"release_audit":"ok","publishable":true}`. Это результат существующего
валидатора, а не разрешение публикации и не свидетельство сборки APK.

У нативного `generate` через `set_trace_callback` записаны все 20 173 фактически
исполненных SQL-оператора с раскрытыми параметрами, включая `BEGIN`, `COMMIT`,
`PRAGMA user_version` и `VACUUM`. Этот SQL выполнен через `-batch -bail` в новых
БД обоими CLI. SHA-256 SQL-файла:
`227d711f2651c8fafb8dae599d4cb6a674aab6b5f05e22327009f202d77f33c9`.
Контрольный replay в CLI 3.37.2 дал исходный SHA: способ replay сам по себе
в этой среде не изменяет результат. Replay 3.50.6 проверяет SQL-сборку другой
библиотекой, но не является запуском полного Python `--check` на ней.

## Бинарные SHA-256

Обозначения ниже используются в сравнительной таблице.

- H0: `73d09ac2d445ad7f4c30226248b30769d6e46bd6858a8907750d16561b258d7d`
- H1: `a5efe41d4237613c0e18e1d2cd841c868dfa4a464a1ba6a88dc7ddda83327112`
- H2: `470ea48563e1f14c54e80ee148abf404112ae4255a49de14d4c4802f320f1683`

| Образец | SQLite / способ | SHA | Размер, байт | auto_vacuum | Страниц | Повтор |
| --- | --- | --- | ---: | ---: | ---: | --- |
| Утверждённая `data/definitions.db` | заголовок: 3.37.2 | H0 | 1 818 624 | 0 | 444 | исходник |
| `native.db` | Python / 3.37.2 | H0 | 1 818 624 | 0 | 444 | `native-repeat.db`: H0 |
| `cli337.db` | SQL replay / 3.37.2 | H0 | 1 818 624 | 0 | 444 | один контроль |
| `cli.db` | SQL replay / 3.50.6, штатные настройки CLI | H1 | 1 822 720 | 1 | 445 | `cli-repeat.db`: H1 |
| `cli350-av0.db` | SQL replay / 3.50.6, перед созданием `PRAGMA auto_vacuum=0` | H2 | 1 818 624 | 0 | 444 | `cli350-av0-repeat.db`: H2 |

## Схема, содержимое и целостность

Все восемь файлов (исходник и семь контрольных) прочитаны системным Python SQLite
3.37.2. Сравнение выполнялось по полным значениям, а не только по хэшам.

| Проверка | Исходная БД | Нативная / CLI 3.37.2 | Оба режима CLI 3.50.6 |
| --- | --- | --- | --- |
| `PRAGMA integrity_check` | `ok` | `ok` | `ok` |
| `PRAGMA user_version` | 10 | 10 | 10 |
| `sqlite_schema` (type, name, tbl_name, SQL), включая отсутствие дополнительных объектов | 3 таблицы, 3 индекса | полное совпадение | полное совпадение |
| `PRAGMA table_xinfo` для каждой таблицы | колонки, типы, PK, NULL/default/hidden | полное совпадение | полное совпадение |
| `PRAGMA index_list` и `index_xinfo` | 1 явный и 2 автоиндекса | полное совпадение | полное совпадение |
| `definitions`, все 12 колонок | 39 записей | полное совпадение | полное совпадение |
| `word_metadata`, все 5 колонок | 20 120 записей | полное совпадение | полное совпадение |
| `build_metadata`, обе колонки | 6 записей | полное совпадение | полное совпадение |
| Происхождение подсказок | own=39, wiktionary=0 | совпадает | совпадает |
| Категории, category_source, forms, related | 38 представленных категорий | полное совпадение | полное совпадение |
| Канонически отсортированное содержимое + логическая схема | digest ниже | полное совпадение | полное совпадение |
| page_size / encoding / freelist_count | 4096 / UTF-8 / 0 | совпадает | совпадает |
| application_id / schema_version | 0 / 5 | совпадает | совпадает |

Таблицы:

- `definitions`: id, word, display, ord, text, notes, antonym, example, source,
  origin, category, guessable; `id INTEGER PRIMARY KEY`, проверка `guessable IN (0,1)`.
- `word_metadata`: word, category, category_source, forms, related;
  `word TEXT PRIMARY KEY`.
- `build_metadata`: key, value; `key TEXT PRIMARY KEY`.

Индексы: `idx_definitions_word ON definitions(word)`,
`sqlite_autoindex_word_metadata_1`, `sqlite_autoindex_build_metadata_1`.
Никакие тексты подсказок в отчёт не копируются; сравнивались все их поля,
включая id, порядок, display, text, source и дополнительные текстовые колонки.

Метаданные генерации (одинаковы во всех образцах):

| Ключ | Значение |
| --- | --- |
| candidate_sha256 | `061633244ec968aeec2e0dc50aca1b61ecb8ca735590c5510938ab5085b711b0` |
| category_index_sha256 | `81d4c21302c54a969466c289fa950e7d255d3eb35a5b7bb641b4f8facb392335` |
| dictionary_words_sha256 | `9c1aed9e0011e4173dffd0f4281832d821d4de90623d19273f7fc8e68c212489` |
| own_license | `CC0-1.0` |
| owner_approval_sha256 | `50ea8dd86af6a32a13165e21378273b9734557f6980cc5ccea518a7d04e3e128` |
| rights_policy | `owned-minimal-hints-v1` |

У `word_metadata` категория `other` назначена 17 572 словам, остальные 2 548
распределены по 37 категориям. Сравнены не только эти количества, но и
привязка категории к каждому слову, источник категории, формы и родственные слова.
Нативная генерация также повторно валидирует индекс и T6-пулы через `inputs()`.

Диагностические SHA-256 канонического представления таблиц:

| Таблица | SHA-256 |
| --- | --- |
| build_metadata | `f2d8325275cdd733475082330b163b5e15a127834f09f95830323c2757ab7248` |
| definitions | `91a268f74983aa861a90e8245c9f5de8d96228adfbd850586edc5f9cc5c119b8` |
| word_metadata | `7462215f1a2042e9aef39d97583386a2bb20a80b564cdd50c595c0a52f8d8075` |

Общий канонический digest:
`8e761828ce45d465859e788b4197083749a9dab4d4b07797332c002222b9dfe7`.

Формат `pre03-canonical-v1`: UTF-8 JSON без BOM и завершающего перевода строки;
`ensure_ascii=False`, `sort_keys=True`, `separators=(',', ':')`, `allow_nan=False`.
Объект включает метку формата, `schema`, `structure`, `indexes`, `user_version`,
`rows`. Таблицы обходятся по имени; `SELECT *` сохраняет порядок колонок и
типы значений, строки каждой таблицы сортируются по байтам их JSON-кодировки.
Сортировка сохраняет дубликаты; числовые id также входят в digest.
Сравниваемые значения здесь только NULL/INTEGER/TEXT. Физические rootpage,
свободные области и заголовок версии библиотеки в digest не входят.
Это локальный диагностический формат, он не введён в manifest или release gate.

## Классификация причин

**A. Только бинарное представление SQLite — подтверждено для проверенной пары.**

1. При штатном replay CLI 3.50.6 эффективный `auto_vacuum` в созданной файловой
   БД равен 1 вместо 0: дополнительная страница и иная раскладка дали H1.
   Генератор `build_database` не задаёт этот PRAGMA явно. Источник отличающегося
   значения в конфигурации SDK CLI отдельно не установлен.
2. После явного `auto_vacuum=0` в изолированном опыте размер и число страниц
   совпали, но H2 отличается от H0 на 171 байт. Из них 2 байта — смещения
   98 и 99 в четырёхбайтовом поле по смещению 96: значение версии писателя
   3 037 002 против 3 050 006. Назначение поля описывает
   [спецификация SQLite](https://sqlite.org/fileformat2.html#write_library_version_number_and_version_valid_for_number).
3. Остальные 169 отличающихся байтов находятся на страницах 341, 345, 353,
   373, 378, 381, 383, 395, 402, 422. По `dbstat` исходника это leaf-страницы
   `sqlite_autoindex_word_metadata_1`. Вероятная причина — особенности записи
   индекса/уплотнения при `VACUUM` между сборками библиотеки. Точный внутренний
   алгоритм не исследован; все отличия не объявляются «пустыми байтами».
   SQL-эквивалентность и корректность индексов подтверждены сравнением и
   `integrity_check`, а не предположением о назначении каждого байта.

**B. Различия логической структуры или содержимого — не обнаружены.**
Физический `auto_vacuum` различается в первом эксперименте и явно показан выше;
это не скрыто как совпадение всех PRAGMA. Содержимое, ограничения таблиц,
определения индексов, метаданные, собственные подсказки и категории совпали.

Для ранее наблюдавшегося результата 3.40.1 классификация остаётся **NOT RUN**:
нет его контрольного файла/новой сборки для побайтного и логического сравнения.
Различие версии писателя — обоснованная возможная причина его SHA, но переносить
полученный здесь вывод об эквивалентности на тот файл без проверки нельзя.

## Последствия для release и F-Droid

В текущей среде strict gate не нарушен: оба нативных rebuild дали H0, Python
release-аудит прошёл. По коду `verify()` бинарное равенство свежей генерации
с `manifest.definitions.sha256` обязательно. `verifyOwnedHints` включён в
`verifyDictionaryLicenses`, а `verifyDefinitionsAudit` вызывает
`--check-release`, который также вызывает `verify()`. Таким образом, иной SHA
свежей генерации способен заблокировать даже сборку с неизменным исходным
`data/definitions.db`. Полный Python gate с SQLite 3.50.6 **не запускался**;
блокировка на таком результате следует из сравнения SHA в коде.

Gradle копирует утверждённую БД в assets и проверяет её SHA. Временные БД этого
исследования не попадают в APK. Если разные результаты генерации когда-либо
станут assets, логического равенства будет недостаточно для идентичности APK.
Для выбранной стратегии developer-signed reproducible APK требуется проверка
воспроизведения APK; механизм описан в
[официальной документации F-Droid](https://f-droid.org/docs/Reproducible_Builds/).
Это следствие для планирования сборочной среды, а не результат проверки APK
или готовности к публикации. Сборка, подпись APK и независимый F-Droid rebuild
в PRE-03 не выполнялись; дальнейшие задания не запускались.

## Варианты устранения и рекомендация

| Вариант | Эффект | Ограничения / решение |
| --- | --- | --- |
| A. Закрепить SQLite в toolchain | Сохраняет требование побайтного rebuild и текущий H0 | Рекомендуется: зафиксировать Python 3.10.12 + фактическую библиотеку SQLite 3.37.2 с source id, сборкой/параметрами; предпочтительно воспроизводимый образ с digest. Проверить на независимой среде, что он даёт H0. Только номера версии CLI недостаточно. Реализация — отдельная задача, не выполнена здесь. |
| B. Отдельный канонический digest при строгом SHA утверждённого файла | Различает подмену бинарного артефакта и логическую эквивалентность rebuild между средами | Полезен дополнительно к A как диагностика. Если оставить и бинарный rebuild-контроль, несовместимость сред останется. Если заменить только сравнение свежего rebuild на канонический digest, сохранив SHA исходника/assets, изменится политика приёмки rebuild. Нужно отдельное решение владельца. |

Для B потребуется отдельно определить версионируемый формат: все таблицы и
колонки, типы, NULL, дубликаты, id/порядок, ограничения, индексы, user_version,
происхождение/лицензии, категории и метаданные; проверять integrity и неожиданные
объекты. Нельзя автоматически переносить результаты ручного аудита на иной
бинарный артефакт лишь по совпадению содержимого. Диагностический digest выше
сам по себе такого права не предоставляет.

Альтернативы: сохранить текущий gate и явно ограничить поддержанные среды;
или отдельно исследовать детерминированную сериализацию с последующим
утверждением нового артефакта. Исправление байтов заголовка не решает задачу:
в контроле с выровненным `auto_vacuum` есть ещё 169 отличий в индексе.
Ни нормализация файла, ни замена SHA в этой задаче не выполнялись.

### Граница решения владельца

Текущий диагностический отчёт завершён; для варианта A изменение политики
аудита или утверждённых артефактов не требуется, блокировки PRE-03 нет.
**BLOCKED_FOR_OWNER_DECISION** применим к будущей реализации замены бинарного
rebuild-контроля вариантом B либо замене утверждённой БД:

- Обнаружено: H1/H2 отличаются от H0 при полном совпадении SQL-содержимого.
- Возможное изменение: разрешить канонический digest для свежего rebuild,
  сохранив строгий SHA утверждённого файла/assets; либо отдельно утвердить новую БД.
- Причина: переносимость проверки между версиями SQLite.
- Альтернативы: вариант A; оставить gate и ограничить поддержанные среды.

Такое решение не принято и не реализовано. Утверждённые SHA, manifest, результаты
ручного аудита и права сохранены; waiver не вводился. Изменён только этот документ.

## Воспроизведение диагностического скрипта

Ниже полный скрипт итогового сравнения. Сохранить как `audit.py` в **новый пустой**
каталог от `mktemp`, запустить из корня проекта `python3 /tmp/НОВЫЙ_КАТАЛОГ/audit.py`.
Путь SDK CLI при другой установке следует заменить; команды версий выше
необходимо повторить. Скрипт не предназначен для повторного запуска в том же
каталоге: SQL создаёт таблицы строго, без `IF NOT EXISTS`.

```python
import collections, hashlib, json, pathlib, sqlite3, subprocess, sys
ROOT = pathlib.Path.cwd()
TEMP = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from tools.build_owned_hints import generate

def sha(data):
    return hashlib.sha256(data).hexdigest()

def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')

def quote(name):
    return '"' + name.replace('"', '""') + '"'

# Trace actual generation, including expanded bound parameters and transactions.
original_connect = sqlite3.connect
statements = []
def traced_connect(*args, **kwargs):
    db = original_connect(*args, **kwargs)
    db.set_trace_callback(statements.append)
    return db
sqlite3.connect = traced_connect
try:
    generate(ROOT / 'data', TEMP / 'native.db')
finally:
    sqlite3.connect = original_connect
sql = '\n'.join(s.rstrip().rstrip(';') + ';' for s in statements) + '\n'
(TEMP / 'generation.sql').write_text(sql, encoding='utf-8')
cli = '/home/vladimir/.local/share/words-combination/android-sdk/platform-tools/sqlite3'
for name in ('cli.db', 'cli-repeat.db'):
    subprocess.run([cli, '-batch', '-bail', str(TEMP / name)], input=sql, text=True, check=True)
for name, binary, prefix in (
    ('cli337', '/usr/bin/sqlite3', ''),
    ('cli350-av0', cli, 'PRAGMA auto_vacuum=0;\n'),
    ('cli350-av0-repeat', cli, 'PRAGMA auto_vacuum=0;\n')):
    subprocess.run([binary, '-batch', '-bail', str(TEMP / (name + '.db'))],
                   input=prefix + sql, text=True, check=True)
generate(ROOT / 'data', TEMP / 'native-repeat.db')

def snapshot(path):
    with sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True) as db:
        schema = db.execute('SELECT type,name,tbl_name,sql FROM sqlite_schema ORDER BY type,name').fetchall()
        tables = [r[0] for r in db.execute("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name")]
        structure, indexes, rows = {}, {}, {}
        for table in tables:
            structure[table] = db.execute('PRAGMA table_xinfo(' + quote(table) + ')').fetchall()
            indexes[table] = db.execute('PRAGMA index_list(' + quote(table) + ')').fetchall()
            for index in indexes[table]:
                indexes[index[1]] = db.execute('PRAGMA index_xinfo(' + quote(index[1]) + ')').fetchall()
            # All stored values in these tables are NULL/integer/text; sort by full JSON row bytes.
            rows[table] = sorted(db.execute('SELECT * FROM ' + quote(table)), key=encode)
        canonical = dict(format='pre03-canonical-v1', schema=schema, structure=structure,
                         indexes=indexes, user_version=db.execute('PRAGMA user_version').fetchone()[0], rows=rows)
        (TEMP / (path.name + '.canonical.json')).write_bytes(encode(canonical))
        return dict(sha256=sha(path.read_bytes()), size=path.stat().st_size,
                    integrity=db.execute('PRAGMA integrity_check').fetchall(),
                    user_version=canonical['user_version'],
                    pragmas={key: db.execute('PRAGMA '+key).fetchone()[0] for key in
                             ('page_size','page_count','freelist_count','encoding','auto_vacuum','application_id','schema_version')},
                    canonical_sha256=sha(encode(canonical)),
                    table_sha256={t:sha(encode(r)) for t,r in rows.items()},
                    counts={t:len(r) for t,r in rows.items()},
                    schema=schema, structure=structure, indexes=indexes,
                    metadata=rows['build_metadata'],
                    origin=db.execute('SELECT origin,count(*) FROM definitions GROUP BY origin ORDER BY origin').fetchall(),
                    categories=db.execute('SELECT category,count(*) FROM word_metadata GROUP BY category ORDER BY category').fetchall()), canonical

paths = {'approved':ROOT/'data/definitions.db', **{name:TEMP/(name+'.db') for name in ('native','native-repeat','cli','cli-repeat','cli337','cli350-av0','cli350-av0-repeat')}}
results, snapshots = {}, {}
for name,path in paths.items():
    results[name], snapshots[name] = snapshot(path)
base = paths['approved'].read_bytes()
for name,path in paths.items():
    other = path.read_bytes()
    changes = [i for i,(a,b) in enumerate(zip(base,other)) if a!=b]
    results[name]['different_byte_count'] = len(changes) + abs(len(base)-len(other))
    results[name]['different_offsets_first100'] = changes[:100]
    results[name]['canonical_equal_to_approved'] = snapshots[name] == snapshots['approved']
    results[name]['header_96_99_hex'] = other[96:100].hex()
results['sql'] = dict(statements=len(statements),sha256=sha(sql.encode('utf-8')))
(TEMP/'results.json').write_bytes(encode(results))
for name,result in results.items():
    if name=='sql':
        print(name,result)
    else:
        print(name, json.dumps({k:v for k,v in result.items() if k not in ('schema','structure','indexes','metadata','categories')}, ensure_ascii=False))
print('build_metadata',json.dumps(results['approved']['metadata'],ensure_ascii=False))
print('category_counts',results['approved']['categories'])
print('schema',results['approved']['schema'])
```

Дополнительные диагностические запросы: `PRAGMA compile_options`,
`SELECT sqlite_source_id()`, `PRAGMA secure_delete`; `dbstat` запрашивался
в режиме `mode=ro` для перечисленных десяти страниц. Сравнение байтов выполнено
Python по парам файлов, смещения отсчитываются с нуля.

## Проверки и ограничения

- PASS: `build_owned_hints.py --check`, строгий `build_game_data.py --check-release`.
- PASS: две нативные генерации, replay 3.37.2, по два replay 3.50.6 с исходными
  настройками и с `auto_vacuum=0`; сравнение всех восьми файлов описано выше.
- PASS: скрипт извлечён из этого документа и запущен через `subprocess.run` в новом
  `TemporaryDirectory`; весь `results.json` совпал с исходным результатом.
  `git diff --check` прошёл; завершающие пробелы документа проверены отдельно.
- NOT RUN: SQLite 3.40.1; полный Python `--check` с 3.50.6; независимая машина;
  APK/release-сборка и F-Droid rebuild. Полный gate выполняет внешний оркестратор.
- Код приложения/генератора и данные не менялись. Защищённые пути оркестратора
  не менялись, его команды не запускались; операции изменения Git не выполнялись.
