#!/usr/bin/env python3
"""Regenerate Java 21 daemon download artifacts with Gradle's own resolver."""

import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = "gradle/gradle-daemon-jvm.properties"


def validate_criteria(text):
    properties = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in properties:
            raise ValueError("Malformed or duplicate daemon JVM property")
        properties[key] = value
    if properties.get("toolchainVersion") != "21":
        raise ValueError("Daemon maintenance must preserve Java 21")
    if properties.get("toolchainVendor") != "ADOPTIUM":
        raise ValueError("Daemon maintenance must preserve Adoptium")
    urls = {key: value for key, value in properties.items() if key.startswith("toolchainUrl.")}
    if not urls or any(not value.startswith("https\\://") for value in urls.values()):
        raise ValueError("Gradle must generate HTTPS platform download artifacts")
    if set(properties) != {"toolchainVersion", "toolchainVendor", *urls}:
        raise ValueError("Unexpected daemon JVM criteria")


def refresh():
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT):
        raise RuntimeError("Daemon refresh requires a clean checkout")
    subprocess.run(
        ["bash", str(ROOT / "dev-tools/validation/run-locked-gradle.sh"),
         "updateDaemonJvm", "--jvm-version=21", "--jvm-vendor=adoptium"],
        cwd=ROOT,
        check=True,
    )
    validate_criteria((ROOT / ARTIFACT).read_text())
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "HEAD"], cwd=ROOT, text=True
    ).splitlines()
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True
    ).splitlines()
    if set(changed) - {ARTIFACT} or untracked:
        raise RuntimeError("Gradle refresh changed files outside its generated artifact")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true", help="Generate and validate locally; this helper never publishes")
    parser.parse_args()
    refresh()
