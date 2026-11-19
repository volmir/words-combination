"""The active spec's campaign numbers must match the installed campaign.json.

The spec is a hand-written document; these checks make its numbers a single
source of truth with the data, so editing any of them fails the tools gate.
"""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "docs" / "SPEC.md"
CAMPAIGN = ROOT / "data" / "campaign.json"

LEVEL_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*([^|]+?)\s*\|$")
STAGE_ROW = re.compile(r"^\|\s*(\d+)\s*\|\s*(\d+)[–-](\d+)\s*\|$")
TOTAL = re.compile(r"Итого:.*?R\b.*?=\s*(\d+).*?M\b.*?=\s*(\d+)")
FINALE = re.compile(r"уровень\s*(\d+).*?при\s*(\d+)\s*из\s*(\d+).*?\(R=(\d+),\s*M=(\d+)\)")
MASTER = re.compile(r"собраны все\s*(\d+)\s*из\s*(\d+)\s*звёзд")


class SpecCampaignNumbersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.campaign = json.loads(CAMPAIGN.read_text(encoding="utf-8"))
        cls.spec = SPEC.read_text(encoding="utf-8")

    def rows(self, pattern):
        return [m for line in self.spec.splitlines() if (m := pattern.match(line))]

    def test_level_table_matches_campaign(self):
        rows = self.rows(LEVEL_ROW)
        levels = self.campaign["levels"]
        self.assertEqual(len(levels), len(rows), "SPEC level count differs from campaign.json")
        self.assertEqual(15, len(rows), "SPEC must list exactly the 15 active levels")
        for row, level in zip(rows, levels):
            number, stage, required, maximum, word = row.groups()
            self.assertEqual((number, stage, word),
                             (str(level["number"]), str(level["stage"]), level["word"]))
            self.assertEqual(str(level["requiredStars"]), required)
            self.assertEqual(str(len(level["tasks"])), maximum, "M must equal the task count")
        self.assertEqual(42, sum(int(r.group(3)) for r in rows), "SPEC R sum differs from campaign.json")
        self.assertEqual(49, sum(int(r.group(4)) for r in rows), "SPEC M sum differs from campaign.json")

    def test_stages_match_campaign(self):
        rows = self.rows(STAGE_ROW)
        stages = self.campaign["stages"]
        self.assertEqual(len(stages), len(rows), "SPEC stage count differs from campaign.json")
        self.assertEqual(6, len(rows), "SPEC must list exactly the 6 active stages")
        for row, stage in zip(rows, stages):
            self.assertEqual(tuple(int(g) for g in row.groups()),
                             (stage["number"], stage["firstLevel"], stage["lastLevel"]))

    def test_summary_numbers_match_campaign(self):
        total = TOTAL.search(self.spec)
        self.assertIsNotNone(total, "SPEC must state the R and M totals")
        self.assertEqual((42, 49), (int(total.group(1)), int(total.group(2))))

        finale = FINALE.search(self.spec)
        self.assertIsNotNone(finale, "SPEC must state the finale level and threshold")
        last = self.campaign["levels"][-1]
        self.assertEqual((str(last["number"]), str(last["requiredStars"]), str(len(last["tasks"]))),
                         (finale.group(1), finale.group(2), finale.group(3)))
        self.assertEqual((finale.group(2), finale.group(3)), (finale.group(4), finale.group(5)))

        master = MASTER.search(self.spec)
        self.assertIsNotNone(master, "SPEC must state the Master threshold")
        self.assertEqual((49, 49), (int(master.group(1)), int(master.group(2))))


if __name__ == "__main__":
    unittest.main()
