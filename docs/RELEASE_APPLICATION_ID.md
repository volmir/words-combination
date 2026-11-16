# Android applicationId — APPID-01

Источник решения: явное поручение владельца APPID-01 от 2026-11-16 для
публичного проекта words-combination, до публикационной сборки и LEG-02/FINAL15-01.

| Параметр | Значение |
| --- | --- |
| `defaultConfig.applicationId` | `io.github.volmir.wordscombination` |
| Android/Kotlin `namespace` | `com.example.sostavslova` (сохранён) |
| Прежний applicationId | `com.aistudio.wordsgame.kymxpv` |
| `versionCode` / `versionName` | `1` / `1.0.0` (сохранены) |

Источник конфигурации — `app/build.gradle.kts`. В нём изменена только строка
`defaultConfig.applicationId`; перенос пакетов Kotlin не выполнялся.
Окончательная публикационная подпись и F-Droid-рецепт будут решены в FINAL15-01.
Регистрация или глобальная уникальность нового ID во внешних каталогах
не проверялась и не утверждается. Эта задача не разрешает публикацию.

## Установка и сохранения

Android считает пакет `io.github.volmir.wordscombination` новым приложением.
Он не обновит поверх себя прежнюю установку `com.aistudio.wordsgame.kymxpv`:
две установки имеют разные приватные каталоги. Приватный игровой прогресс
прежней установки автоматически **не переносится**. Миграция, экспорт/импорт,
сброс или изменение формата сохранений в APPID-01 не добавлялись.

## Проверка употреблений и регрессии

- В действующей конфигурации, исходниках, манифестах, тестах и скриптах старый
  ID найден только в `defaultConfig.applicationId`; эта строка заменена.
- Зависимых от старого ID authorities, manifest placeholders, `testApplicationId`,
  package visibility и абсолютных приватных путей не найдено. Отдельного
  F-Droid metadata/рецепта в проекте нет.
- Внутренняя Compose-ссылка `sostavslova://level/{index}` от ID не зависит.
  `WordsApplication`, `MainActivity` и каталог компонентов остаются в прежнем
  namespace; доступ к assets и БД идёт через контекст приложения.
- Исторические отчёты, включая LCH-04 и сведения о ранее проверенных APK,
  сохранены. Команды со старым ID в прежних QA-документах относятся к прежнему
  пакету; для новой сборки в `adb` следует использовать новый ID и полное имя
  Activity `com.example.sostavslova.MainActivity`.
- `tools/tests/test_public_package_audit.py` проверяет утверждённый
  `defaultConfig.applicationId` и сохранённый namespace в полном Python gate.
  Отрицательная проверка с подстановкой старого ID в памяти действительно
  вызывает отказ; файлы для неё не изменялись.
- В существующий `AppContainerTest` добавлено утверждение о фактическом
  `application.packageName`; этот тест также проверяет загрузку кампании и слов.

## Проверки сборки

Проверено 2026-11-16:

| Команда / проверка | Результат |
| --- | --- |
| `python3 -m unittest tools.tests.test_public_package_audit` | 5 тестов, OK; отрицательная подстановка прежнего ID также отклонена |
| `bash orchestrator/run.sh --check full` | Успех: 472 Python-теста, 90 тестов оркестратора, `:core:test`, `:app:assembleDebug` |
| `bash ./gradlew :app:compileDebugAndroidTestKotlin --console=plain` | BUILD SUCCESSFUL |
| `bash ./gradlew :app:compileDebugAndroidTestKotlin :app:assembleRelease --console=plain` | Отказ на `:app:verifyDefinitionsAudit`: тексты и лицензия собственных подсказок не утверждены владельцем; поэтому компиляция androidTest затем проверена отдельной командой |
| SDK `platform-tools/adb devices -l` | Список устройств пуст |
| `git diff --check` | Без ошибок |

Новый артефакт: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `16bdda86eb235cd3b632069f544bcf6ce7e283e1aaa86c943668df497014a456`.
Проверен SDK Build Tools 36.0.0:

```sh
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml
```

Фактический `package=io.github.volmir.wordscombination`, `versionCode=1`,
`versionName=1.0.0`, minSdk 24, targetSdk 36. Версии сопоставлены с прежним
локальным debug APK; `uses-permission` отсутствуют в обеих сборках.
В итоговом манифесте нет прежнего ID; Application —
`com.example.sostavslova.WordsApplication`, launcher —
`com.example.sostavslova.MainActivity`. Автоматическая authority библиотеки
AndroidX Startup — `io.github.volmir.wordscombination.androidx-startup`.
Манифест androidTest имеет пакет `io.github.volmir.wordscombination.test`
и `targetPackage=io.github.volmir.wordscombination`.

Проверка ZIP прошла; SHA-256 всех 11 файлов assets побайтно совпали с прежним
APK. Это подтверждает сохранность упаковки и содержимого assets, но не заменяет
их загрузку на устройстве. Имена компонентов и доступ через Context проверены
статически; фактическая установка, запуск, чтение assets и выполнение
инструментальных тестов — **NOT RUN**, устройства/эмулятора нет.

Проверки прав не ослаблялись, `audit.passed`, `rights_review.passed` и waiver
не менялись. Release не собран из-за действующего аудита; публикация, создание
тега и Git-операции записи не выполнялись. Данные, исторические отчёты,
namespace, подпись и защищённые пути не изменены.
