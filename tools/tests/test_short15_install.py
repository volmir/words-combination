"""Synthetic tests for tools/install_short15_package.py (no network, no real package)."""
import hashlib
import json
import shutil
from pathlib import Path
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import install_short15_package as installer


def digest(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def campaign_json() -> dict:
    ranges = [(1, 3), (4, 5), (6, 8), (9, 10), (11, 13), (14, 15)]
    counts = [1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 5, 5, 5]
    levels = []
    for number, count in enumerate(counts, 1):
        stage = next(index for index, (first, last) in enumerate(ranges, 1) if first <= number <= last)
        kinds = ["T1", "T2", "T3", "T4", "T5"] if number < 14 else (
            ["T1", "T2", "T3", "T4", "T6"] if number == 14 else ["T1", "T2", "T3", "T5", "T7"])
        levels.append(dict(number=number, word=f"слово{number:02d}", stage=stage,
                           requiredStars=4 if number >= 14 else 1,
                           tasks=[dict(id=f"l{number:02d}-t{index + 1}", type=kind)
                                  for index, kind in enumerate(kinds[:count])]))
    return dict(version=2, countMinLength=4,
                stages=[dict(number=index, firstLevel=first, lastLevel=last)
                        for index, (first, last) in enumerate(ranges, 1)],
                levels=levels)


def write_package(directory: Path, *, installable: bool = True) -> Path:
    directory.mkdir(parents=True)
    for name in installer.ARTIFACTS:
        shutil.copyfile(TOOLS.parent/'data'/name,directory/name)
    campaign_sha = digest(directory/'campaign.json')
    report = dict(schema="campaign-validation-v1", installable=installable,
                  unreachable=[], balance_deviations=[], parity_deviations=[],
                  levels=[dict(number=i) for i in range(1, 16)])
    (directory / "validation_report.json").write_text(json.dumps(report) + "\n", encoding="utf-8")
    quality = dict(campaign=dict(sha256=campaign_sha, levels=15, master_stars=49),
                   hashes={"campaign.json": campaign_sha})
    (directory / "quality_report.json").write_text(json.dumps(quality) + "\n", encoding="utf-8")
    names = ("words.txt", "word_sources.jsonl.gz", "levels.json", "golden_search.json",
             "owned_riddles.json", "campaign.json", "manifest.json", "validation_report.json",
             "quality_report.json")
    (directory / "SHA256SUMS").write_text(
        "".join(f"{digest(directory / name)}  {name}\n" for name in names), encoding="utf-8")
    return directory


def write_old_data(directory: Path) -> None:
    directory.mkdir(parents=True)
    (directory / "words.txt").write_text("старое\n", encoding="utf-8")
    (directory / "word_sources.jsonl.gz").write_bytes(b"old")
    (directory / "levels.json").write_text("[]\n", encoding="utf-8")
    (directory / "golden_search.json").write_text("{}\n", encoding="utf-8")
    (directory / "owned_riddles.json").write_bytes(b"old-db")
    (directory / "manifest.json").write_text("{}\n", encoding="utf-8")


class InstallShort15Test(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.package = write_package(self.root / "package")
        self.output = self.root / "data"
        write_old_data(self.output)
        self.backup = self.root / "rollback"

    def test_install_records_hash_and_keeps_rollback(self):
        result = installer.install(self.package, self.output, self.backup)
        self.assertEqual("installed", result["status"])
        manifest = json.loads((self.output / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(installer.PROFILE, manifest["campaign_source"]["profile"])
        self.assertEqual(digest(self.package / "campaign.json"), manifest["campaign_source"]["sha256"])
        self.assertEqual(digest(self.package / "campaign.json"), digest(self.output / "campaign.json"))
        self.assertEqual("старое\n", (self.backup / "words.txt").read_text(encoding="utf-8"))
        self.assertTrue((self.backup / "SHA256SUMS.rollback").is_file())
        self.assertEqual((self.package/"owned_riddles.json").read_bytes(), (self.output/"owned_riddles.json").read_bytes())

    def test_second_install_keeps_original_rollback(self):
        installer.install(self.package, self.output, self.backup)
        installer.install(self.package, self.output, self.backup)
        self.assertEqual("старое\n", (self.backup / "words.txt").read_text(encoding="utf-8"))

    def test_tampered_campaign_is_rejected_before_any_write(self):
        before = (self.output / "words.txt").read_bytes()
        (self.package / "campaign.json").write_text("{}", encoding="utf-8")
        with self.assertRaises(ValueError):
            installer.install(self.package, self.output, self.backup)
        self.assertEqual(before, (self.output / "words.txt").read_bytes())
        self.assertFalse(self.backup.exists())

    def test_non_installable_validation_is_rejected(self):
        package = write_package(self.root / "bad", installable=False)
        with self.assertRaises(ValueError):
            installer.install(package, self.output, self.backup)

    def test_dry_run_does_not_change_data_or_backup(self):
        result = installer.install(self.package, self.output, self.backup, dry_run=True)
        self.assertEqual("dry-run", result["status"])
        self.assertEqual("старое\n", (self.output / "words.txt").read_text(encoding="utf-8"))
        self.assertFalse(self.backup.exists())

    def test_manifest_campaign_hash_must_match_file(self):
        manifest_path = self.package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["campaign_source"] = dict(sha256="0" * 64)
        manifest_path.write_text(json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8")
        names = ("words.txt", "word_sources.jsonl.gz", "levels.json", "golden_search.json",
                 "owned_riddles.json", "campaign.json", "manifest.json", "validation_report.json",
                 "quality_report.json")
        (self.package / "SHA256SUMS").write_text(
            "".join(f"{digest(self.package / name)}  {name}\n" for name in names), encoding="utf-8")
        with self.assertRaises(ValueError):
            installer.verify_package(self.package)


if __name__ == "__main__":
    unittest.main()
