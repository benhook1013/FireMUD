import copy
import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "refresh_daemon", Path(__file__).with_name("refresh-gradle-daemon-jvm.py")
)
refresh_daemon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh_daemon)


def metadata():
    records = []
    for os, arch in refresh_daemon.PLATFORMS:
        suffix = ".zip" if os == "windows" else ".tar.gz"
        records.append({
            "vendor": "eclipse", "release_name": "jdk-21.0.12.1+1",
            "version": {"major": 21, "minor": 0, "security": 12, "patch": 1, "build": 1,
                        "semver": "21.0.12+101.0.LTS", "openjdk_version": "21.0.12.1+1-LTS"},
            "binary": {"os": os, "architecture": arch, "image_type": "jdk",
                       "jvm_impl": "hotspot", "project": "jdk", "package": {
                           "link": "https://github.com/adoptium/temurin21-binaries/releases/download/"
                           f"jdk-21.0.12.1%2B1/OpenJDK21U-jdk_{arch}_{os}_hotspot_21.0.12.1_1{suffix}"}},
        })
    return records


class DaemonCriteriaTest(unittest.TestCase):
    def test_accepts_one_release_and_exact_generated_artifact(self):
        urls = refresh_daemon.select_downloads(metadata())
        criteria = "toolchainVersion=21\ntoolchainVendor=ADOPTIUM\n" + "".join(
            "toolchainUrl.{}={}\n".format(platform, url.replace(":", r"\:"))
            for platform, url in urls.items()
        )
        refresh_daemon.validate_criteria(criteria, urls)
        for mutated in [criteria.replace("Version=21", "Version=25"),
                        criteria.replace("Vendor=ADOPTIUM", "Vendor=OTHER"),
                        criteria.replace("LINUX.X86_64", "FREE_BSD.X86_64"),
                        criteria.replace("https", "http"), criteria + "toolchainVersion=21\n",
                        criteria + "toolchainImplementation=J9\n", criteria.split("toolchainUrl")[0]]:
            with self.subTest(criteria=mutated), self.assertRaises(ValueError):
                refresh_daemon.validate_criteria(mutated, urls)

    def test_rejects_missing_duplicate_and_malformed_platforms(self):
        records = metadata()
        cases = [None, {}, [None], records[:-1], records + [records[0]]]
        malformed = copy.deepcopy(records)
        malformed[0]["binary"]["os"] = []
        cases.append(malformed)
        for case in cases:
            with self.subTest(case=case), self.assertRaises((TypeError, ValueError)):
                refresh_daemon.select_downloads(case)

    def test_rejects_mixed_major_release_vendor_and_url_authority(self):
        mutations = [
            ("version", "major", 25), ("version", "major", "21"),
            ("version", "build", "1"), ("version", "openjdk_version", "25.0.1+1"),
            (None, "release_name", "jdk-21.0.12.1+2"), (None, "vendor", "other"),
            ("binary", "jvm_impl", "openj9"),
        ]
        for section, key, value in mutations:
            case = metadata()
            target = case[0] if section is None else case[0][section]
            target[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                refresh_daemon.select_downloads(case)
        case = metadata()
        case[0]["release_name"] = "jdk-21.0.12.1+2"
        case[0]["version"]["build"] = 2
        case[0]["version"]["openjdk_version"] = "21.0.12.1+2-LTS"
        with self.assertRaises(ValueError):
            refresh_daemon.select_downloads(case)
        for old, new in [("github.com", "example.com"), ("adoptium/", "other/"),
                         ("temurin21-binaries", "temurin25-binaries"),
                         ("https://", "http://"), ("%2B1/", "%2B2/")]:
            case = metadata()
            package = case[0]["binary"]["package"]
            package["link"] = package["link"].replace(old, new)
            with self.subTest(new=new), self.assertRaises(ValueError):
                refresh_daemon.select_downloads(case)


if __name__ == "__main__":
    unittest.main()
