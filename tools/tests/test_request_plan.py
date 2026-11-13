"""SEL-04: синтетический словарь; планировщик не запускает ИИ и не лезет в сеть."""
import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import assess_word_suitability as assess
import build_campaign as campaign_gen
import classify_word_categories as classify
import plan_semantic_requests as plan
import pick_level_words as profiles
import select_level_words as selection
import word_categories as thematic
from game_data_providers import CategoryProvider, atomic_json, json_digest

ALPHABET = selection.read_alphabet()
TAXONOMY = thematic.load_taxonomy()
REAL_BANDS = selection.load_config(selection.CONFIG)["bands"]

# Полосы планирования для синтетических словарей: те же формы, малые окна.
BANDS_SMALL = [
    {"first": 1, "last": 5, "min": 1, "max": 2},
    {"first": 6, "last": 10, "min": 1, "max": 2},
    {"first": 11, "last": 15, "min": 3, "max": 4},
    {"first": 16, "last": 20, "min": 5, "max": 6},
    {"first": 21, "last": 25, "min": 7, "max": 8},
    {"first": 26, "last": 30, "min": 9, "max": 12},
]
BANDS_WIDE = [dict(band, min=2, max=999) for band in BANDS_SMALL]


def target_row(word, freq=12, rank=90000, noun=True, decision="review", labels=(), reachable=True):
    return {"word": word, "role": "target", "features": dict(
        noun=noun, role_decision=decision, reachable=reachable, frequency=freq,
        freq_rank=rank, labels=sorted(labels))}


def suitable(word):
    return target_row(word, freq=150, rank=500, decision="keep")


def source_row(word, *, noun=True, freq=12, rank=90000, decision="review",
               labels=(), reachable=True):
    return {"word": word, "role": "source", "features": dict(
        noun=noun, role_decision=decision, reachable=reachable, frequency=freq,
        freq_rank=rank, labels=sorted(labels))}


def category_index(dict_words, overrides):
    metadata = {}
    for word in sorted(set(dict_words)):
        metadata[word] = {"category": "other", "category_source": "unknown:no_thematic_evidence",
                          "forms": [word], "related": []}
    for word, (category, source) in overrides.items():
        metadata[word] = dict(metadata[word], category=category, category_source=source)
    return thematic.category_index(metadata, TAXONOMY)


def hints_text(dict_words, useful):
    header = {"schema": "definition-hint-audit-v1", "masker_version": "synthetic",
              "cleaner_version": "synthetic"}
    lines = [json.dumps(header, ensure_ascii=False)]
    for word in sorted(dict_words):
        lines.append(json.dumps({"word": word, "hint": "синтетическая подсказка" if word in useful else None},
                                ensure_ascii=False))
    return "\n".join(lines) + "\n"


def planner_config(root, bands):
    document = {"schema": "semantic-request-plan-config-v1", "seed": 20261111,
                "carriers_per_letter": 3, "stage_candidates_per_band": 12,
                "iterations": 4, "new_decisions_per_iteration": 200,
                "planning_bands": bands, "planning_bands_override": True,
                "rationale": {"planning_bands": "синтетические окна теста"}}
    path = Path(root) / "plan-config.json"
    atomic_json(path, document)
    return path


def write_lexical_files(output, words, sources, document, index, hints, previous):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "words.txt").write_text("\n".join(sorted(set(words))) + "\n", encoding="utf-8")
    atomic_json(output / "suitability_input.json", document)
    atomic_json(output / "category_index.json", index)
    (output / "hints.jvm.jsonl").write_text(hints, encoding="utf-8")
    atomic_json(output / "levels.json", previous)
    return output


def make_args(output, *, words, suitability_input, categories, hints, previous,
              bands, model=None, cache_suitability=None, cache_categories=None,
              decisions=None):
    config = planner_config(output, bands)
    return argparse.Namespace(
        words=Path(output) / words, suitability_input=Path(output) / suitability_input,
        categories=Path(output) / categories, hints=Path(output) / hints,
        previous_levels=Path(output) / previous,
        output=Path(output) / "reports" / "request_plan.json", config=config,
        profiles_config=profiles.CONFIG, selection_config=selection.CONFIG,
        campaign_config=campaign_gen.CONFIG, alphabet=selection.ALPHABET,
        suitability_decisions=decisions, cache_suitability=cache_suitability,
        cache_categories=cache_categories, model=model, provider="codex")


class PlanFixtures:
    """Полный синтетический словарь с фоном, запретами, предпочтениями и историей."""

    SOURCES = ["карандаш", "шарманка", "голубизна", "метелица", "карокшинсати",
               "эфыющъыцх", "хлопкопункт", "реввоенсовет", "селитроварня"]
    BANNED = "праймериз"
    WORDS = ["рана", "шрам", "дар", "горн", "зубр", "город", "мел", "сметана",
             "маслице", "эфы", "ющъ", "ыхъ", "печать", "пядь", "ячмень",
             "старик", "краса", "стирка", "ишак", "кошка", "каркас",
             "шептать", "бежать"] + SOURCES + [BANNED]
    HINTED = {"рана", "шрам", "дар", "старик", "краса", "стирка", "ющъ", "эфы",
              "печать", "пядь", "ячмень", "город"}
    SUITABLE = {"дар", "эфы", "город", "ишак", "кошка", "каркас"}
    BACKGROUND = {"печать", "пядь", "ячмень"}

    def __init__(self):
        self.targets = {}
        for word in self.WORDS:
            if word == self.BANNED:
                self.targets[word] = target_row(word, noun=True, decision="keep",
                                                freq=1000, rank=1)
            elif word in self.SUITABLE:
                self.targets[word] = suitable(word)
            elif word == "ыхъ":
                self.targets[word] = target_row(word, freq=5)
            elif word in {"шептать", "бежать"}:
                self.targets[word] = target_row(word, noun=False)
            else:
                self.targets[word] = target_row(word)
        self.source_rows = {}
        for word in self.SOURCES:
            if word in assess.PREFERRED_SOURCES:
                self.source_rows[word] = source_row(word, freq=0, rank=None, decision="keep",
                                                   labels=["historical"])
            else:
                self.source_rows[word] = source_row(word)
        self.source_rows[self.BANNED] = source_row(self.BANNED, decision="keep",
                                                  freq=1000, rank=1)
        candidates = [self.targets[word] for word in sorted(self.WORDS)]
        candidates += [self.source_rows[word] for word in sorted(self.SOURCES)]
        candidates.append(self.source_rows[self.BANNED])
        self.document = {"schema": "word-suitability-input-v1", "candidates": candidates}
        self.index = category_index(self.WORDS, {
            "эфы": ("plants", "gloss_head:plants"),
            "шрам": ("other", "unknown:ai_other")})
        self.hints = hints_text(self.WORDS, self.HINTED)
        self.previous = [{"word": "карандаш", "index": 1}, {"word": "метелица", "index": 2}]

    def write(self, output):
        write_lexical_files(output, self.WORDS, self.SOURCES, self.document, self.index,
                           self.hints, self.previous)
        return Path(output)

    def args(self, output=None, model=None, caches=(), decisions=None):
        output = self.write(output)
        return make_args(output, words="words.txt", suitability_input="suitability_input.json",
                         categories="category_index.json", hints="hints.jvm.jsonl",
                         previous="levels.json", bands=BANDS_SMALL, model=model,
                         cache_suitability=caches[0] if len(caches) > 0 else None,
                         cache_categories=caches[1] if len(caches) > 1 else None,
                         decisions=decisions)


class TinyFixture:
    """Один исходник Мастера плюс фоновые и запрещённые слова."""

    SOURCE = "карокшинсати"
    WORDS = ["рана", "старик", "краса", "стирка", "ишак", "кошка", "каркас",
             "печать", "праймериз", SOURCE]
    HINTED = {"рана", "старик", "краса", "стирка", "печать", "праймериз"}
    SUITABLE = {"ишак", "кошка", "каркас"}

    def __init__(self, words=None, sources=None, suitable_words=None, hinted=None,
                 category_overrides=None):
        self.words = list(words or self.WORDS)
        self.sources = sources if sources is not None else [self.SOURCE]
        self.supp_suitable = set(suitable_words) if suitable_words is not None else set(self.SUITABLE)
        self.supp_hinted = set(hinted) if hinted is not None else set(self.HINTED)
        overrides = category_overrides or {}
        targets = {}
        for word in self.words:
            if word == "праймериз":
                targets[word] = target_row(word, noun=True, decision="keep", freq=1000, rank=1)
            elif word in self.supp_suitable:
                targets[word] = suitable(word)
            else:
                targets[word] = target_row(word)
        candidates = [targets[word] for word in sorted(self.words)]
        candidates += [source_row(word, freq=12, rank=90000) for word in sorted(self.sources)]
        if "праймериз" in self.words:
            candidates.append(source_row("праймериз", decision="keep", freq=1000, rank=1))
        self.document = {"schema": "word-suitability-input-v1", "candidates": candidates}
        self.index = category_index(self.words, overrides)
        self.hints = hints_text(self.words, self.supp_hinted)
        self.previous = [{"word": source, "index": 26} for source in self.sources]

    def write(self, output):
        write_lexical_files(output, self.words, self.sources, self.document, self.index,
                           self.hints, self.previous)
        return Path(output)

    def args(self, output=None, model=None, caches=(), bands=None, decisions=None):
        output = self.write(output)
        return make_args(output, words="words.txt", suitability_input="suitability_input.json",
                         categories="category_index.json", hints="hints.jvm.jsonl",
                         previous="levels.json", bands=bands or BANDS_WIDE, model=model,
                         cache_suitability=caches[0] if len(caches) > 0 else None,
                         cache_categories=caches[1] if len(caches) > 1 else None,
                         decisions=decisions)


class RequestPlanTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def run_plan(self, fixture, model=None, caches=()):
        args = fixture.args(self.root, model=model, caches=caches)
        plan.run(args)
        return json.loads((self.root / "reports" / "request_plan.json").read_text(encoding="utf-8"))

    def test_t6_and_t4_get_only_their_own_requests_and_background_is_ignored(self):
        fixture = TinyFixture(category_overrides={"краса": ("other", "unknown:ai_other")})
        plan_doc = self.run_plan(fixture)
        requested_targets = {(row["word"], row["role"]) for row in plan_doc["suitability"]["requests"]}
        requested_words = {row["word"] for row in plan_doc["category"]["requests"]}
        self.assertIn(("рана", "target"), requested_targets)
        self.assertIn(("старик", "target"), requested_targets)
        self.assertIn(("стирка", "target"), requested_targets)
        self.assertIn((fixture.SOURCE, "source"), requested_targets)
        for background in ("печать", "праймериз"):
            self.assertTrue(all(row["word"] != background
                                for row in plan_doc["suitability"]["requests"]))
            self.assertNotIn(background, requested_words)
        # T6: только неизвестные ответы исходника; ai_other не переспрашивается.
        self.assertEqual({"рана", "старик", "стирка", "ишак", "кошка", "каркас"}, requested_words)
        self.assertEqual(1, plan_doc["category"]["checked_no_category"])
        self.assertEqual(0, plan_doc["category"]["resolved_by_ai"])
        for row in plan_doc["category"]["requests"]:
            self.assertIn(fixture.SOURCE, row["contexts"])
        # Краса — цель T3/T4 с запретом повторной темы, пригодность запрошена.
        self.assertIn(("краса", "target"), requested_targets)

    def test_reused_target_is_requested_once_with_all_contexts(self):
        fixture = PlanFixtures()
        plan_doc = self.run_plan(fixture)
        rows = {row["word"]: row for row in plan_doc["suitability"]["requests"]}
        self.assertIn("рана", rows)
        self.assertEqual("target", rows["рана"]["role"])
        self.assertTrue({"карокшинсати", "шарманка"} <= set(rows["рана"]["contexts"]),
                        rows["рана"]["contexts"])
        reasons = {reason["code"] for reason in rows["рана"]["reasons"]}
        self.assertIn("assignment", reasons)
        self.assertIn("rescue:range", reasons)
        pairs = [(row["word"], row["role"]) for row in plan_doc["suitability"]["requests"]]
        self.assertEqual(len(pairs), len(set(pairs)))

    def test_assignment_rescue_and_hints_scoped_to_stage_fitting(self):
        fixture = PlanFixtures()
        plan_doc = self.run_plan(fixture)
        words = {row["word"] for row in plan_doc["suitability"]["requests"] if row["role"] == "target"}
        # Ющъ — загадка исходника этапов 1–2 без дефицита диапазона: не запрашивается.
        self.assertNotIn("ющъ", words)
        # Эфы структурно пригодно правилами: не запрашивается.
        self.assertNotIn("эфы", words)
        # Спасаемый исходник запрашивает недостающие ответы с причиной диапазона.
        self.assertIn("шрам", words)
        rows = {row["word"]: row for row in plan_doc["suitability"]["requests"]}
        self.assertEqual(["шарманка"], rows["шрам"]["contexts"])
        self.assertEqual([{"code": "rescue:range", "detail": ["1", "2"]}], rows["шрам"]["reasons"])
        # Фоновые существительные не оцениваются и не классифицируются.
        for background in ("печать", "пядь", "ячмень"):
            self.assertNotIn(background, words)
            self.assertFalse(any(row["word"] == background for row in plan_doc["category"]["requests"]))
        order = [min(plan.PRIORITY[reason["code"].split(":")[0]] for reason in row["reasons"])
                 for row in plan_doc["suitability"]["requests"]]
        self.assertEqual(order, sorted(order))

    def test_bans_and_preferred_sources_are_rules_not_requests(self):
        fixture = PlanFixtures()
        plan_doc = self.run_plan(fixture)
        source_pairs = {row["word"] for row in plan_doc["suitability"]["requests"]
                        if row["role"] == "source"}
        self.assertNotIn("праймериз", source_pairs)
        self.assertEqual(sorted(assess.PREFERRED_SOURCES),
                         sorted(plan_doc["pool"]["by_mandate"]["preferred"]))
        for word in assess.PREFERRED_SOURCES:
            self.assertNotIn(word, source_pairs)
        self.assertIn("карандаш", source_pairs)
        row = {r["word"]: r for r in plan_doc["suitability"]["requests"]}["карандаш"]
        self.assertIn("legacy", [reason["code"] for reason in row["reasons"]])

    def test_constraints_coverage_thresholds_unique_and_jaccard_survive(self):
        fixture = PlanFixtures()
        plan_doc = self.run_plan(fixture)
        checked = {row["constraint"] for row in plan_doc["audits"]["checked_constraints"]}
        self.assertLessEqual({"alphabet", "bands", "unique_targets", "unique_endings",
                              "jaccard", "category_global_min", "t4_hints"}, checked)
        self.assertEqual(32, len(plan_doc["audits"]["alphabet"]["alphabet"]))
        band_rows = [{k: v for k, v in b.items() if k in ("first", "last", "min", "max")}
                     for b in plan_doc["audits"]["bands"]]
        self.assertEqual(REAL_BANDS, band_rows)
        totals = plan.requirement_totals(campaign_gen.load_config(campaign_gen.CONFIG))
        self.assertEqual(totals["targets"], plan_doc["audits"]["unique_targets"]["required"])
        self.assertEqual(totals["endings"], plan_doc["audits"]["unique_endings"]["required"])
        self.assertEqual(totals["themes"], plan_doc["audits"]["themes"]["required"])
        jaccard_row = next(row for row in plan_doc["audits"]["checked_constraints"]
                           if row["constraint"] == "jaccard")
        self.assertEqual("среднее ≤3%, максимум ≤15%", jaccard_row["value"])
        self.assertIn("mean", plan_doc["audits"]["jaccard_reference"])
        self.assertIn("max", plan_doc["audits"]["jaccard_reference"])
        expansion_codes = [row["reason_code"] for row in plan_doc["expansions"]]
        self.assertIn("unique_target", expansion_codes)
        self.assertIn("alphabet", expansion_codes)

    def test_identical_inputs_produce_byte_identical_plan(self):
        first = self.root / "run-a"
        second = self.root / "run-b"
        args_a = PlanFixtures().args(first)
        args_b = PlanFixtures().args(second)
        plan.run(args_a)
        plan.run(args_b)
        bytes_a = (first / "reports" / "request_plan.json").read_bytes()
        bytes_b = (second / "reports" / "request_plan.json").read_bytes()
        self.assertEqual(bytes_a, bytes_b)
        self.assertNotIn(str(first).encode(), bytes_a)
        self.assertNotIn(str(second).encode(), bytes_a)

    def test_no_feasible_carrier_gives_exact_reason(self):
        fixture = TinyFixture(words=["рана", "старик", "кошка", "карокшинсати"],
                              suitable_words={"кошка"}, hinted={"рана", "старик"})
        plan_doc = self.run_plan(fixture)
        self.assertTrue(plan_doc["infeasible"]["proven"])
        joined = "\n".join(plan_doc["infeasible"]["reasons"])
        self.assertIn("Буквы без носителей", joined)
        self.assertIn("ъ", joined)
        self.assertIn("Диапазон уровней", joined)

    def test_rules_and_cache_counts_and_cache_hits_remove_requests(self):
        fixture = PlanFixtures()
        fixture.write(self.root)
        args = fixture.args(self.root)
        plan.run(args)
        cache_dir = self.root / "suitability-cache"
        model = "cached-model"
        provider = assess.SuitabilityProvider(cache_dir, model, provider="codex",
                                              allow_network=False)
        item = next(item for item in assess.input_items(fixture.document)
                    if item["word"] == "шрам" and item["role"] == "target")
        provider.store(item, assess.decision(item, "suitable", "Синтетика"))
        plan.run(fixture.args(self.root, model=model, caches=(cache_dir, None)))
        plan_doc = json.loads((self.root / "reports" / "request_plan.json").read_text(encoding="utf-8"))
        self.assertEqual(1, plan_doc["suitability"]["cache_resolved"])
        self.assertIn("шрам:target", plan_doc["suitability"]["cache_hits"])
        self.assertFalse(any(row["word"] == "шрам" for row in plan_doc["suitability"]["requests"]))
        self.assertGreaterEqual(plan_doc["suitability"]["resolved_by_rules"], 3)

    def test_category_cache_count_and_structural_classified(self):
        fixture = TinyFixture()
        fixture.write(self.root)
        plan.run(fixture.args(self.root))
        cache_dir = self.root / "category-cache"
        model = "cached-model"
        item = classify.classification_items(fixture.index, TAXONOMY, ["рана"])[0]
        provider = CategoryProvider(cache_dir, model, TAXONOMY, provider="codex",
                                    allow_network=False)
        provider.store(item, {"id": item["id"], "category": "tools", "confidence": 0.95})
        plan.run(fixture.args(self.root, model=model, caches=(None, cache_dir)))
        plan_doc = json.loads((self.root / "reports" / "request_plan.json").read_text(encoding="utf-8"))
        self.assertEqual(1, plan_doc["category"]["cache_resolved"])
        self.assertIn("рана", plan_doc["category"]["cache_hits"])
        self.assertNotIn("рана", plan_doc["category"]["requests"])

    def test_replan_recomputes_only_affected_profiles_and_matches_fresh_plan(self):
        fixture = PlanFixtures()
        fixture.write(self.root)
        first_args = fixture.args(self.root)
        plan.run(first_args)
        state = plan.build_state(first_args, TAXONOMY)
        planner = plan.Planner(state, selection.load_config(first_args.selection_config),
                               campaign_gen.load_config(first_args.campaign_config),
                               profiles.load_config(first_args.profiles_config),
                               plan.load_config(first_args.config), ALPHABET)
        planner.plan()  # базовые профили и обратный индекс ответов
        affected = planner.replan({"pairs": {("шрам", "target"): "suitable"}})
        # Шрам составим только из «шарманка»; карандаш не содержит буквы «м».
        self.assertEqual({"шрам", "шарманка"}, set(affected))
        revised = json.loads(json.dumps(planner.plan(), ensure_ascii=False, sort_keys=True))
        # Свежий планировщик с тем же решением в word_suitability.json даёт тот же план.
        answers = {item["id"]: assess.decision(item, "suitable", "Синтетика")
                   for item in assess.input_items(fixture.document)
                   if item["word"] == "шрам" and item["role"] == "target"}
        supplied = assess.apply_decisions(assess.input_items(fixture.document), answers,
                                          {"provider": "codex", "model": "unused"})
        supplied = dict(supplied, mode="selective", input_sha256=json_digest(fixture.document))
        decisions_path = self.root / "decisions.json"
        atomic_json(decisions_path, supplied)
        plan.run(fixture.args(self.root, decisions=decisions_path))
        fresh = json.loads((self.root / "reports" / "request_plan.json").read_text(encoding="utf-8"))
        fresh = json.loads(json.dumps(fresh, ensure_ascii=False, sort_keys=True))
        self.assertEqual(revised, fresh)

    def test_pairs_file_matches_lex04_boundary(self):
        fixture = PlanFixtures()
        fixture.write(self.root)
        plan.run(fixture.args(self.root))
        pairs_file = self.root / "reports" / "requested_pairs.txt"
        pairs = assess.read_pairs_file(pairs_file)
        plan_doc = json.loads((self.root / "reports" / "request_plan.json").read_text(encoding="utf-8"))
        self.assertEqual({(row["word"], row["role"]) for row in plan_doc["suitability"]["requests"]},
                         set(pairs))
        words_file = self.root / "reports" / "requested_words.txt"
        wanted = classify.read_words_file(words_file)
        self.assertEqual([row["word"] for row in plan_doc["category"]["requests"]], wanted)

    def test_default_planner_config_matches_selection_bands(self):
        document = json.loads(Path(plan.CONFIG).read_text(encoding="utf-8"))
        self.assertFalse(document["planning_bands_override"])
        self.assertEqual(document["planning_bands"], REAL_BANDS)

    def test_read_hints_accepts_real_jvm_audit_rows(self):
        path = self.root / "hints.jvm.jsonl"
        path.write_text("\n".join([
            json.dumps({"schema": "definition-hint-audit-v1"}),
            json.dumps({"word": "аал", "hint": "Поселение сельского типа",
                        "hint_before": "поселение сельского типа", "has_definition": True},
                       ensure_ascii=False),
            json.dumps({"word": "аба", "hint": None,
                        "hint_before": None, "has_definition": False},
                       ensure_ascii=False)]) + "\n", encoding="utf-8")
        hints = plan.read_hints(path, {"аал", "аба"})
        self.assertTrue(hints["аал"]["useful"])
        self.assertFalse(hints["аба"]["useful"])
        self.assertEqual(json_digest("Поселение сельского типа"), hints["аал"]["sha256"])


if __name__ == "__main__":
    unittest.main()
