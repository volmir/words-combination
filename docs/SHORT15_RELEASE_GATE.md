> **Историческое свидетельство до перехода на JSON (2026-11-17).**
> Описанные здесь прежний формат, команды и статусы не задают текущую сборку.
> Действующий контракт: [OWN_HINTS_TOOLCHAIN.md](OWN_HINTS_TOOLCHAIN.md). Исходные правовые выводы сохранены для прослеживаемости.

# SHORT15-04 — финальный релизный gate

Дата: 2026-11-15. Проверялась установленная кампания **short15-release-v1**:
15 уровней, 6 этапов `[3,2,3,2,3,2]`, 49 заданий/звёзд, финал 4/5 на уровне 15.
Работа выполнена в рабочем дереве без commit/push. HEAD на момент проверки —
`d25a60aa3e1ce897c2983d87740d9778db5d7241`.

**Итоговый статус: gate `SHORT15-04` — PASS; публикация —
`BLOCKED_FOR_PUBLIC_RELEASE`.**
Данные, лицензии включённых материалов, тесты и сборка проверены; собраны
debug-APK и технический pre-release APK. Публичная публикация не разрешена:
ручной аудит/права определений не подтверждены, release подписан debug-ключом,
ручной прогон на устройстве не выполнен (см. §10). Юридическая чистота
определений — следующий этап по решению владельца.

Gate `bash orchestrator/run.sh --check full && --check data && --check android &&
bash ./gradlew :app:assembleRelease` проходит целиком. Release-сборку разрешает
явный waiver владельца от 2026-11-15: `sostavslova.release.allowUnauditedDefinitions=true`
в `gradle.properties` → `tools/build_game_data.py --check-release --allow-unaudited-release`.
Waiver не подменяет аудит: `audit.passed` и `rights_review.passed` остаются
`false`, задача печатает предупреждение, ответ `"publishable": false`, а строгий
вызов без флага по-прежнему завершается кодом 2. `audit.passed=true` не
выставлялся, зависимость задачи в `app/build.gradle.kts` не снималась.

## 1. Идентификаторы и артефакты

| Объект | SHA-256 / значение |
|---|---|
| `data/campaign.json` | `eb4408731b453104bad3f05db4a69397d1e4d208f9193adcdd440fc5ecb1fadc` |
| `data/words.txt` | `3b8a1339df5485ae2c5493cec3b5297e84173b94e57cf253c3543a8c85efc997` |
| `data/definitions.db` | `6d0cd051b75babdb4435777f29e00419ff3ba7dbc8c0d9ae602cff7b18869d7f` |
| `data/manifest.json` | `41ab4ce18d344bb91b1296150d2cdb7d9938ef7d6496d4a0db114e7fa62a4a1e` |
| `build/reports/short15/package/manifest.json` | `09d4747c020d3d2ab9d2197979aa51a829d3653667175506bed568976a76bc29` |
| `build/reports/short15/package/quality_report.json` | `0087e9cd5980828f2cf7af05bdffb2a041bccb2380d363e8b488ade22449a640` |
| `build/reports/short15/package/validation_report.json` | `81ef280febd2fa3d5a629d37ce66ee48ea39b86ea19cac3eaf5288d90110637e` |
| `validated_campaign.json` (пакет и свежая проверка) | `eb4408731b453104bad3f05db4a69397d1e4d208f9193adcdd440fc5ecb1fadc` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`, 17 289 516 байт, `a9e05e42744e6f84a8c9242c95d4ce9baa391726085f7687f62b2e1d38ff1a95` |
| Release APK (технический pre-release, waiver владельца) | `app/build/outputs/apk/release/app-release.apk`, 6 803 203 байта, `8a28c77a7304ad31267762a2fbec183d7eecc143d41989ee4815ed6a5db1ae69` (SHA пересборки не воспроизводится, см. §7) |

`data/manifest.json` отличается от package-манифеста только добавленным
`campaign_source` (так его обновляет SHORT15-03); остальные поля совпадают.
Поэтому независимая проверка кампании запускалась с package-манифестом, к
которому привязан lexical snapshot, а `verifyDictionaryLicenses` — с текущим
`data/manifest.json`.

Свежий `validation_report.json` имеет SHA
`a6c034d1c3d5b341dad59de10dbb98f6b788c972ea6288dc3eb4c59d01de914c`, а не
package-хэш `81ef280f…`: после SHORT15-02 изменился `CampaignRules.kt`
(SHORT15-03). Итоговый `validated_campaign.json` при этом тот же —
`eb440873…1fadc`; manifest хранит package-отчёт, а свежая проверка текущим
кодом лежит в `build/reports/short15/final-gate/`.

## 2. Независимая проверка кампании

Свежий отчёт:
`build/reports/short15/final-gate/validation-installed/validation_report.json`
(`status=valid`, `installable=true`, `jvm_checked=true`, все три списка
нарушений пусты). Машиночитаемая сводка прямых пересчётов:
`build/reports/short15/final-gate/summary.json` (`status=PASS`).

| Проверка | Факт |
|---|---|
| Уровни | 15/15; номера 1…15 без пропусков |
| Этапы | 6: `[3,2,3,2,3,2]` |
| Задания/звёзды | 49/49; M/R: `1/1×3`, `2/2×2`, `3/3×3`, `4/3×2`, `5/4×5` |
| Исходники | 15 различных, все нормализованы; ровно 3 из исторического v1: `чемпионство`, `вегетарианец`, `аккумулятор` (профиль 3…5) |
| Типы | T1 — 15, T2 — 12, T3 — 9, T4 — 6 задач/11 загадок, T5 — 5, T6 — 1, T7 — 1; все T1–T7 присутствуют |
| Финал | уровень 15: M=5, R=4; 4/5 даёт финал, «Мастер» — только 49/49 |
| Достижимость | все задания имеют не меньше ответов, чем требуется; проверены все 41 ветка R; JVM: `all_answer_stars=49`, `finale_reached=true` |
| Jaccard | mean **6,5137997855%**, max **23,2638888889%**, 105 пар; лимиты профиля 7%/30% |
| Short-repeat | weighted adjacent **605**, только 3–4 буквы **537**; отчётная метрика, отдельного порога в short15-профиле нет |
| Недостающие буквы | явно: `й, ф, х, ш, щ, ъ, ы, ю`; покрытие 24/32, профиль не требует 32/32 |
| Финальный T3 | не сводится к одному ориентиру: проверка `t3_final_anchor_only` пройдена, несколько допустимых ответов длины ≥9 |

Порядок 15 исходников: `чемпионство, вегетарианец, аккумулятор,
благословение, благоразумие, безрассудство, журналистика, поздравление,
благодарность, пуэрториканец, подстрекатель, проблематика, складирование,
контрабандист, документалист`.

### 0 новых AI-решений

В `build/reports/short15/package/quality_report.json`:
`semantic.new_ai_decisions=0`, `semantic.network_requests=0`. Счётчики
семантических источников профиля и финального отчёта совпадают:
`ai=352`, `pending=14384`, `rules=14312`. Это перенесённые прежние метки, а не
новые решения SHORT15. Кандидат закреплён `tools/short15_candidate.json`;
сборка GEN-03 шла в режиме `pinned`, `search_stats.checks=0`, `attempts=1`.

## 3. Детерминированность построения

Повторены две независимые сборки с одними и теми же входами:

```sh
HINTS=.local/data01-oc-cache/runs/cf75f869bf83b072307937fe17b35e6f9cc3d914e96fd80bd7cf3994cc206b05/round-01/hints-current.jsonl
python3 tools/build_campaign.py \
  --profile short15-release-v1 \
  --profiles build/reports/short15/profile-cache/031ea23819f46c795ebb5ca8c4c0812e8bea43f28ae2b52f470d502ba65be7f1.json.gz \
  --proposal build/reports/short15/selection/level_words_proposal.json \
  --words data/words.txt --previous-levels data/levels.json \
  --manifest build/reports/short15/package/manifest.json \
  --database data/definitions.db --hints "$HINTS" \
  --output /tmp/opencode/short15-final-a
```

Повтор в `/tmp/opencode/short15-final-b` дал побайтово те же файлы:

| Файл | SHA-256 |
|---|---|
| `campaign.json` (компактный GEN-03) | `a5589978de43e6dc2ff81754daf51716be0a785be961d80e0c5b93ac519df024` |
| `campaign_report.json` | `57168e38308e7371415d878df0dd97ca7fa451b79b734fbc5f53cd1f4aa9407e` |
| `CAMPAIGN_REPORT.md` | `01ae3aa9e985bcf8aab2d1a72164882580438683de361788b4d9b25d5d39f75f` |

Валидация собранного `campaign.json` дала
`validated_campaign.json` с SHA `eb440873…1fadc`, совпадающим с установленной
кампанией и package-пакетом.

Замечание о воспроизводимости: текущий
`build/reports/level_profiles/hints.jvm.jsonl` имеет SHA `8a07dbb3…d2d1d4` и не
принимается привязкой proposal. Точный повтор возможен только с сохранённым
`hints-current.jsonl` из `.local` (SHA `0db4d56d…f7f4c3`). Этот вход не входит
в package/SHA256SUMS; без локального кэша побайтовый повтор из одного
репозитория не гарантирован.

## 4. Подсказки JVM/Python

- Полный JVM-аудит `:core:auditDefinitionHints` на текущем
  `definitions/hint_inputs.jsonl` (20 106 слов): **17 993** пригодные подсказки,
  включая `брудершафт` и `праймериз`. Без этих двух исключённых ролей — **17 991**,
  что совпадает с `hints_useful` пакета.
- Полное сравнение Python `hint_text.prepare` с JVM по всем 20 106 словам:
  **0 расхождений**, по 17 993 пригодным подсказкам.
- `validate_campaign.py` отдельно проверил T3/T4: `parity_deviations=[]`,
  `jvm_checked=true`. Для 11 загадок T4 все цели — существительные, частотный
  ранг 29–3022, подсказки 39–139 символов, ответ не раскрывается.
- T6: `professions`, n=5, локально 6 узнаваемых ответов, глобальный резерв 50.
  У `артистка` нет пригодной подсказки, но это необязательный шестой ответ;
  пяти обязательных подсказок достаточно.

## 5. Лицензии, происхождение и атрибуция

`./gradlew :app:verifyDictionaryLicenses` — успешно (словарь, уровни, кампания,
БД и manifest согласованы).

| Материал | Проверка |
|---|---|
| Словарь | `source.license=CC-BY-SA-4.0`, Kaikki/Wiktextract, URL и SHA входного дампа в manifest |
| `word_sources.jsonl.gz` | 20 106 строк; у каждой есть `sources`, все URL — `https://ru.wiktionary.org/wiki/…`; пропусков 0 |
| `levels.json` | MIT, исторический v1-снимок; не обслуживает карту short15 |
| Определения | 31 351 строка, все `origin=wiktionary`, у всех непустой `source` с URL статьи; собственных определений 0 |
| Уведомления | `THIRD_PARTY_NOTICES.txt`, `Apache-2.0.txt`, `LIBRARY_NOTICES.txt`, `RUNTIME_LIBRARIES.json` включены в APK; `THIRD_PARTY_NOTICES.txt` побайтово совпадает с `legal/` |
| Частотный сигнал | FrequencyWords/OpenSubtitles 2018, CC BY-SA 3.0, закреплённая ревизия и SHA в manifest; сырые субтитры в APK не входят |

Ограничения прав:

- `manifest.definitions.rights_policy = wiktionary-provisional-v1`;
- `audit.passed=false`, `rights_review.passed=false`,
  `rights_review.replacement_required=true`,
  `rights_review.status=unverified-provisional`;
- `python3 tools/build_game_data.py --check-release --out data` завершается
  кодом 2: `Release заблокирован: definitions.db не соответствует пройденному
  ручному аудиту`;
- лицензия собственных определений остаётся плейсхолдером
  `<ЛИЦЕНЗИЯ — решает владелец>`; в текущей БД собственных определений нет.

`audit.passed` и `rights_review.passed` **не выставлялись в true**. Публичный
release не объявляется. Техническую сборку разрешает waiver владельца от
2026-11-15 (`sostavslova.release.allowUnauditedDefinitions=true` в
`gradle.properties`); удалить флаг после ручного аудита.

## 6. Что запускалось

| Команда | Результат |
|---|---|
| `python3 -m unittest discover tools/tests` | **408 тестов, OK** (54,885 с; +2 теста waiver) |
| `bash ./gradlew --offline :core:test --rerun-tasks --console=plain` | BUILD SUCCESSFUL; по XML — **135 тестов, 0 failures/errors/skips** в 18 классах |
| `bash ./gradlew --offline :app:verifyDictionaryLicenses --rerun-tasks` | BUILD SUCCESSFUL |
| `bash ./gradlew --offline :app:compileDebugKotlin` | BUILD SUCCESSFUL (в составе `assembleDebug`) |
| `bash ./gradlew --offline :app:compileDebugAndroidTestKotlin --rerun-tasks` | BUILD SUCCESSFUL |
| `bash ./gradlew --offline :app:lintDebug --rerun-tasks` | BUILD SUCCESSFUL; 0 errors, 10 warnings (5 `IconLauncherShape`, 3 `UnusedResources`, 1 `DataExtractionRules`, 1 `IconLocation`) |
| `bash ./gradlew --offline :app:assembleDebug --rerun-tasks` | BUILD SUCCESSFUL; APK содержит нужные assets |
| `python3 tools/build_game_data.py --check-release --out data` | **код 2** (строгий режим без waiver) — защита публикации на месте |
| `python3 tools/build_game_data.py --check-release --allow-unaudited-release --out data` | код 0, `release_audit=owner-waiver-unaudited`, `publishable=false`, предупреждение в stderr |
| `bash ./gradlew --offline :app:assembleRelease` | **BUILD SUCCESSFUL** (waiver): предупреждение в логе, APK не является публичным release |
| `bash orchestrator/run.sh --check full`, `--check data`, `--check android` | все три PASS; итоговый gate `SHORT15-04` — exit 0 |
| `bash ./gradlew --offline :app:connectedDebugAndroidTest` | **FAILED**: нет устройства; кроме того, в offline-кэше нет `android-test-plugin-host-additional-test-output:32.1.1` |
| `JUnitCore` на `LegacyCampaignMigrationTest` (JVM, не эмулятор) | **OK (27 tests)** |
| `adb devices -l` | список пуст; каталога `emulator/` и system images в SDK нет |

## 7. APK

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
(17 289 516 байт, SHA `a9e05e42…ff1a95`). Содержит `assets/campaign.json`,
`words.txt`, `definitions.db`, `word_sources.jsonl`, `levels.json` и notices.
SHA assets: кампания `eb440873…1fadc`, словарь `3b8a1339…fc997`, БД
`6d0cd051…d9d7f`.

Release APK: `app/build/outputs/apk/release/app-release.apk`
(6 803 203 байта, SHA `8a28c77a…ae69`). Это **техническая pre-release**
сборка, полученная штатной командой `:app:assembleRelease` с waiver владельца
(`allowUnauditedDefinitions`), а не разрешённый публичный release. Проверки APK:

- `apksigner verify`: подпись валидна, схема v2; `zipalign -c 4` — successful;
- подписант — `CN=Android Debug` (`SHA-256 B3:8E:AF:EA:…:52:64`), то есть
  debug-ключом; для публикации недопустимо;
- `package=com.aistudio.wordsgame.kymxpv`, `versionCode=1`, `versionName=1.0.0`,
  minSdk 24, targetSdk 36; `uses-permission` отсутствуют;
- `BuildConfig.CAMPAIGN_SHA256` присутствует в DEX: `eb440873…1fadc`;
- assets совпадают с `data/`: кампания `eb440873…1fadc`, словарь
  `3b8a1339…fc997`, БД `6d0cd051…d9d7f`; notices побайтово совпадают с
  `legal/`.

Побайтовая воспроизводимость release-сборки не подтверждена: два прогона
`:app:assembleRelease --rerun-tasks` дали разные SHA при одинаковом размере
(6 803 203 байта) и одинаковом содержимом assets и подписи; debug-APK при этом
стабилен (`a9e05e42…ff1a95`). Поэтому в отчёте фиксируется SHA конкретного
собранного файла; воспроизводимость данных и кампании (`eb440873…1fadc`,
`3b8a1339…fc997`, `6d0cd051…d9d7f`) от этого не зависит.

## 8. Ручные проверки интерфейса

Полная матрица — [uiux/SHORT15_MANUAL_QA.md](uiux/SHORT15_MANUAL_QA.md).
В этом окружении **нет устройства и эмулятора**, поэтому все строки матрицы
имеют статус **NOT RUN**; они не заменяются статикой или JVM-тестами.
Ключевые сценарии для первого ручного прогона:

1. Карта: 15 карточек, 6 групп `[3,2,3,2,3,2]`, `level_card_16` отсутствует.
2. Уровни 1, 4, 6, 9, 11, 14, 15: уроки T1–T7, M/R и тексты заданий.
3. Ввод/IME: Done и кнопка, автокоррекция, лимит 30, сохранение черновика.
4. Ошибки: неизвестное слово, слово вне набора, короткое, исходное,
   недостающие буквы; баннеры под полем и без перекрытия ввода.
5. Подсказки: цена/остаток, маскирование `***`, отсутствие URL и ответа,
   исчерпание бюджета, загадка рядом с вводом.
6. Задания: «Задания · N из M», сворачивание, T4-загадки, статистика.
7. Финал: 4/5 → финальный диалог и 48/49 (если предыдущие 14 уровней собраны
   полностью), «Мастер» ещё нет; 5/5 → 49/49 и «Мастер».
8. Повторный вход, поворот, process death, сохранение ответов/подсказок.
9. 320/360 dp при 100/150/200%, ширина ≥600 dp, TalkBack, контраст звёзд.
10. Обновление поверх v1 и несовместимый `campaignId` (см. §9).
11. Загрузка/ошибка/retry карты и один welcome-диалог поверх готовой карты.
12. T7-урок: данные говорят T7@15, код показывает T6+T7 на 14; зафиксировать
    продуктовое ожидание (это не блокер данных, но требует решения владельца).

## 9. Чистая установка и обновление со старого APK

**Фактическое исполнение: NOT RUN** — устройства нет. Ниже — проверенная по
коду процедура; статическая проверка `ProgressRepository` и компиляция
androidTest выполнены, но это не заменяет прогон.

Идентичность кампании — SHA-256 `campaign.json` (`campaignId`). При первом
открытии карты/игры вызывается `ProgressRepository.migrateCampaign`:

- переносятся только ответы слов, которые есть в short15; звёзды
  пересчитываются по новым заданиям через `TaskRules.evaluate`;
- `highestUnlockedLevel` считается `CampaignRules.frontier` только по
  непрерывно пройденным уровням;
- старые `completed`, `highestUnlockedLevel`, `finaleReached` не открывают
  15-й уровень и не дают финал;
- несовместимый v2 архивируется в `campaignProgressV2Archive*`, новый снимок
  получает `resetReason`; v1-ключи не удаляются при mismatch;
- после успешной миграции ключи v1 (`currentGame`, `levelProgress`,
  `levelAnswers`, `levelSessionV1`) удаляются.

Процедура на устройстве:

```sh
adb devices -l
# обновление без потери данных: тот же applicationId и подпись, без uninstall/pm clear
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.aistudio.wordsgame.kymxpv
adb shell monkey -p com.aistudio.wordsgame.kymxpv 1
```

Для debug-сборки можно сохранить/снять DataStore до и после:

```sh
adb shell run-as com.aistudio.wordsgame.kymxpv \
  cat files/datastore/progress.preferences_pb > /tmp/progress-before.pb
adb shell run-as com.aistudio.wordsgame.kymxpv \
  cat files/datastore/progress.preferences_pb > /tmp/progress-after.pb
```

Проверить: карта 15/6, старые 30 уровней не появились, чужие звёзды не
открыли short15, 15-й уровень не открыт старыми флагами, найденные слова
перенесены только для совпавших исходников. Чистая установка — это
`adb uninstall` (или `pm clear`) **до** установки; она по определению удаляет
прежние данные, поэтому для проверки сохранности обновляться нужно через
`install -r`.

Откат приложения: старый APK не читает `campaignProgressV2`, а успешная
миграция удаляет v1-ключи. Поэтому downgrade без потери прогресса не
гарантирован; перед экспериментом сохранить `/tmp/progress-before.pb` или
использовать отдельный тестовый профиль. Откат данных пакета —
`.local/short15-rollback-v1-data/`; `sha256sum -c SHA256SUMS.rollback` прошёл.

JVM-прогон migration-теста (не эмулятор): класс `LegacyCampaignMigrationTest`
из `androidTest` исполнялся через `org.junit.runner.JUnitCore` с classpath из
Gradle `debugRuntimeClasspath` + `debugAndroidTestRuntimeClasspath`, классов
`app`/`core` и `android.jar`. Это проверяет логику DataStore/миграции без
Android UI; результат — **27/27** после исправления устаревшего ожидания
(§10, пункт 5).

### Открытие финала

- Для доступа к уровню 15 нужно минимум 38 звёзд (сумма R уровней 1–14).
- Если уровни 1–14 собраны полностью (44/44), то 4/5 на уровне 15 дают
  48/49 и финальный диалог; «Мастер» ещё не выдаётся.
- 5/5 на уровне 15 дают 49/49 и «Мастер».
- Задания уровня 15: T1 — 40 слов от 4 букв, T2 — 15 слов на `с`, T3 — слово
  длины ≥9, T5 — слово с окончанием `ина` длины ≥8, T7 — 10 слов с буквой `у`.
  T4 на 15-м нет.

## 10. Ограничения и точные блокеры публикации

1. **Права определений не подтверждены.** 31 351 текст из Викисловаря имеет
   временный статус `wiktionary-provisional-v1`; `audit.passed=false`,
   `rights_review.passed=false`, `replacement_required=true`. Ручной аудит
   выборки и проверка индивидуальных прав не выполнены; публикация запрещена.
   Владелец 2026-11-15 разрешил техническую pre-release сборку без юридической
   чистоты: waiver в `gradle.properties` не меняет флаги аудита и подлежит
   удалению после аудита. Дополнительно `verify_selection_inputs` сообщает о
   расхождении конфига/списков жалоб с manifest — перед публикацией пакет нужно
   пересобрать и провести новый аудит.
2. **Лицензия собственных определений не решена** — плейсхолдер
   `<ЛИЦЕНЗИЯ — решает владелец>`. Сейчас собственных определений 0, но
   политика на будущее не утверждена.
3. **Release подписан debug-ключом**, `applicationId` —
   `com.aistudio.wordsgame.kymxpv`, `versionCode=1`. Публикация требует
   решения владельца о ключах, applicationId и версии.
4. **Нет ручного прогона на устройстве.** `adb devices` пуст, эмулятора в SDK
   нет; `connectedDebugAndroidTest` не выполнен. Все UI-сценарии, IME,
   TalkBack, 320/360 dp, чистая установка и обновление — **NOT RUN**.
5. **JVM-прогон migration-теста: исправлено.** 27 тестов, 27 прошли.
   `LegacyCampaignMigrationTest.incompleteRecordsUseDefaultsAndNormalizedAliasesMerge`
   ожидал `highestUnlockedLevel=1`, хотя по `CampaignRules.frontier` два разных
   ответа («река» → T1, «каре» → T2) дают 2 звезды при R=2 и открывают уровень 2;
   так же ведёт себя соседний тест
   `secondaryFlagsAnswersAndCurrentGameDoNotInventCompletedPredecessor`.
   Ожидание было ошибочно изменено в SHORT15-03 (с 3 на 1), теперь стоит 2 с
   пояснением. Правило миграции и код не менялись — только ожидание теста.
6. **GEN-05:** `status=exhausted`; стартовые `simulation_targets` не
   достигнуты. Это не отменяет GEN-04 `valid`, но баланс вероятностей не
   доказан и не заявляется.
7. **T7-урок:** данные `introductions` указывают T7@15, код показывает T6+T7
   на 14; нужно продуктовое решение владельца.
8. **Воспроизводимость:** точный rebuild требует локального
   `hints-current.jsonl` с SHA `0db4d56d…`; текущий
   `build/reports/level_profiles/hints.jvm.jsonl` уже другой (`8a07dbb3…`).
   Package/SHA256SUMS этот вход не содержит.
9. **Мелкое расхождение notices:** `THIRD_PARTY_NOTICES.txt` называет файл
   `word_sources.jsonl.gz`, а в APK AGP упаковал его как
   `assets/word_sources.jsonl` (содержимое и URL на месте). Атрибуция есть,
   но имя в уведомлении стоит согласовать.
10. **Устаревшие числа в документации:** `docs/LOCAL_DEFINITIONS.md` всё ещё
    описывает 35 947 толкований для 23 393 слов; текущая БД — 31 351 для
    20 106. `docs/PROJECT_STATUS.md` исправлен на 135 тестов.

## 11. Вердикт

- Gate `SHORT15-04` (`--check full`, `--check data`, `--check android`,
  `:app:assembleRelease`): **PASS** (exit 0).
- Технический/data gate short15: **PASS** по данным, JVM-правилам,
  детерминированности, лицензиям включённых материалов и сборке debug;
  JVM-прогон migration-тестов — 27/27.
- Публичный release: **BLOCKED_FOR_PUBLIC_RELEASE** по пунктам §10. Waiver —
  решение владельца, а не подтверждение прав: `audit.passed=false`,
  `rights_review.passed=false` остаются в manifest.
- Локальные артефакты готовы: debug APK и технический pre-release APK;
  публикация не выполнялась и не объявляется. Юридическая чистота
  определений — следующий этап.

## 12. Команды воспроизведения

```sh
python3 -m unittest discover tools/tests
bash ./gradlew --offline :core:test --rerun-tasks --console=plain
bash ./gradlew --offline :app:verifyDictionaryLicenses --rerun-tasks --console=plain
bash ./gradlew --offline :app:compileDebugAndroidTestKotlin --rerun-tasks --console=plain
bash ./gradlew --offline :app:lintDebug --rerun-tasks --console=plain
bash ./gradlew --offline :app:assembleDebug --rerun-tasks --console=plain
python3 tools/build_game_data.py --check-release --out data                 # код 2 без waiver
python3 tools/build_game_data.py --check-release --allow-unaudited-release --out data
bash ./gradlew --offline :app:assembleRelease --console=plain                # waiver из gradle.properties
```

Независимая проверка установленной кампании:

```sh
python3 tools/validate_campaign.py \
  --campaign data/campaign.json --profile short15-release-v1 \
  --profiles build/reports/short15/profile-cache/031ea23819f46c795ebb5ca8c4c0812e8bea43f28ae2b52f470d502ba65be7f1.json.gz \
  --words data/words.txt --previous-levels data/levels.json \
  --database data/definitions.db \
  --manifest build/reports/short15/package/manifest.json \
  --output build/reports/short15/final-gate/validation-installed
```

Проверка установленного пакета и отката данных:

```sh
python3 tools/install_short15_package.py \
  --package build/reports/short15/package --dry-run
(cd .local/short15-rollback-v1-data && sha256sum -c SHA256SUMS.rollback)
```

Полный JVM-аудит подсказок:

```sh
bash ./gradlew --offline :core:auditDefinitionHints \
  --args="/var/www/words-combination/.local/data01-oc-cache/runs/cf75f869bf83b072307937fe17b35e6f9cc3d914e96fd80bd7cf3994cc206b05/round-01/definitions/hint_inputs.jsonl /var/www/words-combination/build/reports/short15/final-gate/hint_audit.jsonl" \
  --console=plain
```

Итоговые машинные артефакты этой проверки:

- `build/reports/short15/final-gate/summary.json` — прямые пересчёты;
- `build/reports/short15/final-gate/validation-installed/validation_report.json`;
- `build/reports/short15/final-gate/hint_audit.jsonl` и `.summary.json`.

