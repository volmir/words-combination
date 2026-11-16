# TIME-02 — событийный счётчик активного времени

Выполнено только TIME-02. Кампания 15/49, словарь, история, игровые правила и подсказки не изменены.

## Изменённые файлы

- `core/.../ActiveTimeCounter.kt` и `ActiveTimeCounterTest.kt`: автомат stopped/running,
  единственный startTick, идемпотентные start/stop, flush с переносом границы, тест нового процесса.
- `app/.../time/PlayTimeTracker.kt`: единственный менеджер из AppContainer,
  условия активности, событийные сегменты, редкий checkpoint, очередь и диагностика.
- `app/.../time/PlayTimeEffects.kt`, `PlayTimeLifecycle.kt`: фокус окон и Lifecycle observer.
- `app/.../data/PlayTimeRepository.kt`: интерфейс PlayTimeStore для fake; формат и алгоритм хранения TIME-01 сохранены.
- `app/.../data/ProgressRepository.kt`: событие после долговечного editCampaign внутри
  принятой NonCancellable-операции. Не меняет данные или результат игры.
- `app/.../di/AppContainer.kt`: singleton tracker, elapsedRealtime, локальная диагностика,
  общий барьер выхода: stop → progress.awaitSaved → time.awaitSaved.
- `app/.../nav/WordsNavHost.kt`, `ui/game/GameScreen.kt`, `GameViewModel.kt`:
  идентичность NavBackStackEntry, пригодность загруженной игры, lifecycle маршрута,
  checkpoint звёзд и остановка на финальном итоговом экране.
- `app/.../ui/WordsApp.kt`, `ui/components/WordsDialogs.kt`, `res/values/strings.xml`:
  события навигации/Activity/drawer, учёт фокуса игрового Dialog, сообщение об ошибке записи.
- `app/src/androidTest/.../time/PlayTimeTrackerTest.kt`: 17 тестов fake repository,
  виртуальной монотонной шкалы и LifecycleRegistry.createUnsafe.
- `app/src/androidTest/.../time/PlayTimeProgressIntegrationTest.kt`: реальная пара
  независимых DataStore с отменой ViewModel между записью прогресса и событием checkpoint.
- `app/src/androidTest/.../di/AppContainerTest.kt`: проверка singleton tracker.
- `docs/TIME02_REPORT.md`: этот отчёт и воспроизводимая JVM-проверка.

## Поведение

Время идёт, только если совпадают id записи стека и номер level/{index}, игра
прошла существующие проверки загрузки/доступа, Activity и запись стека RESUMED,
есть фокус окна Activity либо игрового Dialog, drawer закрыт и выход не начат.
Загрузка, ошибка, недоступный/неверный маршрут и финальный итоговый экран времени не дают.
Ввод, ожидание ответа/подсказки, пояснения заданий и игровые диалоги считаются временем игры.
Будущая отдельная страница статистики автоматически исключается как маршрут вне level.

Tick существует только в памяти; production-clock — Android SystemClock.elapsedRealtime.
Навигация останавливает счёт синхронным destination listener. PAUSE/STOP/DESTROY,
потеря фокуса, drawer, dispose и смена уровня отрезают сегмент один раз.
Возврат/поворот начинают новый tick в том же singleton без новой параллельной сессии.
Редкий coroutine checkpoint существует только при running, ждёт 60 000 мс между
попытками и не меняет UI. Секундного polling, часов календаря и сохранения tick нет.

Очередь applicationScope сериализует initialize и append. Ошибочный сегмент остаётся
с исходными аргументами и sequence, следующие события/барьер повторяют его первым.
Даже неоднозначный commit не удваивает время. Успешная запись звёзд вызывает checkpoint
до завершения принятой операции ProgressRepository: отмена ViewModel и опоздавший
UI-снимок не теряют и не откатывают checkpoint. Правила подсчёта звёзд берутся из core.
Штатный выход разрешается после обоих барьеров. Ошибка времени срывает подтверждение выхода.

Диагностика: StateFlow writeFailure, Snackbar `game_time_save_error` и локальный
Logcat tag PlayTimeTracker (номер уровня, initialize/append, pendingMs, тип исключения).
Проверяемый отказ: fake возвращает IOException на append 10 мс; сохранено 0 мс,
writeFailure = (1, append, 10, IOException), awaitSaved бросает IOException.
После восстановления fake запись повторяется с sequence=1, сохранено ровно 10 мс,
writeFailure очищается. Успех до подтверждения DataStore не объявляется.

## Действительно выполнено

- `./gradlew ...` первоначально не запустился: wrapper без executable bit; режим файла не менялся, далее использован `bash gradlew`.
- До кода: `bash gradlew :core:test --tests 'com.example.sostavslova.core.ActiveTimeCounterTest' --console=plain` — ожидаемо не скомпилирован отсутствующий ActiveTimeCounter.
- После кода та же узкая проверка и `:app:compileDebugKotlin` — успешно.
- `bash gradlew :app:compileDebugAndroidTestKotlin :app:lintDebug :core:test --console=plain` — успешно; core: 149 тестов, 0 ошибок/пропусков.
- Повторная `:app:compileDebugAndroidTestKotlin :app:lintDebug` после последующих правок — успешно.
- JUnitCore на JVM: PlayTimeTrackerTest + PlayTimeProgressIntegrationTest +
  CampaignProgressRepositoryTest — 35 тестов, PASS. Команда ниже, без загрузки зависимостей.
- `git diff --check` — успешно.
- `python3 tools/check_privacy_publication.py --root . --require-generated` — PASS, включая этот отчёт.
- `adb devices -l` — устройств нет.

Покрыты resume/pause/resume, повторные события и stop, карта/правила/смена уровня,
неправильная запись стека, загрузка/ошибка/закрытый уровень, поворот/dispose,
drawer, фокус игрового диалога и внешний перехват фокуса, блокировка/фон,
финальный экран, звёзды и поздний commit, process death, редкий checkpoint,
ошибки initialize/append, неоднозначный commit, медленный диск, барьер выхода.
В тесте отсутствия polling проверяются неизменные число чтений clock и число append
на каждой из 59 виртуальных секунд; 60-я даёт ровно один checkpoint. Реальных ожиданий 60 секунд нет.

## Не проверено и ограничения

- connectedDebugAndroidTest и реальные окна/поворот/блокировка на устройстве не проверены: adb пуст.
- Полный gate и сборка APK оставлены оркестратору по заданию; новые зависимости и разрешения не добавлены.
- Принудительное убийство процесса может потерять незаписанный хвост и очередь при неисправном диске; они не выдаются за сохранённое время.
- Progress и время остаются двумя файлами: crash между их fsync не является общей атомарной транзакцией.
- Защищённые пути, данные, Git-история не менялись; запрещённые команды Git не выполнялись.

## Повторение JVM-тестов

После компиляции androidTest из корня проекта запустить следующую команду.
Она использует уже разрешённые Gradle-артефакты, временно извлекает classes.jar из AAR
и выполняет только не зависящие от Android runtime классы; connected-тесты это не заменяет.

```bash
python3 - <<'PY'
import json, pathlib, tempfile, zipfile, subprocess
root = pathlib.Path.cwd()
cp = [root/'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes', root/'app/build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes', root/'core/build/classes/kotlin/main']
cache = pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
with tempfile.TemporaryDirectory(prefix='time02-jvm-') as directory:
    for i, item in enumerate(json.loads((root/'app/build/reports/licenses/runtime-inputs.json').read_text())['debug']):
        path = pathlib.Path(item['file'])
        if path.suffix == '.aar':
            with zipfile.ZipFile(path) as archive:
                if 'classes.jar' in archive.namelist():
                    target = pathlib.Path(directory)/f'{i}.jar'
                    target.write_bytes(archive.read('classes.jar'))
                    cp.append(target)
        elif path.suffix == '.jar':
            cp.append(path)
    for artifact in ['junit/junit/4.13.2', 'org.hamcrest/hamcrest-core/1.3', 'org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm/1.10.2']:
        cp.extend((cache/artifact).glob('*/*.jar'))
    result = subprocess.run(['java', '-cp', ':'.join(map(str, cp)), 'org.junit.runner.JUnitCore', 'com.example.sostavslova.time.PlayTimeTrackerTest', 'com.example.sostavslova.time.PlayTimeProgressIntegrationTest', 'com.example.sostavslova.data.CampaignProgressRepositoryTest'])
    raise SystemExit(result.returncode)
PY
```
