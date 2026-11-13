"""SEL-05: выборочный режим prepare_v2; синтетический словарь, без сети и ИИ.

Локальные стадии (очистка, определения, профили, Bloom, проверка пакета) и
планировщик SEL-04 работают настоящим кодом. GEN-02..05 и JVM-процесс заменены
контролируемыми результатами: синтетический словарь не удовлетворяет
производственным 30-уровневым требованиям. ИИ-исполнитель подменяется
инъекционным `executor` провайдеров, чтобы считать фактические батчи модели без
сети и платных вызовов.
"""
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import game_data_providers
from game_data_providers import atomic_json
import prepare_v2 as pipe

# Синтетические исходники 8–13; версия без «ь» — для структурной невозможности.
LEGACY_SOURCES = [
    "карандаш", "голубизна", "метелица", "эфыющъыцх", "почасовик",
    "жарайвикя", "пыльшерблю", "парасловка",
]
LEGACY_SOURCES_NO_SOFT_SIGN = [
    "карандаш", "голубизна", "метелица", "эфыющъыцх", "почасовик",
    "жарайвикя", "плышерблуе", "парасловка",
]
ANSWERS = ["рана", "дар", "арка", "изба", "губа", "мили", "лета", "эфыю", "часик", "поча",
           "ражка", "жарая", "шерблю", "пыль", "слово", "пара", "пункт", "толк",
           "свет", "совет", "роли", "травя"]
ANSWERS_NO_SOFT_SIGN = ["рана", "дар", "арка", "изба", "губа", "мили", "лета", "эфыю",
                        "часик", "поча", "ражка", "жарая", "шерблю", "плыл", "слово", "пара",
                        "пункт", "толк", "свет", "совет", "роли", "травя"]


class SelectivePatchSetup:
    """Общая установка синтетического конвейера; тесты живут в подклассах."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        if not hasattr(self, "soft_sign"):
            self.soft_sign = True
        self.sources = LEGACY_SOURCES if self.soft_sign else LEGACY_SOURCES_NO_SOFT_SIGN
        self.answers = ANSWERS if self.soft_sign else ANSWERS_NO_SOFT_SIGN
        self.words = sorted(set(self.sources) | set(self.answers) | pipe.suitability.PREFERRED_SOURCES)
        self.installed = self.root / "installed"
        self.installed.mkdir()
        self.manifest = self.installed / "manifest.json"
        self.plan_config = self.write_plan_configs(self.root)
        self.dump = self.root / "dump.jsonl"
        self.install_fixture_files()
        self.legacy = self.root / "legacy.json"
        atomic_json(self.legacy, [dict(index=i, word=w, answers=999, words=999,
                                       letter="я", letterCount=999)
                                  for i, w in enumerate(self.sources, 1)])
        self.args = pipe.NS(dump=self.dump, manifest=self.manifest, source_words=self.source,
                            frequency_list=self.freq, cache=self.root / "cache",
                            installed=self.installed, package=self.root / "ready", model="fixture",
                            provider="codex", category_snapshot=None, suitability_snapshot=None,
                            allow_network=False, max_rounds=4, java="unavailable", jvm_classpath=None,
                            selective=True, selective_config=None, plan_config=self.plan_config,
                            plan_selection_config=None, iterations=None, max_new_decisions=None,
                            max_batches=None, time_budget=None, total_new_decisions=None,
                            total_time_budget=None)
        self.gen_valid = True
        self.executor_calls = []
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(pipe, "LEGACY", self.legacy))
        self.stack.enter_context(patch.object(pipe, "processor_inputs", return_value={"fixture": "v1"}))
        self.stack.enter_context(patch.object(pipe.selection, "load_config",
                                              side_effect=lambda *_args: self.selection_bands()))
        self.stack.enter_context(patch.object(pipe, "jvm_hints", side_effect=self.hints))
        self.stack.enter_context(patch.object(pipe.selection, "run", side_effect=self.select))
        self.stack.enter_context(patch.object(pipe.campaign, "run", side_effect=self.generate))
        self.stack.enter_context(patch.object(pipe.rebalance, "run", side_effect=self.balance))
        self.validation = self.stack.enter_context(patch.object(pipe.validation, "run",
                                                                side_effect=self.validate))
        self.stack.enter_context(patch.object(pipe.rebalance, "models", return_value=[object()]))
        self.stack.enter_context(patch.object(pipe.simulation, "simulate",
                                              return_value={"deviations": []}))

    def install_fixture_files(self):
        self.frequencies = {word: 1000 for word in self.words}
        self.source = self.root / "source.txt"
        self.source.write_bytes(pipe.canonical(self.words))
        self.freq = self.root / "freq.txt"
        self.write_freq()
        entries = []
        for word in sorted(self.words):
            tags = ["rare"] if word in pipe.suitability.PREFERRED_SOURCES else []
            entries.append(dict(word=word, lang="Русский", lang_code="ru", pos="noun",
                                senses=[{"glosses": ["Синтетический предмет для проверки конвейера."],
                                         "tags": tags}]))
        self.dump.write_text("\n".join(json.dumps(e, ensure_ascii=False) for e in entries))
        atomic_json(self.manifest, dict(
            source={"sha256_input": pipe.sha(self.dump), "dump_date": "2026-11-01"},
            selection=dict(input_words=len(self.words), input_words_sha256=pipe.sha(self.source),
                           frequency_source={"snapshot": pipe.frequency.REVISION,
                                             "sha256": pipe.sha(self.freq)})))

    def write_freq(self):
        self.freq.write_text("\n".join(
            "%s %d" % (word, self.frequencies[word]) for word in sorted(self.words)) + "\n")

    def pin_frequency(self):
        manifest = pipe.read_json(self.manifest)
        manifest["selection"]["frequency_source"]["sha256"] = pipe.sha(self.freq)
        atomic_json(self.manifest, manifest)

    def selection_bands(self):
        """Полосы GEN-02, достижимые синтетическим словарём (для планировщика)."""
        base = json.loads(pipe.selection.CONFIG.read_text(encoding="utf-8"))
        base["bands"] = [{"first": 1, "last": 5, "min": 1, "max": 10},
                         {"first": 6, "last": 10, "min": 1, "max": 10},
                         {"first": 11, "last": 15, "min": 1, "max": 10},
                         {"first": 16, "last": 20, "min": 1, "max": 10},
                         {"first": 21, "last": 25, "min": 1, "max": 10},
                         {"first": 26, "last": 30, "min": 1, "max": 10}]
        return base

    @staticmethod
    def write_plan_configs(root):
        """Этапы 1–5 с малыми окнами; Мастер (9–12) недостижим — без запросов T6."""
        config = {"schema": "semantic-request-plan-config-v1", "seed": 20261113,
                  "carriers_per_letter": 3, "stage_candidates_per_band": 12,
                  "iterations": 4, "new_decisions_per_iteration": 200,
                  "planning_bands": [
                      {"first": 1, "last": 5, "min": 1, "max": 3},
                      {"first": 6, "last": 10, "min": 1, "max": 3},
                      {"first": 11, "last": 15, "min": 1, "max": 3},
                      {"first": 16, "last": 20, "min": 1, "max": 3},
                      {"first": 21, "last": 25, "min": 1, "max": 3},
                      {"first": 26, "last": 30, "min": 9, "max": 12}],
                  "planning_bands_override": True,
                  "rationale": {"planning_bands": "синтетические окна SEL-05"}}
        path = root / "plan-config.json"
        atomic_json(path, config)
        return path

    @staticmethod
    def hints(args, database, words, directory):
        target = directory / "hints.jvm.jsonl"
        lines = [pipe.profiles.hint_header(database, words)]
        lines.extend(dict(word=w, hint="Синтетическая подсказка.") for w in pipe.dictionary.read_list(words))
        target.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in lines))
        return target

    @staticmethod
    def select(args):
        return {"installable": True, "slots": [{"word": "карандаш"}]}

    def generate(self, args):
        data = {"version": 2, "countMinLength": 4, "stages": [{"number": 1, "firstLevel": 1, "lastLevel": 1}],
                "levels": [dict(number=1, word="карандаш", stage=1, requiredStars=1,
                                tasks=[dict(id="l1-t1", type="T1", n=1)])]}
        atomic_json(args.output / "campaign.json", data)
        return {"status": "valid" if self.gen_valid else "invalid"}

    @staticmethod
    def balance(args):
        data = pipe.read_json(args.campaign)
        atomic_json(args.output / "balanced_campaign.json", data)
        return {"installable": True, "campaign": data}

    @staticmethod
    def validate(args):
        manifest = pipe.read_json(args.manifest)
        assert pipe.sha(args.words) == manifest["sha256_words_txt"]
        assert pipe.sha(args.database) == manifest["definitions"]["sha256"]
        return pipe.read_json(args.campaign), {"installable": True, "jvm_checked": True,
            "unreachable": [], "balance_deviations": [], "parity_deviations": []}

    def install_fake_executors(self):
        """Реальные провайдеры с инъекционным исполнителем; считает батчи модели."""
        real_init = game_data_providers.CategoryProvider.__init__

        def initial(instance, cache, model, taxonomy, **kwargs):
            if isinstance(taxonomy, dict) and taxonomy.get("version") == "word-suitability-v1":
                def suitability_executor(request, schema):
                    self.executor_calls.append(request["task"])
                    return {"results": [dict(id=item["id"], status="suitable",
                                             reason="Синтетическая оценка",
                                             assessment_version="word-suitability-v1")
                                        for item in request["items"]]}
                kwargs["executor"] = suitability_executor
            else:
                def category_executor(request, schema):
                    self.executor_calls.append(request["task"])
                    return {"results": [dict(id=item["id"], category="other", confidence=0.95)
                                        for item in request["items"]]}
                kwargs["executor"] = category_executor
            real_init(instance, cache, model, taxonomy, **kwargs)
        self.stack.enter_context(patch.object(game_data_providers.CategoryProvider,
                                               "__init__", initial))
        self.args.allow_network = True

    def package_bytes(self):
        return {name: (self.args.package / name).read_bytes()
                for name in pipe.PACKAGE_ARTIFACTS}

    def report(self):
        workspace = next((self.args.cache / "runs").iterdir())
        return pipe.read_json(workspace / "selective_report.json")


class SelectivePipelineTest(SelectivePatchSetup, unittest.TestCase):
    def setUp(self):
        super().setUp()

    def test_valid_mini_campaign_finishes_without_ai(self):
        result = pipe.prepare(self.args)
        self.assertEqual("prepared", result["status"])
        self.assertEqual(1, result["rounds"])
        report = self.report()
        self.assertEqual("VALID", report["status"])
        self.assertEqual([], self.executor_calls)
        self.assertEqual(0, report["totals"]["new_ai_categories"])
        self.assertEqual(0, report["totals"]["new_ai_suitability"])
        self.assertEqual(0, report["totals"]["pending_categories"])
        self.assertEqual(0, report["totals"]["pending_suitability"])
        self.assertEqual(1, report["totals"]["iterations"])
        self.assertTrue(report["checks"]["published"])
        self.assertTrue(report["checks"]["verify_package"])
        self.assertTrue(report["hashes"])
        self.assertGreater(self.validation.call_count, 0)
        # Готовый пакет воспроизводится повторным прогоном без новых вызовов.
        first = self.package_bytes()
        pipe.prepare(self.args)
        self.assertEqual(first, self.package_bytes())

    def test_unready_campaign_requests_one_limited_batch_and_reports(self):
        self.frequencies["рана"] = 50
        self.write_freq()
        self.pin_frequency()
        self.gen_valid = False
        result = pipe.prepare(self.args)
        self.assertEqual("NEEDS_MORE_EVIDENCE", result["status"])
        self.assertEqual(1, result["iterations"])
        self.assertIn("--selective", result["resume"])
        report = pipe.read_json(result["report"])
        self.assertEqual(0, report["totals"]["new_ai_suitability"])
        self.assertEqual(1, report["totals"]["pending_suitability"])
        self.assertEqual([], self.executor_calls)
        reasons = [row["reason_code"] for row in report["deficits"]]
        self.assertIn("no_progress", reasons)
        self.assertTrue(any("GEN-03" in failure for failure in report["failures"]))
        # Неполный пакет не публиковался; при установке данных также не было.
        self.assertFalse((self.args.package / "manifest.json").exists())
        self.assertFalse((self.installed / "campaign.json").exists())

    def test_limited_batch_leaves_partial_cache_but_no_data(self):
        self.frequencies["рана"] = 50
        self.frequencies["изба"] = 50
        self.write_freq()
        self.pin_frequency()
        self.install_fake_executors()
        self.args.iterations = 1
        self.args.max_new_decisions = 1
        self.gen_valid = False
        result = pipe.prepare(self.args)
        self.assertEqual("NEEDS_MORE_EVIDENCE", result["status"])
        self.assertEqual(1, len(self.executor_calls))
        # Частичный кэш сохранён ровно для оплаченного батча (один ключ).
        cache = list((self.args.cache / "suitability").glob("*.json"))
        self.assertEqual(1, len(cache))
        self.assertFalse((self.args.package / "manifest.json").exists())
        self.assertFalse((self.installed / "campaign.json").exists())

    def test_resume_reuses_cache_and_never_repeats_batches(self):
        self.frequencies["рана"] = 50
        self.frequencies["изба"] = 50
        self.write_freq()
        self.pin_frequency()
        self.install_fake_executors()
        self.args.iterations = 3
        self.args.max_new_decisions = 1
        self.gen_valid = False
        result = pipe.prepare(self.args)
        self.assertEqual("NEEDS_MORE_EVIDENCE", result["status"])
        self.assertEqual(2, len(self.executor_calls))  # по одному решению на итерацию
        journal = pipe.read_json(next((self.args.cache / "selective-runs").rglob("journal.json")))
        self.assertEqual(2, journal["cumulative"]["new_ai_suitability"])
        self.assertEqual(3, journal["cumulative"]["iterations"])
        # Возобновление: оставшиеся решения — из кэша; новые батчи не платятся.
        self.gen_valid = True
        pipe.prepare(self.args)
        self.assertEqual(2, len(self.executor_calls))
        first = self.package_bytes()
        pipe.prepare(self.args)
        self.assertEqual(first, self.package_bytes())

    def test_mid_failure_preserves_last_good_staging(self):
        pipe.prepare(self.args)
        ready = self.package_bytes()
        before = self.manifest.read_bytes()

        def fail(args):
            raise ValueError("инъектированный сбой")

        with patch.object(pipe.rebalance, "run", side_effect=fail):
            with self.assertRaisesRegex(ValueError, "инъектированный"):
                pipe.prepare(self.args)
        self.assertEqual(ready, self.package_bytes())
        self.assertEqual(before, self.manifest.read_bytes())

    def test_compact_snapshot_replays_offline_and_invalidates_on_change(self):
        self.frequencies["рана"] = 50
        self.frequencies["изба"] = 50
        self.write_freq()
        self.pin_frequency()
        self.install_fake_executors()
        first = pipe.prepare(self.args)
        self.assertEqual("prepared", first["status"])
        self.assertEqual(1, len(self.executor_calls))  # один батч из двух решений
        reference = self.package_bytes()
        bundle_category = pipe.read_json(self.args.package / "category_decisions.json")
        bundle_suitability = pipe.read_json(self.args.package / "suitability_decisions.json")
        self.assertEqual("v2-decision-bundle-v1", bundle_category["schema"])
        self.assertEqual("v2-decision-bundle-v1", bundle_suitability["schema"])
        items = [item for snapshot in bundle_suitability["snapshots"] for item in snapshot["items"]]
        self.assertEqual(2, len(items))  # только реально использованные решения
        # Строгий офлайн-повтор: свежие кэши, только компактные снимки, без сети.
        replay = pipe.NS(**vars(self.args))
        replay.cache = self.root / "replay-cache"
        replay.allow_network = False
        replay.category_snapshot = self.root / "category-decisions.json"
        replay.suitability_snapshot = self.root / "suitability-decisions.json"
        replay.category_snapshot.write_bytes((self.args.package / "category_decisions.json").read_bytes())
        replay.suitability_snapshot.write_bytes((self.args.package / "suitability_decisions.json").read_bytes())
        result = pipe.prepare(replay)
        self.assertEqual("prepared", result["status"])
        self.assertEqual(reference, self.package_bytes())
        # Изменённый вход инвалидирует решения: они не переиспользуются молча.
        self.frequencies["рана"] = 60
        self.write_freq()
        self.pin_frequency()
        changed = pipe.NS(**vars(replay))
        changed.cache = self.root / "invalidated-cache"
        self.gen_valid = False
        outcome = pipe.prepare(changed)
        self.assertEqual("NEEDS_MORE_EVIDENCE", outcome["status"])
        report = pipe.read_json(outcome["report"])
        self.assertEqual(0, report["totals"]["new_ai_suitability"])
        self.assertEqual(2, report["totals"]["pending_suitability"])  # решения не переиспользованы

    @staticmethod
    def cached_exchange(provider, items, generate=True):
        """Синтетические ответы без всякого процесса провайдера (полный режим)."""
        answers = {}
        for item in items:
            previous = provider.read(item)
            if previous is not None:
                answers[item["id"]] = previous
                continue
            if not generate:
                continue
            result = (pipe.suitability.decision(item, "suitable", "Синтетическая оценка")
                      if isinstance(provider, pipe.suitability.SuitabilityProvider) else
                      dict(id=item["id"], category="other", confidence=1.0))
            provider.store(item, result)
            answers[item["id"]] = result
        pending = len(items) - len(answers)
        return answers, pending, "Нет офлайн-решения" if pending else None

    def test_full_mode_survives_side_by_side(self):
        # Старый полный режим не затрагивается: без --selective не создаётся
        # журнал и не запрашиваются выборочные конфиги.
        full = pipe.NS(**vars(self.args))
        full.selective = False
        with patch.object(game_data_providers.CategoryProvider, "exchange",
                          self.cached_exchange):
            result = pipe.prepare(full)
        self.assertEqual("prepared", result["status"])
        self.assertFalse(any((self.args.cache / "selective-runs").rglob("journal.json")))

    def test_config_budget_and_journal_machinery(self):
        document = pipe.load_selective_config()
        self.assertEqual("selective-mode-config-v1", document["schema"])
        self.assertLess(document["total_time_budget_s"], 3600)
        overrides = pipe.NS(iterations=2, max_new_decisions=7, max_batches=3, time_budget=11.5,
                            total_new_decisions=100, total_time_budget=2000.0)
        pipe.apply_selective_overrides(document, overrides)
        self.assertEqual(2, document["iterations_per_round"])
        self.assertEqual(7, document["max_new_decisions_per_iteration"])
        self.assertEqual(11.5, document["time_budget_per_iteration_s"])
        journal = pipe.empty_selective_journal({"x": 1})
        budget = pipe.calc_iteration_budget(document, journal)
        self.assertEqual(7, budget["max_new"])
        self.assertEqual(11.5, budget["time_budget"])
        journal["cumulative"]["new_ai_suitability"] = 99
        budget = pipe.calc_iteration_budget(document, journal)
        self.assertEqual(1, budget["max_new"])  # совокупный лимит DATA-01 действует
        with self.assertRaisesRegex(ValueError, "положительным"):
            pipe.apply_selective_overrides(dict(document), pipe.NS(iterations=0))

    def test_stale_bundle_with_wrong_context_is_not_imported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            index = {"schema": "word-category-index-v1", "word_metadata": {},
                     "classification_features": {}}
            atomic_json(root / "index.json", index)
            (root / "words.txt").write_text("", encoding="utf-8")
            frozen = {"schema": "v2-decision-bundle-v1", "snapshots": [{
                "schema": "word-category-decisions-v1",
                "context": {"task": "irrelevant", "prompt_sha256": "x", "taxonomy_sha256": "x",
                            "schema_sha256": "x", "provider": "codex", "model": "fixture"},
                "taxonomy": {}, "input_sha256": "x", "items": [], "results": [],
                "complete": True, "policy": {"min_confidence": 0.8},
                "sha256": "x"}]}
            atomic_json(root / "bundle.json", frozen)
            arguments = pipe.NS(input=root / "index.json", snapshot=root / "bundle.json",
                                cache=root / "cache", model="fixture", provider="codex",
                                words_file=root / "words.txt", taxonomy=pipe.TOOLS / "categories.json",
                                min_confidence=0.8)
            seen = []
            outcome = pipe.selective_decisions(lambda a: seen.append(1) or {"pending": 0},
                                               arguments, False)
            self.assertEqual(1, len(seen))
            self.assertEqual(0, outcome["pending"])


class InfeasibleFixtureTest(SelectivePatchSetup, unittest.TestCase):
    def setUp(self):
        self.soft_sign = False
        super().setUp()

    def test_proven_infeasibility_never_spends_ai(self):
        self.install_fake_executors()
        result = pipe.prepare(self.args)
        self.assertEqual("IMPOSSIBLE_UNDER_CONSTRAINTS", result["status"])
        self.assertEqual(1, result["iterations"])
        self.assertEqual([], self.executor_calls)
        report = pipe.read_json(result["report"])
        self.assertTrue(any(row["reason_code"] == "infeasible" for row in report["deficits"]))
        self.assertFalse((self.args.package / "manifest.json").exists())


class TerminationGuardTest(unittest.TestCase):
    def test_sigterm_kills_the_whole_process_group(self):
        script = (
            "import subprocess, sys, time\n"
            "sys.path.insert(0, %r)\n"
            "import termination_guard as guard\n"
            "guard.become_group_leader()\n"
            "guard.install_termination_handler()\n"
            "child = subprocess.Popen(['sleep', '60'])\n"
            "print(child.pid, flush=True)\n"
            "time.sleep(60)\n"
        ) % str(pipe.TOOLS)
        process = subprocess.Popen([sys.executable, "-c", script], stdout=subprocess.PIPE, text=True)
        try:
            child = int(process.stdout.readline())
        finally:
            process.stdout.close()
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=10)
        # Эскалация гарантирует группу целиком: процесс покидает её по SIGTERM
        # или по эскалационному SIGKILL группы (returncode<0).
        self.assertIn(process.returncode, (143, -9, -15))
        deadline = time.monotonic() + 5
        alive = True
        while alive and time.monotonic() < deadline:
            stat = Path("/proc/%d/stat" % child)
            if not stat.exists():
                alive = False
                break
            state = stat.read_bytes().split(b")", 1)[-1].split(b" ")[1]
            if state == b"Z":
                alive = False
            else:
                time.sleep(0.05)
        self.assertFalse(alive, "дочерний процесс пережил управляемое завершение группы")


if __name__ == "__main__":
    unittest.main()
