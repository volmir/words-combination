# PRE04-06 — изоляция PlayTime и барьеры выхода (G06)

## Причина и воспроизведение

В прежнем `QaCampaign.container` аргумент `progressFile` заменял только
файл прогресса. `AppContainer.playTimeRepository` по-прежнему открывал
`applicationContext.filesDir/datastore/play_time.preferences_pb`.
Синтетическая кампания во всех тестах имеет одинаковый вычисляемый SHA,
поэтому ключи её времени тоже совпадали между тестами. Новый progress
с нулём звёзд мог получить старое время уже пройденного уровня.

Проверены `QaCampaign`, `AppContainer`, `PlayTimeRepository`,
`PlayTimeTracker`, `ExitViewModel` и жизненный цикл тестовых контейнеров.
`initialize(..., starsBeforeTracking=0)` намеренно не уменьшает
`previous.lastStars=3`. Tracker получает из этой записи `lastSequence=1`,
но держит baseline нового прогресса: 0 звёзд. Первый подтверждённый ответ
даёт 1 звезду и ставит сегмент с `sequence=2` в очередь. Репозиторий
отвергает уменьшение 3 → 1; номер сегмента при этом корректен.

Причина подтверждена настоящим DataStore в `TemporaryFolder`:

- До исправления воспроизведена прежняя схема: разные progress-файлы,
  общий time-файл. Общий каталог перенесён во временную папку, чтобы
  воспроизведение не писало в пользовательские данные.
  `freshProgressDoesNotReuseAnotherContainersTime` упал именно на
  инварианте звёзд: 3 теста, 1 FAIL (`/tmp/pre04-06-red.instrumentation`).
- Тестовый декоратор `PlayTimeStore` перехватывает исходный Throwable,
  пишет его через `Log.e(..., error)` вместе с числовыми параметрами
  и передаёт дальше без обёртки. `assertSame` проверяет, что
  `PlayTimeTracker.awaitSaved()` возвращает тот же объект исключения.
- После разнесения time-файлов регрессия проходит; повторное открытие
  старого контейнера сохраняет прежнее время, а новый получает свою запись.

Это воспроизведение причины, а не попытка угадать точные значения старого
запуска F02/F03: в историческом logcat не было message/stacktrace.
На устройстве перед этой работой пользовательский `play_time.preferences_pb`
отсутствовал. Он не создавался и не очищался диагностикой.

## Исходный Throwable и инварианты

Из контролируемого воспроизведения получено:

```text
java.lang.IllegalArgumentException: Stars cannot decrease within a timing record
    at com.example.sostavslova.data.PlayTimeRepository$appendSegment$saved$1.invokeSuspend(PlayTimeRepository.kt:128)
    at com.example.sostavslova.data.PlayTimeRepository$appendSegment$saved$1.invoke(PlayTimeRepository.kt:8)
    at com.example.sostavslova.data.PlayTimeRepository$appendSegment$saved$1.invoke(PlayTimeRepository.kt:4)
    at androidx.datastore.preferences.core.PreferencesKt$edit$2.invokeSuspend(Preferences.kt:358)
    at androidx.datastore.preferences.core.PreferencesKt$edit$2.invoke(Preferences.kt:8)
    at androidx.datastore.preferences.core.PreferencesKt$edit$2.invoke(Preferences.kt:4)
    at androidx.datastore.preferences.core.PreferenceDataStore$updateData$2.invokeSuspend(PreferenceDataStoreFactory.kt:94)
    at androidx.datastore.preferences.core.PreferenceDataStore$updateData$2.invoke(PreferenceDataStoreFactory.kt:8)
    at androidx.datastore.preferences.core.PreferenceDataStore$updateData$2.invoke(PreferenceDataStoreFactory.kt:4)
    at androidx.datastore.preferences.core.PreferenceDataStore$updateData$2.invokeSuspend(PreferenceDataStoreFactory.kt:94)
    at androidx.datastore.preferences.core.PreferenceDataStore$updateData$2.invoke(PreferenceDataStoreFactory.kt:8)
    at androidx.datastore.preferences.core.PreferenceDataStore$updateData$2.invoke(PreferenceDataStoreFactory.kt:4)
    at androidx.datastore.core.DataStoreImpl$transformAndWrite$2$newData$1.invokeSuspend(DataStoreImpl.kt:331)
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
    at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
    at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:586)
    at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:829)
    at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:717)
    at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:704)
```

Полный вывод вместе с параметрами: `/tmp/pre04-06-red.logcat`.
Все значения ниже относятся только к синтетической записи; тексты ответов,
пути к пользовательским файлам и персональные данные не логируются.

| Проверка | Полученные значения | Результат |
|---|---|---|
| Идентичность кампании | SHA вычислен `LevelsRepository`, формат 64 hex; уровень 1 существует | Допустимо |
| Схема записи | `version=1`, `initialized=true` | Допустимо |
| Диапазон звёзд | `maxStars=3`, `requiredStars=1`, baseline=0, `starsAfter=1` | Допустимо |
| Порядок сегментов | `lastSequence=1`, `sequence=2` | Допустимо, ровно +1 |
| Длительности | `activeMs=0`, `cumulativeActiveMs=40` | Неотрицательные |
| Старые пороги | `lastStars=3`, `rAccounted=true`, `fullAccounted=true`, оба замера порогов `null` | Согласованы с прежними 3 звёздами |
| Монотонность звёзд | `starsAfter=1 >= previous.lastStars=3` | **Нарушена** |

Ошибку не вызывают отсутствие SHA, отрицательная длительность или пропуск
sequence. Конфликт одновременно открытых DataStore — отдельный возможный
риск прежней схемы, но полученный Throwable выброшен именно проверкой звёзд.

`PlayTimeTracker` оставляет неудачный сегмент в очереди; барьер повторяет
запись и передаёт ошибку ожидающему. `AppContainer.awaitSaved()` ждёт
прогресс, затем время. `ExitViewModel` при ошибке не показывает успешное
сохранение и не завершает Activity. Эти production-проверки корректны.

## Изменённые файлы

- `app/src/androidTest/kotlin/com/example/sostavslova/qa/QaCampaign.kt`:
  тестовый `ContextWrapper` возвращает собственный `applicationContext`
  и `filesDir=<progressFile.name>.files` рядом с progress-файлом.
  Время остаётся отдельным `datastore/play_time.preferences_pb`;
  другой progress получает другой каталог времени, restart того же
  progress открывает прежний каталог. Подход соответствует уже имеющемуся
  `IsolatedGameStorageRule`, но сохраняет адресную инъекцию progress.
  `closeQa()` вызывает общий `awaitSaved()` до отмены scope;
  в `finally` выполняет cancel/join даже при ошибке записи.
- `app/src/androidTest/kotlin/com/example/sostavslova/qa/QaCampaignStorageTest.kt`:
  четыре проверки — независимость двух контейнеров, сохранение обоих
  хранилищ при закрытии и повторном открытии, передача ошибки закрытия
  с завершением scope, диагностика исходного исключения через декоратор.
  Отрицательные тесты намеренно создают несовместимое время во временной
  папке и проверяют отказ; их ожидаемый IllegalArgumentException не
  означает сбой сценариев выхода.
- `app/src/androidTest/kotlin/com/example/sostavslova/qa/CampaignJourneyUiTest.kt`:
  оба выхода дополнительно проверяют сохранённое время, звезду,
  наличие сегмента и отсутствие ошибки tracker; выход через меню также
  проверяет, что Activity ещё жива до подтверждения.
  После IME добавлен стандартный `compose.waitForIdle()` перед ожиданием
  DataStore. Тайм-ауты, прежние assertions и действия пользователя сохранены.

Production-код, правила сохранения и выхода, пользовательские файлы,
данные, зависимости и защищённые пути не менялись. Git-команды записи
и изменения истории не выполнялись.

## Проверки и ограничения

Среда: подключённое устройство 2201117SY, Android 13 / API 33,
1080×2400, density 440, font_scale 1.0, без overrides. JDK 21 и Android SDK
использованы из локальной среды; JDK задан через `JAVA_HOME`,
Android SDK — через `sdk.dir` в локальном `local.properties`.
APK установлены через `adb install -r -t`, без удаления данных приложения.

Сборки до и после исправления прошли:

```bash
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest --console=plain
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Логи сборок: `/tmp/pre04-06-build-red.log`,
`/tmp/pre04-06-build-green.log`, `/tmp/pre04-06-build-final.log`.
Инструментальные тесты запускаются через:

```bash
adb shell am instrument -w -r -e class '<выборка>' io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
```

Итоговые прогоны на окончательном коде — **PASS, 12 запусков тестов**
(8 разных методов; каждый exit-сценарий прошёл трижды).
Проверены `OK (N tests)` и статусы методов `0`, а не только exit code ADB.

Префикс методов UI — `com.example.sostavslova.qa.CampaignJourneyUiTest#`;
для совместного запуска оба полных имени передаются через запятую.

| Выборка | Результат | Лог в `/tmp/` |
|---|---|---|
| `exitFromMenuWaitsForSaveAndClosesHost` отдельно | OK (1 test) | `pre04-06-menu-final.instrumentation` |
| `doubleSystemBackFromMapExitsWithSavedAnswers` отдельно | OK (1 test) | `pre04-06-back-final.instrumentation` |
| Оба выхода в одном процессе, меню → Back | OK (2 tests) | `pre04-06-pair-final.instrumentation` |
| Повтор обоих в одном процессе | OK (2 tests) | `pre04-06-pair-repeat.instrumentation` |
| `QaCampaignStorageTest` + `CampaignJourneyStateTest` целиком | OK (6 tests) | `pre04-06-storage-state-final.instrumentation` |

Для каждого итогового прогона рядом сохранён `.logcat`, отфильтрованный
по PID соответствующего instrumentation. В четырёх прогонах UI —
**0 ошибок PlayTimeTracker**. В `storage-state-final` две ожидаемые записи
ошибки относятся исключительно к отрицательному `failedClose...`:
первая попытка записи и повтор при барьере. Тест декоратора также
намеренно проверяет исключение, его полная диагностика доступна по тегу
`QaPlayTime` в `/tmp/pre04-06-red.logcat`.

`git diff --check` — PASS. После всех прогонов отдельно проверены
`wm size`, `wm density`, `font_scale` и `cmd appops get ... 10021`:
экран без изменений, app-op восстановлен в исходный `ignore`.
В пользовательском `files/datastore` остался только прежний progress-файл
с прежними размером и временем изменения; `play_time` отсутствует,
как и до начала работы. Ни `clearAll`, ни `clearTimeStatistics`,
ни `pm clear` не вызывались.

Первоначальные прогоны меню (первый FAIL сохранён в
`/tmp/pre04-06-menu.instrumentation`) иногда
останавливались до выхода на ожидании записанного ответа (10 секунд),
без ошибки PlayTime. Диагностические повторы с синхронизацией Compose
проходили. Временный вывод дерева UI удалён, оставлен `waitForIdle()`.

Полный gate оставлен оркестратору; вся androidTest-сюита и другие устройства
не запускались. Следующие задания не выполнялись.

## Продолжение после отказа gate

Проверка `test_repository_has_no_forbidden_personal_markers` обнаружила
имя локального пользователя в абсолютных путях к JDK в этом отчёте.
Пути обезличены: указаны `JAVA_HOME` и локальный `sdk.dir` без их значений.
Политика приватности, тесты и критерии не менялись.

Повторно выполнены `python3 -m unittest tools.tests.test_privacy_publication -v`
(8 тестов, PASS), `python3 tools/check_privacy_publication.py --require-generated`
(PASS) и `git diff --check` (PASS).
Проверены сохранённые логи воспроизведения и всех 12 итоговых запусков выше;
они подтверждают исходный Throwable и успешные выходы отдельно и вместе.
После обезличивания документа Kotlin-код не менялся, инструментальные
тесты повторно не запускались. Полный gate выполнит оркестратор.
