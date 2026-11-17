# PRE04-01 — собственные подсказки и проверка размещения текста

Дата: 2026-11-17. Целевые два метода G02: **20 PASS / 0 FAIL**.
После измерений G01 исправлена тестовая проверка ширины; production-код,
данные, лицензии, типографика и параметры матриц не изменены.

## Изменённые файлы

- `CampaignGameUiTest.kt`: две fixture собственных подсказок, проверки
  происхождения/маскирования и размещения текста.
- `FoundWordsSectionTest.kt`: та же исправленная проверка размещения.
- `TextLayoutAssertions.kt`: общий helper для фактических границ строк.
- `TextLayoutAssertionsTest.kt`: шесть регрессионных сценариев helper.
- Этот отчёт и отчёты диагностики [букв](PRE04_FIX_G01_LETTERS.md)
  и [заголовков](PRE04_FIX_G01_HEADERS.md).

## Контракт собственных подсказок — G02

Обновлены `riddlesRemainUnderTaskAndReflectSolvedUpdates` и
`accumulatedHintStepsCostAndZeroBudgetAreVisible`. Прежние fixture
выставляли `wiktionary=true` и ожидали удалённую атрибуцию CC BY-SA.
Теперь обе используют синтетический `Definition` с `origin="own"`, пустым
`source` и `isWiktionary=false`. Текст сочинён для теста, без словарных статей.
Ответ и однокоренное слово маскируются настоящим `DefinitionHint.prepare`.

Это соответствует маршруту `GameViewModel`: фильтрации `isOwnedHint`
и маскированию через core. Для own проверяется отсутствие блока legacy
Wiktionary-атрибуции. Отдельно проверяется действующий ресурс
`hint_definition_source`: «Собственная подсказка · CC0-1.0.
Скрыты ответ и однокоренные слова.».

Сохранены и дополнены проверки:

- происхождение own, пустой source, отсутствие Wiktionary-признака;
- наличие маски и отсутствие корня ответа, Wiktionary, CC BY-SA и URL
  в текстах и описаниях доступности unmerged-дерева, включая обновления;
- расположение загадки внутри T4, переходы статусов
  1/2 → 2/1 → 3/0 (разгаданы/не разгаданы), буквенный резерв;
- порядок «тема → описание → длина», закрытая четвёртая часть,
  счётчик 2/4 → 3/4;
- бесплатный шаг при нулевом бюджете и один вызов обработчика,
  затем цена 1 при остатке 0, отключённая кнопка и объяснение бюджета;
- live region и проверка размещения всех текстов.

## Почему потребовалось исправление G01

Первый прогон после актуализации fixture дал 10 PASS / 10 FAIL:
все падения в последнем `assertTextFits`. Диагностика установила ложное
срабатывание `didOverflowWidth`: у «К» строка и Text имеют ширину 32 px,
а кэшированный MultiParagraph — 99 px. У заголовка строка 808,6914 px
помещается в Text 809 px, но MultiParagraph имеет ширину 902 px.
`didOverflowHeight=false`, потерянных строк и многоточия нет.

Helper теперь проверяет все четыре границы каждой строки, потерю строк
и многоточие. Допуски не добавлены; буквы и заголовки остаются в обходе.
Новые тесты подтверждают обнаружение настоящего обрезания по обеим осям,
потерянных строк и многоточия, а также корректное размещение буквы
при широком абзаце и многострочного заголовка: **6 PASS**.

## Выполненные проверки

```bash
bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash ./gradlew :app:verifyDictionaryLicenses --rerun-tasks --console=plain
python3 tools/check_privacy_publication.py --root /var/www/words-combination --require-generated
git diff --check
```

Сборка debug и androidTest **PASS**, после правок тестовый APK пересобран.
Лицензии **PASS**: verifyOwnedHints — 39 own / 0 wiktionary / 11 T4,
publication_status=approved; runtime notices verified.
Проверка privacy после обновления отчётов и `git diff --check`: **PASS**.

Физическое устройство Android 13 / MIUI. Установка через `adb install -r -t`
сохранила данные, очистка/удаление приложения не выполнялись. Все прогоны
используют существующий `WordsTestRunner`; app-op 10021 восстановлен
в исходный `ignore` после каждого завершённого запуска.

| Проверка после исправления | Результат |
| --- | --- |
| Регрессии измерения `TextLayoutAssertionsTest` | 6 PASS |
| Два метода G02 и FoundWords при 320 dp, fontScale=1/2, legacy=true | 6 PASS |
| Два метода G02: вся матрица 320/360/400 dp × 1/1,5/2 + 840 dp × 1 | 20 PASS |
| Повторная расширенная матрица G01/G02 с включённым экраном | 67 PASS / 1 FAIL |

Первый расширенный прогон завершился **61 PASS / 7 FAIL**: один FAIL
видимости в allSevenTasks при 840 dp и шесть `No compose hierarchies`
в FoundWords. При диагностике обнаружены `mWakefulness=Dozing` и
`SCREEN_STATE_OFF`. Для повторной проверки временно включено удержание
экрана при питании. После завершения проверок исходное значение
`stay_on_while_plugged_in=0` восстановлено и проверено отдельным запросом.

Повторная расширенная матрица: **67 PASS / 1 FAIL**, 264,52 с, без пропусков.
Все 20 вариантов G02 и все 18 вариантов FoundWords PASS.
Шесть ошибок отсутствия иерархии не повторились при включённом экране.
Оставшийся FAIL — `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes`
при 840 dp / fontScale=1, строка 103: `assertIsDisplayed` для найденного
«кот» после раскрытия группы. Он повторяется при `Awake` / `mStayOn=true`,
поэтому связывать его с выключением экрана нельзя. Предшествующие проверки
размещения текста в этом сценарии проходят; это отдельная находка,
причина которой не установлена. Assertion сохранён, ошибка не объявлена PASS.
Этот метод не входит в целевые два метода PRE04-01; критерий их 20 PASS
выполнен. Дальнейшая диагностика видимости остаётся отдельной задачей.

Воспроизведение расширенной матрицы после сборки и установки:

```bash
ui_classes='com.example.sostavslova.ui.game.CampaignGameUiTest,com.example.sostavslova.ui.game.FoundWordsSectionTest'
ui_methods='.*(riddlesRemainUnderTaskAndReflectSolvedUpdates|accumulatedHintStepsCostAndZeroBudgetAreVisible|allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes|hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange|finaleSurvivesScreenRestorationAndBothActionsFit|emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions).*'
ui_runner='io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner'
adb shell "am instrument -w -r -e class $ui_classes -e tests_regex '$ui_methods' $ui_runner"
```

Код возврата ADB сам по себе не является PASS: проверены конечные статусы
каждого теста, их количество и итог instrumentation.
Локальные логи (не коммитятся): `/tmp/pre04-layout-assertions.instrumentation`,
`/tmp/pre04-g01-fixed-smoke.instrumentation`,
`/tmp/pre04-g01-g02-matrix.instrumentation`,
`/tmp/pre04-g01-g02-awake-matrix.instrumentation`.

## Границы проверки и завершение

Полный набор Android-тестов не запускался; другие группы PRE-04 не проверены.
Полный gate и результат завершения PRE04-01 фиксирует оркестратор
при `--backend codex --mark-done PRE04-01`; см. `orchestrator/PROGRESS.md`
и локальные gate-логи. Checkpoint вручную не редактировался.
