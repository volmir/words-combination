# PRE04-10 — подготовка финала (G14)

Дата: 2026-11-17.

В `GameViewModelTest.finaleLevelsActionAcknowledgesBeforeNavigationAndDoesNotRepeat`
ответы записывались до миграции синтетической кампании. Сохранение оставалось без
`campaignId`, поэтому последующая настоящая миграция при открытии ViewModel
привязывала его к кампании и сбрасывала `finaleReached`.

В `app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt`
перед ответами добавлен вызов `progress.migrateCampaign(campaign, index)`.
Проверяются идентичность мигрированного сохранения и отсутствие финала на старте.
Затем существующая механика `submitCampaignAnswer` принимает ответы первого и
второго уровней, открывает второй уровень и достигает финала. Проверяется, что
финал достигнут, но ещё не подтверждён.

Сохранены проверки показа финала, подтверждения перед переходом к уровням и
отсутствия повторного показа при открытии новой ViewModel. Дополнительно после
повторного открытия проверяются сохранённые `finaleReached` и `finaleShown`.
Ручной установки `finaleReached` нет. Production-код, `CampaignRules`, общая
синтетическая кампания, ожидания и тайм-ауты не менялись.

## Проверки

- `bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`
  — `BUILD SUCCESSFUL`.
- Debug-APK приложения и тестов установлены через `adb install -r`.
  На подключённом устройстве выполнено:

  ```sh
  adb shell am instrument -w -r \
    -e class com.example.sostavslova.ui.game.GameViewModelTest#finaleLevelsActionAcknowledgesBeforeNavigationAndDoesNotRepeat \
    io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
  ```

  Результат: `OK (1 test)`, 0,868 с; G14 прошёл.
- `git diff --check` — без ошибок.

Логи: `/tmp/pre04-10-build.log`, `/tmp/pre04-10-g14.instrumentation`.

Полный gate оставлен оркестратору. Полная androidTest-сюита и другие устройства
не проверялись. Смежных изменений и обнаруженных рисков нет.
