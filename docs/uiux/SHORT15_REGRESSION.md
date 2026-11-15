# SHORT15 — регрессия интерфейса после UX15-01…UX15-12

Дата: 2026-11-15. Проверка выполнена в рабочем дереве без commit/push.
Матрица ручных действий — [SHORT15_MANUAL_QA.md](SHORT15_MANUAL_QA.md).

## 1. Границы проверки

Проверялась установленная короткая кампания **short15-release-v1**, а не
исторические 30 уровней:

- `data/campaign.json`, SHA-256
  `eb4408731b453104bad3f05db4a69397d1e4d208f9193adcdd440fc5ecb1fadc`;
  тот же SHA записан в `data/manifest.json:campaign_source.sha256` и в
  `app/src/main/assets/campaign.json`.
- 15 уровней, 6 этапов `[3,2,3,2,3,2]`, 49 заданий/звёзд.
- Уровень 15: 5 заданий, R=4; «Мастер» — только 49/49.
- Задания T1–T7: T1 — 15, T2 — 12, T3 — 9, T4 — 6 (11 загадок), T5 — 5,
  T6 — 1, T7 — 1. Все типы присутствуют.

Игровые данные SHORT15-02 в этой задаче не перегенерировались. Проверка
T4/T6 ниже опирается на уже созданный пакет и его отчёт, а не заменяет
независимую проверку данных.

## 2. Что действительно запускалось

Команда (Gradle запускался через `bash`, потому что `gradlew` в рабочем дереве
не имеет бита выполнения):

```text
bash gradlew :core:test :app:assembleDebug :app:lintDebug \
  :app:compileDebugAndroidTestKotlin --rerun-tasks --console=plain
```

Результат: **BUILD SUCCESSFUL in 27s**, 64 задачи выполнены (`64 executed`),
не из кэша.

| Проверка | Фактический результат |
|---|---|
| `:core:test` | 135 тестов, 0 падений, 0 ошибок, 0 пропусков; `Short15ProductionCampaignTest` — 2/2 |
| `:app:assembleDebug` | Успешно; APK `app/build/outputs/apk/debug/app-debug.apk` — 17 289 516 байт; в APK есть `assets/campaign.json`, `assets/words.txt`, `assets/definitions.db` |
| `:app:lintDebug` | Успешно; 0 errors, 10 warnings: 5 `IconLauncherShape`, 3 `UnusedResources`, 1 `DataExtractionRules`, 1 `IconLocation` |
| `:app:compileDebugAndroidTestKotlin` | Успешно; androidTest компилируется |
| `:app:verifyDictionaryLicenses` | Успешно как зависимость `assembleDebug`; словарь, кампания, БД и manifest согласованы |
| `:app:syncDictionaryAssets` | Успешно как зависимость `assembleDebug`; assets совпадают с manifest |
| `adb devices` | Список пуст |
| Эмулятор | В `$ANDROID_HOME` нет каталога `emulator` и system images |
| `:app:connectedDebugAndroidTest` | **NOT RUN** — нет устройства/эмулятора |

`docs/PROJECT_STATUS.md` указывает 129 тестов `:core`; фактический прогон дал
135. Это расхождение снимка, а не падение: все 135 тестов прошли.

## 3. Статическая проверка контракта и областей UI

Статус `PASS (статика/данные)` означает проверку по исходникам, данным или
JVM-тесту, а не ручной прогон на устройстве. Для всех экранных сценариев,
требующих устройства, в матрице ниже стоит `NOT RUN`.

| Сценарий | Конфигурация | Ожидаемо | Фактически | PASS-FAIL-NOT RUN |
|---|---|---|---|---|
| Карта кампании | `data/campaign.json` | 15 уровней, 6 этапов `[3,2,3,2,3,2]` | `Short15ProductionCampaignTest.installedCampaignHasFifteenLevelsSixStagesAndEveryTaskType` — 15/6/49; JVM-тест прошёл | PASS (JVM) |
| Карточки карты | `LevelsScreen` | 15 карточек, 16-й нет | `LevelsViewModel` строит список из `campaign.levels`; `LevelsScreen.kt:170-179` обходит только `state.levels`; `LevelsLayoutTest.kt:89-90` читает production `campaign.json` и проверяет `level_card_15` + отсутствие `level_card_16` | PASS (статика/данные); UI-тест NOT RUN |
| Этапы | `LevelsScreen.kt:170-175` | Заголовки 1–3, 4–5, 6–8, 9–10, 11–13, 14–15 | `LevelsLayoutTest` прокручивает `stage_6` и ожидает «Мастер · уровни 14–15»; данные совпадают | PASS (статика/данные); UI-тест NOT RUN |
| Компактная карточка | `LevelButton` | Номер + исходное слово, звёзды, один статус; без строк «Для прохождения» и «Можно добрать» | `LevelsScreen.kt:200-227`; тесты `CampaignMapUiTest` и `LevelsLayoutTest` ищут отсутствие этих строк | PASS (статика); UI-тест NOT RUN |
| Статусы уровня | 0…M звёзд | «Закрыт», «Открыт», «Пройден», «Все звёзды» | `LevelsScreen.kt:202-207`; `CampaignMapUiTest` и `LevelsLayoutTest` проверяют все четыре | PASS (статика); UI-тест NOT RUN |
| Закрытый уровень | Тап по карточке 2 без звезды на 1 | Диалог с предыдущим уровнем и R; игра не открывается | `LevelsViewModel.kt:141-150`; `CampaignMapUiTest` и `LevelsNavigationTest` проверяют диалог и отсутствие `game_screen` | PASS (статика); UI-тест NOT RUN |
| Финал 4/5 | Уровень 15, 4 задания | Финал достигнут, 48/49, «Мастер» ещё нет | `Short15ProductionCampaignTest.fourOfFiveReachesFinaleButMasterNeedsAllFortyNine`; `CampaignRules` + `LevelsScreen.kt:151-156` | PASS (JVM/статика) |
| Мастер 49/49 | Все задания всех 15 уровней | `isMaster`, баннер «Мастер — все звёзды собраны!», корона | тот же JVM-тест: 48 ≠ master, 49 = master; `CampaignMapUiTest` проверяет баннер и корону | PASS (JVM/статика); UI-тест NOT RUN |
| Ввод под исходным словом | `GameScreen.kt:134-167` | Плитки слова, порог, поле ввода; без дублирующих блоков stars/R/M | `LetterTiles`, `level_difficulty`, `Well` + `WordInputRow`; отдельного `StarsRow` в campaign-ветке нет | PASS (статика) |
| Клавиатура/IME | `WordInputRow.kt:51-101` | IME Done/Go/Search/Send/Next отправляет; кнопка отправляет; фокус остаётся; автокоррекция выключена; лимит 30 | `ComponentInteractionTest.inputLimitsLengthAndSubmitsByImeAndButtonWithoutLosingFocus`; `GameNavigationTest` проверяет фокус и IME | PASS (статика); UI-тест NOT RUN |
| Задания | `CampaignTasks.kt:19-39` | Все задания уровня, прогресс, T4-загадки, testTag `level_task_<type>` | `GameScreen.kt:179-195` + `CampaignTasks`; `CampaignGameUiTest.allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes` | PASS (статика); UI-тест NOT RUN |
| Сворачивание заданий | `game_tasks_toggle` | По умолчанию свёрнуто; раскрытие показывает список; ввод не теряется; новый уровень снова свёрнут | `GameScreen.kt:180-192`; `CampaignGameUiTest.tasksToggleKeepsInputAndStartsCollapsedForAnotherLevel` | PASS (статика); UI-тест NOT RUN |
| Загадка рядом с вводом | `CampaignRiddlePreview` | Пока задания свёрнуты — ближайшая нерешённая загадка под полем; после решения — следующая; все решены — блок исчезает; ответ не раскрывается | `CampaignTasks.kt:44-58`; `GameScreen.kt:181`; `CampaignGameUiTest.riddlePreviewMovesToNextUnsolvedAndDisappearsWhenAllSolved`; `GameViewModelTest` о безопасном тексте | PASS (статика); UI-тест NOT RUN |
| Уведомления | `game_feedback` | `state.message` и `notice` под вводом; 3500 мс; не перекрывают поле | `GameScreen.kt:168-178`, `GameScreen.kt:88-100`; `AlertBanner` с `LiveRegionMode.Polite` | PASS (статика) |
| Длинная ошибка | `answer_letters_missing` / save error | Текст переносится, блок растёт, поле и кнопки остаются доступны при 200% | `AlertBanner.kt:28-32` без `maxLines`; `game_feedback` `heightIn(min=48.dp)` без фиксированной высоты; `GameScreen.kt:168-178` | PASS (статика); визуальный прогон NOT RUN |
| Подсказки | `game_hint`, `game_hint_button` | Шаги подсказки, цена/остаток, маскирование ответа, отсутствие URL, бюджет | `GameScreen.kt:210-259`; `GameViewModelTest.hintNeverUsesFoundWordAndSuppressesWordAndUrlInDefinitions`, `hintMasksRootsAndFormsInsteadOfDiscardingUsefulDefinition`, `noDatabaseSkipsPaidMetadataAndFreeT4LengthWorksAtZero` | PASS (статика); UI-тест NOT RUN |
| Найденные слова | `level_found_group_N` | Группы по длине раскрываются/сворачиваются, определения открываются | `GameScreen.kt:196-209`; `CampaignGameUiTest`; `GameNavigationTest` | PASS (статика); UI-тест NOT RUN |
| Загрузка карты | `LevelsScreen.kt:130-134` | Пока карты нет — нейтральный фон без ложных карточек, без приветствия | `CampaignMapUiTest.loadingShowsNeutralSpaceWithoutFakeCardsThenErrorRetryAndSuccess` | PASS (статика); UI-тест NOT RUN |
| Ошибка/retry карты | `levels_retry` | Alert + «Повторить»; retry возвращает карту; пустая карта не показывает приветствие | `LevelsScreen.kt:158-161`; `LevelsViewModel.load()`; `CampaignMapUiTest` | PASS (статика); UI-тест NOT RUN |
| Загрузка/ошибка игры | `GameScreen.kt:127-131` | «Восстанавливаем игру…», при ошибке Alert + retry, без ложной игры | `GameViewModel.onGameOpened/onRetry`; `GameScreen` | PASS (статика); UI-тест NOT RUN |
| Первый запуск | `levels_welcome` | Один диалог только поверх готовой карты; DONE фиксируется после композиции; повторно не появляется | `LevelsViewModel.kt:83-86,110-139`; `LevelsScreen.kt:109-129`; `CampaignMapUiTest.welcomeWaitsForReadyMap...`; `WelcomeDialogUiTest`; `WelcomeStateRepositoryTest` | PASS (статика); UI-тест NOT RUN |
| Обновление/повторный запуск | DataStore v1/v2 | Прежние ответы и звёзды сохраняются; старые флаги не открывают 15-й; приветствие не повторяется | `LegacyCampaignMigrationTest`, `CampaignProgressRepositoryTest`, `WelcomeStateRepositoryTest` | PASS (статика); UI-тест NOT RUN |
| Возврат/повторное посещение | `GameRoute` / `LevelsRoute` | Назад на карту; ответы, черновик и подсказки сохраняются; карта не прыгает | `GameScreen.kt:74-104`; `LevelsScreen.kt:90-101`; `GameNavigationTest`, `LevelsNavigationTest`, `MainScenariosUiTest` | PASS (статика); UI-тест NOT RUN |
| Прокрутка карты | `LazyVerticalGrid` | Первый запуск позиционирует на рекомендованный уровень; обновления прогресса не двигают карту; позиция переживает recreation | `LevelsScreen.kt:90-101`; `CampaignMapUiTest.initialRecommendationScrollsOnce...`; `LevelsNavigationTest` | PASS (статика); UI-тест NOT RUN |
| Размеры 320/360 dp | 100/150/200% | Нет обрезки текста, кнопки ≥48 dp, поле ввода не перекрыто | `LevelsLayoutTest` — 320/1, 320/2, 840/1, 840/2; `GameLayoutTest` — 360/1, 320/2; `LevelsLayoutTest` проверяет 48 dp | PASS (статика); UI-тест NOT RUN; 360/1.5 и 360/2 отдельно не запускались |
| TalkBack | Карта, игра, диалоги | Карточки и звёзды имеют описание/состояние; поле имеет label; уведомления live region; подсказка не раскрывает ответ | `StarsRow.kt:36-61`, `LevelsScreen.kt:208-209`, `WordInputRow.kt:66-67`, `AlertBanner.kt:28-32`, `CampaignGameUiTest` (семантика без ответа/URL) | PASS (статика); ручной TalkBack NOT RUN |
| Цвет звёзд | 3,88:1 / 4,48:1 | Заполненная и пустая звезда различимы на белом | `WordsColors.kt:21-24`; `StarsContrastTest` | PASS (статика); приборный замер NOT RUN |

## 4. T4/T6 и отсутствие 16-го уровня

### 4.1 T4 — 11 загадок, качественные слова

Источник: `data/campaign.json` (уровни 9–14) и уже созданный отчёт
`build/reports/short15/package/quality_report.json`
(`data/manifest.json:campaign_source.quality_report_sha256` =
`0087e9cd…49a640`). Данные SHORT15-02 повторно не генерировались.

| Показатель | Факт |
|---|---|
| Загадок T4 | 11 |
| Слова целей | `работа`, `утро`, `отель`, `десерт`, `проблема`, `билет`, `ковер`, `весна`, `стадион`, `банк`, `артист` |
| Проверка отчёта | у всех `allowed=true`, `noun=true`; частотный ранг 29–3022; длина подсказки 39–139 |
| Вывод | Для T4 выбраны частотные существительные с содержательной маскированной подсказкой; это не случайные обрезки и не служебные формы |

### 4.2 T6 — тема «Профессии»

| Показатель | Факт |
|---|---|
| Задание | `l14-t6`, уровень 14, `n=5`, категория `professions` |
| Слова | `артист`, `артистка`, `банкир`, `бариста`, `батрак`, `радист` |
| Запас | 6 узнаваемых при `n=5` (20% резерва); глобальный резерв темы — 50 |
| Подсказки | пять слов имеют содержательную подсказку; `артистка` — необязательный шестой ответ |
| Вывод | Набор цельный и достижимый; `батрак` входит в проверенный резерв, а не в обязательную пятёрку |

### 4.3 16-й уровень

- В `data/campaign.json` ровно 15 объектов `levels`; `maxStars` = 49.
- `LevelsViewModel` строит `items` только через `campaign.levels.map`
  (`LevelsViewModel.kt:77-82`), без синтетической карточки.
- `LevelsScreen` выводит либо `state.levels`, либо `state.levels.filter { it.stage == ... }`
  (`LevelsScreen.kt:165-179`); добавить 16-й уровень неоткуда.
- `LevelsLayoutTest.kt:89-90` читает реальный `campaign.json` из assets и
  проверяет `level_card_15` + `onAllNodesWithTag("level_card_16").assertCountEquals(0)`.
- `Short15ProductionCampaignTest` проверяет 15 уровней и 49 звёзд.
- Статус: **PASS по данным и исходникам**; сам инструментальный UI-тест
  **NOT RUN** из-за отсутствия устройства.

## 5. Ручные проверки

Полная матрица — в [SHORT15_MANUAL_QA.md](SHORT15_MANUAL_QA.md). В этом
рабочем окружении **нет устройства и эмулятора**, поэтому все сценарии,
требующие нажатий, TalkBack, реального IME или визуального осмотра, имеют
статус **NOT RUN**. Это не замаскировано статусом PASS.

## 6. Найденные проблемы и наблюдения

### 6.1 Подтверждённые FAIL

Не найдено ни одного воспроизведённого UI-дефекта, который можно подтвердить
без устройства. Автоматические проверки (`:core:test`, сборка, lint,
компиляция androidTest) прошли. Это не означает, что ручная матрица пройдена:
она не выполнялась.

### 6.2 Наблюдения, не блокирующие UX15-13

1. **Урок T7 на уровне 14, а не 15.** `campaign.json.introductions` указывает
   `T7@15`, но `GameViewModel.kt:167-173` на первом уровне этапа 6 (уровень 14)
   собирает все новые типы этапа, включая T7, и показывает их одним диалогом.
   Тест `firstLevelOfMasterStageExplainsBothRotatingNewTypesOnce` ожидает
   T6+T7 на 14. На 15 повторного диалога не будет, потому что T7 уже отмечен
   просмотренным. Это не падение и не потеря задания; вопрос продуктового
   ожидания «урок ровно на 15» требует решения владельца. В рамках UX15-13
   поведение не менялось.
   Воспроизведение: очистить данные, дойти до уровня 14, открыть его впервые;
   в диалоге «Новые задания этапа» будут T6 и T7.
2. **Поле ввода молча ограничивает 30 символов.** `WordInputRow.kt:60-64`
   обрезает ввод до `Alphabet.MAX_WORD_LENGTH`, а `GameScreen` не передаёт
   `onLengthExceeded`; строка `word_too_long` используется только на экране
   значения слова. `ComponentInteractionTest` фиксирует именно обрезку до 30.
   Это существующее поведение, не регрессия UX15; при ручной проверке не
   считать его ошибкой без отдельного продуктового требования.
3. **Пустой резерв под баннер.** `game_feedback` всегда занимает минимум
   48 dp (`GameScreen.kt:168-178`), даже когда сообщений нет. Это осознанно:
   комментарий в коде объясняет защиту от прыжка при первом сообщении. При
   визуальном осмотре оценить, не выглядит ли это лишним отступом на 320 dp.
4. **Исторические документы.** `docs/CAMPAIGN.md` всё ещё описывает 30
   уровней/100 звёзд; это уже отмечено в `SHORT15_BASELINE.md`. Для UI и
   проверки использовались `data/campaign.json` и `core/Campaign.kt`, а не
   этот документ. Исправление CAMPAIGN.md — отдельная задача.
5. **Lint-предупреждения.** 10 warnings не являются ошибками сборки:
   `IconLauncherShape` (5), `UnusedResources` (3: `ic_close`,
   `level_start_error`, `nothing_found`), `DataExtractionRules` (1),
   `IconLocation` (1). Ни одно не указывает на регрессию UI short15.

### 6.3 Релизный блокер, не относящийся к UX15-13

`data/manifest.json` фиксирует `definitions.audit.passed=false` и
`rights_review.passed=false` (`wiktionary-provisional-v1`). Ручной аудит прав
определений не пройден; release остаётся заблокированным. Это не мешает
проверке UI short15 и не обходится этой задачей.

## 7. Ограничения

- Нет физического устройства; `adb devices` пуст.
- В Android SDK нет `emulator/` и system images; `connectedDebugAndroidTest`
  не запускался.
- Не проверены вручную: TalkBack, реальный IME, 150% и 200% на 360 dp,
  длинная ошибка на 320 dp, открытие/закрытие приложения, обновление поверх
  предыдущей сборки, process death, визуальный контраст звёзд.
- 360 dp / 150% и 360 dp / 200% не покрыты существующими параметрами
  `LevelsLayoutTest` (там 320/1, 320/2, 840/1, 840/2) и `GameLayoutTest`
  (360/1, 320/2); это строки для ручной матрицы.
- Проверка T4/T6 — по уже созданному пакету SHORT15-02; новых GEN/LEX/SEL
  прогонов и новых ИИ-запросов не было.
