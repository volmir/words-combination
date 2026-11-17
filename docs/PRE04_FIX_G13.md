# PRE04-09 — маршрут синтетической кампании (G13)

Дата: 2026-11-17.

В `GameViewModelTest.importedTextsNeverEnterLegacyHintCampaignRiddleOrLadder`
вызов `seed("портал")` выбирал legacy-маршрут `2`. После `progress.clearAll()`
новая `MiniCampaign` не имела прогресса, поэтому её уровень 2 был закрыт.
Новая модель наследовала прежний маршрут, и `ready()` ожидал недостижимое
игровое состояние.

В `app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt`
при создании модели кампании явно передан `SavedStateHandle` с `index="1"` —
открытый первый уровень новой синтетической кампании. Общие помощники,
production-код и правила открытия уровней не изменены.

Сохранены все проверки исключения импортированных сторонних текстов:
пустые определения legacy hint, отсутствие текста и признака Wiktionary
у riddles, этапы THEME → LENGTH и нулевая стоимость campaign ladder,
отсутствие синтетического маркера во всём состоянии кампании.
Ожидания и тайм-ауты не менялись.

## Проверки

- `bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`
  — BUILD SUCCESSFUL.
- Debug-APK приложения и тестов установлены через `adb install -r`.
  На подключённом устройстве выполнено:

  ```sh
  adb shell am instrument -w -r \
    -e class com.example.sostavslova.ui.game.GameViewModelTest#importedTextsNeverEnterLegacyHintCampaignRiddleOrLadder \
    io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
  ```

  Результат: `OK (1 test)`, 0,651 с; пройден весь G13.
- `git diff --check` — без ошибок.

Логи: `/tmp/pre04-09-build.log`, `/tmp/pre04-09-g13.instrumentation`.

Полный gate оставлен оркестратору. Полная androidTest-сюита и другие устройства
не проверялись. Смежных изменений и обнаруженных рисков нет.
