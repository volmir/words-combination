# PRIV-01 — статический аудит приватности и анонимная публикация

Дата: **2026-11-15**. Исходный HEAD на начало задачи: `5ee9ba8313d1dd47b3f3292a0ffeab620f605802`.
Область: новый APK, пользовательские экраны, строгие ресурсы, generated assets,
публикационные документы и планируемый source repo. Сеть, старые публичные копии
и переписывание Git-истории в область проверки не входят.

## 1. Итоговый статус

| Область | Статус | Комментарий |
|---|---|---|
| Новый APK | **PASS в границах PRIV-01** | В debug и release APK не найдены имя правообладателя, личные контакты, ссылки на личные профили и старые self-copyright notices. |
| Пользовательский UI | **PASS** | `AboutScreen` показывает обезличенную MIT-лицензию проекта; персональная атрибуция и ссылки удалены. |
| Строковые ресурсы | **PASS** | Все `values*/strings.xml` проверены автоматически; URL разрешены только для Викисловаря, Kaikki и Creative Commons. |
| Собственная лицензия кода | **PASS** | Корневой `LICENSE` MIT, правообладатель обезличен; полный текст также в assets APK. |
| Уведомления третьих лиц | **PASS** | Wiktionary CC BY-SA 4.0, Kaikki/Wiktextract, FrequencyWords, Apache-2.0 и BSD-3-Clause сохранены. |
| Публичный Git как есть | **НЕ АНОНИМЕН** | Старые author-идентичности, исторические URL и текущий личный remote остаются; история не переписывалась. |
| F-Droid metadata | **НЕ ПРОВЕРЕНО / ОТСУТСТВУЕТ** | В репозитории нет `fdroiddata`/`fastlane`; карточку нельзя проверить, пока её нет. |
| Публикация приложения в целом | **ОСТАЁТСЯ ЗАБЛОКИРОВАНА другими причинами** | Ручной аудит прав определений, подпись, `applicationId` и F-Droid-рецепт — вне PRIV-01. |

`PERSONAL_DATA_APK_UI_STATUS: PASS` в пределах перечисленных артефактов.
Это не PASS для всей сети интернет и не разрешение на публикацию приложения:
LEG-01 и release-процесс остаются в силе.

## 2. Метод

Проверка выполнялась на рабочем дереве после Gradle sync, а не только по старому
снимку. Использованы:

```bash
git grep -I -n -E '<private marker policy>' -- . ':!orchestrator/.runtime'
python3 tools/check_privacy_publication.py --root . --require-generated
bash gradlew :app:assembleDebug :app:assembleRelease --console=plain
aapt2 dump resources app/build/outputs/apk/debug/app-debug.apk
aapt2 dump resources app/build/outputs/apk/release/app-release.apk
unzip -q app/build/outputs/apk/release/app-release.apk -d <tmp>
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Персональные маркеры в тесте хранятся как SHA-256 нормализованных токенов в
`tools/privacy_publication_policy.json`; сами значения в публикуемых файлах не
повторяются. Дополнительно проверены zip-содержимое `CAMPAIGN_REVIEW_15.xlsx`,
JFIF/PNG-метаданные иконок, `metadata.json`, Gradle/manifest и legal assets.

## 3. УДАЛЕНО

| Файл/артефакт | Что удалено | Чем заменено |
|---|---|---|
| `app/src/main/res/values/strings.xml` | Персональная атрибуция и две ссылки на личные GitHub-проекты в `about_code_sources` | Нейтральный текст о лицензии MIT и файле `LICENSE` |
| `app/src/main/kotlin/.../ui/screens/Screens.kt` | Персональная строка внутри секции словаря | Отдельная секция «Программный код» с обезличенной лицензией |
| `legal/THIRD_PARTY_NOTICES.txt` | Старые ссылки на личные проекты и два именных MIT copyright notice | Обезличенный MIT-абзац; полный текст вынесен в `LICENSE` |
| `app/src/main/assets/THIRD_PARTY_NOTICES.txt` | Те же значения в сгенерированной копии | Пересоздан штатной задачей `syncDictionaryAssets` |
| `data/manifest.json` | `levels_source.url` с личным GitHub-профилем | `null`; `levels_source.license` остался `MIT`; миграция `tools/sanitize_manifest_privacy.py` |
| `tools/build_dictionary.py` | Генерация персонального `levels_source.url` | `url: null` и нейтральное `provenance` |
| `README.md`, `docs/RELEASE_RIGHTS_DECISION.md`, `docs/LOCAL_DEFINITIONS.md`, `docs/uiux/SHORT15_BASELINE.md` | Старые URL и локальные пути с именем пользователя | Нейтральные формулировки и `$HOME` |
| `metadata.json` | Персональных полей не было; оставлен как AI Studio-метаданные | Не F-Droid card; в APK не входит |

В release/debug APK после пересборки не найдены строки старой атрибуции, личные
ссылки, имя, e-mail, телефон или домены личных профилей. Поиск выполнялся по
декодированным ресурсам (`aapt2 dump resources`), по всем распакованным assets и
по байтам APK.

## 4. ПРИСУТСТВУЕТ И ЗАКОННО ДОЛЖНО ОСТАТЬСЯ

Это только чужие уведомления и обязательные лицензионные тексты; они не являются
персональными данными владельца.

| Материал | Где | Почему остаётся |
|---|---|---|
| Русский Викисловарь, авторы — участники Викисловаря, CC BY-SA 4.0 | `legal/THIRD_PARTY_NOTICES.txt`, `app/src/main/assets/THIRD_PARTY_NOTICES.txt`, AboutScreen | Атрибуция и ShareAlike для словарных данных |
| Kaikki.org / Wiktextract | notices и AboutScreen | Указание извлечения данных |
| FrequencyWords / Hermit Dave, CC BY-SA 3.0 для данных | notices, `data/manifest.json` | Атрибуция источника частотного сигнала; имя автора третьей стороны обязательно сохраняется |
| AndroidX, Jetpack Compose, Kotlin, kotlinx — Apache-2.0 | `legal/Apache-2.0.txt`, `legal/LIBRARY_NOTICES.txt`, assets APK | Обязательные лицензионные тексты библиотек |
| Перепакованный Protobuf — BSD-3-Clause | `legal/LIBRARY_NOTICES.txt`, assets APK | Обязательное уведомление |
| Собственный код проекта — MIT | корневой `LICENSE`, `assets/LICENSE`, `legal/THIRD_PARTY_NOTICES.txt` | Свободная лицензия нового кода; правообладатель обезличен по решению владельца |
| Исторические уровни v1 — MIT | `data/levels.json`, `manifest.levels_source.license` | Сохранение условия MIT без имени и личных ссылок |

Полные тексты Apache-2.0 и library notices не сокращались. `LIBRARY_NOTICES.txt`,
`RUNTIME_LIBRARIES.json` и `Apache-2.0.txt` в assets совпадают с `legal/`.

## 5. РИСК ПУБЛИЧНОГО GIT

| Риск | Факт | Что не делалось |
|---|---|---|
| Author-идентичности в истории | В 117 коммитах найдены 4 уникальных имени автора (три личных варианта и `dependabot[bot]`); с учётом e-mail — 5 комбинаций. Значения здесь не повторяются. | История не переписывалась, фильтры `filter-repo` не применялись, reflog/pack не чистились. |
| Текущий remote | `origin` указывает на исторический личный GitHub-аккаунт. | Remote и Git-config не менялись. |
| Старые публичные копии | Старые коммиты, APK, forks, зеркала и поисковые кэши могут содержать прежние значения. | Сеть не проверялась; отзыв внешних копий невозможен. |
| Старые build outputs | До пересборки локальные APK содержали старую атрибуцию; новые APK её не содержат. | Внешние копии старых APK не отзываются. |
| Локальные ignored-файлы | `local.properties`, `.gradle` и build intermediates содержат машинный путь пользователя; в Git и APK они не входят. | Не чистились: это локальная среда сборки, а не публикуемый артефакт. |
| Чужой вклад | Локальная история не показывает человеческих author-идентичностей, кроме владельца; `dependabot[bot]` — автоматизация. Решение владельца подтверждает его единственное правообладание на код двух исходных проектов. | Юридическая экспертиза внешних вкладов не проводилась. |

Для будущих публичных коммитов нужен нейтральный проектный аккаунт/URL и
псевдоним; порядок описан отдельно в
[PRIVACY_GIT_PUBLICATION.md](PRIVACY_GIT_PUBLICATION.md). Новый псевдоним не
стирает старые публичные копии.

## 6. НЕ ПРОВЕРЕНО

- Вся сеть интернет: старые forks, зеркала, GitHub-поиск, кэш и прежние релизы.
  Проверялись только новый APK, текущее дерево и отсутствующие F-Droid metadata.
- F-Droid metadata/card: в репозитории нет `fdroiddata`/`fastlane`, поэтому
  `AuthorName`/`AuthorEmail`/`AuthorWebSite` проверить не на чем. При создании
  карточки эти поля не заполнять личными значениями; отдельный проектный контакт
  владелец пока не создавал.
- Release notes: отдельного файла нет; `metadata.json` — AI Studio-метаданные,
  не F-Droid и не APK.
- Ручной запуск на устройстве/эмуляторе и TalkBack: не выполнялся.
- Release-подпись: APK подписан debug-сертификатом `CN=Android Debug`; это
  публикационный блокер, не персональные данные.
- Source-to-APK binding: APK собран из рабочего дерева с незакоммиченными
  изменениями; `META-INF/version-control-info.textproto` указывает на базовый
  HEAD `5ee9ba8…`. После коммита оркестратора нужна пересборка, чтобы маркер
  соответствовал публикуемому исходнику.
- Ручной аудит прав определений и юридическая оценка ShareAlike: не проводились,
  статус LEG-01 не менялся.
- EXIF: специализированного `exiftool` нет; JFIF/PNG-метаданные иконок проверены
  программно, author/EXIF-полей не найдено.

## 7. Доказательства сборки

| Артефакт | SHA-256 | Проверка |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `3580d563370c052623a1d057242ecaf45765f0ac9ccbaf957420b77563cec77c` | `aapt2 dump resources` и byte scan: персональных маркеров нет |
| `app/build/outputs/apk/release/app-release.apk` | `5ac184ee44ce542f3072615ae99d73b703ec7aafdb08fc3b56ad52f8acca677d` | То же; assets/LICENSE совпадает с корневым |
| `LICENSE` / `assets/LICENSE` | `79986d65a0b9bdd4f0517b955a6e4ea6f524f5f1ce369fa8f3dec52beb6b0739` | Полный MIT-текст, обезличенный правообладатель |
| `legal/THIRD_PARTY_NOTICES.txt` / asset | `8dfc3ac08306da7803fa2d7906dbd268e4d76d606d4391839097a026c1e14bfa` | Идентичны; третьи стороны сохранены |
| `data/manifest.json` | `445a9958081fc4788041bbd92687a231403d7ebe04aa3f3551040db575a288c0` | `levels_source.url=null`, `license=MIT`, `source.license=CC-BY-SA-4.0` |

`aapt2 dump permissions` для release APK не показывает разрешений. Сборка
release выполнена с существующим waiver владельца для неаудированных
определений; `publishable=false`, поэтому этот отчёт не разрешает публикацию.

Дополнительно прошли: 443 Python-теста `tools/tests`, 135 тестов `:core`,
`:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`,
`:app:assembleDebugAndroidTest`, `:app:verifyPrivacyPublication`. Кампания
осталась 15 уровней / 49 заданий, словарь и `levels.json` не менялись.

## 8. Автоматическая защита от регрессии

- `tools/check_privacy_publication.py` проверяет строки интерфейса, AboutScreen,
  `LICENSE`, notices, `data/manifest.json`, generated assets и публикационные
  документы. Запуск: `python3 tools/check_privacy_publication.py --root . --require-generated`.
- Gradle-задача `:app:verifyPrivacyPublication` входит в `preBuild` и требует
  свежие generated assets; она падает при возврате персонального маркера или
  расхождении `assets/THIRD_PARTY_NOTICES.txt` с `legal/`.
- `tools/tests/test_privacy_publication.py` содержит 8 тестов, включая проверку
  policy, AboutScreen, manifest, миграции metadata и сохранности третьих уведомлений.
- Тест не публикует персональные значения: в policy хранятся только SHA-256.

## 9. Ограничение вывода

`PERSONAL_DATA_APK_UI_STATUS: PASS` относится к новым APK и текущему дереву.
`PERSONAL_DATA_GIT_HISTORY: RISK / NOT ANONYMOUS`. Публикация приложения
по-прежнему требует решения LEG-01 по определениям, release-ключа,
`applicationId`, F-Droid metadata и ручного smoke-test. Персональные данные в
новом APK не обнаружены; оснований для статуса
`PERSONAL_DATA_PUBLICATION_BLOCKED` по пункту PRIV-01 нет.


## 10. PRIV-17 — обновление политики и описаний магазина

Дата подготовки: **2026-11-23**. Эта запись дополняет исторический аудит PRIV-01,
не переоценивая его APK, хэши и результаты проверок.

Изменения политики для проверки владельцем (одинаковы в русской и английской частях):

- В перечне локальных данных исключён удалённый TIME-05 показатель; добавлены
  сохраняемые достижения.
- Абзац о резервном копировании заменён: облачное копирование отключено;
  на Android 12 и выше возможен перенос прогресса напрямую между телефонами
  системной функцией; на Android 11 и ниже перенос не поддерживается.
  Разработчик не управляет системными механизмами переноса и не получает данные.
- Остальные разделы и дата вступления политики в силу сохранены. Дату новой
  редакции при публикации определяет владелец.

Описания магазина ru-RU и en-US приведены к тому же перечню: найденные слова,
прогресс, звёзды, достижения и подсказки. Облачное копирование не обещается.
Отдельных публикационных копий политики и описаний в текущем репозитории нет;
исходники для публикации перечислены ниже. Лицензионные копии в assets и их
исходники не меняются; их соответствие проверяет `verifyPrivacyPublication`.

SHA-256 подготовленных текстов (байты UTF-8):

| Исходник для публикации | SHA-256 |
|---|---|
| `docs/PRIVACY_POLICY.md` | `5de7904aa77960772fcecd2b2441a87a880b6b88f41343c690d9b769314f90d9` |
| `fastlane/metadata/android/ru-RU/full_description.txt` | `99ba234d9ac8860df26dde965bb33a6f7151f553f4c199802718970418c33abd` |
| `fastlane/metadata/android/en-US/full_description.txt` | `2ebfc533c43db82903f8f8cfe3c096c3c1aac8cb4d7537420e5e99da36accb77` |

Проверки PRIV-17: `bash gradlew :app:verifyPrivacyPublication --console=plain`,
`python3 tools/check_privacy_publication.py --root . --require-generated`,
`python3 tools/check_docs.py`,
`python3 -m unittest tools.tests.test_spec_campaign_numbers -v` и
`git diff --check`; результаты приведены в итоговом ответе задания.
Gates `full` и `docs` выполняет внешний оркестратор.

**Статус: подготовлено для проверки и утверждения владельцем.** Публикация
страницы не выполнялась. Опубликованную страницу владелец обновляет сам
после утверждения текста; соответствие внешней страницы этим хэшам не проверено.
Коммит, push и изменение Git-истории в PRIV-17 не выполняются.

## 11. STAB-05 — актуальные SHA описаний после REL-23

Дата проверки: **2026-11-24**. Эта запись отделена от исторической записи
PRIV-17: её SHA сохраняются без изменений и больше не выдаются за актуальные
для файлов, изменённых в REL-23.

REL-23 изменил оба `full_description.txt` после подготовки PRIV-17. Текущие
файлы снова сопоставлены с `docs/PRIVACY_POLICY.md`: они описывают локальное
хранение игровых данных и не обещают облачное копирование; политика также
фиксирует прямой перенос на Android 12+ и отсутствие переноса на Android 11 и
ниже. Сверка выполнена локальным privacy-проверяющим проекта; внешняя страница
политики не открывалась и её перепубликация не утверждается.

| Текущий исходник | Актуальный SHA-256 UTF-8 | Исторический SHA PRIV-17 |
|---|---|---|
| `fastlane/metadata/android/ru-RU/full_description.txt` | `27726df29d297c30326f85050af84e4bebf708bb6ded5c204b7f5876e8a6800e` | `99ba234d9ac8860df26dde965bb33a6f7151f553f4c199802718970418c33abd` |
| `fastlane/metadata/android/en-US/full_description.txt` | `faf2e3f79b5fdc93546ea9579056c6e656aaaaf1abf5d7d13d98c03a3319c380` | `2ebfc533c43db82903f8f8cfe3c096c3c1aac8cb4d7537420e5e99da36accb77` |

Исторические результаты PRIV-17, включая его статус подготовки владельцем,
не переоцениваются. Публикационная страница и магазин после REL-23 отдельно
не проверялись.
