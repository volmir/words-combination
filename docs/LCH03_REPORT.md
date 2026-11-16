# LCH-03 — минимальные игровые подсказки

Выполнено в рабочем дереве 2026-11-16, без commit/push и защищённых путей.
Подключены ровно 39 текстов LCH-02, покрывающие 40 назначений: 9 T3, 11 T4,
20 T6; «артист» используется дважды. Никакие новые тексты не сочинялись,
лицензия не назначена. pending-owner-approval, audit.passed=false и
rights_review.passed=false сохранены. Публикация приложения не разрешена.

## Реализация и файлы

- `tools/build_owned_hints.py`, `tools/owned_hint_index.json`: воспроизводимая
  минимальная SQLite-БД, прежняя схема DefinitionsRepository, отдельный
  индекс категорий/наблюдаемых форм без толкований. Источник индекса —
  принятый CUR-03 пакет; его SHA и SHA локального дампа зафиксированы.
  Перед извлечением SHA дампа независимо сверялся командой sha256sum:
  `98bebfb8013064d23bc6f0981b64cd097f64e098c9e434ebb97684c9606cc2da`.
  Сравнение LCH-02 со всеми 31 369 старыми толкованиями не нашло совпадений
  по принятому критерию. В файл индекса не экспортированы глоссы/примеры.
- `data/definitions.db`, `data/manifest.json`: установлены генератором через
  атомарный publish с откатом. 39 записей own, 0 wiktionary; все 20 120 слов
  имеют строку metadata, а не фиктивное определение. В исходном компактном
  индексе 2562 записи; формы/родство оставлены только для 39 текстов.
  SQLite user_version=10, 1 818 624 байта вместо 17 240 064.
- `tools/build_game_data.py`, `tools/install_short15_package.py`,
  `app/build.gradle.kts`: обязательная проверка минимального набора перед
  sync/build и release, запрет восстановления старого пакета поверх нового.
  SHA БД сравнивается со свежей сборкой, включая все таблицы/поля/страницы.
  Пропущенная/непригодная T4 или посторонние байты блокируют даже техническую
  сборку по waiver. Старые инструменты могут готовить отдельные кандидаты,
  но не подменять установленный минимальный набор.
- `tools/owned_hints.py`: проверяет установленный набор по LCH-02 и
  зафиксированному исходному индексу; исторические документы LCH-02 не
  переписаны сравнением текстов с самими собой.
- `DefinitionsRepository.kt`, `GameViewModel.kt`: дополнительный запрет
  не-own записей и непустого source во всех игровых ветках. Для слова вне
  набора нет смыслового текста; доступные категория/буквенные шаги работают
  самостоятельно. API и SQLite-схема сохранены для редакторских инструментов.
- `SqliteDefinitionsRepository.kt`: asset проверяется по BuildConfig SHA;
  распакованная копия заменяется по содержимому, даже при равной/большей
  старой user_version. При отсутствии или ошибке asset прежняя БД удаляется
  без открытия. Всё выполняется на IO.
- `HintLadder.kt`, `CampaignProgressDto.kt`: при чтении старого снимка
  удаляются DEFINITION-ступени, включая записи без wiktionary-флага.
  Пересчитывается открытый префикс; цель, открытые буквы, исторические
  shownSteps/paidUnits сохраняются. Новое поле с default=0; новые снимки
  имеют ownedContentVersion=1 и сохраняют собственный текст без доступной БД.
- `legal/THIRD_PARTY_NOTICES.txt`, `strings.xml`: актуализировано происхождение
  словарных данных; чужие толкования больше не заявлены частью сборки.
  Для производного индекса сохранены атрибуция Викисловаря, CC BY-SA 4.0
  и адреса статей в word_sources.jsonl.gz. category_source сохраняет
  структурированные поля, правила, прежнюю классификацию или решение
  владельца; все 20 T6 имеют owner:SHORT15-05:2026-11-16.
  Лицензия собственных описаний — явный плейсхолдер владельца.
- `docs/LEVEL_HINTS.md`, `docs/PROJECT_STATUS.md`: установка, восстановление,
  режим без БД, происхождение категорий, актуальные числа и ограничения.

`GameScreen` прочитан; визуальные эффекты и разметка не менялись. Он получает
уже очищенный GameUiState. TaskRules/HintRules, T1/T2/T5/T7, 15/49, R/M,
цели, словарь и campaign.json не менялись. T3 принимает любое валидное слово
нужной длины; T6 — любые четыре из всех 20 профессий, даже с пустой картой
описаний. Показ ответа ничего автоматически не засчитывает. Неизвестные
тема/определение отсутствуют в лестнице и не расходуют шаг; бюджет, бесплатные
шаги T4, накопление и смена цели после угадывания сохранены.

Прогресс не удалялся. Идентификатор кампании остался прежним, поэтому замена
текстов не запускает сброс кампании. Неподсказочные поля и остальные ключи
DataStore миграция не трогает; Android-тест отдельно проверяет это на
постороннем ключе времени. Фактический upgrade на устройстве ещё NOT RUN.

## Добавленные и обновлённые проверки

- `OwnedHintsTest`, `HintRulesTest`: все 11 T4, пригодность текстов настоящим
  core, T3 вне hintTarget и набора описаний, каждый участник полного T6-пула,
  динамическая цель, отсутствие расхода за отсутствующее описание,
  отсутствие автоматического зачёта ответа, миграция лестницы.
- `test_owned_hints_install.py`: повторная генерация с точным SHA,
  отсутствие сторонних/скрытых полей, подмены text/origin/notes/metadata,
  удаление обязательного текста, устаревший исходник, отсутствие БД,
  невозможность обхода содержательных проверок waiver, источник категории,
  запрет старой установки, отдельный запуск release CLI.
- `test_campaign_review_export.py`: ожидание действующего пакета изменено
  с 31 369 wiktionary на 39 own / 0 wiktionary. Старые синтетические тесты
  редактора с толкованиями сохранены и прошли; частичная БД их не ломает.
- `SqliteDefinitionsRepositoryTest`: реальный минимальный asset, категория,
  отсутствие произвольного толкования, замена более новой распакованной
  копии, удаление старой БД при отсутствующем asset. Ожидание запрета
  downgrade заменено авторитетностью bundled-содержимого по требованию LCH-03.
- `CampaignProgressRepositoryTest`, `GameViewModelTest`: старое сохранённое
  определение без атрибуции, сохранность ответов/звёзд/счётчиков/позиций,
  файловый перезапуск, отказ от стороннего текста в legacy-подсказке,
  загадке и лестнице; реальный asset всех T4, резервный режим. Положительные
  фикстуры изменены на own; добавлены отрицательные для wiktionary.
  Эти Android-тесты скомпилированы, выполнение NOT RUN.

## Команды и результаты

Gradle запускался офлайн с
`JAVA_HOME=$HOME/.local/share/words-combination/jdk21`.

```sh
python3 -m tools.owned_hints
python3 tools/build_owned_hints.py --extract-index --dump .local/dictionary/raw-wiktextract-data-2026-11-01.jsonl.gz
python3 tools/build_owned_hints.py
python3 tools/build_owned_hints.py --check
python3 -m unittest tools.tests.test_owned_hints_install tools.tests.test_owned_hints tools.tests.test_short15_install
python3 -m unittest discover tools/tests
bash ./gradlew --offline :core:test --tests 'com.example.sostavslova.core.HintRulesTest' --console=plain
bash ./gradlew --offline :core:test --tests 'com.example.sostavslova.core.OwnedHintsTest' --tests 'com.example.sostavslova.core.HintRulesTest' --console=plain
bash ./gradlew --offline :core:test :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleRelease --console=plain
bash ./gradlew --offline :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain
bash ./gradlew --offline :core:auditDefinitionHints '--args=/var/www/words-combination/build/reports/lch03/hint-input.jsonl /var/www/words-combination/build/reports/lch03/hint-output.jsonl' --console=plain
python3 tools/build_game_data.py --check-release --out data
python3 tools/build_game_data.py --check-release --out data --allow-unaudited-release
```

Итог: **462 Python-теста OK**, **142 core-теста, 0 failures/errors/skipped**.
Отдельный JVM-аудит: **39/39** пригодных, 0 масок/потерь; каждый выходной текст
побайтово равен собственному исходнику. compileDebugKotlin,
compileDebugAndroidTestKotlin, verifyDictionaryLicenses, syncDictionaryAssets
прошли. Lint: **0 errors, 9 warnings**. Технический assembleRelease прошёл
по уже существующему waiver, без изменения gradle.properties. Строгий CLI
без waiver завершился ожидаемым кодом 2: тексты/лицензия ещё не утверждены.
Waiver оставляет publishable=false. Для минимального набора ручной аудит
должен охватить все 39 записей (0 плохих, SHA текущей БД); прежний порог
выборки ≥300 для больших старых баз не ослаблен.

При разработке были исправлены: несовместимость общего publish с opaque/
null манифестами и импорт модуля при отдельном CLI-запуске. Один промежуточный
прогон обнаружил различие пакета в тесте повторного кэшированного запуска;
после стабилизации исходников отдельный тест и полный финальный прогон
прошли. Проверки и ожидания воспроизводимости не ослаблялись.

Release APK прочитан как ZIP: SHA assets/definitions.db
`8e35a322b2d4f726191231aacb20948ee310fa8750609650ba605bc860599268`,
SHA assets/campaign.json
`1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e`;
оба совпадают с data/. Словарь имеет прежний SHA
`9c1aed9e0011e4173dffd0f4281832d821d4de90623d19273f7fc8e68c212489`.
Локальные доказательства JVM — build/reports/lch03/; логи проверок —
/tmp/lch03-*.log (не защищённый orchestrator).

## Не проверено и риски

- Android instrumentation, настоящий upgrade/перезапуск процесса и ручной UI:
  **NOT RUN**, `adb devices` пуст; компиляция не подтверждает выполнение.
- Полный gate выполняет внешний оркестратор; новый assembleDebug не запускался.
- Утверждение текстов, лицензии и ручной аудит прав не выполнены. Публичный
  выпуск запрещён; автоматическое сравнение не является юридическим аудитом.
- Исторические debug-APK и локальные кэши могут содержать прежние толкования;
  они не входят в текущий release APK и не должны использоваться как новый пакет.
