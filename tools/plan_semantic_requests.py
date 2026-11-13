#!/usr/bin/env python3
"""SEL-04: детерминированный план недостающих ИИ-оценок. ИИ не запускается.

Планировщик строит по готовым структурным артефактам (словарь, экспорт
word-suitability-input-v1, индекс категорий LEX-02, JVM-подсказки, история
уровней v1 и конфиги) машиночитаемый план запросов LEX-03/LEX-04, которые
действительно могут понадобиться для возможной кампании. Полный перебор и
сетевые вызовы здесь запрещены; кэш читается только для счётчиков.

Запросы привязаны к конкретным недостающим ограничениям: обязательный
исходник, покрытие 32 букв, диапазон сложности этапа, назначаемая цель
T3/T4, тема T6. Фоновые слова словаря не классифицируются «на всякий
случай». Одно слово и роль — один запрос в одном совместимом контексте.
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import assess_word_suitability as assess
import build_campaign as campaign_gen
import classify_word_categories as classify
from build_dictionary import read_list, sha256_file
from game_data_providers import (CATEGORY_PROMPT, CategoryProvider, atomic_json, category_schema,
                                 json_digest, prompt_hash)
from game_data_quality import normalize_form
import pick_level_words as profiles
import select_level_words as selection
import word_categories as thematic

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
SCHEMA = "semantic-request-plan-v1"
CONFIG = TOOLS / "request_plan_config.json"
ALPHABET_FILE = ROOT / "core/src/main/kotlin/com/example/sostavslova/core/Alphabet.kt"

# Порядок пользы для конкретных недостающих ограничений; меньше — раньше.
PRIORITY = {"legacy": 0, "preferred": 0, "letter": 1, "stage": 2, "theme": 2,
            "assignment": 3, "rescue": 4}
NOTES = {
    "legacy": "Исходник исторической кампании v1: сохранение 8–15 слов требует решения о пригодности.",
    "preferred": "Предпочтительный исходник владельца: без решения пара не участвует в подборе.",
    "letter": "Кандидат несёт букву алфавита, не покрытую остальным пулом (требование 32 букв).",
    "stage": "Кандидат ближайшего к диапазону сложности этапа набора: нужен запас для подбора.",
    "assignment": "Потенциально назначаемая цель T3/T4 с полезной маскированной подсказкой.",
    "rescue": "Ответ, чья пригодность может довести узнаваемый запас источника до порога этапа.",
    "theme": "Ответ кандидата этапа Мастер, без проверенной темы: нужен полный локальный набор T6.",
}


def load_config(path=CONFIG):
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    if document.get("schema") != "semantic-request-plan-config-v1":
        raise ValueError("Неверная схема конфигурации планировщика")
    required = {"seed", "carriers_per_letter", "stage_candidates_per_band",
                "iterations", "new_decisions_per_iteration", "planning_bands",
                "planning_bands_override"}
    if not required <= set(document):
        raise ValueError("Неполный набор параметров планировщика: " + ", ".join(sorted(required - set(document))))
    for name in required - {"planning_bands", "planning_bands_override"}:
        if type(document[name]) is not int or document[name] <= 0:
            raise ValueError("Ожидалось положительное целое: " + name)
    if type(document["planning_bands_override"]) is not bool:
        raise ValueError("planning_bands_override должен быть логическим")
    bands = document["planning_bands"]
    if not isinstance(bands, list) or not bands:
        raise ValueError("Нужны planning_bands планировщика")
    expanded = []
    for band in bands:
        if any(type(band[key]) is not int for key in ("first", "last", "min", "max")):
            raise ValueError("Неверная полоса планирования")
        if not 1 <= band["first"] <= band["last"] <= 30 or not 0 < band["min"] <= band["max"]:
            raise ValueError("Неверные границы полосы планирования")
        expanded.extend((number, band["min"], band["max"])
                        for number in range(band["first"], band["last"] + 1))
    if [row[0] for row in expanded] != list(range(1, 31)):
        raise ValueError("Полосы планирования должны покрывать уровни 1–30 без пропусков")
    return document


def read_hints(path, words):
    """Лёгкое чтение hints.jvm.jsonl: полезность по слову; без текстов статей."""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines:
        raise ValueError("Файл подсказок пуст")
    header = json.loads(lines[0])
    if not isinstance(header, dict):
        raise ValueError("Файл подсказок без заголовка")
    hints, seen = {}, set()
    for number, line in enumerate(lines[1:], 2):
        if not line.strip():
            continue
        row = json.loads(line)
        if (not isinstance(row, dict) or not {"word", "hint"} <= set(row) or
                set(row) - {"word", "hint", "hint_before", "has_definition"}):
            raise ValueError(f"Невалидная строка подсказок {number}")
        word = normalize_form(row["word"])
        hint = row["hint"]
        if word not in words or word in seen:
            raise ValueError(f"Повторное или постороннее слово подсказок: {word}")
        if hint is not None and (not isinstance(hint, str) or not hint.strip() or len(hint) > 140):
            raise ValueError(f"Невалидная подсказка: {word}")
        seen.add(word)
        hints[word] = {"useful": hint is not None, "sha256": json_digest(hint)}
    return hints


def previous_slots(path):
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    slots = defaultdict(list)
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("word"), str):
            raise ValueError("Некорректная история уровней")
        slots[normalize_form(row["word"])].append(row.get("index"))
    return {word: sorted(index for index in indexes if isinstance(index, int))
            for word, indexes in slots.items()}


def requirement_totals(config):
    """Числа обязательных уникальных назначений из расписания GEN-03."""
    totals = {"targets": 0, "endings": 0, "themes": 0}
    for level in campaign_gen.schedule(config):
        for task in level["tasks"]:
            if task["type"] == "T3":
                totals["targets"] += 1
            elif task["type"] == "T4":
                totals["targets"] += task["targetCount"]
            elif task["type"] == "T5":
                totals["endings"] += 1
            elif task["type"] == "T6":
                totals["themes"] += 1
    return totals


class Planner:
    def __init__(self, state, selection_config, campaign_config, profile_config,
                 planner_config, alphabet):
        self.selection_config = selection_config
        self.campaign_config = campaign_config
        self.profile_config = profile_config
        self.planner_config = planner_config
        self.alphabet = alphabet
        self.min_occurrences = profile_config["min_observed_occurrences"]
        self.preferred_sources = sorted(assess.PREFERRED_SOURCES)
        self.planning_bands = planner_config["planning_bands"]
        self._state = state
        self._index = profiles.MultisetIndex(state["words"])
        self._profiles = {}
        self._fits = {}
        self._reverse = defaultdict(set)

    def target_resolved(self, word):
        return self._state["pair_resolved"].get((word, "target"))

    def source_resolved(self, word):
        return self._state["pair_resolved"].get((word, "source"))

    def category_state(self, word):
        return self._state["category_state"][word]

    def eligible_answer(self, word):
        features = self._state["features"].get(word)
        if features is None or word in thematic.ROLE_EXCLUSIONS:
            return False
        return (features["noun"] and features["role_decision"] != "exclude"
                and not set(features["labels"]) & assess.HARD_LABELS
                and features["reachable"] is True
                and features["frequency"] >= self.min_occurrences
                and 3 <= len(word) <= 30)

    def hints_useful(self, word):
        return self._state["hints"].get(word, {}).get("useful", False)

    def profile(self, source, force=False):
        if not force and source in self._profiles:
            return self._profiles[source]
        answers = self._index.search(source)
        for answer in answers:
            self._reverse[answer].add(source)
        familiar = sorted(answer for answer in answers
                          if self.target_resolved(answer) == "suitable"
                          and self._state["frequencies"][answer] >= self.min_occurrences)
        missing_eligible = sorted(answer for answer in answers
                                  if self.target_resolved(answer) is None and self.eligible_answer(answer))
        familiar_upper = sorted(set(familiar) | set(missing_eligible))
        assignment_potential = sorted(answer for answer in familiar_upper if self.hints_useful(answer))
        result = {"answers": answers, "struct_familiar": familiar,
                  "missing_eligible": missing_eligible, "familiar_upper": familiar_upper,
                  "assignment_potential": assignment_potential,
                  "struct_range": len(familiar), "range_upper": len(familiar_upper)}
        self._profiles[source] = result
        return result

    def candidate_sources(self):
        """Все исходники, которые структура не исключила окончательно."""
        result = []
        for word in self._state["words"]:
            item = self._state["items_by_pair"].get((word, "source"))
            if item is None:
                continue
            if self.source_resolved(word) in ("unsuitable", "insufficient_data"):
                continue
            if not 8 <= len(word) <= 13 or not item["features"]["noun"]:
                continue
            result.append(word)
        return sorted(result)

    def fit_bands(self, source, force=False):
        if not force and source in self._fits:
            return self._fits[source]
        profile = self.profile(source)
        fits = {}
        for index, band in enumerate(self.planning_bands):
            count = profile["struct_range"]
            if band["min"] <= count <= band["max"]:
                fits[index] = "in_range"
            elif count < band["min"] and profile["range_upper"] >= band["min"]:
                fits[index] = "rescuable"
        self._fits[source] = fits
        return fits

    def pool(self):
        candidates = self.candidate_sources()
        fits = {word: self.fit_bands(word) for word in candidates}
        pool = {}
        legacy = self._state["previous_slots"]
        preferred = set(self.preferred_sources) & set(candidates)
        for word, slots in sorted(legacy.items()):
            if word in candidates and word not in pool:
                pool[word] = {"legacy_slots": slots, "preferred": False}
        for word in sorted(preferred):
            pool.setdefault(word, {"legacy_slots": []})
            pool[word]["preferred"] = True
        covered = set("".join(pool))
        for letter in self.alphabet:
            if letter in covered:
                continue
            carriers = sorted((word for word in candidates if word not in pool and letter in word),
                              key=lambda word: (0 if fits.get(word) else 1,
                                                -self._state["frequencies"][word], word))
            for word in carriers[:self.planner_config["carriers_per_letter"]]:
                pool[word] = {"legacy_slots": [], "preferred": False}
            covered = set("".join(pool))
        for band_number, band in enumerate(self.planning_bands):
            ranked = sorted((word for word in candidates if band_number in fits.get(word, ())),
                            key=lambda word: (0 if fits[word][band_number] == "in_range" else 1,
                                              -self.profile(word)["range_upper"],
                                              -self._state["frequencies"][word], word))
            for word in ranked[:self.planner_config["stage_candidates_per_band"]]:
                pool.setdefault(word, {"legacy_slots": []})
                pool[word].setdefault("preferred", False)
        pool_union = set("".join(pool))
        for word in pool:
            added = set(word) - (pool_union - set(word))
            pool[word]["letters_added"] = "".join(sorted(added))
            stage = fits.get(word, {})
            pool[word]["stage_bands"] = sorted(stage)
            pool[word]["stage_kinds"] = dict(stage)
        return {word: dict(pool[word]) for word in sorted(pool)}, dict(fits)

    def add_pair(self, request, word, role, source, code, detail):
        key = (word, role)
        row = request.setdefault(key, {"codes": defaultdict(set), "contexts": set()})
        row["codes"][code].add(detail)
        row["contexts"].add(source)

    def merge_pairs(self, rows):
        output = []
        for key in sorted(rows):
            word, role = key
            row = rows[key]
            output.append({"word": word, "role": role,
                           "reasons": [{"code": code, "detail": sorted(details)}
                                       for code, details in sorted(row["codes"].items())],
                           "contexts": sorted(row["contexts"]),
                           "frequency": self._state["frequencies"][word]})
        output.sort(key=lambda row: (
            min(PRIORITY.get(reason["code"].split(":")[0], 9) for reason in row["reasons"]),
            -row["frequency"], row["word"], row["role"]))
        return output

    def pair_requests(self, pool):
        request = {}
        for word in pool:
            row = pool[word]
            if self.source_resolved(word) is not None:
                continue
            if row["legacy_slots"]:
                self.add_pair(request, word, "source", word,
                              "legacy", ",".join(str(n) for n in row["legacy_slots"]))
            if row["preferred"]:
                self.add_pair(request, word, "source", word, "preferred", "owner")
            if row["letters_added"]:
                self.add_pair(request, word, "source", word, "letter", row["letters_added"])
            if row["stage_bands"]:
                self.add_pair(request, word, "source", word,
                              "stage", ",".join(str(n + 1) for n in row["stage_bands"]))
        for word in pool:
            band_numbers = pool[word]["stage_bands"]
            assignment_bands = sorted(set(band_numbers) & {2, 3, 4, 5})
            if assignment_bands:
                profile = self.profile(word)
                for answer in profile["assignment_potential"]:
                    if self.target_resolved(answer) is not None:
                        continue
                    if len(answer) >= 5:
                        self.add_pair(request, answer, "target", word, "assignment", "t3")
                    self.add_pair(request, answer, "target", word, "assignment", "t4")
            for band_number, kind in pool[word]["stage_kinds"].items():
                if kind == "rescuable":
                    profile = self.profile(word)
                    for answer in profile["missing_eligible"]:
                        if self.target_resolved(answer) is None:
                            self.add_pair(request, answer, "target", word,
                                          "rescue:range", str(band_number + 1))
        return self.merge_pairs(request)

    def category_requests(self, pool):
        request = {}
        band_six = [word for word in pool if 5 in pool[word]["stage_bands"]]
        for source in sorted(band_six):
            for answer in self.profile(source)["answers"]:
                if self.category_state(answer) != "unknown":
                    continue
                request.setdefault(answer, {"codes": defaultdict(set), "contexts": set()})
                request[answer]["codes"]["theme"].add("6")
                request[answer]["contexts"].add(source)
        output = []
        for word in sorted(request):
            output.append({"word": word,
                           "reasons": [{"code": "theme", "detail": sorted(request[word]["codes"]["theme"])}],
                           "contexts": sorted(request[word]["contexts"]),
                           "frequency": self._state["frequencies"][word]})
        output.sort(key=lambda row: (-row["frequency"], row["word"]))
        return output

    def category_scope(self, pool):
        return sorted({answer for word in pool if 5 in pool[word]["stage_bands"]
                       for answer in self.profile(word)["answers"]})

    def considered_targets(self, pool):
        considered = set()
        for word in pool:
            if {2, 3, 4, 5} & set(pool[word]["stage_bands"]):
                considered.update(self.profile(word)["assignment_potential"])
            if "rescuable" in pool[word]["stage_kinds"].values():
                considered.update(self.profile(word)["missing_eligible"])
        return sorted(considered)

    def count_resolutions(self, pool, considered_targets, category_scope, requested_ids):
        pairs = {"rules": 0, "ai": 0, "pending": 0, "considered": 0}
        covered_targets = set(considered_targets)
        for source in pool:
            if self.source_resolved(source) is not None:
                pairs["rules" if self._state["pair_sources"].get((source, "source")) == "rules"
                          else "ai"] += 1
            else:
                pairs["pending"] += 1
            pairs["considered"] += 1
        for word in covered_targets:
            resolved = self.target_resolved(word)
            if resolved is not None:
                pairs["rules" if self._state["pair_sources"].get((word, "target")) == "rules"
                          else "ai"] += 1
            elif (word, "target") in requested_ids:
                pairs["pending"] += 1
            pairs["considered"] += 1
        categories = {"rules": 0, "ai": 0, "checked_no_category": 0,
                      "checked_low_confidence": 0, "unknown": 0}
        for word in category_scope:
            state_value = self.category_state(word)
            if state_value == "classified":
                source_value = self._state["metadata"][word]["category_source"]
                categories["ai" if source_value.startswith("ai:") else "rules"] += 1
            elif state_value == "checked_no_category":
                categories["checked_no_category"] += 1
            elif state_value == "checked_low_confidence":
                categories["checked_low_confidence"] += 1
            else:
                categories["unknown"] += 1
        return pairs, categories

    def band_by_level(self):
        result = {}
        for index, band in enumerate(self.planning_bands):
            for level_number in range(band["first"], band["last"] + 1):
                result[level_number] = index
        return result

    def audits(self, pool, candidates):
        covered = "".join(ch for ch in sorted(self.alphabet) if ch in set("".join(pool)))
        global_carriers = "".join(ch for ch in self.alphabet
                                  if any(ch in word for word in candidates))
        alphabet = {"alphabet": self.alphabet,
                    "covered_by_pool": covered,
                    "missing_in_pool": "".join(ch for ch in self.alphabet if ch not in covered),
                    "globally_uncovered": "".join(ch for ch in self.alphabet if ch not in global_carriers)}
        bands = []
        for band in self.selection_config["bands"]:
            potential = [word for word in candidates
                         if self.profile(word)["range_upper"] >= band["min"]
                         and self.profile(word)["struct_range"] <= band["max"]]
            bands.append({"first": band["first"], "last": band["last"],
                          "min": band["min"], "max": band["max"],
                          "structurally_suitable_candidates": len(potential)})
        requirements = requirement_totals(self.campaign_config)
        schedule = campaign_gen.schedule(self.campaign_config)
        band_by_level = self.band_by_level()
        target_options, ending_options = {}, {}
        for level in schedule:
            for task in level["tasks"]:
                if task["type"] not in ("T3", "T4", "T5"):
                    continue
                sources = [word for word in pool
                           if band_by_level[level["number"]] in pool[word]["stage_bands"]]
                if task["type"] == "T3":
                    target_options[task["id"]] = {
                        answer for word in sources for answer in self.profile(word)["assignment_potential"]
                        if len(answer) >= task["minLength"]}
                elif task["type"] == "T4":
                    for offset in range(task["targetCount"]):
                        target_options[task["id"] + "/" + str(offset)] = {
                            answer for word in sources
                            for answer in self.profile(word)["assignment_potential"]}
                else:
                    options = set()
                    for word in sources:
                        for answer in self.profile(word)["familiar_upper"]:
                            if len(answer) >= task["minLength"]:
                                options.update(ending for ending in self.campaign_config["endings"]
                                               if answer.endswith(ending))
                    ending_options[task["id"]] = options
        target_matching = selection.matching(target_options)
        ending_matching = selection.matching(ending_options)
        theme_sources = [word for word in pool if 5 in pool[word]["stage_bands"]]
        theme_answers = sorted({answer for word in theme_sources for answer in self.profile(word)["answers"]})
        familiar = sorted({answer for word in theme_sources
                          for answer in self.profile(word)["struct_familiar"]})
        theme_knowledge = defaultdict(lambda: {"familiar": 0, "local_answers": 0})
        for word in familiar:
            category = self._state["metadata"][word]["category"]
            if category != "other":
                theme_knowledge[category]["familiar"] += 1
        for word in theme_answers:
            if self.category_state(word) != "classified":
                continue
            category = self._state["metadata"][word]["category"]
            if category != "other":
                theme_knowledge[category]["local_answers"] += 1
        category_global = [{"category": category, **theme_knowledge[category],
                            "shortfall": max(0, self.campaign_config["category_global_min"]
                                             - theme_knowledge[category]["familiar"])}
                           for category in sorted(theme_knowledge)]
        familiar_sets = [self.profile(word)["struct_familiar"] for word in sorted(pool)]
        jaccard_values = [selection.jaccard(left, right)
                          for index, left in enumerate(familiar_sets)
                          for right in familiar_sets[index + 1:]]
        jaccard_reference = {"mean": sum(jaccard_values) / len(jaccard_values) if jaccard_values else 0.0,
                             "max": max(jaccard_values, default=0.0), "pairs": len(jaccard_values)}
        return {"alphabet": alphabet, "bands": bands,
                "unique_targets": {"required": requirements["targets"],
                                   "matched": target_matching["matched"],
                                   "required_check": target_matching["required"],
                                   "unmatched": sorted(target_matching["unmatched"]),
                                   "potential_words": len(set().union(*target_options.values()))},
                "unique_endings": {"required": requirements["endings"],
                                   "matched": ending_matching["matched"],
                                   "required_check": ending_matching["required"],
                                   "unmatched": sorted(ending_matching["unmatched"])},
                "themes": {"required": requirements["themes"], "category_knowledge": category_global},
                "jaccard_reference": jaccard_reference,
                "legacy": {"requested": sorted(word for word in pool if pool[word]["legacy_slots"]),
                           "impossible": sorted(word for word, slots in self._state["previous_slots"].items()
                                                if word not in pool and word in self._state["words"]
                                                and self.source_resolved(word)
                                                in ("unsuitable", "insufficient_data"))},
                "preferred": {"possible": sorted(set(self.preferred_sources) & set(pool))},
                "checked_constraints": [
                    {"constraint": "alphabet", "enforced_by": "GEN-02/GEN-04",
                     "value": "32 знака Alphabet.LETTERS после ё→е"},
                    {"constraint": "bands", "enforced_by": "level_selection_config.json, GEN-02/GEN-04",
                     "value": [{"first": b["first"], "last": b["last"], "min": b["min"], "max": b["max"]}
                               for b in self.selection_config["bands"]]},
                    {"constraint": "unique_targets", "enforced_by": "GEN-03/GEN-04",
                     "value": "T3/T4 в общем пространстве нормализованных целей"},
                    {"constraint": "unique_endings", "enforced_by": "GEN-03/GEN-04",
                     "value": "окончания T5 из campaign_config.endings"},
                    {"constraint": "jaccard", "enforced_by": "GEN-02/GEN-04",
                     "value": "среднее ≤3%, максимум ≤15%"},
                    {"constraint": "category_global_min", "enforced_by": "GEN-03/GEN-04",
                     "value": self.campaign_config["category_global_min"]},
                    {"constraint": "t4_hints", "enforced_by": "GEN-03/GEN-04",
                     "value": "назначенная цель только с полезной маскированной подсказкой JVM"},
                ]}

    def infeasibility(self, candidates):
        reasons = []
        if not candidates:
            reasons.append("Нет ни одного исходника-кандидата 8–13 с допустимой ролью source.")
        global_carriers = set().union(*(set(word) for word in candidates)) if candidates else set()
        missing_global = "".join(ch for ch in self.alphabet if ch not in global_carriers)
        if missing_global:
            reasons.append("Буквы без носителей-исходников во всём пуле кандидатов: " + missing_global + ".")
        for band in self.selection_config["bands"]:
            potential = [word for word in candidates
                         if self.profile(word)["range_upper"] >= band["min"]
                         and self.profile(word)["struct_range"] <= band["max"]]
            if not potential:
                reasons.append("Диапазон уровней {first}–{last} ({min}–{max} узнаваемых ответов) "
                               "не имеет ни одного структурно подходящего кандидата.".format(**band))
        legacy_possible = {word for word, slots in self._state["previous_slots"].items()
                           if word in self._state["words"]
                           and self.source_resolved(word) not in ("unsuitable", "insufficient_data")}
        legacy_possible |= {word for word in self._state["previous_slots"]
                            if word in self._state["words"] and self.source_resolved(word) is None}
        if len(legacy_possible) < self.selection_config["retain_min"]:
            reasons.append("Старых исходников, структурно допустимых для сохранения: {n}; минимум {m}."
                           .format(n=len(legacy_possible), m=self.selection_config["retain_min"]))
        return {"proven": bool(reasons), "reasons": reasons}

    def expansions(self, audits):
        rows = []
        for category in audits["themes"]["category_knowledge"]:
            if category["shortfall"] > 0:
                rows.append({"reason_code": "category_global_reserve", "detail": category["category"],
                             "known_familiar": category["familiar"],
                             "required": self.campaign_config["category_global_min"],
                             "shortfall": category["shortfall"], "requested": 0,
                             "note": "Запрашивать категории узнаваемых слов темы до порога резерва."})
        if audits["unique_targets"]["unmatched"]:
            rows.append({"reason_code": "unique_target", "detail": "Нет различимых целей T3/T4 на весь план",
                         "matched": audits["unique_targets"]["matched"],
                         "required": audits["unique_targets"]["required"], "requested": 0,
                         "note": "Другие исходники/цели в конкретном слоте; сначала подбор, потом оценки."})
        if audits["unique_endings"]["unmatched"]:
            rows.append({"reason_code": "unique_ending", "detail": "Мало различимых окончаний T5",
                         "matched": audits["unique_endings"]["matched"],
                         "required": audits["unique_endings"]["required"], "requested": 0,
                         "note": "Другие исходники или окончания из закрытого инвентаря campaign_config."})
        if audits["alphabet"]["globally_uncovered"]:
            rows.append({"reason_code": "alphabet", "detail": audits["alphabet"]["globally_uncovered"],
                         "requested": 0, "note": "Буква без носителей; новый исходник невозможен из словаря."})
        return rows

    def plan(self):
        pool, _ = self.pool()
        candidates = self.candidate_sources()
        pair_rows = self.pair_requests(pool)
        category_rows = self.category_requests(pool)
        requested_ids = {(row["word"], row["role"]) for row in pair_rows}
        considered_targets = [target for target in self.considered_targets(pool)
                              if (target, "target") in requested_ids or self.target_resolved(target) is not None]
        category_scope = self.category_scope(pool)
        resolution_pairs, resolution_categories = self.count_resolutions(
            pool, considered_targets, category_scope, requested_ids)
        return self.assemble(pool, candidates, pair_rows, category_rows,
                             resolution_pairs, resolution_categories, len(category_scope))

    def assemble(self, pool, candidates, pair_rows, category_rows,
                 resolution_pairs, resolution_categories, category_scope_size):
        # Совестимый кэш читается только для счётчиков; ИИ и сеть запрещены.
        suit_items = {(item["word"], item["role"]): item for item in self._state["all_items"]}
        cache_hit_pairs, remaining_pairs = [], []
        if self._state["cache_suitability_enabled"]:
            provider = assess.SuitabilityProvider(self._state["cache_suitability"],
                                                  self._state["model"],
                                                  provider=self._state["provider"],
                                                  allow_network=False)
            for row in pair_rows:
                item = suit_items[(row["word"], row["role"])]
                if provider.read(item) is not None:
                    cache_hit_pairs.append((row["word"], row["role"]))
                else:
                    remaining_pairs.append(row)
        else:
            remaining_pairs = list(pair_rows)
        taxonomy = thematic.load_taxonomy()
        candidate_words = [row["word"] for row in category_rows]
        items = classify.classification_items(self._state["category_index"], taxonomy, candidate_words)
        eligible_words = {item["word"] for item in items}
        remaining_rows = [row for row in category_rows if row["word"] in eligible_words]
        excluded_words = sorted({row["word"] for row in category_rows} - eligible_words)
        cache_hit_words = []
        if self._state["cache_category_enabled"] and remaining_rows:
            provider = CategoryProvider(self._state["cache_categories"], self._state["model"],
                                        taxonomy, provider=self._state["provider"],
                                        allow_network=False)
            items_by_word = {item["word"]: item for item in items}
            kept = []
            for row in remaining_rows:
                if provider.read(items_by_word[row["word"]]) is not None:
                    cache_hit_words.append(row["word"])
                else:
                    kept.append(row)
            remaining_rows = kept
        audits = self.audits(pool, candidates)
        infeasible = self.infeasibility(candidates)
        return {
            "schema": SCHEMA, "status": "proven_infeasible" if infeasible["proven"] else "ready",
            "inputs": {
                "words_sha256": self._state["digests"]["words"],
                "suitability_input_sha256": self._state["digests"]["suitability_input"],
                "category_index_sha256": self._state["digests"]["categories"],
                "hints_sha256": self._state["digests"].get("hints"),
                "previous_levels_sha256": self._state["digests"]["previous_levels"],
                "profile_config_sha256": self._state["digests"]["profile_config"],
                "selection_config_sha256": self._state["digests"]["selection_config"],
                "campaign_config_sha256": self._state["digests"]["campaign_config"],
                "planner_config_sha256": self._state["digests"]["planner_config"],
                "alphabet_sha256": self._state["digests"]["alphabet"],
                "planning_bands": self.planning_bands,
                "planning_bands_override": self.planner_config["planning_bands_override"],
                "planner_code_sha256": sha256_file(Path(__file__)),
                "planner_inputs_sha256": json_digest(self._state["digests"]),
            }, "rules": {
                "suitability_policy": assess.POLICY,
                "suitability_policy_sha256": json_digest(assess.POLICY),
                "suitability_prompt_sha256": prompt_hash(assess.PROMPT),
                "suitability_schema_sha256": json_digest(assess.result_schema(assess.POLICY)),
                "taxonomy_sha256": json_digest(taxonomy),
                "category_prompt_sha256": prompt_hash(CATEGORY_PROMPT),
                "category_schema_sha256": json_digest(category_schema(taxonomy)),
                "snapshot_schemas": ["word-category-decisions-v1", "word-suitability-decisions-v1"],
                "confidence_threshold_note": "LEX-03 0.8; LEX-04 без порога уверенности",
            }, "seed": self.planner_config["seed"],
            "budget": {"iterations": self.planner_config["iterations"],
                       "new_decisions_per_iteration": self.planner_config["new_decisions_per_iteration"]},
            "pool": self.pool_summary(pool, candidates),
            "suitability": {
                "requests": remaining_pairs,
                "requested": len(remaining_pairs),
                "resolved_by_rules": resolution_pairs["rules"],
                "resolved_by_ai": resolution_pairs["ai"],
                "cache_resolved": len(cache_hit_pairs),
                "cache_hits": ["{}:{}".format(*pair) for pair in sorted(cache_hit_pairs)],
                "considered_pairs": resolution_pairs["considered"],
                "pending_before_cache": resolution_pairs["pending"]},
            "category": {
                "requests": remaining_rows,
                "requested": len(remaining_rows),
                "resolved_by_rules": resolution_categories["rules"],
                "resolved_by_ai": resolution_categories["ai"],
                "checked_no_category": resolution_categories["checked_no_category"],
                "checked_low_confidence": resolution_categories["checked_low_confidence"],
                "cache_resolved": len(cache_hit_words),
                "cache_hits": sorted(cache_hit_words),
                "considered_words": category_scope_size,
                "excluded_by_lexical_rules": excluded_words},
            "groups": self.groups(remaining_pairs, [row["word"] for row in remaining_rows]),
            "audits": audits,
            "infeasible": infeasible,
            "expansions": self.expansions(audits),
            "artifacts": {"pairs_file": "requested_pairs.txt", "words_file": "requested_words.txt"},
            "notes": ["ИИ не запускался: план построен на структурных данных, правилах и чтении кэша.",
                      "Фоновые слова словаря вне пула кандидатов не классифицируются и не оцениваются.",
                      "Точность модели не измерена: независимой разметки нет."],
        }

    def pool_summary(self, pool, candidates):
        by_mandate = {"legacy": [], "preferred": [], "letter": [], "stage": []}
        for word in sorted(pool):
            if pool[word]["legacy_slots"]:
                by_mandate["legacy"].append(word)
            if pool[word]["preferred"]:
                by_mandate["preferred"].append(word)
            if pool[word]["letters_added"]:
                by_mandate["letter"].append(word)
            if pool[word]["stage_bands"]:
                by_mandate["stage"].append(word)
        return {"candidate_sources_total": len(candidates), "included": len(pool),
                "by_mandate": by_mandate,
                "stage_bands": {word: {"bands": pool[word]["stage_bands"],
                                       "kinds": pool[word]["stage_kinds"]}
                                for word in sorted(pool) if pool[word]["stage_bands"]},
                "ordering_note": "Порядок запросов: польза для ограничения, затем -частота, затем слово."}

    def groups(self, remaining_pairs, remaining_words):
        family_counts = Counter()
        detail_samples = defaultdict(set)
        for row in remaining_pairs:
            for reason in row["reasons"]:
                family = reason["code"].split(":")[0]
                family_counts[family] += 1
                detail_samples[family].update(reason["detail"])
        family_counts["theme"] += len(remaining_words)
        return [{"family": family, "requested": family_counts[family],
                 "note": NOTES.get(family, ""), "detail_sample": sorted(detail_samples.get(family, ()))}
                for family in sorted(family_counts)]

    def replan(self, decisions):
        """Расширение: пересчитываются только затронутые профили источников."""
        changed = set()
        for (word, role), status in decisions.get("pairs", {}).items():
            self._state["pair_resolved"][(word, role)] = status
            self._state["pair_sources"][(word, role)] = "ai"
            changed.add(word)
        for word, state_value in decisions.get("categories", {}).items():
            self._state["category_state"][word] = state_value
            self._state["metadata"][word]["category"] = state_value.get("category", "other")
            self._state["metadata"][word]["category_source"] = state_value.get("source", "unknown:")
            changed.add(word)
        affected = set(changed)
        for word in changed:
            affected.update(self._reverse.get(word, ()))
            for source, profile in list(self._profiles.items()):
                if word in profile["answers"]:
                    affected.add(source)
        for source in sorted(affected):
            self._fits.pop(source, None)
            self.profile(source, force=True)
        return affected


def build_state(args, taxonomy):
    words = sorted({normalize_form(word) for word in read_list(args.words)})
    if not words:
        raise ValueError("Словарь пуст")
    document = json.loads(args.suitability_input.read_text(encoding="utf-8"))
    all_items = assess.input_items(document)
    items_by_pair = {(item["word"], item["role"]): item for item in all_items}
    target_words = {item["word"] for item in all_items if item["role"] == "target"}
    if target_words != set(words):
        raise ValueError("Экспорт пригодности не покрывает игровой словарь 1:1")
    supplied = json.loads(args.suitability_decisions.read_text(encoding="utf-8")) \
        if args.suitability_decisions else None
    rows = profiles.assessments_for(document, supplied)
    pair_resolved, pair_sources = {}, {}
    for (word, role), row in rows.items():
        if row["assessment_source"] in ("rules", "ai"):
            pair_resolved[(word, role)] = row["assessment"]["status"]
            pair_sources[(word, role)] = row["assessment_source"]
    features = {item["word"]: item["features"] for item in all_items if item["role"] == "target"}
    frequencies = {item["word"]: item["features"]["frequency"] for item in all_items
                   if item["role"] == "target"}
    index = json.loads(args.categories.read_text(encoding="utf-8"))
    if index.get("schema") != "word-category-index-v1" or not isinstance(index.get("word_metadata"), dict):
        raise ValueError("Нужен category_index.json схемы word-category-index-v1")
    if set(index["word_metadata"]) != set(words):
        raise ValueError("Индекс категорий не покрывает игровой словарь 1:1")
    metadata, category_state = {}, {}
    for word, value in index["word_metadata"].items():
        if value.get("category") not in taxonomy or \
                (value["category"] == "other") != value.get("category_source", "").startswith("unknown:"):
            raise ValueError("Невалидные метаданные категории: " + word)
        metadata[word] = value
        source_value = value["category_source"]
        if value["category"] != "other" and not source_value.startswith("unknown:"):
            category_state[word] = "classified"
        elif source_value == "unknown:ai_other":
            category_state[word] = "checked_no_category"
        elif source_value == "unknown:ai_low_confidence":
            category_state[word] = "checked_low_confidence"
        else:
            category_state[word] = "unknown"
    hints = read_hints(args.hints, set(words)) if args.hints else {}
    digests = {"words": sha256_file(args.words), "suitability_input": sha256_file(args.suitability_input),
               "categories": sha256_file(args.categories), "previous_levels": sha256_file(args.previous_levels),
               "profile_config": sha256_file(args.profiles_config),
               "selection_config": sha256_file(args.selection_config),
               "campaign_config": sha256_file(args.campaign_config),
               "planner_config": sha256_file(args.config),
               "alphabet": sha256_file(args.alphabet)}
    if args.hints:
        digests["hints"] = sha256_file(args.hints)
    return {"words": words, "items_by_pair": items_by_pair, "all_items": all_items,
            "pair_resolved": pair_resolved, "pair_sources": pair_sources,
            "features": features, "frequencies": frequencies,
            "metadata": metadata, "category_state": category_state, "category_index": index,
            "hints": hints, "previous_slots": previous_slots(args.previous_levels),
            "digests": digests, "model": args.model, "provider": args.provider,
            "cache_suitability": args.cache_suitability, "cache_categories": args.cache_categories,
            "cache_suitability_enabled": bool(args.cache_suitability and args.model),
            "cache_category_enabled": bool(args.cache_categories and args.model)}


def write_artifacts(directory, plan):
    directory.mkdir(parents=True, exist_ok=True)
    atomic_json(directory / "request_plan.json", plan)
    pairs = [f'{row["word"]} {row["role"]}' for row in plan["suitability"]["requests"]]
    (directory / "requested_pairs.txt").write_text("\n".join(pairs) + ("\n" if pairs else ""), encoding="utf-8")
    words = [row["word"] for row in plan["category"]["requests"]]
    (directory / "requested_words.txt").write_text("\n".join(words) + ("\n" if words else ""), encoding="utf-8")
    lines = ["# План ИИ-оценок — SEL-04", "",
             f"Статус: {plan['status']}. ИИ не запускался; кэш только читался.", "",
             "## Запросы пригодности (LEX-04)", "",
             f"- Пар: {plan['suitability']['requested']}; правилами решено: "
             f"{plan['suitability']['resolved_by_rules']}; ИИ ранее: {plan['suitability']['resolved_by_ai']}; "
             f"в кэше: {plan['suitability']['cache_resolved']}.",
             "- Файл для --pairs-file: `requested_pairs.txt`.", "",
             "## Запросы категорий (LEX-03)", "",
             f"- Слов: {plan['category']['requested']}; правилами решено: "
             f"{plan['category']['resolved_by_rules']}; ИИ ранее: {plan['category']['resolved_by_ai']}; "
             f"в кэше: {plan['category']['cache_resolved']}.",
             "- Файл для --words-file: `requested_words.txt`.", "", "## Группы причин", ""]
    for group in plan["groups"]:
        lines.append(f"- {group['family']}: {group['requested']} — {group['note']}")
    if plan["infeasible"]["proven"]:
        lines += ["", "## Структурные препятствия", ""]
        lines += ["- " + reason for reason in plan["infeasible"]["reasons"]]
    lines += ["", "## Ограничения кампании (не изменены)", ""]
    for row in plan["audits"]["checked_constraints"]:
        lines.append(f"- {row['constraint']}: {row['value']} ({row['enforced_by']}).")
    (directory / "REQUEST_PLAN.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def run(args):
    taxonomy = thematic.load_taxonomy()
    profile_config = profiles.load_config(args.profiles_config)
    selection_config = selection.load_config(args.selection_config)
    campaign_config = campaign_gen.load_config(args.campaign_config)
    planner_config = load_config(args.config)
    if not planner_config["planning_bands_override"] and \
            planner_config["planning_bands"] != selection_config["bands"]:
        raise ValueError("planning_bands планировщика расходятся с level_selection_config.json")
    if args.provider not in ("codex", "opencode"):
        raise ValueError("Провайдеры планировщика: codex, opencode")
    alphabet = selection.read_alphabet(args.alphabet)
    state = build_state(args, taxonomy)
    planner = Planner(state, selection_config, campaign_config, profile_config, planner_config, alphabet)
    plan = planner.plan()
    write_artifacts(args.output.parent, plan)
    return {"status": plan["status"],
            "suitability_requested": plan["suitability"]["requested"],
            "category_requested": plan["category"]["requested"],
            "suitability_resolved": {"rules": plan["suitability"]["resolved_by_rules"],
                                     "ai": plan["suitability"]["resolved_by_ai"],
                                     "cache": plan["suitability"]["cache_resolved"]},
            "category_resolved": {"rules": plan["category"]["resolved_by_rules"],
                                  "ai": plan["category"]["resolved_by_ai"],
                                  "cache": plan["category"]["cache_resolved"]},
            "artifacts": {"plan": "request_plan.json", "pairs": "requested_pairs.txt",
                          "words": "requested_words.txt", "report": "REQUEST_PLAN.md"}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--words", type=Path, default=ROOT / "data/words.txt")
    parser.add_argument("--suitability-input", type=Path,
                        default=ROOT / "build/reports/level_profiles/suitability_input.json")
    parser.add_argument("--categories", type=Path,
                        default=ROOT / "build/reports/local_definitions/category_index.json")
    parser.add_argument("--hints", type=Path, default=ROOT / "build/reports/level_profiles/hints.jvm.jsonl")
    parser.add_argument("--previous-levels", type=Path, default=ROOT / "data/levels.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/selective/request_plan.json")
    parser.add_argument("--config", type=Path, default=CONFIG)
    parser.add_argument("--profiles-config", type=Path, default=profiles.CONFIG)
    parser.add_argument("--selection-config", type=Path, default=selection.CONFIG)
    parser.add_argument("--campaign-config", type=Path, default=campaign_gen.CONFIG)
    parser.add_argument("--alphabet", type=Path, default=ALPHABET_FILE)
    parser.add_argument("--suitability-decisions", type=Path,
                        help="word_suitability.json выборочного прохода LEX-04 для учёта готовых решений")
    parser.add_argument("--cache-categories", type=Path, help="поштучный кэш LEX-03; только чтение")
    parser.add_argument("--cache-suitability", type=Path, help="поштучный кэш LEX-04; только чтение")
    parser.add_argument("--model", help="модель контекста кэша; без неё кэш не проверяется")
    parser.add_argument("--provider", choices=("codex", "opencode"), default="codex")
    args = parser.parse_args()
    try:
        summary = run(args)
        print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, "Ошибка планирования запросов: " + str(error) + "\n")


if __name__ == "__main__":
    main()
