> **Историческое свидетельство до перехода на JSON (2026-11-17).**
> Описанные здесь прежний формат, команды и статусы не задают текущую сборку.
> Действующий контракт: [OWN_HINTS_TOOLCHAIN.md](OWN_HINTS_TOOLCHAIN.md). Исходные правовые выводы сохранены для прослеживаемости.

# RIGHTS-02 — результат

Выполнена техническая поддержка PENDING / APPROVED с двумя независимыми
человеческими свидетельствами. Реальные одобрение и ручной аудит не выполнялись.

## Изменения

- `tools/owned_hints_rights.py`: общий read-only валидатор точной схемы акта
  RIGHTS-01, календарных дат, четырёх булевых подтверждений, counts 39/40,
  CC0-1.0 и SHA байтов кандидата/кампании. Повторные/лишние поля и подмены
  типов отклоняются. Отдельно проверяется полный ручной аудит 39 текстов,
  его дата, проверявший человек, отсутствие проблем и SHA входов/итоговой БД.
- `tools/build_owned_hints.py`: единое вычисление definitions-блока для install
  и verify; подтверждённое состояние больше не сбрасывается. Проверяются весь
  блок manifest и побайтная воспроизводимость БД. В SQLite меняются только
  служебные поля лицензии и доказательства утверждения, тексты сохраняются.
- `tools/owned_hints.py`: пояснена неизменность авторского pending-снимка.
- `tools/build_game_data.py`: release использует тот же verify; один акт
  без отдельного ручного аудита не открывает ворота. CLI-waiver не разрешает
  release собственных подсказок. Общие импорты работают и вне корня проекта.
- `app/build.gradle.kts`: сохранены `verifyDefinitionsAudit`,
  `verifyDictionaryLicenses`, `verifyOwnedHints` и их зависимости. Удалена
  передача старого waiver-свойства в release-проверку.
- `tools/tests/test_owned_hints_rights.py`: синтетические временные фикстуры
  для обеих фаз, повторной установки, неизменности текстов и отрицательных
  случаев. Реальные свидетельства в репозитории не создаются.
- `tools/tests/test_owned_hints_install.py`: прежние ожидания технического
  release с waiver заменены обязательным отказом согласно RIGHTS-02;
  проверки усилены, тесты не отключались.
- `docs/OWN_HINTS_VERIFICATION.md`: схема отдельного ручного отчёта,
  переходы, порядок проверки итоговой БД и граница доверия.

## Реально выполненные проверки

| Проверка | Результат |
|---|---|
| `python3 tools/build_owned_hints.py --check` | PASS: 39 own, 0 wiktionary, 11 T4, draft |
| `python3 -m unittest discover tools/tests` | PASS: 483 теста, 103,168 с |
| `python3 -m unittest tools.tests.test_owned_hints_rights` после дополнения проверок метаданных/флагов | PASS: 8 тестов с отрицательными подслучаями |
| `:app:verifyDictionaryLicenses --offline --console=plain` | PASS, включая verifyOwnedHints и verifyRuntimeNotices |
| `python3 tools/build_game_data.py --check-release --out data --allow-unaudited-release` | Ожидаемый отказ, exit 2: waiver недопустим |
| `:app:assembleRelease -Psostavslova.release.allowUnauditedDefinitions=true --offline --console=plain` | Ожидаемый отказ на verifyDefinitionsAudit; Gradle exit 1, Python exit 2. До подписи выполнение не дошло |
| Проверка реальных SHA/manifest и отсутствия файлов свидетельств | PASS: исходные данные и оба false сохранены |
| `git diff --check` | PASS |

Gradle запускался через `bash gradlew` с локальным JDK 21. Первый узкий прогон
обнаружил ошибку синтетической фикстуры: категория `objects` отсутствует в
производственной таксономии. Фикстура исправлена на существующую `other`;
валидатор и критерии не ослаблялись. Последующие прогоны прошли.

## Сохранённое реальное состояние

- Кандидат SHA-256: `061633244ec968aeec2e0dc50aca1b61ecb8ca735590c5510938ab5085b711b0`.
- Кампания SHA-256: `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e`.
- БД SHA-256: `8e35a322b2d4f726191231aacb20948ee310fa8750609650ba605bc860599268`.
- `audit.passed=false`, `rights_review.passed=false`, лицензия — плейсхолдер,
  `publication_status=draft-not-for-public-release`.
- Реальные `docs/OWN_HINTS_OWNER_APPROVAL.json` и
  `docs/OWN_HINTS_MANUAL_AUDIT.json` отсутствуют. `data/` не менялась.

Полный gate не запускался: по заданию его выполняет внешний оркестратор.
Устройство и androidTest не запускались; код игры/UI не менялся.
Защищённые пути не менялись, git commit/push/add/checkout/reset не выполнялись.

## Ограничения

JSON/SHA не удостоверяют личность и не доказывают проведение человеческого
аудита; свидетельства должны быть действительно заполнены соответствующими
людьми. APPROVED проверен только на синтетике. Реальный переход и ручной
аудит остаются за RIGHTS-03; публикация не разрешена и не выполнялась.
