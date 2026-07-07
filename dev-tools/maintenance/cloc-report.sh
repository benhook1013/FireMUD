#!/usr/bin/env bash
set -euo pipefail

# TEMP PLAN
# Track the next cloc-report improvements here and remove completed items as
# they land. This header is temporary and should be cleaned up after the next
# round of work.
#
# Active findings / planned fixes:
# - Keep FireMUD on prod/tests for now rather than adding a third verification
#   bucket; revisit only if the broader tests bucket proves too lossy.
# - Consider whether a service-docs mode is still useful after design and
#   architecture modes have had real use.
# - Add diff-scoped reporting later using git diff-backed inventories and the
#   same classifier rather than introducing a separate counting path.
# - Add short summary/paste-friendly output only after the underlying inventory
#   and bucketing are trustworthy.

usage() {
  cat <<'EOF'
Usage: bash dev-tools/maintenance/cloc-report.sh [repo|source|prod|tests|debug|by-module|markdown|design|architecture] [extra cloc args...]

Modes:
  repo      Broad repository footprint across source, docs, config, CI, and scripts.
  source    Source-focused count across build logic, scripts, protos, services, and web client code, including tests.
  prod      Source-focused count excluding files currently classified as tests.
  tests     Test-only count across standard test directories, test fixtures, repo-owned contract tests, and validation-style test scripts.
  debug     Print tracked source-scope file classification as: bucket TAB rule TAB path.
  by-module Aggregate tracked source/prod/tests counts by FireMUD module bucket.
  markdown  Markdown-only count across the repository.
  design    Tracked files under design/.
  architecture Tracked files under design/architecture/.

Examples:
  bash dev-tools/maintenance/cloc-report.sh
  bash dev-tools/maintenance/cloc-report.sh source
  bash dev-tools/maintenance/cloc-report.sh prod
  bash dev-tools/maintenance/cloc-report.sh tests
  bash dev-tools/maintenance/cloc-report.sh debug | column -t -s $'\t'
  bash dev-tools/maintenance/cloc-report.sh by-module
  bash dev-tools/maintenance/cloc-report.sh by-module --json
  bash dev-tools/maintenance/cloc-report.sh design
  bash dev-tools/maintenance/cloc-report.sh architecture --json
  bash dev-tools/maintenance/cloc-report.sh markdown --by-file
EOF
}

if ! command -v cloc >/dev/null 2>&1; then
  echo "cloc is not installed or not on PATH." >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "git is not installed or not on PATH." >&2
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "cloc-report.sh must be run from inside a Git working tree." >&2
  exit 1
fi

mode="${1:-repo}"
if [[ $# -gt 0 ]]; then
  shift
fi

source_dirs=(
  buildSrc
  dev-tools
  gradle
  protos
  services
  web-client
)

print_banner() {
  printf '%s\n' "$*" >&2
}

is_root_file() {
  [[ "$1" != */* ]]
}

is_markdown_path() {
  [[ "$(basename "$1")" == *.md ]]
}

is_design_path() {
  [[ "$1" == design/* ]]
}

is_architecture_path() {
  [[ "$1" == design/architecture/* ]]
}

is_source_root_file() {
  is_root_file "$1" || return 1
  ! is_markdown_path "$1"
}

is_under_source_roots() {
  local path="$1"
  local root
  for root in "${source_dirs[@]}"; do
    if [[ "$path" == "$root"/* ]]; then
      return 0
    fi
  done
  return 1
}

is_source_path() {
  local path="$1"
  is_under_source_roots "$path" || is_source_root_file "$path"
}

test_path_rule() {
  local path="$1"
  local base
  base="$(basename "$path")"

  case "$path" in
    */src/test/*)
      printf '%s\n' "gradle_src_test"
      return 0
      ;;
    */src/testFixtures/*)
      printf '%s\n' "gradle_test_fixtures"
      return 0
      ;;
    */src/integrationTest/*)
      printf '%s\n' "gradle_integration_test"
      return 0
      ;;
    */src/e2e/*|*/src/e2eTest/*)
      printf '%s\n' "gradle_e2e_test"
      return 0
      ;;
    */__tests__/*)
      printf '%s\n' "js_dunder_tests"
      return 0
      ;;
    dev-tools/tests/*)
      printf '%s\n' "dev_tools_contract_tests"
      return 0
      ;;
    dev-tools/validation/test_*.py)
      printf '%s\n' "dev_tools_validation_test"
      return 0
      ;;
  esac

  case "$base" in
    *.test.js|*.test.jsx|*.test.ts|*.test.tsx|*.spec.js|*.spec.jsx|*.spec.ts|*.spec.tsx)
      printf '%s\n' "js_test_name"
      return 0
      ;;
    playwright.config.js|playwright.config.ts|playwright.config.cjs|playwright.config.mjs)
      printf '%s\n' "playwright_config"
      return 0
      ;;
    cypress.config.js|cypress.config.ts|cypress.config.cjs|cypress.config.mjs)
      printf '%s\n' "cypress_config"
      return 0
      ;;
  esac

  return 1
}

classify_source_path() {
  local path="$1"
  local reason
  local root

  if ! is_source_path "$path"; then
    return 1
  fi

  if reason="$(test_path_rule "$path")"; then
    printf 'tests\t%s\n' "$reason"
    return 0
  fi

  if is_root_file "$path"; then
    printf 'prod\t%s\n' "root_non_markdown"
    return 0
  fi

  for root in "${source_dirs[@]}"; do
    if [[ "$path" == "$root"/* ]]; then
      printf 'prod\tsource_root:%s\n' "$root"
      return 0
    fi
  done

  return 1
}

should_include_file() {
  local mode="$1"
  local path="$2"
  local classification
  local bucket

  case "$mode" in
    repo)
      return 0
      ;;
    source)
      classify_source_path "$path" >/dev/null
      ;;
    prod)
      classification="$(classify_source_path "$path")" || return 1
      bucket="${classification%%$'\t'*}"
      [[ "$bucket" == "prod" ]]
      ;;
    tests)
      classification="$(classify_source_path "$path")" || return 1
      bucket="${classification%%$'\t'*}"
      [[ "$bucket" == "tests" ]]
      ;;
    design)
      is_design_path "$path"
      ;;
    architecture)
      is_architecture_path "$path"
      ;;
    markdown)
      is_markdown_path "$path"
      ;;
  esac
}

populate_mode_file_list() {
  local mode="$1"
  local output_file="$2"
  local tracked_file

  while IFS= read -r -d '' tracked_file; do
    if should_include_file "$mode" "$tracked_file"; then
      printf '%s\n' "$tracked_file" >>"$output_file"
    fi
  done < <(git ls-files -z)
}

run_debug_mode() {
  local tracked_file
  local classification

  print_banner "Printing tracked source-scope classification as: bucket<TAB>rule<TAB>path"
  printf 'bucket\trule\tpath\n'
  while IFS= read -r -d '' tracked_file; do
    classification="$(classify_source_path "$tracked_file")" || continue
    printf '%s\t%s\n' "$classification" "$tracked_file"
  done < <(git ls-files -z)
}

run_by_module_mode() {
  local source_list
  local prod_list
  local tests_list
  local source_json
  local prod_json
  local tests_json
  local output_mode="table"
  local arg
  local status

  for arg in "$@"; do
    case "$arg" in
      --json)
        output_mode="json"
        ;;
      *)
        echo "by-module only supports the wrapper flag --json." >&2
        return 1
        ;;
    esac
  done

  source_list="$(mktemp)"
  prod_list="$(mktemp)"
  tests_list="$(mktemp)"
  source_json="$(mktemp)"
  prod_json="$(mktemp)"
  tests_json="$(mktemp)"

  populate_mode_file_list source "$source_list"
  populate_mode_file_list prod "$prod_list"
  populate_mode_file_list tests "$tests_list"

  print_banner "Running by-module rollup from tracked source/prod/tests inventories..."
  cloc --quiet --skip-uniqueness --json --by-file --list-file="$source_list" >"$source_json"
  cloc --quiet --skip-uniqueness --json --by-file --list-file="$prod_list" >"$prod_json"
  cloc --quiet --skip-uniqueness --json --by-file --list-file="$tests_list" >"$tests_json"

  python3 - "$source_json" "$prod_json" "$tests_json" "$output_mode" <<'PY'
import json
import sys
from pathlib import Path

source_json_path = Path(sys.argv[1])
prod_json_path = Path(sys.argv[2])
tests_json_path = Path(sys.argv[3])
output_mode = sys.argv[4]


def bucket_for_path(path: str) -> str:
    if "/" not in path:
        return "repo-root"
    if path.startswith("services/"):
        return "/".join(path.split("/", 2)[:2])
    return path.split("/", 1)[0]


def aggregate(path: Path) -> tuple[dict[str, dict[str, int]], dict[str, int]]:
    obj = json.loads(path.read_text())
    buckets: dict[str, dict[str, int]] = {}
    for file_path, stats in obj.items():
        if file_path in {"header", "SUM"}:
            continue
        bucket = bucket_for_path(file_path)
        bucket_stats = buckets.setdefault(bucket, {"files": 0, "code": 0})
        bucket_stats["files"] += 1
        bucket_stats["code"] += int(stats["code"])
    summary = obj["SUM"]
    return buckets, {"files": int(summary["nFiles"]), "code": int(summary["code"])}


source_buckets, source_summary = aggregate(source_json_path)
prod_buckets, prod_summary = aggregate(prod_json_path)
tests_buckets, tests_summary = aggregate(tests_json_path)

all_buckets = set(source_buckets) | set(prod_buckets) | set(tests_buckets)

root_order = {
    "repo-root": 0,
    "buildSrc": 1,
    "dev-tools": 2,
    "gradle": 3,
    "protos": 4,
    "web-client": 5,
}


def sort_key(bucket: str) -> tuple[int, str]:
    if bucket.startswith("services/"):
        return (10, bucket)
    return (root_order.get(bucket, 20), bucket)


rows = []
for bucket in sorted(all_buckets, key=sort_key):
    source_stats = source_buckets.get(bucket, {"files": 0, "code": 0})
    prod_stats = prod_buckets.get(bucket, {"files": 0, "code": 0})
    tests_stats = tests_buckets.get(bucket, {"files": 0, "code": 0})
    rows.append(
        {
            "module": bucket,
            "total_files": source_stats["files"],
            "total_code": source_stats["code"],
            "prod_files": prod_stats["files"],
            "prod_code": prod_stats["code"],
            "tests_files": tests_stats["files"],
            "tests_code": tests_stats["code"],
        }
    )

summary_row = {
    "module": "TOTAL",
    "total_files": source_summary["files"],
    "total_code": source_summary["code"],
    "prod_files": prod_summary["files"],
    "prod_code": prod_summary["code"],
    "tests_files": tests_summary["files"],
    "tests_code": tests_summary["code"],
}

if output_mode == "json":
    print(json.dumps({"modules": rows, "summary": summary_row}, indent=2))
    raise SystemExit(0)

headers = [
    ("module", "module"),
    ("total_files", "total_files"),
    ("total_code", "total_code"),
    ("prod_files", "prod_files"),
    ("prod_code", "prod_code"),
    ("tests_files", "tests_files"),
    ("tests_code", "tests_code"),
]

widths = {}
for key, label in headers:
    widths[key] = max(len(label), len(str(summary_row[key])), *(len(str(row[key])) for row in rows))

header_line = "  ".join(label.ljust(widths[key]) for key, label in headers)
print(header_line)
for row in rows:
    print("  ".join(str(row[key]).ljust(widths[key]) for key, _ in headers))
print("  ".join(str(summary_row[key]).ljust(widths[key]) for key, _ in headers))
PY
  status=$?

  rm -f "$source_list" "$prod_list" "$tests_list" "$source_json" "$prod_json" "$tests_json"
  return "$status"
}

run_cloc_mode() {
  local mode="$1"
  local banner="$2"
  shift 2

  local file_list
  local status
  file_list="$(mktemp)"

  populate_mode_file_list "$mode" "$file_list"
  print_banner "$banner"
  # Count tracked file footprint rather than deduplicated content so split
  # buckets remain additive and predictable across modes.
  cloc --quiet --skip-uniqueness --list-file="$file_list" "$@"
  status=$?
  rm -f "$file_list"
  return "$status"
}

case "$mode" in
  repo)
    run_cloc_mode repo "Running cloc in repo mode (tracked repository footprint across source, docs, config, CI, and scripts)..." "$@"
    ;;
  source)
    run_cloc_mode source "Running cloc in source mode (tracked build logic, scripts, protos, services, web client, and non-Markdown root support files)..." "$@"
    ;;
  prod)
    run_cloc_mode prod "Running cloc in prod mode (tracked source only, excluding files currently classified as tests)..." "$@"
    ;;
  tests)
    run_cloc_mode tests "Running cloc in tests mode (tracked files currently classified as tests only)..." "$@"
    ;;
  debug)
    run_debug_mode
    ;;
  by-module)
    run_by_module_mode "$@"
    ;;
  design)
    run_cloc_mode design "Running cloc in design mode (tracked files under design/)..." "$@"
    ;;
  architecture)
    run_cloc_mode architecture "Running cloc in architecture mode (tracked files under design/architecture/)..." "$@"
    ;;
  markdown)
    run_cloc_mode markdown "Running cloc in markdown mode (tracked Markdown files only)..." "$@"
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    echo "Unknown mode: $mode" >&2
    echo >&2
    usage >&2
    exit 1
    ;;
esac
