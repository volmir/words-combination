"""Интеграционные проверки с временными Git-репозиториями и заглушками Codex/OpenCode."""
import contextlib
import fcntl
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch


PACKAGE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("orchestrator", PACKAGE / "orchestrator.py")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

MOCK = r'''#!/usr/bin/env python3
import json, os, re, subprocess, sys, time
from pathlib import Path
args = sys.argv[1:]
assert args[0] == 'exec' and args[-1] == '-'
assert 'resume' not in args
prompt = sys.stdin.read()
task = re.search(r'одно задание: ([A-Za-z0-9_-]+):', prompt)[1]
work = Path('orchestrator/.runtime'); work.mkdir(exist_ok=True)
(work / 'worker.pid').write_text(str(os.getpid()))
with (work / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps({'id': task, 'args': args, 'prompt': prompt,
                            'env': {key: os.environ.get(key) for key in
                                    ('CATEGORY_PROVIDER', 'CATEGORY_MODEL', 'SEMANTIC_CACHE')}}) + '\n')
count_file = work / ('count-' + task)
count = int(count_file.read_text()) + 1 if count_file.exists() else 1
count_file.write_text(str(count))
mode = os.environ.get('MOCK_MODE', 'normal')
if mode == 'timeout':
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
    (work / 'child.pid').write_text(str(child.pid))
    time.sleep(30)
if mode == 'tamper':
    Path('orchestrator/injected.txt').write_text('changed')
if mode == 'edit-tasks':
    with Path('orchestrator/TASKS.md').open('a') as f: f.write('\nchanged\n')
if mode == 'edit-progress':
    Path('orchestrator/PROGRESS.md').write_text('changed')
if mode == 'transient' and count > 1:
    assert Path('result-' + task + '.txt').read_text() == 'good', 'previous work lost'
if mode not in ('nochange', 'blocked'):
    Path('result-' + task + '.txt').write_text('bad' if mode == 'retry' and count == 1 else 'good')
if mode == 'interrupt-once' and count == 1 and task == os.environ.get('MOCK_INTERRUPT_TASK', task):
    import signal
    os.kill(os.getppid(), signal.SIGTERM)
    time.sleep(30)
if mode == 'transient' and count == 1:
    print('temporary service unavailable', flush=True)
    sys.exit(1)
if mode == 'timeout-once' and count == 1:
    time.sleep(30)
if mode == 'commit':
    subprocess.run(['git', 'add', '-A'], check=True)
    subprocess.run(['git', 'commit', '-qm', 'unexpected agent commit'], check=True)
if mode == 'fail': sys.exit(7)
out = Path(args[args.index('--output-last-message') + 1])
if mode != 'empty' and not (mode == 'empty-once' and count == 1):
    out.write_text('BLOCKED: нет данных' if mode in ('blocked', 'blocked-error') else 'ИТОГ: ' + 'Привет 🛠 ' * 80 + '\nПРОВЕРЕНО: заглушка\n', encoding='utf-8')
if mode == 'blocked-error': sys.exit(7)
'''

MOCK_OPENCODE = r'''#!/usr/bin/env python3
import json, os, re, signal, sys, time
from pathlib import Path
args = sys.argv[1:]
assert args[:5] == ['run', '--agent', 'build', '--format', 'json'], args
if '--model' in args:
    assert args[args.index('--model') + 1], 'пустая модель не должна передаваться'
assert '--auto' not in args, 'оркестратор не передаёт --auto'
prompt = sys.stdin.read()
task = re.search(r'одно задание: ([A-Za-z0-9_-]+):', prompt)[1]
work = Path('orchestrator/.runtime'); work.mkdir(exist_ok=True)
with (work / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps({'id': task, 'args': args, 'prompt': prompt,
                            'env': {key: os.environ.get(key) for key in
                                    ('CATEGORY_PROVIDER', 'CATEGORY_MODEL', 'SEMANTIC_CACHE')}}) + '\n')
mode = os.environ.get('MOCK_MODE', 'normal')
marker = work / ('interrupted-' + task)
if mode == 'interrupt-once' and os.environ.get('MOCK_INTERRUPT_TASK') == task and not marker.exists():
    marker.write_text('1')
    os.kill(os.getppid(), signal.SIGTERM)
    time.sleep(30)
if mode not in ('blocked', 'empty', 'fail'):
    Path('result-' + task + '.txt').write_text('good')
def event(kind, part):
    print(json.dumps({'type': kind, 'part': part}), flush=True)
event('step_start', {'id': 'p1', 'type': 'step-start'})
event('tool_use', {'id': 'p2', 'type': 'tool', 'tool': 'read'})
event('step_finish', {'id': 'p3', 'type': 'step-finish'})
if mode == 'fail':
    print('provider error', flush=True)
    sys.exit(7)
if mode != 'empty':
    event('text', {'id': 'p4', 'type': 'text', 'text': 'промежуточное сообщение'})
    reply = 'BLOCKED: нет данных' if mode == 'blocked' else 'ИТОГ: ' + 'Привет 🛠 ' * 80 + '\nПРОВЕРЕНО: заглушка\n'
    event('text', {'id': 'p5', 'type': 'text', 'text': reply})
'''


def task(task_id="A-1", gate="test -f result-A-1.txt", retries=1, tier="standard", prompt="Сделай задачу."):
    return f"## {task_id}: Название {task_id}\ntier: {tier}\ngate: {gate}\nretries: {retries}\n```prompt\n{prompt}\n```\n"


def copy_test_package(source, destination):
    # Рабочий журнал относится к очереди проекта, а не к тестовой фикстуре.
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns('__pycache__', '.runtime', 'PROGRESS.md'))


class FixtureTests(unittest.TestCase):
    def test_usage_limit_retry_uses_reported_reset_and_falls_back_for_unknown_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'exec.log'
            # CLI выводит время в локальном часовом поясе, без обозначения зоны.
            reset = datetime(2026, 11, 10, 1, 48).astimezone()
            log.write_text('ERROR: usage limit. try again at Nov 10th, 2026 1:48 AM.\n')
            self.assertEqual(module.retry_wait(log, 30, now=reset - timedelta(hours=2)), 7205)
            self.assertEqual(module.retry_wait(log, 30, now=reset + timedelta(hours=2)), 30)
            log.write_text('temporary service unavailable')
            self.assertEqual(module.retry_wait(log, 30, now=datetime(2026, 1, 1, tzinfo=timezone.utc)), 30)

    def test_retry_settings_reject_invalid_values(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            config = Path(directory) / 'config.json'
            for values in ({'restart_retries': -1}, {'restart_retries': 21}, {'restart_retries': True},
                           {'retry_delay': -1}, {'retry_delay': 0.5}):
                with self.subTest(values=values):
                    config.write_text(json.dumps(values))
                    with self.assertRaises(module.Error):
                        module.load_config(config)

    def test_load_config_validates_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            for body in ({'checks': {'probe': []}},
                         {'checks': {'probe': 'true'}},
                         {'checks': {'probe': [42]}},
                         {'checks': {'probe': ['   ']}},
                         {'checks': {'': ['true']}},
                         {'checks': {'orchestrator': ['true']}}):
                with self.subTest(body=body):
                    config.write_text(json.dumps(body))
                    with self.assertRaises(module.Error):
                        module.load_config(config)
            config.write_text(json.dumps({'checks': {'probe': ['true', 'false']}}))
            loaded = module.load_config(config)
            self.assertEqual(loaded['checks'], {'probe': ['true', 'false']})
            self.assertEqual(module.load_config(Path(directory) / 'missing.json')['checks'], {})

    def test_load_config_selects_backend_section_of_nested_models(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({
                'backend': 'opencode',
                'models': {'opencode': {'light': 'prov/m-free'}, 'codex': {'light': 'c-only'}},
            }))
            loaded = module.load_config(config)
            self.assertEqual(loaded['models'],
                             {'light': 'prov/m-free',
                              'standard': module.OPENCODE_MODEL_DEFAULTS['standard'],
                              'heavy': module.OPENCODE_MODEL_DEFAULTS['heavy']})
            config.write_text(json.dumps({'models': {'light': 'flat-codex'}}))
            loaded = module.load_config(config)
            self.assertEqual(loaded['models']['light'], 'flat-codex')
            self.assertEqual(loaded['models']['standard'], module.CODEX_MODEL_DEFAULTS['standard'])

    def test_load_config_rejects_invalid_models_and_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            for body in ({'backend': 'opencode', 'models': {'light': 'x'}},
                         {'models': {'unknown': {}}},
                         {'models': {'codex': {'tiny': 'x'}}},
                         {'models': {'codex': {'light': 5}}},
                         {'backend': 'claude'},
                         {'efforts': {'light': 5}}):
                with self.subTest(body=body):
                    config.write_text(json.dumps(body))
                    with self.assertRaises(module.Error):
                        module.load_config(config)

    def test_load_config_backend_override_selects_models_section(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({
                'backend': 'codex',
                'models': {'codex': {'light': 'c-only'}, 'opencode': {'light': 'o-only'}},
            }))
            self.assertEqual(module.load_config(config, backend='opencode')['models']['light'], 'o-only')
            self.assertEqual(module.load_config(config, backend='codex')['models']['light'], 'c-only')
            config.write_text(json.dumps({'models': {'light': 'flat-codex'}}))
            with self.assertRaises(module.Error):
                module.load_config(config, backend='opencode')
            self.assertEqual(module.load_config(config, backend='codex')['models']['light'], 'flat-codex')
            with self.assertRaises(module.Error):
                module.load_config(config, backend='claude')

    def test_load_config_selects_named_model_set(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({
                'backend': 'opencode',
                'models': {'opencode': {'light': 'free-l', 'standard': 'free-s', 'heavy': 'free-h'}},
                'model_sets': {'opencode': {'paid': {
                    'light': 'paid-l', 'standard': 'paid-s', 'heavy': 'paid-h'}}},
            }))
            self.assertEqual(module.load_config(config)['models'],
                             {'light': 'free-l', 'standard': 'free-s', 'heavy': 'free-h'})
            self.assertEqual(module.load_config(config, models_set='paid')['models'],
                             {'light': 'paid-l', 'standard': 'paid-s', 'heavy': 'paid-h'})
            self.assertEqual(module.load_config(Path(directory) / 'missing.json')['model_sets'], {})

    def test_load_config_rejects_invalid_model_sets(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            for body in ({'model_sets': []},
                         {'model_sets': {'other': {'paid': {'light': 'a', 'standard': 'b', 'heavy': 'c'}}}},
                         {'model_sets': {'opencode': {'paid': {'light': 'a', 'standard': 'b'}}}},
                         {'model_sets': {'opencode': {'paid': {'light': 'a', 'standard': 'b', 'heavy': ''}}}},
                         {'model_sets': {'opencode': {'paid': {'light': 'a', 'standard': 5, 'heavy': 'c'}}}},
                         {'model_sets': {'opencode': {'': {'light': 'a', 'standard': 'b', 'heavy': 'c'}}}},
                         {'model_sets': {'opencode': {'paid': {'light': 'a', 'standard': 'b', 'heavy': 'c',
                                                               'tiny': 'x'}}}},
                         {'model_sets': {'opencode': {'paid': 'x'}}}):
                with self.subTest(body=body):
                    config.write_text(json.dumps(body))
                    with self.assertRaises(module.Error):
                        module.load_config(config)

    def test_models_option_uses_sets_of_the_active_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'model_sets': {
                'opencode': {'paid': {'light': 'paid-l', 'standard': 'paid-s', 'heavy': 'paid-h'}},
                'codex': {'cheap': {'light': 'cheap-l', 'standard': 'cheap-s', 'heavy': 'cheap-h'}}}}))
            with self.assertRaises(module.Error):
                module.load_config(config, models_set='paid')  # backend по умолчанию codex
            self.assertEqual(module.load_config(config, backend='opencode', models_set='paid')['models']['heavy'],
                             'paid-h')
            self.assertEqual(module.load_config(config, backend='codex', models_set='cheap')['models']['heavy'],
                             'cheap-h')
            with self.assertRaises(module.Error) as caught:
                module.load_config(config, backend='opencode', models_set='free')
            self.assertIn('paid', str(caught.exception))
            config.write_text('{}')
            with self.assertRaises(module.Error):
                module.load_config(config, backend='opencode', models_set='paid')

    def test_semantic_profile_is_validated_and_selected_by_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'semantic': {
                'codex': {'provider': 'codex', 'model': 'gpt-6.1-sol', 'cache': '.local/data01-cache'},
                'opencode': {'provider': 'opencode', 'model': 'opencode/flash#max',
                             'cache': '.local/data01-oc-cache'}}}))
            self.assertEqual(module.load_config(config, backend='codex')['semantic']['model'], 'gpt-6.1-sol')
            self.assertEqual(module.load_config(config, backend='opencode')['semantic']['cache'],
                             '.local/data01-oc-cache')
            for body in ({'semantic': []},
                         {'semantic': {'other': {'provider': 'codex', 'model': 'm', 'cache': 'c'}}},
                         {'semantic': {'codex': {'provider': 'other', 'model': 'm', 'cache': 'c'}}},
                         {'semantic': {'codex': {'provider': 'codex', 'model': '', 'cache': 'c'}}},
                         {'semantic': {'codex': {'provider': 'codex', 'model': 'm'}}}):
                with self.subTest(body=body):
                    config.write_text(json.dumps(body))
                    with self.assertRaises(module.Error):
                        module.load_config(config, backend='codex')

    def test_load_config_ignores_environment_variables(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(os.environ, {'MODEL_LIGHT': 'from-env', 'CODEX_BIN': '/env/codex',
                                        'TASK_TIMEOUT': '99', 'NO_PUSH': '1', 'BRANCH': 'env-branch',
                                        'GATE_BASE': 'env arg'}):
            config = Path(directory) / 'config.json'
            config.write_text('{}')
            loaded = module.load_config(config)
            self.assertEqual(loaded['models']['light'], module.CODEX_MODEL_DEFAULTS['light'])
            self.assertEqual(loaded['codex_bin'], 'codex')
            self.assertEqual(loaded['task_timeout'], module.DEFAULTS['task_timeout'])
            self.assertTrue(loaded['push'])
            self.assertEqual(loaded['branch'], module.DEFAULTS['branch'])
            self.assertEqual(loaded['gate_base'], [])

    def test_load_config_warns_efforts_ignored_for_opencode(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'backend': 'opencode', 'efforts': {'light': 'high'}}))
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                module.load_config(config)
            self.assertIn('efforts игнорируются', stderr.getvalue())
            config.write_text(json.dumps({'backend': 'opencode'}))
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                module.load_config(config)
            self.assertNotIn('efforts', stderr.getvalue())

    def test_codex_command_is_byte_identical_to_previous_version(self):
        config = {'backend': 'codex', 'codex_bin': 'codex', 'sandbox': 'workspace-write'}
        command = module.agent_start_command(config, 'm-1', 'high', Path('last.md'))
        self.assertEqual(command, ['codex', 'exec', '--model', 'm-1', '-c', 'model_reasoning_effort="high"',
                                   '--sandbox', 'workspace-write', '--output-last-message', 'last.md', '-'])
        command = module.agent_start_command(config, '', 'low', Path('last.md'))
        self.assertEqual(command, ['codex', 'exec', '-c', 'model_reasoning_effort="low"',
                                   '--sandbox', 'workspace-write', '--output-last-message', 'last.md', '-'])

    def test_opencode_command_shape(self):
        config = {'backend': 'opencode', 'opencode_bin': 'opencode'}
        self.assertEqual(module.agent_start_command(config, 'prov/m#max', 'high', Path('last.md')),
                         ['opencode', 'run', '--agent', 'build', '--format', 'json', '--model', 'prov/m#max'])
        self.assertEqual(module.agent_start_command(config, '', 'high', Path('last.md')),
                         ['opencode', 'run', '--agent', 'build', '--format', 'json'])

    def test_extract_opencode_reply_takes_last_text_event(self):
        self.assertEqual(module.extract_opencode_reply(PACKAGE / 'tests/fixtures/opencode_run_sample.jsonl'),
                         'ИТОГ: ок')
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'log'
            log.write_text('\n'.join([
                'служебная не-JSON строка',
                json.dumps({'type': 'step_start', 'part': {'type': 'step-start'}}),
                json.dumps({'type': 'text', 'part': {'type': 'text', 'text': 'промежуточное'}}),
                json.dumps({'type': 'text', 'part': {'type': 'text', 'text': 'ИТОГ: последний'}}),
            ]) + '\n')
            self.assertEqual(module.extract_opencode_reply(log), 'ИТОГ: последний')
            log.write_text(json.dumps({'type': 'step_finish', 'part': {'type': 'step-finish'}}) + '\n')
            self.assertIsNone(module.extract_opencode_reply(log))

    def test_copy_starts_without_existing_run_state_and_preserves_source(self):
        with tempfile.TemporaryDirectory(prefix='orchestrator fixture ') as temp:
            source = Path(temp) / 'source'
            destination = Path(temp) / 'destination'
            source.mkdir()
            (source / 'run.sh').write_text('#!/bin/sh\n')
            progress = source / 'PROGRESS.md'
            progress.write_text('# Журнал\n\n- [DONE-1] Выполнено\n', encoding='utf-8')
            runtime = source / '.runtime'
            runtime.mkdir()
            (runtime / 'calls.jsonl').write_text('{"id": "DONE-1"}\n')
            before = {p.relative_to(source): p.read_bytes() for p in source.rglob('*') if p.is_file()}

            copy_test_package(source, destination)

            self.assertEqual((destination / 'run.sh').read_bytes(), (source / 'run.sh').read_bytes())
            self.assertFalse((destination / 'PROGRESS.md').exists())
            self.assertFalse((destination / '.runtime').exists())
            self.assertEqual(
                {p.relative_to(source): p.read_bytes() for p in source.rglob('*') if p.is_file()}, before)


class QueueHelpers:
    """Общие помощники интеграционных тестов очереди для обоих бэкендов."""

    def tearDown(self):
        self.temp.cleanup()

    def g(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, env=self.env, stderr=subprocess.PIPE).decode().strip()

    def commit(self):
        self.g('add', '-A')
        self.g('commit', '-qm', 'setup')

    def save_tasks(self, text):
        (self.repo / 'orchestrator/TASKS.md').write_text(text, encoding='utf-8')

    def set_config(self, commit=True, **overrides):
        path = self.repo / 'orchestrator/config.json'
        data = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
        data.update(overrides)
        path.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        if commit:
            self.g('add', '-A')
            self.g('commit', '-qm', 'config')

    def run_cli(self, *args, ok=True, **env):
        result = subprocess.run(['bash', 'orchestrator/run.sh', *args], cwd=self.repo,
                                env={**self.env, **env}, capture_output=True, text=True, timeout=20)
        if ok:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout + result.stderr

    def calls(self):
        path = self.repo / 'orchestrator/.runtime/calls.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def progress(self):
        return module.read_progress(self.repo / 'orchestrator/PROGRESS.md')


class OrchestratorTests(QueueHelpers, unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="orchestrator space ")
        self.base = Path(self.temp.name)
        self.repo = self.base / "project space"
        self.repo.mkdir()
        copy_test_package(PACKAGE, self.repo / 'orchestrator')
        self.mock = self.base / "mock codex"
        self.mock.write_text(MOCK)
        self.mock.chmod(0o755)
        self.env = dict(os.environ)
        for key in ('MOCK_MODE', 'MOCK_INTERRUPT_TASK', 'GIT_DIR', 'GIT_WORK_TREE',
                    'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
            self.env.pop(key, None)
        self.env.update(GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_NOSYSTEM='1')
        self.g('init', '-q', '-b', 'main')
        self.g('config', 'user.name', 'Test')
        self.g('config', 'user.email', 'test@example.invalid')
        self.g('config', 'core.hooksPath', '/dev/null')
        # Слой окружения удалён: все настройки оркестратора — только через config.json.
        # Фикстура кодексового бэкенда фиксирует backend=codex независимо от
        # значения по умолчанию в конфиге целевого проекта.
        self.set_config(backend='codex', push=False, codex_bin=str(self.mock),
                        restart_retries=0, retry_delay=0)
        self.save_tasks(task())
        self.commit()

    def test_order_skip_and_incremental_queue(self):
        self.save_tasks(task() + '\n' + task('B', 'test -f result-B.txt'))
        self.commit()
        self.run_cli()
        self.assertEqual([c['id'] for c in self.calls()], ['A-1', 'B'])
        self.assertEqual(self.g('branch', '--show-current'), 'auto/tasks')
        self.assertEqual(self.g('status', '--porcelain'), '')
        self.assertEqual(self.g('log', '-1', '--format=%s'), 'B: Название B')
        self.run_cli()
        self.assertEqual(len(self.calls()), 2)
        self.save_tasks(task() + '\n' + task('B', 'test -f result-B.txt') + '\n' + task('NEXT', 'test -f result-NEXT.txt'))
        self.commit()
        self.run_cli()
        self.assertEqual(self.calls()[-1]['id'], 'NEXT')
        self.assertIn('A-1:', self.calls()[-1]['prompt'])

    def test_new_batch_replaces_queue_and_preserves_history(self):
        self.run_cli()
        self.save_tasks(task('BATCH02-1', 'test -f result-BATCH02-1.txt'))
        self.commit()
        self.run_cli()
        self.assertEqual([c['id'] for c in self.calls()], ['A-1', 'BATCH02-1'])
        self.assertEqual(set(self.progress()), {'A-1', 'BATCH02-1'})
        self.assertFalse((self.repo / '.orchestrator').exists())
        self.assertTrue((self.repo / 'orchestrator/.runtime/logs').is_dir())
        self.assertEqual(self.g('ls-files', '--', 'orchestrator/.runtime'), '')

    def test_full_queue_reset_allows_new_batch_with_same_ids(self):
        self.run_cli()
        self.save_tasks('# Задачи\n')
        (self.repo / 'orchestrator/PROGRESS.md').unlink()
        self.commit()
        self.run_cli()
        self.assertEqual(len(self.calls()), 1)
        self.save_tasks(task(prompt='Совсем новая задача с прежним ID.'))
        self.commit()
        self.run_cli()
        self.assertEqual([c['id'] for c in self.calls()], ['A-1', 'A-1'])
        self.assertEqual(len(self.progress()), 1)
        self.assertIn('Совсем новая задача', self.calls()[-1]['prompt'])
        self.assertFalse((self.repo / 'orchestrator/archive').exists())

    def test_configured_models_and_default_push(self):
        configured = json.loads((self.repo / 'orchestrator/config.json').read_text())
        expected = {'light': 'gpt-5.6-luna', 'standard': 'gpt-6.1-sol', 'heavy': 'gpt-6.1-sol'}
        self.assertEqual(configured['models']['codex'], expected)
        self.assertTrue(module.DEFAULTS['push'])
        self.save_tasks(task(tier='light') + '\n' + task('B', 'true') + '\n' + task('C', 'true', tier='heavy'))
        self.commit()
        output = self.run_cli('--list')
        for model in expected.values():
            self.assertIn(model, output)
        remote = self.base / 'default-push.git'
        subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True, env=self.env)
        self.g('remote', 'add', 'origin', str(remote))
        self.run_cli('--push')
        for call, tier, effort in zip(self.calls(), ('light', 'standard', 'heavy'), ('low', 'medium', 'high')):
            with self.subTest(tier=tier):
                args = call['args']
                self.assertEqual(args[args.index('--model') + 1], expected[tier])
                self.assertIn(f'model_reasoning_effort="{effort}"', args)
        self.assertEqual(self.g('rev-parse', 'HEAD'), self.g('rev-parse', 'origin/auto/tasks'))

    def test_push_by_default_with_minimal_config(self):
        (self.repo / 'orchestrator/config.json').write_text(json.dumps({'codex_bin': str(self.mock)}))
        self.commit()
        remote = self.base / 'fallback-push.git'
        subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True, env=self.env)
        self.g('remote', 'add', 'origin', str(remote))
        self.run_cli()
        self.assertEqual(self.g('rev-parse', 'HEAD'), self.g('rev-parse', 'origin/auto/tasks'))

    def test_no_push_works_without_remote_and_keeps_commit_local(self):
        self.run_cli('--no-push')
        self.assertIn('A-1', self.progress())
        self.assertEqual(self.g('remote'), '')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_state_paths_cannot_point_into_runtime(self):
        self.set_config(commit=False, tasks_file='orchestrator/.runtime/TASKS.md')
        self.run_cli('--list', ok=False)

    def test_retry_uses_fresh_exec_and_retains_logs(self):
        self.save_tasks(task(gate="test \"$(cat result-A-1.txt)\" = good"))
        self.commit()
        self.run_cli(MOCK_MODE='retry')
        self.assertEqual(len(self.calls()), 2)
        self.assertIn('повторная попытка', self.calls()[1]['prompt'])
        self.assertEqual(len(list((self.repo / 'orchestrator/.runtime/logs').rglob('*.gate.*.log'))), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_check_mode_runs_inside_queue_and_retries_failed_check(self):
        self.save_tasks(task(gate='bash orchestrator/run.sh --check android'))
        (self.repo / 'gradlew').write_text(
            '#!/usr/bin/env bash\n'
            'test "$*" = ":app:compileDebugKotlin :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain" && '
            'test "$(cat result-A-1.txt)" = good\n')
        self.commit()
        self.run_cli(MOCK_MODE='retry')
        self.assertEqual(len(self.calls()), 2)
        logs = list((self.repo / 'orchestrator/.runtime/logs').rglob('*.gate.*.log'))
        self.assertEqual(len(logs), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_check_mode_ignores_dirty_tree_and_queue_lock(self):
        (self.repo / 'gradlew').write_text('#!/usr/bin/env bash\nexit 0\n')
        self.set_config(checks={'probe': ['exit 0']})
        self.save_tasks('invalid queue')
        head = self.g('rev-parse', 'HEAD')
        status = self.g('status', '--porcelain')
        common = self.repo / self.g('rev-parse', '--git-common-dir')
        with (common / 'universal-orchestrator.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.run_cli('--check', 'probe')
        self.assertEqual(self.g('rev-parse', 'HEAD'), head)
        self.assertEqual(self.g('branch', '--show-current'), 'main')
        self.assertEqual(self.g('status', '--porcelain'), status)
        self.assertEqual(self.calls(), [])
        self.assertFalse((self.repo / 'orchestrator/PROGRESS.md').exists())
        self.assertFalse((self.repo / 'orchestrator/.runtime').exists())

    def test_python_check_inside_queue_does_not_create_protected_bytecode(self):
        self.env.pop('PYTHONDONTWRITEBYTECODE', None)
        # Вложенный запуск использует маленькую серию, чтобы не запускать этот
        # интеграционный тест рекурсивно. Discovery импортирует тестовый модуль.
        suite = self.repo / 'orchestrator/tests'
        shutil.rmtree(suite)
        suite.mkdir()
        (suite / 'test_probe.py').write_text(
            'import unittest\n'
            'class Probe(unittest.TestCase):\n'
            '    def test_discovery(self):\n'
            '        self.assertTrue(__name__.startswith("test_"))\n')
        self.save_tasks(task(gate='bash orchestrator/run.sh --check orchestrator'))
        self.commit()
        self.run_cli()
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')
        self.assertEqual(list((self.repo / 'orchestrator').rglob('__pycache__')), [])
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_help_includes_queue_and_check_options_without_running_them(self):
        for flag in ('--help', '-h'):
            with self.subTest(flag=flag):
                output = self.run_cli(flag)
                self.assertIn('--dry-run', output)
                self.assertIn('--check', output)
                self.assertFalse((self.repo / 'orchestrator/.runtime').exists())

    def test_failure_stops_queue_without_commit(self):
        self.save_tasks(task(gate='exit 9', retries=0) + '\n' + task('B', 'true'))
        self.commit()
        head = self.g('rev-parse', 'HEAD')
        self.run_cli(ok=False)
        self.assertEqual(self.g('rev-parse', 'HEAD'), head)
        self.assertEqual([c['id'] for c in self.calls()], ['A-1'])
        self.assertFalse(self.progress())
        self.assertTrue((self.repo / 'result-A-1.txt').exists())

    def test_resume_interrupted_task_preserves_changes_and_skips_completed_tasks(self):
        self.save_tasks(task() + '\n' + task('B', 'test -f result-B.txt') + '\n' + task('C', 'true'))
        self.commit()
        self.set_config(restart_retries=2)
        self.run_cli(ok=False, MOCK_MODE='interrupt-once', MOCK_INTERRUPT_TASK='B')
        self.assertEqual(set(self.progress()), {'A-1'})
        self.assertEqual((self.repo / 'result-B.txt').read_text(), 'good')
        self.run_cli()
        self.assertEqual([c['id'] for c in self.calls()], ['A-1', 'B', 'B', 'C'])
        self.assertIn('продолжение', self.calls()[2]['prompt'])
        self.assertEqual(set(self.progress()), {'A-1', 'B', 'C'})
        self.assertEqual(self.g('status', '--porcelain'), '')
        self.assertFalse((self.repo / 'orchestrator/.runtime/checkpoint.json').exists())

    def test_transient_agent_error_restarts_without_losing_partial_work(self):
        self.set_config(restart_retries=1)
        self.run_cli(MOCK_MODE='transient')
        self.assertEqual(len(self.calls()), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')
        self.assertEqual(len(list((self.repo / 'orchestrator/.runtime/logs').rglob('*.exec.*.log'))), 2)

    def test_empty_agent_reply_can_restart(self):
        self.set_config(restart_retries=1)
        self.run_cli(MOCK_MODE='empty-once')
        self.assertEqual(len(self.calls()), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_agent_timeout_can_restart_without_spending_gate_retries(self):
        self.save_tasks(task(retries=0))
        self.commit()
        self.set_config(task_timeout=1, restart_retries=1)
        self.run_cli(MOCK_MODE='timeout-once')
        self.assertEqual(len(self.calls()), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_gate_timeout_restarts_only_gate(self):
        self.save_tasks(task(retries=0, gate='if test ! -f orchestrator/.runtime/gate-started; then '
                             'touch orchestrator/.runtime/gate-started; sleep 30; fi'))
        self.commit()
        self.set_config(gate_timeout=1, restart_retries=1)
        self.run_cli()
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_resume_interrupted_gate_does_not_rerun_agent(self):
        self.save_tasks(task(gate='if test ! -f orchestrator/.runtime/gate-started; then '
                             'touch orchestrator/.runtime/gate-started; kill -TERM "$PPID"; sleep 30; fi'))
        self.commit()
        self.run_cli(ok=False)
        self.assertFalse(self.progress())
        self.run_cli()
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_resume_rejects_changed_task_selection_history_and_protected_files(self):
        self.save_tasks(task() + '\n' + task('B', 'true'))
        self.commit()
        self.run_cli(ok=False, MOCK_MODE='fail')
        calls = len(self.calls())
        self.assertIn('A-1', self.run_cli('--only', 'B', ok=False))
        (self.repo / 'orchestrator/notes.txt').write_text('external change')
        self.assertIn('защищённые', self.run_cli(ok=False))
        (self.repo / 'orchestrator/notes.txt').unlink()
        self.save_tasks(task(prompt='Changed unfinished task') + '\n' + task('B', 'true'))
        self.assertIn('изменена', self.run_cli(ok=False))
        self.g('restore', 'orchestrator/TASKS.md')
        self.commit()
        self.assertIn('Git-история', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), calls)

    def test_explicit_blocked_reply_is_not_retried_or_automatically_resumed(self):
        self.run_cli(ok=False, MOCK_MODE='blocked', RESTART_RETRIES='2')
        self.assertEqual(len(self.calls()), 1)
        self.assertIn('BLOCKED', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)

    def test_nonzero_codex_exit_with_blocked_reply_never_retries(self):
        self.run_cli(ok=False, MOCK_MODE='blocked-error', RESTART_RETRIES='2')
        self.assertEqual(len(self.calls()), 1)
        self.assertIn('BLOCKED', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)

    def test_corrupted_checkpoint_never_adopts_dirty_work(self):
        self.run_cli(ok=False, MOCK_MODE='fail')
        checkpoint = self.repo / 'orchestrator/.runtime/checkpoint.json'
        original = json.loads(checkpoint.read_text())
        for body in ('{broken', '{}', json.dumps({**original, 'phase': 'unknown'}),
                     json.dumps({key: value for key, value in original.items() if key != 'summary'})):
            with self.subTest(body=body[:40]):
                checkpoint.write_text(body)
                self.assertIn('повреждена', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual((self.repo / 'result-A-1.txt').read_text(), 'good')

    def test_retries_exhausted(self):
        self.save_tasks(task(gate='false', retries=2))
        self.commit()
        self.run_cli(ok=False)
        self.assertEqual(len(self.calls()), 3)
        self.assertFalse(self.progress())

    def test_mark_done_runs_gate_without_codex(self):
        self.run_cli(ok=False, MOCK_MODE='fail')
        self.run_cli('--mark-done', 'A-1')
        self.assertEqual(len(self.calls()), 1)
        self.assertTrue(self.progress()['A-1']['manual'])
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_mark_done_rejects_failed_gate(self):
        self.save_tasks(task(gate='false'))
        self.commit()
        self.run_cli(ok=False)
        self.run_cli('--mark-done', 'A-1', ok=False)
        self.assertFalse(self.progress())

    def test_service_and_tasks_and_journal_protection(self):
        for mode in ('tamper', 'edit-tasks', 'edit-progress'):
            with self.subTest(mode=mode):
                self.run_cli(ok=False, MOCK_MODE=mode)
                self.assertFalse(self.g('log', '--format=%s').startswith('A-1:'))
                self.g('reset', '--hard', 'HEAD')
                for name in ('orchestrator/injected.txt', 'orchestrator/PROGRESS.md', 'result-A-1.txt'):
                    (self.repo / name).unlink(missing_ok=True)

    def test_gate_cannot_change_protected_files(self):
        self.save_tasks(task(gate='echo changed >> orchestrator/TASKS.md'))
        self.commit()
        self.assertIn('защищённые', self.run_cli(ok=False))
        self.assertFalse(self.progress())

    def test_agent_commit_is_detected(self):
        self.assertIn('Git-история', self.run_cli(ok=False, MOCK_MODE='commit'))
        self.assertFalse(self.progress())

    def test_blocked_empty_or_codex_error(self):
        self.run_cli(ok=False, MOCK_MODE='blocked')
        self.assertFalse(self.progress())
        self.assertEqual(len(self.calls()), 1)

    def test_empty_agent_reply_stops_without_completion(self):
        self.run_cli(ok=False, MOCK_MODE='empty')
        self.assertFalse(self.progress())
        self.assertEqual(len(self.calls()), 1)

    def test_codex_error_stops_without_completion(self):
        self.run_cli(ok=False, MOCK_MODE='fail')
        self.assertFalse(self.progress())
        self.assertEqual(len(self.calls()), 1)

    def test_no_project_change_can_finish(self):
        self.save_tasks(task(gate='true'))
        self.commit()
        self.run_cli(MOCK_MODE='nochange')
        self.assertEqual(self.g('show', '--format=', '--name-only', 'HEAD'), 'orchestrator/PROGRESS.md')

    def test_skip_is_explicit_and_utf8_is_preserved(self):
        self.save_tasks(task(gate='skip'))
        self.commit()
        self.run_cli()
        record = self.progress()['A-1']
        self.assertEqual(record['gate'], 'skipped')
        self.assertEqual(len(record['summary']), 300)
        self.assertIn('🛠', record['summary'])

    def test_dirty_files_including_orchestrator_are_rejected(self):
        for name in ('unrelated.txt', 'orchestrator/notes.txt'):
            with self.subTest(name=name):
                (self.repo / name).write_text('dirty')
                self.assertIn('чистым', self.run_cli(ok=False))
                self.assertFalse(self.calls())
                (self.repo / name).unlink()

    def test_list_and_dry_run_do_not_modify_files(self):
        shutil.rmtree(self.repo / '.git')
        before = sorted(str(p.relative_to(self.repo)) for p in self.repo.rglob('*'))
        self.run_cli('--list')
        self.run_cli('--dry-run')
        after = sorted(str(p.relative_to(self.repo)) for p in self.repo.rglob('*'))
        self.assertEqual(before, after)

    def test_selection_validation_and_from(self):
        self.run_cli('--only', 'MISSING', ok=False)
        self.run_cli('--from', 'MISSING', ok=False)
        self.assertFalse(self.calls())
        self.save_tasks(task() + '\n' + task('B', 'test -f result-B.txt'))
        self.commit()
        self.run_cli('--from', 'B')
        self.assertEqual([c['id'] for c in self.calls()], ['B'])
        self.run_cli('--only', 'A-1')
        self.assertEqual(self.calls()[-1]['id'], 'A-1')

    def test_completed_id_change_is_rejected(self):
        self.run_cli()
        self.save_tasks(task(prompt='Новое содержимое'))
        self.commit()
        self.assertIn('новый ID', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)

    def test_uncommitted_progress_is_not_used_as_done(self):
        self.run_cli()
        self.g('reset', '--soft', 'HEAD~1')
        self.assertIn('незакоммиченные результаты', self.run_cli(ok=False))

    def test_failed_commit_does_not_cause_silent_skip(self):
        hooks = self.base / 'hooks'
        hooks.mkdir()
        hook = hooks / 'pre-commit'
        hook.write_text('#!/bin/sh\nexit 1\n')
        hook.chmod(0o755)
        self.g('config', 'core.hooksPath', str(hooks))
        self.run_cli(ok=False)
        self.assertIn('git commit', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)
        self.g('config', 'core.hooksPath', '/dev/null')
        self.run_cli()
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(self.g('status', '--porcelain'), '')
        self.assertEqual(self.g('log', '-1', '--format=%s'), 'A-1: Название A-1')

    def test_resume_after_commit_completed_before_parent_interruption(self):
        hooks = self.base / 'hooks'
        hooks.mkdir()
        hook = hooks / 'post-commit'
        hook.write_text(
            '#!/usr/bin/env python3\n'
            'import os, signal, subprocess, time\n'
            'parent = int(subprocess.check_output(["ps", "-o", "ppid=", "-p", str(os.getppid())]))\n'
            'os.kill(parent, signal.SIGTERM)\n'
            'time.sleep(30)\n')
        hook.chmod(0o755)
        self.g('config', 'core.hooksPath', str(hooks))
        self.run_cli(ok=False)
        committed = self.g('rev-parse', 'HEAD')
        self.assertEqual(self.g('log', '-1', '--format=%s'), 'A-1: Название A-1')
        self.g('config', 'core.hooksPath', '/dev/null')
        self.run_cli()
        self.assertEqual(self.g('rev-parse', 'HEAD'), committed)
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(len(self.progress()), 1)

    def test_custom_paths_models_and_gate_base(self):
        (self.repo / 'plans').mkdir()
        (self.repo / 'orchestrator/TASKS.md').rename(self.repo / 'plans/work.md')
        config_file = self.repo / 'orchestrator/config.json'
        config = json.loads(config_file.read_text())
        config.update(tasks_file='plans/work.md', progress_file='notes/progress.md',
                      gate_base=['env'], branch='work/queue',
                      efforts={**config['efforts'], 'standard': 'high'})
        config['models']['codex']['standard'] = 'test-model'
        config_file.write_text(json.dumps(config))
        self.commit()
        self.run_cli()
        self.assertTrue((self.repo / 'notes/progress.md').exists())
        call = self.calls()[0]
        self.assertIn('test-model', call['args'])
        self.assertIn('model_reasoning_effort="high"', call['args'])
        self.assertEqual(self.g('branch', '--show-current'), 'work/queue')

    def test_lock_prevents_concurrent_execution(self):
        with (self.repo / '.git/universal-orchestrator.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertIn('уже работает', self.run_cli(ok=False))
            self.run_cli('--list')
        self.run_cli()

    def test_existing_branch_is_not_switched_automatically(self):
        self.g('branch', 'auto/tasks')
        self.assertIn('выберите её вручную', self.run_cli(ok=False))
        self.assertFalse(self.calls())

    def test_push_to_local_remote(self):
        remote = self.base / 'remote.git'
        subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True, env=self.env)
        self.g('remote', 'add', 'origin', str(remote))
        self.run_cli('--push')
        self.assertEqual(self.g('rev-parse', 'HEAD'), self.g('rev-parse', 'origin/auto/tasks'))

    def test_push_failure_stops_after_local_commit(self):
        self.save_tasks(task() + '\n' + task('B', 'true'))
        self.commit()
        self.g('remote', 'add', 'origin', str(self.base / 'missing.git'))
        self.assertIn('сохранён локально', self.run_cli('--push', ok=False))
        self.assertEqual([c['id'] for c in self.calls()], ['A-1'])
        self.assertIn('A-1', self.progress())

    def test_resume_retries_push_before_running_next_task(self):
        self.save_tasks(task() + '\n' + task('B', 'true'))
        self.commit()
        remote = self.base / 'initially unavailable.git'
        self.g('remote', 'add', 'origin', str(remote))
        self.run_cli('--push', ok=False)
        committed = self.g('rev-parse', 'HEAD')
        self.assertEqual([c['id'] for c in self.calls()], ['A-1'])
        subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True, env=self.env)
        self.run_cli('--push')
        self.assertEqual([c['id'] for c in self.calls()], ['A-1', 'B'])
        self.assertEqual(self.g('rev-parse', 'HEAD~1'), committed)
        self.assertEqual(self.g('rev-parse', 'HEAD'), self.g('rev-parse', 'origin/auto/tasks'))

    def test_sigkill_parent_cannot_start_second_agent_while_orphan_holds_lock(self):
        with tempfile.TemporaryFile() as output:
            process = subprocess.Popen(['bash', 'orchestrator/run.sh'], cwd=self.repo,
                                       env={**self.env, 'MOCK_MODE': 'timeout'}, stdout=output, stderr=output)
            worker = self.repo / 'orchestrator/.runtime/worker.pid'
            ready = self.repo / 'orchestrator/.runtime/child.pid'
            pid = None
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists())
                pid = int(worker.read_text())
                process.kill()
                process.wait(timeout=5)
                self.assertIn('уже работает', self.run_cli(ok=False))
                self.assertEqual(len(self.calls()), 1)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=5)
                if pid is not None:
                    try:
                        os.killpg(pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    status = Path(f'/proc/{pid}/stat').read_text().split()[2]
                except FileNotFoundError:
                    break
                if status == 'Z':
                    break
                time.sleep(0.02)
            self.run_cli()
            self.assertEqual(len(self.calls()), 2)
            self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_timeout_stops_child_group(self):
        self.set_config(task_timeout=1)
        self.assertIn('таймаут', self.run_cli(ok=False, MOCK_MODE='timeout'))
        pid = int((self.repo / 'orchestrator/.runtime/child.pid').read_text())
        status = Path(f'/proc/{pid}/stat')
        if status.exists():
            self.assertEqual(status.read_text().split()[2], 'Z')
        self.assertFalse(self.progress())

    def test_sigterm_stops_child_group(self):
        with tempfile.TemporaryFile() as output:
            process = subprocess.Popen(['bash', 'orchestrator/run.sh'], cwd=self.repo,
                                       env={**self.env, 'MOCK_MODE': 'timeout'}, stdout=output, stderr=output)
            pidfile = self.repo / 'orchestrator/.runtime/child.pid'
            deadline = time.monotonic() + 5
            while not pidfile.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            try:
                self.assertTrue(pidfile.exists())
            finally:
                process.send_signal(signal.SIGTERM)
                process.wait(timeout=5)
            pid = int(pidfile.read_text())
            status = Path(f'/proc/{pid}/stat')
            if status.exists():
                self.assertEqual(status.read_text().split()[2], 'Z')
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse(self.progress())

    def test_gate_timeout(self):
        self.save_tasks(task(gate='sleep 30'))
        self.commit()
        self.set_config(gate_timeout=1)
        self.assertIn('таймаут', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)
        self.assertFalse(self.progress())

    def test_parser_rejects_bad_input_before_codex(self):
        cases = [task() + task(), task(tier='other'), task(retries=-1),
                 task().replace('## A-1:', '## A-1'),
                 task().replace('gate: test -f result-A-1.txt\n', ''),
                 task().replace('```\n', ''), task().replace('tier: standard', 'tier: standard\ntier: light'),
                 task().replace('tier: standard', 'tierr: standard')]
        for value in cases:
            with self.subTest(value=value):
                self.save_tasks(value)
                self.run_cli('--list', ok=False)
        self.assertFalse(self.calls())

    def test_parser_headers_inside_prompt_and_crlf(self):
        self.save_tasks(task(prompt='Текст\n## INNER: это не задача\n```python\nx = 1'))
        parsed = module.parse_tasks(self.repo / 'orchestrator/TASKS.md')
        self.assertEqual(len(parsed), 1)
        self.assertIn('INNER', parsed[0].prompt)
        (self.repo / 'orchestrator/TASKS.md').write_bytes(task().replace('\n', '\r\n').encode())
        self.assertEqual(module.parse_tasks(self.repo / 'orchestrator/TASKS.md')[0].id, 'A-1')

    def test_path_escape_and_bad_config_are_rejected(self):
        self.set_config(commit=False, tasks_file='../outside.md')
        self.run_cli('--list', ok=False)
        path = self.repo / 'orchestrator/config.json'
        path.write_text('{"unknown": true}')
        self.run_cli('--list', ok=False)

    def test_linked_worktree(self):
        linked = self.base / 'linked project'
        self.g('worktree', 'add', '-qb', 'linked-base', str(linked))
        self.repo = linked
        self.set_config(branch='linked-tasks')
        self.run_cli()
        self.assertEqual(self.g('status', '--porcelain'), '')


class OpenCodeTests(QueueHelpers, unittest.TestCase):
    """Полный цикл очереди на бэкенде OpenCode: команда, извлечение ответа, сбои."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="orchestrator opencode ")
        self.base = Path(self.temp.name)
        self.repo = self.base / "project space"
        self.repo.mkdir()
        copy_test_package(PACKAGE, self.repo / 'orchestrator')
        self.mock = self.base / "mock opencode"
        self.mock.write_text(MOCK_OPENCODE)
        self.mock.chmod(0o755)
        self.env = dict(os.environ)
        for key in ('MOCK_MODE', 'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
            self.env.pop(key, None)
        self.env.update(GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_NOSYSTEM='1')
        self.g('init', '-q', '-b', 'main')
        self.g('config', 'user.name', 'Test')
        self.g('config', 'user.email', 'test@example.invalid')
        self.g('config', 'core.hooksPath', '/dev/null')
        self.set_config(backend='opencode', opencode_bin=str(self.mock), push=False,
                        restart_retries=0, retry_delay=0,
                        models={'opencode': {'light': 'opencode/test-m', 'standard': 'opencode/test-s',
                                             'heavy': 'opencode/test-h'}})
        self.save_tasks(task(tier='light'))
        self.commit()

    def test_full_cycle_uses_run_command_and_extracts_last_text(self):
        self.run_cli()
        call = self.calls()[0]
        self.assertEqual(call['args'], ['run', '--agent', 'build', '--format', 'json',
                                        '--model', 'opencode/test-m'])
        self.assertIn('ЗАДАНИЕ A-1', call['prompt'])
        record = self.progress()['A-1']
        self.assertEqual(record['model'], 'opencode/test-m')
        self.assertEqual(record['summary'][:6], 'ИТОГ: ')
        # Финальный ответ — последний text-event, а не промежуточное сообщение заглушки.
        self.assertIn('Привет 🛠', record['summary'])
        self.assertIn('A-1:', self.g('log', '-1', '--format=%s'))
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_empty_model_is_taken_from_cli_settings(self):
        self.set_config(models={'opencode': {'light': ''}})
        self.run_cli()
        self.assertEqual(self.calls()[0]['args'], ['run', '--agent', 'build', '--format', 'json'])

    def test_blocked_reply_is_not_retried(self):
        self.run_cli(ok=False, MOCK_MODE='blocked')
        self.assertEqual(len(self.calls()), 1)
        self.assertIn('BLOCKED', self.run_cli(ok=False))
        self.assertEqual(len(self.calls()), 1)

    def test_empty_stream_stops_without_completion(self):
        self.run_cli(ok=False, MOCK_MODE='empty')
        self.assertFalse(self.progress())
        self.assertEqual(len(self.calls()), 1)

    def test_provider_exit_code_fails_the_task(self):
        self.run_cli(ok=False, MOCK_MODE='fail')
        self.assertFalse(self.progress())
        self.assertEqual(len(self.calls()), 1)

    def test_flat_models_are_rejected_for_opencode(self):
        self.set_config(commit=False, models={'light': 'x'})
        self.assertIn('плоский формат', self.run_cli(ok=False))
        self.assertFalse(self.calls())

    def test_backend_flag_overrides_config(self):
        self.set_config(backend='codex', models={'codex': {'light': 'codex-only'},
                                                 'opencode': {'light': 'opencode/flag-m',
                                                              'standard': 'opencode/flag-s',
                                                              'heavy': 'opencode/flag-h'}})
        self.run_cli('--backend', 'opencode')
        self.assertEqual(self.calls()[0]['args'],
                         ['run', '--agent', 'build', '--format', 'json', '--model', 'opencode/flag-m'])
        self.assertEqual(self.progress()['A-1']['model'], 'opencode/flag-m')

    def paid_set(self):
        return {'opencode': {'paid': {'light': 'opencode/paid-l',
                                      'standard': 'opencode/paid-s',
                                      'heavy': 'opencode/paid-h'}}}

    def test_models_flag_selects_named_set(self):
        self.set_config(models={'opencode': {'light': 'free-l', 'standard': 'free-s', 'heavy': 'free-h'}},
                        model_sets=self.paid_set())
        self.assertIn('opencode/paid-l', self.run_cli('--models', 'paid', '--dry-run'))
        self.assertFalse(self.calls())
        self.run_cli('--models', 'paid')
        self.assertEqual(self.calls()[0]['args'],
                         ['run', '--agent', 'build', '--format', 'json', '--model', 'opencode/paid-l'])
        self.assertEqual(self.progress()['A-1']['model'], 'opencode/paid-l')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def codex_cheap_set(self):
        return {'codex': {'cheap': {'light': 'codex-cheap-l', 'standard': 'codex-cheap-s',
                                    'heavy': 'codex-cheap-h'}}}

    def codex_mock(self):
        path = self.base / 'mock codex'
        path.write_text(MOCK)
        path.chmod(0o755)
        return path

    def test_models_flag_requires_a_set_for_the_active_backend(self):
        self.set_config(backend='codex', codex_bin=str(self.codex_mock()), model_sets=self.paid_set())
        output = self.run_cli('--backend', 'codex', '--models', 'paid', ok=False)
        self.assertIn('для backend codex', output)
        self.assertIn('model_sets.codex', output)
        self.assertFalse(self.calls())

    def test_models_flag_selects_named_set_for_codex(self):
        self.set_config(backend='codex', codex_bin=str(self.codex_mock()),
                        model_sets=self.codex_cheap_set())
        self.run_cli('--models', 'cheap')
        args = self.calls()[0]['args']
        self.assertEqual(args[args.index('--model') + 1], 'codex-cheap-l')
        self.assertEqual(self.progress()['A-1']['model'], 'codex-cheap-l')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_semantic_profile_is_exported_to_agent(self):
        self.set_config(backend='codex', codex_bin=str(self.codex_mock()),
                        semantic={'codex': {'provider': 'codex', 'model': 'gpt-6.1-sol',
                                            'cache': '.local/lex-cache'}})
        output = self.run_cli('--dry-run')
        self.assertIn('ИИ-оценки LEX: codex / gpt-6.1-sol; кэш .local/lex-cache', output)
        self.assertFalse(self.calls())
        self.run_cli()
        env = self.calls()[0]['env']
        self.assertEqual(env['CATEGORY_PROVIDER'], 'codex')
        self.assertEqual(env['CATEGORY_MODEL'], 'gpt-6.1-sol')
        self.assertEqual(Path(env['SEMANTIC_CACHE']), self.repo / '.local/lex-cache')

    def test_backend_switch_rebinds_unfinished_task_model(self):
        self.set_config(backend='opencode', opencode_bin=str(self.mock),
                        codex_bin=str(self.codex_mock()),
                        push=False, restart_retries=2, retry_delay=0,
                        models={'codex': {'light': 'gpt-5.6-luna', 'standard': 'gpt-6.1-sol',
                                          'heavy': 'gpt-6.1-sol'},
                                'opencode': {'light': 'opencode/light', 'standard': 'opencode/standard',
                                             'heavy': 'opencode/heavy'}})
        self.save_tasks(task() + '\n' + task('B', 'test -f result-B.txt'))
        self.commit()
        self.run_cli(ok=False, MOCK_MODE='interrupt-once', MOCK_INTERRUPT_TASK='A-1')
        output = self.run_cli('--backend', 'codex')
        self.assertIn('модель пересчитана под backend codex', output)
        self.assertEqual(self.progress()['A-1']['model'], 'gpt-6.1-sol')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_resume_with_models_flag_keeps_saved_models_and_warns(self):
        self.set_config(models={'opencode': {'light': 'free-l', 'standard': 'free-s', 'heavy': 'free-h'}},
                        model_sets=self.paid_set(), restart_retries=2)
        self.save_tasks(task(tier='light') + '\n' + task('B', 'test -f result-B.txt', tier='light'))
        self.commit()
        self.run_cli(ok=False, MOCK_MODE='interrupt-once', MOCK_INTERRUPT_TASK='B')
        output = self.run_cli('--models', 'paid')
        self.assertIn('продолжается с сохранённой моделью', output)
        self.assertEqual(self.calls()[2]['args'][-1], 'free-l')
        self.assertEqual(self.progress()['B']['model'], 'free-l')


if __name__ == '__main__':
    unittest.main(verbosity=2)
