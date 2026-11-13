#!/usr/bin/env python3
"""Профили --check для orchestrator/run.sh.

Встроен только профиль orchestrator — самопроверка модуля. Остальные профили
задаются ключом checks в orchestrator/config.json: словарь «профиль → массив
shell-команд». Команды выполняются по очереди из корня проекта; ошибка любой
останавливает профиль с её кодом возврата.
"""
import json
import subprocess
import sys
from pathlib import Path

BUILTIN_CHECKS = {"orchestrator": ["python3 -m unittest discover -s orchestrator/tests"]}


def load_checks():
    """checks из config.json рядом с модулем; встроенный orchestrator не переопределяется."""
    path = Path(__file__).resolve().parent / "config.json"
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(f"config.json: неверный JSON: {error}")
    checks = data.get("checks") if isinstance(data, dict) else None
    if not isinstance(checks, dict) or any(
            not isinstance(name, str) or not name or not isinstance(commands, list) or not commands
            or not all(isinstance(command, str) and command.strip() for command in commands)
            for name, commands in checks.items()):
        raise SystemExit("config.json: checks — словарь «профиль → массив непустых shell-команд»")
    if "orchestrator" in checks:
        raise SystemExit("config.json: профиль orchestrator встроен в модуль и не переопределяется")
    return checks


def main(argv):
    checks = {**BUILTIN_CHECKS, **load_checks()}
    if argv == ["--list"]:
        for name, commands in sorted(checks.items()):
            print(f"  {name}: {' && '.join(commands)}")
        return 0
    if len(argv) != 1 or not argv[0] or argv[0].startswith("-"):
        print("использование: checks.py [--list|ПРОФИЛЬ]; "
              "профили — встроенный orchestrator + ключ checks в orchestrator/config.json", file=sys.stderr)
        return 2
    commands = checks.get(argv[0])
    if commands is None:
        print(f"профиль {argv[0]} не задан; доступные: {', '.join(sorted(checks))}", file=sys.stderr)
        return 2
    for command in commands:
        result = subprocess.run(["bash", "-c", command])
        if result.returncode:
            return result.returncode
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
