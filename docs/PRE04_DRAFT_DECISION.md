# PRE04-05 — временный неотправленный ввод (G05)

Дата: 2026-11-17. Итог: **PASS — 12 тестов, 0 ошибок, 53,518 с**.

Применено утверждённое решение владельца: неотправленный
ответ — временное состояние открытого экрана. После ухода с уровня,
повторного открытия, пересоздания Activity и восстановления сохранённого
состояния поле пустое. Вариант можно ввести заново.

## Изменения

- `GameViewModel.kt`: ввод хранится только в `GameUiState`; удалены чтение и
  запись `game.input`/`game.source` в `SavedStateHandle`. Старый сохранённый
  черновик игнорируется. Аргумент маршрута `index` по-прежнему читается.
- `GameScreen.kt`: при удалении `GameRoute` из композиции вызывается
  `onScreenClosed()`, очищающий только поле ввода. Это покрывает и
  пересоздание Activity с сохранённой ViewModel. Обычный stop/resume того же
  экрана без его уничтожения не пересоздаёт игровую сессию.
- `WordsNavHost.kt`, `WordsApp.kt`: удалены перенос черновиков через
  `level.draft.*`, восстановление из Bundle и параметр `saveLevelState`.
  Мост между двумя SavedStateHandle не исправлялся — он удалён.
- `GameNavigationTest.kt`: ожидания восстановления черновиков заменены на
  пустой ввод; добавлен сценарий без единой отправки, проверены системный
  Back, кнопка возврата, recreate и переходы между двумя уровнями.
- `GameViewModelTest.kt`: проверены отсутствие записи черновика, игнорирование
  старого сохранённого текста и очистка при сохранённой ViewModel; сохранены
  проверки ответов, звёзд, статистики и отсутствия повторного поиска.
- `TimeNavigationTest.kt`: после ухода через меню и возвращения ожидается
  пустой ввод; проверки сохранения прогресса и учёта времени оставлены.
- `IsolatedGameStorageRule.kt`: оба навигационных класса используют отдельный
  контейнер через существующий `LocalAppContainer`, включая recreate.
  Его DataStore прогресса и времени расположены во временном каталоге теста.
  Из GameNavigationTest удалены вызовы `clearAll()` пользовательского
  репозитория. Команды очистки данных приложения не выполнялись.

Ожидание feedback в GameNavigationTest также приведено к действующему
ресурсу: трёхбуквенный ответ в кампании показывает
`correct_three_letter_answer`, более длинный — `correct_answer`. Само
поведение отправки ответа не менялось. Это необходимо, чтобы существующие
сценарии дошли до проверок G05; assertions прогресса не ослаблялись.

## Проверка сохранения прогресса

| Сценарий | Проверяемое поведение |
| --- | --- |
| Ввод без отправки → Back → возврат | Пустое поле, найденных слов нет, снимок кампании сохранён |
| Ввод без отправки → recreate | Пустое поле, снимок кампании сохранён |
| Правильный ответ → recreate / Back / кнопка возврата → вход | Ответ и подсказка сохранены, поле пустое; статистика показывает 1 найденное слово |
| Два открытых уровня → ввод → выход → recreate карты → повторный вход | Поля пустые, переноса ввода нет; полный снимок кампании совпадает |
| Старая сохранённая ViewModel / SavedStateHandle | Черновик очищен / проигнорирован; маршрут, найденные слова, звёзды и статистика сохранены |
| Ответ записывается во время ухода с уровня | Подтверждённый ответ записан в свой уровень, ввод нового уровня не затронут |
| Уход через меню «Время игры» | Прогресс и уже записанное время сохраняются, черновик не возвращается |

Сценарий двух уровней предварительно получает звёзды на первом уровне и
проверяет достижение порога открытия второго. Затем сравнивает полный
снимок кампании, включая найденные слова, выполненные задания и доступ.
Сохранение звёзд и статистики legacy-уровней дополнительно проверено
сценарием сохранённой ViewModel и тестом событий звёзд.

Код ProgressRepository, DataStore, правил кампании и статистики не менялся.
Изменение не требует миграции или очистки пользовательских сохранений.

## Выполненные команды

Сборки PASS:

```bash
bash ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest --console=plain
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell input keyevent KEYCODE_WAKEUP
```

На физическом устройстве Android 13 (API 33), с естественными параметрами
1080×2400, density 440, font_scale 1.0 запущены оба навигационных класса и
пять связанных тестов ViewModel:

```bash
adb shell am instrument -w -r -e class 'com.example.sostavslova.ui.game.GameNavigationTest,com.example.sostavslova.ui.time.TimeNavigationTest,com.example.sostavslova.ui.game.GameViewModelTest#emptyOrNonRussianSubmissionExplainsItselfAndLongInputIsKeptWhole,com.example.sostavslova.ui.game.GameViewModelTest#routeAndProgressRestoreButOldDraftIsIgnoredAfterViewModelRecreation,com.example.sostavslova.ui.game.GameViewModelTest#repeatedOpenDuringLoadAndRetainedOwnerDoNotSearchAgainAndObserveFreshV1,com.example.sostavslova.ui.game.GameViewModelTest#answerInFlightWhenNavigatingSavesOnlyItsLevelAndPreservesNewDraft,com.example.sostavslova.ui.game.GameViewModelTest#firstStarsEmitEventsAndFinishedIsAnEventOnlyOnce' io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
git diff --check
```

Первый прогон: 12 тестов, 8 PASS, 4 FAIL — четыре навигационных сценария
останавливались на устаревшем ожидании «Правильно!» для трёхбуквенного
ответа, до проверок черновика. После актуализации ожидания выполнен полный
повтор выбранных 12 тестов: **OK (12 tests)**, все статусы методов `0`.
Это 6 GameNavigationTest, 1 TimeNavigationTest и 5 GameViewModelTest.
`git diff --check` также PASS. Штатный тестовый runner восстановил
MIUI app-op 10021 в `ignore`, что проверено через `adb shell cmd appops get
io.github.volmir.wordscombination 10021`. Параметры экрана после прогона
совпадают с исходными.

Логи находятся вне репозитория: `/tmp/pre04-05-build.log`,
`/tmp/pre04-05-build-final.log`, `/tmp/pre04-05-tests-build-final.log`,
`/tmp/pre04-05-native.instrumentation`, `/tmp/pre04-05-final.instrumentation`.

## Ограничения и риски

Full gate оставлен оркестратору. Полная androidTest-сюита и другие версии
Android не запускались. Уничтожение процесса Android отдельно не
эмулировалось; старый сохранённый ввод проверен через новый SavedStateHandle.
Данные, зависимости, ресурсы, защищённые пути и Git-история не менялись.
Следующие задания не выполнялись.
