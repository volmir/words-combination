# PRE04-11 — системный Back в диалоге выхода (G09)

Дата: 2026-11-17.

В `app/src/androidTest/kotlin/com/example/sostavslova/ui/ExitNavigationTest.kt`
helper `back()` вызывал `Activity.onBackPressedDispatcher` напрямую. Открытый
`AlertDialog` имеет отдельное окно, поэтому такой вызов обходил обработку Back
диалогом и оставлял его открытым.

Helper заменён на `Espresso.pressBack()` из уже подключённой тестовой зависимости.
Этот API отправляет Back-событие в активное окно и вызывается из тестового потока,
без `runOnUiThread`. Он используется и для закрытия drawer, и для диалога.

В G09 сохранены проверки `findActivity`, исчезновения диалога и `isFinishing=false`.
Дополнительно зарегистрирована незавершённая операция через существующий
`ProgressRepository.trackOperation(Job)`: перед Back подтверждение недоступно,
сообщения об успешном сохранении нет. После Back операция остаётся незавершённой.
После её явного завершения диалог не появляется снова, Activity не завершается,
меню открывается и показывает пункт выхода. `finally` завершает тестовую операцию
также при падении assertions. Проверки сохранения и пересоздания в остальных
тестах сохранены.

Production-навигация, сохранение, зависимости и тайм-ауты не менялись.

## Проверки

- `bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`
  — `BUILD SUCCESSFUL`.
- APK приложения и тестов установлены на подключённое устройство командами
  `adb install -r app/build/outputs/apk/debug/app-debug.apk` и
  `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  — обе `Success`.
- Отдельный G09:

  ```sh
  adb shell am instrument -w -r \
    -e class com.example.sostavslova.ui.ExitNavigationTest#systemBackDismissesDialogAndActivityIsFoundThroughWrappers \
    io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
  ```

  — `OK (1 test)`, 4,389 с.
- Весь класс:

  ```sh
  adb shell am instrument -w -r \
    -e class com.example.sostavslova.ui.ExitNavigationTest \
    io.github.volmir.wordscombination.test/com.example.sostavslova.WordsTestRunner
  ```

  — `OK (3 tests)`, 10,5 с.
- `git diff --check` — без ошибок.

Первый запуск G09 завершился в `setUp` с `No compose hierarchies found`, до
проверки Back. Диагностика устройства показала `mWakefulness=Dozing`.
После `adb shell input keyevent KEYCODE_WAKEUP` и `adb shell wm dismiss-keyguard`
устройство перешло в `Awake`; повторный запуск и весь класс прошли без изменения
тестовых ожиданий. Логи: `/tmp/pre04-11-build.log`,
`/tmp/pre04-11-g09.instrumentation`, `/tmp/pre04-11-g09-awake.instrumentation`,
`/tmp/pre04-11-exit-navigation.instrumentation`.

Полный gate оставлен оркестратору. Полная androidTest-сюита и другие устройства
не проверялись. Смежных изменений и обнаруженных production-дефектов нет.
