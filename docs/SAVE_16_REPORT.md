# SAVE-16 — правила переноса прогресса между телефонами

## Изменения

- `app/src/main/AndroidManifest.xml`: `allowBackup=true`, подключены
  `dataExtractionRules` и `fullBackupContent`. Разрешения не добавлены.
- `app/src/main/res/xml/data_extraction_rules.xml`: облако полностью исключено;
  для переноса между устройствами разрешён только `files/datastore/progress.json`
  (`domain="file"`, `path="datastore/progress.json"`).
- `app/src/main/res/xml/backup_rules.xml`: полностью исключены оба режима
  резервного копирования на Android 7–11 (API 24–30). Переноса прогресса на этих
  версиях нет; разделение режимов действует с Android 12 (API 31).
- `tools/tests/test_backup_rules.py`: четыре проверки XML, точного файла
  DataStore, атрибутов манифеста и отсутствия разрешений во всех исходных
  манифестах приложения.
- `app/src/androidTest/kotlin/com/example/sostavslova/data/ProgressRestoreTest.kt`:
  два сценария копирования настоящего файла DataStore между временными
  каталогами. Новый `AppContainer` использует штатный путь без переопределения
  `progressFile`; проверяются чтение прогресса, согласование прежней/изменённой
  кампании, сохранение найденных слов, звёзд и frontier, идемпотентность миграции
  и `welcomeStateV1=DONE` без повторного приветствия.

В облаке и в правилах для API 24–30 полностью исключены все девять доменов:
`root`, `file`, `database`, `sharedpref`, `external`, `device_root`,
`device_file`, `device_database`, `device_sharedpref`.

В `device-transfer` единственный `include` образует разрешительный список для
всех доменов: остальные файлы, включая старый `progress.preferences_pb`, временные
файлы и удалённое хранилище времени, не включаются. Каталоги cache, code_cache
и no_backup исключаются самой платформой. Семантика разрешительного списка и
приоритет исключений сверены с [документацией Android](https://developer.android.com/identity/data/autobackup).

## Проверки

- `python3 -m unittest tools.tests.test_backup_rules -v`: 4 теста, PASS.
- `python3 -m unittest discover tools/tests`: 594 теста, PASS.
- `bash gradlew :app:compileDebugAndroidTestKotlin :app:lintDebug --console=plain`:
  PASS. В отчёте lint 0 ошибок, 13 предупреждений по другим ресурсам;
  предупреждений о резервном копировании нет. Первый запуск компиляции выявил
  ошибочное имя константы в новом тесте; после исправления повторный запуск успешен.
- `python3 tools/check_docs.py`: PASS.
- `python3 -m unittest tools.tests.test_spec_campaign_numbers -v`: 3 теста, PASS.
- `git diff --check`: PASS.
- `adb devices`: подключённых устройств и эмуляторов нет. Сценарии
  восстановления на Android и фактический системный перенос между телефонами
  не проверены выполнением.

## Ограничения и смежные находки

Фактический актуальный DataStore — JSON, а не `preferences_pb`, упомянутый
в исходной формулировке задания. Это подтверждено `AppContainer` и сериализатором;
старый protobuf оставлен только как источник миграции и не переносится.
Существующих тестов с ожиданием `allowBackup=false` в исходниках приложения
и `tools/` не найдено. Правовые тексты оставлены для PRIV-17 без изменений.
Данные, зависимости, защищённые пути и Git-история не изменялись.
`./gradlew` не имеет права исполнения, поэтому Gradle запускался через `bash`.
Отдельного `tools/check_spec_numbers.py` нет; сверка чисел выполнена существующим
`test_spec_campaign_numbers`. Gates оставлены внешнему оркестратору.
