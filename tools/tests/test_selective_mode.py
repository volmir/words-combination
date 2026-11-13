"""SEL-05: выборочный режим prepare_v2; синтетический словарь, без сети и ИИ.

Локальные стадии (очистка, определения, профили, Bloom, проверка пакета) и
планировщик SEL-04 работают настоящим кодом. GEN-02..05 и JVM-процесс заменены
контролируемыми результатами: синтетический словарь не удовлетворяет
производственным 30-уровневым требованиям. ИИ-исполнитель подменяется
инъекционным `executor` провайдеров, чтобы считать фактические батчи модели без
сети и платных вызовов.
"""
import copy
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
DEFAULT_PLAN_BANDS = [
    {"first": 1, "last": 5, "min": 1, "max": 3},
    {"first": 6, "last": 10, "min": 1, "max": 3},
    {"first": 11, "last": 15, "min": 1, "max": 3},
    {"first": 16, "last": 20, "min": 1, "max": 3},
    {"first": 21, "last": 25, "min": 1, "max": 3},
    {"first": 26, "last": 30, "min": 9, "max": 12},
]
THEME_SOURCE = "хлопкопункт"
THEME_ANSWERS = ["хлоп", "клоп", "лук", "ток", "кот", "пот", "полк", "клок", "толк", "пункт"]


class SelectivePatchSetup:
    """Общая установка синтетического конвейера; тесты живут в подклассах."""

    # Сценарии SEL-06 меняют только синтетический вход, не пороги кампании.
    fixture_sources = None
    fixture_answers = None
    planning_bands = DEFAULT_PLAN_BANDS

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        if not hasattr(self, "soft_sign"):
            self.soft_sign = True
        self.sources = self.fixture_sources or (LEGACY_SOURCES if self.soft_sign
                                                else LEGACY_SOURCES_NO_SOFT_SIGN)
        self.answers = self.fixture_answers or (ANSWERS if self.soft_sign
                                                else ANSWERS_NO_SOFT_SIGN)
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
        self.executor_requests = []
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

    def write_plan_configs(self, root):
        """Этапы 1–5 с малыми окнами; сценарий T6 расширяет только окно Мастера."""
        config = {"schema": "semantic-request-plan-config-v1", "seed": 20261113,
                  "carriers_per_letter": 3, "stage_candidates_per_band": 12,
                  "iterations": 4, "new_decisions_per_iteration": 200,
                  "planning_bands": copy.deepcopy(self.planning_bands),
                  "planning_bands_override": True,
                  "rationale": {"planning_bands": "синтетические окна SEL-05/SEL-06"}}
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
                    self.executor_requests.append(dict(
                        task=request["task"],
                        roles=sorted({item["role"] for item in request["items"]}),
                        items=[dict(item) for item in request["items"]]))
                    return {"results": [dict(id=item["id"], status="suitable",
                                             reason="Синтетическая оценка",
                                             assessment_version="word-suitability-v1")
                                        for item in request["items"]]}
                kwargs["executor"] = suitability_executor
            else:
                def category_executor(request, schema):
                    self.executor_calls.append(request["task"])
                    self.executor_requests.append(dict(
                        task=request["task"],
                        roles=[],
                        items=[dict(item) for item in request["items"]]))
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

    def installed_bytes(self):
        return {str(path.relative_to(self.installed)): path.read_bytes()
                for path in sorted(self.installed.rglob("*")) if path.is_file()}

    @staticmethod
    def report_outcome(report):
        """Стабильная часть отчёта: решения, проверки и хэши, без часов и счётчиков кэша."""
        return {
            "schema": report["schema"],
            "status": report["status"],
            "inputs": report["inputs"],
            "budget": report["budget"],
            "rounds": report["rounds"],
            "checks": report["checks"],
            "failures": report["failures"],
            "deficits": report["deficits"],
            "hashes": report["hashes"],
            "accuracy": report["accuracy"],
            "iterations": report["totals"]["iterations"],
        }

    def workspace(self):
        return next((self.args.cache / "runs").iterdir())


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
        # (а) Готовый пакет воспроизводится повторным прогоном без новых вызовов.
        first, first_report = self.package_bytes(), self.report_outcome(report)
        calls = len(self.executor_calls)
        pipe.prepare(self.args)
        self.assertEqual(calls, len(self.executor_calls))
        self.assertEqual(first, self.package_bytes())
        self.assertEqual(first_report, self.report_outcome(self.report()))

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

    def test_gen_contracts_and_production_bands_are_not_relaxed(self):
        # В setUp полосы подменены синтетикой; здесь читаем закреплённые файлы.
        selection = pipe.read_json(pipe.selection.CONFIG)
        campaign = pipe.read_json(pipe.campaign.CONFIG)
        planner = pipe.planner.load_config(pipe.planner.CONFIG)
        self.assertEqual(30, selection["levels"])
        self.assertEqual((8, 15), (selection["retain_min"], selection["retain_max"]))
        self.assertEqual((0.03, 0.15), (selection["mean_jaccard_max"], selection["max_jaccard_max"]))
        self.assertEqual(40, campaign["category_global_min"])
        self.assertEqual(32, len(pipe.selection.read_alphabet()))
        self.assertEqual(selection["bands"], planner["planning_bands"])
        self.assertFalse(planner["planning_bands_override"])

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


class ThemeRequestTest(SelectivePatchSetup, unittest.TestCase):
    """(б) T6: только неизвестные ответы выбранного исходника Мастера."""

    fixture_sources = LEGACY_SOURCES + [THEME_SOURCE]
    fixture_answers = ANSWERS + [word for word in THEME_ANSWERS if word not in ANSWERS]
    planning_bands = [
        {"first": 1, "last": 5, "min": 1, "max": 3},
        {"first": 6, "last": 10, "min": 1, "max": 3},
        {"first": 11, "last": 15, "min": 1, "max": 3},
        {"first": 16, "last": 20, "min": 1, "max": 3},
        {"first": 21, "last": 25, "min": 1, "max": 3},
        {"first": 26, "last": 30, "min": 10, "max": 10},
    ]

    def test_theme_requests_only_master_answers_and_repeat_is_cached(self):
        self.install_fake_executors()
        self.args.iterations = 1
        result = pipe.prepare(self.args)
        self.assertEqual("prepared", result["status"])
        self.assertEqual(1, len(self.executor_calls))
        request = self.executor_requests[0]
        self.assertEqual("word-category-v1", request["task"])
        self.assertEqual(sorted(THEME_ANSWERS), sorted(item["word"] for item in request["items"]))
        self.assertEqual([], [row for row in self.executor_requests if row["task"] != "word-category-v1"])
        plan = pipe.read_json(self.workspace() / "round-01/plan-01/request_plan.json")
        self.assertEqual(sorted(THEME_ANSWERS), sorted(row["word"] for row in plan["category"]["requests"]))
        self.assertEqual([], plan["suitability"]["requests"])
        report = self.report()
        self.assertEqual(len(THEME_ANSWERS), report["totals"]["new_ai_categories"])
        self.assertEqual(0, report["totals"]["pending_categories"])
        self.assertEqual(0, report["totals"]["new_ai_suitability"])
        first, first_report = self.package_bytes(), self.report_outcome(report)
        calls = len(self.executor_calls)
        pipe.prepare(self.args)
        self.assertEqual(calls, len(self.executor_calls))
        self.assertEqual(first, self.package_bytes())
        self.assertEqual(first_report, self.report_outcome(self.report()))
        self.assertEqual(len(THEME_ANSWERS), self.report()["totals"]["cached_categories"])

    def test_theme_without_network_stays_unknown_and_never_other(self):
        self.args.iterations = 1
        self.gen_valid = False
        result = pipe.prepare(self.args)
        self.assertEqual("NEEDS_MORE_EVIDENCE", result["status"])
        self.assertEqual([], self.executor_calls)
        plan = pipe.read_json(self.workspace() / "round-01/plan-01/request_plan.json")
        self.assertEqual(sorted(THEME_ANSWERS), sorted(row["word"] for row in plan["category"]["requests"]))
        index = pipe.read_json(self.workspace() / "round-01/categories/category_index.json")
        report = pipe.read_json(result["report"])
        self.assertEqual(len(THEME_ANSWERS), report["totals"]["pending_categories"])
        self.assertEqual(0, report["totals"]["new_ai_categories"])
        for word in THEME_ANSWERS:
            metadata = index["word_metadata"][word]
            self.assertEqual("other", metadata["category"])
            self.assertTrue(metadata["category_source"].startswith("unknown:"), metadata)
            self.assertNotEqual("unknown:ai_other", metadata["category_source"])

    def test_changed_prompt_does_not_reuse_old_snapshot_or_cache(self):
        result, calls, bundle = self.first_theme_run()
        self.assertEqual("prepared", result["status"])
        self.gen_valid = False
        replay = self.changed_replay("prompt", bundle)
        with patch.object(game_data_providers.CategoryProvider, "prompt",
                          "SEL-06: изменённая версия промпта"):
            outcome = pipe.prepare(replay)
        self.assertEqual("NEEDS_MORE_EVIDENCE", outcome["status"])
        self.assertEqual(calls, len(self.executor_calls))
        report = pipe.read_json(outcome["report"])
        self.assertEqual(0, report["totals"]["new_ai_categories"])
        self.assertGreater(report["totals"]["pending_categories"], 0)

    def test_changed_taxonomy_does_not_reuse_old_snapshot_or_cache(self):
        result, calls, bundle = self.first_theme_run()
        self.assertEqual("prepared", result["status"])
        self.gen_valid = False
        replay = self.changed_replay("taxonomy", bundle)
        original = pipe.classification.thematic.load_taxonomy

        def extended(path=pipe.classification.thematic.TAXONOMY_PATH):
            taxonomy = original(path)
            taxonomy["sel06_synthetic"] = "Синтетическая рубрика SEL-06"
            return taxonomy

        with patch.object(pipe.classification.thematic, "load_taxonomy", side_effect=extended):
            outcome = pipe.prepare(replay)
        self.assertEqual("NEEDS_MORE_EVIDENCE", outcome["status"])
        self.assertEqual(calls, len(self.executor_calls))
        report = pipe.read_json(outcome["report"])
        self.assertEqual(0, report["totals"]["new_ai_categories"])
        self.assertGreater(report["totals"]["pending_categories"], 0)

    def first_theme_run(self):
        self.install_fake_executors()
        self.args.iterations = 1
        result = pipe.prepare(self.args)
        bundle = (self.args.package / "category_decisions.json").read_bytes()
        return result, len(self.executor_calls), bundle

    def changed_replay(self, label, bundle):
        replay = pipe.NS(**vars(self.args))
        replay.cache = self.root / ("changed-" + label + "-cache")
        replay.package = self.root / ("changed-" + label + "-package")
        replay.allow_network = False
        replay.category_snapshot = self.root / ("old-" + label + "-category.json")
        replay.category_snapshot.write_bytes(bundle)
        return replay


class TargetRoleTest(SelectivePatchSetup, unittest.TestCase):
    """(в) и (г): новые цели T3/T4 и исчерпанный бюджет."""

    def setUp(self):
        super().setUp()
        self.frequencies["рана"] = 50
        self.frequencies["часик"] = 50
        self.write_freq()
        self.pin_frequency()

    def test_new_targets_request_only_target_roles_and_repeat_is_cached(self):
        self.install_fake_executors()
        self.args.iterations = 1
        result = pipe.prepare(self.args)
        self.assertEqual("prepared", result["status"])
        self.assertEqual(1, len(self.executor_calls))
        request = self.executor_requests[0]
        self.assertEqual("word-suitability-v1", request["task"])
        self.assertEqual(["target"], request["roles"])
        self.assertEqual(["рана", "часик"], sorted(item["word"] for item in request["items"]))
        self.assertEqual([], [row for row in self.executor_requests if row["task"] != "word-suitability-v1"])
        plan = pipe.read_json(self.workspace() / "round-01/plan-01/request_plan.json")
        self.assertEqual([], plan["category"]["requests"])
        rows = {(row["word"], row["role"]): row for row in plan["suitability"]["requests"]}
        self.assertEqual({("рана", "target"), ("часик", "target")}, set(rows))
        self.assertTrue(all(row["role"] == "target" for row in rows.values()))
        self.assertTrue(all(any(reason["code"] == "assignment" for reason in row["reasons"])
                            for row in rows.values()))
        self.assertEqual(0, self.report()["totals"]["new_ai_suitability_source"])
        first, first_report = self.package_bytes(), self.report_outcome(self.report())
        calls = len(self.executor_calls)
        pipe.prepare(self.args)
        self.assertEqual(calls, len(self.executor_calls))
        self.assertEqual(first, self.package_bytes())
        self.assertEqual(first_report, self.report_outcome(self.report()))

    def test_exhausted_budget_keeps_needs_more_evidence_and_data_untouched(self):
        self.install_fake_executors()
        self.args.iterations = 1
        self.args.total_new_decisions = 1
        self.gen_valid = False
        before = self.installed_bytes()
        result = pipe.prepare(self.args)
        self.assertEqual("NEEDS_MORE_EVIDENCE", result["status"])
        self.assertEqual(1, len(self.executor_calls))
        self.assertEqual(1, len(self.executor_requests[0]["items"]))
        self.assertEqual(before, self.installed_bytes())
        self.assertFalse((self.args.package / "manifest.json").exists())
        first = self.report()
        self.assertEqual(1, first["totals"]["new_ai_suitability"])
        self.assertGreater(first["totals"]["pending_suitability"], 0)
        first_report = self.report_outcome(first)
        calls = len(self.executor_calls)
        # Повтор с тем же кэшем/журналом: совокупный лимит уже израсходован.
        pipe.prepare(self.args)
        self.assertEqual(calls, len(self.executor_calls))
        self.assertEqual(before, self.installed_bytes())
        second = self.report()
        self.assertEqual(0, second["totals"]["new_ai_suitability"])
        self.assertEqual(first["totals"]["pending_suitability"],
                         second["totals"]["pending_suitability"])
        # Повтор лишь добавляет диагностику исчерпанного бюджета; решения,
        # проверки, дефициты GEN и хэши остаются прежними.
        repeated = self.report_outcome(second)
        repeated["deficits"] = [row for row in repeated["deficits"]
                                if row["reason_code"] != "no_progress"]
        self.assertEqual(first_report, repeated)
        self.assertTrue(any(row["reason_code"] == "no_progress" for row in second["deficits"]))

    def test_gen04_gen05_rejection_never_publishes(self):
        self.install_fake_executors()
        self.args.iterations = 1
        self.gen_valid = True
        before = self.installed_bytes()
        with patch.object(pipe.rebalance, "run",
                          side_effect=lambda args: {"installable": False, "campaign": {}}):
            result = pipe.prepare(self.args)
        self.assertEqual("NEEDS_MORE_EVIDENCE", result["status"])
        self.assertEqual(before, self.installed_bytes())
        self.assertFalse((self.args.package / "manifest.json").exists())
        report = pipe.read_json(result["report"])
        self.assertTrue(any("GEN-04/GEN-05" in failure for failure in report["failures"]))
        self.assertFalse(report["checks"]["verify_package"])
        self.assertEqual(0, self.validation.call_count)


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
