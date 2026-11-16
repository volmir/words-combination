# UXR-01 — отказ при недостающих буквах

В начале выполнения `git status --short` и `git diff --stat` были пустыми: незакоммиченных правок предыдущей попытки не обнаружено. Текущий код всё ещё терял `missingLetter` и `probabilistic` при преобразовании `TaskSubmission` в `Submission` внутри `GameViewModel.onSubmit()`. Ветка `LETTERS_MISSING` затем молча завершалась из-за `missingLetter == null`.

## Изменения

- `app/src/main/kotlin/com/example/sostavslova/ui/game/GameViewModel.kt`: оба поля передаются в полном объёме; отображение результата вынесено в проверяемую функцию `submissionMessage`. Вероятностное совпадение выбирает нейтральную формулировку без утверждения о наличии слова в словаре. Отсутствующая информация о букве даёт статическое сообщение вместо молчаливого выхода.
- `app/src/main/kotlin/com/example/sostavslova/ui/game/GameScreen.kt`: баннер показывает заглавную букву и реальные количества из результата. Верх уровня, форма, место feedback и существующие подсказки не изменены; сохранена семантика `AlertBanner` с `liveRegion=Polite`.
- `app/src/main/res/values/strings.xml`: точное, вероятностное и резервное сообщения. Для точного случая: «Слово „бык“ есть в словаре, но его нельзя составить из букв этого уровня. Не хватает буквы „Б“: есть 0, нужно 1». Сообщение описывает одну нехватку, не утверждая полноту списка.
- `core/src/test/kotlin/com/example/sostavslova/core/SubmissionTest.kt`: регрессия по действительному словарю для «бык» / «вегетарианец» через `GameState` и `TaskRules`, повторная отправка, неизменность состояния и передача вероятностного результата.
- `app/src/androidTest/kotlin/com/example/sostavslova/data/CampaignProgressRepositoryTest.kt`: передача результата через реальный DataStore, повторная отправка и отсутствие изменения прогресса при отказе.
- `app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt`: действительный словарь, нехватка «б», повторная отправка, сброс busy, сохранение состояния, неизвестное слово, короткое слово, исходник, правильный трёхбуквенный ответ и его повтор.
- `app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameSubmissionUiTest.kt`: цепочка `GameState → TaskRules → ProgressRepository → GameViewModel → GameBanner` с действительным словарём; точный текст внутри `game_feedback`, доступность сообщения для TalkBack, повторная отправка и доступность поля. Дополнительно — разные количества, несколько нехваток, вероятностная формулировка, резервный текст и сохранение категорий ответов. `NOT_COUNTED` проверяется на отдельном снимке с составимым словом точного словаря, исключённым из `allWords`: обычный старт уровня включает все составимые слова, поэтому такой снимок создаётся в тесте явно.

## Проверки

- `./gradlew ...` — первая попытка не запустилась: у файла `gradlew` нет права исполнения. Права не менялись, последующие вызовы выполнены через `bash gradlew`.
- `bash gradlew :core:test --tests 'com.example.sostavslova.core.SubmissionTest' --console=plain` — PASS.
- `bash gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain` — PASS.
- `bash gradlew :core:test :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain` — PASS; 144 теста core, 0 ошибок.
- `git diff --check` — PASS.
- `adb devices` — список устройств пуст.

Инструментальные тесты скомпилированы, но не выполнены; ручной прогон и фактическое озвучивание TalkBack не проверены из-за отсутствия устройства/эмулятора. Полный gate оставлен оркестратору по условию задания. Правила зачёта, данные, кампания, подсказки и защищённые пути не менялись. Команды изменения Git-истории, commit/push/add/checkout/reset не выполнялись. Новые зависимости, разрешения, анимации и другие аудиовизуальные эффекты не добавлены.
