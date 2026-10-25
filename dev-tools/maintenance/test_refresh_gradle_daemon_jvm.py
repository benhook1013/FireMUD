import copy
import hashlib
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit

spec = importlib.util.spec_from_file_location(
    "refresh_daemon", Path(__file__).with_name("refresh-gradle-daemon-jvm.py")
)
refresh_daemon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh_daemon)


def metadata():
    records = []
    for os_name, arch in refresh_daemon.PLATFORMS:
        suffix = ".zip" if os_name == "windows" else ".tar.gz"
        records.append({
            "vendor": "eclipse", "release_name": "jdk-21.0.12.1+1",
            "version": {"major": 21, "minor": 0, "security": 12, "patch": 1, "build": 1,
                        "semver": "21.0.12+101.0.LTS", "openjdk_version": "21.0.12.1+1-LTS"},
            "binary": {"os": os_name, "architecture": arch, "image_type": "jdk",
                       "jvm_impl": "hotspot", "project": "jdk", "package": {
                           "link": "https://github.com/adoptium/temurin21-binaries/releases/download/"
                           f"jdk-21.0.12.1%2B1/OpenJDK21U-jdk_{arch}_{os_name}_hotspot_21.0.12.1_1{suffix}"}},
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


class JsonResponse:
    def __init__(self, value):
        self.payload = json.dumps(value).encode()

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, limit=-1):
        return self.payload if limit < 0 else self.payload[:limit]


class OversizedResponse(JsonResponse):
    def read(self, limit=-1):
        return b"x" * limit


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


def generator_download_urls(command):
    handoffs = [argument for argument in command
                if isinstance(argument, str) and argument.startswith("-PdaemonJvmDownloadUrlsFile=")]
    if len(handoffs) != 1:
        raise AssertionError("generator must receive exactly one daemon URL metadata file")
    metadata_path = Path(handoffs[0].split("=", 1)[1])
    if not metadata_path.is_file():
        raise AssertionError("generator daemon URL metadata file is missing")
    values = json.loads(metadata_path.read_text())
    if not isinstance(values, dict) or set(values) != set(refresh_daemon.PLATFORMS.values()):
        raise AssertionError("generator daemon URL metadata does not contain exactly six platforms")
    return values


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

        with (
            patch.object(refresh_daemon, "fetch_latest_metadata",
                         return_value=staggered_latest_metadata()),
            self.assertRaisesRegex(ValueError, "do not share one release/build"),
        ):
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

    def test_http_fetch_uses_exact_platform_filters_and_assets(self):
        rows = feature_metadata(metadata())
        responses = [JsonResponse([copy.deepcopy(row)]) for row in rows]
        requests = []

        def open_request(request, timeout):
            requests.append(request)
            self.assertEqual(timeout, 30)
            return responses[len(requests) - 1]

        with patch.object(refresh_daemon.urllib.request, "urlopen", side_effect=open_request):
            fetched = refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")

        self.assertEqual(
            refresh_daemon.select_release_downloads(fetched, "jdk-21.0.12.1+1"),
            refresh_daemon.select_downloads(metadata()),
        )
        self.assertEqual(len(requests), 6)
        observed_platforms = set()
        for request, (os_name, architecture) in zip(requests, refresh_daemon.PLATFORMS):
            parsed = urlsplit(request.full_url)
            self.assertEqual(parsed.scheme, "https")
            self.assertEqual(parsed.netloc, "api.adoptium.net")
            self.assertEqual(parsed.path, "/v3/assets/feature_releases/21/ga")
            query = parse_qs(parsed.query)
            self.assertEqual(query, {
                "architecture": [architecture], "heap_size": ["normal"],
                "image_type": ["jdk"], "jvm_impl": ["hotspot"], "os": [os_name],
                "page": ["0"], "page_size": ["20"], "sort_method": ["DEFAULT"],
                "sort_order": ["DESC"], "vendor": ["eclipse"],
            })
            self.assertEqual(request.get_header("User-agent"), "FireMUD-dependency-maintenance/1.0")
            observed_platforms.add((query["os"][0], query["architecture"][0]))
        self.assertEqual(observed_platforms, set(refresh_daemon.PLATFORMS))

    def test_http_fetch_finds_exact_pinned_release_on_second_page(self):
        filler_page = [{"release_name": f"unrelated-{index}", "binaries": []}
                       for index in range(refresh_daemon.MAX_FEATURE_RELEASES)]
        rows = feature_metadata(metadata())
        responses = []
        for row in rows:
            responses.extend([JsonResponse(filler_page), JsonResponse([copy.deepcopy(row)])])
        requests = []

        def open_request(request, timeout):
            requests.append(request)
            self.assertEqual(timeout, 30)
            return responses[len(requests) - 1]

        with patch.object(refresh_daemon.urllib.request, "urlopen", side_effect=open_request):
            fetched = refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")

        self.assertEqual(
            refresh_daemon.select_release_downloads(fetched, "jdk-21.0.12.1+1"),
            refresh_daemon.select_downloads(metadata()),
        )
        self.assertEqual(len(requests), 12)
        for offset in range(0, len(requests), 2):
            self.assertEqual(parse_qs(urlsplit(requests[offset].full_url).query)["page"], ["0"])
            self.assertEqual(parse_qs(urlsplit(requests[offset + 1].full_url).query)["page"], ["1"])

    def test_http_fetch_fails_closed_for_missing_duplicate_malformed_and_oversized_pages(self):
        with (
            patch.object(refresh_daemon.urllib.request, "urlopen", side_effect=[
                JsonResponse([]) for _ in refresh_daemon.PLATFORMS
            ]),
            self.assertRaisesRegex(ValueError, "within 10 pages"),
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")

        full_page = [{"release_name": f"unrelated-{index}", "binaries": []}
                     for index in range(refresh_daemon.MAX_FEATURE_RELEASES)]
        with (
            patch.object(refresh_daemon.urllib.request, "urlopen",
                         side_effect=[JsonResponse(full_page)
                                      for _ in range(refresh_daemon.MAX_FEATURE_RELEASE_PAGES)]) as urlopen,
            self.assertRaisesRegex(ValueError, "within 10 pages"),
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")
        self.assertEqual(urlopen.call_count, refresh_daemon.MAX_FEATURE_RELEASE_PAGES)

        not_found = HTTPError("https://api.adoptium.net/page/1", 404, "Not Found", None, None)
        with (
            patch.object(refresh_daemon.urllib.request, "urlopen",
                         side_effect=[JsonResponse(full_page), not_found]) as urlopen,
            self.assertRaisesRegex(ValueError, "within 10 pages"),
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")
        self.assertEqual(urlopen.call_count, 2)

        server_error = HTTPError("https://api.adoptium.net/page/0", 503, "Unavailable", None, None)
        with (
            patch.object(refresh_daemon.urllib.request, "urlopen", side_effect=server_error),
            self.assertRaises(HTTPError) as raised,
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")
        self.assertEqual(raised.exception.code, 503)

        duplicate = feature_metadata(metadata())[0]
        with (
            patch.object(refresh_daemon.urllib.request, "urlopen",
                         return_value=JsonResponse([duplicate, copy.deepcopy(duplicate)])),
            self.assertRaisesRegex(ValueError, "Duplicate Adoptium release metadata"),
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")

        with (
            patch.object(refresh_daemon.urllib.request, "urlopen", return_value=JsonResponse([None])),
            self.assertRaisesRegex(TypeError, "Malformed Adoptium feature-release metadata"),
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")

        with (
            patch.object(refresh_daemon.urllib.request, "urlopen", return_value=OversizedResponse([])),
            self.assertRaisesRegex(ValueError, "exceeds the bounded size"),
        ):
            refresh_daemon.fetch_release_metadata("jdk-21.0.12.1+1")

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
                    self.assertEqual(generator_download_urls(command), refresh_daemon.select_downloads(metadata()))
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
            self.assertEqual(command[2:5], ["updateDaemonJvm", "--jvm-version=21", "--jvm-vendor=adoptium"])

    def test_generator_inherits_only_the_existing_resource_session_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository_with_outside_edit(root)
            original_run = subprocess.run
            generator_calls = []

            def run_or_generate(*args, **kwargs):
                command = args[0] if args else kwargs["args"]
                if is_gradle_generator(command):
                    generator_calls.append((command, dict(kwargs)))
                    self.assertEqual(generator_download_urls(command), refresh_daemon.select_downloads(metadata()))
                    return subprocess.CompletedProcess(command, 0)
                return original_run(*args, **kwargs)

            environment = {name: value for name, value in os.environ.items()
                           if name not in refresh_daemon.RESOURCE_SESSION_ENV}
            environment[refresh_daemon.RESOURCE_SESSION_ENV[0]] = "53"
            with patch.dict(os.environ, environment, clear=True), \
                    patch.object(refresh_daemon, "ROOT", root), \
                    patch.object(refresh_daemon, "fetch_release_metadata",
                                 return_value=feature_metadata(metadata())), \
                    patch.object(refresh_daemon, "fetch_latest_metadata",
                                 side_effect=AssertionError("check-only must not query latest")), \
                    patch.object(refresh_daemon.subprocess, "run", side_effect=run_or_generate):
                refresh_daemon.refresh(check_only=True)

            self.assertEqual(len(generator_calls), 1)
            _, options = generator_calls[0]
            self.assertEqual(options["pass_fds"], (53,))
            self.assertEqual(set(options), {"cwd", "check", "pass_fds"})

    def test_outside_artifact_state_tracks_untracked_contents_modes_and_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository_with_outside_edit(root)
            untracked = root / "untracked-output.txt"
            untracked.write_text("first content\n")
            link = root / "untracked-link"
            link.symlink_to("missing-target-one")
            with patch.object(refresh_daemon, "ROOT", root):
                initial = refresh_daemon.outside_artifact_state()
                untracked.write_text("second content\n")
                untracked.chmod(0o644)
                changed_contents = refresh_daemon.outside_artifact_state()
                untracked.chmod(0o600)
                changed_mode = refresh_daemon.outside_artifact_state()
                link.unlink()
                link.symlink_to("missing-target-two")
                changed_link = refresh_daemon.outside_artifact_state()

            self.assertNotEqual(initial, changed_contents)
            self.assertNotEqual(changed_contents, changed_mode)
            self.assertNotEqual(changed_mode, changed_link)

    def test_outside_artifact_state_refuses_untracked_special_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository_with_outside_edit(root)
            os.mkfifo(root / "untracked-fifo")
            original_check_output = subprocess.check_output
            diff_calls = []

            def enumerate_fifo_for_guard(command, *args, **kwargs):
                if command == ["git", "ls-files", "--others", "--exclude-standard", "-z"]:
                    return b"untracked-fifo\0"
                if command[:3] == ["git", "diff", "HEAD"]:
                    diff_calls.append(command)
                return original_check_output(command, *args, **kwargs)

            with (
                patch.object(refresh_daemon, "ROOT", root),
                patch.object(refresh_daemon.subprocess, "check_output",
                             side_effect=enumerate_fifo_for_guard),
                self.assertRaisesRegex(RuntimeError, "Unsupported untracked special file"),
            ):
                refresh_daemon.outside_artifact_state()
            self.assertEqual(len(diff_calls), 1)

    def test_check_only_rejects_generator_created_untracked_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository_with_outside_edit(root)
            generated_outside = root / "unexpected-generator-output.txt"
            original_run = subprocess.run
            generator_calls = []

            def run_or_generate(*args, **kwargs):
                command = args[0] if args else kwargs["args"]
                if is_gradle_generator(command):
                    generator_calls.append(command)
                    self.assertEqual(generator_download_urls(command), refresh_daemon.select_downloads(metadata()))
                    generated_outside.write_text("outside the generated artifact\n")
                    return subprocess.CompletedProcess(command, 0)
                return original_run(*args, **kwargs)

            with (
                patch.object(refresh_daemon, "ROOT", root),
                patch.object(refresh_daemon, "fetch_release_metadata",
                             return_value=feature_metadata(metadata())),
                patch.object(refresh_daemon, "fetch_latest_metadata",
                             side_effect=AssertionError("check-only must not query latest")),
                patch.object(refresh_daemon.subprocess, "run", side_effect=run_or_generate),
                self.assertRaisesRegex(RuntimeError, "changed files outside its generated artifact"),
            ):
                refresh_daemon.refresh(check_only=True)

            self.assertEqual(len(generator_calls), 1)
            self.assertEqual(generated_outside.read_text(), "outside the generated artifact\n")

    def test_cli_places_pin_selection_inside_resource_session(self):
        events = []
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository_with_outside_edit(root)
            original_run = subprocess.run
            original_select = refresh_daemon.select_pinned_downloads

            def select_after_admission(criteria, rows):
                self.assertIn("resource-session", events)
                events.append("pinned-release-selection")
                return original_select(criteria, rows)

            def run_or_enter_session(command, **kwargs):
                if (len(command) >= 6 and command[0] == "bash"
                        and command[1].endswith("dev-tools/validation/run-locked-gradle.sh")
                        and command[2] == "--resource-session"):
                    self.assertEqual(command[3], refresh_daemon.sys.executable)
                    self.assertIn("run_in_resource_session", command[5])
                    events.append("resource-session")
                    refresh_daemon.run_in_resource_session(check_only=True)
                    return subprocess.CompletedProcess(command, 0)
                if is_gradle_generator(command):
                    self.assertIn("resource-session", events)
                    self.assertEqual(generator_download_urls(command), refresh_daemon.select_downloads(metadata()))
                    events.append("native-generator")
                    return subprocess.CompletedProcess(command, 0)
                return original_run(command, **kwargs)

            environment = {name: value for name, value in os.environ.items()
                           if name not in refresh_daemon.RESOURCE_SESSION_ENV}
            environment["CI"] = "true"
            with patch.dict(os.environ, environment, clear=True), \
                    patch.object(refresh_daemon, "ROOT", root), \
                    patch.object(refresh_daemon, "fetch_release_metadata",
                                 return_value=feature_metadata(metadata())), \
                    patch.object(refresh_daemon, "fetch_latest_metadata",
                                 side_effect=AssertionError("check-only must not query latest")), \
                    patch.object(refresh_daemon, "select_pinned_downloads",
                                 side_effect=select_after_admission), \
                    patch.object(refresh_daemon.subprocess, "run", side_effect=run_or_enter_session):
                self.assertEqual(refresh_daemon.main(["--check-only"]), 0)

        self.assertEqual(events, ["resource-session", "pinned-release-selection", "native-generator"])

    def test_cli_verifies_and_reuses_an_inherited_resource_session(self):
        events = []
        environment = dict(os.environ)
        environment[refresh_daemon.RESOURCE_SESSION_ENV[0]] = "inherited-descriptor"
        with patch.dict(os.environ, environment, clear=True), \
                patch.object(refresh_daemon, "verify_resource_session",
                             side_effect=lambda: events.append("verified")), \
                patch.object(refresh_daemon, "refresh",
                             side_effect=lambda check_only: events.append(f"refresh:{check_only}")), \
                patch.object(refresh_daemon.subprocess, "run") as run:
            self.assertEqual(refresh_daemon.main(["--check-only"]), 0)
        run.assert_not_called()
        self.assertEqual(events, ["verified", "refresh:True"])

    def test_resource_session_verifier_inherits_the_open_lock_descriptor(self):
        environment = dict(os.environ)
        environment[refresh_daemon.RESOURCE_SESSION_ENV[0]] = "53"
        with patch.dict(os.environ, environment, clear=True), \
                patch.object(refresh_daemon.subprocess, "run") as run:
            refresh_daemon.verify_resource_session()
        self.assertEqual(run.call_args.kwargs["pass_fds"], (53,))

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

            with (
                patch.object(refresh_daemon, "ROOT", root),
                patch.object(refresh_daemon, "fetch_release_metadata",
                             return_value=feature_metadata(metadata())),
                patch.object(refresh_daemon, "fetch_latest_metadata",
                             side_effect=AssertionError("check-only must not query latest")),
                patch.object(refresh_daemon.subprocess, "run", side_effect=run_or_generate),
                self.assertRaisesRegex(RuntimeError, "changed files outside its generated artifact"),
            ):
                refresh_daemon.refresh(check_only=True)

            self.assertEqual(len(generator_calls), 1)
            self.assertEqual(outside.read_text(), "unexpected generator edit\n")


if __name__ == "__main__":
    unittest.main()
