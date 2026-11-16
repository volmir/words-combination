# PRE-04: независимый разбор 93 FAIL

## Срез и границы анализа

Разобран сохранённый прогон Android 13 / MIUI: **379 запусков, 286 PASS,
93 FAIL, 0 SKIPPED, 0 отдельных errors в JUnit XML**. Нового прогона в рамках
анализа нет. Исходный срез — `8434561` с существовавшей правкой тестового
runner. Код, тесты, данные, права, SHA и служебные файлы не изменялись;
результат этой задачи — только настоящий документ.

Источники: [диагностика исходного зависания](ANDROID_UI_HANG_DIAGNOSIS.md),
`.local/ui-hang-20261116/full-failures.json`, итоговый JUnit XML из
`app/build/outputs/androidTest-results/connected/debug/`,
[локальный HTML-отчёт](../app/build/reports/androidTests/connected/debug/index.html),
сохранённые logcat отдельных тестов, исходники. Все пары «полный класс,
полное имя метода с параметрами» из JSON сверены с XML: совпадают **93/93**.
F01–F93 ниже — порядок записей JSON; параметризованный запуск считается одним FAIL.

Отчёт обезличен: без серийного номера, идентификаторов процессов, имён
пользователей, абсолютных путей домашнего каталога и данных смартфона.
Тексты черновиков и примеров в реестре — синтетические литералы тестов.

Классификация: **A** — подтверждённый дефект приложения; **B** — дефект теста;
**C** — подтверждённая причина в окружении; **D** — устаревшее ожидание;
**E** — данных недостаточно для причинной атрибуции. Статически подтверждённый
дефект и доказанность того, что именно он породил конкретный тайм-аут,
различаются. Уверенность ниже указывается отдельно.

**Результат: 15 групп симптомов, не 93 независимых дефекта и не 15 уже
доказанных первопричин.** A — 2, B — 7, C — 0, D — 23, E — 61 FAIL.
Ноль в C не доказывает отсутствие проблем окружения: изоляция хранилища
подозрительна, но конкретный `require`, давший исключение, в логах не виден.
Исправленная блокировка MIUI `startActivitySync()` не относится к этим 93 FAIL.

## Сводная таблица

P0 — пользовательское состояние / барьер выхода; P1 — блокер доказательной
приёмки; P2 — локальная актуализация или уточнение измерений. Количество —
наблюдавшиеся первые падения, а не гарантированное число PASS после правки.

| ID | Причина или проверяемая гипотеза | FAIL | Классификация | Доказательства | Приоритет | Рекомендуемое атомарное действие |
| --- | --- | ---: | --- | --- | --- | --- |
| G01 | Общий `hasVisualOverflow` для букв/заголовков; причина геометрии не установлена | 48 | E | `assertTextFits`: 30 CampaignGame + 18 FoundWords; первый текст — 33 буквы и 15 заголовков | P1 | Сначала снять размеры/layout-флаги одного представителя каждой подгруппы; исправлять только доказанный компонент или измерение |
| G02 | Ожидание старой Wiktionary-атрибуции после перехода на собственные подсказки | 20 | D | Два метода × 10 конфигураций; ресурс содержит CC0, fixture всё ещё ставит `wiktionary=true` | P1 | Перевести эти две fixture и положительные/отрицательные проверки на действующий owned-контракт; происхождение и маскирование сохранить в проверках |
| G03 | Не дождались ответа/feedback; запись, публикация UI и отображение пока не разделены | 8 | E | F01 и семь ожиданий «Правильно!»; нет доказательства потери DataStore | P0 | Добавить диагностические наблюдения commit → UiState → semantics в одном представителе; проверить изоляцию и жизненный цикл |
| G04 | Проверяется описание дочернего ряда звёзд на узле родительской карточки | 4 | B | Карточка имеет StateDescription; `StarsRow` — отдельный merging-node с ContentDescription | P2 | Проверять обе семантики на их фактических узлах, сохранив проверку доступности и счёта |
| G05 | Черновик записывается в VM-handle, а навигация читает другой entry-handle | 2 | A | F85/F92: пустой input; явный разрыв записи/чтения SOURCE и INPUT | P0 | После отдельного разрешения исправить передачу одного снимка черновика VM → navigation; два существующих сценария оставить строгими |
| G06 | Ошибка записи времени блокирует сохранение/выход; вероятно, общая timing-fixture | 2 | E | В обоих logcat `append ... IllegalArgumentException`; точного stack/message нет; `play_time` общий | P0 | В тестовой инфраструктуре изолировать оба хранилища и снять исходное исключение из PlayTimeStore; не обходить барьер выхода |
| G07 | Ожидается цена лестницы с уже отфильтрованным DEFINITION | 1 | D | Четыре шага стали THEME/LENGTH/FIRST_LETTER/ANSWER: 2 платных вместо 1 | P1 | Актуализировать fixture и проверять последовательность стадий и её стоимость по принятому контракту |
| G08 | 60-секундный budget истёк в полном приёмочном тесте времени; фаза неизвестна | 1 | E | Только UncompletedCoroutinesError; полный перебор свидетелей в setup | P1 | Измерить фазы подготовки/записи/барьеров; затем устранить доказанную дорогую подготовку либо блокировку |
| G09 | «Системный Back» подменён вызовом диспетчера Activity при открытом Dialog | 1 | B | F06; helper не отправляет событие в окно диалога | P2 | Послать настоящий Back в активное окно и оставить assertions закрытия/незавершённой Activity |
| G10 | Набор маршрутов ожидается без согласованной страницы времени | 1 | D | Ожидаемый set не содержит `time`, фактический содержит | P2 | Актуализировать точный набор и проверку перехода на Time; не убирать сравнение |
| G11 | Устаревший регистрозависимый фрагмент строки «Об игре» | 1 | D | В ресурсе «Словарные толкования и литературные примеры исключены» | P2 | Проверять действующий полный смысл/ресурс и разделение CC BY-SA/CC0 |
| G12 | В момент запроса semantics нет Compose-иерархии | 1 | E | F81: нет сведений о lifecycle/window/root в точке отказа | P0 | Сохранить состояние Activity и окон непосредственно перед запросом; не считать это автоматически повтором MIUI-блока |
| G13 | После v1-сценария тест оставляет маршрут `2` для новой закрытой кампании | 1 | B | `seed("портал")` → route=2 → clearAll → model без явного handle → ready | P1 | Явно задать открытый маршрут синтетической кампании при смене режима; assertions фильтрации сохранить |
| G14 | Финал подготавливается до миграции campaign identity | 1 | B | Seed не вызывает migrate; recordLevel сохраняет null identity; первое открытие сбрасывает finaleReached | P1 | Сначала мигрировать синтетическую кампанию, затем набрать финал реальными ответами и проверить подтверждение |
| G15 | Переполнение поля страницы времени; конкретный текст/ось не записаны | 1 | E | Только общее сообщение на `TimeScreenTest.kt:83` | P1 | Снять имя поля и параметры TextLayoutResult; связь с G01 проверять, не объявлять установленной |

## G01 и G02: почему десятки FAIL могут иметь общую причину

### CampaignGameUiTest — ровно 50 из 80 запусков

Параметры: 10 комбинаций ширины/масштаба, восемь методов. Фактически упали
пять методов в каждой комбинации:

| Метод | FAIL | Первая точка / группа |
| --- | ---: | --- |
| `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes` | 10 | `assertTextFits`, G01 |
| `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange` | 10 | `assertTextFits`, G01 |
| `finaleSurvivesScreenRestorationAndBothActionsFit` | 10 | `assertTextFits`, G01 |
| `riddlesRemainUnderTaskAndReflectSolvedUpdates` | 10 | Старая атрибуция, строка 323, G02 |
| `accumulatedHintStepsCostAndZeroBudgetAreVisible` | 10 | Старая атрибуция, строка 156, G02 |

Остальные 30 запусков этого класса прошли. Последние два метода **не упали
на подсчёте стоимости или раскрытии загадки**: они остановились раньше,
на отсутствии старой строки. Проверки после первого assertion не считаются
ни проваленными, ни доказанно пройденными. Исправление G02 может открыть
следующие ошибки; обещать немедленные 20 PASS нельзя.

### FoundWordsSectionTest — ровно 18 из 36 запусков

Один метод `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions`
упал во всех 18 комбинациях 3 ширины × 3 масштаба × legacy/v2. Все остановки
в `assertTextFits:168`, вызываемом после раскрытия группы (`:95`), до финальной
проверки длинного слова. Метод `largestInstalledLevelKeepsStatisticsReachableWithEveryGroupExpanded`
прошёл все 18 раз. **Не доказано**, что ломаются 146 слов, восстановление
прокрутки, статистика или производительность больших списков.

### Подгруппы переполнения и уровень доказательств

- 18 CampaignGame + 15 FoundWords = **33** первых сообщения с одной буквой
  (`К`/`А`): общий кандидат — [LetterTiles.kt](../app/src/main/kotlin/com/example/sostavslova/ui/components/LetterTiles.kt#L24).
  Там Text 18sp/lineHeight 18sp внутри вычисляемой плитки. Глобальные helpers
  обходят все Text экрана, а не только проверяемые найденные слова или диалог.
- 12 CampaignGame + 3 FoundWords = **15** первых сообщений с заголовком
  уровня. Кандидаты — [GameScreen.kt:149](../app/src/main/kotlin/com/example/sostavslova/ui/game/GameScreen.kt#L149),
  общая типографика и `requiredWidth` тестового контейнера. Синтетическое
  «уровень 30 из 30» здесь допустимо: тест явно создаёт такую кампанию;
  это **не самостоятельное доказательство устаревшего ожидания short15**.
- G15 может иметь общую основу измерения с G01, но это ещё **один**, а не
  автоматически 49-й доказанный случай одной причины.

Доказан лишь возврат `hasVisualOverflow=true`. В сохранённых assertions нет
`didOverflowHeight/Width`, `size`, `multiParagraph.width/height`, constraints,
числа строк, ellipsis, bounds и снимка текста. Поэтому нельзя ни объявить
48 дефектов вёрстки A, ни объявить проверку ложной B, ни сослаться на
«ошибку округления MIUI» C. Согласно [исходнику TextLayoutResult](https://android.googlesource.com/platform/frameworks/support/+/654bd97eb199de44e1b1b84d8ec874014151a745/compose/ui/ui-text/src/commonMain/kotlin/androidx/compose/ui/text/TextLayoutResult.kt),
флаг объединяет разные признаки переполнения; одного Boolean недостаточно
для выбора конкретного исправления. Это описание API, не воспроизведение
ошибки установленной версии библиотеки.

Уверенность: **высокая** в группировке, **средняя** в общей причине у 33 букв,
**низкая/средняя** в единой причине букв, заголовков и G15. Минимальная будущая
проверка: один буквенный случай и один случай заголовка с полными метриками и
снимком, затем соседние параметры; отдельно поле G15. Нельзя просто удалять
`assertTextFits`, исключать буквы/заголовки из охвата или вводить допуск без
доказанной погрешности. Если обнаружится один дефект LetterTiles, одна правка
потенциально затронет 33 FAIL; единый дефект общей типографики может затронуть
больше, но пока не подтверждён.

### G02: подтверждённое устаревшее ожидание

[strings.xml:59](../app/src/main/res/values/strings.xml#L59) теперь содержит
собственную подсказку / CC0. [CampaignHint](../app/src/main/kotlin/com/example/sostavslova/ui/game/GameScreen.kt#L333)
и [CampaignTasks](../app/src/main/kotlin/com/example/sostavslova/ui/game/CampaignTasks.kt#L63)
читают этот ресурс. В двух UI-fixture выставлен прежний `wiktionary=true`
и жёстко ожидается отсутствующая строка CC BY-SA. Текущий маршрут ViewModel
фильтрует определения через `isOwnedHint`, поэтому fixture обходит обычный
путь производства такого состояния. Контракт перехода описан в
[LCH03_REPORT.md](LCH03_REPORT.md). Возврат старой строки в приложение ради
этих тестов противоречил бы реальному набору подсказок и не предлагается.

Уверенность **высокая**. Минимальная проверка после отдельной правки двух
fixture: по одному методу при обычном и крупном шрифте, затем все 20 вариантов;
проверить CC0, отсутствие ложной Wiktionary-атрибуции и маскирование ответа.

## Сохранение, выход и PlayTimeTracker

### G03: тайм-аут feedback не равен доказанной потере записи

F07–F10 и F82–F84 останавливаются на ожидании «Правильно!» до основных
проверок повторного ввода, подсказок или сохранённого счёта. F01 ждёт
подтверждённое число найденных ответов в helper `answer`; сам stack содержит
только корутинный таймер, точная suspend-точка там отсутствует. Отдельно F01
проходил; в общем наборе — нет. У F81 другой симптом, отсутствие root, поэтому
ему выделена G12, хотя связь с G03 возможна.

Кандидаты G03: неготовая игровая сессия/модальный диалог, lifecycle,
транзитное сообщение, состояние общего Application-контейнера и его барьеров.
`runTest` + реальный Main — гипотеза, **не установленная причина**. Нельзя
переносить доказательства старого MIUI-блока, происходившего до тела теста,
на эти ограниченные тайм-ауты уже исполняющегося тела.

Уверенность в конкретной причине **низкая**. Минимальная проверка: для одного
теста одновременно снять `CampaignProgress`, `GameUiState` (input/busy/message/
explanations/returnLevels) и semantics, с отметками submit/commit/render.
Проверить отдельно и после минимального префикса предыдущих классов. Только
затем решать, нужен ли фикс тестовой синхронизации или отдельный production-фикс.

### G06: два барьера выхода и один реальный сигнал ошибки

В logcat F02/F03 повторяется `PlayTimeTracker: level=1 operation=append ...
error=IllegalArgumentException`. Счётчики времени в отчёт не перенесены.
[PlayTimeTracker:100–112](../app/src/main/kotlin/com/example/sostavslova/time/PlayTimeTracker.kt#L100)
ловит исходное исключение и сохраняет только его простой тип; при Barrier
тот же сбой передаётся ожидающему. [AppContainer.awaitSaved](../app/src/main/kotlin/com/example/sostavslova/di/AppContainer.kt#L89)
ждёт прогресс, затем время; [ExitViewModel](../app/src/main/kotlin/com/example/sostavslova/ui/ExitViewModel.kt#L62)
при ошибке не подтверждает успешное сохранение и не завершает Activity.
Так объясняется связь двух симптомов: «нет сообщения об успешном сохранении»
и «Activity не завершилась». Это не два доказанных дефекта выхода.

Факт плохой изоляции подтверждён исходниками: [QaCampaign](../app/src/androidTest/kotlin/com/example/sostavslova/qa/QaCampaign.kt#L42)
инъецирует только progress-файл; [AppContainer:73–77](../app/src/main/kotlin/com/example/sostavslova/di/AppContainer.kt#L73)
всегда использует общий `play_time`. `closeQa` отменяет scope, но не делает
этот файл отдельным для следующего теста. `ProgressRepository.clearAll`
намеренно не очищает время; некоторые MainActivity-тесты используют общий
Application-контейнер. Это подтверждённый риск C, но **конкретная причинная
атрибуция F02/F03 пока E**: в записи нет message и места `throw`.

В [PlayTimeRepository:120–128](../app/src/main/kotlin/com/example/sostavslova/data/PlayTimeRepository.kt#L120)
есть несколько разных `require`: допустимые sequence/duration/stars,
непрерывность sequence и невозможность уменьшения звёзд. Старые lastStars
при новом progress — сильная гипотеза; конфликт sequence — другая. Нельзя
выбирать между ними по одному имени исключения. `Multiple DataStores` обычно
дал бы IllegalStateException, поэтому объявлять его данным IllegalArgumentException
некорректно. Нельзя объявлять причиной отсутствующий SHA синтетической кампании:
[LevelsRepository:31–35](../app/src/main/kotlin/com/example/sostavslova/data/LevelsRepository.kt#L31)
вычисляет SHA и для инъецированного JSON.

Минимальная проверка: в тестовом декораторе PlayTimeStore сохранить Throwable
и анонимные sequence/starsBefore/starsAfter; выполнить эти два сценария с
изолированными progress **и** time, затем вместе. Не удалять пользовательский
`play_time`, не пропускать `awaitSaved`, не разрешать завершение Activity при
ошибке сохранения. Уверенность в связи с ошибкой времени **высокая**,
в причине нарушения контракта хранилища **средняя**.

### G05: подтверждённый разрыв передачи черновика

[GameViewModel:285](../app/src/main/kotlin/com/example/sostavslova/ui/game/GameViewModel.kt#L285)
записывает INPUT в собственный SavedStateHandle; SOURCE туда же (`:202`).
Он создаётся через `createSavedStateHandle` в [GameRoute:83](../app/src/main/kotlin/com/example/sostavslova/ui/game/GameScreen.kt#L83).
Однако [returnToLevels:24–29](../app/src/main/kotlin/com/example/sostavslova/nav/WordsNavHost.kt#L24)
читает SOURCE/INPUT у `NavBackStackEntry.savedStateHandle`. Моста записи туда
нет; при null SOURCE экспорт вообще пропускается. Восстановление (`:59–64`)
тоже заполняет entry-handle, а не handle игровой ViewModel.

Это разные объекты: Navigation выдаёт entry-handle через отдельную служебную
SavedStateViewModel, что видно в [исходнике AndroidX](https://android.googlesource.com/platform/frameworks/support/+/c96e367ef49d7fcec5a031286b22ac5630d85014/navigation/navigation-common/src/main/java/androidx/navigation/NavBackStackEntry.kt).
F85 и F92 затем видят пустой input вместо двух разных синтетических черновиков.
[TIME04_REPORT.md](TIME04_REPORT.md) прямо обещает сохранение черновика при
навигации через меню. Классификация A, уверенность **высокая** по статическому
потоку и согласованному наблюдению. Минимальная будущая проверка: сравнить
два handle до pop и после повторного открытия; после отдельного разрешения
production-правки прогнать именно F85/F92 и вариант без пересоздания Activity.
Не переносить черновик в глобальную переменную и не ослаблять сравнение.

## Остальные установленные дефекты и неопределённости

### G04: семантика ряда звёзд, 4 одинаковых отказа

[LevelsLayoutTest:63](../app/src/androidTest/kotlin/com/example/sostavslova/ui/levels/LevelsLayoutTest.kt#L63)
ищет ContentDescription у карточки. Дамп уже показывает на ней правильный
StateDescription «Получено 0 из 1 звезды». [StarsRow:68](../app/src/main/kotlin/com/example/sostavslova/ui/components/StarsRow.kt#L68)
задаёт отдельному merging-node описание «Звёзды»; вложенный merging-node не
обязан слиться с merging-родителем — [правило Compose](https://developer.android.com/develop/ui/compose/accessibility/merging-clearing).
Это B, высокая уверенность в неверном узле проверки; отсутствие самого
дочернего accessibility-node не доказано. Минимально проверить unmerged дерево
и сохранить assertions описания ряда **и** динамического счёта карточки.

### G07: 1 vs 2 — изменился состав fixture, не цена механики

`QaCampaign.Meanings.SAFE` возвращает определение с `origin="wiktionary"`.
[loadHintWords:439](../app/src/main/kotlin/com/example/sostavslova/ui/game/GameViewModel.kt#L439)
исключает такие определения. Для T4-цели «кот» остаются THEME, LENGTH,
FIRST_LETTER, ANSWER. По [HintRules:59–69](../core/src/main/kotlin/com/example/sostavslova/core/HintRules.kt#L59)
THEME/LENGTH бесплатны, FIRST_LETTER/ANSWER стоят по единице: **0+0+1+1=2**.
Старое ожидание 1 относилось к лестнице с дополнительным бесплатным DEFINITION
(первые четыре шага ещё не включали ANSWER). Та же проверка позднее ожидает
бюджет 4 вместо 3 и «4 из 5 частей»; это латентные ожидания того же случая,
не дополнительные FAIL в текущих 93.

D, высокая уверенность. В следующей задаче либо явно использовать допустимое
собственное определение, если проверяется пятичастная лестница, либо явно
проверить fallback и его реальные стадии/цену. Механику цен не менять.

### G13 и G14: два разных дефекта подготовки GameViewModelTest

F86: [seed:87](../app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt#L87)
после `seed("портал")` устанавливает поле route в `2`. После очистки progress
на [строке 272](../app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt#L272)
новый model получает default handle из того же route. В MiniCampaign уровень 2
закрыт на чистом progress; `ready()` ждёт `canPlay` вместо состояния отказа.
Это статически подтверждённый B. Стек не содержит project-frame, поэтому
без маркера нельзя утверждать, какой из нескольких await был первым
в данном запуске. Уверенность в дефекте **высокая**, в точной точке тайм-аута
**средняя**. Минимально открыть явный маршрут 1 новой кампании и наблюдать
готовность; проверить сохранённые assertions запрета сторонних определений.

F87: [строки 615–624](../app/src/androidTest/kotlin/com/example/sostavslova/ui/game/GameViewModelTest.kt#L615)
записывают ответы до первого `migrateCampaign`. [CampaignRules.recordLevel](../core/src/main/kotlin/com/example/sostavslova/core/CampaignRules.kt#L103)
меняет найденные слова/финал, сохраняя исходный null campaignId. При первом
открытии [миграция:164–170](../app/src/main/kotlin/com/example/sostavslova/data/ProgressRepository.kt#L164)
принимает неидентифицированный progress и намеренно сбрасывает finaleReached/
finaleShown. Поэтому ожидание finaleSummary уже подготовлено неверно. B,
высокая уверенность в дефекте setup; project-frame в стеке отсутствует.
Минимальная проверка: зафиксировать identity и finaleReached до/после открытия;
в будущей правке мигрировать fixture **до** ответов, не устанавливать флаги вручную.
Группы не объединяются в один доказанный root cause только из-за общего класса.

### G09, G10, G11

G09/F06: helper [ExitNavigationTest:20](../app/src/androidTest/kotlin/com/example/sostavslova/ui/ExitNavigationTest.kt#L20)
называет back прямой вызов Activity.onBackPressedDispatcher. Но AlertDialog
имеет своё окно и callback dismiss; [контракт DialogProperties](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/DialogProperties.html)
описывает системный Back активного диалога. WordsApp отключает свой map-handler
при открытом exit.dialog. B, высокая уверенность в неверной симуляции,
средняя в исключительности этой причины; проверка — настоящий Back через
Espresso/UiDevice, без увеличения времени ожидания и без удаления assertions.

G10/F11: exact set старых четырёх маршрутов расходится с Destination.Time.
Это не расхождение порядка элементов set. Согласованная страница времени
описана в TIME04_REPORT; D, высокая уверенность. Минимально проверить точный
актуальный set и навигацию на Time вместо удаления теста.

G11/F12: в [strings.xml:39](../app/src/main/res/values/strings.xml#L39) сохранён
смысл исключения примеров, но фраза стала «Словарные толкования и литературные
примеры исключены». Старый substring начинается с заглавной «Л»; сопоставление
регистрозависимо. D, высокая уверенность. Проверять актуальную строку и
смысл разделения лицензий; менять юридический текст ради теста не требуется.

### G08, G12, G15 — что пока не установлено

G08/F05: ровно общий лимит runTest 1 минута; first relevant project-frame
отсутствует. [PlayTimeAcceptanceTest:79–91](../app/src/androidTest/kotlin/com/example/sostavslova/time/PlayTimeAcceptanceTest.kt#L79)
строит свидетелей полным `sorted().filter { TaskRules.submit(...) }.take(...)`
для задач кампании, затем выполняет много реальных транзакций. Это кандидат
дорогой подготовки, но в стеке нет номера фазы, доказательств deadlock или
точного вклада CPU/диска. Сохранённый отдельный дамп jdb не содержит frame
этого метода и не используется как доказательство. E, средняя/низкая
уверенность в performance-гипотезе. Минимально замерить фазы и готовность
barrier/actor; только после этого оптимизировать построение свидетелей или
профилировать запись. Автоматически увеличивать 1 минуту нельзя.

G12/F81: `No compose hierarchies` возник при ожидании feedback, не на start
Test Rule. Нет достаточного lifecycle/window dump в этот момент. E, низкая
уверенность в причине; возможны завершение/пересоздание Activity или выбор
неактивного root. Нужен точечный снимок состояния в отказе и сравнение
изолированного запуска с префиксом набора; не объявлять это автоматически C.

G15/F93: helper сообщает только «Text must wrap without clipping» и проверяет
поля разных уровней/ширин. Неизвестны конкретное поле, ось переполнения и размер
layout. E, низкая уверенность в едином root с G01. Снять эти значения,
проверить длинную длительность и название уровня отдельно, не менять текст
или ограничение fontScale ради зелёного результата.

## Предлагаемая очередь — по числу затрагиваемых FAIL

Это предложения, **не изменения TASKS.md**. «До N» — верхняя граница влияния
гипотезы, не обещание PASS; первые падения маскируют более поздние проверки.
Доказанная правка тестов G02 готова к отдельной задаче; production-изменения
допустимы лишь после нового решения владельца.

| Порядок / предложение | Возможное влияние одной правки | Критерий атомарности и минимальная проверка |
| --- | ---: | --- |
| T01: определить источник overflow LetterTiles | До 33 | Метрики одного компонента; при доказанном A — запрос разрешения на конкретный фикс, при B/C — исправить только доказанное измерение/окружение. Затем три метода CampaignGame и FoundWords при двух масштабах |
| T02: актуализировать owned-fixture и атрибуцию двух методов | 20 первых FAIL | Только два несовместимых сценария, CC0/отсутствие ложного источника/маскирование остаются проверяемыми; затем все 20 вариантов |
| T03: определить источник overflow заголовков | До 15 | Не смешивать с буквами без общих метрик; если T01 докажет единый root, объединить влияние, не суммировать дважды |
| T04: отделить submit/commit/UiState/semantics | До 8 | Один representative + минимальный порядок предшествующих тестов; менять только доказанную причину, не общий тайм-аут |
| T05: проверить семантику звёзд на правильных узлах | 4 | Сохранить label и динамический StateDescription; прогнать четыре конфигурации |
| T06: исправить мост черновика VM/navigation | 2 | P0; только после отдельного разрешения production-правки. F85/F92, два уровня, Back и drawer, пересоздание |
| T07: изолировать timing-fixture и раскрыть исходный Throwable | До 2 | Только G06, без очистки пользовательского time-файла и без отключения exit barrier; обычный и последовательный запуск |
| T08: устранить наследование route=2 в imported-text тесте | 1 | Явный открытый маршрут новой fixture; все проверки непоказа сторонних текстов сохранить |
| T09: мигрировать identity до подготовки финала | 1 | Получить финал настоящими ответами после migrate; проверить отсутствие повторного диалога |
| T10: актуализировать состав и цену hint-fixture | 1 | Явные стадии и счётчики; не менять HintRules, учесть последующие assertions бюджета и количества частей |
| T11: использовать системный Back активного диалога | 1 | Сохранить assertDoesNotExist и isFinishing=false; отдельный тест и весь ExitNavigationTest |
| T12: актуализировать exact set маршрутов | 1 | Проверить Time и фактическую навигацию, не удалить сравнение |
| T13: актуализировать ожидание текста «Об игре» | 1 | Проверить смысл исключения толкований/примеров и обе лицензии; правка только теста |
| T14: локализовать потерю Compose-root | До 1 | Lifecycle/window dump в момент запроса, без повторного изменения MIUI policy наугад |
| T15: измерить фазы полной time-acceptance | До 1 | Отдельно witness generation/записи/barrier; обоснованный фикс дорогой подготовки либо блокировки |
| T16: локализовать overflow конкретного поля Time | До 1 | Текст, геометрия, ось; переиспользовать результаты T01/T03 только при подтверждённой общей причине |

## Реестр всех 93 отказов

Ниже класс указан коротко; полный package для каждого класса приведён в
таблице соответствий. Тип исключения и сообщение взяты из сохранённого FAIL.
Для больших semantics-dump приведены существенное сообщение и наблюдаемое
значение, полный текст остаётся в JSON/HTML. «Нет project-frame» означает
именно отсутствие его в сохранённом стеке, а не подстановку предполагаемой
строки исходника. Проверяемое поведение — цель теста и фактическая точка
остановки, не утверждение о дефекте этой функции приложения.

| Короткое имя | Полный класс |
| --- | --- |
| `CampaignJourneyUiTest` | `com.example.sostavslova.qa.CampaignJourneyUiTest` |
| `PlayTimeAcceptanceTest` | `com.example.sostavslova.time.PlayTimeAcceptanceTest` |
| `ExitNavigationTest` | `com.example.sostavslova.ui.ExitNavigationTest` |
| `MainScenariosUiTest` | `com.example.sostavslova.ui.MainScenariosUiTest` |
| `ComponentInteractionTest` | `com.example.sostavslova.ui.components.ComponentInteractionTest` |
| `CampaignGameUiTest` | `com.example.sostavslova.ui.game.CampaignGameUiTest` |
| `FoundWordsSectionTest` | `com.example.sostavslova.ui.game.FoundWordsSectionTest` |
| `GameNavigationTest` | `com.example.sostavslova.ui.game.GameNavigationTest` |
| `GameViewModelTest` | `com.example.sostavslova.ui.game.GameViewModelTest` |
| `LevelsLayoutTest` | `com.example.sostavslova.ui.levels.LevelsLayoutTest` |
| `TimeNavigationTest` | `com.example.sostavslova.ui.time.TimeNavigationTest` |
| `TimeScreenTest` | `com.example.sostavslova.ui.time.TimeScreenTest` |

| FAIL | Класс | Метод и параметры | Исключение | Сообщение / существенное наблюдение | Первый релевантный frame из стека | Проверяемое поведение / точка отказа | Связь и классификация |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F01 | `CampaignJourneyUiTest` | `closedDeepLinkUsesInjectedCampaignWithoutChangingProgress` | `kotlinx.coroutines.TimeoutCancellationException` | Timed out waiting for 10000 ms | `Нет project-frame; первый доступный: kotlinx.coroutines.TimeoutKt.TimeoutCancellationException(Timeout.kt:189)` | Закрытый deep-link не меняет progress; затем ответ открытого уровня должен дать суммарно 1 найденное слово. Тайм-аут ожидания подтверждённого ответа. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F02 | `CampaignJourneyUiTest` | `exitFromMenuWaitsForSaveAndClosesHost` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.qa.CampaignJourneyUiTest$exitFromMenuWaitsForSaveAndClosesHost$1.invokeSuspend(CampaignJourneyUiTest.kt:266)` | Меню → выход: дождаться сообщения об успешном сохранении, подтвердить и завершить host. Остановка до подтверждения сохранения. | G06 / E; парный exit-сценарий + PlayTimeStore, возможна C |
| F03 | `CampaignJourneyUiTest` | `doubleSystemBackFromMapExitsWithSavedAnswers` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.qa.CampaignJourneyUiTest$doubleSystemBackFromMapExitsWithSavedAnswers$1.invokeSuspend(CampaignJourneyUiTest.kt:280)` | Два Back с карты: завершить host после барьера сохранения. Остановка на ожидании activity.isFinishing. | G06 / E; парный exit-сценарий + PlayTimeStore, возможна C |
| F04 | `CampaignJourneyUiTest` | `newInstallToFinaleRestartAndAllStars` | `java.lang.AssertionError` | expected:<1> but was:<2> | `com.example.sostavslova.qa.CampaignJourneyUiTest$newInstallToFinaleRestartAndAllStars$1.invokeSuspend(CampaignJourneyUiTest.kt:175)` | Четыре шага T4-подсказки: ожидается paidUnits=1, получено 2. Дальнейшие restart/финал/все звёзды ещё не проверены этим запуском. | G07 / D |
| F05 | `PlayTimeAcceptanceTest` | `installedCampaignRThenRevisitsTo49StarsHasExactLevelStageAndCampaignTotals` | `kotlinx.coroutines.test.UncompletedCoroutinesError` | After waiting for 1m, the test body did not run to completion | `Нет project-frame; первый доступный: kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$2$1$2.invokeSuspend$lambda$0(TestBuilders.kt:353)` | Пройти установленную кампанию до R, затем M, проверить времена/перезапуск/49 звёзд. Общий 1-минутный лимит; фаза неизвестна. | G08 / E |
| F06 | `ExitNavigationTest` | `systemBackDismissesDialogAndActivityIsFoundThroughWrappers` | `java.lang.AssertionError` | Failed: assertDoesNotExist.; Reason: Did not expect any node but found '1' node that satisfies: (TestTag = 'exit_dialog_confirm') | `com.example.sostavslova.ui.ExitNavigationTest.systemBackDismissesDialogAndActivityIsFoundThroughWrappers(ExitNavigationTest.kt:63)` | Системный Back должен закрыть exit-dialog, сохранив Activity. Тест вызывает диспетчер Activity; confirm-node остался. | G09 / B |
| F07 | `MainScenariosUiTest` | `levelAnswerIsSavedAndScoreUpdated` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.MainScenariosUiTest.awaitText(MainScenariosUiTest.kt:46)` | Первый ответ сохраняется и обновляет счёт; остановка на «Правильно!» до сравнения сохранений. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F08 | `MainScenariosUiTest` | `scenario2_incorrectWordNotFoundAndRepeatedWordAlreadyFound` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.MainScenariosUiTest.awaitText(MainScenariosUiTest.kt:46)` | Неверное и повторное слова дают разные сообщения; остановка на первом верном ответе, до целевых веток. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F09 | `MainScenariosUiTest` | `scenario3_hintDoesNotRevealUnfoundWords` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.MainScenariosUiTest.awaitText(MainScenariosUiTest.kt:46)` | Подсказка не раскрывает ненайденные слова; остановка на первом «Правильно!», до проверки подсказки. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F10 | `MainScenariosUiTest` | `backToMapAndReopenPreservesAnswers` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.MainScenariosUiTest.awaitText(MainScenariosUiTest.kt:46)` | Back/повторное открытие сохраняет ответ; остановка на первом «Правильно!», до возврата. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F11 | `ComponentInteractionTest` | `drawerKeepsSupportedDestinationsAndInvokesNavigation` | `java.lang.AssertionError` | expected:<[levels, level/{index}, rules, about]> but was:<[level/{index}, levels, time, rules, about]> | `com.example.sostavslova.ui.components.ComponentInteractionTest.drawerKeepsSupportedDestinationsAndInvokesNavigation$lambda$6(ComponentInteractionTest.kt:46)` | Меню и exact set поддерживаемых маршрутов; ожидаемый набор не содержит Time. | G10 / D |
| F12 | `ComponentInteractionTest` | `aboutShowsAttributionLicenseAndChangesAtLargeFont` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Литературные примеры исключены' (ignoreCase: false) as substring) | `com.example.sostavslova.ui.components.ComponentInteractionTest.aboutShowsAttributionLicenseAndChangesAtLargeFont(ComponentInteractionTest.kt:130)` | При 200% шрифте доступны источники/лицензии/изменения. Не найден старый регистрозависимый substring. | G11 / D |
| F13 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=320, font=1]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F14 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=320, font=1]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F15 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=320, font=1]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F16 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=320, font=1]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F17 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=320, font=1]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F18 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=320, font=1,5]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F19 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=320, font=1,5]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F20 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=320, font=1,5]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F21 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=320, font=1,5]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F22 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=320, font=1,5]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F23 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=320, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F24 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=320, font=2]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F25 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=320, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F26 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=320, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F27 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=320, font=2]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F28 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=360, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; заголовок уровня (15 FAIL) |
| F29 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=360, font=1]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F30 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=360, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; заголовок уровня (15 FAIL) |
| F31 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=360, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; заголовок уровня (15 FAIL) |
| F32 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=360, font=1]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F33 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=360, font=1,5]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F34 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=360, font=1,5]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F35 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=360, font=1,5]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F36 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=360, font=1,5]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F37 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=360, font=1,5]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F38 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=360, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F39 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=360, font=2]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F40 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=360, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F41 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=360, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F42 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=360, font=2]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F43 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=400, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; заголовок уровня (15 FAIL) |
| F44 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=400, font=1]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F45 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=400, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; заголовок уровня (15 FAIL) |
| F46 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=400, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; заголовок уровня (15 FAIL) |
| F47 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=400, font=1]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F48 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=400, font=1,5]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; заголовок уровня (15 FAIL) |
| F49 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=400, font=1,5]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F50 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=400, font=1,5]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; заголовок уровня (15 FAIL) |
| F51 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=400, font=1,5]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; заголовок уровня (15 FAIL) |
| F52 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=400, font=1,5]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F53 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=400, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F54 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=400, font=2]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F55 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=400, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F56 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=400, font=2]` | `java.lang.AssertionError` | К | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F57 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=400, font=2]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F58 | `CampaignGameUiTest` | `allSevenTasksRiddlesGroupsAndStatisticsAtDifferentSizes[width=840, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Все T1–T7, загадки, группы и статистика; первая ошибка в глобальном assertTextFits, до дальнейших проверок метода. | G01 / E; заголовок уровня (15 FAIL) |
| F59 | `CampaignGameUiTest` | `riddlesRemainUnderTaskAndReflectSolvedUpdates[width=840, font=1]` | `java.lang.AssertionError` | Failed: assertExists.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.riddlesRemainUnderTaskAndReflectSolvedUpdates(CampaignGameUiTest.kt:323)` | Расположение/статус загадок и изменение solved; остановка на старой строке атрибуции до проверки обновления. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F60 | `CampaignGameUiTest` | `hintPartsAreUniqueOrderedAndDoNotExposeFutureStepsAfterRestorationOrTargetChange[width=840, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Уникальность/порядок/скрытие частей подсказки и restoration; первая ошибка в глобальной проверке Text, а не доказанное раскрытие ответа. | G01 / E; заголовок уровня (15 FAIL) |
| F61 | `CampaignGameUiTest` | `finaleSurvivesScreenRestorationAndBothActionsFit[width=840, font=1]` | `java.lang.AssertionError` | Мастер · уровень 30 из 30 | `com.example.sostavslova.ui.game.CampaignGameUiTest.assertTextFits(CampaignGameUiTest.kt:389)` | Диалог финала, restoration и две кнопки; первая ошибка в глобальной проверке Text, не обязательно в диалоге. | G01 / E; заголовок уровня (15 FAIL) |
| F62 | `CampaignGameUiTest` | `accumulatedHintStepsCostAndZeroBudgetAreVisible[width=840, font=1]` | `java.lang.AssertionError` | Action performScrollTo() failed.; Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText contains 'Викисловарь · CC BY-SA 4.0. Скрыты ответ и однокоренные слова.' (ignoreCase: false)) | `com.example.sostavslova.ui.game.CampaignGameUiTest.accumulatedHintStepsCostAndZeroBudgetAreVisible(CampaignGameUiTest.kt:156)` | Показ накопленных шагов, цены и нулевого бюджета; остановка на старой атрибуции до клика/расхода. | G02 / D; общий ресурс и две legacy-fixture (20 FAIL) |
| F63 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=320, font=1, legacy=false]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F64 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=320, font=1, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F65 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=320, font=1,5, legacy=false]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F66 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=320, font=1,5, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F67 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=320, font=2, legacy=false]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F68 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=320, font=2, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F69 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=360, font=1, legacy=false]` | `java.lang.AssertionError` | Разминка · уровень 1 из 15 | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; заголовок уровня (15 FAIL) |
| F70 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=360, font=1, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F71 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=360, font=1,5, legacy=false]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F72 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=360, font=1,5, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F73 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=360, font=2, legacy=false]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F74 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=360, font=2, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F75 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=400, font=1, legacy=false]` | `java.lang.AssertionError` | Разминка · уровень 1 из 15 | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; заголовок уровня (15 FAIL) |
| F76 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=400, font=1, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F77 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=400, font=1,5, legacy=false]` | `java.lang.AssertionError` | Разминка · уровень 1 из 15 | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; заголовок уровня (15 FAIL) |
| F78 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=400, font=1,5, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F79 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=400, font=2, legacy=false]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F80 | `FoundWordsSectionTest` | `emptyAnd146WordsShareOneSectionAndKeepOnlyGroupAndStatisticsActions[width=400, font=2, legacy=true]` | `java.lang.AssertionError` | А | `com.example.sostavslova.ui.game.FoundWordsSectionTest.assertTextFits(FoundWordsSectionTest.kt:168)` | Секция 146 найденных слов, действия/раскрытие/restoration; остановка на глобальном assertTextFits после раскрытия группы, не на числе слов. | G01 / E; буква, общий кандидат LetterTiles (33 FAIL) |
| F81 | `GameNavigationTest` | `hintAndBackKeepSavedAnswers` | `java.lang.IllegalStateException` | No compose hierarchies found in the app. Possible reasons include: (1) the Activity that calls setContent did not launch; (2) setContent was not called; (3) setContent was called before the ComposeTestRule ran. If setContent is called by the Activity, make sure the Activity is launched after the ComposeTestRule runs | `com.example.sostavslova.ui.game.GameNavigationTest.awaitText$lambda$2(GameNavigationTest.kt:47)` | Подсказка и Back сохраняют ответ; root отсутствует уже при ожидании первого feedback, до получения подсказки. | G12 / E |
| F82 | `GameNavigationTest` | `repeatedResumeAndRotationKeepHintAnswersAndDraft` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.game.GameNavigationTest.awaitText(GameNavigationTest.kt:47)` | Resume/rotation сохраняют hint/ответы/черновик; остановка до lifecycle-проверок, на первом feedback. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F83 | `GameNavigationTest` | `levelAnswersStatisticsAndNonInteractiveFoundWordsRemainAvailable` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.game.GameNavigationTest.awaitText(GameNavigationTest.kt:47)` | Ответы, группы и статистика доступны, найденные слова не интерактивны; остановка до основной проверки на первом feedback. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F84 | `GameNavigationTest` | `recreationAndBackContinueRestoreAnswersAndDraft` | `androidx.compose.ui.test.ComposeTimeoutException` | Condition still not satisfied after 10000 ms | `com.example.sostavslova.ui.game.GameNavigationTest.awaitText(GameNavigationTest.kt:47)` | Recreate/Back восстанавливают ответы и черновик; остановка на feedback до recreate. | G03 / E; общий узел диагностики submit/commit/render, единый root не доказан |
| F85 | `GameNavigationTest` | `mapRecreationKeepsDraftsSeparateForDifferentLevels` | `java.lang.AssertionError` | Failed to assert the following: (Text + EditableText = [Первый черновик]); фактически EditableText = '' | `com.example.sostavslova.ui.game.GameNavigationTest$mapRecreationKeepsDraftsSeparateForDifferentLevels$1.invokeSuspend(GameNavigationTest.kt:164)` | Два уровня сохраняют разные черновики после Back и recreate; вместо первого синтетического черновика пустой input. | G05 / A |
| F86 | `GameViewModelTest` | `importedTextsNeverEnterLegacyHintCampaignRiddleOrLadder` | `kotlinx.coroutines.TimeoutCancellationException` | Timed out waiting for 10000 ms | `Нет project-frame; первый доступный: kotlinx.coroutines.TimeoutKt.TimeoutCancellationException(Timeout.kt:189)` | Импортированные определения не попадают в legacy/riddle/ladder. Тест содержит переход с route=2 на чистую закрытую кампанию; какой await истёк, стек не показывает. | G13 / B |
| F87 | `GameViewModelTest` | `finaleLevelsActionAcknowledgesBeforeNavigationAndDoesNotRepeat` | `kotlinx.coroutines.TimeoutCancellationException` | Timed out waiting for 10000 ms | `Нет project-frame; первый доступный: kotlinx.coroutines.TimeoutKt.TimeoutCancellationException(Timeout.kt:189)` | Закрытие финала с переходом к уровням подтверждается и не повторяется. Seed до migrate теряет финальный флаг; точный await в стеке отсутствует. | G14 / B |
| F88 | `LevelsLayoutTest` | `levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid[width=320,fontScale=1]` | `java.lang.AssertionError` | Failed to assert the following: (ContentDescription = 'Звёзды' (ignoreCase: false)); у карточки StateDescription «Получено 0 из 1 звезды», искомое описание задано дочернему StarsRow | `com.example.sostavslova.ui.levels.LevelsLayoutTest.levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid(LevelsLayoutTest.kt:63)` | Карта: wrap/48dp/звёзды/grid; первая ошибка — label звёзд запрашивается у карточки, до остальных layout-проверок. | G04 / B |
| F89 | `LevelsLayoutTest` | `levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid[width=320,fontScale=2]` | `java.lang.AssertionError` | Failed to assert the following: (ContentDescription = 'Звёзды' (ignoreCase: false)); у карточки StateDescription «Получено 0 из 1 звезды», искомое описание задано дочернему StarsRow | `com.example.sostavslova.ui.levels.LevelsLayoutTest.levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid(LevelsLayoutTest.kt:63)` | Карта: wrap/48dp/звёзды/grid; первая ошибка — label звёзд запрашивается у карточки, до остальных layout-проверок. | G04 / B |
| F90 | `LevelsLayoutTest` | `levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid[width=840,fontScale=1]` | `java.lang.AssertionError` | Failed to assert the following: (ContentDescription = 'Звёзды' (ignoreCase: false)); у карточки StateDescription «Получено 0 из 1 звезды», искомое описание задано дочернему StarsRow | `com.example.sostavslova.ui.levels.LevelsLayoutTest.levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid(LevelsLayoutTest.kt:63)` | Карта: wrap/48dp/звёзды/grid; первая ошибка — label звёзд запрашивается у карточки, до остальных layout-проверок. | G04 / B |
| F91 | `LevelsLayoutTest` | `levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid[width=840,fontScale=2]` | `java.lang.AssertionError` | Failed to assert the following: (ContentDescription = 'Звёзды' (ignoreCase: false)); у карточки StateDescription «Получено 0 из 1 звезды», искомое описание задано дочернему StarsRow | `com.example.sostavslova.ui.levels.LevelsLayoutTest.levelsWrapKeep48DpTargetsShowSavedStarsAndUseAdaptiveGrid(LevelsLayoutTest.kt:63)` | Карта: wrap/48dp/звёзды/grid; первая ошибка — label звёзд запрашивается у карточки, до остальных layout-проверок. | G04 / B |
| F92 | `TimeNavigationTest` | `timeReadsWithoutWritesPreservesMapAndStopsGameOnDrawerNavigation` | `java.lang.AssertionError` | Failed to assert the following: (Text + EditableText = [черновик]); фактически EditableText = '' | `com.example.sostavslova.ui.time.TimeNavigationTest$timeReadsWithoutWritesPreservesMapAndStopsGameOnDrawerNavigation$1.invokeSuspend(TimeNavigationTest.kt:70)` | Drawer → время/правила → карта → тот же уровень: сохранить черновик; получен пустой input. | G05 / A |
| F93 | `TimeScreenTest` | `allFieldsFitAt320And360DpWith200PercentFontAndTalkBackHeadings` | `java.lang.AssertionError` | Text must wrap without clipping | `com.example.sostavslova.ui.time.TimeScreenTest.allFieldsFitAt320And360DpWith200PercentFontAndTalkBackHeadings(TimeScreenTest.kt:83)` | Все поля времени читаются при 320/360dp и 200%; overflow отдельного Text, поле и ось не записаны. | G15 / E |


## Контроль полноты и статус

В реестре ровно 93 уникальных F-ID; группы дают 48+20+8+4+2+2+9×1=93. Каждое имя/параметр совпадает с итоговым XML. Ни один FAIL не исключён, не признан PASS и не посчитан дважды. Связи между группами — гипотезы с указанными границами, а не дополнительные ошибки.

В этом анализе сборки, unit/instrumentation-тесты и команды ADB не запускались. Проверена только согласованность сохранённых результатов, ссылок и полнота реестра. Предложения не реализованы; отдельного разрешения на production-изменения этот документ не даёт. **PRE-04 остаётся FAIL.**
