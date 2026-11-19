# ARCH-01: идентичность сохранений и миграция прогресса

Дата: 2026-11-19. Ориентир: `08293d7`; HEAD не менялся.
`data/`, словарь, `campaign.json`, пользовательский баланс, `applicationId`,
подпись, версия релиза и F-Droid metadata не изменялись. Новых зависимостей и
разрешений нет. Коммит/push не выполнялись.

## 1. Аудит: где SHA файла использовался как идентификатор

| Место | Было | Стало |
|---|---|---|
| `ProgressRepository.migrateCampaign` | `identity = campaign.contentSha256`; при любом несовпадении архив всего прогресса и пустой снимок `campaign-mismatch` | `CampaignIdentity.of(campaign)` + `CampaignProgressReconciler`; адресно по слову уровня |
| `PlayTimeRepository.identity` / `key` | требовал SHA файла; ключ `playTimeV1:<sha>:<номер>:<слово>` | требует слова уровней; ключ `playTimeV1:<words-id>:<слово>` |
| `PlayTimeTracker.PlayTimeTarget.identity` | SHA файла + номер + слово | words-ID + номер + слово (только в памяти) |
| `PlayTimeTracker.checkpointProgress` | `previous.campaign.contentSha256 == progress.campaignId` | words-ID или прежний `contentSha256` |
| `StatsViewModel.statsUiState` | `progress.campaignId == campaign.contentSha256` | `CampaignIdentity.of(campaign)` |
| `CampaignTimeStatistics.aggregate` | требовал `campaignSha256` и равенство SHA файла | требует `CampaignTimeSnapshot.campaignId == CampaignIdentity.of(campaign)` |

`Campaign.contentSha256` оставлен в `Campaign` и `LevelsRepository` как
контрольная сумма установленного файла (`BuildConfig.CAMPAIGN_SHA256`).
`data/manifest.json` и проверки лицензий не менялись.

Ключи уровней уже были исходными словами
(`CampaignProgress.levels: Map<String, CampaignLevelProgress>`), поэтому схема
прогресса не переписывалась. Номер→слово не угадывается: для сохранений с
числовыми ключами Reconciler принимает только явную карту
`historicalLevelWords`; иначе уровень уходит в диагностический сброс, а
исходный payload — в архив.

## 2. Новая идентичность

`core/CampaignIdentity.kt`:

1. исходные слова уровней;
2. `Alphabet.normalize` (ё→е, нижний регистр);
3. проверка непустоты и уникальности после нормализации;
4. лексикографическая сортировка;
5. соединение `\n`;
6. SHA-256 UTF-8;
7. префикс `words:`.

Порядок уровней не участвует: перестановка двух уровней даёт тот же ID.
Константы совместимости (не вычисляются из текущего файла):

- `LEGACY_SHORT15_SHA = 1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e`;
- `LEGACY_SHORT15_RELEASE_SHA = 995e8c8d454dfe1c50a7c68b8c6d3308716263d41183e4bf6b06fd73990451e4`.

Любой прежний campaignId (64 hex, `null`, известные константы) считается
совместимым кандидатом; решение принимается по словам, а не по строке ID.
Поэтому миграция не ограничена одним историческим SHA.

## 3. Reconciler

`core/CampaignProgressReconciler.kt` — чистая функция без Android/DataStore.
Вход: сохранение, новая кампания, `WordIndex`, необязательные прежняя
конфигурация и историческая карта номеров. Выход: новое состояние, список
машинных преобразований, частичные сбросы, причина несовместимости.

Правила:

- найденные слова восстанавливаются через `GameState.start`; недопустимые
  согласно словарю/буквам удаляются, остальные сохраняются;
- звезда существующего задания сохраняется, даже если порог вырос;
- новое задание может сразу выполниться по сохранённым `found`;
- удалённое задание исключается; звезда не переносится на другое задание;
- при переиспользовании ID с иной семантикой старая звезда не присваивается
  новому заданию: проверяется суффикс типа ID (`-t1`…`-t7`), а при известной
  прежней конфигурации — ещё и полные параметры задания;
- `highestUnlockedLevel = max(сохранённый, frontier)` и не уменьшается;
- `finaleReached`/`finaleShown` не сбрасываются;
- лестница подсказки сбрасывается только если цель/задание исчезли;
  `shownSteps` и `paidUnits` сохраняются;
- изменившееся слово сбрасывает только свой уровень, остальные переносятся;
- полный сброс — только нечитаемое сохранение или ни одного совпавшего слова.

`ProgressRepository` архивирует исходный JSON при частичной потере данных
(смена слова, отфильтрованные слова, смена семантики задания) и ставит
диагностический `resetReason = campaign-reconciled:<слова>`. Повторная
миграция идемпотентна и причину не теряет.

## 4. Время игры

- Ключ нового формата адресуется словом, поэтому перестановка/смена номеров
  уровней не теряет замеры.
- Старые ключи `playTimeV1:<sha>:<номер>:<слово>` читаются как fallback без
  записи; при следующей записи создаётся новый ключ. Старые ключи не удаляются.
- Если для слова есть несколько разных старых записей, значение не выбирается
  наугад: уровень считается неизмеренным, данные остаются на диске.
- Изменение порогов/числа заданий больше не отвергает запись: структурные
  проверки сохранены, флаги `rAccounted`/`fullAccounted` только дополняются,
  накопленное время и milestone не обнуляются.
- `CampaignTimeSnapshot.campaignSha256` переименован в `campaignId`.

## 5. Изменённые файлы

`:core`: `CampaignIdentity.kt` (новый), `CampaignProgressReconciler.kt` (новый),
`Campaign.kt`, `CampaignRules.kt`, `CampaignTimeStatistics.kt`,
`CampaignIdentityTest.kt` (новый), `CampaignProgressReconcilerTest.kt` (новый),
`CampaignTimeStatisticsTest.kt`, `CampaignStatisticsAssemblerTest.kt`.

`:app`: `ProgressRepository.kt`, `PlayTimeRepository.kt`, `PlayTimeTracker.kt`,
`StatsViewModel.kt`; обновлены `LegacyCampaignMigrationTest`,
`PlayTimeRepositoryTest`, `PlayTimeAcceptanceTest`, `CampaignJsonUpgradeTest`,
`PlayTimeTrackerTest`, `GameViewModelTest`, `OwnedRiddlesUiTest`,
`QaCampaignStorageTest`, `StatsPresentationTest`, `StatsDataFlowTest`.
`CampaignProgressRepositoryTest` правок не потребовал.

Документы: `README.md`, `docs/PROJECT_STATUS.md`, `AGENTS.md` (п. 12).
Исторические отчёты не переписывались.

## 6. Проверки

Выполнено:

- `bash gradlew :core:test --tests CampaignIdentityTest --tests CampaignProgressReconcilerTest` — успешно (19 тестов).
- `bash gradlew :core:test` — успешно.
- `bash orchestrator/run.sh --check android` — успешно
  (`compileDebugKotlin`, `lintDebug`, `compileDebugAndroidTestKotlin`).
- `bash orchestrator/run.sh --check full` — успешно: `tools/tests`,
  `orchestrator/tests`, `:core:test`, `:app:assembleDebug`,
  `verifyDictionaryLicenses`, `verifyOwnedHints`, `verifyRuntimeNotices`,
  `verifyPrivacyPublication`.

Тесты, которые проверяли сброс при смене SHA файла или отказ при
`contentSha256 = null`/невалидном SHA, заменены на проверки адресного
сохранения и валидации слов кампании. Это следствие нового контракта
идентичности, а не ослабление проверок.

Покрытые JVM-сценарии Reconciler: старый SHA без потерь; изменённый порог;
изменённый список T6; удалённое задание; новое задание, выполненное по found;
изменённое слово одного уровня; перестановка уровней; неуменьшение frontier;
сохранение финала; полный сброс при чужих словах; переиспользованный ID с
другой семантикой; сброс лестницы подсказки; фильтрация недопустимых слов;
числовые ключи без истории и с явной картой.

## 7. Не проверено

- `connectedDebugAndroidTest` не запускался: в среде нет эмулятора/устройства.
  Android-тесты обновлены и компилируются, но их выполнение на устройстве
  остаётся за владельцем.
- Реальные сохранения предыдущих релизов на устройстве не прогонялись.
- Миграция времени с реального файла `play_time.json` проверена тестами, но не
  ручным обновлением APK поверх старой установки.

## 8. Риски и смежные находки

- `migrateCampaign` вызывается также на открытии игры; теперь он пересобирает
  `GameState` для сохранённых уровней. Работа идёт вне UI-потока, но это
  дороже прежней проверки SHA. При заметной задержке стоит кэшировать
  согласованный снимок по `contentSha256` (отдельная задача).
- Полную семантику переиспользованного ID можно отличить только при известной
  прежней конфигурации. Без неё Reconciler проверяет суффикс типа ID
  (`-t1`…`-t7`); произвольные ID без суффикса считаются совместимыми.
- Прежние `playTimeV1:*` ключи не удаляются и занимают место; это осознанная
  страховка на время перехода.
- Старый статический кандидат `docs/fdroiddata/...yml` (1.0.2) остаётся
  историческим и не отражает фактическую заявку 1.0.3.
