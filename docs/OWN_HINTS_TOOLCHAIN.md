# Воспроизводимая сборка загадок T4

Действующий контракт от 2026-11-17: отдельный UTF-8 asset
`data/owned_riddles.json`, схема `owned-riddles`, версия 1. В нём ровно
11 утверждённых собственных загадок, лицензия CC0-1.0. Остальные 28 текстов
остаются в каноническом авторском снимке, но не поставляются в приложение.

Источник всех 39 текстов — неизменный `docs/owned_hints.json`.
`OWN_HINTS_OWNER_APPROVAL.json` и `OWN_HINTS_MANUAL_AUDIT.json` — независимые
решение владельца и ручной аудит 39/39. Их не создаёт и не исправляет сборщик.
`tools/owned_riddles_contract.json` связывает точные SHA этих трёх документов,
лексического индекса и список 11 T4-ключей. Историческое поле прежнего
пакета в аудите сохраняется как evidence, но не является входным бинарником.

## Чистая локальная среда и будущий F-Droid recipe

Нужен Python 3.10+ и стандартная библиотека; собственный генератор не требует
сети, Git-истории, нативного worker, локального toolchain или дампа словаря.
Из полного исходного дерева:

```sh
python3 tools/build_owned_hints.py
python3 tools/build_owned_hints.py --check
python3 tools/build_game_data.py --check-release --out data
bash ./gradlew --offline :core:test :app:assembleDebug :app:assembleDebugAndroidTest
```

Первая команда воспроизводит только JSON и соответствующий блок manifest.
`campaign.json`, словарь, уровни и правовые документы не записываются.
Если JSON поставляется в checkout, достаточно `--check`. Отсутствующий или
изменённый файл в режиме проверки приводит к ошибке; скачиваний нет.

Для Gradle нужны JDK 21, Android SDK и обычные кэши зависимостей. Отсутствие
этих кэшей — отдельное ограничение offline-сборки, оно не относится к генератору.
F-Droid metadata и финальный recipe ещё требуют отдельной приёмки.
Подпись — [RELEASE_SIGNING.md](RELEASE_SIGNING.md).

## Детерминизм и строгие отказы

Сериализация: UTF-8 без BOM, ключи отсортированы, `ensure_ascii=False`,
разделители `,`/`:`, один завершающий LF. Схема содержит только `schema`,
`version`, `license`, `riddles`; последний объект — нормализованный ключ →
дословный текст. Хэш проверяется в manifest, Gradle, APK и runtime.
Дубли ключей, пустой/изменённый текст, лишняя цель, недостающая цель,
неполные/устаревшие approval или audit дают FAIL. Waiver отсутствует.

`verifyOwnedHints`, `verifyDefinitionsAudit` (историческое имя строгого
release gate), `verifyDictionaryLicenses`, `verifyRuntimeNotices` и
`verifyPrivacyPublication` сохраняют проверки содержимого, происхождения,
прав и упаковки. Последние два независимо проверяют библиотеки и приватность.
Удаление старого формата не решает оставшиеся правовые вопросы wordlist,
графики, Protobuf, подписанного релиза и F-Droid.

Восстановление сохранений не зависит от формата загадок: идентификатором
кампании остаётся SHA исходных байтов `data/campaign.json`.
Полная инвентаризация и результаты — [SQLITE_REMOVAL.md](SQLITE_REMOVAL.md).
