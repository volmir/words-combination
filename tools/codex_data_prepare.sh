#!/usr/bin/env bash
# Явный opt-in для сетевой подготовки данных в агенте оркестратора.
set -euo pipefail

if [[ "${1:-}" != exec ]]; then
    echo 'Ожидается codex exec; используйте эту обёртку через CODEX_BIN для DATA-01.' >&2
    exit 2
fi
shift

exec codex exec \
    -c 'sandbox_workspace_write.network_access=true' \
    --add-dir "${GRADLE_USER_HOME:-${HOME}/.gradle}" \
    --add-dir "${CODEX_HOME:-${HOME}/.codex}" \
    "$@"
