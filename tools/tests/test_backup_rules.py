"""SAVE-16: offline Android backup policy, checked without an SDK."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}


class BackupRulesTest(unittest.TestCase):
    def setUp(self):
        self.rules = ET.parse(MAIN / "res/xml/data_extraction_rules.xml").getroot()

    def assert_every_domain_excluded(self, section):
        self.assertEqual(len(DOMAINS), len(section))
        self.assertEqual(
            {(domain, ".") for domain in DOMAINS},
            {(node.get("domain"), node.get("path")) for node in section},
        )
        for node in section:
            self.assertEqual("exclude", node.tag)
            self.assertEqual({"domain", "path"}, set(node.attrib))

    def test_cloud_backup_excludes_every_domain(self):
        self.assertEqual("data-extraction-rules", self.rules.tag)
        self.assertEqual(["cloud-backup", "device-transfer"], [node.tag for node in self.rules])
        self.assert_every_domain_excluded(self.rules.find("cloud-backup"))

    def test_device_transfer_includes_only_current_progress_store(self):
        transfer = self.rules.find("device-transfer")
        # A single include is an allowlist across ALL domains. Do not add a
        # blanket file/root exclude: it would also suppress the included file.
        self.assertEqual(1, len(transfer))
        self.assertEqual("include", transfer[0].tag)
        self.assertEqual(
            {"domain": "file", "path": "datastore/progress.json"}, transfer[0].attrib,
        )
        container = (MAIN / "kotlin/com/example/sostavslova/di/AppContainer.kt").read_text()
        self.assertRegex(container, r'File\(appContext\.filesDir, "datastore"\)')
        self.assertRegex(container, r'progressFileProvider = progressFile \?: \{ File\(storeDirectory, "progress\.json"\) \}')

    def test_android_7_to_11_disables_both_backup_modes(self):
        legacy = ET.parse(MAIN / "res/xml/backup_rules.xml").getroot()
        self.assertEqual("full-backup-content", legacy.tag)
        self.assert_every_domain_excluded(legacy)

    def test_manifest_enables_backup_with_both_rule_files_and_no_permissions(self):
        manifest = ET.parse(MAIN / "AndroidManifest.xml").getroot()
        application = manifest.find("application")
        self.assertEqual("true", application.get(ANDROID + "allowBackup"))
        self.assertEqual("@xml/data_extraction_rules", application.get(ANDROID + "dataExtractionRules"))
        self.assertEqual("@xml/backup_rules", application.get(ANDROID + "fullBackupContent"))
        self.assertIsNone(application.get(ANDROID + "backupAgent"))
        for path in (ROOT / "app/src").rglob("AndroidManifest.xml"):
            with self.subTest(manifest=path):
                document = ET.parse(path).getroot()
                self.assertEqual([], [node.attrib for node in document if node.tag.startswith("uses-permission")])


if __name__ == "__main__":
    unittest.main()
