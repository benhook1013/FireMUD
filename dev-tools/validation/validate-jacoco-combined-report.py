#!/usr/bin/env python3
"""Verify that a JaCoCo XML upload retains required covered production classes."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


class CoverageError(ValueError):
    """Raised when combined coverage evidence is missing or malformed."""


def covered_lines_by_class(report: Path) -> dict[str, int]:
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError) as exc:
        raise CoverageError(f"could not read JaCoCo XML report {report}: {exc}") from exc
    if root.tag != "report":
        raise CoverageError("JaCoCo XML root must be report")

    covered: dict[str, int] = {}
    for package in root.findall("package"):
        package_name = package.get("name")
        if not package_name:
            raise CoverageError("JaCoCo package is missing its name")
        for class_node in package.findall("class"):
            class_name = class_node.get("name")
            if not class_name or not class_name.startswith(f"{package_name}/"):
                raise CoverageError("JaCoCo class has an invalid package-qualified name")
            line_counters = [
                counter
                for counter in class_node.findall("counter")
                if counter.get("type") == "LINE"
            ]
            if class_name in covered:
                raise CoverageError(f"JaCoCo class {class_name} appears more than once")
            if not line_counters:
                covered[class_name] = 0
                continue
            if len(line_counters) != 1:
                raise CoverageError(f"JaCoCo class {class_name} has duplicate LINE counters")
            value = line_counters[0].get("covered")
            if value is None or not value.isdigit():
                raise CoverageError(f"JaCoCo class {class_name} has invalid covered LINE count")
            covered[class_name] = int(value)
    return covered


def covered_lines_by_method(report: Path) -> dict[str, int]:
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError) as exc:
        raise CoverageError(f"could not read JaCoCo XML report {report}: {exc}") from exc
    covered: dict[str, int] = {}
    for class_node in root.findall("./package/class"):
        class_name = class_node.get("name")
        if not class_name:
            raise CoverageError("JaCoCo class is missing its name")
        for method in class_node.findall("method"):
            method_name = method.get("name")
            if not method_name:
                raise CoverageError(f"JaCoCo class {class_name} has a method without a name")
            key = f"{class_name}#{method_name}"
            line_counters = [
                counter for counter in method.findall("counter") if counter.get("type") == "LINE"
            ]
            if len(line_counters) != 1:
                raise CoverageError(f"JaCoCo method {key} must have one LINE counter")
            value = line_counters[0].get("covered")
            if value is None or not value.isdigit():
                raise CoverageError(f"JaCoCo method {key} has invalid covered LINE count")
            covered[key] = covered.get(key, 0) + int(value)
    return covered


def verify_required_coverage(
    report: Path, required_classes: list[str], required_methods: list[str]
) -> None:
    covered = covered_lines_by_class(report)
    for class_name in required_classes:
        if covered.get(class_name, 0) <= 0:
            raise CoverageError(
                f"combined JaCoCo report has no covered lines for required class {class_name}"
            )
    covered_methods = covered_lines_by_method(report)
    for method_name in required_methods:
        if covered_methods.get(method_name, 0) <= 0:
            raise CoverageError(
                f"combined JaCoCo report has no covered lines for required method {method_name}"
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify representative unit and integration coverage in combined JaCoCo XML."
    )
    parser.add_argument("--report", required=True, type=Path, help="Combined JaCoCo XML report")
    parser.add_argument(
        "--require-covered-class",
        action="append",
        dest="required_classes",
        required=True,
        help="Package-qualified JaCoCo class name that must have covered lines",
    )
    parser.add_argument(
        "--require-covered-method",
        action="append",
        dest="required_methods",
        default=[],
        help="Package-qualified JaCoCo CLASS#METHOD that must have covered lines",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        verify_required_coverage(args.report, args.required_classes, args.required_methods)
    except CoverageError as exc:
        print(f"error={exc}", file=sys.stderr)
        return 1
    print(f"report={args.report}")
    for class_name in args.required_classes:
        print(f"covered_class={class_name}")
    for method_name in args.required_methods:
        print(f"covered_method={method_name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
