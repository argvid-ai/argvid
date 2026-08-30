"""Inspect the built APK, not the source manifest, for the local-only boundary."""

import os
from pathlib import Path
import re
import subprocess
import unittest


class ApkPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/aapt"
        cls.apk = Path(__file__).resolve().parents[1] / "src/app/build/outputs/apk/debug/app-debug.apk"

    def dump(self, *args):
        return subprocess.check_output([str(self.aapt), "dump", *args], text=True)

    def test_app_has_separate_sandbox_and_no_network_or_extra_runtime_permissions(self):
        output = self.dump("permissions", str(self.apk))
        self.assertIn("package: ai.argvid.gen0.camera\n", output)
        permissions = set(re.findall(r"uses-permission: name='([^']+)'", output))
        self.assertEqual({
            "android.permission.CAMERA",
            "android.permission.WAKE_LOCK",
            "ai.argvid.gen0.camera.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        }, permissions)

    def test_private_app_data_is_excluded_from_backup_and_device_transfer(self):
        manifest = self.dump("xmltree", str(self.apk), "AndroidManifest.xml")
        self.assertRegex(manifest, r"android:allowBackup[^\n]*=\(type 0x12\)0x0")
        self.assertIn("android:dataExtractionRules", manifest)
        rules = self.dump("xmltree", str(self.apk), "res/xml/data_extraction_rules.xml")
        sections = {}
        current = None
        for line in rules.splitlines():
            if "E: cloud-backup" in line:
                current = "backup"
                sections[current] = set()
            elif "E: device-transfer" in line:
                current = "transfer"
                sections[current] = set()
            elif 'A: domain="' in line:
                sections[current].add(re.search(r'A: domain="([^"]+)"', line)[1])
        expected = {"root", "file", "database", "sharedpref", "external",
                    "device_root", "device_file", "device_database", "device_sharedpref"}
        self.assertEqual({"backup": expected, "transfer": expected}, sections)
        self.assertEqual(18, rules.count('A: path="."'))
        self.assertEqual(18, rules.count("E: exclude"))
        self.assertNotIn("E: include", rules)


if __name__ == "__main__":
    unittest.main()
