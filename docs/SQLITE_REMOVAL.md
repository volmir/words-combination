# Удаление проектного SQLite-стека — 2026-11-17

Результат: проектные runtime, генераторы, tests и gates переведены на текстовый JSON. Активных проектных зависимостей прежнего движка и бинарных БД в исходниках/APK/AAB нет. История прав, локальные неиспользуемые кэши и AndroidX/платформа выделены ниже. Полный connected-прогон и ручная приёмка PRE04-17 этим отчётом не заменяются.

## Исходное состояние и неизменность

- Буквенная лестница HINT-01/HINT-02 уже была реализована в рабочем дереве; её тесты, цены, выбор T4 и миграция сохранены. Предшествующие незакоммиченные HINT-правки включены в итоговую интеграцию.
- Прежняя `data/definitions.db`: 1 818 624 байта, user_version 10, SHA-256 `73d09ac2d445ad7f4c30226248b30769d6e46bd6858a8907750d16561b258d7d`. Размер, версия и хэш проверены до удаления.
- `data/campaign.json` ДО и ПОСЛЕ: 9 651 байт, SHA-256 `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e`. Байты, campaignId, словарь, levels, golden_search и word_sources не менялись.
- Канонические 39 текстов: `docs/owned_hints.json`, SHA `061633244ec968aeec2e0dc50aca1b61ecb8ca735590c5510938ab5085b711b0`; approval SHA `50ea8dd86af6a32a13165e21378273b9734557f6980cc5ccea518a7d04e3e128`; audit SHA `9f6bec48459ef460fab2d11f50745bf74db9c0322d4571f5092f1378daedaff1`. Все три файла и `tools/owned_hint_index.json` побайтово сохранены.
- Предварительный исчерпывающий поиск по tracked-файлам с путями, строками, снимком T4 и APK: [SQLITE_REMOVAL_BEFORE.json](audits/SQLITE_REMOVAL_BEFORE.json). Итоговая машинная сверка: [SQLITE_REMOVAL_AFTER.json](audits/SQLITE_REMOVAL_AFTER.json).

## Контракт JSON и 11 текстов

`data/owned_riddles.json`: **1 666 байт**, SHA-256 `05e3c05099fe38648834a6b9f70f458cf51238edc7bacdc85d8af3932ad11f63`. Схема `owned-riddles`, версия 1, CC0-1.0. UTF-8, сортировка ключей, компактные разделители и завершающий LF. Нормализация ключей ё→е. Ровно 11 целей / 6 T4-заданий уровней 9–14; каждый текст дословно равен утверждённому значению hint. Остальные 28 текстов не поставляются.

| Ключ | Уровень | SHA-256 текста UTF-8 |
|---|---|---|
| артист | 14 | `c9b8984c48c407ef2dc4ec36b51d3fed57f63f9cc03fb6468cb4c4d9f682671f` |
| банк | 14 | `e71b218c912a04ca552dbfeaf0f53364f244e2d54cc2ce85074aff006499f006` |
| билет | 12 | `00502b29f77c4bd856879ddad25596a677dea728f2d2eeb40ed3846b2cd7b19c` |
| весна | 13 | `357cb5a9f1881845a26205b2117681d9e0b2f8a615834325f06fe95a6a137415` |
| десерт | 11 | `e7307e9c64fac5fdd5da64700faf03741d8338424bb3dedb0d47c48148baf25e` |
| ковер | 13 | `4a5fb5c8dd965c6067347b14dbdb9601b29ea04281c2a7f14ce051aa790b5583` |
| отель | 11 | `f7a395314daf41b746fbb6d1442bcbee21fcad27379d7334e7e2b1dbe6088d69` |
| проблема | 12 | `4629da32dc874ff607338f51251180043822b630c800451f46437c7b9f17c3f0` |
| работа | 9 | `ee22f3cf344a6476c1adbf8b75ac01c723597ef74c155a77ddb461be7ca4cd2f` |
| стадион | 14 | `8d643e46c877d5fbbef47aaec75ac72fbf968753586a6d3642159d4eb07ad669` |
| утро | 10 | `6c22f6109217d8603e9589ccc112b9fd96d99238c875240c585ab9dc09ca456b` |

`build_owned_hints.py` проверяет роли всех 39 текстов, полный пул 20 профессий T6, происхождение индекса и точные SHA authoring/approval/audit. `owned_riddles_contract.json` фиксирует существующие свидетельства; новые решения владельца и ручной аудит не создавались. Историческое database_sha256 в неизменном аудите остаётся evidence прежнего пакета; точный SHA всего аудита проверяется, JSON дополнительно сверяется побайтово со свежей сериализацией дословного поднабора. Подмена даже этого исторического поля отклоняется. Повторяющийся ключ, отсутствие/добавление цели, пустой/изменённый текст, невалидная версия/лицензия, недостающие права или неверный manifest дают FAIL.

## Удалённые пути и заменённые потребители

Удалены ровно идентифицированные файлы:

- `data/definitions.db` и генерированная копия `app/src/main/assets/definitions.db`.
- `app/src/main/kotlin/com/example/sostavslova/data/SqliteDefinitionsRepository.kt` (включая install/copy, SHA-cache, SQLiteOpenHelper и SQL-запросы).
- `app/src/main/kotlin/com/example/sostavslova/data/DefinitionsRepository.kt` (устаревшие интерфейс и модель Definition).
- `app/src/main/kotlin/com/example/sostavslova/ui/components/DefinitionBlock.kt` (оставшихся потребителей толкований нет).
- `app/src/androidTest/kotlin/com/example/sostavslova/data/SqliteDefinitionsRepositoryTest.kt` (SQL-физика/копирование заменены проверками JSON и upgrade).
- `tools/owned_hints_toolchain.py`, `tools/owned_hints_toolchain.json`, `tools/prepare_owned_hints.py`, `tools/tests/test_owned_hints_toolchain.py`.
- Собственный закреплённый runtime 3.37.2, Ubuntu `.deb` download/recovery, LD_LIBRARY_PATH-worker, проверка glibc/x86_64 и сравнение бинарных страниц больше не являются зависимостями проекта. Gradle-библиотеки и их версии не менялись.

Переписаны потребители (полные точные пути также в машинном реестре):

- Runtime: `data/RiddlesRepository.kt` (JsonRiddlesRepository, строгий SHA/schema/keys/serialization), `di/AppContainer.kt`, `WordsApplication.kt`, `ui/game/GameViewModel.kt`, `GameScreen.kt`, `CampaignTasks.kt`, `ui/screens/Screens.kt`, `res/values/strings.xml`. loadRiddles читает точный текст, warmup/темы/толкования и fallback T4 удалены. Повреждённый обязательный asset даёт loadError; found/star state не заменяется пустым.
- Gradle: `app/build.gradle.kts`, `.gitignore`: JSON в BuildConfig, hash/license/rights-gates, строгий whitelist assets, preBuild/release. `verifyDefinitionsAudit` сохраняет имя совместимости, но выполняет строгую JSON-проверку; waiver не допускается.
- Собственные тексты/права: `tools/build_owned_hints.py`, `owned_hints.py`, `owned_hints_rights.py`, новые `owned_riddles_contract.json`, `check_text_storage.py`.
- Генерация/подготовка: `tools/build_game_data.py`, `build_local_definitions.py`, `build_dictionary.py`, `clean_dictionary.py`, `prepare_v2.py`, `apply_short15_balance.py`, `build_campaign.py`, `campaign_profiles.py`, `pick_level_words.py`, `rebalance_campaign.py`, `validate_campaign.py`. Они используют явный `lexical-preparation` JSON (`lexical_data.json`) для исследовательских смыслов/метаданных в staging, без бинарного промежуточного формата. Эти тексты не копируются в assets. Требования к локальному дампу/частоте, происхождению, категориям, достижимости и JVM сохранены; словарь не перегенерировался.
- Редакторские пути/установка/аудит: `tools/campaign_review_export.py`, `campaign_review_import.py`, `install_short15_package.py`, `audit_public_package.py`, новый `lexical_json.py`. Издатель сохраняет транзакцию/откат; runtime installer требует утверждённые загадки. Опциональное сравнение prose работает только с текстовым JSON.
- tests/fixtures: все найденные SQL-фикстуры переведены на JSON; проверки категорий, сохранения слов, происхождения/лицензий, мутаций, rollback, детерминизма и старых состояний сохранены. Физические SQL-schema/free-pages/install-cache тесты удалены как неприменимые. Три теста удалённого DefinitionBlock заменены покрытием штатных загадок. Новые JsonRiddlesRepositoryTest, CampaignJsonUpgradeTest, OwnedRiddlesUiTest, OwnedRiddlesAssetTest, test_owned_riddles_json, test_owned_riddles_portable, test_text_storage_contract. Нет новых skip/Ignore/Disabled.
- Актуальные документы: `AGENTS.md`, `README.md`, `docs/PROJECT_STATUS.md`, `OWN_HINTS_TOOLCHAIN.md`, `LEVEL_HINTS.md`, `OWN_HINTS_VERIFICATION.md`, действующая часть `CAMPAIGN.md`, `legal/THIRD_PARTY_NOTICES.txt` и About. Прежние инструкции отдельно помечены историческими и отсылают к текущему контракту.

## Проверки

| Статус | Команда/сценарий | Факт |
|---|---|---|
| PASS | `python3 -m unittest discover tools/tests` | 493 теста, 0 failures/errors/skipped |
| PASS | `:core:test --tests "*OwnedRiddlesAssetTest" --tests "*HintRulesTest" --tests "*HintLadderTest"` | 28 адресных тестов |
| PASS | `bash ./gradlew --offline :core:test` | 161 тест, 0 failures/errors/skipped |
| PASS | `:app:verifyDictionaryLicenses :app:verifyOwnedHints :app:verifyRuntimeNotices :app:verifyPrivacyPublication :app:verifyDefinitionsAudit` | Все строгие gates; точные права JSON, notices и privacy |
| PASS | `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` | Компиляция/APK; lint 0 errors, 18 warnings |
| PASS | `:app:assembleRelease :app:bundleRelease` с `--offline` | Строгий gate без waiver; release APK без подписи владельца, публикация не выполнялась |
| PASS | `python3 tools/build_game_data.py --check-release --out data` | JSON/evidence publishable=true; это gate текстов, не разрешение публикации всего приложения |
| PASS | `python3 tools/audit_public_package.py --apk app/build/outputs/apk/debug/app-debug.apk --profile debug --output /tmp/json-debug-package-audit.json` | Whitelist, байты assets, word_sources, runtime notices/markers и native provenance |
| PASS | ZIP-проверка debug APK, release APK и AAB | Нет .db/.sqlite*/движка, есть точный JSON из 11 текстов |
| PASS | Адресная instrumentation на 2201117SY / Android 13, 67 тестов суммарно | 66 PASS во втором пакете + исправленный адресный OwnedRiddlesUiTest PASS отдельно |
| PASS | DataStore upgrade + новые VM/store, повторные нажатия, поворот/Activity.recreate | Цель, слова, звёзды, frontier, visible steps и paidUnits сохранены, нет двойного расхода или campaign-mismatch |
| PASS | Штатный экран MainActivity/WordsApp, уровни 9–14 | Все 11 точных текстов действительно displayed; без fallback |
| PASS | `git diff --check` и остаточный поиск по исходникам/архивам | Нет активной проектной зависимости или бинарной БД |
| НЕ ПРОВЕРЕНО (оставлено PRE04-17) | Полный `connectedDebugAndroidTest` и ручная приёмка | По заданию дорогой полный прогон не запускался |
| НЕ ПРОВЕРЕНО (отдельный REPRO/F-Droid) | Побайтовая воспроизводимость подписанного APK, окончательный recipe/публикация | Не подменяются JSON-детерминизмом или offline debug build |

У всех Gradle-команд выше режим `--offline --console=plain`, wrapper вызывается через `bash` (его executable bit не менялся). Первые тесты нового JSON упали на отсутствии asset; затем перенесены реализация и проверки. Промежуточные несоответствия старых фикстур/ожиданий исправлены. Один пробный полный Python-прогон справедливо отказал по изменившемуся во время работы code fingerprint; финальные 493 теста выполнены на стабильном коде. Первый UI-пакет: экран телефона был выключен; повтор после пробуждения дал 66/67. Новый тест ошибочно ждал lazy-карточку уровня 1 при прокрученной к уровню 14 карте; затем исправлены готовность карты и ожидание смены Compose-root между маршрутами. Итоговый тест проверил все 11 текстов. Ни тайм-ауты, ни FAIL не отключались/не увеличивались.

### Offline без прежнего toolchain

`test_owned_riddles_portable` копирует только tools/docs/data, без Git, локальных кэшей и готового JSON; запрещает импорт модулей движка и создание сокетов. Генерация даёт побайтово тот же JSON и manifest. Отдельная чистая копия 420 исходных файлов `/tmp/words-json-clean-source`, без `.git`, `.local` и build/, собрана `bash gradlew --offline :app:assembleDebug --console=plain`: **PASS, все 47 задач выполнены, 25 s**. Использованы установленные JDK 21/Android SDK и общий Gradle-кэш. Собственный генератор не требует .deb, архитектурно закреплённого worker или сети; загрузка обычных зависимостей в пустой общий Gradle-кэш отдельно не проверялась.

## Упаковка и измеренный размер

| Артефакт | Байты | SHA-256 |
|---|---|---|
| Прежний debug APK | 13777247 | `8ecc829a70a60ca2aa4f3554d926d95091f3c4529e215f9c08e630f110c27ac4` |
| `app-debug.apk` | 13243940 | `231cf5ea662da64e1addf3694036ae1b414d48da12a908614a8c08c810b021db` |
| `app-release-unsigned.apk` | 2673081 | `fe50a8b8f7792eb26782bbc68d0515eeb07f91d1fe0a10110b0581130841a416` |
| `app-release.aab` | 4510923 | `3af650df4b5f9bcd65bd1278e67589943954fea4b003543561b117e6360ecd0f` |

Измеренная экономия debug APK — **533 307 байт**. Прежняя запись БД в ZIP: 1 818 624 байта / compressed 505 771; JSON: 1 666 / compressed 830. Остальная разница включает удалённый runtime/UI-код и ресурсы. APK до изменения сохранён локально для сравнения.

Полный список assets каждого готового артефакта — в AFTER JSON. Debug содержит Apache-2.0.txt, CC-BY-SA-4.0.txt, LIBRARY_NOTICES.txt, LICENSE, RUNTIME_LIBRARIES.json, THIRD_PARTY_NOTICES.txt, campaign.json, levels.json, word_sources.jsonl, words.txt, owned_riddles.json. Release дополнительно содержит созданные AGP dexopt-профили. Runtime JSON во всех трёх архивах равен data/ побайтово.

## Устройство и целостность пользовательских файлов

Устройство: 2201117SY, Android 13, serial 5PMRWCOV9PSK4XAI. Установка только `adb install -r -t`; **pm clear/uninstall и удаление пользовательского DataStore/APK не выполнялись**. Тесты используют отдельные временные хранилища. Исходная приватная БД оставлена нетронутой: нет необходимости доказывать её право на удаление. Текущий код её не открывает.

| Файл установки | Размер до/после | SHA-256 до = после |
|---|---|---|
| `files/datastore/progress.preferences_pb` | 292 | `b84d5e2cf95498c8697d217f5a7295e0921f204d506da059da2c984898b20531` |
| `databases/definitions.db` | 1822720 | `ffe5e6f66298610ef239863bc9583512344e42da0229f90ca0c6fb6aaf6f9688` |

Временный MIUI app-op 10021 для Activity-тестов возвращён в исходный ignore. Runner не менялся. [Снимок штатного экрана уровня 12](audits/owned-riddles-level12.png) получен автоматическим UI-тестом с изолированным прогрессом, не ручной приёмкой.

## Остаточный поиск и допустимые исключения

Повторён регистронезависимый поиск SQLite/sqlite3/libsqlite, imports, android.database.sqlite/SQLiteDatabase, definitions.db, *.db/*.sqlite*, .deb, старого SHA и заголовков бинарной схемы по всем tracked + новым исходникам. Также просмотрены реальные ZIP-пакеты и сжатые tracked-архивы. Активных проектных зависимостей — **0**, бинарных БД в исходниках — **0**.

Остатки по точным путям классифицированы в AFTER JSON; полный список:

- `README.md` — link to migration report.
- `docs/ANDROID_UI_HANG_DIAGNOSIS.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/CAMPAIGN.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/CAMPAIGN_REVIEW.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/CORE_CAMPAIGN.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/CUR03_ACCEPTANCE.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/DATA_PIPELINE_V2.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/DEFINITIONS_POLICY.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/DICTIONARY_CLEANUP.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/LCH01_REPORT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/LCH03_REPORT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/LEVEL_HINTS.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/LOCAL_DEFINITIONS.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/OWN_HINTS_LICENSE_RELEASE_AUDIT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/OWN_HINTS_MANUAL_AUDIT.json` — immutable historical rights evidence; exact file hash is validated.
- `docs/OWN_HINTS_MANUAL_AUDIT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/OWN_HINTS_SCOPE.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/OWN_HINTS_TOOLCHAIN.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/OWN_HINTS_VERIFICATION.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/PRE_RELEASE_ANDROID_QA.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/PROJECT_STATUS.md` — link to migration report.
- `docs/RELEASE_RIGHTS_DECISION.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/RIGHTS02_REPORT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/RIGHTS03_REPORT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/RIGHTS_RELEASE_AUDIT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/SELECTIVE_CAMPAIGN.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/SHORT15_05_REPORT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/SHORT15_INSTALL.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/SHORT15_RELEASE_GATE.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/SQLITE_REPRODUCIBILITY_AUDIT.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/audits/LCH04_PACKAGE_INVENTORY.json` — historical evidence; not an active build input.
- `docs/audits/SQLITE_REMOVAL_BEFORE.json` — historical evidence; not an active build input.
- `docs/decisions/2026-11-06-provisional-definitions.md` — historical section/report or migration inventory; current contract linked explicitly.
- `docs/qa/pre04-17/coverage.json` — historical evidence; not an active build input.
- `docs/qa/pre04-17/junit.xml` — historical evidence; not an active build input.
- `docs/uiux/SHORT15_REGRESSION.md` — historical section/report or migration inventory; current contract linked explicitly.
- `orchestrator/PROGRESS.md` — historical evidence; not an active build input.
- `orchestrator/TASKS.bak.md` — historical evidence; not an active build input.
- `tools/audit_public_package.py` — negative guard/test; rejects imports/binaries and never loads them.
- `tools/check_text_storage.py` — negative guard/test; rejects imports/binaries and never loads them.
- `tools/tests/test_owned_riddles_portable.py` — negative guard/test; rejects imports/binaries and never loads them.
- `tools/tests/test_text_storage_contract.py` — negative guard/test; rejects imports/binaries and never loads them.

- Новые BEFORE/AFTER JSON и этот отчёт — историческая инвентаризация. Не являются командами старой сборки.
- Упоминания в check_text_storage, audit_public_package и двух негативных тестах запрещают появление прежних импортов/бинарников; библиотеку не импортируют и не загружают. Они являются проверкой отсутствия, а не зависимостью.
- В debug DEX осталось 20 строк внешней платформы: неиспользуемые адаптеры `androidx.core:core-ktx:1.13.1` / `androidx.core:core:1.13.1` (`SQLiteDatabaseKt`, `SQLiteCursorCompat`). Проект их не вызывает; native движка в APK нет. В release DEX таких строк **0** после обычного R8. Android/AndroidX не переписывались и зависимости не заменялись.
- Вне исходников остался **41** игнорируемый локальный cache-путь, включая `.local/owned-hints-toolchain/lib/libsqlite3.so.0` и прежние исследовательские БД. Точные пути/размеры в AFTER JSON. Они не читаются сборкой/генератором, не копируются в источник/APK и не требуются для чистой offline-сборки. Случайные пользовательские/исследовательские файлы не удалялись.
- Защищённые `orchestrator/TASKS.md`, `PROGRESS.md`, checkpoints и статус PRE04-01 не изменены. Исторические записи оркестратора не переписывались.

## Независимые блокеры публичного релиза/F-Droid

Цепочка прав wordlist/лексического индекса и CC BY-SA, отдельные notices FrequencyWords/OpenSubtitles, происхождение графики и соответствие перепакованного Protobuf остаются вопросами LEG-02. MIT/Apache/BSD и прочие обязательства не отменены. applicationId уже утверждён; подпись владельца, F-Droid metadata/recipe, подписанная воспроизводимость, полный Android QA и разрешение публикации остаются отдельными. Отсутствие БД не объявляет приложение лицензионно чистым.

## Полный реестр изменённых файлов

`D` — удалён, `M` — изменён, `A` — добавлен. Включены сохранённые предшествующие HINT-правки.

```text
M	.gitignore
M	AGENTS.md
M	README.md
M	app/build.gradle.kts
M	app/src/androidTest/kotlin/com/example/sostavslova/data/CampaignProgressRepositoryTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/data/LevelsRepositoryTest.kt
D	app/src/androidTest/kotlin/com/example/sostavslova/data/SqliteDefinitionsRepositoryTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/di/AppContainerTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/qa/CampaignJourneyStateTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/qa/CampaignJourneyUiTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/qa/QaCampaign.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/ui/components/ComponentInteractionTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/ui/components/ComponentLayoutTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/ui/game/CampaignGameUiTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameLayoutTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameSubmissionUiTest.kt
M	app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt
M	app/src/main/kotlin/com/example/sostavslova/WordsApplication.kt
M	app/src/main/kotlin/com/example/sostavslova/data/CampaignProgressDto.kt
D	app/src/main/kotlin/com/example/sostavslova/data/DefinitionsRepository.kt
D	app/src/main/kotlin/com/example/sostavslova/data/SqliteDefinitionsRepository.kt
M	app/src/main/kotlin/com/example/sostavslova/di/AppContainer.kt
D	app/src/main/kotlin/com/example/sostavslova/ui/components/DefinitionBlock.kt
M	app/src/main/kotlin/com/example/sostavslova/ui/game/CampaignTasks.kt
M	app/src/main/kotlin/com/example/sostavslova/ui/game/GameScreen.kt
M	app/src/main/kotlin/com/example/sostavslova/ui/game/GameViewModel.kt
M	app/src/main/kotlin/com/example/sostavslova/ui/screens/Screens.kt
M	app/src/main/res/values/strings.xml
M	core/src/main/kotlin/com/example/sostavslova/core/CampaignRules.kt
M	core/src/main/kotlin/com/example/sostavslova/core/HintLadder.kt
M	core/src/main/kotlin/com/example/sostavslova/core/HintRules.kt
M	core/src/test/kotlin/com/example/sostavslova/core/CampaignDatasetAudit.kt
M	core/src/test/kotlin/com/example/sostavslova/core/HintRulesTest.kt
M	core/src/test/kotlin/com/example/sostavslova/core/OwnedHintsTest.kt
D	data/definitions.db
M	data/manifest.json
M	docs/ANDROID_UI_HANG_DIAGNOSIS.md
M	docs/CAMPAIGN.md
M	docs/CAMPAIGN_REVIEW.md
M	docs/CORE_CAMPAIGN.md
M	docs/CUR03_ACCEPTANCE.md
M	docs/DATA_PIPELINE_V2.md
M	docs/DEFINITIONS_POLICY.md
M	docs/DICTIONARY_CLEANUP.md
M	docs/LCH01_REPORT.md
M	docs/LCH03_REPORT.md
M	docs/LEVEL_HINTS.md
M	docs/LOCAL_DEFINITIONS.md
M	docs/OWN_HINTS_LICENSE_RELEASE_AUDIT.md
M	docs/OWN_HINTS_MANUAL_AUDIT.md
M	docs/OWN_HINTS_SCOPE.md
M	docs/OWN_HINTS_TOOLCHAIN.md
M	docs/OWN_HINTS_VERIFICATION.md
M	docs/PRE_RELEASE_ANDROID_QA.md
M	docs/PROJECT_STATUS.md
M	docs/RELEASE_RIGHTS_DECISION.md
M	docs/RIGHTS02_REPORT.md
M	docs/RIGHTS03_REPORT.md
M	docs/RIGHTS_RELEASE_AUDIT.md
M	docs/SELECTIVE_CAMPAIGN.md
M	docs/SHORT15_05_REPORT.md
M	docs/SHORT15_INSTALL.md
M	docs/SHORT15_RELEASE_GATE.md
M	docs/SQLITE_REPRODUCIBILITY_AUDIT.md
M	docs/decisions/2026-11-06-provisional-definitions.md
M	docs/uiux/SHORT15_REGRESSION.md
M	legal/THIRD_PARTY_NOTICES.txt
M	tools/apply_short15_balance.py
M	tools/audit_public_package.py
M	tools/build_campaign.py
M	tools/build_dictionary.py
M	tools/build_game_data.py
M	tools/build_local_definitions.py
M	tools/build_owned_hints.py
M	tools/campaign_profiles.py
M	tools/campaign_review_export.py
M	tools/campaign_review_import.py
M	tools/clean_dictionary.py
M	tools/install_short15_package.py
M	tools/owned_hints.py
M	tools/owned_hints_rights.py
D	tools/owned_hints_toolchain.json
D	tools/owned_hints_toolchain.py
M	tools/pick_level_words.py
D	tools/prepare_owned_hints.py
M	tools/prepare_v2.py
M	tools/rebalance_campaign.py
M	tools/tests/test_build_dictionary.py
M	tools/tests/test_campaign_review_export.py
M	tools/tests/test_campaign_review_import.py
M	tools/tests/test_clean_dictionary.py
M	tools/tests/test_game_data.py
M	tools/tests/test_level_profiles.py
M	tools/tests/test_local_definitions.py
M	tools/tests/test_owned_hints_install.py
M	tools/tests/test_owned_hints_owner_review.py
M	tools/tests/test_owned_hints_rights.py
D	tools/tests/test_owned_hints_toolchain.py
M	tools/tests/test_prepare_v2.py
M	tools/tests/test_public_package_audit.py
M	tools/tests/test_rights_release_gate.py
M	tools/tests/test_short15_balance.py
M	tools/tests/test_short15_install.py
M	tools/validate_campaign.py
A	app/src/androidTest/kotlin/com/example/sostavslova/data/CampaignJsonUpgradeTest.kt
A	app/src/androidTest/kotlin/com/example/sostavslova/data/JsonRiddlesRepositoryTest.kt
A	app/src/androidTest/kotlin/com/example/sostavslova/ui/game/OwnedRiddlesUiTest.kt
A	app/src/main/kotlin/com/example/sostavslova/data/RiddlesRepository.kt
A	core/src/test/kotlin/com/example/sostavslova/core/HintLadderTest.kt
A	core/src/test/kotlin/com/example/sostavslova/core/OwnedRiddlesAssetTest.kt
A	data/owned_riddles.json
A	docs/audits/SQLITE_REMOVAL_BEFORE.json
A	tools/check_text_storage.py
A	tools/lexical_json.py
A	tools/owned_riddles_contract.json
A	tools/tests/test_owned_riddles_json.py
A	tools/tests/test_owned_riddles_portable.py
A	tools/tests/test_text_storage_contract.py
A	docs/SQLITE_REMOVAL.md
A	docs/audits/SQLITE_REMOVAL_AFTER.json
A	docs/audits/owned-riddles-level12.png
```
