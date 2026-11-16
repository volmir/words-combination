# RIGHTS-03 — применение решения владельца

Дата проверки: 2026-11-16. Статус: **DONE для слоя подсказок**.

## Свидетельства и содержательная проверка

Существующий акт владельца принят валидатором RIGHTS-02: точные 39 текстов,
40 назначений, CC0-1.0 и четыре подтверждения владельца. Источник ручного
аудита — предоставленные `OWN_HINTS_MANUAL_AUDIT.json` и
`OWN_HINTS_MANUAL_AUDIT.md`, проверяющий Project Owner, дата 2026-11-16.
Агент не создавал и не редактировал эти человеческие свидетельства.

При возобновлении прочитаны все 39 полных подсказок и назначения, проверены
понятность, соответствие значениям и контексту заданий, отсутствие раскрытия
ответов и постороннего словарного материала. Новых содержательных замечаний
не обнаружено. Подтверждение происхождения и прав опирается на акт владельца
и отдельный человеческий аудит, а не на автоматическое сравнение SHA.
Протокол содержит 39/39 проверок, 156 отметок, 0 нерешённых проблем; точные
тексты, индивидуальные SHA и все 40 назначений сверены с кандидатом.
Проверка БД побайтным воспроизведением исключает скрытые дополнительные
определения и старые Wiktionary-глоссы.

| Файл | SHA-256 |
|---|---|
| `docs/owned_hints.json` | `061633244ec968aeec2e0dc50aca1b61ecb8ca735590c5510938ab5085b711b0` |
| `data/campaign.json` | `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e` |
| `docs/OWN_HINTS_OWNER_APPROVAL.json` | `50ea8dd86af6a32a13165e21378273b9734557f6980cc5ccea518a7d04e3e128` |
| `docs/OWN_HINTS_MANUAL_AUDIT.json` | `9f6bec48459ef460fab2d11f50745bf74db9c0322d4571f5092f1378daedaff1` |
| `docs/OWN_HINTS_MANUAL_AUDIT.md` | `a6b2afc63283ed7c6f514bd0c920752d9923b3bba20a456d6688156315773ae6` |
| `data/definitions.db` | `73d09ac2d445ad7f4c30226248b30769d6e46bd6858a8907750d16561b258d7d` |
| `data/manifest.json` | `248f326bd8538d127eb64747fe25c7f46838bf0773718164ba16a9f585361e61` |

## Результат

Сохранены полезные изменения предыдущей попытки: БД, производный манифест,
раздел юридического уведомления о 39 подсказках и изоляция pending-фикстур
в тестах. Ожидание экспорта сверяется с валидированными свидетельствами;
проверки отказов и запрета waiver сохранены.

Существующим `tools.build_owned_hints.install` дважды пересобраны БД и
манифест: own=39, wiktionary=0, own_license=CC0-1.0, checked=39, bad=0,
audit.passed=true и rights_review.passed=true получены из свидетельств.
Вторая генерация побайтно совпала с первой. Кандидат, кампания, слова и все
три документа человеческих свидетельств остались побайтно неизменны.

NOTICE содержит CC0-1.0 и официальные ссылки на лицензию и полный legalcode
только для 39 текстов. Лицензии слов, word_metadata, библиотек и прочих
компонентов не заменены. Стандартная Gradle-синхронизация assets прошла;
definitions.db и THIRD_PARTY_NOTICES.txt в assets совпадают с источниками.
Манифест штатно не копируется в assets.

## Проверено в текущей попытке

- `owner_decision` и `publication_fields`: PASS, точные SHA и 39/39 записей.
- Сверка Markdown с кандидатом: PASS, 39 текстов, 40 назначений, 156 отметок.
- Два вызова `tools.build_owned_hints.install(Path('data'))` и отдельный
  `verify(Path('data'))`: PASS, повторная генерация побайтно идентична.
- `python3 -m unittest tools.tests.test_campaign_review_export tools.tests.test_owned_hints_install tools.tests.test_owned_hints_rights tools.tests.test_owned_hints_owner_review tools.tests.test_privacy_publication`:
  36 тестов, PASS.
- `bash ./gradlew :app:verifyDictionaryLicenses :app:syncDictionaryAssets :core:test --console=plain`:
  BUILD SUCCESSFUL, лицензии и assets PASS, 155 core-тестов PASS.
- `python3 tools/check_privacy_publication.py --root . --require-generated`:
  PASS после регенерации производного манифеста с предоставленным
  обезличенным свидетельством. Прежний блокер приватности снят.
- Побайтная сверка БД и NOTICE с Android assets: PASS.
- `git diff --check`: PASS.

## Ограничения

Полный gate и assembleDebug в этой попытке не запускались: по заданию gate
выполняет внешний оркестратор. Устройство, release и публикация не проверялись.
`publication_status=approved` относится только к подсказкам и не разрешает
публикацию APK до LEG-02/FINAL15-01. Waiver, Git-операции записи и изменения
защищённых путей не выполнялись.
