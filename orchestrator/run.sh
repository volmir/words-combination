#!/usr/bin/env bash
# Очередь запускайте из корня проекта; --check работает из любого каталога.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

check_usage() {
    cat <<'EOF'
Использование: bash orchestrator/run.sh --check [full|tools|orchestrator|core|data|android]
  full          Python-тесты и гейт перед коммитом: :core:test :app:assembleDebug
  tools         Тесты инструментов словаря
  orchestrator  Тесты оркестратора с заглушкой Codex
  core          Все JVM-тесты игровой логики
  data          Проверка лицензий/хэшей данных и JVM-тесты
  android       Компиляция Kotlin, lint и компиляция инструментальных тестов
Для --check по умолчанию: full. Ошибка любой проверки останавливает выполнение.
Режим --check не запускает очередь, агента и Git-операции.
EOF
}

# Проверки выполняются до запуска Python: рабочее дерево может быть изменено,
# а блокировка очереди уже занята родительским оркестратором.
if [[ "${1:-}" == --check ]]; then
    if (( $# > 2 )); then
        check_usage >&2
        exit 2
    fi
    profile="${2-full}"
    case "$profile" in
        --help|-h) check_usage; exit 0 ;;
        full|tools|orchestrator|core|data|android) ;;
        *) check_usage >&2; exit 2 ;;
    esac
    cd -- "$SCRIPT_DIR/.."
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
    exit 0
fi

if (( $# == 1 )) && [[ "$1" == --help || "$1" == -h ]]; then
    python3 "$SCRIPT_DIR/orchestrator.py" "$@"
    check_usage
    exit 0
fi

exec python3 "$SCRIPT_DIR/orchestrator.py" "$@"
