import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "refresh_daemon", Path(__file__).with_name("refresh-gradle-daemon-jvm.py")
)
refresh_daemon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh_daemon)


class DaemonCriteriaTest(unittest.TestCase):
    def setUp(self):
        self.criteria = "toolchainVersion=21\ntoolchainVendor=ADOPTIUM\ntoolchainUrl.LINUX.X86_64=https\\://api.foojay.io/disco/v3.0/ids/example/redirect\n"

    def test_accepts_generator_artifact_without_platform_mapping(self):
        refresh_daemon.validate_criteria(self.criteria)
        refresh_daemon.validate_criteria(self.criteria.replace("LINUX.X86_64", "NEW_PLATFORM.NEW_ARCH"))

    def test_rejects_language_and_vendor_drift(self):
        for old, new in [("Version=21", "Version=25"), ("Vendor=ADOPTIUM", "Vendor=OTHER")]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                refresh_daemon.validate_criteria(self.criteria.replace(old, new))

    def test_rejects_empty_insecure_duplicate_and_unknown_properties(self):
        cases = [self.criteria.split("toolchainUrl")[0],
                 self.criteria.replace("https", "http"),
                 self.criteria + "toolchainVersion=21\n",
                 self.criteria + "toolchainImplementation=J9\n"]
        for criteria in cases:
            with self.subTest(criteria=criteria), self.assertRaises(ValueError):
                refresh_daemon.validate_criteria(criteria)


if __name__ == "__main__":
    unittest.main()
