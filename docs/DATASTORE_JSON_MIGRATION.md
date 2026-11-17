# Preferences DataStore → DataStore Core + JSON

Статус: **реализация подготовлена, Android-приёмка не выполнена**.
Не считать релизной до прохождения перечисленных ниже проверок.

## Контракт обновления

- Сохранять `datastore/progress.preferences_pb` и `datastore/play_time.preferences_pb` без записи и удаления.
- При отсутствии соответствующего нового `progress.json` или `play_time.json` однократно читать прежний `PreferenceMap` собственным строгим декодером и переносить все значения в версионированный JSON.
- Не считать повреждённые или неподдерживаемые значения пустым прогрессом: ошибка должна блокировать обновление.
- После успешного переноса читать и записывать только JSON через `DataStoreFactory`, сохраняя его атомарные обновления, Flow и файловые барьеры.
- Пользовательские `campaignProgressV2`, архивы, `paidUnits`, `welcomeStateV1`, legacy-уровни и `playTimeV1:*` сохраняют ключи и исходный вложенный JSON. Идентификатор, SHA и состав кампании не изменяются.
- Тестовые репозитории тоже используют новый JSON DataStore; отдельный миграционный тест строит бинарные образцы формата AndroidX без Protobuf-библиотеки.

## Проверки перед выпуском

1. Выполнить `bash gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain`.
2. Выполнить адресные Android-тесты `JsonPreferencesMigrationTest`, `ProgressRepositoryTest`, `CampaignProgressRepositoryTest`, `LegacyCampaignMigrationTest`, `CampaignJsonUpgradeTest`, `PlayTimeRepositoryTest`, `PlayTimeAcceptanceTest`, `PlayTimeProgressIntegrationTest` и регрессию навигации/подсказок.
3. На установленной старой версии с реальными сохранениями сделать обновление APK **без очистки данных**; сверить значения звёзд, слов, frontier, paidUnits, буквенных подсказок, архивов и времени. Сверить SHA старых `.preferences_pb` до и после и отсутствие списания при повторном запуске.
4. Выполнить `bash gradlew :app:exportRuntimeLicenseInputs`; затем `python3 tools/build_library_notices.py` и `bash gradlew :app:verifyRuntimeNotices :app:verifyDictionaryLicenses :app:assembleDebug :app:assembleRelease --console=plain`.
5. Подтвердить в **новом** `legal/RUNTIME_LIBRARIES.json`, `legal/LIBRARY_NOTICES.txt`, APK/AAB и разрешённых runtimeClasspath отсутствие `datastore-preferences*`, `protobuf*` и перепакованного Protobuf. Проверить, что Apache/MIT/CC BY-SA/CC0 и иные реальные notices остались.
6. Не переписывать исторические аудиты: их упоминания старой версии DataStore относятся к прежним поставкам.

## Известное ограничение подготовки

В текущем окружении нет Android SDK и Gradle 9.3.1; Gradle-wrapper не может скачать дистрибутив. Поэтому Kotlin/Android-компиляция, миграция на реальном телефоне и пересборка реестра библиотек **не проверены**. До их выполнения старые сгенерированные `legal/RUNTIME_LIBRARIES.json` и `legal/LIBRARY_NOTICES.txt` намеренно не перезаписывались предположениями: release gate должен остановиться, если они не соответствуют фактической сборке.
