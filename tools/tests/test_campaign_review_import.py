"""Staging checks for tools/campaign_review_import.py.

The installed short15 campaign is copied to a temporary project so the tests
exercise the real 15/49 campaign, dictionary and definitions database without
ever touching ``data/``.  Workbooks are tiny standard-library XLSX files; the
importer must not require openpyxl.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile
import unittest
from xml.sax.saxutils import escape
from zipfile import ZIP_DEFLATED, ZipFile

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import campaign_review_export as export
import campaign_review_import as review


HEADERS = ("Уровень", "ID задания", "Тип", "Текущее слово", "Действие",
           "Новое значение", "Основание", "Права/статус", "Источник/URL")


def digest(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _cell(reference: str, value: object) -> str:
    text = escape(str(value))
    return (f'<c r="{reference}" t="inlineStr"><is><t xml:space="preserve">'
            f'{text}</t></is></c>')


def _sheet_xml(rows: list[list[object]], start_row: int = 1) -> str:
    body = []
    for offset, row in enumerate(rows):
        number = start_row + offset
        cells = []
        for index, value in enumerate(row):
            column = chr(ord("A") + index)
            cells.append(_cell(f"{column}{number}", value))
        body.append(f'<row r="{number}">{"".join(cells)}</row>')
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f'<sheetData>{"".join(body)}</sheetData></worksheet>')


def write_workbook(path: Path, rows: list[list[object]],
                   other_sheet_rows: list[list[object]] | None = None) -> None:
    """Write a minimal valid XLSX with the editable sheet and one extra sheet."""
    sheets = [("Правки", _sheet_xml([list(HEADERS), *rows]))]
    if other_sheet_rows is not None:
        sheets.append(("Обзор", _sheet_xml(other_sheet_rows)))
    workbook_sheets = "".join(
        f'<sheet name="{escape(name)}" sheetId="{index}" r:id="rId{index}"/>'
        for index, (name, _) in enumerate(sheets, 1))
    workbook = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                f'<sheets>{workbook_sheets}</sheets></workbook>')
    relationships = "".join(
        f'<Relationship Id="rId{index}" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
        f'Target="worksheets/sheet{index}.xml"/>'
        for index in range(1, len(sheets) + 1))
    rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'{relationships}</Relationships>')
    root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 '<Relationship Id="rId1" '
                 'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
                 'Target="xl/workbook.xml"/></Relationships>')
    overrides = "".join(
        f'<Override PartName="/xl/worksheets/sheet{index}.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        for index in range(1, len(sheets) + 1))
    content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                     '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                     '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                     '<Default Extension="xml" ContentType="application/xml"/>'
                     '<Override PartName="/xl/workbook.xml" '
                     'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                     f'{overrides}</Types>')
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", rels)
        for index, (_, xml) in enumerate(sheets, 1):
            archive.writestr(f"xl/worksheets/sheet{index}.xml", xml)


@unittest.skipUnless((ROOT / "data/campaign.json").is_file(),
                     "installed short15 data is absent")
class ImportInstalledCampaignTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name) / "project"
        data = cls.root / "data"
        data.mkdir(parents=True)
        for name in ("campaign.json", "words.txt", "definitions.db", "manifest.json"):
            shutil.copy2(ROOT / "data" / name, data / name)
        # Keep T6_ADD/category-review coverage independent of editorial additions
        # to the installed campaign. Only this temporary fixture is changed.
        campaign = json.loads((data / "campaign.json").read_text(encoding="utf-8"))
        task = next(task for level in campaign["levels"] for task in level["tasks"]
                    if task["id"] == "l14-t6")
        task["words"] = [word for word in task["words"] if word not in {"акробат", "диктор"}]
        (data / "campaign.json").write_text(json.dumps(campaign, ensure_ascii=False), encoding="utf-8")
        with sqlite3.connect(data / "definitions.db") as database:
            database.executemany(
                "UPDATE word_metadata SET category=?, category_source=? WHERE word=?",
                [("sport", "synthetic:test", "акробат"), ("other", "synthetic:test", "диктор")])
        manifest = json.loads((data / "manifest.json").read_text(encoding="utf-8"))
        manifest["campaign_source"]["sha256"] = digest(data / "campaign.json")
        manifest["definitions"]["sha256"] = digest(data / "definitions.db")
        (data / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        cls.original_campaign = (data / "campaign.json").read_bytes()
        cls.data_hashes = {name: digest(data / name) for name in
                           ("campaign.json", "words.txt", "definitions.db", "manifest.json")}
        cls.snapshot = export.export(cls.root)
        cls.snapshot_path = cls.root / "build/reports/curation/curation_snapshot.json"
        cls.snapshot_path.parent.mkdir(parents=True, exist_ok=True)
        cls.snapshot_path.write_text(
            json.dumps(cls.snapshot, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8")
        cls.snapshot_hash = digest(cls.snapshot_path)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def rows(self) -> list[list[object]]:
        return [[row["level"], row["task_id"], row["type"], row["word"],
                 "", "", "", "", ""] for row in self.snapshot["edits"]]

    def workbook(self, name: str, extra: list[list[object]] | None = None,
                 other_sheet_rows: list[list[object]] | None = None) -> Path:
        path = self.root / name
        write_workbook(path, self.rows() + list(extra or []), other_sheet_rows)
        return path

    def run_import(self, name: str, extra: list[list[object]] | None = None,
                   workbook: Path | None = None, **kwargs):
        out = self.root / "build/reports/curation" / name
        workbook = workbook or self.workbook(name + ".xlsx", extra)
        report = review.stage(self.root, self.snapshot_path, workbook, out, **kwargs)
        return report, out

    def assert_data_unchanged(self):
        self.assertEqual(self.original_campaign,
                         (self.root / "data/campaign.json").read_bytes())
        self.assertEqual(self.data_hashes,
                         {name: digest(self.root / "data" / name) for name in self.data_hashes})

    def test_empty_workbook_has_zero_changes_and_byte_identical_candidate(self):
        report, out = self.run_import("empty")
        self.assertEqual([], report["changes"])
        self.assertEqual([], report["diff"])
        self.assertTrue(report["candidate_equals_source"])
        self.assertEqual(self.snapshot["snapshot"]["campaign_sha256"],
                         report["candidate_campaign_sha256"])
        self.assertEqual(self.original_campaign, (out / "campaign.candidate.json").read_bytes())
        self.assertEqual([], json.loads((out / "hint_overrides.pending.json").read_text()))
        self.assertFalse(report["production_validated"])
        self.assertFalse(report["rights_audit_passed"])
        self.assertFalse(report["publishable"])
        self.assert_data_unchanged()

    def test_only_the_edits_sheet_is_used(self):
        extra = [14, "l14-t6", "T6", "", "T6_ADD", "акробат",
                 "Цирковая профессия", "не подтверждено",
                 "https://ru.wiktionary.org/wiki/акробат"]
        workbook = self.workbook("other-sheet.xlsx", other_sheet_rows=[extra])
        report, out = self.run_import("other-sheet", workbook=workbook)
        self.assertEqual([], report["changes"])
        self.assertEqual(self.original_campaign, (out / "campaign.candidate.json").read_bytes())

    def test_t6_add_known_formable_words_are_staged_with_category_flags(self):
        extra = [
            [14, "l14-t6", "T6", "", "T6_ADD", "акробат",
             "Цирковой гимнаст; профессия, подтверждённая словарём.",
             "не подтверждено", "https://ru.wiktionary.org/wiki/акробат"],
            [14, "l14-t6", "T6", "", "T6_ADD", "диктор",
             "Работник радио и телевидения; профессия.",
             "не подтверждено", "https://ru.wiktionary.org/wiki/диктор"],
        ]
        report, out = self.run_import("t6-add", extra)
        candidate = json.loads((out / "campaign.candidate.json").read_text(encoding="utf-8"))
        task = next(task for level in candidate["levels"] for task in level["tasks"]
                    if task["id"] == "l14-t6")
        self.assertIn("акробат", task["words"])
        self.assertIn("диктор", task["words"])
        flags = {flag["word"]: flag for flag in report["needs_manual_review"]
                 if flag["code"] == "manual_category_evidence_required"}
        self.assertEqual("sport", flags["акробат"]["observed_category"])
        self.assertEqual("other", flags["диктор"]["observed_category"])
        self.assertTrue(flags["акробат"]["override_category"])
        self.assertTrue(flags["акробат"]["manual_confirmation_required"])
        self.assertFalse(report["production_validated"])
        self.assertFalse(report["rights_audit_passed"])
        self.assertFalse(report["publishable"])
        self.assert_data_unchanged()

    def test_t6_add_unknown_word_is_rejected(self):
        extra = [[14, "l14-t6", "T6", "", "T6_ADD", "крокозябра",
                  "Нет такого слова", "не подтверждено", "https://example.org/word"]]
        with self.assertRaisesRegex(ValueError, "словаре"):
            self.run_import("unknown", extra)
        self.assert_data_unchanged()

    def test_t6_add_existing_word_is_rejected(self):
        extra = [[14, "l14-t6", "T6", "", "T6_ADD", "артист",
                  "Повтор профессии", "не подтверждено", "https://example.org/word"]]
        with self.assertRaisesRegex(ValueError, "уже есть в T6"):
            self.run_import("existing", extra)
        self.assert_data_unchanged()

    def test_t6_add_requires_evidence(self):
        extra = [[14, "l14-t6", "T6", "", "T6_ADD", "акробат",
                  "Цирковая профессия", "не подтверждено", ""]]
        with self.assertRaisesRegex(ValueError, "источник"):
            self.run_import("no-evidence", extra)

    def test_snapshot_hash_mismatch_is_rejected(self):
        bad = json.loads(self.snapshot_path.read_text(encoding="utf-8"))
        bad["snapshot"]["campaign_sha256"] = "0" * 64
        path = self.root / "bad-snapshot.json"
        path.write_text(json.dumps(bad, ensure_ascii=False), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            review.stage(self.root, path, self.workbook("bad-snapshot.xlsx"),
                         self.root / "build/reports/curation/bad-snapshot")

    def test_workbook_hash_pin_mismatch_is_rejected(self):
        workbook = self.workbook("pinned.xlsx")
        with self.assertRaisesRegex(ValueError, "SHA-256 книги"):
            self.run_import("pinned", workbook=workbook,
                            expected_workbook_sha256="0" * 64)

    def test_unreachable_n_is_rejected(self):
        extra = [[14, "l14-t6", "T6", "", "SET_N", "999",
                  "Проверка недостижимого порога", "не подтверждено", ""]]
        with self.assertRaisesRegex(ValueError, "недостижим"):
            self.run_import("unreachable", extra)

    def test_r_outside_star_count_is_rejected(self):
        extra = [[1, "l01-t1", "T1", "", "SET_R", "2",
                  "Проверка границы R", "не подтверждено", ""]]
        with self.assertRaisesRegex(ValueError, "R вне"):
            self.run_import("bad-r", extra)

    def test_t3_t4_target_uniqueness_is_preserved(self):
        extra = [[12, "l12-t4", "T4", "билет", "T4_SWAP", "работа",
                  "Проверка повтора цели", "не подтверждено",
                  "https://ru.wiktionary.org/wiki/работа"]]
        with self.assertRaisesRegex(ValueError, "неуникальны"):
            self.run_import("duplicate", extra)

    def test_t3_swap_cannot_reuse_an_existing_target(self):
        extra = [[12, "l12-t3", "T3", "километр", "T3_SWAP", "проблема",
                  "Проверка повтора цели T3", "не подтверждено",
                  "https://ru.wiktionary.org/wiki/проблема"]]
        with self.assertRaisesRegex(ValueError, "неуникальны"):
            self.run_import("duplicate-t3", extra)

    def test_hint_replace_is_pending_only_and_does_not_change_campaign_or_db(self):
        extra = [[6, "l06-t3", "T3", "абсурд", "HINT_REPLACE",
                  "Отсутствие смысла и логической связи.",
                  "Редакторская формулировка", "не подтверждено",
                  "https://example.org/editorial-note"]]
        report, out = self.run_import("hint", extra)
        pending = json.loads((out / "hint_overrides.pending.json").read_text(encoding="utf-8"))
        self.assertEqual(1, len(pending))
        self.assertEqual("абсурд", pending[0]["word"])
        self.assertEqual("Отсутствие смысла и логической связи.", pending[0]["text"])
        self.assertEqual(self.original_campaign, (out / "campaign.candidate.json").read_bytes())
        self.assertFalse(report["hint_overrides_integrated"])
        self.assertEqual(self.data_hashes["definitions.db"],
                         digest(self.root / "data/definitions.db"))
        self.assert_data_unchanged()

    def test_hint_replace_that_reveals_answer_is_rejected(self):
        extra = [[6, "l06-t3", "T3", "абсурд", "HINT_REPLACE",
                  "Абсурд — это отсутствие смысла.", "Плохая подсказка",
                  "не подтверждено", "https://example.org/bad-hint"]]
        with self.assertRaisesRegex(ValueError, "раскрывает"):
            self.run_import("revealing-hint", extra)

    def test_conflicting_hint_replacements_are_rejected(self):
        extra = [
            [6, "l06-t3", "T3", "абсурд", "HINT_REPLACE",
             "Отсутствие смысла и логической связи.", "Первый вариант",
             "не подтверждено", "https://example.org/hint-1"],
            [6, "l06-t3", "T3", "абсурд", "HINT_REPLACE",
             "Нелепость и бессмысленность происходящего.", "Второй вариант",
             "не подтверждено", "https://example.org/hint-2"],
        ]
        with self.assertRaisesRegex(ValueError, "противоречивый"):
            self.run_import("conflicting-hints", extra)

    def test_output_into_data_is_rejected(self):
        workbook = self.workbook("data-output.xlsx")
        with self.assertRaisesRegex(ValueError, "data/"):
            review.stage(self.root, self.snapshot_path, workbook,
                         self.root / "data/staging")
        self.assertFalse((self.root / "data/staging").exists())

    def test_protected_identity_columns_are_rejected(self):
        rows = self.rows()
        rows[0][0] = 99
        workbook = self.root / "protected.xlsx"
        write_workbook(workbook, rows)
        with self.assertRaisesRegex(ValueError, "защищённые"):
            review.stage(self.root, self.snapshot_path, workbook,
                         self.root / "build/reports/curation/protected")

    def _fake_validator(self, name: str, pass_validation: bool) -> Path:
        path = self.root / name
        valid = "True" if pass_validation else "False"
        path.write_text(f'''#!/usr/bin/env python3
import argparse, json
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument("--campaign"); parser.add_argument("--profile")
parser.add_argument("--output")
args = parser.parse_args()
out = Path(args.output); out.mkdir(parents=True, exist_ok=True)
(out / "validation_report.json").write_text(json.dumps({{
    "status": "valid" if {valid} else "invalid",
    "installable": {valid},
}}))
(out / "validated_campaign.json").write_text("checked copy")
raise SystemExit(0 if {valid} else 2)
''', encoding="utf-8")
        return path

    def test_official_validation_failure_leaves_no_validated_campaign(self):
        validator = self._fake_validator("validator-fail.py", False)
        report, out = self.run_import("validation-fail", validate=True,
                                      validator=validator)
        self.assertEqual("FAIL", report["official_validation"]["status"])
        self.assertFalse(report["official_validation"]["validated_campaign"])
        self.assertFalse((out / "official_validation/validated_campaign.json").exists())
        self.assertFalse(report["production_validated"])
        self.assertFalse(report["publishable"])

    def test_official_validation_pass_keeps_only_a_staged_copy(self):
        validator = self._fake_validator("validator-pass.py", True)
        report, out = self.run_import("validation-pass", validate=True,
                                      validator=validator)
        self.assertEqual("PASS", report["official_validation"]["status"])
        self.assertTrue(report["official_validation"]["validated_campaign"])
        self.assertTrue((out / "official_validation/validated_campaign.json").is_file())
        self.assertFalse(report["production_validated"])
        self.assertFalse(report["rights_audit_passed"])
        self.assertFalse(report["publishable"])
        self.assertEqual(self.original_campaign, (out / "campaign.candidate.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
