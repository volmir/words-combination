# LEGAL-GRAPHICS-02 — замена графики и проверка поставки

Текущая ветка `main`, HEAD `1b0becffeec470ef22c9d39b13bf2f4911a3fb79`. При старте были только три незакоммиченных отчёта: `docs/LEGAL_RELEASE_AUDIT.md`, `docs/GRAPHICS_PROVENANCE_AUDIT.md`, `docs/audits/XML_ICON_PROVENANCE.json`. Их содержимое сохранено как историческое свидетельство; новая геометрия не приписывается старым файлам.

## Девять векторов

Официальный источник: Google Material Icons / Material Symbols, commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`, Apache-2.0, Copyright Google LLC. Корона — настоящий Material Symbols Outlined `crown_24px.svg`, viewport исходника `0 -960 960 960`: точное преобразование `scale(0.025)`, `translateY=24`. Остальные семь замен берут Filled 24px пути без перерисовки; bar_chart rect переведены в эквивалентные замкнутые пути. Цвета, dp-размеры и tint-контейнеры сохранены. Menu побайтно сохранено. Невидимые bounds fill=none не перенесены.

| Файл в `app/src/main/res/drawable/` | До SHA-256 | После SHA-256 | Официальный неизменяемый источник | SHA-256 upstream SVG | Лицензия / исходники |
|---|---|---|---|---|---|
| `ic_arrow_back.xml` | `e77bd486a881eb13d89cd932580695995188c933f5a6b340f2ac13f333f29914` | `70088371bd8aa39db1dad8e0f0b96da65d4705545a5531afba3594efdfa8fa86` | [src/navigation/arrow_back/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/navigation/arrow_back/materialicons/24px.svg) | `e1590e051f577d02ec994e7cc6005a2bc96407a3d1ba2d7ce6825fb80402684c` | Apache-2.0 / PASS |
| `ic_close.xml` | `befb8e9999248a4753ac23a545a902bd78ea795e96639fe8eebd5ccc3b07a42e` | `90f3e2c5d563434f323cda718288ca04af4c34ab95693bececb3d23e35a51f96` | [src/navigation/close/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/navigation/close/materialicons/24px.svg) | `b54342456d5a7f2da53795147f8af36ec76fbf5b57d792fe75f07538e6c6783e` | Apache-2.0 / PASS |
| `ic_crown.xml` | `c843d97bef3b8dcf1794a63011a2ab504b8005856a8778d8a3b98cc5ea80cb92` | `1d8ba2149425537b5c5a7de0b40e33cfaf20b2b9209fbddee1fa6e368e1d39eb` | [symbols/web/crown/materialsymbolsoutlined/crown_24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/crown/materialsymbolsoutlined/crown_24px.svg) | `0f73eb7347f1e4d94f5cae617c5dd72c46b1fd97f2548c08fdd305cb7b30d992` | Apache-2.0 / PASS |
| `ic_help.xml` | `daee62f4123245acd5090df976b2ae3d3a9149dc22a67012733dafdf35da5b59` | `c8c435eeeb9f1f5eee5c4d9d33d8dd0a729da3856fd7dc390d04f7c85a5b5bf2` | [src/action/help/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/action/help/materialicons/24px.svg) | `130d5d3eaa72de2905720c0aeedac65f688eb224188b04134f2ae998dd85d0e6` | Apache-2.0 / PASS |
| `ic_lock.xml` | `21112ba985f447fa34bbde3d05f828eaa5e689dbba38116b0d2970a4930b8b9b` | `824b92ace1ce1428fcd797d88d335fd2b2c337f468fc0e6ba524efd51d401f26` | [src/action/lock/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/action/lock/materialicons/24px.svg) | `33b9bc04d9e69c5f4eb8ea5d49b240c4fdc1da973c7571235cbca1fa6f80394d` | Apache-2.0 / PASS |
| `ic_menu.xml` | `020f03900ae32fdffc8433c3552297c98e384f113770835e4fb835b2f1847497` | `020f03900ae32fdffc8433c3552297c98e384f113770835e4fb835b2f1847497` | [src/navigation/menu/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/navigation/menu/materialicons/24px.svg) | `5222c9f8a54b1023571595636174fa96d136a3c245d80168702888151193762f` | Apache-2.0 / PASS |
| `ic_star_filled.xml` | `e6b15505f6d9ea9fb2a6b340b10b9dbd3ae1a8eae3a2a37561e8167f95dbae0a` | `6bed431ee09369be2b5682428371819192a02eceaeef63e0bf8478428a521f72` | [src/toggle/star/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/toggle/star/materialicons/24px.svg) | `b476baf36b66c81802396e090b842d7a84269f1f8aff197af6862ec890441c75` | Apache-2.0 / PASS |
| `ic_star_outline.xml` | `a1312028e049d2ae79b8305073b5d4728dfcdea78a8cb11509ca8150dbbdf8c8` | `89048b6ec365b867fa3660c63500138a0ad64498cf64f621859123b1dc618da8` | [src/toggle/star_border/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/toggle/star_border/materialicons/24px.svg) | `e963c6216056acb1579d6a0f6121791ed9254dc25a3cd621ff5bf5bfab0d315c` | Apache-2.0 / PASS |
| `ic_statistics.xml` | `288eac3d7151e3df6e696f20bf210cca934656e339b0b5d9c927cb71e9e45b80` | `6dddfc06ce526b38cd86eef472041688623ebac62fab9d05d548e332eb614e3b` | [src/editor/bar_chart/materialicons/24px.svg](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/src/editor/bar_chart/materialicons/24px.svg) | `5abe3390a29d11d9a3e2e212224d5196272a9072ae8bb76c963bf18fee4d5fe1` | Apache-2.0 / PASS |

## Растры и решение владельца

Владелец подтвердил создание растров с нуля в Google AI Studio, без загруженных сторонних изображений, и отдельно явно согласовал CC0-1.0 для перечисленных 11 PNG и их производных **в пределах принадлежащих ему прав**. Решение с хэшами — [RASTER_GRAPHICS_OWNER_APPROVAL.json](RASTER_GRAPHICS_OWNER_APPROVAL.json). Это не заявление об исключительных правах и не гарантия отсутствия любых прав третьих лиц. Сами 11 PNG не изменялись.

Применимые первичные [Gemini API Additional Terms / Google AI Studio](https://ai.google.dev/gemini-api/terms), редакция с 2026-03-23, проверены 2026-11-18: Google не заявляет права собственности на generated content, допускает одинаковые результаты у других пользователей, ответственность за использование/необходимые атрибуции остаётся у пользователя. [Google APIs Terms](https://developers.google.com/terms), §§2(b),5, требуют соблюдения прав третьих лиц. Из этого сделан ограниченный вывод: запрет на свободную передачу собственных прав на эти изображения не выявлен; одобрение CC0 владельцем не превращается в гарантию Google. Офлайн-приложение не является API-клиентом сервиса генерации и не подключает AI Studio.

| Оставляемый путь | Размер / пиксели | SHA-256 | Назначение / сборочные ссылки | Лицензия |
|---|---|---|---|---|
| `app/src/main/res/drawable/ic_launcher_monogram_art.png` | 28656 bytes; 512×512 | `7e6a50eea320bb4ba73593ef20289092c7f1fea4a88b3630f991466eede7160e` | adaptive launcher foreground art; app/src/main/res/drawable/ic_launcher_foreground.xml | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-hdpi/ic_launcher.png` | 2412 bytes; 72×72 | `f7d1006553a1010a4ddbb63455f33f708c6399f0ccffe7e843b7be06e845ff90` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-hdpi/ic_launcher_round.png` | 3143 bytes; 72×72 | `5fe1ecf1b95a22b51c394f2ab66794ede0802785ad26f51cd0b3e5a93297ff0b` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-mdpi/ic_launcher.png` | 1582 bytes; 48×48 | `dcc39ed8010c03d02e70f021317b357025dc9e0f89cd1adb9cb8deb93a0d8630` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-mdpi/ic_launcher_round.png` | 2102 bytes; 48×48 | `7b73416c1c381c45e15b93782606aa475cf7ddf1c96df293e7aa66b72829a886` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-xhdpi/ic_launcher.png` | 3279 bytes; 96×96 | `91f2d0ff04c7eb6a03c434e6f32324f67e94e3615cafc74ac0f872393cd4d610` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-xhdpi/ic_launcher_round.png` | 4359 bytes; 96×96 | `5e25e1189506d72a176b2f15663b427f46b68747929b4964714c163b3eae068c` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` | 5144 bytes; 144×144 | `390eb0b1719ee5ca97cbe22bb803b5399d1dfd35ee04e41c69a40bc84050e0c2` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png` | 7009 bytes; 144×144 | `1ee3ca6da61e11604aa8f6d9d781c70f731115cbf3b385e38962bbb07936228b` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` | 7036 bytes; 192×192 | `cf6e88861f98e41737853b7ba9cab52a625bfd6b8dc2d138172548279a7a2f43` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |
| `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png` | 9659 bytes; 192×192 | `73e5ffc79316bec24b269c1f3b0977933caf118545f3ca71d04586c858e2aa72` | legacy launcher density variant; app/src/main/AndroidManifest.xml android:icon/android:roundIcon; density fallback below adaptive v26 | CC0-1.0, owner-approved |

Все 11 зарегистрированы в [RASTER_GRAPHICS_PROVENANCE.json](audits/RASTER_GRAPHICS_PROVENANCE.json). Одноцветный XML background и adaptive/layer-list wrappers — кодовые оболочки, их ресурсные ссылки сохранены. Два adaptive launcher XML ссылаются на background/foreground, foreground — на monogram; десять mipmap PNG покрывают старые версии/density fallback.

### Удалён только неиспользуемый JPG

`app/src/main/res/drawable/words_app_icon_1793804687264.jpg`, 437466 bytes, SHA-256 `4713d12eb988b96ed6624496e00c70ad7ba31576642687f86fa3ba40dd34f0c5`.

Проверены все текстовые файлы проекта, Kotlin/Compose `R.drawable`/`R.mipmap`, XML/manifest, Gradle/sourceSets, генераторы, тесты, скрипты и динамический `getIdentifier`: активных ссылок на JPG нет. Merge включал его как объявленный drawable, но release `resources.txt:414` помечал `is not reachable`; в прежнем release APK байтов JPG нет. В прежнем debug APK он присутствовал как неиспользуемый ресурс. В историческом AAB байтов JPG нет, но имя ещё было в protobuf resource table. Это не runtime-вызов и не доказательство использования картинки. После удаления новый APK проверяется по resource table, а не только unzip. Старые аудитные упоминания и хэш сохранены; они не являются генератором или обязательной зависимостью изображения. Другие PNG/JPG не удалялись.

## Изменённые области

- Восемь существующих VectorDrawable; menu и 11 PNG сохранены побайтно; удалён один разрешённый JPG.
- `legal/THIRD_PARTY_NOTICES.txt`: отдельные Google Material Icons/Symbols, pinned source, conversion, CC0 и ограничение прав. MIT, CC BY-SA, T4 CC0, FrequencyWords и notices библиотек сохранены. Apache-2.0.txt не менялся.
- About: только новый юридический раздел из strings.xml с tag `about_graphics_license`; навигация, события и геймплей не менялись.
- Реестры и отчёты: старый XML-реестр вложен в `historical_audits`, сохранены прежние SHA/UNKNOWN; новый snapshot — `vectors`, 9/9 Apache.
- `tools/verify_graphics.py`: детерминированная офлайн-проверка registry/SHA/conversion/одобрения/полноты; включена через `verifyGraphics` в `verifyDictionaryLicenses` → preBuild. Опциональный APK-аудит проверяет реально упакованные paths/notices/resource table и инвентаризирует все растры.
- `tools/tests/test_graphics.py`: синтетические проверки подмены геометрии/одобрения/новых изображений/удалённого JPG/смены меню; `GraphicsResourcesTest.kt`: render девяти векторов, отверстие контурной звезды, integration notices.

## Проверки и артефакты

| Команда / проверка | Фактический результат |
|---|---|
| `python3 tools/verify_graphics.py` / `:app:verifyGraphics` | PASS: 9 Apache векторов, 11 PNG с точным одобрением, исключённый JPG отсутствует |
| Финальный повтор `bash gradlew --offline :app:verifyGraphics :app:verifyRuntimeNotices :app:verifyDictionaryLicenses :app:verifyOwnedHints :app:verifyPrivacyPublication --console=plain` после обновления всех реестров/документов | PASS: BUILD SUCCESSFUL, 7s, 7 задач (6 executed / 1 up-to-date) |
| `bash gradlew --offline :app:verifyRuntimeNotices :app:verifyDictionaryLicenses :app:verifyOwnedHints :app:verifyPrivacyPublication :app:lintDebug :core:test :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin --console=plain` | PASS: BUILD SUCCESSFUL, 2m 48s, 119 задач (55 executed / 64 up-to-date). :core:test UP-TO-DATE; сохранённые результаты: 161 tests / 0 failures. Lint: 0 errors, 14 warnings. Kotlin/androidTest compile PASS |
| `python3 -m unittest discover tools/tests` | PASS: **503 tests**, 119.719s после финального усиления PNG-аудита. Предыдущий прогон до этого изменения: 503 tests / 117.894s, также PASS. |
| `bash gradlew --offline :app:bundleRelease --console=plain` | PASS: BUILD SUCCESSFUL, 10s, 60 задач. Ключ не настроен; `jarsigner -verify` сообщает `jar is unsigned` |
| `python3 tools/verify_graphics.py --apk <debug/release APK> --profile <debug/release> --aapt2 <SDK>/build-tools/36.0.0/aapt2 --output <report>` | PASS для двух новых APK: compiled pathData/paint/transform и notices; 11 проектных PNG exact source bytes либо recompiled AAPT2 bytes |
| Проверка AAB base через временный proto APK и `aapt2 convert --output-format binary` | PASS того же графического gate. Перенесены оригинальные base entries/manifest без signing/установки. Исходный SHA AAB и SHA временного inspection APK в packages registry |
| Полный inventory ZIP и aapt2 resources/xmltree | PASS: 9 векторов в debug; 6 используемых в release/AAB, 3 неиспользуемых пройдены source/debug и удалены shrinker. 11 PNG в каждой поставке; debug имеет ещё 52 PNG AndroidX Core 1.13.1 / Apache-2.0, побайтно воспроизведённые AAPT2 из разрешённого AAR |
| Адресные `GraphicsResourcesTest` и `StarsContrastTest` на телефоне | SKIPPED: `adb devices -l` пуст. Новые тесты скомпилированы; установка/сброс пользовательских данных не выполнялись |
| Host SVG preview всех 9, различие filled/outline, корона/замок/стрелка/statistics | PASS визуальной проверки чистой геометрии; не выдаётся за Android device run |
| `git diff --check`, XML/JSON, SHA инвариантов | PASS; данные и menu/PNG/полный Apache текст не менялись |

Первоначальные запуски gates остановились на персональном пути/ссылке владельца в **ранее подготовленном незакоммиченном** LEGAL_RELEASE_AUDIT.md. После адресного удаления этих значений из документа privacy checker прошёл. Правила/списки исключений privacy не менялись и не отключались. До исправления полного compile/release цикла не было; после исправления выполнен успешный указанный цикл. Повторный Python-прогон после усиления APK PNG-проверки отмечен отдельно, старый PASS не переносится автоматически.

| Артефакт | Bytes | SHA-256 | Растры / результат |
|---|---:|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 12663069 | `1941cb6b29827c09dc91dbc02ffa8ef441eb5118cb81479b360bba543cbe1f7d` | 63 PNG; geometry/paint/transform/notices/provenance PASS; JPG absent |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 2607822 | `8a26c179436676ab768aba74f1cbca39620ecad211ea1675b64689e8aad49e5b` | 11 PNG; geometry/paint/transform/notices/provenance PASS; JPG absent |
| `app/build/outputs/bundle/release/app-release.aab` | 4384508 | `0923de61fff3c58417529ec29ce58ed2a79a1ed214b66df3e309a1b3457f6563` | 11 PNG; geometry/paint/transform/notices/provenance PASS; JPG absent |

[Полный package inventory](audits/LEGAL_GRAPHICS_02_PACKAGES.json) перечисляет каждый упакованный raster: путь (включая короткие release имена), resource name, bytes, pixels, SHA, source AAR/project SHA и способ совпадения. В release/AAB нет других JPEG/WebP/GIF/AVIF. Новые AAB notices совпадают с legal/, прежний устаревший AAB теперь только историческая запись. Неподписанный APK: `apksigner verify` ожидаемо exit 1 (нет подписи); это не готовый developer-signed выпуск.

### Использование ресурсов

| Ресурс / путь ссылки | Использование |
|---|---|
| `WordsTopBar.kt:38,43` | back/menu, включая условный выбор иконки; имена/обработчики не менялись |
| `LevelsScreen.kt:220,234` | crown/lock, прежние 24dp и tint контейнера |
| `StarsRow.kt:71`, `GameScreen.kt:372` | filled/outline по существующему состоянию done; цвет/контрастные константы не менялись |
| close/help/statistics | Нет актуальных вызовов UI; сохранены по задаче, зарегистрированы и проверены в debug/адресном render-тесте; shrinker удаляет из release |
| `StarsContrastTest.kt:41–42`, новый `GraphicsResourcesTest.kt` | Различие геометрии, сохранённый контраст, рендер и notices; connected execution SKIPPED |
| `AndroidManifest.xml:6–7` → mipmap launcher/round | Пять density PNG каждого типа плюс adaptive v26; новые разрешения не добавлялись |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` → background/foreground → `ic_launcher_monogram_art` | Сохранённая цепочка включения PNG, включая adaptive/layer-list/manifest ссылки |
| Gradle main resources, default sourceSets | Включение всех project drawables; `verifyGraphics` и старые hash/license/privacy gates остаются обязательными |
| Генераторы/скрипты/документы | Графический gate/registry — проверочные ссылки, не runtime-загрузчик. Активных генераторов из удалённого JPG и динамического getIdentifier для него нет; исторические аудитные записи сохранены |


## Неизменные данные и границы

| Файл | SHA-256 до | SHA-256 после |
|---|---|---|
| `data/campaign.json` | `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e` | `1734a3338f39ad00b019871b18aef010e8bc738b4dfd830b8668af717b20d76e` |
| `data/owned_riddles.json` | `05e3c05099fe38648834a6b9f70f458cf51238edc7bacdc85d8af3932ad11f63` | `05e3c05099fe38648834a6b9f70f458cf51238edc7bacdc85d8af3932ad11f63` |

PASS: 15 уровней / 6 этапов / 49 заданий, T1–T7, все 11 T4 и источники уровня 12 («ПРОБЛЕМАТИКА»/«проблема») побайтно сохранены. :core не менялся; DataStore, paidUnits, навигация и reset не затронуты.

**Итог L01: PASS — устранён в проверенной текущей поставке** (9 Apache-2.0 векторов, 11 явно утверждённых CC0 PNG, 52 библиотечных debug PNG с подтверждением AAR/Apache-2.0). Это инженерное подтверждение конкретных источников/лицензий и одобрения владельца, без гарантии исключительности или отсутствия неизвестных третьих прав.

Ключи и подпись не менялись; unsigned release — технический артефакт. Developer-signed reproducibility, metadata/recipe F-Droid, privacy policy, срок разработки/исключение и решение сопровождающих остаются отдельными вопросами. PRE04-01/PRE04-17 и очередь/checkpoints оркестратора не менялись. Commit/push/publish/pm clear/uninstall не выполнялись.

## Полный список файлов на проверку

| Файл | Статус / причина |
|---|---|
| `app/build.gradle.kts` | ` M`; Добавлен обязательный офлайн verifyGraphics; подпись/версии/зависимости не менялись |
| `app/src/main/kotlin/com/example/sostavslova/ui/screens/Screens.kt` | ` M`; Юридический раздел About, строки в strings.xml; игровые события не менялись |
| `app/src/main/res/drawable/ic_arrow_back.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_close.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_crown.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_help.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_lock.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_star_filled.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_star_outline.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/ic_statistics.xml` | ` M`; Разрешённая замена геометрии конкретным Google-источником; имя/цвет/dp/viewport сохранены |
| `app/src/main/res/drawable/words_app_icon_1793804687264.jpg` | ` D`; Удалён с разрешения владельца после доказательства неиспользования |
| `app/src/main/res/values/strings.xml` | ` M`; Юридический раздел About, строки в strings.xml; игровые события не менялись |
| `docs/PROJECT_STATUS.md` | ` M`; Текущий отчёт/инвентарь/машинное доказательство происхождения и результатов |
| `legal/THIRD_PARTY_NOTICES.txt` | ` M`; Google AP-2.0 и утверждённая raster CC0; прежние data/library notices сохранены |
| `app/src/androidTest/kotlin/com/example/sostavslova/ui/components/GraphicsResourcesTest.kt` | `??`; Адресные render/outline/notices Android-тесты |
| `docs/GRAPHICS_PROVENANCE_AUDIT.md` | `??`; Существовал незакоммиченным до задачи; аудит актуализирован, прежние выводы/хэши сохранены исторически |
| `docs/LEGAL_GRAPHICS_02_REPORT.md` | `??`; Текущий отчёт/инвентарь/машинное доказательство происхождения и результатов |
| `docs/LEGAL_RELEASE_AUDIT.md` | `??`; Существовал незакоммиченным до задачи; аудит актуализирован, прежние выводы/хэши сохранены исторически |
| `docs/RASTER_GRAPHICS_OWNER_APPROVAL.json` | `??`; Отдельное явное решение владельца, привязанное к SHA 11 PNG |
| `docs/audits/LEGAL_GRAPHICS_02_PACKAGES.json` | `??`; Текущий отчёт/инвентарь/машинное доказательство происхождения и результатов |
| `docs/audits/RASTER_GRAPHICS_PROVENANCE.json` | `??`; Текущий отчёт/инвентарь/машинное доказательство происхождения и результатов |
| `docs/audits/XML_ICON_PROVENANCE.json` | `??`; Существовал незакоммиченным до задачи; аудит актуализирован, прежние выводы/хэши сохранены исторически |
| `tools/tests/test_graphics.py` | `??`; Офлайн graphics/source/APK gate или его синтетические регрессии |
| `tools/verify_graphics.py` | `??`; Офлайн graphics/source/APK gate или его синтетические регрессии |

Решение владельца о будущем собственном ключе developer-signed с возможностью распространения через F-Droid, Google Play и GitHub учтено как отдельная задача. Конфигурации/инфраструктура подписи и сами ключи LEGAL-GRAPHICS-02 не менялись.
