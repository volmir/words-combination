# Финализация кампании v2

Статус после **OPT-01**: проверка переносимости v1-пригодности выполнена,
VALID-кандидата v2 нет, `data/` остаётся v1. Новых ИИ-вызовов и сети не было;
текущие кэши, словарь и `data/` не изменялись. Отчёт аудита —
`build/reports/finishing/OPT-01.json` (каталог `build/` не коммитится).

## 1. Что именно проверялось

Цель OPT-01 — выяснить, какие **индивидуальные** решения LEX-04 из v1 можно
без нового ИИ перенести на вход v2 из 20 106 слов. Агрегатные пулы v1
(`source` 8 906, `target` 7 913) сами по себе решением не считаются: у каждого
переносимого статуса должны быть слово, роль, признаки, результат и проверяемое
происхождение.

Проверка только читала файлы. Она не запускала `prepare_v2`, не вызывала
провайдера, не создавала и не переименовывала кэш, не меняла `data/`.
Инструмент — `tools/audit_suitability_portability.py`.

## 2. Найденный источник v1, а не только статистика

Обозначение для путей v2:
`V2=.local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e`.

| Объект | Путь | SHA-256 / размер | Что подтверждает |
|---|---|---|---|
| Активный v1-словарь | `data/words.txt` | `980a7e44d560c6b5efa3a46a38ca80828809afce02458c54d73a3b4e5b8f50b0`, 23 393 слова | исходный словарь v1 |
| v1-манифест | `data/manifest.json` | `06d8d824d75a9a1f6665a80e11d42cb187b0ebeaeaf634cc438ad035f7cc8491` | dump `98bebfb8…`, frequency `32dfd941…`, БД `718db097…` |
| v1-профиль GEN-01 | `tools/cache/level_profiles/4f6d9905ff36c3d8d512d66e18069dd5cbd1f3939aa99c8a2f53dd5e029737bf.json.gz` | `63f1b74a5f9d4d410efd7ca2f579c0eacea8201fbe428e24ec9de7da7b77ed5a` | `payload.assessments` = 33 710 записей; `inputs.suitability` = SHA файла `word_suitability.json` |
| v1-вход LEX-04 | `build/reports/level_profiles/suitability_input.json` | файл `9a2a3fabc17ccb2184e265788eecc8241ae1a19720f248a14317ecf02bd77729`, JSON-digest `fd03c6e59dc1bc2e214d34fa6b8ca7f969d25c7591e37355a245e2ffe9aa86b5` | 33 710 пар (23 393 target + 10 317 source) |
| v1-снимок ИИ-решений | `build/reports/suitability/suitability_decisions.json` | файл `6506ac39…`, `sha256` `f6bdd29c…`, `input_sha256` `fd03c6e5…` | 16 588 item + 16 588 result, `complete=true` |
| v1-поштучный кэш | `tools/cache/word_suitability/*.json` | 16 588 файлов | все конверты валидны, один контекст, ключ = `sha256([context,item])` |
| v1-полный результат | `build/reports/suitability/word_suitability.json` | `6989944d1ebfd437bc08d6d297cdcaf299aa3c0564eca183768118d5f6444392` | 33 710 оценок: 16 588 `ai` + 17 122 `rules`; все `assessment_sha256` валидны |
| v1-предложение уровней | `build/reports/level_words_proposal.json` | `2f8a2c54…` | derived-предложение v1 (23 393 слова); его `inputs.profile_inputs.suitability` = `6989944d…`, но индивидуальных решений оно не содержит |
| v2-словарь | `.local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e/round-01/package/words.txt` | `3b8a1339df5485ae2c5493cec3b5297e84173b94e57cf253c3543a8c85efc997`, 20 106 слов | целевой словарь v2 |
| v2-вход LEX-04 | `$V2/round-01/profiles/suitability_input.json` | файл `57b1ea2d…`, JSON-digest `18742f8c7ebb73a164de1c2079c3c0c5457ef8efdf2c6f07c7a29d99bd640f28` | 29 048 пар (20 106 target + 8 942 source) |
| v2-манифест | `$V2/round-01/package/manifest.json` | `09d4747c020d3d2ab9d2197979aa51a829d3653667175506bed568976a76bc29` | dump и frequency совпадают с v1; БД `6d0cd051…` отличается |
| v2-текущий результат | `$V2/round-01/suitability/word_suitability.json` | `4da220fc…` | контекст `opencode` / `deepseek-v4.1-flash#max`; 352 ИИ-решения, 2 433 pending |

Полные хэши перечисленных объектов:

```text
data/words.txt
  980a7e44d560c6b5efa3a46a38ca80828809afce02458c54d73a3b4e5b8f50b0
data/manifest.json
  06d8d824d75a9a1f6665a80e11d42cb187b0ebeaeaf634cc438ad035f7cc8491
tools/cache/level_profiles/4f6d9905ff36c3d8d512d66e18069dd5cbd1f3939aa99c8a2f53dd5e029737bf.json.gz
  63f1b74a5f9d4d410efd7ca2f579c0eacea8201fbe428e24ec9de7da7b77ed5a
build/reports/level_profiles/suitability_input.json
  9a2a3fabc17ccb2184e265788eecc8241ae1a19720f248a14317ecf02bd77729
  JSON-digest fd03c6e59dc1bc2e214d34fa6b8ca7f969d25c7591e37355a245e2ffe9aa86b5
build/reports/suitability/suitability_decisions.json
  6506ac3917791732303b080680366ff59cc6a6b80eb44a48e346ba61bc1c11e3
  decision SHA f6bdd29c88f9e9408e60771ffd4c558f3295007130bb9eea40ad52652712e5cd
build/reports/suitability/word_suitability.json
  6989944d1ebfd437bc08d6d297cdcaf299aa3c0564eca183768118d5f6444392
build/reports/level_words_proposal.json
  2f8a2c54bee16466480750c4f50df3e2d211f4f26368133619688fdcce02e87d
$V2/round-01/package/words.txt
  3b8a1339df5485ae2c5493cec3b5297e84173b94e57cf253c3543a8c85efc997
$V2/round-01/profiles/suitability_input.json
  57b1ea2d9dbf9804523dcfd71accf035230d61804ddfd61767a8fa123306d573
  JSON-digest 18742f8c7ebb73a164de1c2079c3c0c5457ef8efdf2c6f07c7a29d99bd640f28
$V2/round-01/package/manifest.json
  09d4747c020d3d2ab9d2197979aa51a829d3653667175506bed568976a76bc29
$V2/round-01/suitability/word_suitability.json
  4da220fc7fd0c30dad6afa51df7be28049bc2db1220f8f4eb0de863532546ef2
```

Общий dump: `98bebfb8013064d23bc6f0981b64cd097f64e098c9e434ebb97684c9606cc2da`;
frequency: `32dfd94138aea2667ec455dcc8512029e39bd4b8ea70b09410d569b69d511a30`;
вход 113 413 слов: `c419c65f916799955584130390527b60642ec6f26c6228bdd7058a95986d6415`.

Выбран профиль `4f6d9905…`: только у него одновременно есть
`inputs.suitability = 6989944d…` (файл `word_suitability.json`) и
`inputs.categories = 1a08b699…` (действующая категорийная индекс-версия).
Профиль `39b2ab89…` совпадает по `suitability`, но не содержит `categories`;
`a47d7a40…` и `b19bab10…` не содержат `suitability` и являются историческими.

Цепочка v1 проверена: `inputs.profile_inputs` содержит `suitability`
`6989944d…`, `words` `980a7e44…`, `database` `718db097…`, `manifest`
`06d8d824…`, `dump` `98bebfb8…`, `frequency` `32dfd941…`. `payload.assessments`
побайтово совпадает с `assessments` из `word_suitability.json`. Все 16 588
поштучных файлов кэша совпали со снимком по context, item, result и SHA.

## 3. Правило совместимости

`exact-compatible` — только полное совпадение ключа LEX-04:

1. схема `word-suitability-decisions-v1` и политика
   `{"assessment_version": "word-suitability-v1"}`;
2. контекст целиком: `task`, `prompt_sha256`, `taxonomy_sha256`,
   `schema_sha256`, `provider`, `model`;
3. item целиком: `word`, `role`, `features` и
   `id = sha256([word, role, features])`;
4. та же нормализация (ё→е) и тот же исходный dump/frequency;
5. для поштучного кэша — совпадение `sha256([context, item])` с именем файла.

`needs-recheck` — слово и роль совпали, но изменились признаки (`noun`,
`role_decision`, `reachable`, `frequency`, `freq_rank`, `labels`) или контекст
(`provider`/`model`). `unavailable` — пары `(слово, роль)` нет во входе v2,
либо есть только агрегат без индивидуальной записи.

Статус `suitable` из агрегата v1 без item/result не считается решением v2.
Причины (`reason`) сохранены, но не проверялись независимо; доказательством
служит пара item+result с SHA, а не текст причины.

### 3.1. Чек-лист полей

| Проверка | v1 | v2 | Результат |
|---|---|---|---|
| Роль | 16 588 ИИ: target 9 654 / source 6 934 | 29 048 пар: target 20 106 / source 8 942 | 14 959 пар с той же ролью; смены роли нет |
| Версия LEX-04 | `word-suitability-v1`; снимок `word-suitability-decisions-v1` | то же | совпадает |
| Смысл меток | 16 допустимых, 3 жёстких (`offensive`, `proper_name`, `productive_diminutive`), 6 preferred-source; `taxonomy_sha256` `73ead601…` | тот же закрытый набор | совпадает; все 17 122 правила воспроизведены |
| Ключи контекста | task, prompt, taxonomy, schema, provider, model | 4 из 6 совпадают | `provider`/`model` различаются |
| Нормализация | 0 неканонических слов/пар, ё→е, `[а-я]{3,30}` | 0 | совпадает |
| Входные признаки | `noun`, `role_decision`, `reachable`, `frequency`, `freq_rank`, `labels` | те же 6 ключей | 14 734 точных item; 225 изменённых |
| Dump / frequency | `98bebfb8…` / `32dfd941…` | те же | совпадает |
| Taxonomy / model | `73ead601…`; `codex` / `gpt-6.1-sol` | `73ead601…`; `opencode` / `deepseek-v4.1-flash#max` | taxonomy совпадает, модель различается |

`taxonomy_sha256` в контексте LEX-04 — это SHA-256 закрытой политики меток
(`assess.POLICY`), а не `tools/categories.json`; категорийная таксономия
`3645c587…` относится к LEX-03 и на признаки пригодности не влияет.

## 4. Пересечение и индивидуальные решения

| Показатель | Значение |
|---|---:|
| Слов v1 / v2 / пересечение | 23 393 / 20 106 / **20 103** |
| Слов только в v2 | 3 (`зелени`, `рыбка`, `улочка`) |
| Слов только в v1 | 3 290 |
| Индивидуальных ИИ-решений v1 | **16 588** (target 9 654, source 6 934) |
| ИИ-статусы v1 | suitable 10 524, unsuitable 322, insufficient_data 5 742 |
| Индивидуальных правил v1 | **17 122** (target 13 739, source 3 383) |
| Правила v1 по статусам | suitable 6 295, unsuitable 10 827 |
| Всего индивидуальных оценок v1 | **33 710** |
| ИИ-пары, найденные в v2 | 14 959 (target 8 638, source 6 321) |
| ИИ-пары с точными признаками | **14 734** (target 8 415, source 6 319) |
| ИИ-пары с изменёнными признаками | 225 (target 223, source 2) |
| ИИ-пары без пары в v2 | 1 629 (target 1 016, source 613) |
| Правила с точными признаками | 13 976 (target 11 359, source 2 617) |
| Правила с изменёнными признаками | 110 (target 106, source 4) |
| Правила без пары в v2 | 3 036 (target 2 274, source 762) |
| Покрытие v2 точными индивидуальными записями | **28 710 / 29 048 = 98,8364%** |
| Пар v2 без точной записи | 338: 3 новых target + 335 изменённых (329 target, 6 source) |

Все 17 122 правила v1 воспроизводятся текущей функцией `rule_decision`
без расхождений; все 16 588 ИИ-записей по-прежнему остаются «спорными» для
правил. Это подтверждает, что смысл меток и границы ролей не изменились.

Причина 335 изменённых пар — не исходный dump (он тот же), а другой состав
словаря: `reachable` для target/source вычисляется по доступным ответам,
поэтому уменьшение словаря с 23 393 до 20 106 меняет его у части слов.

## 5. Таблица exact-compatible / needs-recheck / unavailable

### 5.1. Активный контекст v2 (`opencode` / `opencode/deepseek-v4.1-flash#max`)

| Класс | ИИ-решений | Из них suitable | Смысл |
|---|---:|---:|---|
| exact-compatible | **0** | 0 | полный ключ, включая provider/model, совпал с активным v2 |
| needs-recheck | 14 959 | 9 933 | 14 734 — только смена контекста; 225 — смена признаков |
| unavailable | 1 629 | 591 | пары нет во входе v2 |
| **Итого** | **16 588** | **10 524** | |

### 5.2. Закреплённый контекст v1 (`codex` / `gpt-6.1-sol`)

| Класс | ИИ-решений | Из них suitable | Смысл |
|---|---:|---:|---|
| exact-compatible | **14 734** | **9 828** | полный ключ совпал с контекстом v1; нужен отдельный прогон, а не активный кэш |
| needs-recheck | 225 | 105 | изменились признаки (в основном `reachable: true → false`) |
| unavailable | 1 629 | 591 | пары нет во входе v2 |
| **Итого** | **16 588** | **10 524** | |

**Безопасно переносимых решений:**

- для **активного** контекста v2 — **0**;
- для отдельного прогона, закреплённого на контексте v1, — **14 734**
  решения, в том числе **9 828 suitable**. Это единственный путь без нового ИИ,
  но он не даёт права смешивать модели или переименовывать контекст.

Разница между 14 734 и 14 959 — 225 записей с изменённым входом. Разница между
28 710 (точные индивидуальные записи) и 14 734 (только ИИ) — 13 976 правил,
которые v2 всё равно пересчитывает бесплатно.

## 6. Почему нельзя смешивать модели

В текущем v2-кэше уже есть 352 ИИ-решения. Все 352 item совпали с v1-записями
по полному item id. Но статусы совпали только у 201, а **151 (42,9%)**
разошлись:

| Переход v1 → текущий v2 | Число |
|---|---:|
| suitable → suitable | 151 |
| insufficient_data → suitable | 89 |
| insufficient_data → unsuitable | 58 |
| insufficient_data → insufficient_data | 45 |
| unsuitable → unsuitable | 5 |
| прочие конфликты | 4 |

Примеры: `аал` target — v1 `insufficient_data`, v2 `unsuitable`; `адресок`
target — v1 `unsuitable`, v2 `suitable`; `авось` target — v1
`insufficient_data`, v2 `suitable`. Это одни и те же item/features, но разные
`provider`/`model`. Поэтому старый `suitable` нельзя выдавать за решение
активного v2 без явного переноса в закреплённый контекст v1.

## 7. Десять воспроизводимых примеров

Все примеры — реальные записи v1; полный id и ключ кэша печатает
`--only <слово> <роль>`. `pinned` — классификация при контексте v1,
`active` — при текущем контексте v2.

| № | Слово | Роль | Статус v1 | pinned v1 | active v2 | Причина / изменение | id (начало) |
|---:|---|---|---|---|---|---|---|
| 1 | `аббат` | target | suitable | exact-compatible | needs-recheck | context: model, provider | `03a3b3ff213e` |
| 2 | `аббатство` | source | suitable | exact-compatible | needs-recheck | context: model, provider | `b7f3d902fa17` |
| 3 | `аал` | target | insufficient_data | exact-compatible | needs-recheck | context: model, provider | `5b5fd78f48ca` |
| 4 | `адресок` | target | unsuitable | exact-compatible | needs-recheck | context: model, provider | `cd3b16bc870d` |
| 5 | `аттестация` | source | insufficient_data | exact-compatible | needs-recheck | context: model, provider | `11842dac2b10` |
| 6 | `деталька` | source | unsuitable | exact-compatible | needs-recheck | context: model, provider | `67bbcc948f6e` |
| 7 | `авантюра` | target | suitable | needs-recheck | needs-recheck | input: `reachable` true→false | `6dbe0df34b8b` |
| 8 | `шевеление` | source | suitable | needs-recheck | needs-recheck | input: `reachable` true→false | `8fe8efe8417d` |
| 9 | `аватарка` | target | suitable | unavailable | unavailable | нет пары в v2 | `940dee3a3758` |
| 10 | `абрикоса` | source | suitable | unavailable | unavailable | нет пары в v2 | `54b92c3b607b` |

Дополнительно: три слова есть только в v2 — `зелени`, `рыбка`, `улочка`
(target). У них нет v1-решения; в частности, `рыбка` — добавленное в v2
подтверждённое владельцем уменьшительное, а не переносимая запись.

## 8. Команды

Полный аудит (по умолчанию читает v1-пути и 16 588 файлов кэша):

```sh
V2=.local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e
python3 tools/audit_suitability_portability.py \
  --v2-input "$V2/round-01/profiles/suitability_input.json" \
  --v2-current "$V2/round-01/suitability/word_suitability.json" \
  --v2-words "$V2/round-01/package/words.txt" \
  --v2-manifest "$V2/round-01/package/manifest.json" \
  --output build/reports/finishing/OPT-01.json
```

Один пример:

```sh
V2=.local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e
python3 tools/audit_suitability_portability.py \
  --v2-input "$V2/round-01/profiles/suitability_input.json" \
  --v2-current "$V2/round-01/suitability/word_suitability.json" \
  --v2-words "$V2/round-01/package/words.txt" \
  --v2-manifest "$V2/round-01/package/manifest.json" \
  --only авантюра target
```

Проверки:

```sh
python3 -m unittest tools.tests.test_suitability_portability -v
python3 -m unittest discover tools/tests
sha256sum data/words.txt data/manifest.json \
  build/reports/suitability/suitability_decisions.json \
  build/reports/suitability/word_suitability.json \
  tools/cache/level_profiles/4f6d9905ff36c3d8d512d66e18069dd5cbd1f3939aa99c8a2f53dd5e029737bf.json.gz
```

`--v1-snapshot`, `--v1-word-suitability`, `--v1-input`, `--v1-cache`,
`--v1-profile`, `--v1-words` и `--v1-manifest` по умолчанию указывают на
перечисленные выше v1-файлы. Скрипт не пишет в кэш; единственный выход —
`build/reports/finishing/OPT-01.json`.

## 9. Что это значит для завершения

- Для текущего `opencode`/`deepseek` безопасно переносимых записей нет.
  Импортёр OPT-02 для этого кэша должен быть no-op, а не копировать старые
  файлы под новые ключи.
- Если использовать 14 734 записи, нужен отдельный выборочный прогон с
  `provider=codex`, `model=gpt-6.1-sol` и отдельным кэшем/снимком. Контекст
  нельзя переименовывать. Полный `assess_word_suitability --snapshot` не
  подходит: у v1 и v2 разные `input_sha256`; для точных item нужен
  `prepare_v2.seed_snapshot` или импортёр OPT-02, сохраняющий происхождение.
- 225 записей с изменённым входом и 1 629 записей без пары всё равно требуют
  нового решения или правила. 3 новых слова v2 решаются отдельно.
- Это не VALID кампании: GEN-02/GEN-03/GEN-04/GEN-05, `validate_campaign`,
  установка `data/` и Android-проверки в OPT-01 не запускались.

## 10. Ограничения

- Текущие 352 решения v2 — выборочная партия, а не полная разметка; точность
  модели независимой разметкой не измерена.
- Причины v1-решений сохранены, но не аудировались; например, часть причин
  о длине слова может быть неточной. Для переноса используется статус, а не
  текст причины.
- Файл `tools/assess_word_suitability.py` изменился после v1-кэша (добавлен
  выборочный режим), но `VERSION`, `POLICY`, `PROMPT`, схема и
  `rule_decision` не менялись; все 17 122 правила воспроизведены.
- Сеть, новые ИИ-вызовы, `data/`, кэши и `orchestrator/*` не изменялись.
