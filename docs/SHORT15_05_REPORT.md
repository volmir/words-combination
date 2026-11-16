# SHORT15-05 — PASS

Дата: 2026-11-16. Исходный BLOCKED из предыдущей попытки снят явным
разрешением владельца в docs/CAMPAIGN.md. Прежний диагностический отчёт
сохранён локально: build/reports/short15-05/previous-blocked-report.md.
Ни Git-история, ни защищённые пути orchestrator/ не изменялись.

## Исходное состояние

SHORT15-01: профиль и Kotlin-контракт существуют; SHORT15-02: staged-пакет
и положительный GEN-04 сохранены в build/reports/short15/; SHORT15-03:
установлена та же кампания; SHORT15-04: прежний gate описан в
SHORT15_RELEASE_GATE.md. Исторические результаты не выдаются за новый прогон.
В рабочем дереве при начале был только прежний docs/SHORT15_05_REPORT.md.

| Файл в data/ до изменения | SHA-256 |
|---|---|
| `words.txt` | `3b8a1339df5485ae2c5493cec3b5297e84173b94e57cf253c3543a8c85efc997` |
| `campaign.json` | `eb4408731b453104bad3f05db4a69397d1e4d208f9193adcdd440fc5ecb1fadc` |
| `definitions.db` | `6d0cd051b75babdb4435777f29e00419ff3ba7dbc8c0d9ae602cff7b18869d7f` |
| `manifest.json` | `445a9958081fc4788041bbd92687a231403d7ebe04aa3f3551040db575a288c0` |
| `word_sources.jsonl.gz` | `99fb9af8eb07d376358358b03df5b3b8ec47332fa48cfff275c58b11104185c8` |

Проверены штатными build_dictionary.sha256_file и
prepare_frequency.validate(path, expected_digest(manifest)):

- `.local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz`:
  `98bebfb8013064d23bc6f0981b64cd097f64e098c9e434ebb97684c9606cc2da`.
- `.local/frequency/frequencywords-2018-ru/ru_full.txt`:
  `32dfd94138aea2667ec455dcc8512029e39bd4b8ea70b09410d569b69d511a30`.

Проверка частотного списка покрыла формат всех строк. Источники совпадают
с manifest и заданием; сеть не использовалась. Подготовленный архив и Excel
в проекте и проверенных соседних каталогах отсутствуют; использован текст
задания. campaign_review_import.py существует, но его неподдерживаемые
операции длины T3, окончания/длины T5 и лексикона не поручались импортёру.

## Словарь и определения

Проверено 23 лексемы (18 ответов на ист, 3 профессии, антитело, менталист).
Все имеют существительные в закреплённом дампе. Новые 15 лексем:
акмеист, анекдотист, атомист, бортник, дуалист, дуатлонист, каноист, канонир, колумнист, меланист, мелодист, методист, моделист, таксатор, талмудист.

Чистая регенерация дала 20 120 слов: 15 добавлено, менталист удалён,
остальной состав совпал с установленным. Для акмеист помета филол. признана
специальной только у этой лексемы. Адресные исключения обходят лишь
перечисленные частотные причины и требуют существующего допустимого
значения существительного; морфологические и запрещённые пометы не обходятся.
Политика находится в selection_config.json, старые overrides не применялись.

У всех 15 новых лексем есть определения, URL и строки происхождения.
Источники итогового provenance побайтово по полям совпали с результатом
extract_records для этих лемм. SQLite integrity_check — ok.
Повторная сборка определений дала идентичную БД user_version=9:
31 369 толкований, покрытие 20 117/20 120. Собственных определений нет.
audit.passed=false, rights_review.passed=false, политика
wiktionary-provisional-v1 сохранена; новых waiver нет.

В word_metadata у 20 профессий источник категории —
owner:SHORT15-05:2026-11-16. Остальные категории перенесены из прежней БД
с исходной атрибуцией; формы/родство и определения извлечены заново.
Сводный индекс категорий пересчитан штатной category_index.
Повторно использованы 352 прежние ИИ-оценки: совпали все признаки, id и хэши
контекста. Новых ИИ-решений/сетевых вызовов — 0, неподтверждённые оценки
не подменены suitable.

## Условия

T1: [9,10,11,12,13,14,15,16,17,18,19,20,21,22,24].
T2: [3,3,4,4,5,5,5,6,6,6,7,7]. Оба типа считают ответы 4+.
T6 уровня 14: n=4, все 20 утверждённых профессий, уникальных.
Финал: T3 8+, hintTarget=антитело; T5 ист/7+; T7 у/n=8, общий ввод 3+.
На фактическом кандидате T3 имеет ровно 24 ответа:
анекдотист, антитело, декатлон, дилетант, документ, дуатлонист, колумнист, комедиант, медалист, меланист, мелодист, мескалин, методика, методист, моделист, отместка, отметина, семантик, стотинка, студентик, студентка, талмудист, толкание, уклонист.
T5 имеет ровно 18 ответов:
акмеист, аметист, анекдотист, атомист, дантист, дуалист, дуатлонист, каноист, колумнист, медалист, меланист, мелодист, методист, моделист, окулист, талмудист, танкист, уклонист.

Остальные назначения, исходники, R/M, этапы [3,2,3,2,3,2], 49 звёзд,
финал 4/5 и Мастер 49/49 сохраняются адресным staging-инструментом.
Эталон каменистость: 299→302 (акмеист, атомист, каноист); остальные эталоны
не изменились. Исторический levels.json сохранён побайтово.

## Тесты до изменения контрактов

Обновлённая синтетическая short15 в CampaignTest упала на прежнем Kotlin
валидаторе (1 из 1). Python-тесты профиля упали в 4 проверках из 8.
Адресный тест cleanup упал на старой политике. После реализации эти проверки
прошли; добавлены отрицательные тесты прежних/ошибочных параметров.
Логи: red-kotlin.log, red-python.log, red-cleanup.log, green-contract.log
в build/reports/short15-05/.

## Изменённые файлы

- `core/src/main/kotlin/com/example/sostavslova/core/CampaignValidator.kt`
- `core/src/test/kotlin/com/example/sostavslova/core/CampaignTest.kt`
- `core/src/test/kotlin/com/example/sostavslova/core/Short15ProductionCampaignTest.kt`
- `core/src/test/kotlin/com/example/sostavslova/core/WordSearchTest.kt`
- `data/campaign.json`
- `data/definitions.db`
- `data/golden_search.json`
- `data/manifest.json`
- `data/word_sources.jsonl.gz`
- `data/words.txt`
- `docs/CAMPAIGN.md`
- `docs/PROJECT_STATUS.md`
- `docs/SHORT15_05_REPORT.md`
- `tools/apply_short15_balance.py`
- `tools/campaign_profiles.json`
- `tools/campaign_profiles.py`
- `tools/clean_dictionary.py`
- `tools/selection_config.json`
- `tools/tests/test_campaign_profiles.py`
- `tools/tests/test_campaign_review_export.py`
- `tools/tests/test_campaign_review_import.py`
- `tools/tests/test_campaign_validation.py`
- `tools/tests/test_clean_dictionary.py`
- `tools/tests/test_short15_balance.py`

Kotlin production-код ограничен числовыми рядами Short15CampaignValidator;
release-30 не менялся. Синтетические и интеграционные тесты проверяют новый
баланс и отклоняют прежние значения. apply_short15_balance.py переносит только
согласованные поля кампании, категории и исторический ввод в staging.
UI, открытие уровней и сохранения не менялись.

## Сборка и установка

Все команды запускались из корня. Локальные логи/артефакты —
`build/reports/short15-05/` (не входят в Git). Основная последовательность:

1. `clean_dictionary.py --dump .local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz --manifest data/manifest.json --cache tools/cache/cleanup --reports build/reports/short15-05/cleanup --out build/reports/short15-05/clean --campaign-v2` — built.
2. `apply_short15_balance.py --staging build/reports/short15-05/clean --baseline data --output build/reports/short15-05/clean/manifest.json` — побайтовое сохранение исторического levels.json и ролей прежнего конвейера.
3. `build_local_definitions.py --dump … --manifest build/reports/short15-05/clean/manifest.json --reports build/reports/short15-05/definitions --out data --prepare-only` — регенерация без записи в data/.
4. `apply_short15_balance.py --category-index …/definitions/category_index.json --baseline-database data/definitions.db --output …/reviewed_categories.json`; повтор `build_local_definitions.py` с `--category-index …/reviewed_categories.json --reports …/definitions-reviewed --prepare-only` — обе проверки прошли. Сводный индекс категорий пересчитан; повтор БД дал идентичные байты.
5. Сформирован отдельный `package/` из результата генератора; `pick_level_words.py --export-hints …/hints-input.jsonl` и `:core:auditDefinitionHints` с абсолютными путями — JVM-аудит всех определений успешен, 18 002 пригодные подсказки.
6. `pick_level_words.py --prepare-only` → адресное сопоставление 352 точных прежних LEX-04 пар → `assess_word_suitability.py --pairs-file …/semantic-pairs.txt --cache .local/data01-oc-cache/suitability --provider opencode --model opencode/deepseek-v4.1-flash#max --max-new-decisions 0` — 352 cache hit, 0 новых решений, 0 сетевых вызовов. Для всех пар проверены исходный контекст и assessment_sha256.
7. `pick_level_words.py` с актуальными words/manifest/database/category-index, прежним levels.json, новыми JVM-hints и совместимыми suitability — GEN-01 готов. Снимок: `profile-cache/bab996c28bb4483b853d932afbe7a1fe44aa664d04b62cca6c38259e442dffbd.json.gz`.
8. `select_level_words.py --profile short15-release-v1 --pinned tools/short15_candidate.json` с новым снимком и словарём — GEN-02 valid: 15/15 полос, 3 старых исходника, mean Jaccard 6,51725%, max 23,26389%, 105 пар.
9. `build_campaign.py` с новым снимком, proposal GEN-02 и реальными входами — GEN-03 valid с первой попытки. Чтобы сохранить все не затронутые заданием назначения, установлен адресный кандидат `apply_short15_balance.py --campaign data/campaign.json --output …/editorial_campaign.json`, а не произвольные новые назначения GEN-03. Полевая сверка подтвердила, что изменены только разрешённые параметры задания.
10. `validate_campaign.py` на адресном кандидате с новым профилем/снимком/словарём/БД — GEN-04 valid, installable=true, jvm_checked=true; unreachable/balance_deviations/parity_deviations пусты. Обязательный JVM-аудит не обходился. 41 комбинация R, 49 звёзд, finale_reached=true. T6: 20 слов, узнаваемых 9 при минимуме 5; глобальных 52 при минимуме 40.
11. В package скопированы только validated_campaign.json и актуальный validation_report.json. quality_report.json сформирован из фактических метрик GEN-04 и SHA файлов, затем SHA256SUMS — из всех семи артефактов и двух отчётов. `install_short15_package.py --package …/package --backup .local/short15-05-rollback-data --dry-run`, затем та же команда без dry-run — прошли. До положительного GEN-04 установленная data/ не менялась.

SHA256SUMS согласован со всеми байтами пакета и проверен installer.
Installer добавляет campaign_source в установленный manifest; поэтому его SHA
отличается от staged manifest, что предусмотрено штатным инструментом.
Смысловое содержимое адресного и validated JSON совпало; различие SHA объясняется
каноническим форматированием. Пакет сохранён в build/reports/short15-05/package/,
откат — .local/short15-05-rollback-data/SHA256SUMS.rollback.

## Итоговые SHA-256 установленной data/

| Файл | SHA-256 |
|---|---|
| `words.txt` | `9c1aed9e0011e4173dffd0f4281832d821d4de90623d19273f7fc8e68c212489` |
| `word_sources.jsonl.gz` | `7f1f4e259916d5f9cb822737f6cf9886c3cef98215795ede69bf7a67eb2f4c9b` |
| `levels.json` | `e4ded7a9bdfc5855c69bd167abb03c25d5291652a1504956d3a0e99e4742e73a` |
| `golden_search.json` | `e80bedfdbc04d60935d632b2945c13a22700280ca181f60dcdd9e4b0e4132c8b` |
| `definitions.db` | `406d127a444b02d9eaddb30e820dd7cadc200b967338554a552f81468ee6c097` |
| `campaign.json` | `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e` |
| `manifest.json` | `881bff5a44a234169326105d25226fd878f9b5e01028d3094f38481fc4ab1213` |

## Результаты проверок

- Первоначальный `python3 -m unittest discover tools/tests` до установки — **447 тестов, OK**. После установки внешний gate выявил устаревшие ожидания редакторских тестов; исправление и повторные результаты приведены ниже.
- После установки: `python3 -m unittest tools.tests.test_short15_balance tools.tests.test_campaign_profiles tools.tests.test_clean_dictionary tools.tests.test_campaign_validation tools.tests.test_short15_install` — **69 тестов, OK**.
- Узкий `CampaignTest` — прошёл, включая отказы на прежние/ошибочные числовые параметры.
- `bash ./gradlew --offline :app:verifyDictionaryLicenses :core:test :app:assembleDebug --console=plain` — **BUILD SUCCESSFUL**, **137 core-тестов, 0 failures/errors**. syncDictionaryAssets выполнен штатно.
- Отдельная проверка установленного пакета verify_installed — прошла; words/campaign/definitions внутри debug-APK побайтово совпали с data/. manifest.json в APK не упаковывается (проверяется сборкой).
- Фактический интеграционный JVM-тест подтвердил отсутствие менталист в WordIndex и отказ GameState; T3 8+/антитело/24 ответа, T5 ист/7+/18 ответов, T7 у/8 с зачётом 3-буквенного слова; T6 20 уникальных профессий/n=4; 15 уровней/49 заданий, финал 4/5 и Мастер 49/49.
- `rebalance_campaign.py` — диагностический GEN-05 **exhausted**, 7 кандидатов, выбран исходный №0. Пять кандидатов прошли GEN-04, два отклонены; balanced_campaign.json не создан. Стартовые вероятностные цели симулятора не достигнуты. Альтернативы не устанавливались и не выдаются за утверждённый баланс.
- `python3 tools/build_game_data.py --check-release --out data` — ожидаемый **exit 2**, релиз заблокирован непройденным ручным аудитом. Это проверка запрета, не release-сборка.
- `git diff --check` — чисто. Защищённые пути не изменены; git commit/push/checkout/reset/add не запускались.

## Продолжение после отказа внешнего gate — 2026-11-16

Сохранённые изменения и установленный пакет оставлены на месте. До правок
повторены редакторские тесты: 26 тестов, те же 2 failures / 1 error.
Причина — ожидание прежнего T6 (6 вариантов, n=5) в экспорте и попытка
повторно добавить уже утверждённые профессии в тестах импорта.

В `test_campaign_review_export.py` проверяются n=4, точный список всех 20
профессий и 31 369 определений. В `test_campaign_review_import.py` временная
копия кампании/БД специально лишена двух добавляемых вариантов и получает
синтетические категории sport/other с согласованными SHA: это сохраняет
проверки T6_ADD, обязательности источника и ручного подтверждения категории.
Установленная data/ не менялась. Добавлен отдельный отказ на повтор T6_ADD.
Проверки импортёра не ослаблялись; редакторская фикстура не является
производственной кампанией и не используется для GEN-04.

Повторные проверки:

- Штатные `build_dictionary.sha256_file` и `prepare_frequency.validate`
  подтвердили оба закреплённых локальных источника и их соответствие manifest.
- `python3 -m unittest tools.tests.test_campaign_review_export tools.tests.test_campaign_review_import` — 27 тестов, OK.
- `python3 -m unittest discover tools/tests` после исправлений и на установленной
  data/ — **448 тестов, OK** (82,630 с), лог `retry-tools-full.log`.
- `verify_package` и `verify_installed` из `install_short15_package.py` — PASS.
- GEN-04 повторён на исходном staging-пакете с тем же profile-cache:
  `valid`, `installable=true`, `jvm_checked=true`, все три списка отклонений
  пусты. Лог `build/reports/short15-05/retry-package-gen04.log`.
  Попытка связать staging-снимок с установленным manifest была отклонена
  как stale: installer добавляет campaign_source; обязательная проверка SHA
  сохранена. Установленные артефакты отдельно сверены штатным installer.
- `bash ./gradlew --offline :app:verifyDictionaryLicenses :core:test :app:assembleDebug --console=plain` — BUILD SUCCESSFUL; core/debug в этом запуске UP-TO-DATE.
- `bash ./gradlew --offline :core:cleanTest :core:test --console=plain` — BUILD SUCCESSFUL, 137 тестов, 0 failures/errors/skipped.
- Повторный фактический пересчёт: 20 120 слов, +15 / −1 относительно rollback,
  T3=24, T5=18; SQLite integrity_check=ok, определения всех 15 новых слов есть.
- `git diff --check` — чисто; `git diff --name-only -- orchestrator` — пусто.

Генераторы повторно не запускались: данные и их SHA сохранились, исправлены
только затронутые редакторские тесты. Сохранённые результаты генерации и
новые проверки целостности/GEN-04 относятся к одному и тому же пакету.

## Не проверено и ограничения

Полный `orchestrator/run.sh --check full` оставлен внешнему оркестратору по
прямой инструкции владельца; его runtime/логи не затрагивались. Инструментальные
тесты/ручная игра на устройстве, lint, androidTest-компиляция и release-сборка
в этой задаче не запускались. Публикация не выполнялась и не разрешена.
Результат PASS относится к утверждённому числовому балансу, воспроизводимому
словарю и положительному GEN-04; не означает достижения вероятностных целей
GEN-05 или прохождения ручного аудита прав. Смежные задачи не выполнялись.
