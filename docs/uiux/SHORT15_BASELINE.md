# SHORT15 baseline — исходный UI и контракт короткой кампании (UX15-01)

Дата снимка: 2026-11-15. Только анализ: код не менялся. Факты сверены с
`data/campaign.json` (SHA-256 `eb440873…1fadc`, совпадает с
`data/manifest.json:campaign_source.sha256`), `docs/PROJECT_STATUS.md` и
исходниками `:app`/`:core`. Документы о 30 уровнях ниже помечены как история,
где это применимо.

## 1. Подтверждение контракта short15 по фактическим данным

| Параметр | Ожидание | Факт (`data/campaign.json`) | Статус |
|---|---|---|---|
| Число уровней N | 15 | 15 | OK |
| Этапы | 6, размеры `[3,2,3,2,3,2]` | `stages` = 1–3, 4–5, 6–8, 9–10, 11–13, 14–15 | OK |
| Типы заданий | T1–T7 все встречаются | все 7 типов присутствуют; T5 впервые на 11, T6 на 14, T7 на 15; `introductions` = T1@1, T2@4, T3@6, T4@9, T5@11, T6@14, T7@15 | OK |
| Суммарные задания/звёзды | 49 | `1+1+1+2+2+3+3+3+4+4+5+5+5+5+5 = 49`; `Campaign.maxStars` (`core/.../Campaign.kt`) = сумма `tasks.size` | OK |
| R по уровням | 1,1,1,2,2,3,3,3,3,3,4,4,4,4,4 | `requiredStars` из campaign.json совпадает | OK |
| Финал | 4/5 на уровне 15 | уровень 15: M=5, R=4; `CampaignRules.recordLevel` ставит `finaleReached` при прохождении последнего уровня (`CampaignRules.kt`) | OK |
| Мастер | 49/49 | `CampaignSummary.isMaster = stars == maxStars` (`CampaignRules.kt`); тест `Short15ProductionCampaignTest.fourOfFiveReachesFinaleButMasterNeedsAllFortyNine` проверяет 48≠master, 49=master | OK |
| `countMinLength` | 4 (T1/T2) | 4 в campaign.json | OK |

Покрытие типов по уровням (M): 1–3: `[T1]`; 4–5: `[T1,T2]`; 6–8: `[T1,T2,T3]`;
9–10: `[T1–T4]`; 11–13: `[T1–T5]`; 14: `[T1,T2,T4,T5,T6]`; 15: `[T1,T2,T3,T5,T7]`.
Исходники: чемпионство, вегетарианец, аккумулятор, благословение, благоразумие,
безрассудство, журналистика, поздравление, благодарность, пуэрториканец,
подстрекатель, проблематика, складирование, контрабандист, документалист.

### BLOCKER-расхождения документов (данные не подменяются)

- **BLOCKER (док., не данные):** `docs/CAMPAIGN.md` в разделах «Этапы и задания»,
  «Звёзды, доступ и ответы» и «Проверяемая приёмка» по-прежнему описывает
  30 уровней, 100 звёзд и «Мастер 100/100». Это противоречит установленной
  short15-кампании (15/49) и `docs/PROJECT_STATUS.md`. Перед любыми
  продуктовыми решениями по 30-уровневой таблице CAMPAIGN.md нужно
  актуализировать; до тех пор источником чисел считать только
  `data/campaign.json` + `core/Campaign.kt`. На runtime не влияет: UI берёт
  числа из модели (см. §5).
- `data/levels.json` — исторический v1 (30 уровней, MIT) для тестов/миграции
  старых сохранений; карту не обслуживает (`docs/PROJECT_STATUS.md:25-26`).
  Не использовать как источник порогов short15.

## 2. Реальные экранные маршруты

`app/.../nav/Destination.kt` + `nav/WordsNavHost.kt`; корень — `ui/WordsApp.kt`
(Scaffold + TopBar + ModalNavigationDrawer, BackHandler выхода):

| Маршрут | Экран | Файл |
|---|---|---|
| `levels` (start) | Карта кампании, 15 уровней, 6 этапов | `ui/levels/LevelsScreen.kt` (`LevelsRoute`) |
| `level/{index}` (deep link `sostavslova://level/{index}`) | Игра уровня | `ui/game/GameScreen.kt` (`GameRoute`) |
| `word-meaning` | Поиск значения слова в словаре (не игровая свободная игра) | `ui/meaning/WordMeaningScreen.kt` |
| `rules` | Правила | `ui/screens/Screens.kt` (`RulesScreen`) |
| `about` | Об игре, число слов, лицензии | `ui/screens/Screens.kt` (`AboutScreen`) |

Экранов свободной игры / поиска комбинаций в навигации нет и не возвращается
(UX15-01 не требует их восстановления). Остатки старого поиска: строки
`search_button`, `nothing_found`, `no_combinations` в `strings.xml:24,80-81`
используются только экраном значения слова; `enter_word` не используется
в кампании. Диалогов-мастеров нет: финал — `WordsDialog` поверх `GameScreen`
(`GameScreen.kt:224-240`), объяснения новых заданий — `WordsDialog`
(`GameScreen.kt:241-246`), статистика и задания — диалоги `GameDialog`
(`GameScreen.kt:247-290`), заблокированный уровень — `AlertDialog`
(`LevelsScreen.kt:95-100`), выход — `ExitDialog` (`WordsApp.kt:127-147`).

## 3. Состояние ключевых областей UI

- **Загрузка карты:** `LevelsScreen.kt:103-113` — при `loading && levels.empty`
  рисуется самодельный экран-заглушка: заголовок + `Well` «Выберите доступный
  уровень:» + `CircularProgressIndicator` + «Загружаем уровни…» (`levels_loading`).
  После загрузки — `LazyVerticalGrid` (1 колонка <600dp, иначе Adaptive 220dp,
  `maxWidth 600dp`), заголовок этапа `levels_stage_N`, автопозиционирование на
  рекомендованный уровень один раз (`LevelsScreen.kt:85-94`). Ошибка загрузки —
  `AlertBanner` + кнопка «Повторить» (`LevelsScreen.kt:136-139`). Миграция
  v1→short15 выполняется в `LevelsViewModel.load()` до показа карты
  (`LevelsViewModel.kt:67-69`).
- **Поле ввода:** `WordInputRow` (`ui/components/WordInputRow.kt`) — label
  «Ваш ответ:», кнопка «Проверить», 48dp, IME Done/Go/Search/Send/Next,
  `KeyboardOptions` без автокоррекции, локаль ru, лимит `Alphabet.MAX_WORD_LENGTH`,
  стекинг при ширине <280dp или fontScale >1.3. В `GameScreen` ввод стоит **после**
  плиток исходного слова и после полного блока заданий (см. ниже).
- **Блоки заданий:** `CampaignTasks` (`ui/game/CampaignTasks.kt`) — линейный
  список всех заданий уровня с `level_task_<type>` testTag, прогресс
  `game_task_progress`, разбор загадок T4 внутри блока, отдельный дублирующий
  диалог `GameDialog.Tasks` (`GameScreen.kt:266-282`). Свернутимости нет.
- **Уведомления:** `state.message` и `notice` (события `GameViewModel.events`,
  задержка 3500 мс, `GameScreen.kt:88-98`) рисуются `GameBanner` **над** полем
  ввода (`GameScreen.kt:148-149`), а не под ним. На карте — `AlertBanner`
  для `state.message` (`LevelsScreen.kt:140`).
- **Прогресс:** карта — `levels_campaign_progress` «Звёзд %d из %d · пройдено
  %d из %d» (`levels_progress`) + кнопка «Продолжить»; карточка — `StarsRow` +
  статус + порог + «Можно добрать ещё N» + «Рекомендуемый уровень»
  (`LevelsScreen.kt:199-206`). Игра — заголовок, строка порога, строка звёзд,
  счётчик `game_counts` курсивом, `StarsRow` со входом в диалог заданий
  (`GameScreen.kt:132-147`). Три соседних блока дублируют stars/R/M.
- **Приветствие:** отсутствует полностью. Поиск `Добро пожаловать|welcome|greet`
  по `app/src/main` и `core/src/main` — 0 совпадений; в `ProgressRepository`
  нет флага первого показа. Это объём UX15-06/UX15-07.
- **Статистика:** диалог `GameDialog.Statistics` — `StatsTable` (по длинам:
  отгадано/всего/осталось) + «Взято шагов подсказок…» (`GameScreen.kt:248-265`,
  `ui/components/StatsTable.kt`). Отдельного экрана статистики нет.
  Счётчик `game_counts` — курсив (`GameScreen.kt:145`, объём UX15-09).
- **Тема/шрифты:** Material 3 `lightColorScheme`, сине-белая палитра
  (`Theme.kt`, `WordsColors.kt`); `FontFamily.Default`; `titleLarge 24sp Normal`
  (не SemiBold), `titleMedium 18sp Normal` (не 20sp SemiBold), `bodyLarge 16sp`,
  `bodySmall` не задан явно; `Shapes(small = 4dp)` на всё, именованных
  скруглений 8–10dp нет — объём UX15-09/UX15-10. Курсив в заголовке правил
  и счётчике игры (`Screens.kt:71`, `GameScreen.kt:145`) — объём UX15-09.
- **Правила:** `RulesScreen` описывает свободную игру («составить как можно
  больше слов», без заданий/звёзд/T1–T7) — объём UX15-12.

## 4. Что из исходных UIUX-01…28 уже сделано, чего нет

Исходный перечень UIUX-01…28 в репозитории отсутствует: поиск по
`docs/`, `tools/`, `git log --all --grep=UIUX` даёт только ссылку в
`orchestrator/TASKS.md` (UX15-01 и UX15-14: «сверь все решения исходных
UIUX-01…UIUX-28»). Полный перечень направлений (P1-1…P1-5, P2-1…P2-5, P0)
зафиксирован в UX15-14 как обязанность финального отчёта; здесь — только
наблюдения по коду, без выдуманных номеров UIUX:

| Наблюдение | Состояние |
|---|---|
| Material 3, сине-белая палитра, FontFamily.Default, без тёмной темы/звука/вибрации/рекламы/аналитики, без новых разрешений | Сделано (нет `INTERNET` в манифесте по AGENTS.md §3; зависимости не добавлялись) |
| Строки только в `strings.xml`, `RussianPlural`, testTag `<экран>_<элемент>`, 48dp на интерактивных | Сделано (20 testTag в Levels/Game; исключения: см. §5) |
| Одноразовое приветствие поверх готовой карты | Не сделано (UX15-06/07) |
| Реальная карта вместо экрана «Загружаем уровни…» | Не сделано: заглушка есть (UX15-08) |
| Ввод ближе к исходному слову, без дублей stars/R/M | Не сделано (UX15-02) |
| Сворачиваемые задания, загадка рядом с вводом | Не сделано (UX15-03) |
| Отклик на слово под вводом | Не сделано: баннер над вводом (UX15-04) |
| Компактные карточки карты | Не сделано: порог+остаток+рекомендация на каждой карточке (UX15-05) |
| Типографика по шкале 24/20/18 SemiBold, без курсива в статистике | Не сделано (UX15-09) |
| Именованные скругления 8–10dp | Не сделано: везде 4dp (UX15-10) |
| Контраст звёзд / семантика прогресса | Частично: `StarsRow` имеет contentDescription/stateDescription и min 48dp при клике; контраст filled/outline не измерен (UX15-11, NOT VERIFIED на устройстве) |
| Правила/«Об игре» согласованы с short15 | Не сделано: правила — свободная игра (UX15-12) |
| Экраны свободной игры/поиска комбинаций | Отсутствуют в навигации; остаточные строки `search_button`/`nothing_found`/`no_combinations` — только экран значения слова. Не возвращать (UX15-01) |
| Заставка (системный Splash) | Не трогалась; ручное восстановление не требуется |

## 5. Где UI рисует числовые пороги — источник каждого числа

Правило: все пороги — из модели (`CampaignLevel.requiredStars/maxStars`,
`Campaign.maxStars`, `CampaignSummary`), фиксированных таблиц этапов в UI нет.

| Место | Число | Источник |
|---|---|---|
| `GameScreen.kt:136-138` порог открытия/финала | R и M текущего уровня | `current.level.requiredStars`, `current.level.maxStars`; выбор строки — `number == state.totalLevels` |
| `GameScreen.kt:139` звёзды уровня | stars/M | `completedTaskIds.size`, `level.maxStars` |
| `CampaignTasks.kt:45-53` заголовки заданий | n, minLength, ending, letter, category | поля `CampaignTask.*` из campaign.json |
| `LevelsScreen.kt:121-123` прогресс кампании | stars/maxStars/passed/total | `CampaignRules.summary()` |
| `LevelsScreen.kt:179-205` карточка уровня | stars, M, R, остаток | `LevelUiState` из `CampaignRules.stars/maxStars/requiredStars` (`LevelsViewModel.kt:72-76`) |
| `LevelsScreen.kt:95-99` диалог закрытого уровня | предыдущий уровень и его R | `previous.index to previous.requiredStars` (`LevelsViewModel.kt:105`) — корректно: открывает прохождение именно предыдущего уровня с его R |
| `LevelsScreen.kt:130-135` баннер финала/мастера; `GameScreen.kt:227` финал | 4/5 и 49/49 — из модели | `finaleReached`, `summary.isMaster` (`stars == maxStars`) |
| `GameViewModel.kt:176` `totalLevels` | N | `loadedCampaign.levels.size` (15) |
| `StarsRow.kt:31-38` | stars/M | аргументы вызывающего; semantics `level_stars_progress` |

Исключения-остатки (не пороги short15, но зафиксированы):
- `strings.xml:28` `stars_progress` «Звёзды: %1$d из 3» — мёртвая строка v1,
  в коде кампании не используется (активна `level_stars_progress` с `%2$d`).
- `LevelsViewModel.kt:26` `LevelUiState.maxStars = 3` по умолчанию — только
  legacy-ветка v1 (`campaignStars == null`); в ветке short15 всегда
  перезаписывается из `level.maxStars`.
- Имена этапов жёстко маппятся 1–5/else→Мастер в двух местах
  (`LevelsScreen.kt:168-175`, `CampaignTasks.kt:70-77` `gameStageName`) —
  при 6 этапах short15 корректно, но дублирование; не является порогом.
- `GameScreen.kt:93` задержка уведомления 3500 мс — не порог кампании.

## 6. SDK, эмулятор, доступные тесты

- SDK: `ANDROID_HOME=$HOME/.local/share/words-combination/android-sdk`
  (build-tools, cmdline-tools, platforms, platform-tools). JDK 21.0.13.
- Эмулятор: **нет** — каталога `emulator/` и `system-images/` в SDK нет.
- Устройства: `adb devices` — пусто. Инструментальные тесты **NOT RUN**
  (нечем запускать); компиляция androidTest по `docs/PROJECT_STATUS.md`
  ранее проходила.
- Набор тестов в репозитории: `:core` — 129 тестов, включая
  `Short15ProductionCampaignTest` (15/6/`[3,2,3,2,3,2]`/49, финал 4/5,
  мастер 49/49 на установленной кампании), `CampaignRulesTest`,
  `TaskRulesTest`; `tools/` — 406 тестов; `androidTest` — 28 файлов,
  включая `CampaignMapUiTest`, `CampaignGameUiTest`, `GameLayoutTest`,
  `LevelsLayoutTest`, `CampaignJourneyUiTest`, `QaCampaign`/`MiniCampaign`
  фикстуры. Последние результаты — `docs/PROJECT_STATUS.md:46-58`
  (не перезапускались в этой задаче, кроме `git diff --check`).

## 7. Требования к следующим задачам (не выполнять здесь)

UX15-02…UX15-13 обязаны брать R/M/maxStars только из `CampaignLevel`/
`CampaignSummary` (таблица §5), не вводить фиксированные 30/100/«из 3»,
сохранять testTag и 48dp, не возвращать свободную игру/поиск комбинаций и не
трогать системный Splash. Релизный BLOCKER: ручной аудит прав определений
не пройден (`manifest.definitions.audit.passed=false`,
`rights_review.passed=false`, `wiktionary-provisional-v1`) —
`verifyDefinitionsAudit` отказывает; это не блокирует UX15-02…13, но блокирует
публикацию. GEN-05 — `exhausted`, баланс вероятностей не доказан.
