# Проверка модуля оркестратора

Здесь фиксируются реальные прогоны модуля: автотесты, режим проверок
и живые запуски с настоящими CLI-агентами. Тесты выполнения работают
с временными репозиториями и подменяют агентов заглушками: они проверяют
поведение оркестратора, а не качество кода, генерируемого моделями.

## 2026-11-13: перенос мультибэкенд-версии (Codex + OpenCode)

Среда: Linux, локальный Git-проект, Python 3.10, JDK 21 и Android SDK 36
(`android-36`, build-tools 36.0.0) установлены; `codex-cli 0.167.1`,
`opencode v2.2.0`.

Перенесена версия модуля с двумя бэкендами (`backend: codex|opencode`),
профилями `--check` из `config.json` и именованными наборами моделей
(`model_sets.opencode`). В проект «Составь слова» скопированы
`orchestrator.py`, `checks.py`, `run.sh`, тесты, документация и примеры.
Профили проверок и модели адаптированы под проект:

- `tools`, `core`, `data`, `android`, `full`, `release` (плюс встроенный
  `orchestrator`); `full` = оба Python-набора + `:core:test :app:assembleDebug`.
- `models.codex` — платный набор Codex; `models.opencode` — бесплатный набор
  по умолчанию; `model_sets.opencode.paid` — платный DeepSeek-набор.
- Бэкенд по умолчанию `opencode`, Codex переключается флагом `--backend codex`.

Проверки:

- `bash -n orchestrator/run.sh`, `python3 -m py_compile` файлов модуля — успешно.
- `bash orchestrator/run.sh --check orchestrator` — **86 тестов, OK**
  (заглушки Codex и OpenCode, временные Git-репозитории, без сети и SDK).
- `bash orchestrator/run.sh --check tools` — **278 тестов, OK**.
- `bash orchestrator/run.sh --list` и `--dry-run` — работают; в колонке
  MODEL/EFFORT для каждого tier видна модель активного бэкенда. При
  `backend="opencode"` выводится предупреждение об игнорировании `efforts` —
  ожидаемое поведение перенесённой версии.
- Адаптация: `OrchestratorTests` фиксирует `backend=codex` независимо от
  конфига проекта, компаратор профиля `android` обновлён под полный состав
  команд проекта (`compileDebugKotlin`, `lintDebug`, `compileDebugAndroidTestKotlin`).

Не проверено в этом прогоне: `--check full` (Gradle `:core:test :app:assembleDebug`) —
полный гейт будет исполнен задачей DATA-01; Android-компиляция и release
по-прежнему требуют ручного аудита определений и не считаются успешными
без реального прогона.

Живой прогон с настоящим OpenCode в этом прогоне не выполнялся; модуль
продолжает незавершённую задачу DATA-01 через флаг `--backend opencode --models paid`.
