# STAB-06 — фактический прогон Android instrumented-тестов 1.1.0

Текущая локальная проверка после исправлений: **PASS на API 24 и API 36**,
по **340 tests / 0 failures / 0 errors / 0 skipped** на итоговом снимке.
Завершение внешнего connected gate подтверждается отдельно записью STAB-06
в `orchestrator/PROGRESS.md` и логом оркестратора, как описано в конце отчёта.

Ниже сохранён исходный отчёт до исправлений. Результаты отдельно разрешённой
владельцем исправляющей задачи приведены в заключительном разделе.

Статус исходного прогона: **BLOCKED / FAIL**, не PASS. Тогда полный набор
был выполнен один раз, без повторных запусков и исправлений.

## Снимок и окружение

- Дата: 2026-11-24; запуск примерно 13:21–13:28 UTC (14:21–14:28 Europe/Bratislava).
- `git rev-parse HEAD`: `e1466a140f318e2150022fa821d649a2d7b42d2d`.
  Рабочее дерево до прогона чистое; SHA после прогона совпадает.
- Версия: **1.1.0 (6)** — сверены `app/build.gradle.kts` и фактический
  `app/build/outputs/apk/debug/output-metadata.json` (`app-debug.apk`).
- JDK: OpenJDK Temurin **21.0.13+9-LTS**, 64-Bit Server VM.
- Android SDK и `adb` доступны локально; установки SDK, образов, зависимостей не было.
- Выделенный тестовый AVD: **pf36**, профиль Pixel 5, фактическая модель из
  `getprop ro.product.model`: **Android SDK built for x86_64**;
  `getprop ro.build.version.sdk`: **36** (Android 16), образ default/x86_64.
- Системная локаль: `ro.product.locale=en-US`, `persist.sys.locale` пустой.
- При первоначальном `adb devices -l` список пуст. После загрузки:

```text
List of devices attached
emulator-5554 device product:sdk_phone64_x86_64 model:Android_SDK_built_for_x86_64 device:emu64x
```

Транспортный ID опущен как несущественный. Подключён только этот эмулятор;
реальных телефонов нет. AVD запущен с `-read-only -no-window -no-audio
-no-snapshot-save -no-boot-anim`; готовность подтверждена `sys.boot_completed=1`.
До установки `pm path io.github.volmir.wordscombination` не вернул пакета:
пользовательская release-сборка не заменялась, реальный прогресс не сбрасывался.
После прогона запущенный агентом AVD остановлен через `adb -s emulator-5554 emu kill`
без сохранения состояния; исходное отсутствие подключённых устройств восстановлено.

## Команда и результат

Из корня репозитория, без фильтра по классу/методу и без изменения настроек:

```bash
ANDROID_SERIAL=emulator-5554 bash ./gradlew --offline :app:connectedDebugAndroidTest --console=plain
```

Вывод направлен во временный локальный файл вне репозитория; код завершения
зафиксирован непосредственно после команды: **1**. Gradle: `BUILD FAILED in
6m 47s`, `:app:connectedDebugAndroidTest FAILED`, `Finished 340 tests`.
Offline-зависимостей хватило; тесты действительно запускались на Android.

| Показатель | Значение |
|---|---:|
| tests (XML и фактические testcase) | 340 |
| Успешно | 329 |
| failures | 11 |
| errors | 0 |
| skipped | 0 |

Источник: `app/build/outputs/androidTest-results/connected/debug/TEST-pf36(AVD) - 16-_app-.xml`.
Атрибуты XML сверены с 340 фактическими `testcase` и 11 элементами `failure`.
Timestamp XML: `2026-11-24T13:28:02`, время тестового набора: **356.995 с**.
SHA-256 XML: `b40691c4527377fedf1c577376f9bc4f5eeb542b2c52a7206c5045f00f3c1e0e`.
HTML-отчёт Gradle: `app/build/reports/androidTests/connected/debug/index.html`.
Эти локальные build-артефакты могут быть заменены следующим запуском;
старые результаты из `app/build` в этот итог не включены.

## Падения и минимальные сценарии

В ходе прогона и после него read-only диагностика `adb shell dumpsys window`
показала фокус на **Application Not Responding: com.android.systemui**.
Существующая `GameRootDiagnostics` также зафиксировала это окно при падениях
`GameNavigationTest`. Окно не закрывали, настройки не меняли, тесты не
перезапускали. Это подтверждённая неисправность окружения; её влияние на
каждое отдельное падение требует проверки после отдельной исправляющей задачи.
Приведённые сценарии восстановлены по стеку и исходникам, отдельно не повторялись.

| Точный класс/метод | Классификация | Минимальный сценарий и наблюдение |
|---|---|---|
| `com.example.sostavslova.ui.ExitNavigationTest#systemBackDismissesDialogAndActivityIsFoundThroughWrappers` | Окружение | Открыть меню → выход при незавершённом сохранении → системный Back. `RootViewWithoutFocusException`, `ExitNavigationTest.kt:68`. |
| `com.example.sostavslova.ui.ExitNavigationTest#drawerBackClosesDrawerAndDialogSurvivesRecreationWithCancel` | Окружение | Открыть боковое меню → Back. `RootViewWithoutFocusException`, `ExitNavigationTest.kt:26`; до проверки пересоздания сценарий не дошёл. |
| `com.example.sostavslova.ui.components.LetterSlotsAccessibilityTest#descriptionsNameLengthAndEveryOpenedPosition` | Приложение (локализация; предварительно) | При системной локали `en-US` отрисовать `LetterSlots("_".repeat(20) + "я")`. Ожидается «Слово из 21 буквы. Открыто: 21-я Я.», получено «Слово из 21 букв. Открыто: 21-я Я.». `ComponentLayoutTest.kt:262`. |
| `com.example.sostavslova.ui.game.CampaignGameUiTest#t1AndT2ExplanationsUseConfiguredCountMinLength` | Тест (видимость/прокрутка; предварительно) | Отрисовать `GameScreen(pending.copy(countMinLength = 5, explanations = listOf(T1, T2)).withHintPanel())` → проверить первый текст пояснения через `assertIsDisplayed()` без прокрутки. `CampaignGameUiTest.kt:651`: компонент не отображается. |
| `com.example.sostavslova.ui.game.GameNavigationTest#hintAndBackKeepSavedAnswers` | Окружение (предварительно) | Открыть уровень 1 → переключиться на клавиатуру → ввести ответ `нос` для «чемпионство», иначе `пар` → IME action. Ожидание сообщения о зачёте превышает 10 с: `awaitText:45` → `awaitCorrectAnswer:66` → `GameNavigationTest.kt:122`. Последующие шаги метода не выполнены. |
| `com.example.sostavslova.ui.game.GameNavigationTest#recreationAndBackKeepAnswersAndDiscardDraft` | Окружение (предварительно) | Открыть уровень 1 → переключиться на клавиатуру → ввести ответ `нос` для «чемпионство», иначе `пар` → IME action. Ожидание сообщения о зачёте превышает 10 с: `awaitText:45` → `awaitCorrectAnswer:66` → `GameNavigationTest.kt:134`. Последующие шаги метода не выполнены. |
| `com.example.sostavslova.ui.game.GameNavigationTest#repeatedResumeKeepsSessionAndRecreationDiscardsDraft` | Окружение (предварительно) | Открыть уровень 1 → переключиться на клавиатуру → ввести ответ `нос` для «чемпионство», иначе `пар` → IME action. Ожидание сообщения о зачёте превышает 10 с: `awaitText:45` → `awaitCorrectAnswer:66` → `GameNavigationTest.kt:209`. Последующие шаги метода не выполнены. |
| `com.example.sostavslova.ui.game.GameNavigationTest#levelAnswersShowLengthTotalsAndNonInteractiveFoundWordsRemainAvailable` | Окружение (предварительно) | Открыть уровень 1 → переключиться на клавиатуру → ввести ответ `нос` для «чемпионство», иначе `пар` → IME action. Ожидание сообщения о зачёте превышает 10 с: `awaitText:45` → `awaitCorrectAnswer:66` → `GameNavigationTest.kt:93`. Последующие шаги метода не выполнены. |
| `com.example.sostavslova.ui.game.GameTouchInputTest#touchTypingEraseLongPressShuffleAndKeyboardSwitchUseRealViewModel` | Окружение (предварительно) | На синтетическом уровне «картотека» набрать плитками «кот» → перемешать → стереть/очистить → повторно набрать и отправить → перейти на клавиатуру и ввести «ко». Ожидание видимой IME превышает 10 с, `GameTouchInputTest.kt:96`; последующие шаги не выполнены. |
| `com.example.sostavslova.ui.levels.LevelsLayoutTest#levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid` | Окружение (предварительно) | Отрисовать карту и проверить звёзды уровня 1 через Android accessibility tree. Compose-проверки пройдены, `rootInActiveWindow` не подтвердил доступный узел «Звёзды» со state «Получено 0 из 1 звезды · для открытия нужно: 1»: `LevelsLayoutTest.kt:142`, вызов `:57`. Активным было окно ANR System UI. |
| `com.example.sostavslova.ui.levels.LevelsNavigationTest#drawerOpensLevelsScrollSurvivesRecreationAndSelectionOpensRealGame` | Приложение (восстановление прокрутки; предварительно) | На карте закрыть сообщение о недоступности уровня 2 → прокрутить к уровню 15 → пересоздать Activity. За 10 с не найден `level_card_15`, `LevelsNavigationTest.kt:41`. Сбой окружения мешает подтвердить первопричину; навигация в игру далее не выполнена. |

Для ошибки текста на 21 букве вероятна зависимость `pluralStringResource`
от английской системной локали при русских ресурсах: ожидаемая русская форма
и фактическое описание приведены из XML. Менять локаль для обхода падения
не пытались. Для пояснений установлен факт невидимости, но не доказана ошибка
вёрстки: тест проверяет текст без прокрутки. Для восстановления карты установлен
тайм-аут после пересоздания; дефект приложения отдельно от влияния ANR пока
не подтверждён. Эти предварительные классификации не являются оправданием
падений и не меняют статус FAIL.

## Проверка отчёта

`git diff --check` и `python3 tools/check_docs.py` завершились с кодом **0**.
Изменены только этот отчёт и его строка в `docs/INDEX.md`.

## Неисполненные проверки и границы вывода

- Все 340 обнаруженных тестов получили результат, `skipped=0`; незапланированных
  пропусков тестов нет. Упавшие методы не исполнили оставшиеся после исключения
  шаги; это failures, а не успешно проверенные сценарии.
- API 24–30 **NOT RUN**. Локально доступен AVD pf24, но после неуспешного
  прогона API 36 работа остановлена до отдельной задачи по исправлению;
  покрытие старых API не заявляется.
- Внешний gate **не запускался агентом** по условию задания. Для приёмки
  оркестратор должен реально выполнить connected-тесты на выделенном тестовом
  Android после устранения блокирующих причин. Компиляция androidTest и
  этот неуспешный прогон gate не заменяют. Конфигурация gate не изменена.
- Ручная приёмка, TalkBack, физические устройства и release APK этим debug-прогоном
  не проверены; выводов о готовности публичного релиза нет.
- Код, данные, тесты, конфиги и защищённые пути не изменены. Git-история
  не изменялась. Сырые логи, персональные данные и идентификаторы телефонов
  в отчёт не добавлены.

Исходным прогоном приёмка STAB-06 не достигнута: требовалась отдельная исправляющая задача для
окружения System UI и перечисленных неуспешных сценариев, затем новый полный
прогон и реальный внешний connected gate.

## Отдельная исправляющая задача от 2026-11-24

Владелец после исходного FAIL явно выбрал: «Выполнить отдельное исправление
причин падений, затем полный прогон». Исправления выполнены вне первоначального
режима STAB-06 «только проверка»; запреты на изменение данных, зависимостей,
юридических текстов и публикацию сохранены.

### Подтверждённые причины и исправления

- Окружение: прежний AVD сообщал `Failed to find ColorBuffer` / `Failed to bind
  to texture` и ANR System UI. Новый запуск использует явный программный
  рендеринг `-gpu swiftshader` и холодную загрузку `-no-snapshot`, сохраняя
  `-read-only -no-window -no-audio -no-boot-anim`. Конфиги AVD и проекта не
  менялись. В целевом воспроизведении 15 тестов четыре прежних падения
  (оба Back, IME, accessibility звёзд) прошли без изменений кода и тестов;
  оставшиеся семь падений воспроизвелись без ANR.
- `LetterSlots.kt`, `strings.xml`: склонение длины слова теперь выбирает
  `RussianPlural` с формами из ресурсов. Английская локаль больше не даёт
  «21 букв». Существующий `LetterSlotsAccessibilityTest` воспроизвёл ошибку
  до исправления и прошёл после него на системной локали `en-US`.
- `GameNavigationTest.kt`: ожидаемое сообщение о трёхбуквенном ответе теперь
  форматируется с настоящим `campaign.countMinLength` и формой слова из
  ресурсов. Раньше тест искал буквальные `%1$d` / `%2$s`. Все четыре
  тайм-аута ожидания сообщения устранены; проверки сохранения ответов,
  подсказок, черновиков и повторного открытия остаются.
- `CampaignGameUiTest.kt`: точное ожидание T1 обновлено по действующему
  `game_explain_t1`; оба пояснения проверяются с реальной прокруткой и
  `assertIsDisplayed()`. Первое повторное исполнение выявило, что одной
  прокрутки недостаточно: устаревшее ожидание T1 отсутствовало в дереве.
  Тексты приложения ради теста не менялись.
- `LevelsScreen.kt`: сетка создаётся после первого `pre-draw` окна, когда
  применены системные отступы. Временная диагностика показала восстановление
  `index=13, offset=124`, затем первый viewport 2120 px вместо 1852 px и
  ограничение позиции до `index=11, offset=206`; после уменьшения viewport
  уровень 15 уходил из видимой области. Это дефект восстановления приложения,
  а не ANR. Исходный `LevelsNavigationTest` воспроизвёл его и прошёл после
  исправления. Временные логи удалены; задержки и увеличение тайм-аутов не
  добавлялись.

Тесты не удалялись и не отключались. Первое целевое воспроизведение:
15 tests / 7 failures / 0 errors / 0 skipped, exit 1. Затем целевой набор
из 8 тестов дал 7 успешных и одно падение устаревшего ожидания T1, exit 1.
После его обновления и исправления viewport целевые тесты карты и пояснений
дали 2 tests / 0 failures / 0 errors / 0 skipped, exit 0. Повторы выполнялись
для проверки конкретных исправлений и диагностики причины, без повторения
до случайного успеха.

### Первый исправленный снимок

База: `e1466a140f318e2150022fa821d649a2d7b42d2d` плюс пять перечисленных
изменённых файлов `app/src`. SHA-256 точного патча
`git diff --binary e1466a140f318e2150022fa821d649a2d7b42d2d -- app/src`:
`fa095bc75fd8b44ed30a2169c8b692c63435ad1e8dff72f280f2f48dc56a5009`.
Этот снимок дал первый успешный API 36. Последующая проверка API 24 выявила
дополнительные проблемы; итоговый снимок приведён ниже отдельно.

Версия debug: 1.1.0 (6). SHA-256 APK:

- `app-debug.apk`: `31527df231bd043f30cd99b19d087ab6ecd69db63ec1383a36d677b788fcb70c`.
- `app-debug-androidTest.apk`: `f5e5063c4aacb2dc68ed7abb9c6d73d0944bdbc2d2b39ec38fddb57fdb4afb32`.

### Первый полный прогон после исправлений

API 36, AVD pf36 / Pixel 5, Android SDK built for x86_64, Android 16,
системная локаль `en-US`. Подключён один выделенный эмулятор;
перед первой установкой пакет приложения отсутствовал. Команда:

```bash
ANDROID_SERIAL=emulator-5554 bash ./gradlew --offline :core:test :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 5m 50s`. XML:
`TEST-pf36(AVD) - 16-_app-.xml`, timestamp `2026-11-24T13:51:29`,
время набора 334.442 с; SHA-256
`a1fb9524c0d34ea3714bf8b8fdc73bf33e041cffdf102230d33d055cbc23b85a`.
Атрибуты XML и фактические элементы сверены: **340 tests / 0 failures /
0 errors / 0 skipped**. `dumpsys activity lastanr`: ни одного ANR с загрузки.
Все дополнительные Gradle-проверки команды прошли; `:core:test` и
`:app:assembleDebug` были `UP-TO-DATE`. Lint: 0 errors, 13 warnings.
Результат сохранён локально в `/tmp/stab06-fixed-api36-results/debug/`
до запуска следующего набора.

### Дополнительные исправления по API 24

Первый полный прогон pf24, Android 7.0 / API 24, Pixel 5 / Android SDK built
for x86_64, локаль `en-US`, той же командой connected без фильтра дал exit 1:
**340 tests / 14 failures / 0 errors / 0 skipped**. XML timestamp
`2026-11-24T13:55:16`, время 183.199 с, SHA-256
`693682444e56142c24b067e59fb81f0f32a98574166856cb9def1621e1a88605`.
Копия: `/tmp/stab06-api24-first-results/debug/`. Перед установкой пакет
отсутствовал; реальных устройств нет. Прежний pf36 перед этим остановлен,
подключён только pf24 (`emulator-5556`), также `-read-only -no-snapshot
-no-window -no-audio -no-boot-anim -gpu swiftshader`.

Точные дополнительные падения:

| Класс | Методы | Причина / минимальный сценарий |
|---|---|---|
| `com.example.sostavslova.ui.components.AchievementComponentsTest` | `everyGlyphRendersFilledAndOutlineGeometryInBothThemes` | Тест: снимок иконки вызывает отсутствующий на API 24 оконный overload `PixelCopy.request`. |
| `com.example.sostavslova.ui.game.GameBannerTest` | `disablingAnimationsDuringRevealShowsFinalFrame`, `rewardFadesInAndFinishesWithin300ms`, `zeroSystemScaleShowsRewardAndAllDialogStarsImmediately`, `errorsAreVisibleImmediately`, `starsAppearInOrderAndFinishWithin600ms`, `ordinarySuccessIsVisibleImmediately` | Тест: та же ошибка API при снимке цвета баннера/звёзд. |
| `com.example.sostavslova.ui.game.OwnedRiddlesUiTest` | `allElevenApprovedRiddlesDisplayVerbatimOnSixRealCampaignScreens` | Тест: та же ошибка API при сохранении снимка уровня 12 после проверки текстов. |
| `com.example.sostavslova.ui.levels.CampaignMapUiTest` | `compactCardsUseSubtleAvailabilityColorsAndUpdateWhenUnlocked`, `darkThemeLevelCardsRenderAdaptedStateColors` | Тест: та же ошибка API при чтении пикселей карточки. |
| `com.example.sostavslova.ui.game.GameNavigationTest` | `recreationAndBackKeepAnswersAndDiscardDraft`, `repeatedResumeKeepsSessionAndRecreationDiscardsDraft`, `unsubmittedAnswerIsDiscardedOnBackAndRecreation` | Приложение: восстановить режим KEYBOARD после recreate; `FocusRequester is not initialized` из эффекта до подкомпозиции поля. |
| `com.example.sostavslova.qa.CampaignJourneyUiTest` | `newInstallToFinaleRestartAndAllStars` | Тест / синхронизация (предварительно): пройти синтетическую кампанию с подсказками; ожидание DataStore 10 с. Исходный стек корутин не сохранил номер шага. |

- `GameInput.kt`: фокус и IME запрашиваются после `onGloballyPositioned`
  настоящего поля. Это устраняет вызов до его подкомпозиции внутри
  `WordInputRow`. Три сценария пересоздания прошли в целевом запуске.
- Новый тестовый `ui/CaptureToImageCompat.kt` и четыре использующих его файла
  (`AchievementComponentsTest`, `GameBannerTest`, `OwnedRiddlesUiTest`,
  `CampaignMapUiTest`): на API 24–25 снимок берёт `UiAutomation`, на API 26+
  сохраняется штатный Compose `captureToImage`. На старых API ожидается
  настоящий кадр через draw / frame-metrics, затем снимок обрезается по
  координатам узла. Без ожидания кадра первый целевой запуск получил четыре
  пиксельных несовпадения; после исправления синхронизации все десять
  целевых пиксельных тестов прошли (exit 0). Порог сравнения цветов, формы и
  времена анимаций не менялись. Нет SDK-пропусков, задержек или новых библиотек.
- `CampaignJourneyUiTest`: перед следующей подсказкой тест ждёт доступности
  кнопки и завершения обработки UI; сохранение DataStore может предшествовать
  снятию `hintLoading` в UI. На случай повторения ошибки сообщения теперь
  содержат ожидаемый ответ/шаг и фактический синтетический прогресс.
  Исходный стек не позволяет точно установить место первого тайм-аута;
  отдельное целевое исполнение прошло уже с исправленным фокусом, до
  добавления ожидания доступности. Увеличения тайм-аутов не было.

Следующий полный API 24 дал **340 tests / 1 failure / 0 errors / 0 skipped**,
exit 1, время 225.774 с, timestamp `2026-11-24T14:02:51`, SHA-256 XML
`397a33ab1811cb63cb5a79dab7e776b3b053a85e8afd2bda53f8f1301028cabc`
(`/tmp/stab06-api24-busy-race-results/debug/`). Единственное падение:
`GameNavigationTest#recreationAndBackKeepAnswersAndDiscardDraft`, ожидание
`awaitOpenedHint` сразу после первого нажатия подсказки (строка 145 того
снимка). Подтверждена ещё одна ошибка синхронизации теста: баннер ответа
появляется раньше снятия `busy`, а тест нажимал недоступную кнопку.
`GameNavigationTest.openHint()` теперь ожидает `assertIsEnabled()` перед
настоящим нажатием; все три места запроса подсказки используют этот helper.
Прежние ожидания открытия подсказки и сохранения остаются. Целевой набор
из всех шести `GameNavigationTest` и сценария кампании прошёл: **7 tests /
0 failures / 0 errors / 0 skipped**, exit 0, `BUILD SUCCESSFUL in 54s`.

### Итоговый снимок и повторная проверка

База по-прежнему `e1466a140f318e2150022fa821d649a2d7b42d2d`. Точный итоговый
снимок 209 файлов `app/src` и `core/src`, включая новый тестовый helper,
идентифицирован SHA-256 манифеста путей и содержимого:
`378dc030da7b7c6b226ad09184851b9fde4a89d709d261abe3b5e324c32989cc`.
Манифест сохранён в `/tmp/stab06-final-source-manifest.txt`; воспроизведение:

```python
from pathlib import Path
import hashlib, subprocess
paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others',
    '--exclude-standard', '-z', '--', 'app/src', 'core/src']).decode().split('\0')
manifest = ''.join(f'{hashlib.sha256(Path(p).read_bytes()).hexdigest()}  {p}\n'
    for p in sorted(set(paths) - {''}))
print(hashlib.sha256(manifest.encode()).hexdigest())
```

Итоговые debug APK: `app-debug.apk` SHA-256
`1f428edfdee2cac7f08c6d87e45f4eebff23f53a8938d7a2603bff1160ed9676`,
`app-debug-androidTest.apk` SHA-256
`eed0cf9b5ff836836a0ad59f716786ee5f18ce3325f27383100c338796b6ec03`.

Полный итоговый API 24:

```bash
ANDROID_SERIAL=emulator-5556 bash ./gradlew --offline :app:connectedDebugAndroidTest --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 3m 19s`. XML
`TEST-pf24(AVD) - 7.0-_app-.xml`: timestamp `2026-11-24T14:07:41`,
время 186.203 с, SHA-256
`40664b8dcdfb6878f238b7f726204f9f33c15049d61c9db26715c88b974eed6a`.
Атрибуты и элементы XML сверены: **340 tests / 0 failures / 0 errors /
0 skipped**. Копия: `/tmp/stab06-final-api24-results/debug/`.
`logcat -b events -d -s am_anr:I` не содержит событий ANR. Предупреждение
Gradle об отсутствии `additionalTestOutput` на API 24 не является пропуском:
все 340 тестов реально выполнены, XML и отчёт получены.

Полный итоговый API 36, та же конфигурация pf36 / Pixel 5 / Android 16,
холодная загрузка с `swiftshader`, локаль `en-US`, одно тестовое устройство:

```bash
ANDROID_SERIAL=emulator-5554 bash ./gradlew --offline :core:test :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 5m 19s`. XML
`TEST-pf36(AVD) - 16-_app-.xml`: timestamp `2026-11-24T14:14:01`,
время 303.577 с, SHA-256
`71a743f51ebfd6b767e371670aaa4e5262410dcc380c60da9d950350baf5e564`.
Атрибуты и элементы XML сверены: **340 tests / 0 failures / 0 errors /
0 skipped**. Копия: `/tmp/stab06-final-api36-results/debug/`.
`dumpsys activity lastanr` не содержит ANR с загрузки. Все дополнительные
проверки команды успешны; lint: 0 errors, 13 warnings. SHA-256 обоих APK и
манифеста исходников после прогона повторно совпали с приведёнными выше.

| Итоговый локальный прогон | tests | failures | errors | skipped | exit |
|---|---:|---:|---:|---:|---:|
| API 24 / Android 7.0 | 340 | 0 | 0 | 0 | 0 |
| API 36 / Android 16 | 340 | 0 | 0 | 0 | 0 |

### Внешний connected gate

Для ручного исправления заблокированной задачи используется штатная команда:

```bash
ANDROID_SERIAL=emulator-5556 bash orchestrator/run.sh --mark-done STAB-06 --no-push
```

Она заново выполняет заданный в очереди gate:
`bash ./gradlew --offline :app:connectedDebugAndroidTest --console=plain`
на выделенном pf24, с той же холодной загрузкой и программным рендерингом.
Это самостоятельное исполнение оркестратора после двух итоговых локальных
прогонов; первая успешная команда модели его не заменяет. Конфигурация
gate и очередь не менялись.

Подтверждение завершения внешнего gate — запись STAB-06 с успешным gate
в `orchestrator/PROGRESS.md`, создаваемая оркестратором только после exit 0,
и локальный лог
`orchestrator/.runtime/logs/20261124T131923.764349Z/STAB-06.gate.0.log`.
Если такой записи нет или лог содержит failure, внешний gate не подтверждён.
Итоговый commit исправлений и отчёта создаёт этот же процесс; push отключён.
Полный исходный и итоговый XML остаются раздельными: текущий
`app/build/outputs/androidTest-results/connected/debug/` после gate относится
к новому внешнему прогону, а не к двум локальным копиям выше.

Проверка документации: `bash orchestrator/run.sh --check docs` и
`git diff --check`; без изменения правил оркестратора.

### Границы этой проверки

Реальные телефоны, ручная приёмка владельцем, TalkBack, перенос между двумя
телефонами и release APK этим debug-прогоном не проверены. Публикационный
допуск из прохождения instrumented-тестов не следует.


## STAB-07 — финальная сборка кандидата, 2026-11-24

**Статус: проверки и сборка PASS; release — UNSIGNED / NOT FOR PUBLICATION.
Публикационная готовность BLOCKED до штатной подписи владельцем.**
Этот раздел дополняет доказательства STAB-06; прежние FAIL и последующие
исправления выше сохранены. Успешная сборка не означает разрешения публикации.

### Проверенный commit, конфигурация и неизменность

Проверен commit `cd7e02b274061f4d033eaf5b8ea3a01bb23811c3`
(`STAB-06: Выполнить реальные Android instrumented-тесты`), ветка `main`.
До начала рабочее дерево чистое. STAB-07 меняет только этот документ;
код, тесты, данные, версии, зависимости, ключи, юридические решения и
защищённые пути не изменяются. Git-операции записи и публикация не выполнялись.

Окружение: Linux, Temurin JDK 21.0.13+9-LTS, Gradle 9.3.1, AGP 9.1.1,
Kotlin 2.2.10, Android Build Tools 36.0.0; compileSdk/targetSdk 36,
minSdk 24. Зависимости сборок взяты из локального кэша (`--offline`).
Логи и вспомогательные проверки — `/tmp/stab07-evidence/`, вне репозитория.
Для Python установлен `PYTHONDONTWRITEBYTECODE=1`.

На 2026-11-24 около 14:20 UTC публичный GitHub API
`GET https://api.github.com/repos/volmir/words-combination/releases`
вернул последним опубликованным выпуском
[1.0.4 (5)](https://github.com/volmir/words-combination/releases/tag/1.0.4),
без опубликованной 1.1.0. README и заголовок очереди также называют 1.1.0 (6)
кандидатом. Проверка других каналов распространения не проводилась; если
владелец уже опубликовал (6) в другом канале, выбор новой версии остаётся
обязательным блокирующим условием. Версия не менялась.

### Фактический PASS STAB-06 на том же коде

Read-only проверены запись `gate: passed`, `manual: true` STAB-06
от 14:18:29 UTC в `orchestrator/PROGRESS.md` и завершение лога
`orchestrator/.runtime/logs/20261124T131923.764349Z/STAB-06.gate.0.log`:
`Finished 340 tests`, `BUILD SUCCESSFUL in 3m 21s`.
XML внешнего gate API 24 имеет 340 testcase, tests=340, failures=0,
errors=0, skipped=0, timestamp `2026-11-24T14:18:28`, SHA-256
`eba270d5390458d617a7220747840b3cf8f506a370c0ed7b754c10d738b7c2e4`.
Повторно разобраны также сохранённые итоговые XML API 24 и API 36:
оба 340/0/0/0, SHA совпадают с предыдущим разделом отчёта.

Все 209 файлов из `/tmp/stab06-final-source-manifest.txt` сравнены с текущими
байтами: расхождений нет (exit 0). Повторно построенный по приведённому выше
алгоритму манифест имеет тот же SHA-256
`378dc030da7b7c6b226ad09184851b9fde4a89d709d261abe3b5e324c32989cc`.
Это связывает фактически испытанные production-исходники и тесты с текущим
commit, созданным после успешного gate. После этого gate production-код
не менялся. В STAB-07 connected-тесты заново не запускались.

### Целостность кампании, словаря и утверждённых загадок

Фактические SHA сверены Python/hashlib с заголовком очереди и manifest:

| Файл | SHA-256 |
| --- | --- |
| `data/words.txt` | `a8964442ca99334cf3c1ae198fc5a61e476c52ac250992abd3002d75f4f960f5` |
| `data/campaign.json` | `995e8c8d454dfe1c50a7c68b8c6d3308716263d41183e4bf6b06fd73990451e4` |
| `data/manifest.json` | `114c5551c9f1c6d3976efdb9b9c88a7bc1ca003a1b88f6360d232589768d96f7` |
| `data/owned_riddles.json` | `05e3c05099fe38648834a6b9f70f458cf51238edc7bacdc85d8af3932ad11f63` |
| `docs/owned_hints.json` | `214a14ca721cc33d7abc1933e5ecdd605e722441910dbecf4a137b4bd70e784d` |
| `docs/OWN_HINTS_OWNER_APPROVAL.json` | `2856445f4405c5f3b7fb6b1865f4629795c8a4f1fac5451237210da191fda63d` |
| `docs/OWN_HINTS_MANUAL_AUDIT.json` | `00f49e4fae3dd39f32987ba065de6741c538532d8a7eee7801ad0a5c2a97d3c4` |

Ровно 20 103 строки словаря = `manifest.words`, 15 уровней, 49 заданий/возможных
звёзд; runtime содержит 11 T4-текстов для 6 заданий. Все шесть поставляемых
файлов данных в `app/src/main/assets` побайтово совпали с `data/`.
`verifyOwnedHints` дополнительно проверил утверждённые source/approval/audit,
категории и воспроизводимость загадок, без записи/регенерации данных.
`manifest.json` служит входом сборки и в APK не поставляется — это штатный
набор `syncDictionaryAssets`, а не пропущенный asset.

### Реально выполненные проверки и exit codes

Полный gate выполнен составляющими профиля `checks.full` из текущего
`orchestrator/config.json`; Gradle дополнительно ограничен `--offline`.
Обёртка `--check full` и очередь агентом не запускались: внешний gate
выполняет оркестратор. Команды и критерии профиля не ослаблены.

| Команда из корня проекта | Exit | Результат |
| --- | ---: | --- |
| `bash ./gradlew --offline :app:verifyDictionaryLicenses :app:verifyOwnedHints :app:verifyRuntimeNotices :app:verifyPrivacyPublication :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain` | 0 | BUILD SUCCESSFUL, 7s; все имена задач существуют; lint 0 errors, 13 warnings |
| `python3 -m unittest discover -s tools/tests` | 0 | 594 теста, OK, 113.599s |
| `python3 -m unittest discover -s orchestrator/tests` | 0 | 99 тестов, OK, 42.271s; тестовые фикстуры во временных каталогах |
| `bash ./gradlew --offline :core:test :app:assembleDebug --console=plain` | 0 | BUILD SUCCESSFUL, 6s; обе задачи UP-TO-DATE |
| `bash ./gradlew --offline :app:assembleRelease --console=plain` | 0 | BUILD SUCCESSFUL, 1m 29s; 28 задач исполнены, 30 UP-TO-DATE |

Логи строк таблицы: `/tmp/stab07-evidence/check-1.log` … `check-5.log`,
коды возврата записаны непосредственно после subprocess в `results.json`.
Core XML: 36 наборов, 262 tests / 0 failures / 0 errors / 0 skipped;
это сохранённые результаты, принятые Gradle как UP-TO-DATE, а не новый прогон.
Вывод `missing.json.gz` в tools относится к успешной отрицательной проверке;
итог unittest — OK. Release выполнил `verifyDefinitionsAudit` (`release_audit: ok`),
`verifyReleaseSigning`, `minifyReleaseWithR8`, `optimizeReleaseResources`,
`lintVitalRelease`, `packageRelease` и `assembleRelease`.

### Проверка фактических APK

`aapt2` и `apksigner` взяты из
`/home/vladimir/.local/share/words-combination/android-sdk/build-tools/36.0.0/`.
Для каждого debug/release APK выполнены `aapt2 dump badging APK`,
`aapt2 dump permissions APK` и
`aapt2 dump xmltree --file AndroidManifest.xml APK`: exit 0.
Оба APK: applicationId `io.github.volmir.wordscombination`, versionName
`1.1.0`, versionCode `6`, minSdk `24`, targetSdk `36`.
`uses-permission` отсутствуют полностью, включая INTERNET.
Debug имеет `debuggable=true`; release этого флага не имеет.
`output-metadata.json` подтверждает variantName debug/release соответственно.
В `build.gradle.kts` release использует `isMinifyEnabled=true`,
`isShrinkResources=true`, optimize proguard и только отдельный signingConfig
release при его наличии. Настройки не менялись; факт R8 подтверждён логом.

ZIP-проверка Python (`zipfile`, `hashlib`, `gzip`) подтвердила все 12 runtime
assets и равенство содержимого исходникам: `words.txt`, `campaign.json`,
`levels.json`, `owned_riddles.json`, `word_familiarity.txt`, `word_sources.jsonl`,
`LICENSE`, `THIRD_PARTY_NOTICES.txt`, `Apache-2.0.txt`, `CC-BY-SA-4.0.txt`,
`LIBRARY_NOTICES.txt`, `RUNTIME_LIBRARIES.json`.
AGP распаковывает исходный `word_sources.jsonl.gz` в `word_sources.jsonl`;
сравнение выполнено с декомпрессированным источником. В release дополнительно
присутствуют штатные `assets/dexopt/baseline.prof` и `baseline.profm`.
Первоначальные assertions вспомогательного ZIP-скрипта дали exit 1 на этих
особенностях упаковки; после корректного учёта gzip и baseline profiles
полная проверка равенства дала exit 0. Исходники/сборка ради неё не менялись.

В обоих упакованных манифестах `allowBackup=true`, `fullBackupContent`
ссылается на ресурс `0x7f0d0000`, `dataExtractionRules` — `0x7f0d0001`.
Для debug `aapt2 dump xmltree --file res/xml/backup_rules.xml APK` и
аналогичная команда для `res/xml/data_extraction_rules.xml` дали exit 0.
Для release те же исходные ZIP-пути дали exit 1 (`failed to find file`):
оптимизация переименовала их. `aapt2 dump resources APK` (exit 0) разрешил
ресурсы в `res/Qq.xml` и `res/4j.xml`; `aapt2 dump xmltree --file` по каждому
фактическому пути дал exit 0. Проверено содержимое обоих XML: девять
исключений всех доменов для Android 7–11 и cloud-backup; device-transfer
на Android 12+ включает только domain=file, path=datastore/progress.json.
Это статическая проверка правил, не перенос между физическими телефонами.

SHA-256 полученных/проверенных APK:

| Артефакт | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `1f428edfdee2cac7f08c6d87e45f4eebff23f53a8938d7a2603bff1160ed9676` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `54b5a66ae151e25c21cc553d160381c8ec0cce5d48caf64337dc32d1cd1ba26e` |
| Существующий STAB-06 `app-debug-androidTest.apk` (в STAB-07 не пересобирался) | `eed0cf9b5ff836836a0ad59f716786ee5f18ce3325f27383100c338796b6ec03` |
| Скачанный опубликованный `WordsCombination-v1.0.4.apk` | `3df5c933536a1c6201f071fe9873e009a26c06da3d8dcf0068c625f210082574` |

### Подпись и блокирующие условия

Для debug, release и скачанного с GitHub предыдущего APK выполнена команда
`apksigner verify --verbose --print-certs APK`:

| APK | Exit | Фактический результат |
| --- | ---: | --- |
| Опубликованный 1.0.4 (5), скачан в `/tmp/stab07-evidence/previous-published-1.0.4.apk` | 0 | v2 verified, CN=Words Combination; SHA-256 сертификата `a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353` |
| Debug 1.1.0 (6) | 0 | v2 verified, CN=Android Debug; SHA-256 сертификата `b38eafea4f467575d60c47578e0b7ac69750c64b7f0a1de66fb30c7602d15264` |
| Release 1.1.0 (6) | 1 | DOES NOT VERIFY; Missing META-INF/MANIFEST.MF; сертификата нет |

`verifyReleaseSigning` явно сообщил: release-ключ не настроен, артефакт
неподписанный и не предназначен для публикации или установки. Debug fingerprint
не совпадает с опубликованным и не заменяет release-подпись. Сравнение
fingerprint кандидата с предыдущей версией пока невозможно: у release
кандидата нет сертификата. Секретные файлы и значения не читались/не логировались
агентом, ключи не создавались; Gradle выполнил штатную проверку конфигурации.

Остаются условия до публикационного допуска:

1. Владелец обеспечивает штатную release-подпись; повторная проверка
   `apksigner` должна пройти и подтвердить fingerprint предыдущего APK.
   SHA подписанного APK фиксируется заново: приведённый SHA относится
   исключительно к неподписанному файлу.
2. Проверить установку подписанного кандидата поверх опубликованного (5)
   с валидным прогрессом и сохранением достижений/звёзд; в STAB-07 не выполнено.
3. Заполнить и принять [ручной чек-лист](RELEASE_CHECKLIST_1_1.md), включая
   TalkBack, физические устройства и перенос между телефонами; автоматические
   debug-тесты не означают прохождения ручной приёмки.
4. Выполнить gate воспроизводимости кандидата из двух чистых клонов и
   сопоставить подписанный APK с проверенным commit; исторические результаты
   1.0.x не являются доказательством для 1.1.0. В STAB-07 не запускалось.
5. Отдельная независимая публикационная приёмка прав, QA, подписи и канала
   распространения; прежние юридические решения не изменены. Автоматической
   публикации, создания тега и перехода к STAB-08 нет.

### Проверка дополненного отчёта

`PYTHONDONTWRITEBYTECODE=1 bash orchestrator/run.sh --check docs` — exit 0:
UTF-8, ссылки и индекс корректны, три теста соответствия SPEC/кампании — OK.
`git diff --check` — exit 0. После сборок повторно подтверждён неизменный
SHA манифеста 209 исходных файлов STAB-06. `git status --short` показывает
изменение только `docs/RELEASE_1_1_TEST_EVIDENCE.md`; diff по `app`, `core`,
`data`, `legal`, `orchestrator` пуст. Заключение STAB-07: сборки и проверки
успешны, подписанного release-кандидата нет; до действия владельца — BLOCKED.
