"""Интеграционные проверки с временными Git-репозиториями и заглушкой Codex."""
import fcntl
import importlib.util
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
with (work / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps({'id': task, 'args': args, 'prompt': prompt}) + '\n')
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
if mode not in ('nochange', 'blocked'):
    Path('result-' + task + '.txt').write_text('bad' if mode == 'retry' and count == 1 else 'good')
if mode == 'commit':
    subprocess.run(['git', 'add', '-A'], check=True)
    subprocess.run(['git', 'commit', '-qm', 'unexpected agent commit'], check=True)
if mode == 'fail': sys.exit(7)
out = Path(args[args.index('--output-last-message') + 1])
if mode != 'empty':
    out.write_text('BLOCKED: нет данных' if mode == 'blocked' else 'ИТОГ: ' + 'Привет 🛠 ' * 80 + '\nПРОВЕРЕНО: заглушка\n', encoding='utf-8')
'''


def task(task_id="A-1", gate="test -f result-A-1.txt", retries=1, tier="standard", prompt="Сделай задачу."):
    return f"## {task_id}: Название {task_id}\ntier: {tier}\ngate: {gate}\nretries: {retries}\n```prompt\n{prompt}\n```\n"


class OrchestratorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="orchestrator space ")
        self.base = Path(self.temp.name)
        self.repo = self.base / "project space"
        self.repo.mkdir()
        shutil.copytree(PACKAGE, self.repo / 'orchestrator', ignore=shutil.ignore_patterns('__pycache__', '.runtime'))
        self.mock = self.base / "mock codex"
        self.mock.write_text(MOCK)
        self.mock.chmod(0o755)
        self.env = dict(os.environ)
        for key in [*map(str.upper, module.DEFAULTS), 'GATE_BASE', 'NO_PUSH', 'MOCK_MODE',
                    'GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR']:
            self.env.pop(key, None)
        for tier in ('LIGHT', 'STANDARD', 'HEAVY'):
            self.env.pop('MODEL_' + tier, None)
            self.env.pop('EFFORT_' + tier, None)
        self.env.update(NO_PUSH='1', CODEX_BIN=str(self.mock), GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_NOSYSTEM='1')
        self.g('init', '-q', '-b', 'main')
        self.g('config', 'user.name', 'Test')
        self.g('config', 'user.email', 'test@example.invalid')
        self.g('config', 'core.hooksPath', '/dev/null')
        self.save_tasks(task())
        self.commit()

    def tearDown(self):
        self.temp.cleanup()

    def g(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, env=self.env, stderr=subprocess.PIPE).decode().strip()

    def commit(self):
        self.g('add', '-A')
        self.g('commit', '-qm', 'setup')

    def save_tasks(self, text):
        (self.repo / 'orchestrator/TASKS.md').write_text(text, encoding='utf-8')

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

    def test_configured_models_and_explicit_push(self):
        configured = json.loads((self.repo / 'orchestrator/config.json').read_text())
        expected = {'light': 'gpt-5.6-luna', 'standard': 'gpt-6.1-terra', 'heavy': 'gpt-6-astra'}
        self.assertEqual(configured['models'], expected)
        self.assertFalse(configured['push'])
        self.assertFalse(module.DEFAULTS['push'])
        self.save_tasks(task(tier='light') + '\n' + task('B', 'true') + '\n' + task('C', 'true', tier='heavy'))
        self.commit()
        output = self.run_cli('--list')
        for model in expected.values():
            self.assertIn(model, output)
        remote = self.base / 'default-push.git'
        subprocess.run(['git', 'init', '--bare', '-q', str(remote)], check=True, env=self.env)
        self.g('remote', 'add', 'origin', str(remote))
        self.env.pop('NO_PUSH')
        self.run_cli('--push')
        self.assertEqual(self.g('rev-parse', 'HEAD'), self.g('rev-parse', 'origin/auto/tasks'))

    def test_default_run_works_without_remote_and_keeps_commit_local(self):
        self.env.pop('NO_PUSH')
        self.run_cli()
        self.assertIn('A-1', self.progress())
        self.assertEqual(self.g('remote'), '')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_state_paths_cannot_point_into_runtime(self):
        self.run_cli('--list', ok=False, TASKS_FILE='orchestrator/.runtime/TASKS.md')

    def test_retry_uses_fresh_exec_and_retains_logs(self):
        self.save_tasks(task(gate="test \"$(cat result-A-1.txt)\" = good"))
        self.commit()
        self.run_cli(MOCK_MODE='retry')
        self.assertEqual(len(self.calls()), 2)
        self.assertIn('повторная попытка', self.calls()[1]['prompt'])
        self.assertEqual(len(list((self.repo / 'orchestrator/.runtime/logs').rglob('*.gate.*.log'))), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')

    def test_check_mode_runs_inside_queue_and_retries_failed_check(self):
        self.save_tasks(task(gate='bash orchestrator/run.sh --check core'))
        (self.repo / 'gradlew').write_text(
            '#!/usr/bin/env bash\n'
            'test "$*" = ":core:test --console=plain" && '
            'test "$(cat result-A-1.txt)" = good\n')
        self.commit()
        self.run_cli(MOCK_MODE='retry')
        self.assertEqual(len(self.calls()), 2)
        logs = list((self.repo / 'orchestrator/.runtime/logs').rglob('*.gate.*.log'))
        self.assertEqual(len(logs), 2)
        self.assertEqual(self.progress()['A-1']['gate'], 'passed')
        self.assertEqual(self.g('status', '--porcelain'), '')

    def test_check_mode_ignores_queue_config_dirty_tree_and_queue_lock(self):
        (self.repo / 'gradlew').write_text('#!/usr/bin/env bash\nexit 0\n')
        self.commit()
        (self.repo / 'orchestrator/config.json').write_text('invalid JSON')
        self.save_tasks('invalid queue')
        head = self.g('rev-parse', 'HEAD')
        status = self.g('status', '--porcelain')
        common = self.repo / self.g('rev-parse', '--git-common-dir')
        with (common / 'universal-orchestrator.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.run_cli('--check', 'core', CODEX_BIN=str(self.base / 'missing-codex'))
        self.assertEqual(self.g('rev-parse', 'HEAD'), head)
        self.assertEqual(self.g('branch', '--show-current'), 'main')
        self.assertEqual(self.g('status', '--porcelain'), status)
        self.assertEqual(self.calls(), [])
        self.assertFalse((self.repo / 'orchestrator/PROGRESS.md').exists())
        self.assertFalse((self.repo / 'orchestrator/.runtime').exists())

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
        for mode in ('blocked', 'empty', 'fail'):
            with self.subTest(mode=mode):
                self.run_cli(ok=False, MOCK_MODE=mode)
                self.assertFalse(self.progress())
                (self.repo / 'result-A-1.txt').unlink(missing_ok=True)

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
        self.assertIn('незакоммиченные результаты', self.run_cli(ok=False))
        self.g('config', 'core.hooksPath', '/dev/null')
        self.g('commit', '-qm', 'complete verified task')
        self.run_cli()
        self.assertEqual(len(self.calls()), 1)

    def test_custom_paths_models_and_gate_base(self):
        (self.repo / 'plans').mkdir()
        (self.repo / 'orchestrator/TASKS.md').rename(self.repo / 'plans/work.md')
        config_file = self.repo / 'orchestrator/config.json'
        config = json.loads(config_file.read_text())
        config.update(tasks_file='plans/work.md', progress_file='notes/progress.md',
                      gate_base=['env'], branch='work/queue')
        config['models']['standard'] = 'test-model'
        config_file.write_text(json.dumps(config))
        self.commit()
        self.run_cli(EFFORT_STANDARD='high')
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

    def test_timeout_stops_child_group(self):
        self.assertIn('таймаут', self.run_cli(ok=False, MOCK_MODE='timeout', TASK_TIMEOUT='1'))
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
        self.assertIn('таймаут', self.run_cli(ok=False, GATE_TIMEOUT='1'))
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
        self.run_cli('--list', ok=False, TASKS_FILE='../outside.md')
        path = self.repo / 'orchestrator/config.json'
        path.write_text('{"unknown": true}')
        self.run_cli('--list', ok=False)

    def test_linked_worktree(self):
        linked = self.base / 'linked project'
        self.g('worktree', 'add', '-qb', 'linked-base', str(linked))
        self.repo = linked
        self.run_cli(BRANCH='linked-tasks')
        self.assertEqual(self.g('status', '--porcelain'), '')


if __name__ == '__main__':
    unittest.main(verbosity=2)
