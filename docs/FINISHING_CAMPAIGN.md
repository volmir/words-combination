# Финализация кампании v2

Статус после **OPT-01, OPT-02, OPT-03, OPT-04 и OPT-05**: проверка переносимости
v1-пригодности выполнена, добавлен защищённый импортёр только индивидуально
совпавших записей (по умолчанию dry-run), текущий отбор зафиксирован
воспроизводимой read-only метрикой, GEN-02 научился улучшать промежуточный
кандидат с недобором полос без ослабления финального валидатора, а поиск
GEN-02 переведён на полный профильный пул с seeded restarts, обменами
исходников и явными штрафами за max-J и непокрытые полосы. VALID-кандидата
v2 нет, `data/` остаётся v1. Новых ИИ-вызовов и сети не было; текущие кэши,
словарь и `data/` не изменялись. Отчёты — `build/reports/finishing/OPT-01.json`,
`build/reports/finishing/OPT-02.json`, `build/reports/finishing/OPT-03.json`
и каталоги `build/reports/finishing/OPT-05-final3`, `OPT-05-softb7` и
`OPT-05-current` (каталог `build/` не коммитится).

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

## 10. Импорт OPT-02: только индивидуальные совпадения

`tools/import_v1_suitability.py` читает v1-снимок `items/results` и вход v2.
Он не использует агрегатные пулы и не выводит статус из `word_suitability.json`:
переносятся только записи с полным совпадением ключа OPT-01 — `word`, `role`,
`features`, `id`, версия LEX-04 и контекст. Контекст назначения по умолчанию
равен контексту самого v1-снимка; `--v2-context`/`--v2-current` позволяют
проверить другой (например, активный) контекст. Если контекст отличается,
импорт не переименовывает провайдера/модель, а даёт no-op.

Запись в кэш выполняется только с `--apply`; по умолчанию — dry-run. Ключ и
конверт остаются штатными: `sha256([context, item])`, `{context, item, result,
sha256}`. В отчёте сохраняются SHA снимка, входа v1, файла и JSON входа v2, а
для каждой переносимой записи — `cache_key` и `envelope_sha256`.

Результат на реальных входах OPT-01 (dry-run, без новых ИИ-вызовов):

| Контекст назначения | Совпало | suitable | Уже в `tools/cache/word_suitability` | Новых решений избежано |
|---|---:|---:|---:|---:|
| закреплённый v1: `codex` / `gpt-6.1-sol` | **14 734** | **9 828** | 14 734 | **14 734** (737 батчей по 20) |
| активный v2: `opencode` / `opencode/deepseek-v4.1-flash#max` | **0** | 0 | 0 | **0** |

В закреплённом контексте записи распределены так: target 8 415, source 6 319.
Отклонены без записи: 225 с изменённым входом (`input_changed`), 1 629 без
пары во v2 (`no_v2_pair`), 0 со сменой роли и 0 уже решённых правилом. Для
активного контекста те же 14 734 записи отклоняются как `context_changed`, а не
копируются под ключи `opencode`/`deepseek`.

Экономия реальных новых запросов: **14 734 решения** (9 828 suitable) в
отдельном прогоне, закреплённом на v1-контексте, и **0** для активного
`opencode`/`deepseek`-кэша. Это число — подтверждённые индивидуальные
совпадения, а не агрегат. Для пустого отдельного кэша `--apply` запишет все
14 734 конверта; в существующем v1-кэше все они уже присутствуют, поэтому
повторный запуск не пишет файлы. Смешивать контексты и указывать `--apply` на
активный кэш нельзя.

```sh
V2=.local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e
# Закреплённый v1-контекст, dry-run (по умолчанию):
python3 tools/import_v1_suitability.py \
  --v2-input "$V2/round-01/profiles/suitability_input.json" \
  --v1-input build/reports/level_profiles/suitability_input.json \
  --v1-manifest data/manifest.json \
  --v2-manifest "$V2/round-01/package/manifest.json" \
  --v1-words data/words.txt \
  --output build/reports/finishing/OPT-02.json
# Активный контекст: ожидаемый no-op.
python3 tools/import_v1_suitability.py \
  --v2-input "$V2/round-01/profiles/suitability_input.json" \
  --v2-current "$V2/round-01/suitability/word_suitability.json" \
  --output build/reports/finishing/OPT-02-active.json
# Запись только в отдельный кэш закреплённого прогона:
# ... --cache "$PINNED_CACHE" --apply
```

Проверки импортёра:

```sh
python3 -m unittest tools.tests.test_import_v1_suitability -v
python3 -m unittest discover tools/tests
```

## 11. Ограничения

- Текущие 352 решения v2 — выборочная партия, а не полная разметка; точность
  модели независимой разметкой не измерена.
- Причины v1-решений сохранены, но не аудировались; например, часть причин
  о длине слова может быть неточной. Для переноса используется статус, а не
  текст причины.
- Файл `tools/assess_word_suitability.py` изменился после v1-кэша (добавлен
  выборочный режим), но `VERSION`, `POLICY`, `PROMPT`, схема и
  `rule_decision` не менялись; все 17 122 правила воспроизведены.
- OPT-02 проверен dry-run на реальных входах; `--apply` в рабочие кэши не
  запускался. Экономия 14 734 — подтверждённые совпадения, а не гарантия, что
  конкретный выборочный план запросит все эти пары.
- Сеть, новые ИИ-вызовы, `data/`, кэши и `orchestrator/*` не изменялись.

## 12. OPT-03: проверяемая базовая метрика текущего отбора

`tools/audit_selection_baseline.py` — автономный read-only диагностический
режим. Он не запускает GEN-02 и не вызывает модель: читает уже сохранённые
`level_words_proposal.json` и GEN-01-профили, заново строит R, пересчитывает
все 435 пар Jaccard, проверяет 30 слотов, `retained`, 32 буквы, полосы и
краткие слова 3–4 букв. Единственный выход — указанный JSON-отчёт; кэши,
`data/` и `orchestrator/*` не изменяются.

### 12.1. Что здесь означает R

В этом разделе **R** — диагностический набор, а не порог звёзд из правил
игры. Это упорядоченный список 30 записей:

```json
{"slot": 1, "word": "...", "recognizable_answers": ["...", "..."]}
```

SHA-256 считается по каноническому JSON
`{schema: "selection-r-v1", levels: 30, slots: [...]}`. Поэтому R меняется
при смене кандидата, порядка слотов, профиля или набора узнаваемых ответов.
`--expected-r-sha256` закрепляет версию R: при несовпадении отчёт получает
`status=r_version_mismatch`, а команда завершается с кодом 3. Это отдельная
проверка от `profile_version_mismatch` (SHA файла/`payload` профилей).

### 12.2. Зафиксированные входы и R

Активный кандидат — канонический прогон DATA-01
`$V2=.../eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e`.

| Объект | SHA-256 / значение |
|---|---|
| v2-словарь `$V2/round-01/package/words.txt` | `3b8a1339df5485ae2c5493cec3b5297e84173b94e57cf253c3543a8c85efc997`, 20 106 слов |
| GEN-01-профиль, файл | `45599e213ebe3589c89955e91ede1e6bb16f484056a668006d0613f4bd164424` |
| GEN-01-профиль, `payload_sha256` | `8e13da85c04c81455a270db28b016a8431c5d26e1100ed703d1f8dbb7a7d0d22` |
| Вход LEX-04 профиля | `4da220fc7fd0c30dad6afa51df7be28049bc2db1220f8f4eb0de863532546ef2` |
| Категорийный вход профиля | `5860a238279d87a3b2a68b49575b069d100d295461b38df4a4db87485ad049cc` |
| `previous_levels` / конфиг отбора / алфавит | `e4ded7a9…`, `730e5b48…`, `63ab47b6…` |
| **R текущего кандидата** | **`8e7329c9de4de306e0859e5967cf2e748843a210ec45b07ade2e24a5b371fac3`** |
| R опорного состава 5,3458% | `ce678926f5ea3cb2a4576a8e3255ebde8c02f8a8aff10574ebce9a1d9c70b41f` |
| Целевая пригодность полного входа | 3 811 `suitable` (3 592 правила + 219 ИИ), 8 087 `pending` |

R текущего кандидата и R опорного состава **различаются**. Поэтому 5,3458% и
8,1693% нельзя сравнивать как метрики одного набора. Исторические v1-числа
2,963% / 14,599% относятся ещё и к словарю 23 393 слова, а не к 20 106.

### 12.3. Снимок текущего отбора

| Показатель | Значение |
|---|---:|
| Слотов / разных источников | 30 / 30 |
| GEN-02 / слотов без локальных нарушений | **26 / 30** (нарушены 26–29) |
| `retained` | **8** (в границах 8–15) |
| Покрытых букв `Alphabet.LETTERS` | **32 / 32**, пропусков нет |
| Пар Jaccard | **435 / 435** |
| Сумма / среднее Jaccard | 35,536525 / **8,1693%** |
| Максимум Jaccard | **29,7872%** |
| Краткие 3-буквенные: всего / уникальных / на ≥2 уровнях | 840 / 146 / 113 |
| Краткие 4-буквенные: всего / уникальных / на ≥2 уровнях | 1 307 / 334 / 238 |
| Краткие 3–4: всего / уникальных / повторов сверх первого | 2 147 / 480 / 1 667 |

`retained`: `селитроварня`, `аспирантура`, `аккумулятор`, `чемпионство`,
`вегетарианец`, `окаменелость`, `субординация`, `демонстрация`.

| Полоса | Диапазон | Валидных слотов | 3-буквенных | 4-буквенных |
|---|---:|---:|---:|---:|
| 1–5 | 40–300 | 5 / 5 | 88 | 128 |
| 6–10 | 70–380 | 5 / 5 | 126 | 161 |
| 11–15 | 100–460 | 5 / 5 | 128 | 202 |
| 16–20 | 130–540 | 5 / 5 | 180 | 306 |
| 21–25 | 160–620 | 5 / 5 | 194 | 303 |
| 26–30 | 250–700 | 1 / 5 | 124 | 207 |

По слогам числа 3- и 4-буквенных слов, уникальные слова, повторы на ≥2 и ≥5
уровнях, пересечения соседних уровней и топ повторов сохранены в JSON
(`short_words.per_level`, `short_words.length_3`, `short_words.length_4`).

### 12.4. `best_found` и доказанная невозможность — разные вещи

Текущий кандидат — **`best_found`**, а не минимум: 26 валидных слотов, четыре
нарушения `recognizable_range` (слоты 26–29) и два нарушения Jaccard
(`mean` 8,1693% > 3%, `max` 29,7872% > 15%). В JSON:
`candidate.classification = "best_found_with_proven_impossibility"`,
`evaluation.best_found.is_lower_bound = false`.

Одновременно независимая проверка профилей доказывает, что **валидной
кампании из 30 слотов на текущем R не существует**: паросочетание «слот —
источник» даёт максимум **26/30**, максимальное число узнаваемых ответов у
допустимого источника — 251, старых исходников, подходящих хотя бы одному
слоту, — 8, отсутствующих букв нет. Это доказательство ограничено текущим R
и текущим пулом профилей; после новых подтверждённых оценок оно может
измениться. Оно не превращает `best_found` в оптимум и не доказывает
глобальную невозможность.

**5,3458% не является строгим минимумом.** Это составная оценка другого R:
нижние 25 слотов — `best_found`, верхние 5 — отдельная min-cost-flow оценка.
При независимом пересчёте того же списка источников как одного набора из 30
получается 5,1936% среднего и 49,1329% максимума по 435 парам; отчёт
фиксирует это в `reference.recomputed_full_pairs`. Поэтому 5,3458% — не
доказанная нижняя граница и не единая метрика 435 пар, а исторический
ориентир. Строгим минимумом его называть нельзя.

### 12.5. Команда и проверки

```sh
V2=.local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e
python3 tools/audit_selection_baseline.py \
  --proposal "$V2/round-01/selection/level_words_proposal.json" \
  --profiles .local/data01-oc-cache/profiles/97db2c28cfbdef78ef6f2f523e9f02510c27b52fb14e7beffb4ffb37bda14dd9.json.gz \
  --words "$V2/round-01/package/words.txt" \
  --previous-levels data/levels.json \
  --reference build/reports/data01/data01-continuation-20261114.json \
  --output build/reports/finishing/OPT-03.json
```

Ожидаемая сводка: `status=baseline_recorded`, `pairs=435`, `mean=0.081693…`,
`max=0.297872…`, `retained=8`, `valid_slots=26`,
`proven_impossible=true`.

Схема отчёта `selection-baseline-v1` детерминирована: без времени,
абсолютных путей и случайных идентификаторов. Поля: `r`, `inputs`,
`candidate`, `evaluation.best_found`, `evaluation.proven_impossible`,
`jaccard.pairs` (все 435 пар с числителем, знаменателем и J), `levels`,
`alphabet`, `bands`, `short_words`, `proposal_pair_check`, `reference`.
Несовпадение закреплённого R, версии профиля или сохранённых 435 пар даёт
`status=r_version_mismatch` и код 3, а не молчаливое сравнение разных наборов.

```sh
python3 -m unittest tools.tests.test_selection_baseline -v
python3 -m unittest discover tools/tests
```

Тесты проверяют ровно 435 пар, формулу `|A∩B|/|A∪B|`, пустое объединение,
явное несовпадение SHA R, несовпадение версии профиля, раздельные
`best_found` / `proven_impossible`, другой R в reference и детерминизм
отчёта. Новых ИИ-вызовов и публикации нет.

Ограничение: OPT-03 не запускает GEN-03/GEN-04/GEN-05, `validate_campaign`,
`install` и release. Доказательство невозможности действительно только для
зафиксированного R; `best_found` остаётся лучшим проверенным вариантом
ограниченного поиска. 5,3458% из DATA-01 — составная оценка другого R, а не
строгий минимум и не единая метрика 435 пар.

## 13. OPT-04: оптимизация Jaccard при неполных полосах

`tools/select_level_words.py` больше не делает ранний выход из
`refine_jaccard()`, если промежуточный кандидат недобирает полосы или слоты.
Поиск получает явный штраф за дефицит (`DEFICIT_WEIGHT`), поэтому вариант
26/30 может улучшаться, когда пул кандидатов расширяется. Штраф действует
только внутри поиска: `Selector.evaluate()` остаётся финальным валидатором и
по-прежнему возвращает `recognizable_range`/`empty_slot`; установить такой
набор нельзя.

Результат `refine_jaccard()` разделяет три состояния: `hard_infeasible_by_matching`
(паросочетание «слот — источник» не покрывает 30 слотов), `search_exhausted`
(бюджет поиска исчерпан без доказанной невозможности) и `best_found` (лучший
проверенный кандидат). Те же поля добавлены в предложение GEN-02. Seed и
входные данные не менялись: используется локальный `Random(config['seed'])`.

Синтетические тесты:
`test_incomplete_26_of_30_improves_after_pool_expansion` — неполный состав
26/30 после расширения пула до 30/30 проходит валидатор, а подмена слота
низкополосным словом даёт `recognizable_range`;
`test_one_250_plus_candidate_cannot_fill_30_slots` — один кандидат с 250+
ответами даёт `matched=1`, `hard_infeasible_by_matching=true` и не выдаётся
за `search_exhausted` или VALID. Словарь, нормализация, правила T1–T7,
`data/` и `orchestrator/*` не изменялись.

## 14. OPT-05: полный пул, max-J и распределение по полосам

`tools/select_level_words.py` переведён на полный профильный пул. Жёсткий
`shortlist_per_slot=64` больше не ограничивает поиск: предварительное
отсечение выполняется только по независимым от слота условиям и пулам
заданий, а все локально допустимые кандидаты каждого слота входят в
`admissible_pools`. Поле `search.shortlist_per_slot` сохранено в схеме
конфига для совместимости, но поиск его не использует. Кандидаты только с нарушением диапазона хранятся в
`range_pools` как штрафуемый резерв, остальные нарушения перечислены в
`pool.locally_inadmissible_by_reason`. Каждый кандидат попадает ровно в одну
из трёх групп; `pool.admissible_omitted_without_report` вычисляется и в
проверенных прогонах равен нулю. Топ-5000 по наблюдаемой частоте, метрика
покрытия частотных слов и прежний порядок `top5000_recognizable_covered`
не менялись; 32 буквы, retained 8–15 (6–15 в SOFT-B) и уникальность
исходников остаются финальными границами `evaluate()`.

Жадный этап и уточнение используют несколько seeded restarts. Начальный seed
берётся из конфига (по умолчанию `20261111`), производные seed считаются как
`sha256(seed|фаза|политика|restart)` и не зависят от процесса. Уточнение
делает обмены исходников между слотами, явно штрафует непокрытые полосы,
недостающие буквы, потерю старых слов и превышение выбранного порога
максимального Jaccard; верхняя часть допустимого диапазона ответов — только
последний tie-break. Доступны `--time-limit`, `--max-checks`, `--seed` и
`--policy`/`--policy-sequence`. Финальный `Selector.evaluate()` не ослаблен:
временное штрафуемое состояние не становится установкой.

Временная лестница политик передаётся явными аргументами `Selector` и не
меняет глобальные значения `tools/level_selection_config.json` (STRICT
остаётся значением по умолчанию):

| Политика | Верхняя полоса | mean J | max J | retained |
|---|---:|---:|---:|---:|
| STRICT | 250 | 3% | 15% | 8 |
| SOFT-A | 230 | 4% | 18% | 8 |
| SOFT-B | 210 | 4.5% | 20% | 6 |

Контрольный прогон на закреплённом входе v2
`.local/data01-oc-cache/runs/eb800874.../round-01/package/words.txt`
(20 106 слов, профили `97db2c28...json.gz`, не `data/`) с ограничением
180 секунд на политику:

```sh
python3 tools/select_level_words.py \
  --profiles .local/data01-oc-cache/profiles/97db2c28cfbdef78ef6f2f523e9f02510c27b52fb14e7beffb4ffb37bda14dd9.json.gz \
  --words .local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e/round-01/package/words.txt \
  --previous-levels .local/data01-oc-cache/runs/eb80087458575123948d8906124891bf24f3ff181c0dfac2f12b25cfed4b773e/previous_levels.json \
  --reports build/reports/finishing/OPT-05-final3 --policy-sequence --time-limit 180
```

| Политика | Статус | Установка | retained | Нет букв | mean / max J | Цель mean / max | Проверок | Таймаут |
|---|---|---|---:|---|---:|---:|---:|---|
| STRICT | infeasible | False | 7 | нет | 6.5728% / 27.1875% | 3.0000% / 15.0000% | 79 197 | True |
| SOFT-A | infeasible | False | 7 | ъ | 8.9016% / 71.9298% | 4.0000% / 18.0000% | 64 427 | True |
| SOFT-B | search_exhausted | False | 6 | ъ | 8.7496% / 37.2340% | 4.5000% / 20.0000% | 59 273 | True |

Лучший вариант текущей ревизии selector (`01ba79fe…`) с ограничением
времени — SOFT-B: 30/30 полос, 30 разных исходников, 6 старых, 0 пустых
слотов, mean J 8.7496%, max J 37.2340% при цели 4.5%/20%; `ъ` не покрыт,
поэтому вариант диагностический. Верхняя полоса 26–30 в SOFT-B содержит
всего семь допустимых кандидатов; минимально возможный максимум J среди
любых пяти из них — 25.4144%, поэтому цель 20% недостижима на этом входе
без изменения политики или пополнения пула. Ограничение 180 секунд
сработало во всех трёх попытках (`timed_out=true`); SOFT-B успел выполнить
0 из 16 уточняющих стартов и 59 273 проверки `evaluate`, то есть
остановился раньше, чем неограниченный прогон.

Отдельный неограниченный диагностический прогон SOFT-B (`OPT-05-softb7`,
selector `f23efe44…`) выполнил 16/16 стартов, 62 423 проверки и нашёл
комбинацию лучше по покрытию: все 32 буквы, mean J 8.9006%, max J 34.2105%,
retained 6. Статус — `search_exhausted`, `installable=false`,
`strict_installable=false`; оба нарушения — mean/max Jaccard. Текущая
ревизия selector (`01ba79fe…`) воспроизвела тот же результат в
`OPT-05-current`: 62 423 проверки, 16/16 стартов, `timed_out=false`.
Это подтверждает, что ограничение времени само по себе не доказывает
невозможность, но делает точку остановки зависимой от нагрузки машины.
Детерминированный режим без `--time-limit` (или с `--max-checks`) остаётся
воспроизводимым. Лучшая найденная комбинация SOFT-B (сохранена в
`build/reports/finishing/OPT-05-softb7/level_words_proposal.json`):

`чемпионство, аккумулятор, объективность, вегетарианец, полицейская,
субординация, страховщик, передозировка, юрисконсульт, благоразумие,
селитроварня, безрассудство, прослушивание, журналистика, поздравление,
благодарность, распорядитель, аплодисменты, демонстрация, пуэрториканец,
проблематика, складирование, электромагнит, подстрекатель, кинофестиваль,
контрабандист, опрыскиватель, консерватизм, документалист, полсантиметра`.

Максимальная пара Jaccard 34.2105% — `опрыскиватель`/`подстрекатель`;
следующие 32.44% — `документалист`/`аплодисменты`. Время-ограниченный SOFT-B
из `OPT-05-final3` дал 30/30 полос, 6 старых, но пропустил `ъ`; это
диагностика, а не VALID: `installable=false`, `strict_installable=false`,
`data/` не изменён.
STRICT и SOFT-A не проходят уже по числу кандидатов верхней полосы (1 и 4
на пять слотов); их `infeasible` относится к зафиксированному входу, а не
доказывает невозможность для другого словаря или после завершения LEX-04.

Синтетические тесты OPT-05: достижимый полный пул с max J ≤15%; невозможный
набор с max J=1.0, который остаётся диагностикой; отсутствие обрезки пула до
64 (80 кандидатов в тесте); стабильные SHA-256 JSON/CSV/Markdown и
`search_stats`; таймаут с нулевым бюджетом, который не выдаётся за установку;
явная лестница STRICT → SOFT-A → SOFT-B без изменения глобальных default.
Полный набор `tools/tests`: 383 теста, OK.
