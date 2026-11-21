"""Проверки команд проекта без SDK, сети и запуска настоящего агента."""
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


PACKAGE = Path(__file__).resolve().parents[1]
REPO = PACKAGE.parent


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checks = load_module("project_checks", PACKAGE / "checks.py")
orchestrator = load_module("project_orchestrator", PACKAGE / "orchestrator.py")

DOCUMENTATION_COMMANDS = [
    "python3 tools/check_docs.py",
    "python3 -m unittest discover -s tools/tests -p test_spec_campaign_numbers.py",
]


class ProjectGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="words gate ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "orchestrator").mkdir()
        for name in ("run.sh", "checks.py", "config.json"):
            shutil.copy2(PACKAGE / name, self.root / f"orchestrator/{name}")
        binaries = self.root / "bin"
        binaries.mkdir()
        self.log = self.root / "commands.log"
        self.env = dict(os.environ, PATH=f"{binaries}:{os.environ['PATH']}",
                        GATE_TEST_LOG=str(self.log))
        for path, name in ((binaries / "python3", "python3"), (self.root / "gradlew", "gradle")):
            if name == "python3":
                # Вызовы checks.py делегируются настоящему интерпретатору: они
                # резолвят профили из конфига, а сами проверками не являются.
                script = ('#!/usr/bin/env bash\n'
                          f'if [[ "$*" == *checks.py* ]]; then exec {sys.executable} "$@"; fi\n'
                          'printf \'%s|%s\\n\' "$PWD" "python3 $*" >> "$GATE_TEST_LOG"\n'
                          'exit "${GATE_TEST_EXIT:-0}"\n')
            else:
                script = ('#!/usr/bin/env bash\n'
                          f'printf \'%s|%s\\n\' "$PWD" "{name} $*" >> "$GATE_TEST_LOG"\n'
                          'exit "${GATE_TEST_EXIT:-0}"\n')
            path.write_text(script, encoding="utf-8")
            path.chmod(0o755)

    def run_gate(self, *args, exit_code=0):
        result = subprocess.run(
            ["bash", str(self.root / "orchestrator/run.sh"), "--check", *args],
            cwd=self.root / "bin", env={**self.env, "GATE_TEST_EXIT": str(exit_code)},
            capture_output=True, text=True, timeout=10)
        calls = self.log.read_text().splitlines() if self.log.exists() else []
        for call in calls:
            self.assertTrue(call.startswith(f"{self.root}|"), call)
        return result, [call.split("|", 1)[1] for call in calls]

    def test_profiles_run_project_checks_from_repository_root(self):
        expected = {
            "orchestrator": ["python3 -m unittest discover -s orchestrator/tests"],
            "docs": DOCUMENTATION_COMMANDS,
            "tools": ["python3 -m unittest discover -s tools/tests"],
            "core": ["gradle :core:test --console=plain"],
            "data": ["gradle :app:verifyDictionaryLicenses :core:test --console=plain"],
            "android": ["gradle :app:compileDebugKotlin :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain"],
        }
        for profile, commands in expected.items():
            with self.subTest(profile=profile):
                self.log.unlink(missing_ok=True)
                result, calls = self.run_gate(profile)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(calls, commands)

    def test_full_and_default_include_both_python_suites_and_commit_gate(self):
        for args in ((), ("full",)):
            with self.subTest(args=args):
                self.log.unlink(missing_ok=True)
                result, calls = self.run_gate(*args)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(calls, [
                    "python3 -m unittest discover -s tools/tests",
                    "python3 -m unittest discover -s orchestrator/tests",
                    "gradle :core:test :app:assembleDebug --console=plain",
                ])

    def test_failed_check_stops_full_gate_and_preserves_exit_code(self):
        result, calls = self.run_gate("full", exit_code=7)
        self.assertEqual(result.returncode, 7)
        self.assertEqual(calls, ["python3 -m unittest discover -s tools/tests"])

    def test_gradle_failure_is_not_reported_as_success(self):
        result, calls = self.run_gate("android", exit_code=9)
        self.assertEqual(result.returncode, 9)
        self.assertEqual(len(calls), 1)

    def test_invalid_arguments_do_not_run_checks(self):
        for args in (("unknown",), ("",), ("full", "android"),
                     ("android", "--only", "A-1"), ("--dry-run",)):
            with self.subTest(args=args):
                result, calls = self.run_gate(*args)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(calls, [])

    def test_help_does_not_run_checks(self):
        result, calls = self.run_gate("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("full", result.stdout)
        self.assertEqual(calls, [])

    def test_project_profiles_load_from_config_with_policy_names(self):
        loaded = checks.load_checks()
        self.assertEqual(["android", "core", "data", "docs", "full", "release", "tools"],
                         sorted(loaded))
        for name, commands in loaded.items():
            with self.subTest(profile=name):
                self.assertTrue(commands)
                self.assertTrue(all(command.strip() for command in commands))
        self.assertEqual(DOCUMENTATION_COMMANDS, loaded["docs"])

    def test_unknown_profile_fails_and_lists_available_profiles(self):
        result, calls = self.run_gate("no-such-profile")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, [])
        self.assertIn("no-such-profile", result.stderr)
        for name in sorted(checks.load_checks()):
            self.assertIn(name, result.stderr)
        self.assertIn("orchestrator", result.stderr)

    def test_docs_profile_executes_real_documentation_checks(self):
        for command in checks.load_checks()["docs"]:
            with self.subTest(command=command):
                result = subprocess.run(["bash", "-c", command], cwd=REPO,
                                        capture_output=True, text=True, timeout=180)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # Проверка должна ловить поломку документации, а не быть заглушкой.
        with tempfile.TemporaryDirectory(prefix="docs check ") as temp:
            root = Path(temp) / "repo"
            (root / "tools").mkdir(parents=True)
            (root / "docs").mkdir()
            shutil.copy2(REPO / "tools" / "check_docs.py", root / "tools" / "check_docs.py")
            (root / "docs" / "INDEX.md").write_text("# Индекс\n", encoding="utf-8")
            readme = root / "README.md"
            readme.write_text("[нет](docs/MISSING.md)\n", encoding="utf-8")
            broken = subprocess.run([sys.executable, str(root / "tools" / "check_docs.py")],
                                    cwd=root, capture_output=True, text=True, timeout=60)
            self.assertNotEqual(broken.returncode, 0, broken.stdout)
            self.assertIn("docs/MISSING.md", broken.stderr)
            readme.write_text("[см.](README.md)\n", encoding="utf-8")
            fixed = subprocess.run([sys.executable, str(root / "tools" / "check_docs.py")],
                                   cwd=root, capture_output=True, text=True, timeout=60)
            self.assertEqual(fixed.returncode, 0, fixed.stdout + fixed.stderr)

    def test_example_queues_parse_and_use_configured_profiles(self):
        available = set(checks.load_checks()) | {"orchestrator"}
        for relative in ("orchestrator/TASKS.md", "orchestrator/examples/TASKS.md"):
            with self.subTest(queue=relative):
                tasks = orchestrator.parse_tasks(REPO / relative)
                self.assertTrue(tasks)
                used = set()
                for task in tasks:
                    if task.gate == "skip":
                        self.assertIn("Причина пропуска", task.prompt)
                        continue
                    used.update(re.findall(r"--check\s+([A-Za-z][A-Za-z0-9_-]*)", task.gate))
                self.assertEqual(set(), used - available, used - available)
                self.assertIn("docs", used)


if __name__ == "__main__":
    unittest.main()
