#!/usr/bin/env python3
"""Regenerate Java 21 Adoptium daemon URLs with Gradle's native generator."""

import argparse
import json
import re
import subprocess
import tempfile
import urllib.request
from pathlib import Path
from urllib.parse import quote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = "gradle/gradle-daemon-jvm.properties"
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
        parsed = urlsplit(url)
        prefix = f"/adoptium/temurin21-binaries/releases/download/{quote(release, safe='')}/"
        expected_suffix = ".zip" if binary["os"] == "windows" else ".tar.gz"
        filename = parsed.path.removeprefix(prefix)
        if (parsed.scheme != "https" or parsed.netloc != "github.com"
                or parsed.query or parsed.fragment or not parsed.path.startswith(prefix)
                or filename != (f"OpenJDK21U-jdk_{binary['architecture']}_{binary['os']}_hotspot_"
                                + release_version.replace("+", "_") + expected_suffix)):
            raise ValueError("Expected immutable official Adoptium release URL")
        if platform in urls:
            raise ValueError("Duplicate Adoptium platform")
        urls[platform] = url
    if set(urls) != set(PLATFORMS.values()):
        raise ValueError("Missing supported Adoptium platform")
    return urls


def validate_criteria(text, expected_urls):
    properties = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in properties:
            raise ValueError("Malformed or duplicate daemon JVM property")
        properties[key] = value.replace(r"\:", ":").replace(r"\=", "=")
    expected = {"toolchainVersion": "21", "toolchainVendor": "ADOPTIUM"}
    expected.update({f"toolchainUrl.{platform}": url for platform, url in expected_urls.items()})
    if properties != expected:
        raise ValueError("Gradle daemon criteria differ from the selected official Java 21 artifacts")


def outside_artifact_state():
    return (
        subprocess.check_output(["git", "diff", "HEAD", "--", ".", f":(exclude){ARTIFACT}"], cwd=ROOT),
        subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT),
    )


def refresh(check_only=False):
    if not check_only and subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT):
        raise RuntimeError("Publishing refresh requires a clean checkout")
    before = outside_artifact_state()
    request = urllib.request.Request(
        "https://api.adoptium.net/v3/assets/latest/21/hotspot",
        headers={"User-Agent": "FireMUD-dependency-maintenance/1.0", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        urls = select_downloads(json.load(response))
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
