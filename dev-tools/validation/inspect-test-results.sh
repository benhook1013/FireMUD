#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  cat <<'EOF' >&2
Usage:
  inspect-test-results.sh [--root <repo-root>] <service-name>
  inspect-test-results.sh --strict [--root <repo-root>] \
    --require-suite <fully-qualified-suite>... \
    --require-case <fully-qualified-suite#test-case>... <service-name>

Summarize parsed JUnit XML currently on disk under services/<service>/build/test-results.
This is diagnostic only; it helps explain quiet Gradle tails but does not prove
the original Gradle invocation completed cleanly.

Strict mode fails closed unless every named suite has exactly one non-empty,
green report and every named test case executed exactly once without a skip,
failure, or error.
EOF
  exit 1
}

STRICT_MODE=0
REQUIRED_SUITES=()
REQUIRED_CASES=()

while (($# > 0)); do
  case "$1" in
    --root)
      [[ $# -ge 2 ]] || usage
      ROOT_DIR="$2"
      shift 2
      ;;
    --strict)
      STRICT_MODE=1
      shift
      ;;
    --require-suite)
      [[ $# -ge 2 ]] || usage
      REQUIRED_SUITES+=("$2")
      shift 2
      ;;
    --require-case)
      [[ $# -ge 2 ]] || usage
      REQUIRED_CASES+=("$2")
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

if [[ "$STRICT_MODE" == "1" ]]; then
  ((${#REQUIRED_SUITES[@]} > 0 && ${#REQUIRED_CASES[@]} > 0)) || usage

  for ((i = 0; i < ${#REQUIRED_SUITES[@]}; i++)); do
    suite_name="${REQUIRED_SUITES[$i]}"
    [[ -n "$suite_name" && ! "$suite_name" =~ [[:cntrl:]] && "$suite_name" != *'#'* ]] || usage
    for ((j = 0; j < i; j++)); do
      [[ "$suite_name" != "${REQUIRED_SUITES[$j]}" ]] || usage
    done
  done

  for ((i = 0; i < ${#REQUIRED_CASES[@]}; i++)); do
    case_name="${REQUIRED_CASES[$i]}"
    [[ "$case_name" == *'#'* && "${case_name#*#}" != *'#'* && ! "$case_name" =~ [[:cntrl:]] ]] || usage
    case_suite="${case_name%%#*}"
    case_test="${case_name#*#}"
    [[ -n "$case_test" ]] || usage
    suite_is_required=0
    for required_suite in "${REQUIRED_SUITES[@]}"; do
      if [[ "$case_suite" == "$required_suite" ]]; then
        suite_is_required=1
        break
      fi
    done
    [[ "$suite_is_required" == "1" ]] || usage
    for ((j = 0; j < i; j++)); do
      [[ "$case_name" != "${REQUIRED_CASES[$j]}" ]] || usage
    done
  done
elif ((${#REQUIRED_SUITES[@]} > 0 || ${#REQUIRED_CASES[@]} > 0)); then
  usage
fi

SERVICE_NAME="$1"
SERVICE_DIR="$ROOT_DIR/services/$SERVICE_NAME"

if [[ ! -d "$SERVICE_DIR" ]]; then
  echo "Unknown service directory: $SERVICE_DIR" >&2
  exit 1
fi

python3 - "$SERVICE_DIR" "$STRICT_MODE" "${REQUIRED_SUITES[@]}" --required-cases "${REQUIRED_CASES[@]}" <<'PY'
from __future__ import annotations

import datetime as dt
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

service_dir = Path(sys.argv[1])
strict_mode = sys.argv[2] == "1"
case_separator = sys.argv.index("--required-cases", 3)
required_suites = sys.argv[3:case_separator]
required_cases = sys.argv[case_separator + 1 :]
service_name = service_dir.name
result_root = service_dir / "build" / "test-results"


def strict_inspection() -> None:
    xml_files = sorted(result_root.glob("**/TEST-*.xml")) if result_root.exists() else []
    malformed_reports = 0
    parsed_suite_count = 0
    parsed_test_count = 0
    suite_reports: dict[str, list[dict[str, object]]] = defaultdict(list)
    required_suite_set = set(required_suites)

    def non_negative_count(value: str | None) -> int | None:
        if value is None or not value.isascii() or not value.isdecimal():
            return None
        try:
            count = int(value)
        except ValueError:
            return None
        return count if count >= 0 else None

    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except (ET.ParseError, OSError, ValueError):
            malformed_reports += 1
            continue

        if root.tag == "testsuite":
            suites = [root]
        elif root.tag == "testsuites":
            suites = list(root.findall(".//testsuite"))
        else:
            malformed_reports += 1
            continue

        if not suites:
            malformed_reports += 1
            continue

        file_is_malformed = False
        file_suites: list[dict[str, object]] = []
        for suite in suites:
            suite_name = suite.attrib.get("name")
            tests = non_negative_count(suite.attrib.get("tests"))
            failures = non_negative_count(suite.attrib.get("failures"))
            errors = non_negative_count(suite.attrib.get("errors"))
            skipped = non_negative_count(suite.attrib.get("skipped"))
            disabled = non_negative_count(suite.attrib.get("disabled", "0"))
            testcases = list(suite.findall("./testcase"))

            if (
                not suite_name
                or tests is None
                or failures is None
                or errors is None
                or skipped is None
                or disabled is None
                or tests != len(testcases)
            ):
                file_is_malformed = True

            parsed_cases: list[dict[str, object]] = []
            for testcase in testcases:
                classname = testcase.attrib.get("classname")
                name = testcase.attrib.get("name")
                if not classname or not name:
                    file_is_malformed = True
                    continue
                parsed_cases.append(
                    {
                        "identity": f"{classname}#{name}",
                        "failed": bool(testcase.findall("./failure")),
                        "errored": bool(testcase.findall("./error")),
                        "skipped": bool(testcase.findall("./skipped")),
                    }
                )

            suite_info: dict[str, object] = {
                "name": suite_name,
                "tests": tests,
                "failures": failures,
                "errors": errors,
                "skipped": skipped,
                "disabled": disabled,
                "cases": parsed_cases,
            }
            file_suites.append(suite_info)

        if file_is_malformed:
            malformed_reports += 1
            continue

        parsed_suite_count += len(file_suites)
        parsed_test_count += sum(int(suite["tests"]) for suite in file_suites)
        for suite in file_suites:
            suite_name = str(suite["name"])
            if suite_name in required_suite_set:
                suite_reports[suite_name].append(suite)

    problems: list[str] = []
    suite_status: dict[str, tuple[str, dict[str, object] | None]] = {}
    for suite_name in required_suites:
        matches = suite_reports.get(suite_name, [])
        if not matches:
            suite_status[suite_name] = ("missing", None)
            problems.append(f"Missing required suite: {suite_name}")
            continue
        if len(matches) != 1:
            suite_status[suite_name] = ("duplicate", None)
            problems.append(f"Duplicate required suite: {suite_name} ({len(matches)} report(s))")
            continue

        suite = matches[0]
        suite_status[suite_name] = ("passed", suite)
        if int(suite["tests"]) == 0:
            suite_status[suite_name] = ("zero tests", suite)
            problems.append(f"Required suite has zero tests: {suite_name}")
        elif (
            int(suite["failures"]) > 0
            or int(suite["errors"]) > 0
            or int(suite["skipped"]) > 0
            or int(suite["disabled"]) > 0
            or any(
                bool(case["failed"]) or bool(case["errored"]) or bool(case["skipped"])
                for case in suite["cases"]
            )
        ):
            suite_status[suite_name] = ("failed or skipped", suite)
            problems.append(f"Required suite is failing, errored, or skipped: {suite_name}")

    for case_spec in required_cases:
        case_suite, _ = case_spec.split("#", 1)
        suite = suite_status[case_suite][1]
        matching_cases = (
            [case for case in suite["cases"] if case["identity"] == case_spec]
            if suite is not None
            else []
        )
        if not matching_cases:
            problems.append(f"Missing required case: {case_spec}")
            continue
        if len(matching_cases) != 1:
            problems.append(f"Duplicate required case: {case_spec} ({len(matching_cases)} execution(s))")
            continue
        case = matching_cases[0]
        if bool(case["failed"]) or bool(case["errored"]) or bool(case["skipped"]):
            problems.append(f"Required case did not pass: {case_spec}")

    print("Strict JUnit proof: passed" if not problems and malformed_reports == 0 else "Strict JUnit proof: failed")
    print(
        f"Reports: {len(xml_files)}; suites: {parsed_suite_count}; tests: {parsed_test_count}; "
        f"malformed reports: {malformed_reports}"
    )

    for suite_name in required_suites:
        status, suite = suite_status[suite_name]
        if suite is None:
            print(f"Suite {suite_name}: outcome={status}")
        else:
            print(
                f"Suite {suite_name}: tests={suite['tests']} skipped={int(suite['skipped']) + int(suite['disabled'])} "
                f"failures={suite['failures']} errors={suite['errors']} outcome={status}"
            )

    for case_spec in required_cases:
        case_suite, _ = case_spec.split("#", 1)
        suite = suite_status[case_suite][1]
        matching_cases = (
            [case for case in suite["cases"] if case["identity"] == case_spec]
            if suite is not None
            else []
        )
        if not matching_cases:
            outcome = "missing"
        elif len(matching_cases) != 1:
            outcome = "duplicate"
        elif bool(matching_cases[0]["failed"]):
            outcome = "failure"
        elif bool(matching_cases[0]["errored"]):
            outcome = "error"
        elif bool(matching_cases[0]["skipped"]):
            outcome = "skipped"
        else:
            outcome = "passed"
        print(f"Case {case_spec}: executions={len(matching_cases)} outcome={outcome}")

    if malformed_reports > 0:
        problems.append(f"Malformed report(s): {malformed_reports}")
    for problem in problems:
        print(f"- {problem}")

    if not xml_files:
        print("No JUnit XML reports were found.")
        raise SystemExit(1)
    if problems:
        raise SystemExit(1)


if strict_mode:
    strict_inspection()
    raise SystemExit(0)

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
