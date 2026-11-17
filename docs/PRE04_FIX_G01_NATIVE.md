# PRE04-03 — G01 на штатном экране смартфона

Дата: 2026-11-17. **Итог: native-проверки G01 PASS — 5/5 тестов,
0 FAIL, 0 пропусков, 36,864 с.** Историческое переполнение не воспроизвелось
в принятой матрице. Production-код, типографика и androidTest не изменены.

## Матрица и результаты

Подключённый физический смартфон Android 13 / MIUI; фактические значения
прочитаны до и после повторного прогона и совпали:

| Проверка | Значение |
| --- | --- |
| `adb shell wm size` | Physical size: 1080x2400; override отсутствует |
| `adb shell wm density` | Physical density: 440; override отсутствует |
| `adb shell settings get system font_scale` | 1.0 |

Использованы естественные constraints и системная плотность/масштаб,
без LocalDensity override, фиксированной ширины или изменения настроек
экрана. Старые искусственные display-варианты не запускались.

| Метод | Варианты | Результат |
| --- | --- | --- |
| `CampaignGameUiTest.allSevenTasksRiddlesGroupsAndStatisticsAtNativeSize` | native | 1 PASS |
| `FoundWordsSectionTest.emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions` | legacy=false/true | 2 PASS |
| `FoundWordsSectionTest.largestInstalledLevelKeepsStatisticsReachableWithEveryGroupExpanded` | legacy=false/true | 2 PASS |

Проверены полный заголовок «Мастер · уровень 30 из 30», T1–T7, загадки,
группы найденных слов, статистика, восстановление прокрутки и stress-сценарии
на установленном словаре. Существующий `assertTextFits` обходит текстовые
узлы без исключений для букв или заголовка; проверки границ строк,
потери строк и многоточия сохранены. FAIL размещения текста не возник,
поэтому новые метрики проблемных узлов не снимались. Причина исторических
FAIL при искусственных параметрах этим native-прогоном не устанавливается.

## Снятие блокировки MIUI

Первая попытка через Gradle подтвердила Campaign — 1 PASS, но фильтр
по методу не выбрал параметризованный FoundWords. Последующие установки
перед отдельным запуском FoundWords были отклонены:
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`.
Это остановка до instrumentation, а не FAIL размещения текста.

При продолжении владелец подтвердил установку на телефоне и сообщил,
что выбрал «запомнить выбор». Оба APK успешно установлены командами
`adb install -r -t`; очистка данных и удаление приложения не выполнялись.

Первая прямая instrumentation завершилась до запуска тестов:
`Cannot prepare MIUI test Activity launches`. После команды разрешения
операции 10021 MIUI возвращала `No operations`, а существующий runner
проверяет явную строку `MIUIOP(10021): allow`.

Диагностика только target-пакета: временный `appops set ... 10021 allow`,
затем служебные `appops start` / `stop` зарегистрировали операцию.
Последующее чтение показало явный `allow`; исходный `ignore` восстановлен
до повторного запуска тестов. Код runner и его проверки не менялись.

Повторный прогон использовал существующий `WordsTestRunner`. По завершении
его лог и отдельный запрос `appops get` подтвердили восстановление
`ignore`. Доступ к установке и записи app-op больше не блокировали
выполнение всех пяти тестов.

## Команды повторного прогона

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell input keyevent KEYCODE_WAKEUP

ui_classes='com.example.sostavslova.ui.game.CampaignGameUiTest,com.example.sostavslova.ui.game.FoundWordsSectionTest'
ui_methods='.*(allSevenTasksRiddlesGroupsAndStatisticsAtNativeSize|emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions|largestInstalledLevelKeepsStatisticsReachableWithEveryGroupExpanded).*'
ui_runner='io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner'
adb shell "am instrument -w -r -e class $ui_classes -e tests_regex '$ui_methods' $ui_runner"
adb shell cmd appops get io.github.volmir.wordscombination 10021
```

Число и конечные статусы каждого теста проверены отдельно: ровно пять
завершённых тестов с кодом 0; итог — `OK (5 tests)`.
Сам по себе код возврата команды ADB не используется как признак PASS.

Локальные логи, не коммитятся:

- `/tmp/pre04-03-native-gradle.log` — первоначальный Campaign PASS;
- `/tmp/pre04-03-native-found-gradle.log`,
  `/tmp/pre04-03-native-install.log` — первоначальные отказы установки;
- `/tmp/pre04-03-native-prepare-failed.instrumentation` — остановка runner
  до регистрации операции;
- `/tmp/pre04-03-native-resumed.instrumentation` — итоговые 5 PASS.

## Изменения и ограничения

Изменён только этот отчёт. Остальные методы Campaign и полная
instrumented-сюита не запускались: задача требует representative G01
и проверки legacy/v2. Релиз, публикация, данные и права не менялись.

Полный gate (`--check full` и `assembleDebugAndroidTest`) и завершение
PRE04-03 выполняет оркестратор через `--backend codex --mark-done PRE04-03`;
результат фиксируется в `orchestrator/PROGRESS.md` и локальном gate-логе.
Checkpoint вручную не редактировался.
