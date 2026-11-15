# LEG-01 — доказательный аудит прав для F-Droid / публичного релиза

Дата: **2026-11-15**. Область: установленная кампания **short15-release-v1**, текущее дерево
`words-combination`. HEAD на момент аудита — `6c0daca135cb057b591ab83b5172df323404bd76`;
проверенный APK собран из ревизии `d25a60aa3e1ce897c2983d87740d9778db5d7241` (см. §2).

> Это **инженерный аудит доказательств**, а не юридическое заключение и не юридическая
> консультация. Там, где нет первичного источника, истории авторов или письменного решения
> владельца, ниже стоит **«не доказано»**, а не «нарушено» и не «чисто». Наличие файла
> `LICENSE`, зелёная Gradle-задача или совпадение SHA-256 сами по себе не подтверждают право
> распространения.

## 1. Вердикт

| Вопрос | Статус | Что это значит |
|---|---|---|
| Публичное распространение текущего APK | **BLOCKED_PUBLIC_RIGHTS** | Права на 31 351 текст определений и лицензия самого кода не доказаны; `definitions.audit.passed=false`, `rights_review.passed=false`. |
| Техническая сборка release | **TECHNICAL_BUILD_WITH_WAIVER_ONLY** | По отчёту `SHORT15-04` `:app:assembleRelease` проходит только по waiver владельца; в этом аудите сборка не перезапускалась. Это не публикационный релиз. |
| Публикация в F-Droid | **NOT_READY** | Нет лицензии кода, метаданных F-Droid, чистого source-to-APK соответствия и корректного NOTICE; часть блокеров техническая, часть правовая. |
| Проверка `:app:verifyDictionaryLicenses` | **PASS, но только хэши** | Задача проверяет схему/хэши/наличие файлов; она **не** проверяет авторство, лицензию или допустимость распространения. |
| Строгая проверка `--check-release` | **BLOCKED, exit 2** | Без waiver release запрещён; с waiver — `publishable=false`. |
| Ни один критерий PASS из §7 | **не достигнут** | Текущие `false`/`blocked` сохранены, ничего не переклассифицировано. |

## 2. Проверенные факты и артефакты

| Факт | Значение / доказательство |
|---|---|
| Словарь | `data/words.txt`: **20 106** строк; SHA-256 `3b8a1339df5485ae2c5493cec3b5297e84173b94e57cf253c3543a8c85efc997` |
| Определения, фактический COUNT | `data/definitions.db`, таблица `definitions`: **31 351** строка; `word_metadata`: 20 106; `build_metadata`: 7; `PRAGMA user_version=8`. Покрытие: **20 103 из 20 106** слов; без определений остаются `бунд`, `родные`, `хулиганье`. |
| Объём текста определений | 2 246 406 символов; средняя длина 71,7; максимум 545; `example=''` и `notes=''` у всех строк; `source` заполнен у всех 31 351 строки; `origin='wiktionary'` у всех. |
| SHA-256 БД | `6d0cd051b75babdb4435777f29e00419ff3ba7dbc8c0d9ae602cff7b18869d7f`; совпадает с `manifest.definitions.sha256` и с `assets/definitions.db` внутри APK (байт-в-байт). |
| `manifest.definitions` | `counts.own=0`, `counts.wiktionary=31351`; `audit.passed=false`; `rights_policy=wiktionary-provisional-v1`; `rights_review.status=unverified-provisional`; `rights_review.passed=false`; `replacement_required=true`; `guessability_evaluated=false`. |
| `manifest.selection` | `audit.passed=false`, `method=automated-first-pass`; `definitions_required=false`; режим `game-cleanup-v2`. |
| Waiver | `gradle.properties:5-9`: `sostavslova.release.allowUnauditedDefinitions=true`; `app/build.gradle.kts:42-45` пробрасывает его в `verifyDefinitionsAudit`. |
| Строгая проверка | `python3 tools/build_game_data.py --check-release --out data` → **exit 2**, «definitions.db не соответствует пройденному ручному аудиту». |
| Waiver-проверка | `... --allow-unaudited-release --out data` → exit 0, `release_audit=owner-waiver-unaudited`, `publishable=false`; findings: политика не подтверждена, ручной аудит не пройден, конфиг/списки жалоб изменились. |
| Gradle-проверка | `:app:verifyDictionaryLicenses` → **BUILD SUCCESSFUL** (проверка хэшей и схемы, не прав). |
| APK | `app/build/outputs/apk/release/app-release.apk`, **6 803 203** байта, SHA-256 `8a28c77a7304ad31267762a2fbec183d7eecc143d41989ee4815ed6a5db1ae69`. |
| Подпись APK | `apksigner verify`: подпись валидна, схема v2; `CN=Android Debug`, SHA-256 сертификата `B3:8E:AF:EA:4F:46:75:75:D6:0C:47:57:8E:0B:7A:C6:97:50:C6:4B:7F:0A:1D:E6:6F:B3:0C:76:02:D1:52:64`. Это debug-ключ, не публикационный. |
| Упаковка | `zipalign -c 4` → exit 0; `aapt2 dump badging`: `com.aistudio.wordsgame.kymxpv`, `versionCode=1`, `versionName=1.0.0`, minSdk 24, targetSdk 36; `uses-permission` в APK отсутствуют. |
| Соответствие APK/HEAD | `META-INF/version-control-info.textproto` в APK: ревизия `d25a60a…`; текущий HEAD — `6c0daca…`. Тег релиза отсутствует. |
| Source remote | Git remote: `https://github.com/volmir/words-combination.git`; ветка `auto/tasks`; публичная доступность remote офлайн не проверялась. |
| Лицензия кода в корне | В текущем дереве **нет** `LICENSE`/`COPYING`/`NOTICE`. В истории `6dcc944` был MIT-файл `Copyright (c) 2020 Prokhnenko Vladimir`; он удалён коммитом `5e2aa6a`. |
| F-Droid metadata | В репозитории нет `fdroiddata`-метаданных, `fastlane/` и релизного рецепта. `metadata.json` — это AI Studio-метаданные, не F-Droid. |

## 3. Инвентаризация источников

### 3.1. APK assets

В APK входят: `classes.dex`, `assets/words.txt`, `assets/campaign.json`, `assets/levels.json`,
`assets/definitions.db`, `assets/word_sources.jsonl` (в APK без `.gz`; содержимое совпадает с `.gz`), `assets/THIRD_PARTY_NOTICES.txt`,
`assets/Apache-2.0.txt`, `assets/LIBRARY_NOTICES.txt`, `assets/RUNTIME_LIBRARIES.json`, native-библиотеки
AndroidX. Отдельного `assets/CC-BY-SA-4.0.txt` нет; `syncDictionaryAssets` упоминает его в `preserve`,
но ниоткуда не копирует (`app/build.gradle.kts:180-185`).

| Asset в APK | Размер, байт | Проверка |
|---|---:|---|
| `assets/words.txt` | 329 782 | SHA `3b8a1339…fc997`, совпадает с `data/words.txt` |
| `assets/campaign.json` | 9 247 | SHA `eb440873…1fadc`, 15 уровней / 49 заданий |
| `assets/levels.json` | 4 896 | SHA `e4ded7a9…e73a`, 30 исторических уровней |
| `assets/definitions.db` | 17 223 680 | SHA `6d0cd051…d9d7f`; 31 351 строка |
| `assets/word_sources.jsonl` | 3 791 765 | SHA `64936db2…f2c80`; в NOTICE назван `.gz` |
| `assets/THIRD_PARTY_NOTICES.txt` | 7 844 | SHA `d85ebe0c…5c2cd`; плейсхолдер внутри |
| `assets/Apache-2.0.txt` | 11 358 | полный Apache-2.0 |
| `assets/LIBRARY_NOTICES.txt` | 18 145 | список артефактов + Apache/BSD |
| `assets/RUNTIME_LIBRARIES.json` | 18 972 | 80 артефактов, версии не совпадают с APK |

`classes.dex` — 3 706 172 байта; native-libs AndroidX (path/datastore) также входят в APK.
Разрешений (`uses-permission`) нет; `allowBackup=false`.

### 3.2. Доступность исходников

| Объект | Статус |
|---|---|
| Код `:core`, `:app` | В Git; remote `https://github.com/volmir/words-combination.git`; текущая ветка `auto/tasks`. Публичная доступность офлайн не проверена; тега релиза нет. |
| `data/` (7 файлов) | В Git: `words.txt`, `campaign.json`, `levels.json`, `manifest.json`, `golden_search.json`, `word_sources.jsonl.gz`, `definitions.db`. Этого достаточно для сборки текущего пакета. |
| `legal/` (4 файла) | В Git; копируются в assets. |
| `app/src/main/assets/` | Генерируется `syncDictionaryAssets`; в `.gitignore`, в Git не входит. |
| Локальный дамп и частоты | `.local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz` (273 MB) и `.local/frequency/.../ru_full.txt` (29 MB) **не в Git**. Пересборка данных из первоисточников по публичному репозиторию не воспроизводима; текущие `data/` — воспроизводимы. |
| Подпись | `debug.keystore` не в Git и игнорируется; signingConfig ссылается на него. Clean-clone сборка требует внешнего ключа/правки конфигурации. |
| F-Droid metadata | Нет `fdroiddata`-метаданных, `fastlane/`, source tarball/тега или build recipe. |

### 3.3. Данные и тексты

| Источник | Происхождение, версия/ссылка | Заявленная лицензия | Право, атрибуция, share-alike | Куда попадает | Точный блокер | Минимальное устранение |
|---|---|---|---|---|---|---|
| `data/words.txt` | Локальный дамп Kaikki.org / Wiktextract от **2026-11-01**, SHA входа `98bebfb8013064d23bc6f0981b64cd097f64e098c9e434ebb97684c9606cc2da`; 113 413 входных слов → 20 106 после очистки; `data/manifest.json.source`. | В манифесте указана **CC BY-SA 4.0** (`license_url`), но это лицензия источника, а не отдельное явное заявление для выходного `words.txt`. | Атрибуция: «участники русского Викисловаря» + URL статей в `word_sources`; share-alike для производного списка **не оформлен явно**. Право на конкретный список как объект авторского права не доказано (в разных юрисдикциях списки слов могут не охраняться; здесь это не решается). | `assets/words.txt` в APK; основа кампании и поиска. | Нет явной лицензии на выходной файл, полного текста CC BY-SA 4.0 в APK и подтверждённой атрибуции для всех статей. | Добавить отдельный `DATA_LICENSE`/NOTICE: `words.txt`, `word_sources`, `definitions.db` распространяются на CC BY-SA 4.0; приложить полный текст или URI, список изменений и атрибуцию; проверить владельцем. |
| `data/definitions.db` | Тот же дамп; `tools/build_local_definitions.py`; `origin='wiktionary'`, `source='https://ru.wiktionary.org/wiki/…'`; 31 351 строка, 20 103 слова; `user_version=8`; SHA `6d0cd051…d9d7f`. | Заявлена **CC BY-SA 4.0**; фактическая политика — `wiktionary-provisional-v1`; индивидуальный статус прав **не проверен**. | Атрибуция по URL статьи; история авторов доступна на странице; share-alike для адаптированных текстов **не оформлен**. В выборке 5 000 записей ru-noun локального дампа нет полей `license`, `author`, `source` — только `word`, `pos`, `senses` и т. п.; по записи нельзя доказать конкретную лицензию/автора. | `assets/definitions.db`; подсказки, окно значения, T4-загадки. | **Главный правовой блокер:** 31 351 текст распространён как «provisional/unverified»; `audit.passed=false`, `rights_review.passed=false`, `replacement_required=true`; ручной аудит и проверка индивидуальных прав не выполнены. Это не доказывает нарушение, но и не даёт доказанного права на публикацию. | Маршрут A (аудит + CC BY-SA + атрибуция) либо B (замена) либо C (исключить БД); до этого публикацию не объявлять. |
| `data/campaign.json` | `tools/build_campaign.py` + `tools/rebalance_campaign.py`; профиль `short15-release-v1`; 15 уровней, 49 заданий; SHA `eb440873…1fadc`. | Отдельной лицензии нет; слова берутся из `words.txt`, текстов статей внутри нет. | Производность от словаря — по правилам словаря; самостоятельного литературного текста не обнаружено. | `assets/campaign.json`; карта, задания, звёзды. | Отдельного блокера нет; наследует блокер `words.txt`/атрибуции. | Включить в общий DATA-NOTICE; отдельная лицензия не требуется, если владелец подтвердит. |
| `data/levels.json` | Исторический снимок 30 уровней v1 из `volmir/words-api`; SHA `e4ded7a9…e73a`; используется для legacy-тестов/восстановления, не для текущей карты. | **MIT** (указана в `manifest.levels_source`). | MIT-уведомление `Copyright (c) 2018 Prokhnenko Vladimir` есть в `THIRD_PARTY_NOTICES.txt:79-100`. | `assets/levels.json`; тесты, legacy. | Правового блокера нет при сохранении MIT-уведомления. Лишний неиспользуемый asset увеличивает поверхность. | Сохранить уведомление; при желании исключить из публичного APK, если legacy-путь не нужен (продуктовое решение). |
| `data/word_sources.jsonl.gz` | Генерируется `tools/build_dictionary.py`; 20 106 строк; URL статей и номера строк; SHA `99fb9af8…85c8` в манифесте. | Как часть данных Викисловаря — CC BY-SA 4.0 по манифесту. | URL статьи + история авторов; отдельных имён авторов нет, но URL — разумный путь к истории. | В APK лежит как **`assets/word_sources.jsonl`** (3 791 765 байт); содержимое после распаковки побайтово совпадает с `zcat data/word_sources.jsonl.gz` (SHA `64936db2…f2c80`). | **Техническое несоответствие NOTICE:** `THIRD_PARTY_NOTICES.txt:10` называет `word_sources.jsonl.gz`, в APK — `word_sources.jsonl`. Это ломает проверяемость атрибуции. | Исправить имя в NOTICE; сохранить URL/атрибуцию; при желании явно указать, что файл распакован AGP. |
| `data/golden_search.json` | Генерируется `tools/build_dictionary.py`; 21 009 байт; тестовая выборка. | Производная данных; отдельной лицензии нет. | В APK не входит; публично не распространяется. | Только тестовые ресурсы. | Публичного блокера нет. | Ничего; не включать в релизный APK. |
| Частотный список FrequencyWords | Локальный `ru_full.txt` (28 999 417 байт), SHA `32dfd941…5a30`; репозиторий `hermitdave/FrequencyWords`, коммит `f8a65e6d…e55b`; `source.json` фиксирует `content_license_declared_by_upstream=CC-BY-SA-3.0`. | **CC BY-SA 3.0** для данных (по README закреплённой ревизии); код проекта — отдельно MIT (`UPSTREAM_CODE_LICENSE`). | Сырые субтитры и частотный файл **не входят** в APK; используется только сигнал отбора. Share-alike для производного отбора не оценён. | Только `tools/`/`data/manifest.selection`; не asset APK. | Низкий/средний: нет полного текста CC BY-SA 3.0; вопрос, является ли отбор производным материалом, не решён. | Сохранить NOTICE и `source.json`; указать, что сырые данные не распространяются; проверить у владельца/юриста необходимость share-alike для отбора. |
| `data/manifest.json` | Генерируемый provenance-манифест; SHA `41ab4ce18d344bb91b1296150d2cdb7d9938ef7d6496d4a0db114e7fa62a4a1e`. | Не лицензия, а метаданные. | Фиксирует источник, лицензии, хэши, false-флаги. | Не входит в APK; управляет `verify*` и `BuildConfig`. | Сам по себе не блокер; но его `definitions.audit.passed=false` и `rights_review.passed=false` — юридически значимые запреты. | Не менять вручную; обновлять только approved-инструментом после аудита. |

### 3.4. Код, зависимости, ресурсы, сборка

| Источник | Происхождение, версия/ссылка | Заявленная лицензия | Право, атрибуция, share-alike | Куда попадает | Точный блокер | Минимальное устранение |
|---|---|---|---|---|---|---|
| Код `:core`, `:app` | Текущий репозиторий `volmir/words-combination`; основан на MIT-проектах `volmir/words-combination` (2020) и `volmir/words-api` (2018). | Для upstream-частей — **MIT** (уведомления в `THIRD_PARTY_NOTICES.txt:51-100`). Для текущего кода **своя лицензия не заявлена**. | MIT-уведомление upstream сохранено; но нет корневого `LICENSE` и явного grant на текущий код. Прямое распространение владельцем своего кода может опираться на авторское право, но это не заменяет FOSS-лицензию для F-Droid и не доказывает авторство всех частей. F-Droid/аудитор не получит доказанного grant на сборку и распространение. | `classes.dex`, исходники. | **Критический:** отсутствие лицензии текущего кода/корневого LICENSE; удаление MIT-файла в `5e2aa6a`. Это не отменяет MIT для старых частей, но не даёт FOSS-лицензии на новый код. | Владелец выбирает FOSS-лицензию (совместимую с MIT), добавляет корневой `LICENSE`, SPDX/уведомления, публикует исходники с тегом; сохраняет upstream MIT. |
| AndroidX / Compose / Kotlin / kotlinx | Фактический release-APK: Compose UI **1.7.2**, Material3 **1.3.0**, activity **1.10.1**, lifecycle **2.8.7**, navigation **2.8.9**, datastore **1.1.7**, core **1.13.1**, coroutines **1.10.2**, Kotlin **2.2.10**; protobuf — BSD-3. | **Apache-2.0**, кроме `datastore-preferences-external-protobuf` — **BSD-3-Clause**. Проприетарных/AGPL зависимостей в инвентаре не обнаружено. | Apache/BSD требуют сохранения уведомлений/текстов; `legal/Apache-2.0.txt` и BSD-текст есть. | `classes.dex`, native-libs, `assets/LIBRARY_NOTICES.txt`, `assets/RUNTIME_LIBRARIES.json`. | **NOTICE не соответствует APK:** `RUNTIME_LIBRARIES.json`/`LIBRARY_NOTICES.txt` описывают другой набор (Compose 1.12.1, activity 1.13.0, lifecycle 2.11.0, datastore 1.3.0, Kotlin 2.4.21, coroutines 1.11.0), включают `ui-test-manifest`/`ui-tooling` (не release runtime) и **не включают** фактически упакованный `material-icons-core:1.7.0`. Это техническая ошибка атрибуции, а не доказанная несовместимость лицензий. | Перегенерировать `RUNTIME_LIBRARIES.json` и `LIBRARY_NOTICES.txt` из фактического release runtime classpath и APK; сверить каждый `.version`-маркер; включить все фактические артефакты. |
| Векторные drawable | 12 XML в `app/src/main/res/drawable`; используются 8 (`ic_arrow_back`, `ic_crown`, `ic_help`, `ic_lock`, `ic_menu`, `ic_star_filled`, `ic_star_outline`, `ic_statistics`). Пути визуально соответствуют Material Icons; `ic_crown`/`ic_launcher_background` выглядят собственными. | Для Material-подобных иконок вероятна **Apache-2.0**, но provenance не зафиксирован; для остальных — не доказано. | Нет per-file source/лицензии; `LIBRARY_NOTICES` перечисляет `material-icons-core`, но не копии drawable. | `res/drawable` → APK resources. | **Недоказанное право** на иконки; F-Droid/аудитор может запросить provenance. Не обязательно блокер, если владелец подтвердит авторство. | Зафиксировать источник/лицензию каждой иконки или заменить собственными; добавить уведомление для Material-частей. |
| Иконка приложения | `words_app_icon_1793804687264.jpg` 1024×1024, добавлена в `a1182f6`; mipmap-производные. EXIF: только JFIF/DPI, автора/лицензии нет. | **Не доказана**. | Нет исходника, автора, лицензии, счёта/договора. | `res/drawable`, `mipmap-*`, launcher. | Недоказанное право на логотип/иконку. Если изображение создано владельцем или сгенерировано — нужно письменное подтверждение; если взято из сети — блокер. | Владелец подтверждает авторство/лицензию либо заменяет иконку на оригинальную; сохранить provenance. |
| Шрифты | В APK нет файлов шрифтов; `Theme.kt` использует `FontFamily.Default` (системный шрифт). | Лицензия шрифта ОС; приложение шрифт не распространяет. | Нет встроенного шрифта — нет font-license блокера. | Нет. | Нет. | Ничего. |
| Локализации | Только `app/src/main/res/values/strings.xml` (русский); каталогов `values-*` нет. `aapt2` показывает label для многих locale как fallback, а не переводы. | Строки — часть кода; отдельной лицензии нет. | Нет сторонних переводов. | `resources.arsc`. | Отдельного блокера нет; наследует лицензию кода. | Включить строки в лицензию кода; при добавлении переводов фиксировать provenance. |
| `legal/THIRD_PARTY_NOTICES.txt` | 7 844 байта; копируется в `assets/` без изменений (SHA `d85ebe0c…5c2cd`). | Смешанные уведомления MIT/CC BY-SA/BSD. | Содержит плейсхолдер `<ЛИЦЕНЗИЯ — решает владелец>` (строка 24), имя `word_sources.jsonl.gz` (строка 10), отрицание прав на временные определения (строки 26-31). | `assets/THIRD_PARTY_NOTICES.txt`. | **Плейсхолдер не решён**, имя файла неверно, статус определений описан как временный; для публикации это не готовый NOTICE. | Владелец решает лицензию собственных определений (сейчас own=0); обновить текст, имя файла, статус; добавить полный CC BY-SA текст/URI. |
| `legal/LIBRARY_NOTICES.txt`, `RUNTIME_LIBRARIES.json` | Сгенерированы/обновлены 2026-11-04; отражают состояние **до** downgrade зависимостей в `a1182f6`. | Apache-2.0/BSD-3. | Полные тексты Apache/BSD есть; но артефакты не те. | `assets/`. | Та же проблема NOTICE/APK, что выше. | Перегенерировать; проверить, что каждый артефакт и версия соответствуют APK. |
| Сборка и подпись | `applicationId=com.aistudio.wordsgame.kymxpv`, `namespace=com.example.sostavslova`, `versionCode=1`, `versionName=1.0.0`, minSdk 24/target 36; release использует `debugConfig` (`app/build.gradle.kts:126-147`). `debug.keystore` не в Git и игнорируется `.gitignore:12`. | N/A (техническое). | Публикационный ключ отсутствует; debug-ключ не даёт права на публикацию. | APK. | **Технический блокер:** release подписан debug-ключом; `debug.keystore` отсутствует в чистом клоне, а signingConfig на него ссылается; `applicationId` — заглушка AI Studio; нет тега/чистого source-to-APK соответствия; нет F-Droid metadata. | Владелец выбирает applicationId/version и ключ; signing вынести из репозитория/использовать F-Droid-подпись; собрать из чистого клона; добавить metadata/fastlane; удалить waiver после прав. |
| `metadata.json` | AI Studio-метаданные; содержит `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`. | N/A. | В APK не входит; сетевого/Gemini-кода и разрешений нет (проверено `aapt2`, DEX). | Только корень репозитория. | Не публичный блокер, но вводит в заблуждение; не является F-Droid metadata. | Исправить/удалить; не использовать как описание приложения. |

### 3.5. Зависимости: APK vs NOTICE

Фактические версии взяты из `META-INF/*.version` APK; для Kotlin stdlib — из
`gradle/libs.versions.toml` (маркера в DEX нет). `legal/` описывает другой набор.

| Компонент | Факт в APK | В `legal/`/NOTICE | Лицензия |
|---|---:|---:|---|
| `androidx.compose.ui:ui` | 1.7.2 | 1.12.1 | Apache-2.0 |
| `androidx.compose.material3:material3` | 1.3.0 | 1.4.0 | Apache-2.0 |
| `androidx.activity:activity` | 1.10.1 | 1.13.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime` | 2.8.7 | 2.11.0 | Apache-2.0 |
| `androidx.navigation:navigation-*` | 2.8.9 | 2.10.2 | Apache-2.0 |
| `androidx.datastore:datastore-*` | 1.1.7 | 1.3.0 | Apache-2.0 |
| `androidx.core:core` | 1.13.1 | 1.18.0 | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*` | 1.10.2 | 1.11.0 | Apache-2.0 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.10 | 2.4.21 | Apache-2.0 |
| `androidx.compose.material:material-icons-core` | 1.7.0 | **отсутствует** | Apache-2.0 |
| `androidx.compose.ui:ui-tooling`, `ui-test-manifest` | **нет в release** | **есть в NOTICE** | Apache-2.0 |

Семейство лицензий (Apache-2.0, плюс BSD-3 для protobuf) само по себе не выглядит правовым
блокером; блокер — **неверный инвентарь/атрибуция**, который нельзя предъявить F-Droid/аудитору
как доказательство.

## 4. Публичное право и техническая сборка — это разные вещи

**Публичное право (не доказано):**

1. Нет доказанного права распространять 31 351 текст `definitions.db`; политика сама называет их
   `provisional`, `replacement_required=true`, `rights_review.passed=false`.
2. Нет явной лицензии на текущий код приложения; upstream MIT не покрывает новый код автоматически,
   а корневой `LICENSE` удалён.
3. Атрибуция/ShareAlike для `words.txt` и `definitions.db` не оформлены как единый, проверяемый
   NOTICE; полного текста CC BY-SA 4.0 в APK нет (есть только URI).
4. Provenance иконки приложения и части drawable не зафиксирован.
5. `selection.audit.passed=false`: даже состав слов не прошёл ручной аудит (это не только вопрос
   прав, но и качества/жалоб).

**Техническая сборка (проходит с оговорками):**

1. `:app:verifyDictionaryLicenses` зелёный — только хэши/схема.
2. По отчёту `SHORT15-04` `:app:assembleRelease` проходит по waiver; в этом аудите сборка не
   перезапускалась. `:app:verifyDefinitionsAudit` печатает `publishable=false`.
3. APK валиден по подписи/zip-align, но подписан debug-ключом.
4. `debug.keystore` не в Git; чистый клон, вероятно, не соберёт даже debug/release без ключа
   (статическая проверка конфигурации; clean-clone сборка не запускалась).
5. APK собран из `d25a60a`, текущий HEAD — `6c0daca`; source-to-APK binding надо пересобрать.

Технический успех **нельзя** переклассифицировать в публичное право. Waiver — решение владельца
разрешить сборку, а не подтверждение лицензии. CC BY-SA 4.0 для данных — не лицензия кода:
F-Droid всё равно нужна отдельная FOSS-лицензия на код приложения.

## 5. Таблица рисков

| ID | Риск | Уровень | Почему | PASS-критерий |
|---|---|---|---|---|
| R1 | 31 351 текст Викисловаря распространён без доказанного права | **Критический** | `rights_policy=wiktionary-provisional-v1`, `audit.passed=false`, `rights_review.passed=false`, `replacement_required=true`; нет per-entry автора/лицензии. | A: `audit.passed=true` + `rights_review.passed=true` + политика не provisional; или B/C: в APK нет `origin=wiktionary` текстов. |
| R2 | Нет лицензии текущего кода | **Критический** | Корневой `LICENSE` отсутствует; удалён в `5e2aa6a`; upstream MIT не заменяет grant на новый код. | Корневой FOSS `LICENSE`, SPDX/уведомления, публичный source tag. |
| R3 | NOTICE не соответствует фактическому APK | **Высокий** | `RUNTIME_LIBRARIES`/`LIBRARY_NOTICES` описывают Compose 1.12.1 и др., а APK содержит 1.7.2; `material-icons-core` не указан; `word_sources` имя неверно; указанные в списке `META-INF/.../LICENSE.txt` в APK отсутствуют. | NOTICE сгенерирован из фактического APK; каждая версия и файл совпадают; проверка автоматизирована. |
| R4 | Release подписан debug-ключом; ключ не в Git | **Высокий (технический)** | `CN=Android Debug`; `debug.keystore` игнорируется; signingConfig ссылается на отсутствующий файл. | Публикационный ключ/F-Droid-подпись; clean-clone build; `apksigner` не debug. |
| R5 | APK не соответствует текущему HEAD | **Средний/высокий** | APK VCS revision `d25a60a`, HEAD `6c0daca`; тега нет. | Пересборка из зафиксированного тега/коммита; SHA и VCS записаны. |
| R6 | Иконка/часть drawable без provenance | **Средний** | Нет автора/лицензии; JPG без EXIF; vector paths без заголовков. | Письменное подтверждение владельца или замена; NOTICE. |
| R7 | CC BY-SA атрибуция/ShareAlike не оформлены | **Высокий** | Нет полного текста CC BY-SA 4.0; нет явной лицензии выходных данных; URL статей есть, но авторы не перечислены. | DATA-NOTICE с лицензией, атрибуцией, изменениями, ShareAlike; полный текст/URI; owner sign-off. |
| R8 | Частотный список CC BY-SA 3.0 | **Низкий/средний** | Сырые данные не в APK; но производность отбора не оценена; нет полного текста. | NOTICE + `source.json`; решение владельца/юриста о share-alike. |
| R9 | Нет F-Droid metadata/рецепта | **Высокий (публикация)** | Нет `fdroiddata`, fastlane, тега, описания сборки; `metadata.json` — AI Studio. | Метаданные, source URL, build recipe, лицензия, версия, anti-features. |
| R10 | Плейсхолдер лицензии собственных определений | **Средний** | `THIRD_PARTY_NOTICES.txt:24`: `<ЛИЦЕНЗИЯ — решает владелец>`; own=0 сейчас, но политика не решена. | Владелец выбирает лицензию; текст обновлён; плейсхолдеров нет. |
| R11 | Нет ручного smoke-test на устройстве | **Средний (публикация)** | `adb devices` пуст; UI/IME/upgrade не проверены. Не право, но публикационная готовность. | Прогон по `SHORT15_RELEASE_GATE.md`/`SHORT15_MANUAL_QA.md`. |

## 6. Реалистичные маршруты

Ни один маршрут ниже **не реализован**. Это варианты для решения владельца; они не меняют данные,
манифест, БД или код.

### Маршрут A (А) — сохранить Викисловарь, оформить CC BY-SA 4.0 и доказательства

**Суть:** оставить 31 351 текст, но превратить «provisional» в проверенный режим с атрибуцией и
ShareAlike. Это самый полный, но самый трудоёмкий путь.

Минимальные шаги:

1. Владелец назначает проверяющего и фиксирует правовую позицию: на каких основаниях тексты
   Викисловаря распространяются по CC BY-SA 4.0; как трактуются вставки третьих лиц и адаптация
   (извлечение, очистка HTML, раскрытие отсылок).
2. Ручной аудит: минимум 300 уникальных строк, ≤3% `bad`, привязка к точной БД/игровым файлам;
   отдельно — проверка индивидуальных прав/цитат. Результат: `audit.passed=true`,
   `rights_review.passed=true`, `rights_policy` не `wiktionary-provisional-v1`.
3. Оформить `DATA-NOTICE`/`LICENSE-DATA`: `words.txt`, `word_sources`, `definitions.db` —
   CC BY-SA 4.0; авторы — участники русского Викисловаря; ссылки на статьи; Kaikki/Wiktextract;
   перечень изменений; полный текст CC BY-SA 4.0 или URI; никаких дополнительных ограничений.
4. Исправить NOTICE: убрать плейсхолдер, исправить `word_sources` имя, перегенерировать
   `RUNTIME_LIBRARIES`/`LIBRARY_NOTICES` из фактического APK, добавить `material-icons-core` и
   фактические версии.
5. Решить судьбу `selection.audit.passed=false`: пересобрать пакет/провести аудит, иначе
   `verify_selection_inputs` снова даст «конфиг/списки изменились».
6. Удалить waiver, пройти strict `--check-release`, пересобрать, подписать публикационным ключом
   (или отдать F-Droid), добавить metadata.

**Плюсы:** сохраняются определения, подсказки, T4; полная атрибуция; можно честно заявить
CC BY-SA. **Минусы:** ShareAlike; возможные сторонние вставки; большой аудит; нельзя объявлять
исключительные права на тексты; нужна юридическая проверка.

### Маршрут B (Б) — заменить зависимые от Викисловаря определения на собственные/разрешённые

**Суть:** убрать `origin=wiktionary` из публичной БД; оставить слова/кампанию, но наполнить
определения/подсказки материалом, на который есть доказанное право.

Минимальные шаги:

1. Выбрать источник: собственные тексты владельца; или генерация с провайдером, чьи условия
   разрешают коммерческое распространение и перелицензирование; **без платных ИИ-моделей** в рамках
   этой задачи. Зафиксировать модель/провайдера/дату/условия/авторство.
2. Пересобрать БД: `origin=own` (или разрешённый origin), лицензия собственных определений
   выбрана владельцем; удалить 31 351 wiktionary-строку; обновить `manifest.definitions`,
   `counts`, `rights_policy`, хэши, `user_version`; провести ручной аудит ≥300 строк.
3. Проверить, что подсказки не раскрывают ответ (`***`), T3/T4 работают, категории не тянут
   сторонний текст; обновить NOTICE (убрать provisional, добавить лицензию/атрибуцию).
4. Strict `--check-release`, сборка, подпись, metadata.

**Плюсы:** нет ShareAlike для определений; можно выбрать удобную лицензию; F-Droid проще.
**Минусы:** большой объём контента; качество/стоимость; условия провайдера; словарь `words.txt`
всё равно остаётся CC BY-SA; нужен новый аудит.

### Маршрут C (В) — временный/постоянный релиз без внешних определений

**Суть:** не распространять `definitions.db` и тексты Викисловаря; оставить словарь, кампанию и
подсказки по длине/первой букве. Это самый быстрый способ убрать главный правовой блокер, но
продуктово ощутимый.

Технически код уже поддерживает такой режим:

- `SqliteDefinitionsRepository` при `hasDefinitions=false` удаляет старую БД и возвращает `null`
  (`app/src/main/kotlin/.../SqliteDefinitionsRepository.kt`);
- `GameViewModel.loadHintWords`/`loadRiddles` работают с пустым списком определений;
- `GameScreen`/`CampaignTasks` показывают fallback «длина + первая буква»;
- в androidTest есть `CampaignJourneyUiTest.t4RemainsPlayableWithoutDatabase` и варианты
  `FILTERED`/`ABSENT` (на устройстве не запускались).

Минимальные шаги:

1. Владелец подтверждает продуктовое изменение: T4-загадки и подсказки без значений; ввод/правила
   не меняются.
2. Ввести сборку/флаг `hasDefinitions=false`, исключить `definitions.db` из `syncDictionaryAssets`
   и APK; проверить, что `HAS_DEFINITIONS=false` и UI не показывает источник Викисловаря.
3. Обновить NOTICE: убрать раздел определений/Викисловаря, оставить `words.txt`/кампанию и их
   CC BY-SA-атрибуцию; убрать `hint_definition_source`.
4. Прогнать strict `--check-release` на release-входе **без** `data/definitions.db`: текущий
   `require_release_audit` смотрит на `--out/definitions.db`, а не только на APK, поэтому одного
   исключения из assets недостаточно. Затем тесты T1–T7, 49/49, финал 4/5; собрать, подписать,
   metadata.

**Плюсы:** нет блока R1; быстро; меньше зависимостей; F-Droid проще. **Минусы:** теряются
определения/T4-содержание; продуктовая деградация; всё ещё нужны лицензия кода, NOTICE для слов,
подпись и metadata. Это **не** «юридически чисто автоматически»: `words.txt` остаётся CC BY-SA.

## 7. Критерии PASS (для любого маршрута)

Публичный релиз можно считать готовым только когда **все** пункты выполнены и подтверждены
артефактами, а не намерениями:

1. **P1 — права определений.** Либо `manifest.definitions.audit.passed=true` и
   `rights_review.passed=true` с проверяющим/датой/хэшем выборки, политика не `provisional`;
   либо в release-входе и APK нет `definitions.db`/`origin=wiktionary` (маршрут B/C).
2. **P2 — строгий gate.** `python3 tools/build_game_data.py --check-release --out data` → exit 0
   **без** waiver; `:app:verifyDefinitionsAudit` → `publishable=true`; waiver удалён.
3. **P3 — данные и хэши.** `:app:verifyDictionaryLicenses` проходит; `words.txt`, `campaign.json`,
   `definitions.db` (если есть), `word_sources` и APK-assets совпадают с manifest/BuildConfig;
   `definitions.db` COUNT/покрытие зафиксированы.
4. **P4 — NOTICE и атрибуция.** Каждый распространённый текст/asset имеет источник, лицензию,
   версию/хэш, атрибуцию и (если нужен) ShareAlike; полный CC BY-SA текст/URI; `word_sources`
   имя совпадает; dependency-NOTICE соответствует фактическому APK; нет плейсхолдеров.
5. **P5 — лицензия кода и исходники.** Корневой `LICENSE` (FOSS, совместимый с MIT), SPDX/уведомления,
   публичный тег/коммит, clean-clone build, отсутствие обязательной зависимости от
   `debug.keystore`; release не подписан debug-ключом (или подписан F-Droid).
6. **P6 — F-Droid metadata.** `fdroiddata`/fastlane-описание, applicationId/version, source URL,
   build recipe, лицензия, отсутствие anti-features; нет сети/разрешений/проприетарных зависимостей.
7. **P7 — решение владельца.** Письменное решение по маршруту A/B/C, лицензии кода/данных,
   applicationId, ключу подписи, формулировкам атрибуции и публикации. Без него — `BLOCKED`.
8. **P8 — публикационная проверка (не право).** Ручной smoke-test на устройстве по
   `SHORT15_RELEASE_GATE.md`/`SHORT15_MANUAL_QA.md`: 15/6, T1–T7, 49/49, финал 4/5, обновление,
   IME/TalkBack. Сейчас **NOT RUN**.

Текущий статус: **P1–P8 не выполнены**, кроме технических P3-хэшей (они зелёные, но P3 в целом
требует и правового смысла).

## 8. Конкретный вопрос владельцу

**Какой маршрут выбираете — A (оставить Викисловарь и оформить CC BY-SA 4.0), B (заменить
определения на собственные/разрешённые) или C (выпустить без внешних определений) — и
подтверждаете ли вы следующие пункты письменно?**

Для ответа достаточно заполнить:

1. **Маршрут:** A / B / C.
2. **Если A:** кто персонально проводит ручной аудит прав (имя/роль); на каком основании
   конкретные тексты считаются CC BY-SA 4.0; разрешаете ли ShareAlike для `definitions.db` и
   `words.txt`; кто проверяет 300+ строк и индивидуальные вставки.
3. **Если B:** кто автор/провайдер новых определений; какая лицензия и условия распространения;
   разрешено ли перелицензирование; кто проверяет 300+ строк и отсутствие копирования
   Викисловаря.
4. **Если C:** подтверждаете ли продуктовое изменение (T4/подсказки без значений); понимаете ли,
   что `words.txt` всё равно остаётся CC BY-SA и требует атрибуции.
5. **Для всех:** какая FOSS-лицензия у кода приложения; какой финальный `applicationId` и
   `versionCode/versionName`; кто владеет ключом подписи/публикует в F-Droid; разрешаете ли
   публикацию исходников и данных под выбранными лицензиями; кто отвечает за финальный NOTICE.

Пока ответа нет, статус остаётся **BLOCKED_PUBLIC_RIGHTS**; текущий APK — только технический
pre-release, не публикация.

## 9. Что не проверено / ограничения

- **Нет сети:** первичные тексты лицензий CC BY-SA 4.0/3.0, страницы Викисловаря и история авторов
  офлайн не открывались; проверены только зафиксированные локальные артефакты и URL.
- **Нет юридического заключения:** вопрос «является ли APK адаптацией/сборником» и пределы
  ShareAlike не решались; для этого нужен владелец/юрист и первичные источники.
- **Не проверены все 31 351 статья:** provenance проверен по БД (URL/origin), но не по каждой
  странице; выборка дампа 5 000 ru-noun записей не содержит полей лицензии/автора.
- **Нет clean-clone build:** вывод о `debug.keystore` сделан по `app/build.gradle.kts:126-147` и
  `.gitignore:12`; сборка без ключа не запускалась.
- **Нет F-Droid build:** метаданные/рецепт отсутствуют; inclusion policy F-Droid офлайн не
  перепроверялась.
- **Нет устройства/эмулятора:** ручной smoke-test, IME, TalkBack, обновление — **NOT RUN**.
- **Воспроизводимость:** SHA release-APK между пересборками не воспроизводится (зафиксировано
  в `docs/SHORT15_RELEASE_GATE.md`); APK собран из `d25a60a`, не из текущего HEAD.
- **Иконки:** provenance `words_app_icon_1793804687264.jpg` и vector drawable не подтверждён.
- **`data/` и `definitions.db` не изменялись**; `manifest.audit.passed`/`rights_review.passed`
  остались `false`; waiver не снимался; код не менялся.

## 10. Команды, которыми получены факты

```sh
# Фактический COUNT и схема БД (read-only)
python3 - <<'PY'
import sqlite3
c = sqlite3.connect('file:data/definitions.db?mode=ro', uri=True)
print(c.execute('select count(*) from definitions').fetchone())
print(c.execute('select count(distinct word) from definitions').fetchone())
print(c.execute('pragma user_version').fetchone())
PY

# Строгий release-аудит: должен быть exit 2 без waiver
python3 tools/build_game_data.py --check-release --out data

# Waiver-режим: exit 0, но publishable=false
python3 tools/build_game_data.py --check-release --allow-unaudited-release --out data

# Техническая проверка хэшей/схемы (не прав)
bash ./gradlew --offline :app:verifyDictionaryLicenses --console=plain

# Подпись/упаковка/разрешения APK
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
zipalign -c 4 app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
```

**Итог LEG-01:** публичный релиз short15 **не разрешён**; техническая сборка возможна только по
waiver; для F-Droid нужны лицензия кода, доказанные права на данные/определения, корректный NOTICE,
подпись и metadata. Ни один из маршрутов A/B/C не реализован самовольно.
