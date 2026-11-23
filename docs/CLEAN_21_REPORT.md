# CLEAN-21 — Наследие эпохи определений

## Аудит до удаления

Поиск выполнен `rg -n` по именам элементов и полям в `core/`, `app/`,
`tools/`, Gradle-файлах, README, AGENTS.md и конфигурации gate
(последняя прочитана без изменения). Проверены конструкторы метаданных,
DTO сохранений, пути миграции и вызовы инструментов подготовки.

| Элемент | Использовался | Удалён / оставлен и почему |
|---|---|---|
| `DefinitionHint.kt`, `DefinitionHintTest`, `DefinitionHintDatasetAuditTest` | Не runtime; `CampaignDatasetAudit`, `HintTextTest`, `OwnedHintsTest`, `OwnedRiddlesAssetTest`, `tools/prepare_v2.py`, `pick_level_words.py`, `validate_campaign.py`, `build_local_definitions.py` | Оставлены: Gradle-аудиты, тестовый gate и проверки текстов; удаление затронуло бы подготовку данных и проверки прав. Для этого элемента удаление остановлено. |
| `auditDefinitionHints`, `DefinitionHintDatasetAudit.kt` | Gradle JavaExec; вызов из `tools/prepare_v2.py`, экспорт/контроль версий в `pick_level_words.py` | Оставлены: действующие потребители подготовки данных и аудита, не мёртвый код. |
| `Levels.kt`: `Level`, `LevelProgress`, `LevelRules.evaluate`, `lettersCount`, объединение и число звёзд | `ProgressRepository`: legacy DTO, миграция, старые операции; `TestData`, тесты v1 | Оставлены: совместимость v1→v2. `LegacyCampaignV1` и весь код чтения сохранений сохранены. |
| `LevelRules.remainingToComplete`, `LevelRules.isUnlocked` | Только собственные проверки в `LevelRulesTest`; приложение, миграция, Gradle и инструменты их не вызывают | Удалены вместе с двумя утверждениями remaining и тестом `unlockSequentially`. Проверки достижимости и расчёта/объединения звёзд сохраняются. |
| `HintWordData.category`, `HintCategory`, `undesirableLabels` | Только объявления и аргументы фикстур `HintRulesTest`, нет чтений; приложение не заполняет, DTO не сериализует | Удалены; из фикстур убраны только эти аргументы, все утверждения сохраняются. Имена двух тестов уточнены. |
| `HintWordData.definition`, `forms`, `related` | `CampaignDatasetAudit` читает и повторно проверяет T4; `OwnedHintsTest` проверяет утверждённые тексты; фикстуры `HintRulesTest` | Оставлены: используются Gradle-аудитом и gate; изменение затронуло бы аудит текстов. |
| `HintWordData.familiar` | `WordRepository.hintWordData`, выбор цели в `HintRules`, FAM-15 | Оставлено без изменений: действующее поле. |
| `HintWordData.knownPositions` | `HintRules.restore`, `HintLadder.build`, `HintLadderTest` | Оставлено: действующая логика открытых позиций, даже при отсутствии заполнения в приложении. |
| `HintStage.THEME`, `DEFINITION`, `LENGTH`; старые поля `HintStep` | `CampaignProgressDto.toCore`: `HintStage.valueOf`; очистка/перевод старой лестницы в `HintLadder` | Оставлены: старые JSON должны декодироваться до миграции. Буквенные значения также используются runtime. |
| `data/levels.json` | `verifyDictionaryLicenses`: SHA manifest; `syncDictionaryAssets`, набор разрешённых assets; `TestData`, инструменты подготовки | Оставлен без изменений. Для элемента удаление остановлено: затрагивает данные и лицензионную проверку. |
| `data/golden_search.json` | Тестовые данные и инструменты | Оставлен побайтово по решению владельца. |
| `frequent_nouns_golden.txt` | Уже отсутствует после GOLD-03A | Изменений нет. |

## Границы изменений

Нового поведения нет. Удалены только неиспользуемые метаданные и методы
интерфейса v1. Для метаданных не было сохраняемого формата: DTO хранят
`HintTarget`/`HintStep`/`HintLadder`, а не `HintWordData`.
`LegacyCampaignMigrationTest` и `HintLadderTest` не редактируются.
Данные, правовые документы и документы владельца, конфигурация Gradle,
проверки лицензий и защищённые пути не меняются. README и AGENTS.md
уточняют оставшуюся область совместимости и аудита.

## Проверки

- `bash ./gradlew --offline :core:test --tests 'com.example.sostavslova.core.HintRulesTest' --tests 'com.example.sostavslova.core.LevelRulesTest' --tests 'com.example.sostavslova.core.HintLadderTest' --console=plain` — успешно.
- `bash ./gradlew --offline :core:test :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain` — успешно; 262 core-теста в 36 классах, без ошибок. В составе сборки прошли `verifyDictionaryLicenses`, `verifyOwnedHints`, `verifyRuntimeNotices`, `verifyPrivacyPublication`, `verifyGraphics`.
- `python3 tools/check_docs.py` — успешно.
- `python3 -m unittest discover -s tools/tests -p test_spec_campaign_numbers.py` — 3 теста, успешно.
- `git diff --check` — успешно. `git diff --exit-code` для `data/`, `legal/`, `orchestrator/`, `LegacyCampaignMigrationTest.kt`, `HintLadderTest.kt` — пустой diff.
- Повторный `rg` по удалённым символам в `core/`, `app/`, README и AGENTS.md — совпадений нет.

Инструментальные тесты на устройстве не запускались: выполнена их компиляция.
Команды gate через `orchestrator/run.sh` не запускались: внешний оркестратор
выполнит gate сам. Его служебные файлы не изменялись. Изменений Gradle,
release-конфигурации, данных и прав нет; release-сборка не запускалась.
