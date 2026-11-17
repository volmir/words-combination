> **Историческое свидетельство до перехода на JSON (2026-11-17).**
> Описанные здесь прежний формат, команды и статусы не задают текущую сборку.
> Действующий контракт: [OWN_HINTS_TOOLCHAIN.md](OWN_HINTS_TOOLCHAIN.md). Исходные правовые выводы сохранены для прослеживаемости.

# PRE-04: зависание запуска Android UI-тестов

Дата: 2026-11-16. Исходный commit: `8434561` плюс описанная ниже правка
тестовой инфраструктуры. Устройство: Xiaomi 2201117SY, Android 13, MIUI
`ro.miui.ui.version.name=V816`.

## Подтверждённая причина

MIUI блокирует запуск тестовой `androidx.activity.ComponentActivity`:
операция `MIUIOP(10021)` для `io.github.volmir.wordscombination` имеет режим
`ignore`. В системном логе одновременно со стартом теста:

```text
ActivityStarterImpl: MIUILOG- Permission Denied Activity : Intent {
  act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
  cmp=io.github.volmir.wordscombination/androidx.activity.ComponentActivity
}
```

Дамп Java-потоков через 30 секунд показывает точный невозвращающийся вызов:

```text
Instr: androidx.test.runner.AndroidJUnitRunner WAITING
  android.app.Instrumentation.startActivitySync(Instrumentation.java:562)
  androidx.test.core.app.InstrumentationActivityInvoker.startActivity(...)
  androidx.test.core.app.ActivityScenario.launchInternal(...)
  androidx.test.ext.junit.rules.ActivityScenarioRule.before(...)
```

Блокируется подготовка Compose Test Rule, до тела теста. Main-поток в
`MessageQueue.nativePollOnce`, рабочие потоки корутин свободны.

Временные маркеры `QAHANG ENTER/EXIT` были добавлены вокруг начала теста,
`QaCampaign.container()`, `begin()`, `compose.setContent()`, `awaitTag()`,
`saved()`, `@After close()`, `closeQa()`, `progressRepository.awaitSaved()`,
отмены и `join` applicationScope. До исправления присутствует только маркер
старта внешнего правила; тело теста не достигнуто. После временного `allow`
все перечисленные этапы и очистка завершаются. Трассировка удалена из исходников;
её копии и журналы сохранены локально.

| Гипотеза | Вывод для исходного зависания |
| --- | --- |
| A: конфликт `runTest` и Compose | Тело `runTest` ещё не выполняется; оно проходит после изменения только MIUI app-op |
| B: первоначальная композиция | `compose.setContent()` не достигнут |
| C: DataStore | `QaCampaign.container()` и чтение/запись ещё не достигнуты |
| D: очистка `@After` | Не достигнута; после разрешения запуска завершается |
| E: Main/Default/IO | Дамп не показывает блокировки этих потоков; ожидается разрешённый системой старт Activity |

Контрольный эксперимент: с `ignore` deep-link тест остановлен по лимиту
65 секунд; с `allow` оба проблемных метода проходят. Возврат к `ignore`
снова воспроизводит зависание T4-теста, остановленного через 45 секунд.
Проверки с разрешённым запуском повторены после удаления временной трассировки.

**Разовая подготовка через ADB недостаточна:** при установке target APK
через Gradle MIUI снова устанавливает `ignore`. Полный прогон без runner
повторно остановился после первых 110 завершённых тестов. Состояние app-op и
отказ MIUI сохранены, зависшая инструментализация остановлена через force-stop;
следующий запуск начат только после завершения предыдущего Gradle-процесса.

## Минимальное исправление

- `app/build.gradle.kts`: выбран `com.example.sostavslova.WordsTestRunner`.
- `app/src/androidTest/kotlin/com/example/sostavslova/WordsTestRunner.kt`:
  наследник стандартного `AndroidJUnitRunner`. После установки APK, до старта
  тестов, только на устройстве с MIUI считывает app-op 10021 **только target
  пакета**, временно разрешает запуск Activity и проверяет результат.
  При завершении восстанавливает исходный режим и проверяет восстановление,
  в том числе при обычных ошибках тестов. Ошибка подготовки или восстановления
  завершает instrumentation с ошибкой; тесты не пропускаются.
- Этот отчёт: диагноз, доказательства, результаты и ограничения.

`CampaignJourneyUiTest.kt`, `QaCampaign.kt`, `AppContainer.kt`, `runTest`,
игровая логика и утверждения не изменены. Новых зависимостей и разрешений
приложения нет. Релизный APK не содержит класс из `androidTest`.
Данные, SHA-256, лицензии, акты аудита, флаги прав, TASKS.md, PROGRESS.md и
контрольные точки оркестратора не изменялись.

## Проверки и команды

Сборка debug и androidTest с исправлением:

```bash
bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Установка APK выполнялась только через `adb install -r -t` с сохранением
данных. Для двух отдельных методов команда имеет вид:

```bash
adb shell am instrument -w -r \
  -e class 'com.example.sostavslova.qa.CampaignJourneyUiTest#closedDeepLinkUsesInjectedCampaignWithoutChangingProgress' \
  io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner

adb shell am instrument -w -r \
  -e class 'com.example.sostavslova.qa.CampaignJourneyUiTest#t4RemainsPlayableWithoutDatabase' \
  io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
```

Для всего класса используется `-e class com.example.sostavslova.qa.CampaignJourneyUiTest`.
Хостовый лимит отдельных запусков — 90 секунд, класса — 240 секунд.

| Проверка с новым runner | Результат |
| --- | --- |
| `closedDeepLinkUsesInjectedCampaignWithoutChangingProgress` | PASS, 1/1, 5,652 с |
| `t4RemainsPlayableWithoutDatabase` | PASS, 1/1, 5,245 с |
| Весь `CampaignJourneyUiTest` | Завершён без зависания; 6 тестов, 3 PASS, 3 FAIL, 0 ignored |
| Смена и восстановление MIUI app-op | В логах `ignore -> allow`, затем `restored to ignore`; подтверждено отдельным запросом ADB |
| Полный `connectedDebugAndroidTest` с runner | Завершён: 379 тестов, 286 PASS, 93 FAIL, 0 skipped/errors; 780,8 с, код Gradle 1; остановки по тайм-ауту нет |

Оставшиеся ошибки класса уже воспроизводятся без runner при вручную
разрешённом запуске, поэтому не вызваны этой правкой:

- `exitFromMenuWaitsForSaveAndClosesHost`: штатный `ComposeTimeoutException`
  через 10 секунд, ожидание сообщения «Прогресс сохранён», строка 266.
- `doubleSystemBackFromMapExitsWithSavedAnswers`: штатный тайм-аут ожидания
  завершения Activity; в логах сценариев выхода также есть
  `PlayTimeTracker ... error=IllegalArgumentException`. Причина этих ошибок
  отдельно не установлена; утверждения и код выхода не менялись.
- `newInstallToFinaleRestartAndAllStars`: строка 175, `expected:<1> but was:<2>`
  для числа платных единиц подсказки. Ожидание не ослаблялось.

В полном наборе deep-link метод дополнительно завершился с
`TimeoutCancellationException` через 10 секунд при ожидании сохранения:
отдельный метод PASS не означает PASS в общем окружении. Это ограниченная
ошибка тела теста, после успешного запуска Activity; исходное бесконечное
ожидание `startActivitySync()` больше не повторилось. T4-тест без базы прошёл
и отдельно, и в полном наборе. Корневая причина дополнительных FAIL в рамках
этой правки не устанавливалась и не подменялась предположениями.

Полный набор завершился без пропущенных/отключённых тестов. Ошибки по классам
(параметризованные варианты учитываются отдельно):

| Класс | FAIL |
| --- | ---: |
| `CampaignJourneyUiTest` | 4 |
| `PlayTimeAcceptanceTest` | 1 |
| `ExitNavigationTest` | 1 |
| `MainScenariosUiTest` | 4 |
| `ComponentInteractionTest` | 2 |
| `CampaignGameUiTest` | 50 |
| `FoundWordsSectionTest` | 18 |
| `GameNavigationTest` | 5 |
| `GameViewModelTest` | 2 |
| `LevelsLayoutTest` | 4 |
| `TimeNavigationTest` | 1 |
| `TimeScreenTest` | 1 |
| **Всего** | **93** |

Имена и стеки всех ошибок: `.local/ui-hang-20261116/full-failures.json`.
Счётчики сверены с итоговым JUnit XML, а не только с консолью.

Полная команда (дополнительные параметры защищают данные от автоматического
удаления APK средствами AGP):

```bash
bash ./gradlew :app:connectedDebugAndroidTest --console=plain \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.experimental.testOptions.uninstallIncompatibleApks=false
```

Наличие обеих опций и их значения по умолчанию проверены в установленном
AGP 9.1.1. Для полного прогона установлен хостовый предел 1200 секунд,
остановка после 150 секунд без событий instrumentation. При остановке
сохраняются вывод, logcat и состояние Activity; тайм-аут не считается PASS.

Локальные доказательства: `.local/ui-hang-20261116/` (не входит в Git):
`baseline-deeplink.*`, `repeat-denied-t4.*`, `miui-denials.log`,
`*.kt.instrumented`, `allowed-*.logcat`, `final-*.instrumentation`,
`full-install-reset-*`, `full-connected.*`, `runner-*.instrumentation`,
`runner-*.logcat`, `fixed-full-connected.*`. Полные результаты Gradle также
в `app/build/reports/androidTests/connected/debug/`.

Перед полными прогонами существовавшие файлы `files/` и `databases/`
приложения сохранены в локальный архив с правами 0600. После завершения
восстановлены три исходных файла, содержимое каждого сверено побайтно;
ничего не удалялось. Подтверждение: `device-state-restored.json` в каталоге
диагностики. Приложение и test APK остались установленными, app-op 10021
проверен после завершения: исходный `ignore` восстановлен.
`git diff --check` прошёл; исходные тела тестов и fixture сверены с резервными
копиями, защищённые данные и `orchestrator/` не имеют diff. Уже существовавшие
правки `docs/SQLITE_REPRODUCIBILITY_AUDIT.md` и `docs/PRE_RELEASE_ANDROID_QA.md`
сохранены без изменений со стороны этой задачи.

## Ограничения

- Проверено на указанном Xiaomi с Android 13. Другие версии MIUI и устройства не проверены; неизвестная политика MIUI даёт явную ошибку подготовки.
- При аварийном убийстве instrumentation восстановление в `finish()` не гарантируется. Исходный режим записан в логах; здесь восстановление: `adb shell cmd appops set io.github.volmir.wordscombination 10021 ignore`.
- `pm clear`, uninstall, публикация, release-сборка и изменение ключей не выполнялись. Восстановлены существовавшие файлы; удаление оставшихся тестовых файлов не проводилось.
- 93 FAIL и зависимость deep-link проверки от общего окружения требуют отдельного разбора. **PRE-04 не пройдена**, публикационная готовность не подтверждена.

Справка по сохранению APK после тестов:
[официальный issue Android](https://issuetracker.google.com/issues/37077961).
