#!/usr/bin/env python3
"""Последовательный Codex → gate → журнал → Git. Без внешних Python-пакетов."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
from dataclasses import asdict, dataclass
from datetime import datetime, timezone


class Error(Exception):
    pass


@dataclass(frozen=True)
class Task:
    id: str
    title: str
    tier: str
    gate: str
    retries: int
    prompt: str

    def fingerprint(self):
        data = json.dumps(asdict(self), ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(data.encode()).hexdigest()


def parse_tasks(path):
    """Небольшой строгий формат Markdown; заголовки внутри prompt — обычный текст."""
    tasks, seen = [], set()
    current, fields, prompt = None, {}, None
    fence, outside_fence = None, None
    lines = path.read_text(encoding="utf-8-sig").splitlines()

    def finish():
        if current is None:
            return
        task_id, title = current
        if prompt is None or not "\n".join(prompt).strip():
            raise Error(f"{task_id}: нужен непустой блок ```prompt")
        if "gate" not in fields or not fields["gate"]:
            raise Error(f"{task_id}: укажите gate: <команда> или gate: skip")
        tier = fields.get("tier", "standard")
        if tier not in ("light", "standard", "heavy"):
            raise Error(f"{task_id}: неизвестный tier {tier}")
        retries = fields.get("retries", "1")
        if not re.fullmatch(r"[0-9]+", retries) or int(retries) > 20:
            raise Error(f"{task_id}: retries должен быть целым числом 0–20")
        tasks.append(Task(task_id, title, tier, fields["gate"], int(retries),
                          "\n".join(prompt).strip()))

    for number, line in enumerate(lines, 1):
        if fence:
            if line.strip() == fence:
                fence = None
            else:
                prompt.append(line)
            continue
        if outside_fence:
            if line.strip() == outside_fence:
                outside_fence = None
            continue
        header = re.fullmatch(r"## ([A-Za-z][A-Za-z0-9_-]*):\s*(\S.*)", line)
        if header:
            finish()
            task_id, title = header.groups()
            if task_id in seen:
                raise Error(f"повтор ID {task_id}")
            seen.add(task_id)
            current, fields, prompt = (task_id, title.strip()), {}, None
            continue
        if line.startswith("## "):
            raise Error(f"{path.name}:{number}: заголовок задачи должен иметь вид '## ID: Название'")
        if current is None:
            match = re.match(r"^(`{3,}|~{3,})", line)
            if match:
                outside_fence = match[1]
            continue
        match = re.fullmatch(r"(`{3,}|~{3,})prompt\s*", line)
        if match:
            if prompt is not None:
                raise Error(f"{current[0]}: повтор блока prompt")
            prompt, fence = [], match[1]
            continue
        if not line.strip():
            continue
        match = re.fullmatch(r"(tier|gate|retries):\s*(.*)", line)
        if not match or prompt is not None:
            raise Error(f"{path.name}:{number}: неверная строка метаданных: {line}")
        key, value = match.groups()
        if key in fields:
            raise Error(f"{current[0]}: повтор поля {key}")
        fields[key] = value.strip()
    if fence:
        raise Error("не закрыт блок prompt")
    finish()
    return tasks


DEFAULTS = {
    "tasks_file": "orchestrator/TASKS.md", "progress_file": "orchestrator/PROGRESS.md",
    "branch": "auto/tasks", "remote": "origin", "push": False,
    "codex_bin": "codex", "sandbox": "workspace-write", "gate_base": [],
    "task_timeout": 3600, "gate_timeout": 1800, "git_timeout": 120,
    "models": {"light": "gpt-5.6-luna", "standard": "gpt-6.1-terra", "heavy": "gpt-6-astra"},
    "efforts": {"light": "low", "standard": "medium", "heavy": "high"},
}


def load_config(path):
    config = dict(DEFAULTS)
    config["models"], config["efforts"] = dict(DEFAULTS["models"]), dict(DEFAULTS["efforts"])
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or set(data) - set(DEFAULTS):
            raise Error("config.json: неизвестные ключи или неверный JSON-объект")
        for key, value in data.items():
            if key in ("models", "efforts"):
                if not isinstance(value, dict) or set(value) - set(config[key]):
                    raise Error(f"config.json: неверная секция {key}")
                config[key].update(value)
            else:
                config[key] = value
    # Прежние переменные окружения сохранены; окружение имеет приоритет над JSON.
    for key in ("tasks_file", "progress_file", "branch", "remote", "codex_bin", "sandbox"):
        config[key] = os.environ.get(key.upper(), config[key])
    for key in ("task_timeout", "gate_timeout", "git_timeout"):
        if key.upper() in os.environ:
            config[key] = int(os.environ[key.upper()])
        if type(config[key]) is not int or config[key] <= 0:
            raise Error(f"{key}: нужно положительное число секунд")
    for tier in config["models"]:
        config["models"][tier] = os.environ.get("MODEL_" + tier.upper(), config["models"][tier])
        config["efforts"][tier] = os.environ.get("EFFORT_" + tier.upper(), config["efforts"][tier])
        if not isinstance(config["models"][tier], str) or not isinstance(config["efforts"][tier], str):
            raise Error("models/efforts: значения должны быть строками")
    if "GATE_BASE" in os.environ:
        config["gate_base"] = shlex.split(os.environ["GATE_BASE"])
    if not isinstance(config["gate_base"], list) or not all(isinstance(v, str) and v for v in config["gate_base"]):
        raise Error("gate_base: нужен массив аргументов, например [\"docker\", \"compose\", \"run\", \"--rm\", \"app\"]")
    if type(config["push"]) is not bool:
        raise Error("push: нужно true или false")
    if "NO_PUSH" in os.environ:
        if os.environ["NO_PUSH"] not in ("0", "1"):
            raise Error("NO_PUSH: допустимы 0 или 1")
        config["push"] = os.environ["NO_PUSH"] == "0"
    for key in ("tasks_file", "progress_file", "branch", "remote", "codex_bin", "sandbox"):
        if not isinstance(config[key], str) or not config[key]:
            raise Error(f"{key}: нужна непустая строка")
    if config["sandbox"] not in ("read-only", "workspace-write", "danger-full-access"):
        raise Error("неверный sandbox")
    return config


def git(*args, check=True, timeout=120):
    result = subprocess.run(["git", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout)
    if check and result.returncode:
        raise Error(f"git {' '.join(args)}: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout.decode("utf-8", "replace").strip()


def local_path(root, value):
    path = root / value
    if path.is_symlink() or path.absolute() != path.resolve():
        raise Error(f"путь должен быть обычным путём без symlink и '..': {value}")
    if not path.is_relative_to(root) or path == root or path.is_relative_to(root / ".git"):
        raise Error(f"путь должен быть внутри проекта вне .git/: {value}")
    return path


RECORD = re.compile(r"^<!-- orchestrator:v1 (.+) -->$", re.MULTILINE)


def read_progress(path):
    records = {}
    if not path.exists():
        return records
    content = path.read_text(encoding="utf-8")
    if content.count("<!-- orchestrator:v1") != len(RECORD.findall(content)):
        raise Error("повреждён служебный комментарий в журнале")
    for value in RECORD.findall(content):
        record = json.loads(value)
        if not isinstance(record, dict) or not isinstance(record.get("id"), str) or not re.fullmatch(r"[a-f0-9]{64}", str(record.get("fingerprint", ""))):
            raise Error("повреждена запись журнала")
        if record["id"] in records:
            raise Error(f"повтор ID в журнале: {record['id']}")
        records[record["id"]] = record
    if re.search(r"^- \[.+\] ✓", content, re.MULTILINE) and not records:
        raise Error("обнаружен старый журнал без fingerprint; используйте новый PROGRESS_FILE")
    return records


def snapshot(paths, excluded=()):
    result = {}
    for path in paths:
        entries = [path, *sorted(path.rglob("*"))] if path.is_dir() else [path]
        for entry in entries:
            if any(entry == directory or entry.is_relative_to(directory) for directory in excluded):
                continue
            if entry.is_symlink():
                raise Error(f"symlink в защищённом пути: {entry}")
            if entry.is_file():
                result[str(entry)] = (hashlib.sha256(entry.read_bytes()).hexdigest(), entry.stat().st_mode)
            else:
                result[str(entry)] = "directory" if entry.is_dir() else "missing"
    return result


def stop_group(process):
    """Останавливает также дочерние процессы Codex/gate, чтобы они не продолжали правки."""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def run_logged(command, log, timeout, prompt=None):
    # Prompt через stdin: нет ограничения длины аргументов и shell-интерполяции.
    with log.open("wb") as output, tempfile.TemporaryFile() as input_file:
        if prompt is not None:
            input_file.write(prompt.encode())
            input_file.seek(0)
        process = subprocess.Popen(command, stdin=input_file, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            stop_group(process)
            raise Error(f"таймаут или остановка; лог: {log}")


def tail(path, limit=12000):
    if not path.exists():
        return "(нет вывода)"
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - limit))
        return stream.read().decode("utf-8", "replace")


def prompt_for(task, context, protected):
    return f"""Выполни ровно одно задание: {task.id}: {task.title}.
Следуй AGENTS.md целевого проекта, если он есть. К следующим заданиям не переходи.
Не выполняй git commit/push/checkout/reset/add и не меняй Git-историю.
Не меняй защищённые пути: {', '.join(protected)}, включая их служебные логи.
Оркестратор сам выполнит gate. Не ослабляй проверки и критерии ради успеха.
Если задача невыполнима, последняя строка ответа: BLOCKED: <причина>.
Иначе дай итог по-русски:
ИТОГ: <что сделано>
ПРОВЕРЕНО: <что действительно запускалось>
НЕ ПРОВЕРЕНО / ОТКЛОНЕНИЯ: <ограничения либо «нет»>

Последние итоги предыдущих задач:
{context or '(пока нет)'}

ЗАДАНИЕ {task.id}:
{task.prompt}
"""


def append_progress(path, task, summary, manual, model, effort):
    content = path.read_text(encoding="utf-8") if path.exists() else "# Журнал задач\n"
    record = {"id": task.id, "fingerprint": task.fingerprint(),
              "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
              "gate": "skipped" if task.gate == "skip" else "passed",
              "manual": manual, "summary": summary, "model": model or "default", "effort": effort}
    content += f"\n- [{task.id}] ✓ {record['date']} — {task.title} — {summary}\n"
    if task.gate == "skip":
        content += "  Проверка: ПРОПУЩЕНА (gate: skip).\n"
    content += "<!-- orchestrator:v1 " + json.dumps(record, ensure_ascii=False).replace("-->", "--\\u003e") + " -->\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    # Atomic write: Ctrl+C не оставит половину JSON-записи.
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    os.replace(temporary, path)


def execute(args):
    script_dir = Path(__file__).resolve().parent
    config_path = (Path.cwd() / args.config).absolute() if args.config else script_dir / "config.json"
    config = load_config(config_path)
    if args.push is not None:
        config["push"] = args.push
    # Список/план работают даже до git init и не создают каталогов или логов.
    root = Path.cwd().resolve()
    tasks_path = local_path(root, config["tasks_file"])
    progress_path = local_path(root, config["progress_file"])
    config_path = local_path(root, str(config_path))
    work = script_dir / ".runtime"
    if any(path.is_relative_to(work) for path in (tasks_path, progress_path, config_path)):
        raise Error("очередь, журнал и конфигурация должны находиться вне служебного каталога .runtime/")
    if tasks_path == progress_path or config_path in (tasks_path, progress_path):
        raise Error("пути tasks_file, progress_file, config должны различаться")
    tasks = parse_tasks(tasks_path)
    records = read_progress(progress_path)
    ids = [task.id for task in tasks]
    for selected in (args.only, args.start, args.mark_done):
        if selected and selected not in ids:
            raise Error(f"нет задания {selected}")
    for task in tasks:
        if task.id in records and records[task.id]["fingerprint"] != task.fingerprint():
            raise Error(f"{task.id}: выполненная задача изменена. Для новой работы назначьте новый ID.")
    selected = tasks[ids.index(args.start):] if args.start else tasks
    if args.only or args.mark_done:
        selected = [task for task in tasks if task.id == (args.only or args.mark_done)]
    if args.list or args.dry_run:
        print("ID | TIER | STATUS | MODEL/EFFORT | TITLE")
        for task in selected:
            status = "done" if task.id in records else "pending"
            print(f"{task.id} | {task.tier} | {status} | {config['models'][task.tier] or 'default'}/{config['efforts'][task.tier]} | {task.title}")
            if args.dry_run:
                print(f"  gate: {task.gate}; retries: {task.retries}")
        return
    pending = [task for task in selected if task.id not in records]
    if args.mark_done and not pending:
        raise Error(f"{args.mark_done}: уже выполнено")
    if records:
        relative_progress = str(progress_path.relative_to(root))
        if not git("ls-files", "--", relative_progress) or git("diff", "HEAD", "--", relative_progress):
            raise Error("журнал содержит незакоммиченные результаты; сначала завершите или исправьте предыдущий коммит")
    if not pending:
        print("Нет невыполненных задач.")
        return
    if git("rev-parse", "--show-toplevel") != str(root):
        raise Error("запускайте из корня Git-репозитория")
    if not script_dir.is_relative_to(root) or script_dir == root:
        raise Error("скопируйте каталог orchestrator/ внутрь целевого проекта")
    if not git("rev-parse", "--verify", "HEAD", check=False):
        raise Error("нужен хотя бы один начальный коммит")
    if git("check-ref-format", "--branch", config["branch"], check=False) != config["branch"] or config["branch"] in ("main", "master"):
        raise Error("branch: задайте отдельную ветку задач, например auto/tasks")
    if config["remote"].startswith("-"):
        raise Error("remote не может начинаться с '-'")
    common = Path(git("rev-parse", "--git-common-dir")).resolve()
    # Один процесс на весь репозиторий, включая linked worktrees.
    with (common / "universal-orchestrator.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Error("другой оркестратор уже работает с этим репозиторием")
        run_queue(args, config, root, script_dir, config_path, tasks_path, progress_path, records, pending)


def run_queue(args, config, root, script_dir, config_path, tasks_path, progress_path, records, pending):
    work = script_dir / ".runtime"
    relative_work = work.relative_to(root).as_posix()
    if work.is_symlink():
        raise Error(".runtime не должен быть symlink")
    exclude = Path(git("rev-parse", "--git-path", "info/exclude"))
    exclude.parent.mkdir(parents=True, exist_ok=True)
    old_exclude = exclude.read_text() if exclude.exists() else ""
    exclude_rule = f"/{relative_work}/"
    if exclude_rule not in old_exclude.splitlines():
        with exclude.open("a") as file:
            file.write(f"\n{exclude_rule}\n")
    if git("ls-files", "--", relative_work):
        raise Error(f"{relative_work}/ уже отслеживается Git; исключите служебные файлы из индекса")
    current_branch = git("symbolic-ref", "--short", "-q", "HEAD", check=False)
    if not current_branch:
        raise Error("detached HEAD: сначала выберите рабочую ветку")
    for name in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply"):
        if Path(git("rev-parse", "--git-path", name)).exists():
            raise Error(f"сначала завершите текущую Git-операцию ({name})")
    if args.mark_done:
        if current_branch != config["branch"]:
            raise Error("--mark-done запускается в настроенной ветке задач")
    elif git("status", "--porcelain"):
        raise Error("рабочее дерево должно быть чистым; сохраните изменения коммитом перед запуском")
    if git("var", "GIT_AUTHOR_IDENT", check=False) == "" or git("var", "GIT_COMMITTER_IDENT", check=False) == "":
        raise Error("настройте git user.name и user.email")
    if not args.mark_done and shutil.which(config["codex_bin"]) is None:
        raise Error(f"не найден CODEX_BIN: {config['codex_bin']}")
    if config["push"]:
        git("remote", "get-url", config["remote"])
    # Задачи и конфигурация должны быть закоммичены, а не скрыты .gitignore.
    for path in (tasks_path, config_path, script_dir / "orchestrator.py", script_dir / "run.sh"):
        if path.exists() and not git("ls-files", "--", str(path.relative_to(root))):
            raise Error(f"добавьте в Git: {path.relative_to(root)}")
    if not args.mark_done:
        # Не переходим автоматически на существующую чужую/старую ветку.
        if current_branch != config["branch"]:
            exists = git("show-ref", "--verify", f"refs/heads/{config['branch']}", check=False)
            if exists:
                raise Error(f"ветка {config['branch']} уже существует; выберите её вручную или задайте BRANCH")
            git("checkout", "-b", config["branch"])
    run_name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    logs = work / "logs" / run_name
    logs.mkdir(parents=True)
    protected = [script_dir, tasks_path, progress_path, config_path]
    display_paths = list(dict.fromkeys(str(path.relative_to(root)) for path in protected))
    for task in pending:
        print(f"[{task.id}] {task.title}", flush=True)
        head = git("rev-parse", "HEAD")
        baseline = snapshot(protected, excluded=[work])

        def verify():
            if snapshot(protected, excluded=[work]) != baseline:
                raise Error(f"{task.id}: изменены защищённые файлы; изменения оставлены без коммита")
            if git("rev-parse", "HEAD") != head or git("symbolic-ref", "--short", "HEAD") != config["branch"]:
                raise Error(f"{task.id}: изменена Git-история или ветка; остановка")

        model, effort = config["models"][task.tier], config["efforts"][task.tier]
        context = "\n".join(f"{r['id']}: {r.get('summary', '')}" for r in list(records.values())[-15:])
        prompt = prompt_for(task, context, display_paths)
        last = logs / f"{task.id}.last.md"
        summary = "Выполнено вручную; проверка запущена оркестратором."
        for attempt in range(task.retries + 1):
            if not args.mark_done:
                command = [config["codex_bin"], "exec"]
                if model:
                    command += ["--model", model]
                command += ["-c", "model_reasoning_effort=" + json.dumps(effort),
                            "--sandbox", config["sandbox"], "--output-last-message", str(last), "-"]
                last.unlink(missing_ok=True)
                code = run_logged(command, logs / f"{task.id}.exec.{attempt}.log", config["task_timeout"], prompt)
                verify()
                if code:
                    raise Error(f"{task.id}: Codex завершился с кодом {code}; см. {logs}")
                reply = last.read_text(encoding="utf-8") if last.exists() else ""
                if not reply.strip() or re.search(r"^BLOCKED:", reply, re.MULTILINE):
                    raise Error(f"{task.id}: агент не подтвердил завершение; см. {last}")
                summary = next((line for line in reply.splitlines() if line.startswith("ИТОГ:")), reply.splitlines()[0])
                summary = " ".join(summary.split())[:300]
            gate_log = logs / f"{task.id}.gate.{attempt}.log"
            if task.gate == "skip":
                print("  gate: ПРОПУЩЕНА по TASKS.md", flush=True)
                break
            print(f"  gate (попытка {attempt + 1}): {task.gate}", flush=True)
            code = run_logged([*config["gate_base"], "sh", "-c", task.gate], gate_log, config["gate_timeout"])
            verify()
            if code == 0:
                print("  gate: OK", flush=True)
                break
            if args.mark_done or attempt == task.retries:
                raise Error(f"{task.id}: gate завершился с кодом {code}; нет коммита. Лог: {gate_log}")
            prompt = prompt_for(task, context, display_paths) + f"""
Это повторная попытка исправления {attempt + 1}/{task.retries}.
Предыдущие изменения остались в рабочем дереве. Изучи их перед правками.
Предыдущий ответ:
{tail(last)}
Gate завершился с кодом {code}. Последний вывод:
{tail(gate_log)}
Исправь причину. Не отключай и не ослабляй gate ради прохождения.
"""
        verify()
        append_progress(progress_path, task, summary, bool(args.mark_done), model, effort)
        git("add", "-A", "--", ".")
        git("commit", "-m", f"{task.id}: {task.title}", timeout=config["git_timeout"])
        print(f"  commit: {git('rev-parse', '--short', 'HEAD')}", flush=True)
        records = read_progress(progress_path)
        if config["push"]:
            push_log = logs / f"{task.id}.push.log"
            code = run_logged(["git", "push", "-u", config["remote"], f"HEAD:refs/heads/{config['branch']}"],
                              push_log, config["git_timeout"])
            if code:
                raise Error(f"push не прошёл; коммит {task.id} сохранён локально. Выполните git push вручную. Лог: {push_log}")
        # Hooks не должны оставлять изменения, попадающие в следующую задачу.
        if git("status", "--porcelain"):
            raise Error("после коммита остались изменения (возможно, Git hook); разберите их вручную")
    print(f"Готово. Журнал: {progress_path.relative_to(root)}; логи: {logs.relative_to(root)}")


def main():
    # SIGTERM, как и Ctrl+C, должен остановить активную группу дочерних процессов.
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", help="JSON-конфигурация; по умолчанию orchestrator/config.json")
    view = parser.add_mutually_exclusive_group()
    view.add_argument("--list", action="store_true", help="список без запусков и изменений")
    view.add_argument("--dry-run", action="store_true", help="план с gate, без запусков и изменений")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--only", metavar="ID", help="только одна задача")
    selection.add_argument("--from", dest="start", metavar="ID", help="начать с ID")
    selection.add_argument("--mark-done", metavar="ID", help="проверить и закоммитить ручное исправление")
    publishing = parser.add_mutually_exclusive_group()
    publishing.add_argument("--push", dest="push", action="store_true", help="push после каждой задачи")
    publishing.add_argument("--no-push", dest="push", action="store_false", help="отключить push, сохранить локальные коммиты")
    parser.set_defaults(push=None)
    args = parser.parse_args()
    if args.mark_done and (args.list or args.dry_run):
        parser.error("--mark-done нельзя совмещать с --list/--dry-run")
    try:
        execute(args)
        return 0
    except (Error, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"ОШИБКА: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Остановлено. Незакоммиченные изменения сохранены.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
