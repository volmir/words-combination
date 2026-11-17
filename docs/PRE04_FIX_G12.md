# PRE04-15 — отсутствие Compose-root в G12

Дата: 2026-11-17. **Статус: результат принят владельцем с ограничением
по исторической причине F81; завершение оформляется после полного gate.**

Точный отказ `No compose hierarchies` воспроизведён в
`GameNavigationTest.hintAndBackKeepSavedAnswers` при ожидании feedback после
отправки ответа. В контрольном опыте его вызывает выключение экрана:
Activity переходит в STOPPED, и Compose test registry теряет активный root,
хотя AndroidComposeView и его semantics продолжают существовать.
На бодрствующем устройстве тест проходит отдельно и после минимального
предшествующего класса. Однако исходный F81 не содержит снимка состояния
экрана/lifecycle. Доказать, что именно выключение экрана вызвало **исторический**
F81, этими опытами нельзя. Совпадение сообщения и стека не является таким
доказательством. Первоначальный BLOCKED снят решением владельца,
зафиксированным в конце отчёта; историческая причина остаётся неустановленной.

## Что изменено

- `app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameRootDiagnostics.kt`:
  добавлено диагностическое правило. Lifecycle callback регистрируется до
  запуска Activity; внутренний TestWatcher снимает состояние при отказе
  **до** teardown Compose rule, закрытия ActivityScenario и тестового контейнера.
  Ошибка самой диагностики логируется и не заменяет исходное исключение.
- `app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameNavigationTest.kt`:
  подключены наружное правило lifecycle и внутренний watcher. Необязательный
  аргумент instrumentation `-e g12Trace true` включает дополнительный снимок
  непосредственно перед ожиданием feedback. При обычном запуске снимок
  делается только при отказе; переходы lifecycle записываются всегда.

Диагностика читает окна процесса и `ViewRootForTest`, а также `dumpsys power`,
`window`, `activity activities`, `activity processes`. Она не подменяет
Compose root, не ищет альтернативный root для assertions, не повторяет
упавшее действие и не превращает ошибку в успешное ожидание.
Production-код, runner, assertions, тайм-ауты, данные и настройки питания
не изменены. Исправления поведения без доказанного дефекта не вносились.

## Наблюдения в момент отказа

Среда: физическое устройство Android 13 / API 33, MIUI, штатные параметры
экрана; JDK 21. Runtime-зависимость `ui-test-android:1.7.2` подтверждена
`dependencyInsight`.

Первый запуск выполнен в состоянии, в котором устройство было обнаружено:
`mWakefulness=Dozing`. Он остановился в `setUp` с ComposeTimeoutException,
поэтому **это не воспроизведение исходной точки F81**. Снимок показал
STOPPED и существующий, но неактивный Compose view. После
`KEYCODE_WAKEUP` и `wm dismiss-keyguard` отдельный тест прошёл.

Для проверки именно точки F81 временный диагностический hook перед
`awaitCorrectAnswer` отправлял `input keyevent KEYCODE_SLEEP`, затем ожидал
тишины accessibility через `uiAutomation.waitForIdle(500, 5000)`.
`Thread.sleep`, увеличение тайм-аутов и подмена root не применялись.
Hook и аргумент `g12SleepBeforeFeedback` удалены из итогового исходника;
копия экспериментального теста сохранена только локально:
`/tmp/pre04-15-sleep-probe.kt`.

В этом опыте подготовка, открытие уровня и отправка ответа уже состоялись.
Получено именно `IllegalStateException: No compose hierarchies found`,
стек: `awaitText` → `awaitCorrectAnswer` → `hintAndBackKeepSavedAnswers`.
Watcher снял следующее состояние до закрытия Activity:

| Запрошенный сигнал | Наблюдение |
|---|---|
| Activity lifecycle | PRE_ON_CREATE → CREATED → STARTED → RESUMED → PAUSED → STOPPED |
| Текущее окно | `mCurrentFocus=null`; `mFocusedApp` указывает на MainActivity; DecorView прикреплён, windowVisibility=8, focus=false |
| Compose roots | AndroidComposeView прикреплён; shown=false, resumed=false; root semantics содержит 2 дочерних узла; запрос теста сообщает отсутствие активных иерархий |
| Пересоздание Activity | Тот же экземпляр Activity; повторного PRE_ON_CREATE/CREATED нет; changingConfigurations=false, destroyed=false, finishing=false |
| Runner | WordsTestRunner активен в том же процессе; ActiveInstrumentation: mFinished=false, watcher и UiAutomationConnection существуют; TestRunner ещё не сообщил завершение теста |
| Питание | `mWakefulness=Dozing`, `mSleeping=true` |

DESTROYED с finishing=true появляется **после** диагностического снимка,
при штатном teardown упавшего теста. Это не причина потери root.
MIUI app-op 10021 был разрешён runner до запуска и восстановлен после
завершения. Activity была создана и возобновлена, тест дошёл до отправки
ответа. Оснований считать этот отказ повторением исходного зависания
ActivityScenario до выполнения instrumentation нет.

Для объяснения механизма проверен bytecode реально выбранной версии
Compose (`javap -p -c`):
`ComposeRootRegistry.StateChangeHandler.onStateChanged` регистрирует root
на ON_RESUME, а на остальных lifecycle events снимает регистрацию.
Таким образом, существующий AndroidComposeView не гарантирует активную
Compose-иерархию для тестового запроса. В воспроизведённом опыте root
исчезает из активного набора вследствие PAUSED/STOPPED, а не из-за отсутствия
`setContent`, пересоздания или завершения runner.

## Отдельный тест и минимальный префикс

Исходный сохранённый logcat показывает непосредственно перед F81
`GameLayoutTest` (тогда два параметра fontScale), затем
`hintAndBackKeepSavedAnswers`. Сейчас GameLayoutTest имеет один сценарий
на естественных параметрах устройства. Для сравнения выбран этот ближайший
предшествующий класс, без запуска всей прежней сюиты из 379 тестов.

Общий вызов:

```sh
adb shell am instrument -w -r -e g12Trace true \
  -e class '<выборка>' \
  io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
```

Выборки:

- Отдельно: `com.example.sostavslova.ui.game.GameNavigationTest#hintAndBackKeepSavedAnswers`.
- Префикс: `com.example.sostavslova.ui.game.GameLayoutTest,com.example.sostavslova.ui.game.GameNavigationTest#hintAndBackKeepSavedAnswers`.

На бодрствующем устройстве оба запуска прошли; порядок TestRunner
подтвердил выполнение GameLayoutTest перед G12. В обоих случаях снимок
перед feedback показывает RESUMED, фокус MainActivity, показанный
AndroidComposeView с resumed=true и незавершённый активный runner.
Зависимость отказа от этого префикса не обнаружена.

Исходный logcat `.local/ui-hang-20261116/fixed-full-connected.logcat`
содержит начало F81 в 22:31:34.875 и отказ в 22:31:42.622, но не содержит
lifecycle/window/power в этой точке. Поэтому гипотеза об историческом
выключении экрана остаётся гипотезой. Дефект приложения или очистка
Compose registry предшествующим тестом не доказаны.

## Проверки и локальные артефакты

- `bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest --console=plain`
  — BUILD SUCCESSFUL; итоговый лог `/tmp/pre04-15-final-build.log`.
- Тестовый APK установлен через `adb install -r` — Success.
- `bash ./gradlew :app:dependencyInsight --configuration debugAndroidTestRuntimeClasspath --dependency ui-test-android --console=plain`
  — BUILD SUCCESSFUL, выбрана 1.7.2; `/tmp/pre04-15-compose-version.log`.
- Исходное состояние Dozing: FAIL в setUp (зафиксированный опыт, не пропуск);
  `/tmp/pre04-15-as-found.instrumentation`, `.logcat`.
- Контроль с выключением экрана перед feedback: точный FAIL G12;
  `/tmp/pre04-15-sleep-feedback.instrumentation`, `.logcat`.
- Первые контрольные PASS: отдельно 1 тест / 7,143 с;
  с префиксом 2 теста / 10,726 с;
  `/tmp/pre04-15-alone.instrumentation`, `/tmp/pre04-15-prefix.instrumentation`.

Итоговые проверки после удаления sleep-hook и сборки окончательного APK:

| Выборка | Результат | Локальный instrumentation-лог |
|---|---|---|
| Только G12, `g12Trace=true` | OK (1 test), 8,453 с | `/tmp/pre04-15-final-alone.instrumentation` |
| GameLayoutTest → G12, `g12Trace=true` | OK (2 tests), 11,116 с | `/tmp/pre04-15-final-prefix.instrumentation` |
| Весь GameNavigationTest без дополнительных аргументов | OK (6 tests), 44,527 с | `/tmp/pre04-15-final-class.instrumentation` |

Рядом сохранены соответствующие `.logcat`; фильтр G12Roots содержит
lifecycle и диагностические снимки. Локальные сырые логи не добавляются
в репозиторий; в этом отчёте нет идентификаторов устройства, процессов
или личных путей. `git diff --check` — без ошибок. После опытов экран
возвращён в исходное выключенное состояние командой KEYCODE_SLEEP;
настройки screen_off_timeout и stay_on_while_plugged_in не менялись.

На первом этапе полный gate оставлен оркестратору. Полная androidTest-сюита, воспроизведение
исторического порядка со старыми параметрами и другие устройства/API не
проверялись. Для установления причины исходного F81 нужен его естественный повтор со
снимком G12Roots; искусственно вызванный сон не заменяет это доказательство.
Защищённые пути и пользовательский DataStore не изменялись.

## Дополнительная проверка после BLOCKED

Повторная проверка 2026-11-17 подтвердила ограничение исходных артефактов:
`run_connected.py` сохранял logcat с фильтром TestRunner, AndroidRuntime,
AndroidXTracer и WordsTestRunner. В окрестности F81 в сохранённом файле
есть только сообщения TestRunner; снимков питания, окон и lifecycle нет.
Поэтому отсутствие этих событий в файле не доказывает, что Activity
оставалась RESUMED или что экран был включён.

Без изменений исходников и настроек устройства выполнены пять пар запусков
с `g12Trace=true`: G12 отдельно и GameLayoutTest → G12.
Все **10 запусков / 15 выполнений тестов — PASS**, суммарно 106,152 с.
В снимках перед feedback видны RESUMED, показанный активный Compose view
и фокус MainActivity. Естественный повтор F81 не получен.

Локальные результаты: `/tmp/pre04-15-review/results.json`; рядом для каждой
попытки сохранены `.instrumentation` и `.logcat`. Сборка
`:app:assembleDebugAndroidTest` — BUILD SUCCESSFUL
(`/tmp/pre04-15-review-build.log`).

Экран перед запуском пробуждён, после проверки возвращён в обнаруженное
состояние Dozing. Значения screen_off_timeout=300000,
stay_on_while_plugged_in=0 и font_scale=1.0 не менялись;
runner восстановил исходный app-op 10021. Данные приложения не очищались.
Полный gate в дополнительной проверке не запускался: на этом этапе
задача оставалась BLOCKED из-за отсутствия доказанной исторической причины.

Проверки подтверждают работоспособность текущего сценария и готовность
диагностики к следующему отказу. Историческая причина F81 не установлена.

## Решение владельца о закрытии

2026-11-17 владелец явно разрешил закрыть PRE04-15 по подтверждённым PASS
и добавленной диагностике, сохранив историческую причину F81 как
неустановленную: «разрешаю закрыть».

Таким образом, критерий этой задачи изменён владельцем: естественный повтор
исторического F81 больше не является условием её завершения. Это не означает,
что причина F81 доказана или что найден и исправлен дефект приложения.
Production-код и проверки теста не ослаблялись.

Завершение оформляется командой `bash orchestrator/run.sh --mark-done PRE04-15`:
она запускает полный gate и сборку androidTest, затем создаёт коммит с журналом
и выполняет push. Результат оформления сохраняется в `orchestrator/PROGRESS.md`
и локальных логах оркестратора.
