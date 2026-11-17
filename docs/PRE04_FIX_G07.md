# PRE04-08 — актуализация стоимости подсказки (G07)

Дата: 2026-11-17.

В `CampaignJourneyUiTest.newInstallToFinaleRestartAndAllStars` исправлены
ожидания для действующей fallback-лестницы. Fixture `QaCampaign.Meanings.SAFE`
возвращает тему и синтетическое определение с `origin="wiktionary"`.
Приложение исключает такое определение; добавлять его обратно или заменять
собственным ради старых ожиданий не требуется.

| Этап | Стоимость шага | Накопленный расход | Остаток из 5 |
|---|---:|---:|---:|
| THEME | 0 | 0 | 5 |
| LENGTH | 0 | 0 | 5 |
| FIRST_LETTER | 1 | 1 | 4 |
| ANSWER | 1 | 2 | 3 |

Изменён только тест `app/src/androidTest/kotlin/com/example/sostavslova/qa/CampaignJourneyUiTest.kt`:

- Явно указан используемый вариант fixture и причина fallback.
- После каждого запроса проверяются полный порядок лестницы, показанный
  префикс этапов и накопленный расход `0, 0, 1, 2`.
- UI проверяется на «открыто N из 4 частей», наличие текущей части и
  отсутствие следующей. До ANSWER слово «кот» отсутствует в показанных
  текстах сохранённой лестницы и в semantics UI; на ANSWER оно появляется.
- После restart сохранена проверка равенства прогресса; ожидания обновлены
  с «4 из 5» на «4 из 4 частей» и с остатка 4 на 3.
- Сохранены последующие проверки ответов, финала, повторного запуска и
  сбора всех звёзд.

`HintRules`, цены, production UI, общая fixture `QaCampaign`, данные и
тайм-ауты не изменялись. Экономика подсказок осталась прежней.

## Проверки

- `bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`
  — BUILD SUCCESSFUL.
- `bash ./gradlew :core:test --tests 'com.example.sostavslova.core.HintRulesTest' --console=plain`
  — BUILD SUCCESSFUL, 22 теста, 0 ошибок/падений/пропусков.
- Оба debug-APK установлены через `adb install -r`. На подключённом
  смартфоне Android 13 выполнено:

  ```sh
  adb shell am instrument -w -r \
    -e class com.example.sostavslova.qa.CampaignJourneyUiTest#newInstallToFinaleRestartAndAllStars \
    io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
  ```

  Результат: `OK (1 test)`, 23,74 с. Пройден весь метод, включая проверки
  после подсказок, восстановление прогресса, финал и все звёзды.
  Перед запуском устройство выведено из Dozing через KEYCODE_WAKEUP.
- `git diff --check` — без ошибок.

Логи: `/tmp/pre04-08-build.log`, `/tmp/pre04-08-hint-rules.log`,
`/tmp/pre04-08-g07.instrumentation`.

Прямой вызов `./gradlew` не стартовал: у файла нет executable-бита.
Проверки успешно выполнены через `bash ./gradlew`; права файла не менялись.
Полный gate оставлен оркестратору. Полная androidTest-сюита и другие устройства
не проверялись. Смежных изменений нет; следующие задания не выполнялись.
