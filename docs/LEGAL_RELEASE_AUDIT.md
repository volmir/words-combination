# Независимый юридический и лицензионный аудит перед F-Droid

Дата исходного аудита: **2026-11-18**. Актуализация **LEGAL-GRAPHICS-02**: разрешённые владельцем изменения графики и notices выполнены; текущие результаты — в [отчёте реализации](LEGAL_GRAPHICS_02_REPORT.md). Исходные таблицы зависимостей и артефактов ниже сохраняют свою датированную область, новые APK/AAB проверены отдельно.

## 1. Краткое заключение

**Общая готовность к публикации в основном каталоге F-Droid: BLOCKED по оставшимся отдельным вопросам выпуска. L01 закрыт: PASS.** Восемь XML заменены точными ресурсами Google, menu сохранено; все девять — Apache-2.0. Владелец явно утвердил CC0-1.0 для 11 используемых PNG в пределах принадлежащих ему прав. Удалён только разрешённый неиспользуемый JPG. Новые debug/release APK и AAB проверены. Developer-signed воспроизводимость, metadata, пользовательская privacy policy и применимость условия срока разработки остаются отдельными вопросами.

Независимо подтверждены: свободная MIT-лицензия собственного кода; разрешённый Gradle-граф runtime без Protobuf и прежней SQLite-инфраструктуры; соответствие текущих notices этому графу; сохранность MIT, Apache-2.0 и CC BY-SA 4.0 в APK; ровно 11 дословных T4-загадок из утверждённого CC0-набора; полное соответствие 20 120 слов 23 223 записям закреплённого локального дампа; отсутствие запрашиваемых разрешений и обнаруженных рекламных/аналитических runtime SDK. AAB из исходного аудита был устаревшим; в LEGAL-GRAPHICS-02 он пересобран и проверен, прежние SHA остаются историческим свидетельством.

`PASS` ниже относится только к названному проверенному свойству. Успех Gradle gate и поле `publishable=true` у проверки загадок не означают юридическую приёмку всего приложения. Решение о включении принимает F-Droid; этот отчёт такого решения не заменяет.

## 2. Проверенный commit и состояние проекта

| Свойство | Результат |
|---|---|
| Ветка | `main`; не переключалась |
| HEAD | `1b0becffeec470ef22c9d39b13bf2f4911a3fb79`, merge `feature/datastore-json` |
| Начальный `git status --short` | Пустой, рабочее дерево чистое |
| Источник | Публичный репозиторий из текущего `git remote origin`; GitHub API подтвердил `visibility=public` и доступность проверенного commit. Персональная ссылка владельца не публикуется |
| Корень | `/var/www/words-combination`; персональный путь рабочей машины не публикуется |
| Среда | Temurin OpenJDK 21.0.13+9-LTS; Python 3.10.12; Gradle 9.3.1; Android SDK 36 / Build Tools 36.0.0 |
| Идентификатор / версия | `io.github.volmir.wordscombination`; `versionCode=1`, `versionName=1.0.0`; minSdk 24, targetSdk 36 |
| Исходная область до реализации | Документы аудита `docs/LEGAL_RELEASE_AUDIT.md`, `docs/GRAPHICS_PROVENANCE_AUDIT.md`, `docs/audits/XML_ICON_PROVENANCE.json`; временные проверки в `/tmp/words-legal-audit/`, `/tmp/words-icon-audit/`, штатные build outputs. Код, данные, лицензии, пользовательские сохранения и очередь оркестратора не изменялись |

Прочитаны `AGENTS.md`, README, текущие `PROJECT_STATUS`, контракт `CAMPAIGN`, `OWN_HINTS_TOOLCHAIN`, `DATASTORE_JSON_MIGRATION`, конфигурации Gradle, действующие gates и первичные правовые файлы. Исторические `RELEASE_RIGHTS_DECISION`, `RIGHTS*_REPORT`, `OWN_HINTS_*`, `PRIVACY_*`, `SQLITE_*`, release/short15 QA-документы использованы для поиска свидетельств и разграничения версий, а не как доказательство текущего PASS.

### 2.1. Артефакты исходного аудита — исторический снимок до LEGAL-GRAPHICS-02

| Артефакт | Размер | SHA-256 | Вывод |
|---|---:|---|---|
| Имеющийся debug APK: `app/build/outputs/apk/debug/app-debug.apk` | 12 131 985 | `23bf025d2ca57c3c9b2208062e3880683689b3e8ae6bf1d32dd02ae58fafe12d` | Assets совпадают с текущими; debug-подпись. Записи о commit внутри нет: принадлежность всех байтов HEAD не доказана |
| Release APK **до** пересборки, тот же путь `app/build/outputs/apk/release/app-release-unsigned.apk` | 2 605 294 | `a0caced137ca53e2b9e15ab4a2ff708019f3975fe66f72e96449bb4a55a357c3` | VCS revision `34758974d192e3e3a9d98c0eb09050e17357e7b6`; не приписан текущему чистому HEAD |
| Release APK **после** адресной сборки текущей ветки | 2 605 294 | `3cba72efe4cb5bdcd7827bbe5073548cd75c62ff90e2114d1dbb0c5a08f0563c` | VCS revision совпадает с HEAD; неподписанный технический артефакт |
| Старый AAB: `app/build/outputs/bundle/release/app-release.aab` | 4 510 923 | `3af650df4b5f9bcd65bd1278e67589943954fea4b003543561b117e6360ecd0f` | VCS revision `fec089f90e98bd34b3e89dd8b47b7d5a850c77d1`; старые notices и Protobuf-маркеры; FAIL для использования как текущего выпуска |

Чистый export APK: 2 605 294 bytes, SHA-256 `1957d0fa3a2223c70aeab73fa7259d6ea25ec1934b17953524518882678e99c7`. Все ZIP-entry и их содержимое совпадают с пересобранным APK рабочей ветки, кроме `META-INF/version-control-info.textproto`. Это подтверждает отсутствие скрытых локальных входов для обычной сборки в данной среде, но не воспроизводимость подписанного выпуска на другом buildserver.

`apksigner verify --verbose --print-certs`: debug — валидная v2-подпись `CN=Android Debug`; SHA-256 сертификата `b38eafea4f467575d60c47578e0b7ac69750c64b7f0a1de66fb30c7602d15264`. Release — exit 1, `Missing META-INF/MANIFEST.MF`, ожидаемо для unsigned APK. Не проводились создание ключей, подписание, установка, сброс данных, commit, push или публикация.

### 2.2. Проверки исходного аудита — не подмена проверок новых артефактов

| Проверка | Статус и фактический результат |
|---|---|
| `bash gradlew :app:exportRuntimeLicenseInputs :app:verifyDictionaryLicenses :app:verifyDefinitionsAudit :app:verifyReleaseSigning :app:dependencies --configuration releaseRuntimeClasspath --console=plain` | PASS: BUILD SUCCESSFUL, 22 s; notices verified; JSON-аудит `release_audit=ok`, `publishable=true` **только в области загадок**; подпись не настроена, выдано предупреждение |
| Экспорт реальных runtime, androidTest, core test и buildscript classpath через временный init script | PASS: `bash gradlew --offline -I /tmp/words-legal-audit/export.init.gradle legalAuditResolvedInputs --console=plain`, 3 s; 210 уникальных внешних Maven-координат во всех проверенных областях |
| `bash gradlew --offline :app:assembleRelease --console=plain` | PASS: BUILD SUCCESSFUL, 37 s, 59 задач; строгие gates, release Kotlin, R8, lintVital, упаковка; ключ не создавался |
| Чистый экспорт исходников HEAD и `:app:assembleRelease` | PASS: `git archive HEAD` → `/tmp/words-legal-audit/clean-source`, затем `bash .../gradlew -p ... --offline :app:assembleRelease --console=plain`: BUILD SUCCESSFUL, **2 min 13 s, 59/59 задач выполнены**. Использован существующий Maven/SDK cache; `.local`, дамп и user state исходного проекта не копировались |
| `python3 tools/check_privacy_publication.py --require-generated` | PASS: `PRIVACY_PUBLICATION_CHECK=PASS`; это проверка персональных маркеров/атрибуций, не пользовательская privacy policy |
| Вызов `tools.check_text_storage.check(Path('.'))` | PASS: ошибок нет. Простое исполнение `python3 tools/check_text_storage.py` **не является проверкой**: у модуля нет CLI/main |
| `python3 tools/audit_public_package.py --apk app/build/outputs/apk/debug/app-debug.apk --profile debug --output /tmp/words-legal-audit/debug-package.json` | PASS механических проверок; сам инструмент выдаёт `MECHANICAL_CHECKS_PASS_PUBLIC_RELEASE_BLOCKED` |
| Та же команда для release с `--profile release` | FAIL: штатные `assets/dexopt/baseline.prof`, `baseline.profm` отсутствуют в whitelist инструмента; L06 |
| `python3 -m unittest tools.tests.test_library_notices tools.tests.test_owned_riddles_json tools.tests.test_owned_riddles_portable tools.tests.test_rights_release_gate tools.tests.test_public_package_audit tools.tests.test_privacy_publication` | PASS: **31 тест**, 10.775 s |
| Независимые ZIP/DEX/ELF/JSON-проверки | PASS в названных ниже областях: все runtime assets и provenance, native SHA, типы DEX, маркеры версий, манифест, права 11 текстов |
| Полный `:core:test`, assembleDebug, полная tools-suite, connected UI, матрица разрешений | NOT_APPLICABLE к изменениям одного отчёта; в этом аудите не запускались. Реальная Android-миграция — UNKNOWN, U03; игровые сохранения не трогались |
| `fdroid build`, scanner, compare/reproducible signed APK | BLOCKED выбранной схемы релиза: нет подготовленного recipe/подписанного эталона; `fdroid` в среде отсутствует |

Временный init script только регистрирует задачу: экспортирует `resolvedConfiguration.resolvedArtifacts` для `:app` (`debugRuntimeClasspath`, `releaseRuntimeClasspath`, `debugAndroidTestRuntimeClasspath`), `:core:testRuntimeClasspath`, `buildscript.classpath` root/app/core. Для каждого артефакта фиксируются точные `group:name:version` и фактический файл. POM проверены с учётом наследования лицензии и XML как с namespace, так и без него. Временные логи не являются частью исходной поставки и не требуют включения в APK.

## 3. Реестр зависимостей с лицензиями

### 3.1. Области и обязанности

| Область / компонент | Версия / источник | Лицензия и подтверждение | Attribution / текст / ограничения | F-Droid и статус |
|---|---|---|---|---|
| Собственный Kotlin `core/src/main`, `app/src/main`; Python `tools/`, Gradle-скрипты, тесты | Проверенный HEAD; Git | MIT, `LICENSE:1–21`, явное заявление в `legal/THIRD_PARTY_NOTICES.txt:62–66` | Сохранять copyright/permission notice; текст MIT входит в APK. Отдельные данные и изображения не объявляются MIT автоматически | PASS наличия свободной лицензии кода; независимое установление прав каждого автора за пределами автоматической проверки |
| Runtime AndroidX/Compose/Kotlin/kotlinx/Okio/Guava annotations | 75 уникальных внешних координат: 71 release, ещё 4 только debug; Google Maven / Maven Central | Все 75 POM декларируют Apache-2.0; фактические AAR/JAR и embedded notices сверены с реестром | Полный Apache-2.0; обязательные NOTICE, если поставлены upstream; исходные изменения обозначать при перераспространении модификаций. Нельзя заявлять передачу товарных знаков | PASS лицензий проверенных артефактов; native источники — граница U02 |
| Native graphics-path и datastore shared counter | `androidx.graphics:graphics-path:1.0.1`; `androidx.datastore:datastore-core-android:1.1.7` | Apache-2.0 POM; 8/8 SHA библиотек APK/AAB совпадают с AAR | Покрываются Apache-2.0/реестром AndroidX; неизвестных отдельных `.so` нет | PASS provenance до доверенного Maven AAR; U02 — предел независимой проверки upstream-сборки |
| Gradle и wrapper | distribution 9.3.1, wrapper SHA `497c8c2a…194a9c7` | Apache-2.0; manifest wrapper содержит SPDX; SHA совпадает с официальным wrapper 9.5.x/9.6.x, а не с wrapper 9.3.1 | Build-time; не включается в APK. Разная версия wrapper и distribution сама по себе не нарушение | PASS происхождения wrapper по [официальному реестру SHA](https://gradle.org/release-checksums/) |
| AGP и Gradle plugins | AGP 9.1.1; Kotlin JVM/Compose/serialization plugins 2.2.10 | Apache-2.0 по POM фактически разрешённых plugins; список ниже | Build-time, не APK; лицензии инструментов сохраняются при распространении самих инструментов | PASS; наличие build-time Protobuf/analytics не равно runtime-зависимости |
| JDK | Temurin 21.0.13, Eclipse Adoptium | OpenJDK GPL-2.0 с Classpath Exception; не Oracle JDK | Compiler/JVM хоста; лицензия результата компиляции от этого не меняется | PASS выбранного свободного семейства; полная независимая пересборка JDK не выполнялась |
| Android SDK / Build Tools | platform 36, build-tools 36.0.0, официальные Android tools | Лицензии зависят от пакета SDK; **не** объявляются целиком Apache-2.0 | Инструменты хоста, не поставляемые ресурсы приложения | Официальные SDK prebuilts предусмотрены Inclusion Policy; F-Droid recipe не проверен |
| Python | CPython 3.10.12 и stdlib | PSF-2.0 и notices дистрибутива; отдельные pip runtime-пакеты генератору загадок не нужны | Host-only; не APK | PASS отсутствия сторонних Python-пакетов в обязательном JSON-build пути |
| Android-платформа: `org.json`, системные шрифты, libc/libdl/libm, platform SQLite | ОС устройства, не Maven-встроенная библиотека | Компоненты платформы, отдельно не поставляются | Не добавлять лицензию платформенного SQLite как будто встроен sqlite3; системные sans не входят как font assets | NOT_APPLICABLE для отдельного bundled-компонента |

Обязанности Apache сверялись с [Apache License 2.0, §4](https://www.apache.org/licenses/LICENSE-2.0); версия и роль каждого компонента установлены по разрешению Gradle, не по одному version catalog.

### 3.2. Полный runtime-реестр

¹ PASS означает подтверждение Apache-2.0 в cached Maven POM и соответствие файлов текущему `legal/RUNTIME_LIBRARIES.json`. В нём сохранены SHA-256 каждого AAR/JAR, POM (включая parent) и embedded notice. Ссылка на upstream/source находится в POM: AndroidX — Google Maven/AndroidX source tree; JetBrains — Maven Central/Kotlin/kotlinx; Square — Maven Central/Okio; Google — Maven Central/Guava. R8 удаляет неиспользуемые классы, поэтому реестр является консервативным набором разрешённых компонентов, не списком всех оставшихся классов APK.

| Компонент: точная версия | Профиль | Лицензия / статус |
|---|---|---|
| `androidx.activity:activity-compose:1.10.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.activity:activity-ktx:1.10.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.activity:activity:1.10.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.annotation:annotation-experimental:1.4.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.annotation:annotation-jvm:1.8.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.arch.core:core-common:2.2.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.arch.core:core-runtime:2.2.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.autofill:autofill:1.0.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.collection:collection-jvm:1.4.4` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.collection:collection-ktx:1.4.4` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.animation:animation-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.animation:animation-core-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.foundation:foundation-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.foundation:foundation-layout-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.material3:material3-android:1.3.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.material:material-android:1.7.0` | только debug | Apache-2.0 / PASS¹ |
| `androidx.compose.material:material-icons-core-android:1.7.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.material:material-ripple-android:1.7.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.runtime:runtime-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.runtime:runtime-saveable-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-geometry-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-graphics-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-test-manifest:1.7.2` | только debug | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-text-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-tooling-android:1.7.2` | только debug | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-tooling-data-android:1.7.2` | только debug | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-tooling-preview-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-unit-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.compose.ui:ui-util-android:1.7.2` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.concurrent:concurrent-futures:1.1.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.core:core-ktx:1.13.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.core:core-viewtree:1.0.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.core:core:1.13.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.customview:customview-poolingcontainer:1.0.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.datastore:datastore-android:1.1.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.datastore:datastore-core-android:1.1.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.datastore:datastore-core-okio-jvm:1.1.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.emoji2:emoji2:1.3.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.graphics:graphics-path:1.0.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.interpolator:interpolator:1.0.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-common-java8:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-common-jvm:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-livedata-core:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-process:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-runtime-android:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-runtime-compose-android:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-runtime-ktx-android:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-viewmodel-android:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.7` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.navigation:navigation-common-ktx:2.8.9` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.navigation:navigation-common:2.8.9` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.navigation:navigation-compose:2.8.9` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.navigation:navigation-runtime-ktx:2.8.9` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.navigation:navigation-runtime:2.8.9` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.profileinstaller:profileinstaller:1.4.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.savedstate:savedstate-ktx:1.2.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.savedstate:savedstate:1.2.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.startup:startup-runtime:1.1.1` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.tracing:tracing:1.0.0` | debug, release | Apache-2.0 / PASS¹ |
| `androidx.versionedparcelable:versionedparcelable:1.1.1` | debug, release | Apache-2.0 / PASS¹ |
| `com.google.guava:listenablefuture:1.0` | debug, release | Apache-2.0 / PASS¹ |
| `com.squareup.okio:okio-jvm:3.4.0` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlin:kotlin-android-extensions-runtime:1.9.22` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlin:kotlin-parcelize-runtime:1.9.22` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.0` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.0` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.10` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.7.3` | debug, release | Apache-2.0 / PASS¹ |
| `org.jetbrains:annotations:23.0.0` | debug, release | Apache-2.0 / PASS¹ |

Compose BOM `androidx.compose:compose-bom:2024.09.00` — build-time платформа ограничений, а не дополнительный DEX. POM проверен в конфигурации/каталоге; основные фактические версии: UI/runtime/foundation 1.7.2, Material3 1.3.0, material-icons/ripple 1.7.0. DataStore — Core + JSON, без `datastore-preferences*`. Kotlin runtime 2.2.10; более новый Kotlin 2.2.21 в classpath самого AGP не переносится автоматически в приложение.

### 3.3. Build-time / plugins / транзитивные инструменты

Root buildscript разрешил 112 файлов / **111 уникальных координат** (две variant JAR у одного `kotlin-gradle-plugin-api`). Maven-лицензии всех этих координат прочитаны. POM-ссылки ниже указывают первичную metadata конкретной версии; вывод основан на cached POM, реально участвовавшем в разрешении. Для бинарников из разрешённых Google Maven/Maven Central репозиториев допустимость оценивается по свободной лицензии, а не только по домену.

| Компонент: точная разрешённая версия | Лицензия / SPDX | Первичное свидетельство |
|---|---|---|
| `androidx.databinding:databinding-common:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-common/9.1.1/databinding-common-9.1.1.pom) |
| `androidx.databinding:databinding-compiler-common:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler-common/9.1.1/databinding-compiler-common-9.1.1.pom) |
| `com.android.databinding:baseLibrary:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/databinding/baseLibrary/9.1.1/baseLibrary-9.1.1.pom) |
| `com.android.tools.analytics-library:crash:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/analytics-library/crash/32.1.1/crash-32.1.1.pom) |
| `com.android.tools.analytics-library:protos:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/analytics-library/protos/32.1.1/protos-32.1.1.pom) |
| `com.android.tools.analytics-library:shared:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/analytics-library/shared/32.1.1/shared-32.1.1.pom) |
| `com.android.tools.analytics-library:tracker:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/analytics-library/tracker/32.1.1/tracker-32.1.1.pom) |
| `com.android.tools.build.jetifier:jetifier-core:1.0.0-beta10` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/jetifier/jetifier-core/1.0.0-beta10/jetifier-core-1.0.0-beta10.pom) |
| `com.android.tools.build.jetifier:jetifier-processor:1.0.0-beta10` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/jetifier/jetifier-processor/1.0.0-beta10/jetifier-processor-1.0.0-beta10.pom) |
| `com.android.tools.build:aapt2-proto:9.1.1-14792394` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2-proto/9.1.1-14792394/aapt2-proto-9.1.1-14792394.pom) |
| `com.android.tools.build:aaptcompiler:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/aaptcompiler/9.1.1/aaptcompiler-9.1.1.pom) |
| `com.android.tools.build:apksig:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/9.1.1/apksig-9.1.1.pom) |
| `com.android.tools.build:apkzlib:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/apkzlib/9.1.1/apkzlib-9.1.1.pom) |
| `com.android.tools.build:builder-model:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder-model/9.1.1/builder-model-9.1.1.pom) |
| `com.android.tools.build:builder-test-api:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder-test-api/9.1.1/builder-test-api-9.1.1.pom) |
| `com.android.tools.build:builder:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/9.1.1/builder-9.1.1.pom) |
| `com.android.tools.build:bundletool:1.18.3` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/bundletool/1.18.3/bundletool-1.18.3.pom) |
| `com.android.tools.build:gradle-api:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle-api/9.1.1/gradle-api-9.1.1.pom) |
| `com.android.tools.build:gradle-common-api:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle-common-api/9.1.1/gradle-common-api-9.1.1.pom) |
| `com.android.tools.build:gradle-settings-api:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle-settings-api/9.1.1/gradle-settings-api-9.1.1.pom) |
| `com.android.tools.build:gradle:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.1.1/gradle-9.1.1.pom) |
| `com.android.tools.build:manifest-merger:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/build/manifest-merger/32.1.1/manifest-merger-32.1.1.pom) |
| `com.android.tools.ddms:ddmlib:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/ddms/ddmlib/32.1.1/ddmlib-32.1.1.pom) |
| `com.android.tools.layoutlib:layoutlib-api:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/layoutlib/layoutlib-api/32.1.1/layoutlib-api-32.1.1.pom) |
| `com.android.tools.lint:lint-model:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-model/32.1.1/lint-model-32.1.1.pom) |
| `com.android.tools.lint:lint-typedef-remover:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/lint/lint-typedef-remover/32.1.1/lint-typedef-remover-32.1.1.pom) |
| `com.android.tools.utp:gradle-work-action-api:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/utp/gradle-work-action-api/32.1.1/gradle-work-action-api-32.1.1.pom) |
| `com.android.tools:annotations:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/annotations/32.1.1/annotations-32.1.1.pom) |
| `com.android.tools:common:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/common/32.1.1/common-32.1.1.pom) |
| `com.android.tools:dvlib:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/dvlib/32.1.1/dvlib-32.1.1.pom) |
| `com.android.tools:repository:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/repository/32.1.1/repository-32.1.1.pom) |
| `com.android.tools:sdk-common:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/sdk-common/32.1.1/sdk-common-32.1.1.pom) |
| `com.android.tools:sdklib:32.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/tools/sdklib/32.1.1/sdklib-32.1.1.pom) |
| `com.android:signflinger:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/signflinger/9.1.1/signflinger-9.1.1.pom) |
| `com.android:zipflinger:9.1.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/com/android/zipflinger/9.1.1/zipflinger-9.1.1.pom) |
| `com.google.auto.value:auto-value-annotations:1.6.2` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/auto/value/auto-value-annotations/1.6.2/auto-value-annotations-1.6.2.pom) |
| `com.google.code.findbugs:jsr305:3.0.2` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) |
| `com.google.code.gson:gson:2.11.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.pom) |
| `com.google.crypto.tink:tink:1.7.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/crypto/tink/tink/1.7.0/tink-1.7.0.pom) |
| `com.google.dagger:dagger:2.28.3` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/dagger/dagger/2.28.3/dagger-2.28.3.pom) |
| `com.google.errorprone:error_prone_annotations:2.28.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.28.0/error_prone_annotations-2.28.0.pom) |
| `com.google.flatbuffers:flatbuffers-java:1.12.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/flatbuffers/flatbuffers-java/1.12.0/flatbuffers-java-1.12.0.pom) |
| `com.google.guava:failureaccess:1.0.2` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/guava/failureaccess/1.0.2/failureaccess-1.0.2.pom) |
| `com.google.guava:guava:33.3.1-jre` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.pom) |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.pom) |
| `com.google.j2objc:j2objc-annotations:3.0.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/j2objc/j2objc-annotations/3.0.0/j2objc-annotations-3.0.0.pom) |
| `com.google.jimfs:jimfs:1.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/jimfs/jimfs/1.1/jimfs-1.1.pom) |
| `com.google.protobuf:protobuf-java-util:3.25.5` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java-util/3.25.5/protobuf-java-util-3.25.5.pom) |
| `com.google.protobuf:protobuf-java:3.25.5` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java/3.25.5/protobuf-java-3.25.5.pom) |
| `com.googlecode.juniversalchardet:juniversalchardet:1.0.3` | MPL-1.1 | [POM](https://repo.maven.apache.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom) |
| `com.squareup:javapoet:1.13.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/squareup/javapoet/1.13.0/javapoet-1.13.0.pom) |
| `com.squareup:javawriter:2.5.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/squareup/javawriter/2.5.0/javawriter-2.5.0.pom) |
| `com.sun.istack:istack-commons-runtime:3.0.8` | BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/com/sun/istack/istack-commons-runtime/3.0.8/istack-commons-runtime-3.0.8.pom) |
| `com.sun.xml.fastinfoset:FastInfoset:1.2.16` | Apache-2.0 / BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/com/sun/xml/fastinfoset/FastInfoset/1.2.16/FastInfoset-1.2.16.pom) |
| `commons-codec:commons-codec:1.17.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.17.1/commons-codec-1.17.1.pom) |
| `commons-io:commons-io:2.16.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.pom) |
| `commons-logging:commons-logging:1.2` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.pom) |
| `jakarta.activation:jakarta.activation-api:1.2.1` | BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/1.2.1/jakarta.activation-api-1.2.1.pom) |
| `jakarta.xml.bind:jakarta.xml.bind-api:2.3.2` | BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/2.3.2/jakarta.xml.bind-api-2.3.2.pom) |
| `javax.inject:javax.inject:1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/javax/inject/javax.inject/1/javax.inject-1.pom) |
| `net.java.dev.jna:jna-platform:5.6.0` | Apache-2.0 OR LGPL-2.1-or-later | [POM](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.pom) |
| `net.java.dev.jna:jna:5.6.0` | Apache-2.0 OR LGPL-2.1-or-later | [POM](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.6.0/jna-5.6.0.pom) |
| `net.sf.jopt-simple:jopt-simple:4.9` | MIT | [POM](https://repo.maven.apache.org/maven2/net/sf/jopt-simple/jopt-simple/4.9/jopt-simple-4.9.pom) |
| `net.sf.kxml:kxml2:2.3.0` | BSD style; точный SPDX UNKNOWN / Public Domain; SPDX не установлен | [POM](https://repo.maven.apache.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.pom) |
| `org.apache.commons:commons-compress:1.27.1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/apache/commons/commons-compress/1.27.1/commons-compress-1.27.1.pom) |
| `org.apache.commons:commons-lang3:3.16.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.16.0/commons-lang3-3.16.0.pom) |
| `org.apache.httpcomponents:httpclient:4.5.14` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpclient/4.5.14/httpclient-4.5.14.pom) |
| `org.apache.httpcomponents:httpcore:4.4.16` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.pom) |
| `org.apache.httpcomponents:httpmime:4.5.6` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpmime/4.5.6/httpmime-4.5.6.pom) |
| `org.bitbucket.b_c:jose4j:0.9.5` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/bitbucket/b_c/jose4j/0.9.5/jose4j-0.9.5.pom) |
| `org.bouncycastle:bcpkix-jdk18on:1.79` | MIT | [POM](https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.79/bcpkix-jdk18on-1.79.pom) |
| `org.bouncycastle:bcprov-jdk18on:1.79` | MIT | [POM](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.79/bcprov-jdk18on-1.79.pom) |
| `org.bouncycastle:bcutil-jdk18on:1.79` | MIT | [POM](https://repo.maven.apache.org/maven2/org/bouncycastle/bcutil-jdk18on/1.79/bcutil-jdk18on-1.79.pom) |
| `org.checkerframework:checker-qual:3.43.0` | MIT | [POM](https://repo.maven.apache.org/maven2/org/checkerframework/checker-qual/3.43.0/checker-qual-3.43.0.pom) |
| `org.glassfish.jaxb:jaxb-runtime:2.3.2` | BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/jaxb-runtime/2.3.2/jaxb-runtime-2.3.2.pom) |
| `org.glassfish.jaxb:txw2:2.3.2` | BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/org/glassfish/jaxb/txw2/2.3.2/txw2-2.3.2.pom) |
| `org.jdom:jdom2:2.0.6` | JDOM (текст в POM) | [POM](https://repo.maven.apache.org/maven2/org/jdom/jdom2/2.0.6/jdom2-2.0.6.pom) |
| `org.jetbrains.kotlin:abi-tools-api:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/abi-tools-api/2.2.10/abi-tools-api-2.2.10.pom) |
| `org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.2.10/compose-compiler-gradle-plugin-2.2.10.pom) |
| `org.jetbrains.kotlin:fus-statistics-gradle-plugin:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/fus-statistics-gradle-plugin/2.2.10/fus-statistics-gradle-plugin-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-build-statistics:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-build-statistics/2.2.10/kotlin-build-statistics-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-build-tools-api:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-build-tools-api/2.2.10/kotlin-build-tools-api-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-compiler-runner:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-runner/2.2.10/kotlin-compiler-runner-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-daemon-client:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-daemon-client/2.2.10/kotlin-daemon-client-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-annotations:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-annotations/2.2.10/kotlin-gradle-plugin-annotations-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-api/2.2.10/kotlin-gradle-plugin-api-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-idea-proto:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-idea-proto/2.2.10/kotlin-gradle-plugin-idea-proto-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-idea:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-idea/2.2.10/kotlin-gradle-plugin-idea-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin-model:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin-model/2.2.10/kotlin-gradle-plugin-model-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.10/kotlin-gradle-plugin-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-klib-commonizer-api:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-klib-commonizer-api/2.2.10/kotlin-klib-commonizer-api-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-native-utils:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-native-utils/2.2.10/kotlin-native-utils-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-reflect:2.2.21` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.2.21/kotlin-reflect-2.2.21.pom) |
| `org.jetbrains.kotlin:kotlin-serialization:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-serialization/2.2.10/kotlin-serialization-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/2.2.10/kotlin-stdlib-jdk7-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/2.2.10/kotlin-stdlib-jdk8-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib:2.2.21` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.pom) |
| `org.jetbrains.kotlin:kotlin-tooling-core:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-tooling-core/2.2.10/kotlin-tooling-core-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-util-io:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-util-io/2.2.10/kotlin-util-io-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-util-klib-metadata:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-util-klib-metadata/2.2.10/kotlin-util-klib-metadata-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-util-klib:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-util-klib/2.2.10/kotlin-util-klib-2.2.10.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.pom) |
| `org.jetbrains:annotations:13.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom) |
| `org.jvnet.staxex:stax-ex:1.8.1` | BSD-3-Clause (EDL-1.0) | [POM](https://repo.maven.apache.org/maven2/org/jvnet/staxex/stax-ex/1.8.1/stax-ex-1.8.1.pom) |
| `org.ow2.asm:asm-analysis:9.9` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/ow2/asm/asm-analysis/9.9/asm-analysis-9.9.pom) |
| `org.ow2.asm:asm-commons:9.9` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.9/asm-commons-9.9.pom) |
| `org.ow2.asm:asm-tree:9.9` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.pom) |
| `org.ow2.asm:asm-util:9.9` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/ow2/asm/asm-util/9.9/asm-util-9.9.pom) |
| `org.ow2.asm:asm:9.9` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.9/asm-9.9.pom) |
| `org.slf4j:slf4j-api:1.7.30` | MIT | [POM](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.30/slf4j-api-1.7.30.pom) |
| `org.tensorflow:tensorflow-lite-metadata:0.2.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/tensorflow/tensorflow-lite-metadata/0.2.0/tensorflow-lite-metadata-0.2.0.pom) |

Отдельные обязанности build-only: MIT/BSD/JDOM требуют сохранения своих notices при распространении самих библиотек; MPL/EPL/LGPL — соответствующих условий при модификации/распространении инструментов. Этот APK их не включает. JNA допускает Apache-2.0 как альтернативу LGPL; JAXB EDL — BSD-3-Clause. JDOM имеет свой текст в POM; не подменён общим Apache. kXML POM явно разделяет BSD-style `org.kxml2` и Public Domain `org.xmlpull.v1`; точный SPDX BSD-варианта **UNKNOWN**, но наличие разрешительной upstream-декларации и build-only роль установлены. Это частная неопределённость U02, не основание объявить APK несвободным.

Для Bouncy Castle дополнительно прочитан [LICENSE точного тега r1rv79](https://github.com/bcgit/bc-java/blob/r1rv79/LICENSE.html): MIT-текст. `protobuf-java` и `protobuf-java-util` 3.25.5 под BSD-3-Clause остаются **только в buildscript**, используются инструментами AGP. `com.android.tools.analytics-library:*`, TensorFlow metadata и HTTP-клиент также build-time; в runtime-экспорте их нет.

### 3.4. Test-only

Следующие **27 координат** добавляются тестовыми конфигурациями и отсутствуют в main debug/release runtime. В тестовом графе JUnit Jupiter фактически **5.14.1**, хотя в catalog engine запрошен 5.10.2: launcher 1.14.1 разрешает более новую согласованную ветку. Лицензия установлена по итоговому графу.

| Компонент: точная разрешённая версия | Лицензия / SPDX | Первичное свидетельство |
|---|---|---|
| `androidx.compose.ui:ui-test-android:1.7.2` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-android/1.7.2/ui-test-android-1.7.2.pom) |
| `androidx.compose.ui:ui-test-junit4-android:1.7.2` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-junit4-android/1.7.2/ui-test-junit4-android-1.7.2.pom) |
| `androidx.concurrent:concurrent-futures-ktx:1.1.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures-ktx/1.1.0/concurrent-futures-ktx-1.1.0.pom) |
| `androidx.test.espresso:espresso-core:3.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/espresso/espresso-core/3.6.1/espresso-core-3.6.1.pom) |
| `androidx.test.espresso:espresso-idling-resource:3.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/espresso/espresso-idling-resource/3.6.1/espresso-idling-resource-3.6.1.pom) |
| `androidx.test.ext:junit:1.2.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/ext/junit/1.2.1/junit-1.2.1.pom) |
| `androidx.test.services:storage:1.5.0` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/services/storage/1.5.0/storage-1.5.0.pom) |
| `androidx.test:core:1.6.1` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/core/1.6.1/core-1.6.1.pom) |
| `androidx.test:monitor:1.7.2` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/monitor/1.7.2/monitor-1.7.2.pom) |
| `androidx.test:runner:1.6.2` | Apache-2.0 | [POM](https://dl.google.com/dl/android/maven2/androidx/test/runner/1.6.2/runner-1.6.2.pom) |
| `com.google.code.findbugs:jsr305:3.0.2` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom) |
| `com.google.errorprone:error_prone_annotations:2.18.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.18.0/error_prone_annotations-2.18.0.pom) |
| `javax.inject:javax.inject:1` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/javax/inject/javax.inject/1/javax.inject-1.pom) |
| `junit:junit:4.13.2` | EPL-1.0 | [POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom) |
| `org.hamcrest:hamcrest-core:1.3` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.pom) |
| `org.hamcrest:hamcrest-library:1.3` | BSD-3-Clause | [POM](https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-library/1.3/hamcrest-library-1.3.pom) |
| `org.jetbrains.kotlin:kotlin-test-junit5:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-test-junit5/2.2.10/kotlin-test-junit5-2.2.10.pom) |
| `org.jetbrains.kotlin:kotlin-test:2.2.10` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-test/2.2.10/kotlin-test-2.2.10.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.10.2` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-test-jvm/1.10.2/kotlinx-coroutines-test-jvm-1.10.2.pom) |
| `org.jetbrains:annotations:13.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom) |
| `org.json:json:20240303` | Public Domain; SPDX не установлен | [POM](https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.pom) |
| `org.junit.jupiter:junit-jupiter-api:5.14.1` | EPL-2.0 | [POM](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-api/5.14.1/junit-jupiter-api-5.14.1.pom) |
| `org.junit.jupiter:junit-jupiter-engine:5.14.1` | EPL-2.0 | [POM](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter-engine/5.14.1/junit-jupiter-engine-5.14.1.pom) |
| `org.junit.platform:junit-platform-commons:1.14.1` | EPL-2.0 | [POM](https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-commons/1.14.1/junit-platform-commons-1.14.1.pom) |
| `org.junit.platform:junit-platform-engine:1.14.1` | EPL-2.0 | [POM](https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-engine/1.14.1/junit-platform-engine-1.14.1.pom) |
| `org.junit.platform:junit-platform-launcher:1.14.1` | EPL-2.0 | [POM](https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-launcher/1.14.1/junit-platform-launcher-1.14.1.pom) |
| `org.opentest4j:opentest4j:1.3.0` | Apache-2.0 | [POM](https://repo.maven.apache.org/maven2/org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.pom) |

`org.json:json:20240303` — [Public Domain в LICENSE точного тега](https://github.com/stleary/JSON-java/blob/20240303/LICENSE), а не старое условие «good, not evil» (`JSON`). Для голого заявления Public Domain не придуман стандартный SPDX. JUnit 4 — EPL-1.0, JUnit 5 — EPL-2.0, Hamcrest — BSD-3-Clause; это не основания добавлять их лицензии в main APK при отсутствии этих библиотек там. Debug-only Compose tooling/test-manifest перечислены в runtime отдельно, а не ошибочно названы release-компонентами.

### 3.5. Native и prebuilt-инвентарь

В APK и AAB найдены ровно две семьи библиотек, каждая для `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`: `libandroidx.graphics.path.so` и `libdatastore_shared_counter.so`. **Каждая из восьми библиотек сравнивалась по полному SHA-256 с JNI-entry реально разрешённого AAR, совпадения 8/8**. Собственных sqlite3 `.so`, `.db`, SQLite header, неизвестных JAR/AAR/DEX assets нет. Для x86_64 ELF `DT_NEEDED` обеих семей указывает только системные `libm.so`, `libdl.so`, `libc.so`. В debug/release APK нет файлов `.ttf`, `.otf`, `.woff`, `.woff2`; `Theme.kt` использует `FontFamily.Default`, `themes.xml` — системный `sans`. Лицензия отдельного встроенного шрифта не требуется, поскольку такого файла не поставляется.

Исходники AndroidX graphics native имеют Apache-заголовки ([Conic.cpp](https://github.com/androidx/androidx/blob/androidx-main/graphics/graphics-path/src/main/cpp/Conic.cpp), [Path.h](https://github.com/androidx/androidx/blob/androidx-main/graphics/graphics-path/src/main/cpp/Path.h), [Filament math header](https://github.com/androidx/androidx/blob/androidx-main/graphics/graphics-path/src/main/cpp/math/vec2.h)); просмотр main не доказывает точную ревизию бинарника 1.0.1, U02. Упоминание Skia в структуре API не доказывает, что в `.so` включена отдельная полная BSD-библиотека Skia: такое нарушение не заявляется.

Tracked executable prebuilt проекта — `gradle/wrapper/gradle-wrapper.jar`, официальный SHA проверен. Архивы `docs/qa/pre04-17/*.zip` просмотрены: внутри нет APK/AAB/JAR/SO/DEX/EXE/DB; это QA-логи и свидетельства, в APK не копируются. Собственный pinned SQLite toolchain, Ubuntu `.deb` и SQL-генераторы отсутствуют в активном build пути; `check_text_storage` проходит. Не все файлы хостового Gradle/SDK-кэша относятся к приложению.

### 3.6. LEGAL-GRAPHICS-02: текущая графика

**PASS: 9 векторов Google Material Icons / Material Symbols, Apache-2.0; 11 PNG CC0-1.0 с явным одобрением владельца.** Восемь прежних непроверенных контуров заменены, а не объявлены задним числом Google. Menu побайтно сохранено. Crown взят из `symbols/web/crown/materialsymbolsoutlined/crown_24px.svg` закреплённого commit, нормализован из координат 960 в viewport 24 точным group-transform. Полный Apache-текст сохранён, notices/About актуализированы.

PNG созданы с Google AI Studio без загруженных изображений по свидетельству владельца. [Отдельное решение владельца](RASTER_GRAPHICS_OWNER_APPROVAL.json) разрешает CC0 только в пределах его прав, без гарантии исключительности. Проверены применимые условия; подробный ограниченный вывод и первичные ссылки — [LEGAL_GRAPHICS_02_REPORT.md](LEGAL_GRAPHICS_02_REPORT.md). Новый офлайн-gate не принимает pending approval или несовпадающие SHA.

В новых debug/release APK и AAB проверены реальные ресурсы и уведомления, удалённый JPG отсутствует. Debug дополнительно содержит 52 PNG AndroidX Core Apache-2.0, установленные по побайтному воспроизведению AAPT2; release/AAB содержат только 11 проектных PNG. [Реестр пакетов](audits/LEGAL_GRAPHICS_02_PACKAGES.json) фиксирует все пути, размеры, SHA и происхождение, в том числе переименованные ресурсы.

Исторические результаты с одним menu PASS и восемью UNKNOWN сохранены в [GRAPHICS_PROVENANCE_AUDIT.md](GRAPHICS_PROVENANCE_AUDIT.md) и `historical_audits` [XML-реестра](audits/XML_ICON_PROVENANCE.json). Они не описывают новые файлы. Текущий графический блокер **L01 устранён**, остальные блокеры не объединялись с ним.

## 4. Реестр игровых данных с происхождением

### 4.1. Точные входы и поставка

| Материал | SHA-256 / объём | Происхождение / лицензия | Поставка, обязанности и статус |
|---|---|---|---|
| `data/words.txt` | 20 120 уникальных строк, 330 018 bytes; `9c1aed9e0011e4173dffd0f4281832d821d4de90623d19273f7fc8e68c212489` | Русский Викисловарь → Kaikki/Wiktextract → отбор/ё→е; CC-BY-SA-4.0 | Runtime; PASS целостности/цепочки; сохранить авторов, URI, описание изменений, ShareAlike |
| `data/word_sources.jsonl.gz` | 383 206 bytes; `7f1f4e259916d5f9cb822737f6cf9886c3cef98215795ede69bf7a67eb2f4c9b` | Тот же дамп; CC-BY-SA-4.0; 20 120 строк, 23 223 `title/line/source` | Runtime; в APK/AAB AGP снимает gzip и называет `word_sources.jsonl`; полные распакованные байты совпадают. PASS |
| Локальный Kaikki raw dump | SHA `98bebfb8013064d23bc6f0981b64cd097f64e098c9e434ebb97684c9606cc2da`; `.local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz` | `manifest.source`; русский Викисловарь, CC-BY-SA-4.0 | Не APK. SHA проверен; все 23 223 ссылки на строки сверены независимо: `lang_code=ru`, `pos=noun`, заголовок/нормализация совпадают. PASS; тексты статей не копировались в отчёт |
| FrequencyWords `content/2018/ru/ru_full.txt` | SHA `32dfd94138aea2667ec455dcc8512029e39bd4b8ea70b09410d569b69d511a30`, ревизия `f8a65e6ddc17e0baa2e366a909986798d8dbe55b` | Hermit Dave/OpenSubtitles; CC-BY-SA-3.0 для contents; MIT относится к generator code, а не данным | Build-time signal; локальный файл хэширован, README закреплённой ревизии прочитан. Исходные субтитры/файл не поставляются; атрибуция и изменения сохранены. PASS декларированной лицензии; U01 — права внешних поставщиков |
| `data/owned_riddles.json` | 1 666 bytes; `05e3c05099fe38648834a6b9f70f458cf51238edc7bacdc85d8af3932ad11f63` | 11 точных `hint` из утверждённого набора, CC0-1.0 | Runtime; PASS hashes/approval/39-row audit/11 text comparisons; нет подмены формулировок |
| `docs/owned_hints.json` | 30 027 bytes; `061633244ec968aeec2e0dc50aca1b61ecb8ca735590c5510938ab5085b711b0` | 39 `hint`, заявленное newly-authored-for-game; отдельное owner approval CC0-1.0 | Authoring/evidence, **не runtime**. Исторические `pending` внутри неизменяемого снимка перекрываются отдельным approval точного SHA, а не ручной правкой исходника. PASS технической связи |
| `tools/owned_hint_index.json` | 330 678 bytes; `81d4c21302c54a969466c289fa950e7d255d3eb35a5b7bb641b4f8facb392335` | 2 562 записи category/forms/related, структурные данные Kaikki, owner/ai-категории; CC-BY-SA-4.0 | Только build/provenance; JSON schema и SHA checked; не CC0. В APK отсутствует. PASS разграничения; точность/права всей семантики не заново удостоверены |
| `data/campaign.json` | 9 651 bytes; `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e` | Собственная конфигурация short15 по контракту; семантические элементы имеют указанные выше источники | Runtime: 15 уровней, 6 этапов, 49 заданий/звёзд. PASS целостности и T4-покрытия; собственной лицензией конфигурации не отменяются права на производные данные |
| `data/levels.json` | 4 896 bytes; `e4ded7a9bdfc5855c69bd167abb03c25d5291652a1504956d3a0e99e4742e73a` | Исторический снимок v1, MIT по `manifest.levels_source` | Фактически **включён** в runtime assets, хотя роль — историческое сравнение/совместимость. MIT сохранён. PASS; не спутан с непоставляемой историей |
| `data/golden_search.json`, frequent nouns golden, manifest | Генерируемые проверки / зафиксированный test reference | Производные словарные данные и конфигурация; сохранять применимую CC BY-SA, а не объявлять весь массив CC0 | Main APK не содержит golden/manifest; golden подключён тестам. PASS разграничения |

Wikimedia допускает атрибуцию текстовых материалов адресом исходной страницы с историей авторов; дополнительные notice импортированного материала могут требовать сохранения. Это сверено с [Terms of Use, §7](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use). В реестре 23 223 URL, все ведут на `ru.wiktionary.org/wiki/` и согласованы с `title`; ни одного слова без provenance нет. Постатейное расследование сторонних прав не заменяется этим соответствием, U01.

[Kaikki raw page](https://kaikki.org/ruwiktionary/rawdata.html) подтверждает источник/регулярное обновление; [страница русского набора](https://kaikki.org/ruwiktionary/%D0%A0%D1%83%D1%81%D1%81%D0%BA%D0%B8%D0%B9/index.html) указывает dump 2026-11-01. [README FrequencyWords точной ревизии](https://github.com/hermitdave/FrequencyWords/blob/f8a65e6ddc17e0baa2e366a909986798d8dbe55b/README.md) явно разделяет MIT code и CC BY-SA 3.0 contents. Это подтверждает декларации источников, но не исключительные права на все исходные материалы.

### 4.2. Все 11 загадок

Проверены отдельные SHA source/approval/audit/index/контракта, а не только флаги manifest. `OWN_HINTS_OWNER_APPROVAL.json` (SHA `50ea8dd86af6a32a13165e21378273b9734557f6980cc5ccea518a7d04e3e128`) имеет `decision=approved`, дату 2026-11-16, все четыре подтверждения владельца `true`, точные SHA кампании и 39-текстового источника. `OWN_HINTS_MANUAL_AUDIT.json` (SHA `9f6bec48459ef460fab2d11f50745bf74db9c0322d4571f5092f1378daedaff1`) покрывает 39/39 точных `hint_sha256`, `bad=0`; его исторический `database_sha256` — свидетельство старой упаковки, не требование вернуть DB.

| Runtime ключи | Проверка |
|---|---|
| артист, банк, билет, весна, десерт, ковер, отель, проблема, работа, стадион, утро | Для **каждого**: runtime-текст равен исходному `hint`, SHA равен строке ручного аудита; PASS 11/11 |

Все 11 покрывают 6 T4-заданий. 28 остальных текстов не найдены как отдельный asset/словарь runtime; source `owned_hints` и index отсутствуют среди assets. README и THIRD_PARTY_NOTICES явно говорят «11 из 39», About — «11», ложного заявления о поставке всех 39 не обнаружено. Набор и тексты не перегенерировались через ИИ. Проверено машинное наличие заявления владельца, а не независимо наблюдённое сочинение каждого текста; U01.

### 4.3. CC BY-SA и CC0 после удаления SQLite

**PASS:** CC BY-SA не исчезла. В исходниках и APK есть атрибуция Викисловаря/Kaikki/FrequencyWords, URLs всех слов, описание преобразований, полный CC BY-SA 4.0 и раздельное обозначение CC0-загадок. Код MIT и данные CC BY-SA совместно поставляются как раздельные компоненты; CC0 не распространяется на словарь, индекс, графику, библиотеки или весь APK.

[CC BY-SA 4.0, §§3–4](https://creativecommons.org/licenses/by-sa/4.0/legalcode.en) предусматривает атрибуцию, обозначение изменений и ShareAlike, включая применимые права на базы. [CC BY-SA 3.0, §4](https://creativecommons.org/licenses/by-sa/3.0/legalcode) допускает текст лицензии **или URI**; отсутствие отдельного локального `CC-BY-SA-3.0.txt` при имеющемся URI само по себе не FAIL. Если передаётся переработка частотных данных, условия исходника сохраняются; применение 4.0 к соответствующей адаптации не стирает исходную атрибуцию 3.0.

[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/legalcode.en) не требует attribution как условия использования; canonical URL и решение владельца здесь всё равно сохранены. Отсутствие отдельного полного CC0-текста в assets само по себе не объявлено нарушением. Полный Apache-файл побайтно совпал с официальным текстом; CC BY-SA 4.0 совпал с canonical plaintext после нормализации пробелов.

## 5. Анализ юридических документов

| Файл / место | Фактический результат | Статус |
|---|---|---|
| `LICENSE:1–21` | Полный MIT-текст с обезличенным правообладателем; копия в APK совпадает. Обезличивание согласовано владельцем в правилах проекта, само по себе не доказательство нарушения | PASS наличия и поставки; предел прав автора U01 |
| `legal/THIRD_PARTY_NOTICES.txt:3–28,46–60` | Wiktionary/Kaikki/CC BY-SA 4.0, FrequencyWords 3.0, версия/изменения/статьи и раздельная лицензия индекса сохранены | PASS |
| Там же `:29–44` | 11 runtime загадок из 39, CC0/approval/manual audit и граница лицензии описаны явно | PASS |
| Там же, раздел «Графика интерфейса» | LEGAL-GRAPHICS-02: девять Google-векторов Apache-2.0, закреплённая редакция/изменения, 11 PNG CC0 с явным одобрением в пределах прав | PASS; L01 закрыт |
| `legal/LIBRARY_NOTICES.txt`, `legal/RUNTIME_LIBRARIES.json` | Фактические 75 координат, POM/file/notice SHA, все upstream embedded notices сохранены; повторная генерация в режиме `--check` совпала | PASS для новых APK/AAB; историческое расхождение L05 устранено пересборкой |
| `legal/Apache-2.0.txt`, `legal/CC-BY-SA-4.0.txt` | Полные тексты, совпадение с official plaintext, копии в APK | PASS |
| `legal/Protobuf-BSD-3-Clause.txt:1–32` | Историческое уведомление сохранено в source, **не** входит в текущий APK assets; текущие LIBRARY_NOTICES не заявляют runtime Protobuf | INFO / NOT_APPLICABLE текущему runtime. Не удалять доказательства прежних поставок |
| `docs/OWN_HINTS_OWNER_APPROVAL.json`, `OWN_HINTS_MANUAL_AUDIT.json`, `owned_hints.json`, `tools/owned_riddles_contract.json` | SHA и содержания взаимно согласованы; решение не переписано; исторический pending не принят за действующий запрет после отдельного approval | PASS технической цепочки |
| `docs/OWN_HINTS_MANUAL_AUDIT.md:5–24` | Старый заголовок «незавершённый протокол», но явные завершённые 39/39 и PASS; сверху historical banner | INFO: косметика исторического свидетельства, не текущий блокер |
| `docs/RELEASE_RIGHTS_DECISION.md`, `RIGHTS_RELEASE_AUDIT.md`, `OWN_HINTS_LICENSE_RELEASE_AUDIT.md`, `DEFINITIONS_POLICY.md`, `LOCAL_DEFINITIONS.md`, `SQLITE_REPRODUCIBILITY_AUDIT.md`, прежние short15 отчёты | Упоминания БД, неподтверждённых 39, debug release и прежнего applicationId находятся в исторически помеченных документах | NOT_APPLICABLE как описание текущей сборки; сохранять историю, не править её для искусственного PASS |
| `app/src/main/res/values/strings.xml:31–38`; `app/src/main/kotlin/com/example/sostavslova/ui/screens/Screens.kt:120–223` | About корректно указывает источники, CC BY-SA, 11 CC0-загадок, MIT и отсутствие сторонних толкований. Нет SQLite/Protobuf-обещаний. Полные notices существуют в assets, отдельного UI для их чтения нет | PASS правдивости этих строк; доступ к полной информации улучшить вместе с L04 |
| `PRIVACY_POLICY*` | В tracked-дереве такого документа и эквивалентной политики для игрока не найдено; в About нет соответствующего раздела | FAIL полноты документации, L04 |
| `docs/PRIVACY_GIT_PUBLICATION.md`, `PRIVACY_PUBLICATION_AUDIT.md`, `tools/privacy_publication_policy.json` | Описывают публикацию Git/удаление персональных маркеров автора; не объясняют игроку локальные игровые данные | PASS своего узкого назначения; **не заменяют** privacy policy |
| `README.md:61`, `docs/PROJECT_STATUS.md:41–42`, `docs/OWN_HINTS_TOOLCHAIN.md:51–52` | В действующих документах остаётся открытый вопрос runtime перепакованного Protobuf, хотя текущий граф и APK его не содержат | FAIL актуальности, LOW L07 |
| `docs/DATASTORE_JSON_MIGRATION.md:3,24–26` | Подготовительный снимок всё ещё говорит об отсутствии SDK/Gradle и непересобранном реестре; в этом окружении проверки уже выполняются, реестр актуален. Android upgrade всё ещё не принят автоматически | FAIL актуальности **как текущего статуса**, LOW L07; оформить датированное дополнение, не стирать исходное свидетельство |
| `metadata.json:5–6` | Заявляет `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`; такой runtime-функции нет | FAIL достоверности сторонней metadata, LOW L08; это не metadata F-Droid |

Прочитанный генератор `tools/build_library_notices.py` сохраняет текст всех найденных embedded LICENSE/NOTICE из JAR/AAR и их SHA; неподдерживаемые POM-лицензии приводят к отказу. Удаление четырёх Preferences/Protobuf-компонентов не удалило notices оставшихся 75. В текущих APK `LIBRARY_NOTICES`/`RUNTIME_LIBRARIES` совпадают с `legal/` полностью. Проверка не делает POM исчерпывающим доказательством всей внутренней истории upstream native-кода, U02.

## 6. Проверка F-Droid Inclusion Policy

Официальные требования прочитаны онлайн **2026-11-18**: [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/), [Quick Start](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [Anti-Features](https://f-droid.org/docs/Anti-Features/), [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/). Политика допускает свободные prebuilts из доверенных Maven и официальные SDK; запрет на любые AAR/JAR вообще из неё не следует. У ресурсов должно быть действительное правовое основание.

| Проверяемое свойство | Статус | Доказательство / предел |
|---|---|---|
| 1. Свободная лицензия исходного кода | PASS | MIT в корне и APK, §3 |
| 2. Публичный актуальный source | PASS доступности | Публичный GitHub commit; сборка проверяется отдельно в §2.2 |
| 3. Нет несвободных runtime libs | PASS для разрешённых Maven-компонентов | 75 POM, фактические AAR/JAR; не включает графику L01 |
| 4. Нет неподтверждённых executable prebuilt | PASS проверенной поставки | Wrapper официальный SHA; native 8/8 до AAR; нет собственного SDK/blob/JAR в assets |
| 5. Права поставляемых ресурсов | PASS проверенной графической поставки | 9 Apache-2.0 векторов, 11 PNG с утверждённой CC0, библиотечные PNG прослежены; исходные оговорки о любых третьих правах сохраняются |
| 6. Tracking / 7. рекламные SDK | PASS проверенного поведения | Граф, код, DEX/манифест; нет INTERNET/телеметрии. Локальный PlayTimeTracker пишет время игры, не отправляет его |
| 8. Google Play Services | PASS отсутствия зависимости | Нет GMS/Firebase в runtime/DEX; Google Maven и Guava не являются GMS |
| 9. Обязательные несвободные сервисы | PASS отсутствия | Игра автономна; host-only optional AI tools не используются обычной APK-сборкой |
| 10. Описание соответствует возможностям | PASS README/About; FAIL metadata.json | short15 проверена; AI Studio metadata L08 |
| 11. Android permissions | PASS | `aapt2 dump xmltree/badging`: `uses-permission`, `uses-permission-sdk-23` отсутствуют; allowBackup=false; `android.permission.DUMP` у ProfileInstallReceiver — ограничение доступа к receiver, **не** запрошенное приложением разрешение |
| 12. Privacy policy для игрока | FAIL документации | L04. По четырём прочитанным документам не заявляется универсальное отдельное требование privacy policy для любой офлайн-игры |
| 13. Скрытая загрузка executable code | PASS отсутствия обнаруженного механизма | Нет app downloader/HTTP/DexClassLoader/самообновления; assets не содержат исполняемых add-on |
| 14. Прозрачность сборки | PASS локальной инструкции; BLOCKED готового recipe | Две Gradle-модели, Python stdlib, tracked assets; L02 |
| 15. Anti-Features | См. §7 | Ни одна не присвоена автоматически |
| Релизные tags / upstream descriptions | FAIL готовности | На HEAD нет tag; `fastlane/metadata`, fdroid recipe отсутствуют; root metadata.json их не заменяет |
| Срок активной разработки | UNKNOWN | L03; Android-код впервые виден 2026-11-04 |
| Приёмка в официальном buildserver | BLOCKED | Локальная сборка не равна `fdroid build` и решению сопровождающих |

Связь исходников с бинарником и проверка копирования подписи для выбранной схемы developer-signed должны быть доказаны по [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/). Две сборки с одинаковыми данными не доказывают воспроизводимость всего APK. Отдельная подпись F-Droid была бы другой стратегией; без решения владельца она не подменяет уже выбранную.

## 7. Потенциальные Anti-Features

| Категория | Статус применимости | Основание |
|---|---|---|
| Ads | NOT_APPLICABLE | Рекламы/SDK нет в проверенном runtime |
| Tracking | NOT_APPLICABLE | Игровое время локальное, внешней отчётности не обнаружено |
| Non-Free Dependencies / Addons | NOT_APPLICABLE | Нет зависимости от проприетарного приложения и продвижения add-on |
| Non-Free Network Services / Tethered Network Services | NOT_APPLICABLE | Игра не требует сервера; optional tools не runtime |
| Non-Free Assets | NOT_APPLICABLE по проверенному составу | L01 устранён: свободные лицензии и происхождение текущей графики установлены; это не гарантия отсутствия любых неизвестных прав |
| No Source Since | NOT_APPLICABLE | Проверенный source публичен |
| Disabled Algorithm | NOT_APPLICABLE для unsigned кандидата | Production-подписи ещё нет, алгоритм будущей подписи не оценён |
| Known Vulnerability | UNKNOWN, U04 | `fdroidserver` scanner/актуальный CVE-анализ не запускались; отсутствие аналитических SDK не доказывает отсутствие уязвимостей |

Определения и окончательная маркировка — по [официальному списку Anti-Features](https://f-droid.org/docs/Anti-Features/). Установленных оснований ставить Tracking/Ads из-за названий **build-only** `analytics-library`/HTTP/Protobuf не найдено.

## 8. Найденные несоответствия

Серьёзность и статус независимы: UNKNOWN описывает недоказанный факт, CRITICAL — последствие отсутствия необходимого основания при публикации. В таблице восемь отдельных проблем; повторные упоминания не считаются новыми.

| ID | Серьёзность / статус | Точный путь / место | Суть и доказательство | Рекомендуемое исправление | Нужно решение владельца |
|---|---|---|---|---|---|
| **L01** | **PASS — устранён; исходная серьёзность CRITICAL** | Восемь `app/src/main/res/drawable/ic_*.xml`, сохранённый menu; 11 PNG в raster-реестре; `legal/THIRD_PARTY_NOTICES.txt` | Проверенные ресурсы Google с immutable source/SHA; raster CC0 явно утверждена владельцем; 9/9 source conversion и PNG bytes verified, новые APK/AAB без JPG; §3.6 и LEGAL-GRAPHICS-02 | Выполнено; исторические SHA/UNKNOWN сохранены, исключительные права не заявляются | Решение о raster CC0 **получено**; публикация не разрешалась |
| **L02** | **HIGH / BLOCKED** | `docs/RELEASE_SIGNING.md:14–23,55–100`; отсутствующие `fastlane/metadata/...`, `metadata/io.github.volmir.wordscombination.yml` или эквивалентный рецепт fdroiddata; `app/build.gradle.kts:38–79` | Выбрана developer-signed reproducible схема, но ключ не настроен, только unsigned APK, нет подписанного эталона, fingerprint, release tag и проверенного F-Droid recipe. Сборка локально успешна, `fdroid build`/signature-copy comparison не выполнены | Отдельно подготовить upstream metadata/recipe с HEAD/tag, public binary URL и `AllowedAPKSigningKeys`; владелец настраивает свой ключ; выполнить независимую пересборку и сравнение подписанного эталона. До этого не заявлять reproducible/готовность | **Да** для ключа, выпуска и публикации; рецепт/доказательства можно готовить без публикации |
| **L03** | **HIGH / UNKNOWN** | История `app/build.gradle.kts`, `core/build.gradle.kts`: первый Android commit `5e2aa6a595a1c64721b01e1a363cbbe5bb2f40ee` от 2026-11-04; будущая metadata F-Droid | До даты аудита видны 14 дней Android-разработки. История проекта с 2020 года относится к Vue/web-версии (`ca44270`: `package.json`, `src`, `public`), поэтому из одного возраста Git-репозитория нельзя установить выполнение условия о трёх месяцах активной разработки. Доказательства пользовательской базы/другого исключения не представлены | Подготовить проверяемые сведения о преемственности/разработке и пользователях; согласовать применимость исключения с сопровождающими либо дождаться нужного срока. Не выдавать тестовый телефон за доказанную пользовательскую базу | **Да**, сведения владельца и решение сопровождающих; см. [Inclusion Policy, Quality Control](https://f-droid.org/en/docs/Inclusion_Policy/) |
| **L04** | **MEDIUM / FAIL** | Отсутствует `PRIVACY_POLICY.md` или эквивалент; `app/src/main/kotlin/com/example/sostavslova/ui/screens/Screens.kt:120–223`, `app/src/main/res/values/strings.xml:31–38` | Игроку не дана политика локальных данных. Существующие `PRIVACY_*` защищают автора при публикации Git. Код хранит найденные слова, прогресс, подсказки/время и сохраняет старые `.preferences_pb`; `allowBackup=false` и отсутствие INTERNET проверены, но не описаны для пользователя | Отдельная текстовая политика: что хранится, где, отсутствие передачи/аналитики, backup, сохранённые legacy-файлы и способы удаления средствами ОС; сделать доступной в описании/приложении. Не обещать отсутствующий in-app reset или export | **Да** для публикационного текста/контакта; сам контакт не выдумывать. Это недостаток документации, не доказанное нарушение GDPR и не автоматически universal F-Droid blocker |
| **L05** | **PASS — устранён; исходная серьёзность MEDIUM** | `app/build/outputs/bundle/release/app-release.aab` | Исторический AAB с SHA `3af650df…ecd0f` заменён новой сборкой в LEGAL-GRAPHICS-02. Новые notices совпадают с legal/, resource/graphics audit PASS; точный новый SHA в отчёте и packages registry | Пересборка и проверка выполнены; старый снимок зафиксирован как исторический, не кандидат публикации | Нет для сборки; да для публикации |
| **L06** | **MEDIUM / FAIL** | `tools/audit_public_package.py:44–56`, вызов release `--profile release` | Фактически падает на штатных `assets/dexopt/baseline.prof`, `baseline.profm`; debug проходит, release не покрыт действующим whitelist. Это profiles, сгенерированные AGP, а не обнаруженная сторонняя DB/исполняемый add-on | Отдельно научить аудит проверять разрешённые AGP baseline profiles по явному контракту и источнику, сохранив строгую проверку остальных assets; добавить содержательный release regression case. Не отключать whitelist целиком | Нет |
| **L07** | **LOW / FAIL** | `README.md:61`; `docs/PROJECT_STATUS.md:41–42`; `docs/OWN_HINTS_TOOLCHAIN.md:51–52`; `docs/DATASTORE_JSON_MIGRATION.md:3,24–26` | Действующие обзоры сохраняют открытый runtime-Protobuf вопрос и подготовительный статус отсутствующей среды/старых notices. Фактически текущий graph/APK без Protobuf и реестр уже проходит gate; реальный upgrade при этом не доказан | Датированно актуализировать overview/добавление к migration report; разделить текущий APK, старый AAB и незавершённую Android QA. Исторические audits/approval не переписывать | Нет |
| **L08** | **LOW / FAIL** | `metadata.json:5–6` | `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API` противоречит автономному Android runtime; файла нет в APK и это не F-Droid metadata | Отдельно исправить/пометить исторический AI Studio descriptor; не переносить эту capability в описание каталога | Нет |

## 9. Критические блокеры публикации

**L01 устранён в LEGAL-GRAPHICS-02 (PASS):** восемь замен и сохранённый menu — проверенные Google / Apache-2.0; 11 PNG получили явное CC0-одобрение владельца в пределах его прав; в текущих APK/AAB проверены все растры и исключён JPG. Утверждение об исключительных правах не делается. Подробные свидетельства сохранены в §3.6 и отдельном отчёте.

**Подготовка выбранной поставки L02:** нет developer-signed эталона и доказанной воспроизводимости по recipe. Это блокер выбранной схемы релиза, а не утверждение, что F-Droid вообще не может подписывать приложения самостоятельно.

**Приёмочный риск L03:** соответствие актуальному условию срока разработки/исключению не подтверждено. Оно не решается успешной сборкой или датой создания веб-репозитория. Владелец должен представить сведения, сопровождающие — оценить их.

Нет оснований возвращать в список текущих блокеров удалённые определения SQLite, «неутверждённость всех 39» или несуществующий Protobuf в **новом APK**. Исторический AAB L05 пересобран и проверен отдельно; это не закрывает вопросы подписи или F-Droid recipe.

## 10. Рекомендуемые исправления

Отдельные задачи, без исполнения в рамках аудита:

1. **LEGAL-GRAPHICS-02 — выполнено:** восемь векторов заменены, девять Google-атрибуций внесены в notices/About, одобрение 11 PNG получено, JPG удалён после доказательства неиспользования; офлайн-gate и проверки APK/AAB добавлены.
2. **FDROID-PACKAGE/REPRO:** подготовить upstream metadata и рецепт, закрепить JDK/SDK/Gradle, exact release commit/tag, настроить ключ владельцем и подтвердить воспроизведение APK. Не публиковать автоматически. Включить в пакет пояснение L03.
3. **PRIVACY-DOC:** согласовать честную краткую политику и способ её прочтения. Можно совместить с пользовательским доступом к уже имеющимся полным notices; отсутствие отдельного экрана notices само по себе не объявлено нарушением Apache.
4. **ARTIFACT-AUDIT:** устранить L06, затем повторно проверить release APK и при необходимости новый AAB. Старый AAB сохранить как historical evidence, исключив из набора кандидатов.
5. **DOC-CURRENT:** обновить только действующие обзоры и AI Studio descriptor по L07–L08. Approval, ручной audit, старые юридические свидетельства оставить неизменными.

Дополнительное укрепление воспроизводимости: в `gradle/wrapper/gradle-wrapper.properties` нет `distributionSha256Sum`, нет dependency lock/verification metadata. Это не установленные нарушения Inclusion Policy: wrapper проверен по официальному SHA, runtime gate уже фиксирует SHA файлов/POM. Добавление checksum/проверки toolchain следует отдельной задачей, без произвольной смены версий. Официальный SHA Gradle 9.3.1 bin: `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06` ([первичный реестр](https://gradle.org/release-checksums/)).

## 11. Риски, которые невозможно проверить автоматически

| ID | Статус | Граница проверки / что требуется |
|---|---|---|
| **U01** | UNKNOWN | Независимая подлинность заявлений о сочинении 39 текстов, условия применённого исторического AI-инструмента, отсутствие чужой охраняемой формулировки, исключительные права на материалы исходных словарей/субтитров и непрерывная правоспособность всех авторов. Exact approval и все ссылки/локальные строки проверены, но не права каждой внешней статьи. В runtime нет её definitions/examples; гипотетическое нарушение не объявляется доказанным. При конкретной претензии — адресное расследование владельцем/специалистом |
| **U02** | UNKNOWN | Полная внутренняя source-to-binary цепочка upstream Maven/NDK/JDK и точные дополнительные лицензии всех вложенных частей инструментов: POM declarations/file hashes/native bytes проверены, каждый внешний toolchain из исходников заново не собран; BSD-style kXML не получил выдуманный SPDX. Для F-Droid при использовании доверенных свободных Maven это не автоматически блокирующее требование пересобирать каждый AAR. Неизвестных `.so` в APK нет |
| **U03** | UNKNOWN | Фактическое обновление на штатном подключённом смартфоне с реальным прогрессом и сохранением legacy-файлов в этом юридическом аудите не проводилось. Код миграции просмотрен; сброс/установка не выполнялись. Не ставить Android QA PASS на основании лицензий или JVM-проверок |
| **U04** | UNKNOWN | Актуальные CVE/результат fdroid scanner и окончательная anti-feature маркировка: `fdroidserver` отсутствует, security scan не запускался. Это не установленная уязвимость |

Отдельная воспроизводимая сборка на официальном buildserver и сравнение с подписанным эталоном относятся к BLOCKED L02, не к дополнительному скрытому PASS. Не проверены глобальная уникальность бренда/товарные знаки во всех юрисдикциях и решение сопровождающих о качестве/поддержке: этот аудит не выдаёт гарантию отсутствия любых возможных прав третьих лиц.

## 12. Итоговая оценка готовности к публикации

| Направление | Итог |
|---|---|
| Лицензии разрешённого runtime/сохранность notices в текущем APK | PASS в проверенной области |
| CC0 11/11, approval/audit 39/39 и точность текстов | PASS технического подтверждения решения владельца |
| CC BY-SA / provenance 20 120 слов и 23 223 ссылок | PASS целостности, атрибуции и цепочки до локального дампа; предел внешних прав U01 |
| Текущий source/APK без прежних SQLite и Protobuf | PASS; host-only Protobuf и системный Android SQLite не смешиваются с bundled runtime |
| Графика | **PASS / L01 устранён**: девять Google Apache-2.0, 11 утверждённых CC0 PNG, библиотечные растры Apache-2.0, нет удалённого JPG в новых APK/AAB |
| F-Droid developer-signed выпуск / возраст или исключение | BLOCKED L02 / UNKNOWN L03 |
| Готовность всего приложения к публикации | **BLOCKED** |

Сводка **открытых** несоответствий §8 после LEGAL-GRAPHICS-02: **CRITICAL — 0; HIGH — 2; MEDIUM — 2; LOW — 2**. L01 (исходно CRITICAL) и L05 (исходно MEDIUM) сохранены в реестре со статусом PASS — устранены. INFO: корректно сохранённые исторические сведения.

**UNKNOWN — 5 уникальных областей:** L03 и U01–U04; из них одна — открытая проблема §8. Прежний UNKNOWN графики L01 закрыт указанными свидетельствами. Общее неизвестное наличие любых третьих прав не подменяется гарантией отсутствия таких прав.

**Общий статус F-Droid: BLOCKED по отдельным вопросам выпуска; L01 устранён.** Кампания, словари, загадки, DataStore и подпись не изменены. Изменения LEGAL-GRAPHICS-02: восемь векторов, один разрешённый неиспользуемый JPG, notices/About, адресные tests/gate и документы — полный список в отдельном отчёте. Git-история/ветка не менялись; commit/push/publish не выполнялись.


## 13. Дополнение RELEASE-AUDIT-01 — 2026-11-18

**L06: исправлен в коде проверки APK, но итоговый прогон на реальном подписанном release APK ещё требуется.** Исходная запись L06 в §8 и результаты ранее проверенных бинарных артефактов сохранены как исторические факты.

- `tools/audit_public_package.py`: для `--profile release` разрешены **только** штатные `assets/dexopt/baseline.prof` (формат Android ART V010_P) и `assets/dexopt/baseline.profm` (форматы METADATA_V001_N и METADATA_V002). Для них проверяются заголовки, размеры контейнера и распакованных данных, корректность zlib и отсутствие добавочных байтов; это проверка структуры, а не независимое доказательство происхождения.
- Все прочие APK assets по-прежнему обязаны точно совпадать с разрешёнными входными файлами и содержимым; неожиданные пути, подмена данных и повторяющиеся ZIP-записи отвергаются. Debug-профиль не получает release-исключения.
- `tools/tests/test_public_package_audit.py`: добавлены синтетические адресные тесты на допустимые ART profiles, повреждения, неизвестные пути и повторяющиеся записи. `python3 -m unittest tools.tests.test_public_package_audit -q`: **10/10 PASS**. `python3 -m unittest discover tools/tests -q`: **498/498 PASS**.
- Android Gradle-сборка в среде этой правки **не проверена**: Gradle 9.3.1 отсутствует в локальном кэше, загрузка из `services.gradle.org` заблокирована сетью. Реальная release APK и проверка через buildserver F-Droid здесь не выполнялись.

Проверка на машине владельца с уже собранным release APK:

```bash
python3 tools/audit_public_package.py \\
  --apk app/build/outputs/apk/release/app-release.apk \\
  --profile release \\
  --output /tmp/words-release-artifact-audit.json
```

Источники контракта: [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles) и [AndroidX ProfileTranscoder](https://android.googlesource.com/platform/frameworks/support/+/d1aba6064d4c1aedfd10ec75fd8d181fcb37c947/profileinstaller/profileinstaller/src/main/java/androidx/profileinstaller/ProfileTranscoder.java).

**Статус F-Droid остаётся BLOCKED:** этот фикс L06 не подтверждает developer-signed reproducibility, не заменяет F-Droid buildserver, не закрывает остальные условия публикации. Подписанные релизы 1.0.0/1.0.1, исходные словари, лицензионные notices, ключи и сертификат не изменяются.
