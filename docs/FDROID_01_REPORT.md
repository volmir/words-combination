# FDROID-01: удаление `UpdateCheckData` из metadata заявки !58000

Дата: 2026-11-19. Внешний репозиторий:
`<локальный клон fdroiddata-words-combination-mr58000>`
(ветка `new-app-wordscombination`, HEAD `8b2a9135062c32011565ebf9e957cc1b5baaf530`,
`origin` = `https://gitlab.com/volmir561/fdroiddata.git`,
`upstream` = `https://gitlab.com/fdroid/fdroiddata.git`).

Приложение, релиз 1.0.3, тег, APK/AAB, подпись, `Binaries` и
`AllowedAPKSigningKeys` в рамках этой задачи не изменялись. `data/` не
затрагивался; Android-код правился только в отдельной задаче ARCH-01.

## 1. Замечание ревьювера

Замечание @linsui — note `4273601525` от 2026-11-19T10:13:59Z,
<https://gitlab.com/fdroid/fdroiddata/-/merge_requests/58000#note_4273601525>.
Это GitLab `DiffNote` к файлу
`metadata/io.github.volmir.wordscombination.yml`, строка 48 (новая сторона diff),
с suggestion «удалить строку»:

```suggestion:-0+0
```

Предлагаемое удаление относится именно к строке:

```yaml
UpdateCheckData: app/build.gradle.kts|versionCode *= *([0-9]+)|.|versionName *= *"([0-9.]+)"
```

Других замечаний в этой заметке нет: предложение пустое, то есть заменяет
строку на ничего. Замечание проверено через публичный
`/-/merge_requests/58000/discussions.json`; API `/api/v4/...` без токена
возвращает 401, но web-эндпоинт обсуждений доступен.

## 2. Результат проверки

`UpdateCheckData` для `UpdateCheckMode: Tags` необязателен. По
[Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/):
в режиме `Tags` он нужен только для извлечения версии из нестандартных файлов;
без него `fdroid checkupdates` ищет `AndroidManifest.xml`/`build.gradle`/
`build.gradle.kts` в каталоге последней сборки. `build.gradle.kts` входит в
штатный список `common.manifest_paths`, а `possible_subdirs` находит `app/`
по package name. `AutoUpdateMode: Version` вместе с `Tags` означает, что
найденный тег используется напрямую — отдельный шаблон не требуется.

Проверено на установленном `fdroidserver` (временная установка в
`/tmp/opencode/fdroidlib`, не зависимость проекта):

- `fdroid checkupdates -v --allow-dirty io.github.volmir.wordscombination` в
  одноразовой копии fdroiddata с пропатченным metadata: для тегов
  `1.0.3`, `1.0.2`, `1.0.1`, `1.0.0` выведено
  `Manifest exists in subdir 'app'. Found version 1.0.3 (4)` и т.д.;
  итоговые `CurrentVersion: 1.0.3` / `CurrentVersionCode: 4` не изменились.
  Ошибка `Environment variable {env: serverwebroot} is not set!` относится к
  локальному `config.yml`, а не к metadata, и не влияет на поиск версии.
- `fdroid lint io.github.volmir.wordscombination` — код 0. Единственное
  сообщение: `metadata/...yml:13: trailing spaces (trailing-spaces)`.
  Это существовавшая строка `Binaries: ` с пробелом на конце; она не входит
  в необходимую правку и намеренно не менялась.
- `git diff --check` — код 0, пробелов/ошибок нет.
- `python3` + PyYAML: файл разбирается; `AutoUpdateMode: Version` и
  `UpdateCheckMode: Tags` сохранены, `UpdateCheckData` отсутствует.
- Сравнение с `HEAD` по ключам: изменён только `UpdateCheckData`;
  `Binaries`, `AllowedAPKSigningKeys`, `Builds` (версия 1.0.3, versionCode 4,
  commit `7a2a7f44938bcf4f3104c2189e60e7e0738c5436`), `Repo`, `RepoType`,
  `License`, `CurrentVersion`, `CurrentVersionCode` идентичны `HEAD`.
- `git diff --numstat` — `0 1` (одна удалённая строка, ноль добавленных).

## 3. Patch

Файл: [`docs/fdroiddata/FDROID-01-remove-updatecheckdata.patch`](fdroiddata/FDROID-01-remove-updatecheckdata.patch).
Применяется из корня fdroiddata командой `git apply`.

```diff
--- a/metadata/io.github.volmir.wordscombination.yml
+++ b/metadata/io.github.volmir.wordscombination.yml
@@ -45,6 +45,5 @@ MaintainerNotes: |-
 
 AutoUpdateMode: Version
 UpdateCheckMode: Tags
-UpdateCheckData: app/build.gradle.kts|versionCode *= *([0-9]+)|.|versionName *= *"([0-9.]+)"
 CurrentVersion: 1.0.3
 CurrentVersionCode: 4
```

## 4. Статус применения

Патч **применён в рабочем дереве** указанного клона fdroiddata; изменения не
закоммичены и не отправлены. `git status` показывает ровно:

```text
 M metadata/io.github.volmir.wordscombination.yml
```

Это соответствует ограничению задачи: commit/push/merge выполняет владелец.
Внешняя проверка metadata выполнена (см. §2), поэтому задача не помечается
`BLOCKED`. Публикация правки в GitLab остаётся действием владельца.

## 5. Действия владельца

```sh
cd /path/to/fdroiddata-words-combination-mr58000
git diff --check
git diff                     # ровно одна удалённая строка
git add metadata/io.github.volmir.wordscombination.yml
git commit -m "Words Combination: drop redundant UpdateCheckData"
git push origin new-app-wordscombination
```

После push дождаться зелёного pipeline MR !58000 и отметить обсуждение
`#note_4273601525` решённым. Если GitLab предложит применить suggestion
кнопкой — содержимое совпадает с этим patch.

## 6. Что не проверено

- `fdroid build`/buildserver и повторное воспроизведение APK не запускались:
  правка касается только metadata и не меняет исходники или артефакты.
- `fdroid checkupdates` выполнялся в одноразовой копии, чтобы не менять
  рабочий клон fdroiddata; сам рабочий клон проверен `fdroid lint`.
- `serverwebroot` в локальном `config.yml` не настроен; на проверку версии
  по тегам это не влияет.
