#!/usr/bin/env python3
"""Validate firemud.auth.grpc.public-methods entries against repo proto services."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PROTO_ROOT = ROOT / "protos"

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;")
SERVICE_RE = re.compile(r"^\s*service\s+([A-Za-z0-9_]+)\s*\{")
RPC_RE = re.compile(r"^\s*rpc\s+([A-Za-z0-9_]+)\s*\(")
PUBLIC_METHODS_RE = re.compile(r"^(\s*)public-methods:\s*(?:#.*)?$")
LIST_ITEM_RE = re.compile(r"^\s*-\s+(.+?)\s*(?:#.*)?$")


def tracked_application_yamls() -> list[Path]:
    result = subprocess.run(
        [
            "git",
            "ls-files",
            "services/*/src/main/resources/application*.yml",
            "services/*/src/main/resources/application*.yaml",
        ],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line.strip()]


def proto_methods() -> set[str]:
    methods: set[str] = set()
    for proto_file in sorted(PROTO_ROOT.rglob("*.proto")):
        package: str | None = None
        service: str | None = None
        for line in proto_file.read_text(encoding="utf-8").splitlines():
            if match := PACKAGE_RE.match(line):
                package = match.group(1)
                continue
            if match := SERVICE_RE.match(line):
                service = match.group(1)
                continue
            if service and line.lstrip().startswith("}"):
                service = None
                continue
            if package and service and (match := RPC_RE.match(line)):
                methods.add(f"{package}.{service}/{match.group(1)}")
    return methods


def configured_public_methods(path: Path) -> list[tuple[int, str]]:
    entries: list[tuple[int, str]] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    index = 0
    while index < len(lines):
        line = lines[index]
        public_methods_match = PUBLIC_METHODS_RE.match(line)
        if not public_methods_match:
            index += 1
            continue

        base_indent = len(public_methods_match.group(1))
        index += 1
        while index < len(lines):
            candidate = lines[index]
            if not candidate.strip() or candidate.lstrip().startswith("#"):
                index += 1
                continue
            candidate_indent = len(candidate) - len(candidate.lstrip(" "))
            if candidate_indent <= base_indent:
                break
            if match := LIST_ITEM_RE.match(candidate):
                value = match.group(1).strip().strip("'\"")
                entries.append((index + 1, value))
            index += 1
    return entries


def main() -> int:
    known_methods = proto_methods()
    failures: list[str] = []

    for yaml_file in tracked_application_yamls():
        for line_number, method in configured_public_methods(yaml_file):
            if method not in known_methods:
                failures.append(
                    f"{yaml_file.relative_to(ROOT)}:{line_number}: "
                    f"unknown gRPC public method '{method}'"
                )

    if failures:
        print("Invalid firemud.auth.grpc.public-methods entries:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        print(
            "Public methods must match proto full method names: "
            "<package>.<Service>/<Rpc>.",
            file=sys.stderr,
        )
        return 1

    print("gRPC public-method allowlists match proto declarations.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
