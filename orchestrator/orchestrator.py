#!/usr/bin/env python3
"""Последовательный агент (Codex или OpenCode) → gate → журнал → Git. Без внешних Python-пакетов."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone


class Error(Exception):
    pass


class RetryableError(Error):
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


CODEX_MODEL_DEFAULTS = {"light": "gpt-5.6-luna", "standard": "gpt-6.1-sol", "heavy": "gpt-6-astra"}
OPENCODE_MODEL_DEFAULTS = {"light": "opencode/glm-5.3-flash-free",
                           "standard": "opencode/glm-5.3-flash-free#max",
                           "heavy": "opencode/beam-free#max"}
TIERS = ("light", "standard", "heavy")
BACKENDS = ("codex", "opencode")
DEFAULTS = {
    "tasks_file": "orchestrator/TASKS.md", "progress_file": "orchestrator/PROGRESS.md",
    "branch": "auto/tasks", "remote": "origin", "push": True,
    "backend": "codex", "codex_bin": "codex", "opencode_bin": "opencode",
    "sandbox": "workspace-write", "gate_base": [], "checks": {},
    "task_timeout": 14400, "gate_timeout": 7200, "git_timeout": 120,
    "restart_retries": 3, "retry_delay": 30,
    "models": {"codex": dict(CODEX_MODEL_DEFAULTS), "opencode": dict(OPENCODE_MODEL_DEFAULTS)},
    "model_sets": {},
    "semantic": {},
    "efforts": {"light": "low", "standard": "medium", "heavy": "high"},
}


def load_config(path, backend=None, models_set=None):
    """backend и models_set — необязательные перекрытия от CLI (--backend, --models).

    backend валидируется как ключ конфига; models_set выбирает именованный набор
    из model_sets.opencode и заменяет им модели активного бэкенда на один запуск.
    """
    config = dict(DEFAULTS)
    config["models"] = {name: dict(tiers) for name, tiers in DEFAULTS["models"].items()}
    config["model_sets"] = {}
    config["semantic"] = {}
    config["efforts"] = dict(DEFAULTS["efforts"])
    if path.exists():
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or set(data) - set(DEFAULTS):
            raise Error("config.json: неизвестные ключи или неверный JSON-объект")
        models = data.pop("models", None)
        model_sets = data.pop("model_sets", None)
        semantic = data.pop("semantic", None)
        efforts = data.pop("efforts", None)
        config.update(data)
        # Перекрытие применяется до разбора models: от него зависят и запрет
        # плоского формата, и выбор секции моделей активного бэкенда.
        if backend is not None:
            config["backend"] = backend
        if models is not None:
            if not isinstance(models, dict):
                raise Error("config.json: неверная секция models")
            if set(models) <= set(TIERS):
                # Плоский формат сохранён для совместимости старых конфигов Codex.
                if config["backend"] != "codex":
                    raise Error('config.json: плоский формат models допустим только при backend="codex"; '
                                'для opencode используйте вложенный формат {"codex"|"opencode": {...}}')
                models = {"codex": models}
            if set(models) - set(BACKENDS):
                raise Error("config.json: неверная секция models")
            for name, tiers in models.items():
                if not isinstance(tiers, dict) or set(tiers) - set(TIERS) \
                        or not all(isinstance(value, str) for value in tiers.values()):
                    raise Error(f"config.json: неверная секция models.{name}")
                config["models"][name].update(tiers)
        if model_sets is not None:
            if not isinstance(model_sets, dict) or set(model_sets) - set(BACKENDS):
                raise Error("config.json: неверная секция model_sets; допустимы codex и opencode")
            for backend_name, sets in model_sets.items():
                if not isinstance(sets, dict):
                    raise Error(f"config.json: неверная секция model_sets.{backend_name}")
                for name, tiers in sets.items():
                    if not isinstance(name, str) or not name or not isinstance(tiers, dict) \
                            or set(tiers) != set(TIERS) \
                            or not all(isinstance(value, str) and value for value in tiers.values()):
                        raise Error(f"config.json: неверная секция model_sets.{backend_name}: нужны наборы "
                                    "с непустыми моделями light, standard и heavy")
            config["model_sets"] = model_sets
        if semantic is not None:
            if not isinstance(semantic, dict) or set(semantic) - set(BACKENDS):
                raise Error("config.json: неверная секция semantic; допустимы ключи codex и opencode")
            for name, profile in semantic.items():
                if (not isinstance(profile, dict) or set(profile) - {"provider", "model", "cache"}
                        or profile.get("provider") not in BACKENDS
                        or not all(isinstance(profile.get(key), str) and profile[key].strip()
                                   for key in ("provider", "model", "cache"))):
                    raise Error(f"config.json: неверная секция semantic.{name}: "
                                "нужны provider (codex|opencode), model и cache")
            config["semantic"] = semantic
        if efforts is not None:
            if not isinstance(efforts, dict) or set(efforts) - set(TIERS) \
                    or not all(isinstance(value, str) for value in efforts.values()):
                raise Error("config.json: неверная секция efforts")
            config["efforts"].update(efforts)
            if config["backend"] == "opencode":
                print('ПРЕДУПРЕЖДЕНИЕ: efforts игнорируются при backend="opencode"; '
                      "вариант reasoning задаётся суффиксом #variant в строке модели", file=sys.stderr)
    if config["backend"] not in BACKENDS:
        raise Error('backend: допустимы "codex" и "opencode"')
    for key in ("task_timeout", "gate_timeout", "git_timeout"):
        if type(config[key]) is not int or config[key] <= 0:
            raise Error(f"{key}: нужно положительное число секунд")
    for key in ("restart_retries", "retry_delay"):
        if type(config[key]) is not int or config[key] < 0:
            raise Error(f"{key}: нужно неотрицательное целое число")
    if config["restart_retries"] > 20:
        raise Error("restart_retries: допустимы значения 0–20")
    if type(config["push"]) is not bool:
        raise Error("push: нужно true или false")
    for key in ("tasks_file", "progress_file", "branch", "remote", "codex_bin", "opencode_bin", "sandbox"):
        if not isinstance(config[key], str) or not config[key]:
            raise Error(f"{key}: нужна непустая строка")
    if config["sandbox"] not in ("read-only", "workspace-write", "danger-full-access"):
        raise Error("неверный sandbox")
    if not isinstance(config["gate_base"], list) or not all(isinstance(v, str) and v for v in config["gate_base"]):
        raise Error("gate_base: нужен массив аргументов, например [\"docker\", \"compose\", \"run\", \"--rm\", \"app\"]")
    if not isinstance(config["checks"], dict) or any(
            not isinstance(name, str) or not name or not isinstance(commands, list) or not commands
            or not all(isinstance(command, str) and command.strip() for command in commands)
            for name, commands in config["checks"].items()):
        raise Error("checks: нужен словарь «профиль → массив непустых shell-команд»")
    if "orchestrator" in config["checks"]:
        raise Error("checks: профиль orchestrator встроен в модуль и не переопределяется")
    if models_set is not None:
        sets = config["model_sets"].get(config["backend"], {})
        if models_set not in sets:
            available = ", ".join(sorted(sets)) or f"нет (добавьте model_sets.{config['backend']})"
            raise Error(f"--models: нет набора {models_set} для backend {config['backend']}; "
                        f"доступны: {available}")
        config["models"][config["backend"]].update(sets[models_set])
    tiers = config["models"][config["backend"]]
    # Дальше по коду models — плоский словарь tier→модель активного бэкенда,
    # semantic — профиль ИИ-оценок LEX для того же бэкенда.
    config["models"] = dict(tiers)
    config["semantic"] = config["semantic"].get(config["backend"])
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
                result[str(entry)] = [hashlib.sha256(entry.read_bytes()).hexdigest(), entry.stat().st_mode]
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


def run_logged(command, log, timeout, prompt=None, lock_fd=None, env=None):
    # Prompt через stdin: нет ограничения длины аргументов и shell-интерполяции.
    with log.open("wb") as output, tempfile.TemporaryFile() as input_file:
        if prompt is not None:
            input_file.write(prompt.encode())
            input_file.seek(0)
        process = subprocess.Popen(command, stdin=input_file, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True, env=env,
                                   pass_fds=() if lock_fd is None else (lock_fd,))
        try:
            code = process.wait(timeout=timeout)
            if code:
                stop_group(process)
            return code
        except subprocess.TimeoutExpired:
            stop_group(process)
            raise RetryableError(f"таймаут; лог: {log}")
        except KeyboardInterrupt:
            stop_group(process)
            raise Error(f"остановка; лог: {log}; повторный запуск продолжит задачу")


def tail(path, limit=12000):
    if not path.exists():
        return "(нет вывода)"
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - limit))
        return stream.read().decode("utf-8", "replace")


def agent_start_command(config, model, effort, last):
    """Команда запуска агента; промпт для обоих бэкендов подаётся через stdin (run_logged).

    Для codex команда байт-в-байт прежняя. Для opencode права задаёт `permissions`
    в opencode.json целевого проекта: оркестратор не передаёт ни песочницу, ни --auto.
    Пустая строка модели = модель из настроек CLI.
    """
    if config["backend"] == "codex":
        command = [config["codex_bin"], "exec"]
        if model:
            command += ["--model", model]
        return [*command, "-c", "model_reasoning_effort=" + json.dumps(effort),
                "--sandbox", config["sandbox"], "--output-last-message", str(last), "-"]
    command = [config["opencode_bin"], "run", "--agent", "build", "--format", "json"]
    if model:
        command += ["--model", model]
    return command


def semantic_env(config, root):
    """Окружение агента: провайдер, модель и кэш ИИ-оценок активного бэкенда.

    Значения берутся из секции semantic конфига, поэтому выбранный бэкенд
    определяет и исполнителя LEX-оценок, и каталог кэша. Задачи не выбирают
    провайдера сами и не смешивают кэши разных моделей.
    """
    env = dict(os.environ)
    profile = config.get("semantic")
    if profile:
        env["CATEGORY_PROVIDER"] = profile["provider"]
        env["CATEGORY_MODEL"] = profile["model"]
        env["SEMANTIC_CACHE"] = str((root / profile["cache"]).resolve())
    return env


def extract_opencode_reply(log):
    """Финальный ответ OpenCode: поле part.text последнего text-event потока --format json."""
    reply = None
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped.startswith("{"):
            continue
        try:
            event = json.loads(stripped)
        except ValueError:
            continue
        part = event.get("part") if isinstance(event, dict) else None
        if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str):
            reply = part["text"]
    return reply


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


def progress_content(path, task, summary, manual, model, effort):
    content = path.read_text(encoding="utf-8") if path.exists() else "# Журнал задач\n"
    record = {"id": task.id, "fingerprint": task.fingerprint(),
              "date": datetime.now(timezone.utc).isoformat(timespec="seconds"),
              "gate": "skipped" if task.gate == "skip" else "passed",
              "manual": manual, "summary": summary, "model": model or "default", "effort": effort}
    content += f"\n- [{task.id}] ✓ {record['date']} — {task.title} — {summary}\n"
    if task.gate == "skip":
        content += "  Проверка: ПРОПУЩЕНА (gate: skip).\n"
    content += "<!-- orchestrator:v1 " + json.dumps(record, ensure_ascii=False).replace("-->", "--\\u003e") + " -->\n"
    return content


def atomic_text(path, content, directory):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory,
                                         prefix=".checkpoint-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        # Сохраняем также rename перед возможным отключением питания.
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def read_checkpoint(path, root):
    if path.is_symlink():
        raise Error("checkpoint.json не должен быть symlink")
    if not path.exists():
        return None
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
        if (type(state["version"]) is not int or state["version"] != 1 or state["root"] != str(root)
                or state["phase"] not in ("agent", "gate", "commit", "push")
                or any(not isinstance(state[key], str) for key in
                       ("task", "branch", "logs", "summary", "model", "effort"))
                or ("backend" in state and state["backend"] not in BACKENDS)
                or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", state["task"])
                or type(state["manual"]) is not bool
                or not isinstance(state["baseline"], dict)
                or not re.fullmatch(r"[0-9a-f]{40,64}", state["head"])
                or not re.fullmatch(r"[0-9a-f]{64}", state["fingerprint"])
                or any(type(state[key]) is not int or state[key] < 0
                       for key in ("attempt", "exec_runs", "gate_runs", "commit_runs", "push_runs"))):
            raise ValueError("неверная версия, проект или поля")
        if state["phase"] == "commit" and (not isinstance(state["progress_after"], str)
                or state["progress_before"] is not None and not isinstance(state["progress_before"], str)):
            raise ValueError("неверные поля журнала")
        return state
    except (KeyError, TypeError, ValueError) as error:
        raise Error(f"повреждена контрольная точка {path}: {error}; правки сохранены") from error


def retry_wait(log, default, now=None):
    """Время сброса лимита из CLI задано в локальном часовом поясе машины."""
    match = re.search(r"try again at ([A-Za-z]{3}) (\d{1,2})(?:st|nd|rd|th)?, (\d{4}) "
                      r"(\d{1,2}):(\d{2}) (AM|PM)", tail(log), re.IGNORECASE)
    if match:
        month, day, year, hour, minute, meridiem = match.groups()
        months = "jan feb mar apr may jun jul aug sep oct nov dec".split()
        try:
            if not 1 <= int(hour) <= 12:
                return default
            reset = datetime(int(year), months.index(month.lower()) + 1, int(day),
                             int(hour) % 12 + (12 if meridiem.upper() == "PM" else 0), int(minute)).astimezone()
            return max(default, reset.timestamp() - (now or datetime.now(timezone.utc)).timestamp() + 5)
        except ValueError:
            pass
    return default


def wait_for_retry(delay):
    deadline = time.monotonic() + delay
    while time.monotonic() < deadline:
        time.sleep(min(60, max(0, deadline - time.monotonic())))


def execute(args):
    script_dir = Path(__file__).resolve().parent
    config_path = (Path.cwd() / args.config).absolute() if args.config else script_dir / "config.json"
    config = load_config(config_path, backend=args.backend, models_set=args.models)
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
        profile = config.get("semantic")
        print("ИИ-оценки LEX: " + (f"{profile['provider']} / {profile['model']}; кэш {profile['cache']}"
                                    if profile else "не заданы (секция semantic пуста)"))
        for task in selected:
            status = "done" if task.id in records else "pending"
            print(f"{task.id} | {task.tier} | {status} | {config['models'][task.tier] or 'default'}/{config['efforts'][task.tier]} | {task.title}")
            if args.dry_run:
                print(f"  gate: {task.gate}; retries: {task.retries}")
        return
    pending = [task for task in selected if task.id not in records]
    if args.mark_done and not pending and not (work / "checkpoint.json").exists():
        raise Error(f"{args.mark_done}: уже выполнено")
    if not pending and not (work / "checkpoint.json").exists():
        if records:
            relative_progress = str(progress_path.relative_to(root))
            if not git("ls-files", "--", relative_progress) or git("diff", "HEAD", "--", relative_progress):
                raise Error("журнал содержит незакоммиченные результаты; сначала завершите или исправьте предыдущий коммит")
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
        run_queue(args, config, root, script_dir, config_path, tasks_path, progress_path, selected, lock.fileno())


def run_queue(args, config, root, script_dir, config_path, tasks_path, progress_path, selected, lock_fd):
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
    checkpoint = work / "checkpoint.json"
    state = read_checkpoint(checkpoint, root)
    records = read_progress(progress_path)
    active = None
    if state:
        active = next((task for task in selected if task.id == state["task"]), None)
        if active is None:
            raise Error(f"сначала продолжите незавершённую задачу {state['task']}")
        if active.fingerprint() != state["fingerprint"]:
            raise Error(f"{active.id}: незавершённая задача изменена; контрольная точка сохранена")
        if state["branch"] != config["branch"] or current_branch != state["branch"]:
            raise Error("изменена Git-история или ветка незавершённой задачи")
        if state.get("blocked") and not args.mark_done:
            raise Error(f"{active.id}: {state['blocked']}; требуется решение владельца или --mark-done после исправления")
    if records:
        relative_progress = str(progress_path.relative_to(root))
        uncommitted = not git("ls-files", "--", relative_progress) or git("diff", "HEAD", "--", relative_progress)
        own_journal = (state and state["phase"] == "commit"
                       and progress_path.read_text(encoding="utf-8") == state["progress_after"])
        if uncommitted and not own_journal:
            raise Error("журнал содержит незакоммиченные результаты; сначала завершите или исправьте предыдущий коммит")
    if args.mark_done:
        if current_branch != config["branch"]:
            raise Error("--mark-done запускается в настроенной ветке задач")
    elif not state and git("status", "--porcelain"):
        raise Error("рабочее дерево должно быть чистым; сохраните изменения коммитом перед запуском; "
                    "автовозобновление допускает только правки задачи с контрольной точкой")
    if git("var", "GIT_AUTHOR_IDENT", check=False) == "" or git("var", "GIT_COMMITTER_IDENT", check=False) == "":
        raise Error("настройте git user.name и user.email")
    if config["push"]:
        git("remote", "get-url", config["remote"])
    for path in (tasks_path, config_path, script_dir / "orchestrator.py",
                 script_dir / "run.sh", script_dir / "checks.py"):
        if path.exists() and not git("ls-files", "--", str(path.relative_to(root))):
            raise Error(f"добавьте в Git: {path.relative_to(root)}")
    if not args.mark_done and current_branch != config["branch"]:
        exists = git("show-ref", "--verify", f"refs/heads/{config['branch']}", check=False)
        if exists:
            raise Error(f"ветка {config['branch']} уже существует; выберите её вручную или задайте BRANCH")
        git("checkout", "-b", config["branch"])
    protected = [script_dir, tasks_path, progress_path, config_path]
    display_paths = list(dict.fromkeys(str(path.relative_to(root)) for path in protected))
    pending = [task for task in selected if task.id not in records and task != active]
    if active:
        pending.insert(0, active)
    profile = config.get("semantic")
    if profile:
        print(f"ИИ-оценки LEX: {profile['provider']} / {profile['model']}; кэш {profile['cache']}", flush=True)
    agent_environment = semantic_env(config, root)
    for task in pending:
        print(f"[{task.id}] {task.title}", flush=True)
        resumed = state is not None
        if state is None:
            logs = work / "logs" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
            logs.mkdir(parents=True)
            state = {"version": 1, "root": str(root), "branch": config["branch"], "task": task.id,
                     "fingerprint": task.fingerprint(), "head": git("rev-parse", "HEAD"),
                     "baseline": snapshot(protected, excluded=[work]), "phase": "gate" if args.mark_done else "agent",
                     "attempt": 0, "exec_runs": 0, "gate_runs": 0, "commit_runs": 0, "push_runs": 0,
                     "logs": str(logs.relative_to(root)), "summary": "Выполнено вручную; проверка запущена оркестратором.",
                     "manual": bool(args.mark_done), "backend": config["backend"],
                     "model": config["models"][task.tier], "effort": config["efforts"][task.tier]}
        logs = local_path(root, state["logs"])
        if not logs.is_relative_to(work / "logs") or not logs.is_dir():
            raise Error("контрольная точка ссылается на недопустимый каталог логов")

        def save():
            atomic_text(checkpoint, json.dumps(state, ensure_ascii=False, indent=2) + "\n", work)

        def verify(check_head=True):
            actual = snapshot(protected, excluded=[work])
            expected = dict(state["baseline"])
            if state["phase"] == "commit":
                current = progress_path.read_text(encoding="utf-8") if progress_path.exists() else None
                if current not in (state["progress_before"], state["progress_after"]):
                    raise Error(f"{task.id}: изменены защищённые файлы: журнал")
                expected[str(progress_path)] = actual[str(progress_path)]
            if actual != expected:
                raise Error(f"{task.id}: изменены защищённые файлы; изменения оставлены без коммита")
            if (git("symbolic-ref", "--short", "HEAD") != state["branch"]
                    or check_head and git("rev-parse", "HEAD") != state["head"]):
                raise Error(f"{task.id}: изменена Git-история или ветка; остановка")

        def confirm_commit():
            verify(check_head=False)
            parents = git("rev-list", "--parents", "-n", "1", "HEAD").split()
            if (parents[1:] != [state["head"]] or git("rev-parse", "HEAD^{tree}") != state.get("tree")
                    or progress_path.read_text(encoding="utf-8") != state["progress_after"]):
                raise Error(f"{task.id}: изменена Git-история; не удалось подтвердить собственный коммит")
            state.update(phase="push", head=git("rev-parse", "HEAD"), baseline=snapshot(protected, excluded=[work]))
            save()

        # Коммит мог успеть завершиться перед остановкой родительского процесса.
        if state["phase"] == "commit" and git("rev-parse", "HEAD") != state["head"]:
            confirm_commit()
        verify()
        if resumed:
            print(f"  продолжение: этап {state['phase']}; правки и логи сохранены", flush=True)
        if args.mark_done and state["phase"] in ("agent", "gate"):
            state.update(phase="gate", manual=True, blocked=None)
        save()
        if state.get("backend") != config["backend"]:
            previous = state["model"]
            state.update(backend=config["backend"], model=config["models"][task.tier],
                         effort=config["efforts"][task.tier])
            save()
            if resumed:
                print(f"  модель пересчитана под backend {config['backend']} (tier {task.tier}): "
                      f"{previous or 'из настроек CLI'} → {state['model'] or 'из настроек CLI'}", flush=True)
        model, effort = state["model"], state["effort"]
        if resumed and args.models and not args.mark_done and model != config["models"][task.tier]:
            print(f"ПРЕДУПРЕЖДЕНИЕ: {task.id} продолжается с сохранённой моделью "
                  f"{model or 'из настроек CLI'}; --models {args.models} применится к следующим задачам",
                  file=sys.stderr, flush=True)
        context = "\n".join(f"{r['id']}: {r.get('summary', '')}" for r in list(records.values())[-15:])
        base_prompt = prompt_for(task, context, display_paths)
        last = logs / f"{task.id}.last.md"

        def run_step(kind, command_for, timeout, prompt_for_run=None, failure_on_exit=False, env=None):
            for restart in range(config["restart_retries"] + 1):
                number = state[kind + "_runs"]
                state[kind + "_runs"] += 1
                log = logs / f"{task.id}.{kind}.{number}.log"
                prompt = prompt_for_run() if prompt_for_run else None
                state["last_log"] = str(log.relative_to(root))
                save()  # Всегда до запуска дочернего процесса.
                try:
                    try:
                        code = run_logged(command_for(), log, timeout, prompt, lock_fd, env=env)
                    finally:
                        verify()
                    if kind == "exec" and config["backend"] == "opencode":
                        # Codex пишет финальный ответ сама (--output-last-message);
                        # для OpenCode ответ извлекается из лога --format json.
                        # Пустой поток не затирает предыдущий ответ: он нужен контекстом повтора.
                        reply = extract_opencode_reply(log)
                        if reply is not None or not last.exists():
                            last.write_text(reply or "", encoding="utf-8")
                    if kind == "exec" and last.exists():
                        blocked = re.search(r"^BLOCKED:.*", last.read_text(encoding="utf-8"), re.MULTILINE)
                        if blocked:
                            state["blocked"] = blocked[0]
                            save()
                            raise Error(f"{task.id}: {blocked[0]}; агент не подтвердил завершение; см. {last}")
                    if code and failure_on_exit:
                        raise RetryableError(f"{task.id}: {kind} завершился с кодом {code}; лог: {log}")
                    if kind == "exec" and (not last.exists() or not last.read_text(encoding="utf-8").strip()):
                        raise RetryableError(f"{task.id}: пустой ответ агента; лог: {log}")
                    return code, log
                except (RetryableError, OSError) as error:
                    state["error"] = str(error)
                    save()
                    if restart == config["restart_retries"]:
                        raise Error(f"{error}; повторы исчерпаны; повторный запуск продолжит эту задачу") from error
                    delay = retry_wait(log, config["retry_delay"])
                    print(f"  {error}; повтор {restart + 1}/{config['restart_retries']} через {delay:.0f} с", flush=True)
                    wait_for_retry(delay)

        while state["phase"] in ("agent", "gate", "commit"):
            if state["phase"] == "agent":
                agent_bin = config["codex_bin"] if config["backend"] == "codex" else config["opencode_bin"]
                if shutil.which(agent_bin) is None:
                    raise Error(f"не найден исполняемый файл агента: {agent_bin}")
                def agent_prompt():
                    previous = tail(last)
                    last.unlink(missing_ok=True)
                    prompt = base_prompt
                    if resumed or state["exec_runs"] > 1:
                        previous_log = local_path(root, state["last_log"]) if state.get("last_log") else None
                        prompt += ("\nЭто продолжение незавершённой задачи, возможно повторная попытка после сбоя. "
                                   "Предыдущие изменения остались в рабочем дереве. Изучи git status, diff и новые файлы; "
                                   "сохрани полезную работу, не начинай с удаления правок.\n"
                                   f"Предыдущий ответ:\n{previous}\nПричина/проверка: {state.get('error', '')}\n"
                                   f"Последний вывод:\n{tail(previous_log) if previous_log else '(нет вывода)'}\n")
                    return prompt

                def agent_command():
                    return agent_start_command(config, model, effort, last)

                # Отсутствие last.md равнозначно пустому ответу, но не старому успеху.
                last.touch(exist_ok=True)
                run_step("exec", agent_command, config["task_timeout"], agent_prompt, failure_on_exit=True,
                         env=agent_environment)
                reply = last.read_text(encoding="utf-8") if last.exists() else ""
                state["summary"] = " ".join(next((line for line in reply.splitlines() if line.startswith("ИТОГ:")),
                                                 reply.splitlines()[0]).split())[:300]
                state["phase"] = "gate"
                save()
            if task.gate == "skip":
                print("  gate: ПРОПУЩЕНА по TASKS.md", flush=True)
                code, gate_log = 0, None
            else:
                print(f"  gate (попытка {state['attempt'] + 1}): {task.gate}", flush=True)
                code, gate_log = run_step("gate", lambda: [*config["gate_base"], "sh", "-c", task.gate],
                                          config["gate_timeout"])
            if code:
                state["error"] = f"Gate завершился с кодом {code}. Последний вывод:\n{tail(gate_log)}"
                if args.mark_done or state["manual"] or state["attempt"] >= task.retries or state["phase"] == "commit":
                    save()
                    raise Error(f"{task.id}: gate завершился с кодом {code}; нет коммита. Лог: {gate_log}")
                state["attempt"] += 1
                state["phase"] = "agent"
                save()
                continue
            if task.gate != "skip":
                print("  gate: OK", flush=True)
            verify()
            if state["phase"] != "commit":
                state.update(phase="commit", progress_before=progress_path.read_text(encoding="utf-8") if progress_path.exists() else None,
                             progress_after=progress_content(progress_path, task, state["summary"], state["manual"], model, effort))
                save()
            atomic_text(progress_path, state["progress_after"], work)
            verify()
            git("add", "-A", "--", ".")
            state["tree"] = git("write-tree")
            state["commit_runs"] += 1
            save()
            commit_log = logs / f"{task.id}.commit.{state['commit_runs']}.log"
            code = run_logged(["git", "commit", "-m", f"{task.id}: {task.title}"], commit_log,
                              config["git_timeout"], lock_fd=lock_fd)
            if code:
                raise Error(f"git commit: код {code}; лог: {commit_log}; повторный запуск повторит gate и коммит")
            confirm_commit()
            print(f"  commit: {git('rev-parse', '--short', 'HEAD')}", flush=True)
        verify()
        if git("status", "--porcelain"):
            raise Error("после коммита остались изменения (возможно, Git hook); разберите их вручную")
        if config["push"]:
            try:
                run_step("push", lambda: ["git", "push", "-u", config["remote"], f"HEAD:refs/heads/{config['branch']}"],
                         config["git_timeout"], failure_on_exit=True)
            except Error as error:
                raise Error(f"push не прошёл; коммит {task.id} сохранён локально. Повторный запуск повторит push. {error}") from error
        verify()
        if git("status", "--porcelain"):
            raise Error("после push остались изменения; разберите их вручную")
        checkpoint.unlink()
        state = None
        records = read_progress(progress_path)
    print(f"Готово. Журнал: {progress_path.relative_to(root)}; логи: {work.relative_to(root) / 'logs'}")


def main():
    # SIGTERM, как и Ctrl+C, должен остановить активную группу дочерних процессов.
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", help="JSON-конфигурация; по умолчанию orchestrator/config.json")
    parser.add_argument("--backend", choices=BACKENDS,
                        help="бэкенд агента (codex или opencode); перекрывает config.json")
    parser.add_argument("--models", metavar="SET",
                        help="набор моделей из config.json по ключу backend "
                             "(model_sets.codex|opencode, например cheap); действует один запуск")
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
