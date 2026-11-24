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
