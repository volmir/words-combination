# UX-12 — компактная панель подсказки

Выполнено только UX-12. Правила, цены, бюджет и порядок целей в `HintRules`,
`HintLadder`, `HintEconomy`, ожидания `HintRulesTest` и `HintLadderTest` не изменены.
`data/`, assets и защищённые пути оркестратора не изменены. Git-команды записи не выполнялись.

Изменённые файлы:

- `core/.../HintPanelModel.kt`: чистый снимок панели — идентификатор и ключ подписи
  цели, параметры локализации, «Авто» и доступные задания, выбранное предпочтение,
  фиксация, текущий шаблон, длина, открытые буквы, завершение, цена и остаток.
  Найденная цель скрывается, недоступное предпочтение отображается как «Авто».
- `core/.../HintPanelModelTest.kt`: девять JVM-тестов до/после первой подсказки,
  платной фиксации, завершения и нахождения ответа, единственного задания,
  отсутствия кандидатов, недоступного предпочтения, цен, подписей и миграции.
- `app/.../ui/game/GameViewModel.kt`: модель создаётся в `publishSession` на
  `computationDispatcher` при обновлении уровня; ввод символов её не пересчитывает.
- `app/.../ui/game/GameScreen.kt`: объединённая подпись и слоты, адаптивный заголовок,
  кнопка «Сменить задание», диалог `WordsDialog` с радиокнопками, состоянием selected,
  группой выбора и описаниями для TalkBack. Цели нажатия от 48 dp.
  Выбор передаётся прежнему `onHintTask` → `ProgressRepository.selectCampaignHintTask`
  → `HintRules.selectTask`, затем диалог закрывается. После платной ступени кнопка
  отключена и показано пояснение. Старые чипы и теги удалены; обязательные теги сохранены.
- `app/src/main/res/values/strings.xml`: новые подпись, кнопка и её описание;
  удалены ресурсы прежних отдельных подписей. Склонение букв через `RussianPlural`.
- `CampaignGameUiTest`, `MainScenariosUiTest`, `GameLayoutTest`, `ComponentLayoutTest`:
  новые теги и сценарии; проверяются объединённая подпись, диалог, selected,
  закрытие, блокировка, остаток после покупки, ширины 320/360/411 dp и fontScale 1/2.
- `GameViewModelTest`: проверка повторного использования экземпляра панели при вводе
  и обновления после выбора/покупки через настоящий репозиторий теста.
- `CampaignJourneyUiTest`: прежние проверки ступеней перенесены на объединённую подпись.
  `PaletteApplicationTest`: прежние проверки цветов сохранены для строк диалога.

Проверки (Gradle wrapper вызван через `bash`: у файла нет права исполнения):

- До реализации новый JVM-тест дал ожидаемую ошибку компиляции: модель ещё отсутствовала.
- `bash ./gradlew :core:test --tests 'com.example.sostavslova.core.HintPanelModelTest' --tests 'com.example.sostavslova.core.HintRulesTest' --tests 'com.example.sostavslova.core.HintLadderTest' --console=plain` — успешно, 9 + 25 + 5 тестов.
- `bash ./gradlew :app:compileDebugKotlin --console=plain` — успешно.
- `bash ./gradlew :core:test :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain` — успешно; 243 JVM-теста, без ошибок и пропусков. Все androidTest компилируются. Lint: 0 ошибок, 14 предупреждений.
- `git diff --check` — успешно.
- `python3 tools/check_docs.py` — успешно.

Проверка композиции:

```bash
sed -n '/^private fun CampaignHint(/,/^internal fun HintTaskDialog(/p' app/src/main/kotlin/com/example/sostavslova/ui/game/GameScreen.kt | rg -n 'HintRules|allWords|taskOptions|currentStep|restore\('
```

Совпадений нет (`rg` возвращает 1): правил и перебора слов в `CampaignHint` нет.
Поиск старых `game_hint_task_options`, `game_hint_task_auto`, `game_hint_stage`,
`game_hint_length` и `HintTaskChip` в main/androidTest также не дал совпадений.

Не проверено: запуск инструментальных тестов, реальный TalkBack и визуальная
приёмка на устройстве, включая 320 dp / fontScale 2.0: `adb devices` показал пустой
список. Проверки разметки добавлены и скомпилированы, но не запускались.
Gate оркестратора и сборка APK в этом задании не запускались — gate выполняет внешний процесс.

Смежная находка в рамках задачи: платная кнопка раньше подписывала «останется»
текущим остатком. Теперь показывает `max(0, remaining − cost)`, как требует UX-12;
списание в правилах не менялось. Других смежных изменений нет.
