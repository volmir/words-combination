> **Историческое свидетельство до перехода на JSON (2026-11-17).**
> Описанные здесь прежний формат, команды и статусы не задают текущую сборку.
> Действующий контракт: [OWN_HINTS_TOOLCHAIN.md](OWN_HINTS_TOOLCHAIN.md). Исходные правовые выводы сохранены для прослеживаемости.

# LCH-01 — удаление остатков словарного интерфейса

Дата: 2026-11-16. Выполнено только LCH-01, без коммитов, push, смены ветки
и изменений защищённых путей.

## Исходное состояние

По `orchestrator/PROGRESS.md` и коду текущего HEAD:

- APP-04 уже удалил экран поиска составимых слов, его маршрут, пункт меню,
  UI-обёртки, строки и соответствующий UI-тест. Игровой `WordSearch`,
  `WordRepository.search`, `SearchResult` и `golden_search` сохранены.
- APP-05 уже удалил пользовательский каталог компонентов debug/release,
  footer, previews, ресурсы и секцию «Сервисы». Тесты рендерят компоненты напрямую.
- Фактически оставались самостоятельный экран «Значение слова» и его маршрут/
  пункт меню, а также диалог полного определения по нажатию на найденное слово.

APP-04/APP-05 не восстанавливались и не переделывались.

## Изменённые файлы

Пути ниже относительно корня проекта. Kotlin production-файлы находятся в
`app/src/main/kotlin/com/example/sostavslova/`:

| Файлы | Изменение |
|---|---|
| `nav/Destination.kt`, `nav/WordsNavHost.kt` | Удалены цель и маршрут WordMeaning; остаются карта, уровень, правила и сведения об игре. |
| `ui/components/WordsDrawer.kt` | Удалён пункт «Значение слова», независимо от наличия БД. |
| `ui/screens/Screens.kt` | Удалена обёртка WordMeaningScreen. Правила, сведения об игре и лицензионная атрибуция сохранены. |
| `ui/meaning/WordMeaningScreen.kt`, `ui/meaning/WordMeaningViewModel.kt` | Удалены самостоятельный экран, состояние и обработчики поиска значений. |
| `ui/game/GameScreen.kt` | Удалены callback открытия определения и ветка диалога; найденные слова выводятся обычным текстом в прежних группах. |
| `ui/game/GameViewModel.kt` | Удалены onDefinition, отдельная job, поля состояния определения и GameDialog.Definition. Состояние сохранений не менялось. |
| `ui/components/WordLinks.kt` → `ui/components/FoundWords.kt` | Компонент переименован; удалены действие нажатия и семантика кнопки. Размещение, отступы, размеры, цвет и типографика сохранены. |
| `ui/components/WordsDialogs.kt` | Удалён только DefinitionDialog; общий WordsDialog остаётся для игровых окон. |

`app/src/main/res/values/strings.xml`: удалены девять ненужных строк —
`nav_word_meaning`, `show_word_meaning`, `definition_not_found`,
`definition_loading`, `enter_word`, `search_button`, `word_input_invalid`,
`word_too_long` (последним потребителем был удалённый экран) и оставшаяся
поисковая строка `nothing_found`.

Изменённые тесты находятся в
`app/src/androidTest/kotlin/com/example/sostavslova/ui/`:

| Файлы | Проверяемое поведение |
|---|---|
| `components/ComponentInteractionTest.kt` | В меню нет словарных пунктов, набор маршрутов ограничен игровыми и справочными; найденные слова видны и не имеют действия нажатия. |
| `components/ComponentLayoutTest.kt` | Удалена фикстура удалённого DefinitionDialog, WordLinks заменён на FoundWords; проверки раскладки остальных компонентов сохранены. |
| `game/GameNavigationTest.kt` | После принятого ответа нажатие на найденное слово не открывает значение; сохраняются ответ, группы и статистика. |
| `game/CampaignGameUiTest.kt` | Проверены отсутствие действия у найденного слова, сворачивание групп и статистика. Сохранена проверка смыслового шага лестницы; T4 дополнен смысловым текстом и атрибуцией с переходом к следующей буквенной загадке после решения. |
| `game/GameLayoutTest.kt` | Адаптированы сигнатура GameScreen и набор доступных диалогов; проверки шрифтов, подсказки и размеров целей сохранены. |
| `game/GameViewModelTest.kt` | Тест удалённого диалога заменён проверкой статистики/заданий: прогресс не меняется, определений не читают. Существующие тесты смысловых подсказок, маскирования, сохранения лестницы и T4 сохранены. |

Этот отчёт: `docs/LCH01_REPORT.md`.

Удаление тестов DefinitionDialog и смена ожидания нажатия — следствие явно
удалённого пользовательского функционала. Игровые критерии не ослаблялись.

## Сохранённые границы

Не менялись `data/`, `core/`, репозитории данных/определений и DI, формат и ключи
пользовательских сохранений. Игровой поиск, WordIndex.findBuildable,
SearchResult, WordRepository.search, golden_search, DefinitionsRepository,
SqliteDefinitionsRepository, HintLadder, T3/T4/T6, категории и маскирование
сохранены. Кампания остаётся 15 уровней / 49 заданий; цели и условия R/M
не менялись. Анимации, звук, вибрация, зависимости и разрешения не добавлялись.
`DefinitionBlock` остаётся нужен для игровых подсказок; лицензионный раздел
«Словарь и определения» в сведениях об игре не является экраном поиска.

## Проверки

- `adb devices` — список устройств пуст. **Android UI tests NOT RUN**;
  `connectedDebugAndroidTest` и ручная проверка не выполнялись.
- `bash ./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug :core:test --console=plain`
  — BUILD SUCCESSFUL. Kotlin приложения и androidTest скомпилированы,
  lint прошёл. В этом запуске `:core:test` был UP-TO-DATE.
- `bash ./gradlew --offline :app:compileDebugAndroidTestKotlin :app:lintDebug :core:test --rerun --console=plain`
  — BUILD SUCCESSFUL после уточнения теста счётчиком обращений к определениям.
  **137 тестов :core исполнены заново, 0 failures/errors/skipped**.
  В том числе HintRulesTest — 21, DefinitionHintTest — 7, TaskRulesTest — 14,
  WordSearchTest — 8: смысловые шаги, маскирование, правила T4 и игровой поиск.
- `bash ./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain`
  — BUILD SUCCESSFUL после удаления двух выявленных мёртвых строк.
  Итоговый lint: 0 ошибок, 9 предупреждений.
- `git diff --check` — без ошибок. Статический поиск не находит удалённых
  экранов, маршрутов, DefinitionDialog, WordLinks и onDefinition в коде приложения.

Компиляция androidTest **не означает выполнения Android-тестов**. Существующие
GameViewModelTest (включая смысловые подсказки и T4) и Compose-тесты подготовлены
к запуску на Android, но здесь не исполнялись. Полный gate и сборку APK
в этой задаче не запускали: gate выполняет внешний оркестратор.

## Ограничения и смежные находки

- Поведение интерфейса на устройстве остаётся непроверенным: нет устройства/эмулятора.
- Ручной аудит прав на определения и разрешение публикации эта задача не меняет.
- Несвязанные lint-предупреждения не исправлялись в рамках LCH-01.
