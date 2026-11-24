# FDROID-FIX-110 — диагностика публикации 1.1.0 в F-Droid

Дата проверки: 2026-11-24. **BLOCKED на этапе 03 задания.**
Подтверждённого коммита, из которого воспроизводится опубликованный APK,
не установлено. Чистые сборки тега и revision из APK успешно собираются,
но обе проваливают проверку перенесённой оригинальной подписи.
Исправление конфигурации, сохраняющее опубликованные байты и устраняющее
эти расхождения, не подтверждено. Оснований объявлять reproducibility PASS нет.

В соответствии с требованием выполнять этапы последовательно изменение
YAML, описания и чек-листа MR остановлено до устранения этого блокера.
Тег, APK, приложение, данные, лицензии и исторические документы не изменены.

## 1. Репозитории и состояние заявки

- Приложение: `/var/www/words-combination`, чистая `main` до записи отчёта;
  HEAD `76a2760375c8d65ff21fb7d41a585f42b1243e03`, совпадал с `origin/main`.
- fdroiddata: существующий `/var/www/fdroiddata-words-combination-mr58000`;
  чистая `new-app-wordscombination`, после `git fetch origin new-app-wordscombination`
  расхождение с удалённой веткой `0 / 0`.
- `origin`: `https://gitlab.com/volmir561/fdroiddata.git`;
  `upstream`: `https://gitlab.com/fdroid/fdroiddata.git`.
- HEAD **fdroiddata**: `3819a15c4685653c6abc902a1f75378d99279c55`.
  Это не SHA исходников приложения.
- [MR !58000](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000)
  открыт, source branch совпадает. API возвращает
  `blocking_discussions_resolved=true`; прочитаны также закрытые обсуждения.
- Последний pipeline при проверке:
  [3110282118](https://gitlab.com/volmir561/fdroiddata/-/pipelines/3110282118),
  `failed`. Более новых pipeline в ветке не было.

fdroiddata повторно не клонировался. Для проверки приложения созданы два
отдельных полных клона в `/tmp/fdroid-fix-110/`, без локальных ключей подписи,
`keystore.properties`, build outputs и кэшей исходного рабочего дерева.
Разрушительные Git-команды, force-push, перенос тега и публикация не выполнялись.

## 2. Подтверждение опубликованного APK

Скачан непосредственно `WordsCombination-v1.1.0.apk` из GitHub Releases
по URL выпуска 1.1.0, указанному в задании и поле `Binaries` заявки.
GitHub API подтверждает опубликованный релиз, `draft=false`, `prerelease=false`.
Сверка выполнена по байтам APK, независимо от текста релиза и MR.

| Проверка | Результат |
|---|---|
| Размер | 2 699 287 байт |
| Application ID | `io.github.volmir.wordscombination` |
| versionName / versionCode | `1.1.0` / `6` |
| SHA-256 APK | `6f8970b710b5fdaf47f97500315b8a4ac3cf7e919ced40d5e15f83355e6da452` |
| SHA-256 сертификата | `a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353` |
| Подпись | `apksigner verify --verbose --print-certs`: PASS, APK Signature Scheme v2, один signer |
| Удалённый аннотированный тег `1.1.0`, peeled commit | `7e9241f883870ebc96f5f9839bb000acf8c62bbe` |
| `META-INF/version-control-info.textproto` в APK | revision `720e6b092ec6c7ec6763b210ec2a77c6aa78066f`, `local_root_path: "$PROJECT_DIR"` |

Тег проверен через `git ls-remote origin 'refs/tags/1.1.0*'` и локальный
`git rev-parse '1.1.0^{commit}'`. `apkanalyzer apk summary`, `sha256sum`
и `apksigner` вернули ожидаемые значения.

В APK перечислены только signing-block IDs `0x7109871a` (v2 signature)
и `0x42726577` (padding). **DependencyInfoBlock отсутствует.** В исходниках
тега `dependenciesInfo.includeInApk` и `includeInBundle` равны `false`.
[Замечание linsui](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000#note_4266186172)
относилось к прежнему APK: провалившийся `check apk` для 1.0.2 содержал
`Found extra signing block 'Dependency metadata'`. Ответ автора фиксирует
исправление в 1.0.3; APK 1.1.0 независимо подтверждает отсутствие блока.

## 3. Проверка исходников и воспроизводимости

SHA в описании MR (`ba5987caf45ac3c2d4ae703b5ff91c4077066b41`) и YAML
(`517d9f74a8b09337c26c38e8e9aa078726973cbd`) относятся к последующим
изменениям подготовки F-Droid. Ни один из них не является коммитом тега.

Коммит тега отличается от `720e6b0…` не только тестами и документацией:
в `app/src/main/kotlin/com/example/sostavslova/ui/levels/LevelsScreen.kt`
изменён modifier текста достижений с `align(Alignment.End)` на `fillMaxWidth()`.
Поэтому revision внутри APK нельзя автоматически объявить его точным исходником.

В обоих новых клонах выполнена команда:

```bash
bash ./gradlew --offline --no-daemon --no-build-cache --console=plain \
  --init-script /tmp/fdroid-fix-110/print-jvm.gradle :app:assembleRelease
```

Init script только печатает `System.getProperty("java.version")` и
`System.getProperty("java.home")` из процесса Gradle. В обоих случаях
подтверждены JDK **21.0.13**, Temurin, и путь локального JDK 21.
Release-переменные подписи удалены из окружения дочерних процессов.
Версии инструментов сохранены: Gradle **9.3.1**, AGP **9.1.1**,
Kotlin **2.2.10**, SDK platform **android-36**, Build Tools **36.0.0**.

| Чистый коммит | Gradle | SHA-256 неподписанного APK | `apksigcopier compare --unsigned` с опубликованным APK |
|---|---|---|---|
| `7e9241f883870ebc96f5f9839bb000acf8c62bbe` | PASS, 2m 34s | `c1be8e6b68d6f07d3e7ca31735da95586dd124d9d46e7c84a53a76ab053da79a` | FAIL, exit 1, `DOES NOT VERIFY`, `CHUNKED_SHA512 digest mismatch` |
| `720e6b092ec6c7ec6763b210ec2a77c6aa78066f` | PASS, 2m 53s | `84186ddbb71712944eeb98d055486b7b8660f25c162ef5238bffa1ace5f0296c` | FAIL, exit 1, та же ошибка целостности v2 |

Это проверка копирования подписи и её криптографической действительности,
а не сравнение SHA подписанного APK с неподписанным. Приватный ключ не использовался.

Различия с опубликованным APK:

- Обе сборки: содержимое `classes.dex`, `assets/dexopt/baseline.prof`,
  `assets/dexopt/baseline.profm`; вместо `META-INF/services/xf1`
  создаётся `META-INF/services/wf1`.
- Сборка тега дополнительно отличается revision в
  `META-INF/version-control-info.textproto`.
- В сборке `720e6b0…` VCS-файл уже полностью совпадает, но подпись всё равно
  не проходит проверку. Одной замены поля `commit` недостаточно.
- `AndroidManifest.xml`, `resources.arsc`, словарные assets и остальные записи
  ZIP совпадают по распакованным байтам. Для общих записей сборки тега
  совпадают ZIP timestamp, compression method, flags, attributes, extra и comment;
  порядок записей совпадает после исключения двух имён service-файла.
  Все timestamp равны `1981-01-01 01:01:02`, методы сжатия — Stored/Deflate.

SHA-256 `classes.dex` опубликованного APK:
`6b5733d4756f56151510cfde762c44aeddbb1256e6fd00c956cab4cac72c028f`.
Для чистого тега:
`cc64f1f3c5850980347f4a0342aff2e193c230b8857053a17cc1893198822cb3`;
для чистого `720e6b0…`:
`b705367daa5347a08a3e401a66738845ab7c59a3965a3ae46b1313786391ebd3`.

**Положительный контроль:** новая сборка `720e6b0…` успешно прошла
`apksigcopier compare --unsigned` с прежним чистым подписанным тестовым APK
из `/tmp/words-stab08-auto-20261124/clean-signed.apk`
(SHA-256 `a7a2a71498a29b25a913f75823c94b52d2c9685792eef1a7d0f5ce183ef3a36b`).
Все записи APK совпали; проверка перенесённой подписи вернула exit 0.
Новый неподписанный APK также совпал по полному SHA с прежним чистым
артефактом, указанным в дополнении STAB-08 к
[RELEASE_1_1_TEST_EVIDENCE.md](RELEASE_1_1_TEST_EVIDENCE.md).
Таким образом, чистый тестовый APK воспроизводится в другом абсолютном
каталоге. Это не подтверждение воспроизводимости опубликованного APK.

Опубликованный APK по полному SHA совпадает с текущим локальным
`app/build/outputs/apk/release/app-release.apk`. Его локальные compiler outputs
отличаются от чистого тега в 17 class-файлах ещё до R8; например,
`javap -c -p` показывает отличия в `StarsRowKt`, хотя соответствующий исходник
между двумя проверяемыми коммитами не менялся. R8 версии 9.1.31 в обоих
outputs одинаков, но mapping содержит 6315 классов у рабочего артефакта
против 6312 у чистого тега. Смешанное или инкрементальное состояние сборки
является гипотезой, а не доказанной первопричиной.

Штатный `tools/prepare_verified_release.py` требует существующий тег,
строит два независимых клона и экспортирует результат только после PASS.
Текст GitHub Release заявляет локальный PASS, но проверенный опубликованный
APK не совпадает ни с чистым тегом, ни с ранее проверенным чистым APK
`720e6b0…`. Фактический воспроизводимый рецепт опубликованных байтов
не установлен; текстовое заявление не заменяет проверку.

## 4. Диагностика CI и обсуждений

Для последнего pipeline скачаны журналы всех выполненных jobs и артефакты
сборки/rewritemeta. Ошибка подписи подтверждена также сравнением настоящего
CI APK из `tmp/io.github.volmir.wordscombination_6.apk` с GitHub APK.

| Job последнего pipeline | Результат и причина | Необходимое исправление после снятия блокера |
|---|---|---|
| [fdroid rewritemeta, 18334482861](https://gitlab.com/volmir561/fdroiddata/-/jobs/18334482861) | FAIL: `These files need rewritemeta`; неканонические комментарии, расположение полей, перенос `Binaries`, quoting | Канонический вывод `fdroid rewritemeta` |
| [checkupdates, 18334482858](https://gitlab.com/volmir561/fdroiddata/-/jobs/18334482858) | FAIL на `git diff --exit-code` после перезаписи форматирования. Тег 1.1.0 и версия 1.1.0 (6) успешно найдены | Канонический YAML, восстановить согласованный `Version` / `Tags`, убрать необязательный `UpdateCheckData`, затем повторить проверку |
| [fdroid build, 18334482857](https://gitlab.com/volmir561/fdroiddata/-/jobs/18334482857) | Gradle PASS; перенос подписи FAIL. Commit 517d9f7 не совпадает с release tag; отсутствует `assets/word_sources.jsonl`, отличаются DEX, профили, VCS revision и service-файл | Подтвердить воспроизводимый исходник; сохранять provenance через узкий `scanignore` |
| fdroid lint, 18334482860 | PASS | Повторить для исправленного рецепта |
| schema validation, 18334482863 | PASS | Повторить для исправленного рецепта |
| check source code, 18334482864 | PASS | Fastlane en-US / ru-RU найден |
| tools check scripts, 18334482862; git redirect, 18334482859 | PASS | Reports пусты |
| check apk, 18334482865 | SKIPPED из-за провала сборки | Не объявлять проверенным последним pipeline; DependencyInfoBlock отдельно проверен в APK |

Reports получены через
`GET /projects/88705714/jobs/<id>/artifacts?file_type=codequality`.
У `check source code` ровно две записи с `severity=info` о найденных
Fastlane-метаданных en-US и ru-RU; у tools и git redirect массивы пусты.
Предупреждений и ошибок в этих Reports нет.

Изучены также журналы всех провалившихся jobs более ранних pipeline ветки:

- 3110259343: `gradle: assembleRelease` породил
  `Task 'assembleAssembleReleaseRelease' not found`. Нужен `gradle: yes`.
- 3110236970: `Unrecognised build flag 'java'`; такого поля нет в схеме.
  Отдельные schema jobs ранее падали на `remote upstream already exists`.
  В последнем pipeline обе проблемы отсутствуют.
- 3110176713: отсутствующий provenance-файл ломал `syncDictionaryAssets`;
  3110075232 — валидацию входов `verifyDictionaryLicenses`;
  3110043209 и 3109985386 — чтение этого файла внутри проверки.
  Удаление файла из `scandelete` обязательно для исходников тега.
- 3110009425: scanner отверг gzip; 3109959336 и 3109925171 дополнительно
  отвергли четыре QA ZIP. В 3109925171 lint также отвергал `Categories: Games`;
  согласованное значение — `Word Game`.
- 3110200744 и 3110282118: Gradle уже проходит, но APK не воспроизводится.
- Исторический провал `check apk` для 1.0.2 действительно связан с
  DependencyInfoBlock; к проверенному APK 1.1.0 эта ошибка не относится.

Прочитаны замечания linsui об удалении `WebSite`, `output`, `UpdateCheckData`,
включении autoupdate и отсутствии DependencyInfoBlock. Позднейшего требования,
отменяющего эти решения, в обсуждениях нет.
[Успешный pipeline 3091327083](https://gitlab.com/volmir561/fdroiddata/-/pipelines/3091327083)
действительно соответствует fdroiddata commit
`8bbf5975b3fa8c4e55724df10791b2c5162c6a5e` с версией 1.0.4.
Его YAML получен локальным `git show` и сопоставлен с текущим.

## 5. Подтверждённые требования к дальнейшему рецепту

Это результаты чтения исходников и истории; правки этапов 04–08 не применялись.

В теге `verifyDictionaryLicenses` объявляет `data/word_sources.jsonl.gz`
обязательным входом и проверяет его SHA по `data/manifest.json`.
`syncDictionaryAssets` распаковывает файл в `assets/word_sources.jsonl`;
его 3 791 287 байт присутствуют в опубликованном APK.
Это UTF-8 JSONL происхождения **20 103** слов, CC-BY-SA-4.0.
Актуальный SHA-256 gzip, вычисленный по Git blob и совпадающий с manifest:
`bbb9392fb53d7652da2ecfb1965e0ac4140d1e73b713ed1ca62320dca6206d0d`.
Ранее согласованное точное исключение `scanignore` обосновано.
Старые 20 120 слов и SHA из рецепта 1.0.4 переносить нельзя.

Все четыре QA ZIP из старого рецепта действительно существуют в теге:
`gradle-report.zip`, `manual-witness.zip`, `remote-manual-witness.zip`,
`verification-logs.zip` в `docs/qa/pre04-17/`. Scanner указывал именно их;
в сборке они не используются. Широких исключений не требуется.

Нужны `AuthorName` из принятого рецепта, отсутствие `WebSite` и `output`,
актуальные `MaintainerNotes`, только одна сборка 1.1.0 (6),
`CurrentVersion: 1.1.0`, `CurrentVersionCode: 6`, `gradle: yes`,
`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`.
`Binaries` должен сохранить URL репозитория из задания и окончание шаблона
`releases/download/%v/WordsCombination-v%v.apk`,
а `AllowedAPKSigningKeys` — независимо проверенный отпечаток сертификата.
Необязательный `UpdateCheckData` ранее удалён по принятому замечанию.
Новый локальный запуск стандартного `checkupdates` без него не выполнялся:
переход к исправлению рецепта остановлен на этапе 03.

Экспорт `JAVA_HOME` в дочернем shell `prebuild` сам по себе не доказывает
выбор JVM следующим процессом Gradle. В CI предусмотрена отдельная установка
JDK 21 и `update-alternatives`, а в новых локальных сборках JVM проверена
из самого Gradle. Готовый рецепт для buildserver и его Java-конфигурация
не объявляются проверенными по результатам локального запуска.

Fastlane в теге содержит title, short/full descriptions, иконку,
три скриншота и `changelogs/6.txt` для en-US и ru-RU. Краткие описания:
67/62 символа, changelog: 417/454 символа без завершающего LF.
Оба описания уже отражают отсутствие учёта времени и актуальные функции;
устаревшее верхнеуровневое описание из YAML 1.0.4 переносить дословно нельзя.

## 6. Границы результата и продолжение

Изменены только этот отчёт, [PROJECT_STATUS.md](PROJECT_STATUS.md) и
[INDEX.md](INDEX.md). Исторические правовые документы сохранены.
Диагностические логи, скачанные artifacts, JSON GitLab API, сравнения ZIP
и compiler output находятся локально в `/tmp/fdroid-fix-110/`;
сырой CI trace с временными URL не включён в репозиторий.

Проверено: APK/сертификат/версия, два чистых release build, две отрицательные
проверки подписи и положительный контроль, CI jobs, Reports, обсуждения,
Fastlane, provenance hash и существование QA ZIP.

Проверки изменённых документов и локальный gate:

- `bash orchestrator/run.sh --check docs` — PASS: ссылки, UTF-8, индекс,
  три теста сверки спецификации с campaign.
- `python3 tools/check_privacy_publication.py --root . --require-generated`
  — PASS. Первый запуск общего gate обнаружил запрещённый политикой
  персональный маркер в новом отчёте; прямое написание имени и URL заменено
  ссылкой на согласованный рецепт и задание. Сама политика не менялась.
- Повторный `bash ./gradlew --offline :core:test :app:assembleDebug --console=plain`
  — PASS, `BUILD SUCCESSFUL in 8s`, 51 задача (6 выполнены, 45 up-to-date).
  `:core:test` — UP-TO-DATE; в XML 262 теста, 0 failures/errors/skipped.
- `git diff --check` — PASS.

Не проверено: исправленный YAML, новый CI, buildserver F-Droid,
стандартный checkupdates после исправления, автоматическое добавление
следующей версии и включение в каталог — этапы после блокера не выполнялись.
`diffoscope` не установлен; различия установлены по настоящим ZIP-записям,
`apksigcopier` и `javap`, без подмены APK. Политическое условие срока активной
разработки/исключения не подтверждено; соответствующий пункт MR не закрыт.

Для продолжения нужен подтверждённый рецепт, который из публичных исходников
создаёт именно APK с SHA `6f8970b7…` и проходит перенос подписи. Простая замена
SHA на тег или на revision APK этого не даёт. Если такой рецепт восстановить
нельзя, потребуется отдельное решение владельца о следующем выпуске из
чистого тега; замена текущего APK и перенос тега запрещены этим заданием.

Основание проверки: [F-Droid Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/),
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/),
[Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) и
[Submitting to F-Droid](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).
