# PRE04-07 — диагностика feedback (G03)

Дата: 2026-11-17. Установленная причина воспроизводимого тайм-аута
`MainScenariosUiTest`: тест отправлял трёхбуквенный ответ `нос`, но ожидал
обычное «Правильно!». В кампании такой ответ получает отдельное сообщение
`correct_three_letter_answer`. Запись, публикация состояния и отрисовка
завершаются задолго до тайм-аута. Потеря DataStore не обнаружена.

## Измерение submit → commit → GameUiState → semantics

Физический смартфон Android 13 / MIUI, штатные 1080×2400, плотность 440,
font_scale=1.0. Настройки экрана не менялись. Основной представитель —
`MainScenariosUiTest.levelAnswerIsSavedAndScoreUpdated`.
Для безопасного воспроизведения он сначала подключён к существующему
`IsolatedGameStorageRule`; исходный ответ `нос` и ожидание «Правильно!»
сохранены. Пользовательский DataStore не очищался.

Временные отметки `SystemClock.elapsedRealtime()` снимались в `onSubmit`,
после завершения `dataStore.edit`, при публикации `GameUiState`, в
`GameRoute.SideEffect` и при чтении настоящего дерева semantics из теста.
Ниже один прогон (`pre04-07-main-semantics-before`), отсчёт от submit:

| Событие | Время | Наблюдение |
|---|---:|---|
| submit | 0 мс | input=`нос`, busy=false, message=null, loading=false, canPlay=true, found=[] |
| Подтверждённый `dataStore.edit` | +86 мс | found={чемпионство: [нос]} |
| Возврат репозитория | +86 мс | busy=true, UI ещё содержит found=[] |
| UI со снимком и feedback | +96 мс | found=[нос], input пуст, busy=false, canPlay=true, message=`correct_three_letter_answer` |
| Завершение `publishSession` | +97 мс | тот же подтверждённый ответ |
| Чтение feedback в semantics | +215 мс | полное специальное сообщение, Activity=RESUMED |
| Истечение ожидания «Правильно!» | около +10 с | нужного тесту текста нет; найденный ответ и специальный feedback остаются в дереве |

Точный текст в semantics:

> Слово записано в найденные. Для заданий «набери N» и «на букву» нужны слова от 4 букв.

Это стабильный `GameUiState.message`, а не исчезнувшее одноразовое notice.
После исправления входа на слово от четырёх букв в диагностическом
`levelAnswerIsSavedAndScoreUpdated`: commit +27 мс, состояние с ответом и
feedback +66 мс, чтение semantics «Правильно!» +213 мс. Это единичные
измерения цепочки, не статистический benchmark.

## Проверенные альтернативы

- **input/busy/message:** raw до submit правильный; busy снимается, поле
  очищается, новое сообщение публикуется. Корректный ответ уже сохранён.
- **Жизненный цикл и готовность:** при submit loading=false, canPlay=true,
  returnLevels=false; при чтении semantics и тайм-ауте Activity=RESUMED.
  В исходном helper оставался диалог «Новые задания этапа» (explanations=[T1]).
  Он не помешал тестовой IME-команде записать ответ, но игровые действия
  теперь начинаются после его закрытия и проверки доступности «Проверить».
- **Application-контейнер:** тестовый контейнер отличается от
  `WordsApplication.container`, его applicationScope активен. Код Application,
  очереди записей и барьеры не менялись; подтверждённый commit не завис.
- **Синхронизация:** сохранён исходный `waitUntil(10_000)`; не добавлены sleep
  или увеличение лимита. В `CampaignJourneyUiTest.answer` уже есть
  `waitForIdle()` после IME, добавленный PRE04-06.
- **Изоляция:** Main раньше очищал progress общего Application в Before/After.
  Теперь каждый метод получает отдельные progress и play_time через готовое
  правило; оно закрывает scope и удаляет только временный каталог. Ни clear
  пользовательского progress, ни подмена Application-контейнера не нужны.

## Отдельно и после предшественника

Минимальный исследованный префикс — один метод
`CampaignJourneyStateTest.openingAndAnswerMeasureDictionaryAccessAndNeverScanOnUiThread`.
Проверен фактический порядок стартов методов в instrumentation; каждый
прогон запускает новый процесс instrumentation, методы внутри прогона
делят Application.

| Выборка до исправления входного ответа | Результат |
|---|---|
| Main `levelAnswerIsSavedAndScoreUpdated` отдельно | FAIL на «Правильно!», 14,813 с весь тест |
| Префикс → тот же Main | префикс PASS, Main тот же FAIL, 17,116 с весь прогон; submit → готовый feedback 87 мс |
| F01 `closedDeepLinkUsesInjectedCampaignWithoutChangingProgress` отдельно | PASS, 4,894 с |
| Префикс → F01 | 2 PASS, 6,518 с |

Таким образом, воспроизводимая причина Main не требует загрязнения
предыдущим тестом. Исторический F01 в текущем коде не воспроизведён;
ему не приписывается причина Main или потеря записи. Изменения PRE04-05/06
уже исправляли ожидания трёхбуквенного ответа в `GameNavigationTest`,
изоляцию QA и синхронизацию IME; здесь эти исправления не переделываются.
Общая причина всех восьми исторических FAIL не заявляется.

## Исправление и регрессия

Изменён `app/src/androidTest/kotlin/com/example/sostavslova/ui/MainScenariosUiTest.kt`:

- Для существующих сценариев выбран допустимый ответ длиной от четырёх
  букв из текущего игрового набора. Все ожидания «Правильно!», неизвестного
  и повторного ответа оставлены прежними. Production-тексты и правила
  `submissionMessage` не менялись.
- Добавлен отдельный сценарий трёхбуквенного ответа: специальный feedback,
  пустой доступный input, счёт 1, отсутствие продвижения заданий первого
  уровня, повтор в верхнем регистре без второй записи, возврат и открытие
  уровня с тем же сохранённым снимком.
- Для обычного повторного ответа дополнительно проверяются пустой input,
  неизменный счёт 1 и ровно один сохранённый ответ.
- Применено существующее правило изоляции и закрывается вводный диалог.

Все временные production-отметки и тестовые semantics-dump удалены.
Другие Kotlin-файлы, данные, ресурсы и защищённые пути не изменены.

## Проверки

Сборка окончательного кода:

```bash
bash gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Gradle: BUILD SUCCESSFUL. `./gradlew` не имеет права исполнения в исходном
дереве, поэтому использован `bash gradlew`; права файла не менялись.
APK установлены без удаления приложения и без очистки данных.

Instrumented-выборки запускаются через:

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell am instrument -w -r -e class '<выборка>' io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
```

Диагностический Main после исправления — 6 PASS, 28,616 с. Окончательный
Main отдельно без диагностических отметок — 1 PASS, 6,204 с.
Совместная выборка на окончательном коде — **16 PASS, 0 FAIL, 69,652 с**:

| Порядок | Выборка | Количество |
|---|---|---:|
| 1 | `CampaignJourneyStateTest.openingAndAnswerMeasureDictionaryAccessAndNeverScanOnUiThread` | 1 |
| 2 | `CampaignJourneyUiTest.closedDeepLinkUsesInjectedCampaignWithoutChangingProgress` (F01) | 1 |
| 3 | `MainScenariosUiTest` целиком | 6 |
| 4 | `GameNavigationTest` целиком: в том числе пересоздание Activity и повторные CREATED → RESUMED | 6 |
| 5 | `GameViewModelTest.campaignMissingLettersReachMessageAndRepeatedSubmitDoesNotLeaveBusy` и `correctRepeatIncorrectAndLatinLetterFollowCoreAndPersistOnlyCorrectAnswers` | 2 |

Проверены конечные статусы каждого метода (0), число методов и `OK (16 tests)`,
а не только код возврата ADB. На окончательном коде всего 17 успешных запусков
тестов: один отдельный и 16 совместных. Счёт, повторные ответы и сохранённые
снимки проверены assertions, включая возврат, recreate и resume.

`python3 -m unittest tools.tests.test_privacy_publication -v` — 8 PASS.
`git diff --check` — PASS. SHA-256 пользовательского progress до и после
прогонов совпал (`cmp /tmp/pre04-07-user-store-before.txt /tmp/pre04-07-user-store-after.txt`);
пользовательский play_time не появился. Runner восстановил MIUI app-op 10021
в исходный `ignore`. Команды commit/push/checkout/reset/add не выполнялись.

Логи измерений находятся только в `/tmp/`: `pre04-07-main-raw`,
`pre04-07-main-prefix-before`, `pre04-07-main-semantics-before`,
`pre04-07-f01-alone-before`, `pre04-07-f01-prefix-before`,
`pre04-07-main-green-trace` (instrumentation и диагностические logcat).
Итоговые логи: `pre04-07-final-build.log`,
`pre04-07-main-final-alone-awake.instrumentation`,
`pre04-07-final-sequence.instrumentation`.

Первая попытка итогового отдельного Main остановилась в setUp на ожидании
карточки, до submit (`pre04-07-main-final-alone.instrumentation`): телефон
был в Dozing. После KEYCODE_WAKEUP тот же APK прошёл. Первый пробный dump
также потребовал заменить onRoot на onAllNodes(isRoot), поскольку диалог
создаёт второй root; этот диагностический отказ не использован как G03.

Полный gate оставлен оркестратору. Полная androidTest-сюита, другие устройства
и точное воспроизведение исторического полного порядка не проверены.
Релиз и публикация не выполнялись. Следующие задания не выполнялись.
