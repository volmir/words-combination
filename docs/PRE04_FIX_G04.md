# PRE04-04 — доступность звёзд (G04)

Дата: 2026-11-17. Итог: **native PASS, 1 тест, 0 ошибок, 3,943 с**.
Исправлен устаревший matcher в `LevelsLayoutTest`; production UI не менялся.

## Причина и проверка деревьев

Исторические четыре падения проверяли `ContentDescription = «Звёзды»`
непосредственно у `level_card_1`. В фактическом merged-дереве карточка
является кнопкой с `StateDescription`, а вложенный `StarsRow` остаётся
самостоятельным merging-node. Его описание не переносится на карточку.

Снятые во время итогового прогона свойства:

| Узел | Merged | Unmerged |
| --- | --- | --- |
| `level_card_1` | Role Button; StateDescription «Получено 0 из 1 звезды» → «Получено 1 из 1 звезды»; текст уровня и статуса | Role Button; тот же динамический StateDescription; текстовые узлы отдельно |
| Ряд внутри `level_card_1` | ContentDescription «Звёзды»; тот же динамический StateDescription; MergeDescendants=true | Те же свойства; внутри декоративная иконка `stars_position_0` без описания |

Новый matcher ищет ряд по `hasContentDescription(label)` и
`hasAnyAncestor(hasTestTag("level_card_1"))`. В обоих деревьях проверяются
описание именно этого ряда и количество звёзд на карточке и ряду до и после
обновления состояния. Максимум берётся из fixture кампании, а не фиксируется
литералом `1`. Существующие проверки статусов, клика, сетки, размеров целей
48 dp, текста и последнего уровня сохранены.

## Android accessibility и TalkBack

Дополнительно тот же сценарий через `UiAutomation.rootInActiveWindow`
проверяет видимый label и динамический state в Android accessibility tree
целевого пакета. Android экспортирует описание ряда в виртуальный дочерний
узел, а StateDescription — в его предка. Проверка учитывает эту структуру;
чтение состояния через уже доступный `AccessibilityNodeInfoCompat`
сохраняет совместимость с Android до API 30 без новых зависимостей.

В диагностическом дереве при нулевом прогрессе обнаружена структура:

```text
android.view.View: state=Получено 0 из 1 звезды, visible=true
  android.view.View: content=Звёзды, visible=true
```

Итоговый assertion прошёл для 0/1 и 1/1. Данные доступны Android
accessibility-службе, следовательно, доступны TalkBack через этот API.
Нарушение доступности в рамках G04 не обнаружено. Ручное прослушивание
TalkBack не выполнялось: при диагностике включённых accessibility-служб нет
(`enabled_accessibility_services = null`); настройки служб не менялись.

Два предварительных запуска этого же native-сценария завершились FAIL
на добавленном в ходе исследования Android assertion: первоначально он
ошибочно требовал label и state на одном Android-узле. Второй запуск с
диагностикой установил описанную выше структуру виртуальных узлов. Это
ошибка дополнительной тестовой проверки, а не production UI. После
исправления обхода дерева итоговый запуск прошёл полностью. Другие
сценарии и искусственные display-варианты не запускались.

## Среда, команды и результат

Физический смартфон Android 13 / MIUI. До и после прогона:

- `adb shell wm size`: Physical size 1080x2400, override отсутствует.
- `adb shell wm density`: Physical density 440, override отсутствует.
- `adb shell settings get system font_scale`: 1.0.

Использованы естественные constraints устройства после PRE04-02;
параметризация 320/840 dp и масштабов 1/2 не возвращалась.

Выполнены сборки (включая повторные после правок androidTest), все PASS:

```bash
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest --console=plain
bash ./gradlew :app:assembleDebugAndroidTest --console=plain
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell input keyevent KEYCODE_WAKEUP
adb shell am instrument -w -r -e class 'com.example.sostavslova.ui.levels.LevelsLayoutTest#levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid' io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
git diff --check
```

Итог instrumentation: `numtests=1`, статус метода `0`, `OK (1 test)`.
Код возврата ADB сам по себе не использовался как признак успеха.
Существующий runner временно разрешил MIUI app-op 10021 и восстановил
`ignore`; это подтверждено логом и отдельным `adb shell cmd appops get
io.github.volmir.wordscombination 10021` после прогона.

Локальные логи вне репозитория:

- `/tmp/pre04-04-build.log` — последняя сборка, BUILD SUCCESSFUL.
- `/tmp/pre04-04-native.instrumentation` — первый диагностический FAIL.
- `/tmp/pre04-04-diagnostic.instrumentation` — FAIL с Android-деревом.
- `/tmp/pre04-04-final.instrumentation` — итоговый PASS.
- `/tmp/pre04-04-semantics.log` — merged/unmerged до и после обновления.

## Ограничения и область изменений

Изменены только `app/src/androidTest/kotlin/com/example/sostavslova/ui/levels/LevelsLayoutTest.kt`
и этот отчёт. Full gate оставлен оркестратору; полная instrumented-сюита,
другие Android-версии и ручная озвучка не проверялись. Production, данные,
защищённые пути, Git-история и параметры экрана не менялись. Следующие
задания не выполнялись.
