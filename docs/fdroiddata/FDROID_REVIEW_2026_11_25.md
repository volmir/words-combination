# MR !58000: замечания ревьюверов от 2026-11-25

## Замечания и исправления

Источник — актуальные обсуждения [MR !58000](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000).

- [Комментарий 4318364838](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000#note_4318364838):
  удалить `metadata/io.github.volmir.wordscombination/en-US/summary.txt`
  и `MaintainerNotes`. Оба удалены; описания остаются в Fastlane тега 1.1.1.
- [Комментарий 4318400300](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000#note_4318400300):
  buildserver уже работает на JDK 21. Из сборки удалены `sudo` и `prebuild`.
- [Комментарий 4318294418](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000#note_4318294418):
  участник сообщил об успешном запуске подписанного APK на Redmi Note 14 Pro+
  (Android 16), работе первого уровня и правильной реакции на исходное слово.
  Это внешний отзыв об ограниченной проверке, а не полный регрессионный QA.

Изменены рецепт fdroiddata и его [локальная копия](metadata/io.github.volmir.wordscombination.yml),
[RELEASE_PROCESS.md](../RELEASE_PROCESS.md), [PROJECT_STATUS.md](../PROJECT_STATUS.md)
и [INDEX.md](../INDEX.md). Код игры, версия, словарь, подпись, релизный SHA
`bfeec0e6d67a2972622416d75abb3cd066562318` и опубликованные теги сохранены.
`scanignore` для provenance, четыре QA-исключения, `Binaries`, ключ подписи
и автоматические обновления `Version` / `Tags` сохранены.

Исправления отправлены в существующую исходную ветку MR коммитом
`41d671864b66d11895313a7f4316a657e81ba4f0`. Текст MR обновлён через API;
старый успешный pipeline явно отделён от проверки нового рецепта.

## Проверки

- PASS: статическая проверка текущего рецепта и релизного тега.
- PASS: 14 тестов `tools.tests.test_fdroid_submission_candidate`.
- PASS: `fdroid rewritemeta` не изменяет текущий YAML, `fdroid lint` успешен.
- PASS: `fdroid checkupdates --auto --allow-dirty` до коммита и
  `fdroid checkupdates --auto` после push находят 1.1.1 (7), тег
  и SHA совпадают; файл YAML не меняется.
- PASS: `fdroid scanner --exit-code io.github.volmir.wordscombination:7`.
- PASS: `fdroid build -v -l --test io.github.volmir.wordscombination:7`;
  Gradle завершил сборку за 2 минуты 22 секунды. Журнал daemon для этой
  сборки подтверждает `javaVersion=21`, Eclipse Adoptium.
- PASS: неподписанный APK нового рецепта имеет SHA-256
  `91d8b2f812ba1a533ae8dc4f0b0d337b63845e5173af249a5abe093b472b7b15`.
- PASS: заново скачанный APK GitHub имеет SHA-256
  `43fac7440386d0c8362e3c6e0b934451a2e453c1d89f1b9a8277d3c0c80af593`;
  `apksigcopier compare --unsigned` сравнивает его с новой сборкой успешно.
  `apksigner verify` подтверждает подпись и сертификат
  `a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353`.
- PASS: `bash orchestrator/run.sh --check docs`, проверка privacy,
  `git diff --check` и gate `:core:test :app:assembleDebug --offline`.
  Этот gate использовал актуальные кешированные результаты; полный повтор
  всех регрессионных тестов для изменений только метаданных не выполнялся.
- PASS: сравнение разобранного YAML подтверждает удаление только трёх полей;
  Changes MR содержит только YAML приложения, дублирующий summary исчез.
- PASS: актуальный pipeline **3112340029**, 9/9 jobs, commit
  `41d671864b66d11895313a7f4316a657e81ba4f0`. Pipeline доступен на вкладке
  [Pipelines MR](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000/pipelines).
- PASS: независимая сборка CI (job **18349285873**) и check apk
  (job **18349285881**); скачанный неподписанный артефакт CI имеет SHA-256
  `91d8b2f812ba1a533ae8dc4f0b0d337b63845e5173af249a5abe093b472b7b15`
  и отдельно прошёл `apksigcopier` относительно скачанного публичного APK.
- PASS: все пять codequality Reports скачаны и изучены: шесть записей `info`
  (две локализации Fastlane, воспроизводимый APK, сертификат, размеры ABI, R8),
  ноль записей warning/error. В trace check apk есть предупреждения инструмента
  о правах `config.yml` и устаревшей таблице Android API (36 → 28); job успешен,
  бинарный scanner и проверка подписи не обнаружили ошибок.

## Описание MR и чекбоксы

Описание обновлено через API и повторно прочитано для проверки сохранения.
Успешные сборки текущего рецепта указаны отдельно от исходной регрессии релиза.
Сохранены все 28 пунктов шаблона; подтверждены 27. Три превью скриншотов шириной
220 px сохранены.

| Пункт | Было | Стало | Доказательство |
|---|---|---|---|
| Только YAML; описания upstream | Отмечен, но оставался summary | Отмечен после удаления дубликата | Changes API: только YAML |
| Pipelines | Прежний PASS; снят на время нового CI | Отмечен | Новый pipeline 3112340029, 9/9 |
| Reports | Прежний PASS; снят на время проверки | Отмечен | 5 текущих артефактов, 6 info |
| Inclusion Policy | Не отмечен | Не отмечен | Решение сопровождающего не получено |

Итог обработки замечаний: **DONE**. MR открыт; приложение ещё не принято
в каталог. Слияние MR самостоятельно не выполнялось.

Локальные инструменты: fdroidserver commit
`fc2c86473e66a44fc641f403c338a8b2cccfad88`, ruamel.yaml 0.18.10,
штатный gradlew-fdroid commit `ac23ffb5285c0c0961a677e310dd58ef90531057`.
Материалы проверки — `/tmp/fdroid-review-58000/`.
Два стартовых сообщения о копировании локальных process logs относились к
ещё не созданному временному каталогу; каталог создан, финальные logs скопированы
успешно. На компиляцию и проверку APK это не повлияло. Локальный fdroidserver
также предупредил о правах 0644 на существующий `config.yml`.

Исторические отчёты предыдущих запусков не изменены. Inclusion Policy требует
решения сопровождающего; техническая проверка и внешний отзыв не подтверждают
принятие приложения в каталог. Новый APK не выпускался.
