#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'EOF' >&2
Usage: inspect-test-results.sh [--root <repo-root>] <service-name>

Summarize parsed JUnit XML currently on disk under services/<service>/build/test-results.
This is diagnostic only; it helps explain quiet Gradle tails but does not prove
the original Gradle invocation completed cleanly.
EOF
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --root)
      [[ $# -ge 2 ]] || usage
      ROOT_DIR="$2"
      shift 2
      ;;
    --help|-h)
      usage
      ;;
    *)
      break
      ;;
  esac
done

[[ $# -eq 1 ]] || usage

SERVICE_NAME="$1"
SERVICE_DIR="$ROOT_DIR/services/$SERVICE_NAME"

if [[ ! -d "$SERVICE_DIR" ]]; then
  echo "Unknown service directory: $SERVICE_DIR" >&2
  exit 1
fi

python3 - "$SERVICE_DIR" <<'PY'
from __future__ import annotations

import datetime as dt
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

service_dir = Path(sys.argv[1])
service_name = service_dir.name
result_root = service_dir / "build" / "test-results"

if not result_root.exists():
    print(f"Service: {service_name}")
    print("No build/test-results directory exists yet.")
    print("Diagnostic only: no parsed XML is available to inspect.")
    raise SystemExit(0)

xml_files = sorted(result_root.glob("**/TEST-*.xml"))
if not xml_files:
    print(f"Service: {service_name}")
    print("No JUnit XML files were found under build/test-results.")
    print("Diagnostic only: no parsed XML is available to inspect.")
    raise SystemExit(0)

per_bucket: dict[str, dict[str, object]] = {}
overall = {
    "files": 0,
    "tests": 0,
    "failures": 0,
    "errors": 0,
    "skipped": 0,
}
failing_files: list[tuple[str, int, int]] = []
latest_file: Path | None = None
latest_mtime = -1.0

def empty_bucket() -> dict[str, object]:
    return {
        "files": 0,
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "latest_mtime": -1.0,
        "latest_path": None,
    }

def parse_int(value: str | None) -> int:
    if value in (None, ""):
        return 0
    return int(float(value))

for xml_file in xml_files:
    try:
        root = ET.parse(xml_file).getroot()
    except ET.ParseError as exc:
        print(f"Service: {service_name}")
        print(f"Failed to parse {xml_file}: {exc}")
        raise SystemExit(1)

    rel_parts = xml_file.relative_to(result_root).parts
    bucket_name = rel_parts[0] if rel_parts else "unknown"
    bucket = per_bucket.setdefault(bucket_name, empty_bucket())

    if root.tag == "testsuite":
        suites = [root]
    else:
        suites = list(root.findall("testsuite"))

    file_tests = file_failures = file_errors = file_skipped = 0
    for suite in suites:
        file_tests += parse_int(suite.attrib.get("tests"))
        file_failures += parse_int(suite.attrib.get("failures"))
        file_errors += parse_int(suite.attrib.get("errors"))
        file_skipped += parse_int(suite.attrib.get("skipped")) + parse_int(suite.attrib.get("disabled"))

    mtime = xml_file.stat().st_mtime
    if mtime > latest_mtime:
        latest_mtime = mtime
        latest_file = xml_file

    bucket["files"] = int(bucket["files"]) + 1
    bucket["tests"] = int(bucket["tests"]) + file_tests
    bucket["failures"] = int(bucket["failures"]) + file_failures
    bucket["errors"] = int(bucket["errors"]) + file_errors
    bucket["skipped"] = int(bucket["skipped"]) + file_skipped
    if mtime > float(bucket["latest_mtime"]):
        bucket["latest_mtime"] = mtime
        bucket["latest_path"] = xml_file

    overall["files"] += 1
    overall["tests"] += file_tests
    overall["failures"] += file_failures
    overall["errors"] += file_errors
    overall["skipped"] += file_skipped

    if file_failures or file_errors:
        failing_files.append((str(xml_file.relative_to(service_dir)), file_failures, file_errors))

def fmt_time(timestamp: float) -> str:
    return dt.datetime.fromtimestamp(timestamp, tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

print(f"Service: {service_name}")
print(f"Result root: {result_root}")
print()
print("Per-suite summary from XML currently on disk:")
for bucket_name in sorted(per_bucket):
    bucket = per_bucket[bucket_name]
    latest_path = Path(str(bucket["latest_path"])) if bucket["latest_path"] is not None else None
    latest_suffix = latest_path.relative_to(service_dir) if latest_path is not None else "n/a"
    print(
        f"- {bucket_name}: "
        f"{bucket['files']} file(s), "
        f"{bucket['tests']} test(s), "
        f"{bucket['failures']} failure(s), "
        f"{bucket['errors']} error(s), "
        f"{bucket['skipped']} skipped, "
        f"latest {fmt_time(float(bucket['latest_mtime']))} "
        f"({latest_suffix})"
    )

print()
print(
    "Overall: "
    f"{overall['files']} file(s), "
    f"{overall['tests']} test(s), "
    f"{overall['failures']} failure(s), "
    f"{overall['errors']} error(s), "
    f"{overall['skipped']} skipped"
)
if latest_file is not None:
    print(f"Most recent XML on disk: {latest_file.relative_to(service_dir)} at {fmt_time(latest_mtime)}")

if failing_files:
    print()
    print("Failing XML files:")
    for rel_path, failures, errors in sorted(failing_files)[:10]:
        print(f"- {rel_path}: {failures} failure(s), {errors} error(s)")
else:
    print()
    print("All parsed XML files are green.")

print()
print(
    "Diagnostic only: fresh green XML suggests the meaningful suites may have finished, "
    "but this does not prove the original Gradle process completed cleanly."
)
PY
