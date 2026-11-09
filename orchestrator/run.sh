#!/usr/bin/env bash
# Запускайте из корня целевого проекта. Python — только стандартная библиотека.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/orchestrator.py" "$@"
