# FDROID-SUBMISSION-01: подготовка заявки на включение в F-Droid

**Состояние на 18.11.2026: STATIC CANDIDATE PASS / EXTERNAL BUILD NOT VERIFIED /
INCLUSION NOT YET CONFIRMED.** Никакая заявка в `fdroid/fdroiddata` не отправлена.
Этот отчёт не является сертификатом F-Droid и не меняет опубликованный выпуск.

## Уже выполнено владельцем

18 ноября владелец подтвердил реальный выпуск `1.0.2` на GitHub
(адрес репозитория указан в YAML-кандидате),
проверки `:app:prepareGithubRelease` и `sha256sum -c SHA256SUMS.txt`.

| Параметр | Зафиксированное значение |
| --- | --- |
| Application ID | `io.github.volmir.wordscombination` |
| Version | `1.0.2`, versionCode `3` |
| Аннотированный тег | `1.0.2` |
| Commit | `2ce9687b2468eded6dc32f5841c755a256e6e9dd` |
| Release APK | `WordsCombination-v1.0.2.apk` (точная ссылка в `Binaries` YAML-кандидата) |
| APK SHA-256 по проверке владельца | `4dc963dde4275c3179a24bffed03522cbec52cd3df2584d80bc9cf48aa6cc7ae` |
| SHA-256 публичного сертификата | `a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353` |
| Локальное сравнение | `LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED` (`apksigcopier compare --unsigned`) |

Локальные проверки проводились владельцем. В этой рабочей среде не выполнялось
независимое скачивание APK с GitHub, сравнение опубликованных байтов, запуск
`fdroid build --server` или buildserver F-Droid. Приватный ключ не запрашивался.

## Кандидат рецепта для fdroiddata

[`docs/fdroiddata/metadata/io.github.volmir.wordscombination.yml`](fdroiddata/metadata/io.github.volmir.wordscombination.yml)
— копия **для ревью**, а не F-Droid metadata, которая уже находится в каталоге.
При подготовке GitLab Merge Request файл копируется в репозиторий `fdroiddata`
по пути `metadata/io.github.volmir.wordscombination.yml`.

Основные решения:

1. `RepoType: git`, открытый GitHub-репозиторий, точный commit тега `1.0.2`.
2. `gradle: yes`, модуль `app`, ожидаемый неподписанный APK в штатном
   `app/build/outputs/apk/release/app-release-unsigned.apk`.
3. `Binaries` с версионированным `WordsCombination-v%v.apk` и
   `AllowedAPKSigningKeys` с **отпечатком сертификата**, а не закрытым ключом.
4. `scandelete` перечисляет ровно четыре архивных ZIP из `docs/qa/pre04-17/`.
   Они не используются в сборке, будут удалены **только из временного исходного
   дерева F-Droid**, а не из публичного репозитория. Их влияние на scanner
   пока не проверено; другие исключения добавлять нельзя без доказательств.
5. `UpdateCheckMode: Tags` и `UpdateCheckData` читают версию из
   `app/build.gradle.kts`. `AutoUpdateMode: None` намеренно оставляет будущие
   сборки на ручном контроле до успешной приёмки первого выпуска.

Файлы `fastlane/metadata/android/en-US` и `ru-RU` доступны уже в теге
`1.0.2`: название, краткое/полное описание, иконка и скриншоты. Changelog для
`versionCode=3` в самом теге отсутствует; ссылка `Changelog` ведёт на
опубликованные GitHub Releases. Это следует оценить при ревью metadata.

## Проверки и незакрытые вопросы

Локальная статическая команда:

```bash
python3 tools/check_fdroid_submission_candidate.py
python3 -m unittest tools.tests.test_fdroid_submission_candidate -v
```

Статическая проверка подтверждает согласованность YAML-кандидата с Git-тегом,
версией Gradle, лицензией MIT и расположением Fastlane. Она **не** проверяет
подлинность удалённого APK, корректность всего fdroiddata-схемы, лицензирование
каждой зависимости или воспроизводимость в F-Droid buildserver.

До отправки заявки необходимы:

- `fdroid lint` / `fdroid rewritemeta` и проверка схемы **в клоне fdroiddata**;
- `fdroid build` с обязательным scanner на соответствующем toolchain (JDK 21,
  AGP `9.1.1`, Gradle `9.3.1`, Android SDK 36); использовать стандартные
  инструменты F-Droid, не убирать реальные ошибки через `scanignore`;
- проверка, что опубликованный developer-signed APK **совпадает** с выпуском
  F-Droid при копировании подписи. Особый риск: Android Gradle Plugin может
  включать абсолютный путь к Git-клону в `META-INF/version-control-info.textproto`.
  Локальный гейт применял два разных клона по **одному абсолютному пути**;
  buildserver F-Droid использует другой путь. Если байты расходятся, нельзя
  обойти проверку или изменить уже опубликованный `1.0.2`; потребуется
  согласовать исправление и следующий релиз;
- оценка оставшихся лицензионных/политических замечаний поддерживающими
  F-Droid; локальный PASS в `docs/LEGAL_RELEASE_AUDIT.md` не заменяет её.

### Ограничение по сроку активной разработки

[F-Droid Inclusion Policy](https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Inclusion_Policy/)
обычно требует не менее **трёх месяцев активной разработки**, если нет
подтверждённых пользователей, опыта сопровождения других приложений уже
в F-Droid или событийного исключения. Владелец сообщил, что активная работа
началась примерно в октябре 2026 года; старое создание GitHub-репозитория
само по себе не доказывает трёхмесячную активную разработку. Пользователей
пока нет, другое приложение находится на рассмотрении, но ещё не принято.

**Решение: не заявлять соответствие этому критерию и не отправлять заявку
как полностью готовую до подтверждения основания для включения.**
Подготовка metadata и технических тестов при этом разрешена.

## Порядок завершения

1. Ревью и слияние PR с документами и статической проверкой.
2. Создание отдельной ветки/форка `fdroiddata`, копирование YAML-кандидата.
3. `fdroid lint`, `fdroid rewritemeta`, `fdroid build` и анализ возможных
   несовпадений; зафиксировать честные результаты без подмены статусов.
4. После подтверждения политики допуска и явного разрешения владельца —
   Merge Request в `fdroid/fdroiddata`; обсуждать возможные замечания.
5. Только принятая upstream metadata и проверка со стороны F-Droid дают право
   говорить о включении игры в официальный каталог.

Официальные первичные источники:

- [Submitting to F-Droid: Quick Start](https://fdroid.gitlab.io/jekyll-fdroid/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Reproducible Builds](https://fdroid.gitlab.io/jekyll-fdroid/docs/Reproducible_Builds/)
- [Build Metadata Reference](https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/)
- [Inclusion Policy](https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Inclusion_Policy/)
