#!/usr/bin/env bash
# Проверки проекта; не запускает агента, не коммитит и не регенерирует данные.
set -euo pipefail

usage() {
    cat <<'EOF'
Использование: bash orchestrator/gate.sh [full|tools|orchestrator|core|data|android]
  full          Python-тесты и гейт перед коммитом: :core:test :app:assembleDebug
  tools         Тесты инструментов словаря
  orchestrator  Тесты оркестратора с заглушкой Codex
  core          Все JVM-тесты игровой логики
  data          Проверка лицензий/хэшей данных и JVM-тесты
  android       Компиляция Kotlin, lint и компиляция инструментальных тестов
По умолчанию: full. Ошибка любой проверки останавливает выполнение.
EOF
}

if (( $# > 1 )); then
    usage >&2
    exit 2
fi

profile="${1:-full}"
case "$profile" in
    --help|-h) usage; exit 0 ;;
    full|tools|orchestrator|core|data|android) ;;
    *) usage >&2; exit 2 ;;
esac

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$PROJECT_ROOT"

case "$profile" in
    tools) python3 -m unittest discover -s tools/tests ;;
    orchestrator) python3 -m unittest discover -s orchestrator/tests ;;
    core) bash ./gradlew :core:test --console=plain ;;
    data) bash ./gradlew :app:verifyDictionaryLicenses :core:test --console=plain ;;
    android)
        bash ./gradlew :app:compileDebugKotlin :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain
        ;;
    full)
        python3 -m unittest discover -s tools/tests
        python3 -m unittest discover -s orchestrator/tests
        bash ./gradlew :core:test :app:assembleDebug --console=plain
        ;;
esac
