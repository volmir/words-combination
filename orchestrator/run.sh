#!/usr/bin/env bash
# Очередь запускайте из корня проекта; --check работает из любого каталога.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

usage() {
    cat <<'EOF'
Использование: bash orchestrator/run.sh --check [ПРОФИЛЬ]
  orchestrator  Самопроверка модуля: python3 -m unittest discover -s orchestrator/tests
  full          Профиль по умолчанию; остальные профили задаются ключом checks
                в orchestrator/config.json (профиль → массив shell-команд).
Доступные профили:
EOF
    python3 "$SCRIPT_DIR/checks.py" --list 2>/dev/null || true
    cat <<'EOF'
Ошибка любой проверки останавливает профиль с её кодом возврата.
Режим --check не запускает очередь, агента и Git-операции.
EOF
}

# Проверки выполняются до запуска Python-очереди: рабочее дерево может быть
# изменено, а блокировка очереди уже занята родительским оркестратором.
if [[ "${1:-}" == --check ]]; then
    if (( $# > 2 )); then
        usage >&2
        exit 2
    fi
    profile="${2-full}"
    case "$profile" in
        --help|-h) usage; exit 0 ;;
    esac
    cd -- "$SCRIPT_DIR/.."
    # Вложенный gate не должен создавать __pycache__ в защищённом orchestrator/.
    # Переменная наследуется и дочерними Python-процессами проверок.
    export PYTHONDONTWRITEBYTECODE=1
    exec python3 "$SCRIPT_DIR/checks.py" "$profile"
fi

if (( $# == 1 )) && [[ "$1" == --help || "$1" == -h ]]; then
    python3 "$SCRIPT_DIR/orchestrator.py" "$@"
    usage
    exit 0
fi

exec python3 "$SCRIPT_DIR/orchestrator.py" "$@"
