import copy
import hashlib
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

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


def criteria_text(urls=None):
    if urls is None:
        urls = refresh_daemon.select_downloads(metadata())
    lines = ["toolchainVersion=21", "toolchainVendor=ADOPTIUM"]
    lines.extend(
        "toolchainUrl.{}={}".format(platform, url.replace(":", r"\:"))
        for platform, url in urls.items()
    )
    return "\n".join(lines) + "\n"


def feature_metadata(records):
    return [
        {
            "vendor": entry["vendor"],
            "release_name": entry["release_name"],
            "version_data": copy.deepcopy(entry["version"]),
            "binaries": [copy.deepcopy(entry["binary"])],
        }
        for entry in records
    ]


def staggered_latest_metadata():
    records = metadata()
    version = {
        "major": 21, "minor": 0, "security": 13, "build": 9,
        "semver": "21.0.13+9.0.LTS", "openjdk_version": "21.0.13+9-LTS",
    }
    for entry in records:
        binary = entry["binary"]
        if binary["os"] not in {"linux", "mac"}:
            continue
        entry["release_name"] = "jdk-21.0.13+9"
        entry["version"] = copy.deepcopy(version)
        suffix = ".zip" if binary["os"] == "windows" else ".tar.gz"
        binary["package"]["link"] = (
            "https://github.com/adoptium/temurin21-binaries/releases/download/"
            f"jdk-21.0.13%2B9/OpenJDK21U-jdk_{binary['architecture']}_{binary['os']}_"
            f"hotspot_21.0.13_9{suffix}"
        )
    return records


def repository_with_outside_edit(root):
    artifact = root / refresh_daemon.ARTIFACT
    artifact.parent.mkdir(parents=True)
    artifact.write_text(criteria_text())
    outside = root / "existing-edit.txt"
    outside.write_text("baseline\n")
    for command in [
        ["git", "init", "--quiet"],
        ["git", "config", "user.name", "FireMUD test"],
        ["git", "config", "user.email", "test@example.invalid"],
        ["git", "add", refresh_daemon.ARTIFACT, outside.name],
        ["git", "commit", "--quiet", "-m", "baseline"],
    ]:
        subprocess.run(command, cwd=root, check=True, capture_output=True)
    outside.write_text("existing local edit\n")
    return artifact, outside


def is_gradle_generator(command):
    return (
        isinstance(command, (list, tuple)) and len(command) >= 3
        and command[0] == "bash"
        and command[1].endswith("dev-tools/validation/run-locked-gradle.sh")
        and command[2] == "updateDaemonJvm"
    )


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

    def test_pinned_proof_uses_committed_release_while_latest_is_staggered(self):
        expected_urls = refresh_daemon.select_downloads(metadata())
        committed_criteria = criteria_text(expected_urls)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / refresh_daemon.ARTIFACT
            artifact.parent.mkdir(parents=True)
            artifact.write_text(committed_criteria)
            with patch.object(refresh_daemon, "ROOT", root), \
                    patch.object(refresh_daemon, "fetch_release_metadata",
                                 return_value=feature_metadata(metadata())) as pinned_fetch, \
                    patch.object(refresh_daemon, "fetch_latest_metadata",
                                 side_effect=AssertionError("check-only must not query latest")):
                self.assertEqual(refresh_daemon.select_refresh_downloads(check_only=True), expected_urls)
            pinned_fetch.assert_called_once_with("jdk-21.0.12.1+1")

        with patch.object(refresh_daemon, "fetch_latest_metadata",
                          return_value=staggered_latest_metadata()):
            with self.assertRaisesRegex(ValueError, "do not share one release/build"):
                refresh_daemon.select_refresh_downloads(check_only=False)

    def test_rejects_malformed_or_mixed_committed_pins(self):
        urls = refresh_daemon.select_downloads(metadata())
        text = criteria_text(urls)
        other_release = staggered_latest_metadata()[0]["binary"]["package"]["link"]
        escaped_pinned_url = urls["LINUX.X86_64"].replace(":", r"\:")
        escaped_other_release = other_release.replace(":", r"\:")
        mixed_pin = text.replace(escaped_pinned_url, escaped_other_release, 1)
        malformed = [
            text.replace("toolchainUrl.WINDOWS.X86_64=", "toolchainUrl.UNKNOWN=", 1),
            text + "toolchainVersion=21\n",
            text.replace("github.com", "example.com", 1),
            mixed_pin,
            text.replace(".tar.gz", ".zip", 1),
        ]
        self.assertNotEqual(mixed_pin, text)
        for candidate in malformed:
            with self.subTest(candidate=candidate), self.assertRaises((TypeError, ValueError)):
                refresh_daemon.pinned_release_and_urls(candidate)

    def test_pinned_metadata_must_include_six_exact_official_assets(self):
        text = criteria_text()
        rows = feature_metadata(metadata())
        expected = refresh_daemon.select_downloads(metadata())
        self.assertEqual(refresh_daemon.select_pinned_downloads(text, rows), expected)

        missing = rows[:-1]
        duplicate = rows + [copy.deepcopy(rows[0])]
        wrong_vendor = copy.deepcopy(rows)
        wrong_vendor[0]["vendor"] = "other"
        wrong_version = copy.deepcopy(rows)
        wrong_version[0]["version_data"]["build"] = 2
        wrong_url = copy.deepcopy(rows)
        wrong_url[0]["binaries"][0]["package"]["link"] = (
            wrong_url[0]["binaries"][0]["package"]["link"].replace("github.com", "example.com")
        )
        for candidate in [missing, duplicate, wrong_vendor, wrong_version, wrong_url]:
            with self.subTest(candidate=candidate), self.assertRaises((TypeError, ValueError)):
                refresh_daemon.select_pinned_downloads(text, candidate)

    def test_check_only_preserves_preexisting_edits_outside_the_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact, outside = repository_with_outside_edit(root)
            outside_hash = hashlib.sha256(outside.read_bytes()).digest()
            original_run = subprocess.run
            generator_calls = []

            def run_or_generate(*args, **kwargs):
                command = args[0] if args else kwargs["args"]
                if is_gradle_generator(command):
                    generator_calls.append(command)
                    return subprocess.CompletedProcess(command, 0)
                return original_run(*args, **kwargs)

            with patch.object(refresh_daemon, "ROOT", root), \
                    patch.object(refresh_daemon, "fetch_release_metadata",
                                 return_value=feature_metadata(metadata())), \
                    patch.object(refresh_daemon, "fetch_latest_metadata",
                                 side_effect=AssertionError("check-only must not query latest")), \
                    patch.object(refresh_daemon.subprocess, "run", side_effect=run_or_generate):
                refresh_daemon.refresh(check_only=True)

            self.assertEqual(outside.read_text(), "existing local edit\n")
            self.assertEqual(hashlib.sha256(outside.read_bytes()).digest(), outside_hash)
            self.assertEqual(artifact.read_text(), criteria_text())
            self.assertEqual(len(generator_calls), 1)
            command = generator_calls[0]
            self.assertIn("updateDaemonJvm", command)
            self.assertIn("--jvm-version=21", command)
            self.assertIn("--jvm-vendor=adoptium", command)

    def test_check_only_rejects_generator_edits_outside_the_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, outside = repository_with_outside_edit(root)
            original_run = subprocess.run
            generator_calls = []

            def run_or_generate(*args, **kwargs):
                command = args[0] if args else kwargs["args"]
                if is_gradle_generator(command):
                    generator_calls.append(command)
                    outside.write_text("unexpected generator edit\n")
                    return subprocess.CompletedProcess(command, 0)
                return original_run(*args, **kwargs)

            with patch.object(refresh_daemon, "ROOT", root), \
                    patch.object(refresh_daemon, "fetch_release_metadata",
                                 return_value=feature_metadata(metadata())), \
                    patch.object(refresh_daemon, "fetch_latest_metadata",
                                 side_effect=AssertionError("check-only must not query latest")), \
                    patch.object(refresh_daemon.subprocess, "run", side_effect=run_or_generate):
                with self.assertRaisesRegex(RuntimeError, "changed files outside its generated artifact"):
                    refresh_daemon.refresh(check_only=True)

            self.assertEqual(len(generator_calls), 1)
            self.assertEqual(outside.read_text(), "unexpected generator edit\n")


if __name__ == "__main__":
    unittest.main()
