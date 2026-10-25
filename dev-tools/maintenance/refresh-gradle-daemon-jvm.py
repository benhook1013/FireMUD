#!/usr/bin/env python3
"""Regenerate Java 21 Adoptium daemon URLs with Gradle's native generator."""

import argparse
import json
import re
import subprocess
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import quote, unquote, urlencode, urlsplit

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = "gradle/gradle-daemon-jvm.properties"
LATEST_METADATA_URL = "https://api.adoptium.net/v3/assets/latest/21/hotspot"
FEATURE_RELEASES_URL = "https://api.adoptium.net/v3/assets/feature_releases/21/ga"
MAX_METADATA_BYTES = 8 * 1024 * 1024
MAX_FEATURE_RELEASES = 100
ADOPTIUM_RELEASE_PREFIX = "/adoptium/temurin21-binaries/releases/download/"
PLATFORMS = {
    (os, arch): f"{gradle_os}.{gradle_arch}"
    for os, gradle_os in [("linux", "LINUX"), ("mac", "MAC_OS"), ("windows", "WINDOWS")]
    for arch, gradle_arch in [("x64", "X86_64"), ("aarch64", "AARCH64")]
}


def select_downloads(metadata):
    """Require one official Java 21 release across the six supported platforms."""
    if not isinstance(metadata, list):
        raise TypeError("Adoptium metadata must be an array")
    urls = {}
    selected_release = None
    for entry in metadata:
        if not isinstance(entry, dict) or not isinstance(entry.get("binary"), dict):
            raise TypeError("Malformed Adoptium binary metadata")
        binary = entry["binary"]
        if binary.get("image_type") != "jdk":
            continue
        if not isinstance(binary.get("os"), str) or not isinstance(binary.get("architecture"), str):
            raise TypeError("Malformed Adoptium platform")
        platform = PLATFORMS.get((binary["os"], binary["architecture"]))
        if platform is None:
            continue
        version = entry.get("version")
        release = entry.get("release_name")
        package = binary.get("package")
        if (entry.get("vendor") != "eclipse" or binary.get("jvm_impl") != "hotspot"
                or binary.get("project") != "jdk" or not isinstance(version, dict)
                or type(version.get("major")) is not int or version["major"] != 21
                or not isinstance(release, str)
                or not re.fullmatch(r"jdk-21\.\d+\.\d+(?:\.\d+)?\+\d+", release)
                or not isinstance(package, dict) or not isinstance(package.get("link"), str)):
            raise ValueError("Expected official Java 21 Adoptium HotSpot JDK metadata")
        if (any(type(version.get(field)) is not int or version[field] < 0
                for field in ["minor", "security", "build"])
                or type(version.get("patch", 0)) is not int or version.get("patch", 0) < 0
                or not isinstance(version.get("semver"), str)
                or not isinstance(version.get("openjdk_version"), str)):
            raise ValueError("Malformed Adoptium version")
        release_version = f"21.{version['minor']}.{version['security']}"
        if version.get("patch", 0):
            release_version += f".{version['patch']}"
        release_version += f"+{version['build']}"
        if release != f"jdk-{release_version}" or version["openjdk_version"] not in (
                release_version, release_version + "-LTS"):
            raise ValueError("Adoptium release and version disagree")
        identity = (release, json.dumps(version, sort_keys=True))
        if selected_release is not None and identity != selected_release:
            raise ValueError("Adoptium platforms do not share one release/build")
        selected_release = identity
        url = package["link"]
        validate_download_url(url, binary["os"], binary["architecture"], release, release_version)
        if platform in urls:
            raise ValueError("Duplicate Adoptium platform")
        urls[platform] = url
    if set(urls) != set(PLATFORMS.values()):
        raise ValueError("Missing supported Adoptium platform")
    return urls


def validate_download_url(url, os_name, architecture, release, release_version):
    parsed = urlsplit(url)
    prefix = f"{ADOPTIUM_RELEASE_PREFIX}{quote(release, safe='')}/"
    expected_suffix = ".zip" if os_name == "windows" else ".tar.gz"
    filename = parsed.path.removeprefix(prefix)
    expected_filename = (
        f"OpenJDK21U-jdk_{architecture}_{os_name}_hotspot_"
        f"{release_version.replace('+', '_')}{expected_suffix}"
    )
    if (parsed.scheme != "https" or parsed.netloc != "github.com"
            or parsed.query or parsed.fragment or not parsed.path.startswith(prefix)
            or filename != expected_filename):
        raise ValueError("Expected immutable official Adoptium release URL")


def parse_criteria(text):
    properties = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in properties:
            raise ValueError("Malformed or duplicate daemon JVM property")
        properties[key] = value.replace(r"\:", ":").replace(r"\=", "=")
    return properties


def validate_criteria(text, expected_urls):
    properties = parse_criteria(text)
    expected = {"toolchainVersion": "21", "toolchainVendor": "ADOPTIUM"}
    expected.update({f"toolchainUrl.{platform}": url for platform, url in expected_urls.items()})
    if properties != expected:
        raise ValueError("Gradle daemon criteria differ from the selected official Java 21 artifacts")


def pinned_release_and_urls(text):
    """Derive one exact approved release from all six existing daemon criteria URLs."""
    properties = parse_criteria(text)
    expected_keys = {"toolchainVersion", "toolchainVendor"}
    expected_keys.update(f"toolchainUrl.{platform}" for platform in PLATFORMS.values())
    if set(properties) != expected_keys:
        raise ValueError("Pinned daemon criteria must contain exactly the Java 21 properties and six platform URLs")
    if properties["toolchainVersion"] != "21" or properties["toolchainVendor"] != "ADOPTIUM":
        raise ValueError("Pinned daemon criteria must select Java 21 from Adoptium")

    releases = set()
    urls = {}
    for (os_name, architecture), platform in PLATFORMS.items():
        url = properties[f"toolchainUrl.{platform}"]
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or parsed.netloc != "github.com"
                or parsed.query or parsed.fragment
                or not parsed.path.startswith(ADOPTIUM_RELEASE_PREFIX)):
            raise ValueError("Pinned daemon URL is outside the immutable official Adoptium releases")
        release_segment, separator, _ = parsed.path[len(ADOPTIUM_RELEASE_PREFIX):].partition("/")
        release = unquote(release_segment)
        if (not separator or quote(release, safe="") != release_segment
                or not re.fullmatch(r"jdk-21\.\d+\.\d+(?:\.\d+)?\+\d+", release)):
            raise ValueError("Malformed pinned Adoptium release URL")
        release_version = release.removeprefix("jdk-")
        validate_download_url(url, os_name, architecture, release, release_version)
        if url in urls.values():
            raise ValueError("Duplicate pinned Adoptium platform URL")
        releases.add(release)
        urls[platform] = url
    if len(releases) != 1:
        raise ValueError("Pinned Adoptium platforms do not share one release/build")
    return releases.pop(), urls


def select_release_downloads(metadata, release):
    """Validate official GA release records through the common strict metadata guard."""
    if not isinstance(metadata, list):
        raise TypeError("Adoptium release metadata must be an array")
    records = []
    for entry in metadata:
        if not isinstance(entry, dict):
            raise TypeError("Malformed Adoptium feature-release metadata")
        if entry.get("release_name") != release:
            continue
        if not isinstance(entry.get("binaries"), list):
            raise TypeError("Malformed Adoptium feature-release binaries")
        version = entry.get("version_data")
        for binary in entry["binaries"]:
            if not isinstance(binary, dict):
                raise TypeError("Malformed Adoptium feature-release binary")
            if (binary.get("os"), binary.get("architecture")) in PLATFORMS:
                records.append({
                    "vendor": entry.get("vendor"),
                    "release_name": entry.get("release_name"),
                    "version": version,
                    "binary": binary,
                })
    return select_downloads(records)


def fetch_latest_metadata():
    request = urllib.request.Request(
        LATEST_METADATA_URL,
        headers={"User-Agent": "FireMUD-dependency-maintenance/1.0", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return read_metadata(response)


def read_metadata(response):
    payload = response.read(MAX_METADATA_BYTES + 1)
    if len(payload) > MAX_METADATA_BYTES:
        raise ValueError("Adoptium metadata response exceeds the bounded size")
    return json.loads(payload)


def fetch_release_metadata(release):
    """Fetch one exact GA release for each supported platform with bounded responses."""
    metadata = []
    for os_name, architecture in PLATFORMS:
        params = {
            "architecture": architecture,
            "heap_size": "normal",
            "image_type": "jdk",
            "jvm_impl": "hotspot",
            "os": os_name,
            "page_size": "100",
            "vendor": "eclipse",
        }
        request = urllib.request.Request(
            f"{FEATURE_RELEASES_URL}?{urlencode(params)}",
            headers={"User-Agent": "FireMUD-dependency-maintenance/1.0", "Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            entries = read_metadata(response)
        if not isinstance(entries, list):
            raise TypeError("Adoptium feature-release metadata must be an array")
        if len(entries) > MAX_FEATURE_RELEASES:
            raise ValueError("Adoptium feature-release metadata exceeds the bounded page size")
        if any(not isinstance(entry, dict) for entry in entries):
            raise TypeError("Malformed Adoptium feature-release metadata")
        matches = [entry for entry in entries if entry.get("release_name") == release]
        if len(matches) != 1:
            raise ValueError(f"Expected one official Adoptium release for {os_name}/{architecture}")
        entry = matches[0]
        binaries = entry.get("binaries")
        if not isinstance(binaries, list) or any(not isinstance(binary, dict) for binary in binaries):
            raise TypeError("Malformed Adoptium feature-release binaries")
        platform_binaries = [
            binary for binary in binaries
            if binary.get("os") == os_name and binary.get("architecture") == architecture
        ]
        if len(platform_binaries) != 1:
            raise ValueError(f"Expected one Adoptium JDK binary for {os_name}/{architecture}")
        metadata.append({
            "vendor": entry.get("vendor"),
            "release_name": entry.get("release_name"),
            "version_data": entry.get("version_data"),
            "binaries": platform_binaries,
        })
    return metadata


def select_pinned_downloads(criteria_text, metadata):
    release, pinned_urls = pinned_release_and_urls(criteria_text)
    urls = select_release_downloads(metadata, release)
    validate_criteria(criteria_text, urls)
    if urls != pinned_urls:
        raise ValueError("Pinned daemon URLs differ from official Adoptium release metadata")
    return urls


def select_refresh_downloads(check_only=False):
    if not check_only:
        return select_downloads(fetch_latest_metadata())
    criteria_text = (ROOT / ARTIFACT).read_text()
    release, _ = pinned_release_and_urls(criteria_text)
    return select_pinned_downloads(criteria_text, fetch_release_metadata(release))


def outside_artifact_state():
    return (
        subprocess.check_output(["git", "diff", "HEAD", "--", ".", f":(exclude){ARTIFACT}"], cwd=ROOT),
        subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT),
    )


def refresh(check_only=False):
    if not check_only and subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT):
        raise RuntimeError("Publishing refresh requires a clean checkout")
    before = outside_artifact_state()
    urls = select_refresh_downloads(check_only)
    with tempfile.TemporaryDirectory(prefix="firemud-daemon-jdk-") as directory:
        metadata_file = Path(directory) / "download-urls.json"
        metadata_file.write_text(json.dumps(urls))
        subprocess.run(
            ["bash", str(ROOT / "dev-tools/validation/run-locked-gradle.sh"),
             "updateDaemonJvm", "--jvm-version=21", "--jvm-vendor=adoptium",
             f"-PdaemonJvmDownloadUrlsFile={metadata_file}"],
            cwd=ROOT,
            check=True,
        )
    validate_criteria((ROOT / ARTIFACT).read_text(), urls)
    if outside_artifact_state() != before:
        raise RuntimeError("Gradle refresh changed files outside its generated artifact")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true", help="Generate and validate while preserving existing edits; never publish")
    refresh(check_only=parser.parse_args().check_only)
