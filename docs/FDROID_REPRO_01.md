# FDROID-REPRO-01: независимая проверка developer-signed APK

**Состояние на 18.11.2026: HISTORICAL 1.0.1 FAIL / CLEAN LOCAL PAIR PASS.**
Владелец выполнил реальную проверку: опубликованный ранее GitHub APK `1.0.1`
не совпал с чистой неподписанной сборкой по `classes.dex` и `baseline.prof`.
При этом две чистые неподписанные сборки совпали, а новая подписанная и
неподписанная пара из полного Git-клона прошла `apksigcopier compare --unsigned`
с кодом возврата 0. Это результаты, **сообщённые владельцем**, а не проверка
инфраструктуры F-Droid. Для следующего выпуска применяется
[FDROID-REPRO-02](FDROID_REPRO_02.md); это не разрешение публикации.

## Контракт для первого кандидата

| Поле | Закреплённое значение |
|---|---|
| Application ID | `io.github.volmir.wordscombination` |
| Выпуск | Git tag `1.0.1` (аннотированный) |
| Исходный commit тега | `6e1ea080c8e144d05fea00097981e9ada9803ac9` |
| versionName / versionCode | `1.0.1` / `2` |
| Android | JDK 21, AGP `9.1.1`, Gradle `9.3.1`, compileSdk `36` |
| Подписанный APK | `releases/download/1.0.1/WordsCombination-v1.0.1.apk` в официальном GitHub-репозитории |
| SHA-256 сертификата подписи владельца | `a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353` |

Отпечаток сертификата публичен; **закрытый ключ и пароли не нужны** для
проверки. Нельзя выводить их в терминал, собирать в CI или передавать F-Droid.

Версии `1.0.0` и `1.0.1`, существующие теги и GitHub Release остаются неизменными.

## Проверка на машине владельца (без устройства Android)

Требуются `git`, `gh`, JDK 21, работающий Android SDK и Gradle Wrapper;
дополнительно — [apksigcopier](https://fdroid.gitlab.io/fdroidserver/fdroidserver.apksigcopier.html)
и Android SDK `apksigner`. `apksigcopier` должен быть доступен в `PATH`.
Инструмент найдёт `apksigner` через `ANDROID_HOME` / `ANDROID_SDK_ROOT`.

Этот исторический пример проверки `1.0.1` приводится для диагностики, **не как
рекомендация к публикации**: опубликованный APK не воспроизводится. Используется
**полноценный временный Git-клон**, поскольку при проверке `git worktree`
Android Gradle Plugin записывал `generate_error_reason: NO_VALID_GIT_FOUND`.
Локальные изменения и текущая `main` не затрагиваются. Отсутствующий
`keystore.properties` и удалённые из окружения переменные подписи гарантируют,
что в этом клоне создаётся именно неподписанный APK.

```bash
cd ~/www/words-combination
SOURCE_REPO_ROOT="$(pwd -P)"
REPRO_DIR="$(mktemp -d)"

git fetch --tags origin
test "$(git rev-parse '1.0.1^{commit}')" = '6e1ea080c8e144d05fea00097981e9ada9803ac9'
git clone --no-hardlinks --no-checkout "$SOURCE_REPO_ROOT" "$REPRO_DIR/source"
git -C "$REPRO_DIR/source" checkout --detach 1.0.1
mkdir -p "$REPRO_DIR/reference"

gh release download 1.0.1 \
  --dir "$REPRO_DIR/reference" \
  --pattern 'WordsCombination-v1.0.1.apk' \
  --pattern 'SHA256SUMS.txt'

cd "$REPRO_DIR/source"
env -u SOSTAVSLOVA_RELEASE_STORE_FILE \
    -u SOSTAVSLOVA_RELEASE_STORE_PASSWORD \
    -u SOSTAVSLOVA_RELEASE_KEY_ALIAS \
    -u SOSTAVSLOVA_RELEASE_KEY_PASSWORD \
    bash ./gradlew :app:assembleRelease --no-build-cache --console=plain

test -f app/build/outputs/apk/release/app-release-unsigned.apk

cd "$SOURCE_REPO_ROOT"
python3 tools/verify_reproducible_release.py \
  --signed "$REPRO_DIR/reference/WordsCombination-v1.0.1.apk" \
  --unsigned "$REPRO_DIR/source/app/build/outputs/apk/release/app-release-unsigned.apk" \
  --sha256sums "$REPRO_DIR/reference/SHA256SUMS.txt" \
  --output "$REPRO_DIR/reproducibility-evidence.json"
```

Последняя команда завершится с кодом `0` и выведет
`LOCAL_SOURCE_TO_SIGNED_APK_VERIFIED` **только если**:

1. SHA-256 опубликованного APK совпадает с опубликованным `SHA256SUMS.txt`;
2. Подпись APK действительна, использует ожидаемый публичный сертификат и v2;
3. Второй APK действительно неподписан;
4. `apksigcopier compare --unsigned` смог перенести подпись из эталона и
   проверить её на независимо собранном APK.

Сравнение не использует приватный ключ. Для опубликованного `1.0.1` реальная
проверка завершилась ошибкой; нельзя скрывать её, менять эталонный выпуск или
создавать фиктивный PASS. Для диагностики причин могут помочь
[рекомендации F-Droid по diffoscope](https://fdroid.gitlab.io/jekyll-fdroid/docs/Reproducible_Builds/).

`REPRO_DIR` намеренно сохраняется после проверки для изучения отчёта и
результатов. Удалять временный клон можно отдельно после сохранения необходимых
свидетельств. Не добавлять бинарные результаты в Git.

## Черновик инструкции для fdroiddata (НЕ отправлен)

После успешного локального сравнения подготовить build-рецепт в отдельном
репозитории `fdroid/fdroiddata`. Критичные поля для схемы только с подписью
разработчика:

```yaml
RepoType: git
Repo: https://github.com/OWNER/words-combination.git
Binaries: https://github.com/OWNER/words-combination/releases/download/%v/WordsCombination-v%v.apk
AllowedAPKSigningKeys: a10cdd4ff4000673f14db9fe4a814f8bbd63b6e2007f5f38b1fe5cfb7310f353
Builds:
  - versionName: 1.0.1
    versionCode: 2
    commit: 6e1ea080c8e144d05fea00097981e9ada9803ac9
    gradle:
      - yes
```

Это **не полный проверенный рецепт**, а параметры для дальнейшей ручной
подготовки `fdroiddata`: нужны scanner, проверка доступности toolchain и
лицензий, полный YAML с метаданными, а затем независимая проверка F-Droid.
Замените `OWNER` на публичный идентификатор владельца GitHub-репозитория
при подготовке рецепта; этот черновик намеренно обезличен.
Кандидат `1.0.0` здесь намеренно не описан: у него другое имя APK на GitHub,
поэтому общий шаблон `Binaries` к нему не подходит.

Ссылки: [F-Droid Reproducible Builds](https://fdroid.gitlab.io/jekyll-fdroid/docs/Reproducible_Builds/),
[Build Metadata Reference](https://fdroid.gitlab.io/jekyll-fdroid/docs/Build_Metadata_Reference/).

## Границы завершения

- **PREPARED**: скрипт и его синтетические тесты прошли.
- **LOCAL PROCESS PASS**: владелец подтвердил успешную проверку новой подписанной
  и неподписанной пары из чистого клона; опубликованный GitHub `1.0.1` остаётся FAIL.
- **F-DROID PASS**: только после успешного build-рецепта, сравнительного
  контроля на стороне F-Droid и решения сопровождающих.

Ручная функциональная приёмка на смартфоне остаётся отдельным ранее
выполненным этапом и здесь не повторяется.
