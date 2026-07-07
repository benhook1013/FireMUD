#!/usr/bin/env bash
set -euo pipefail

# FireMUD cloc report wrapper.
# - Builds every view from git-tracked inventories so ignored local outputs such
#   as **/bin/** do not pollute counts.
# - Uses cloc --skip-uniqueness by default so totals represent tracked file
#   footprint rather than deduplicated content.
# - Treats source scope as buildSrc, dev-tools, gradle, protos, services,
#   web-client, plus non-Markdown repo-root support files.
# - Classifies tests broadly enough for this repo: src/test*, test fixtures,
#   JS/TS test naming patterns, dev-tools/tests, and dev-tools/validation
#   test_*.py helpers.
# - Keeps wrapper banners on stderr so cloc structured output flags such as
#   --json remain clean on stdout.

usage() {
  cat <<'EOF'
Usage: bash dev-tools/maintenance/cloc-report.sh [repo|source|prod|tests|debug|by-module|summary|diff|markdown|design|architecture|service-docs] [extra args...]

Modes:
  repo      Broad repository footprint across source, docs, config, CI, and scripts.
  source    Source-focused count across build logic, scripts, protos, services, and web client code, including tests.
  prod      Source-focused count excluding files currently classified as tests.
  tests     Test-only count across standard test directories, test fixtures, repo-owned contract tests, and validation-style test scripts.
  debug     Print tracked source-scope file classification as: bucket TAB rule TAB path.
  by-module Aggregate tracked source/prod/tests counts by FireMUD module bucket.
  summary   Print a compact repo/source/prod/tests/docs rollup for tracked files.
  diff      Run summary output for changed tracked files in a git diff range; add --by-module for per-module output.
  markdown  Markdown-only count across the repository.
  design    Tracked files under design/.
  architecture Tracked files under design/architecture/.
  service-docs Tracked service-local docs under services/*/README.md and services/*/design/.

Examples:
  bash dev-tools/maintenance/cloc-report.sh
  bash dev-tools/maintenance/cloc-report.sh source
  bash dev-tools/maintenance/cloc-report.sh prod
  bash dev-tools/maintenance/cloc-report.sh tests
  bash dev-tools/maintenance/cloc-report.sh debug | column -t -s $'\t'
  bash dev-tools/maintenance/cloc-report.sh by-module
  bash dev-tools/maintenance/cloc-report.sh by-module --json
  bash dev-tools/maintenance/cloc-report.sh summary
  bash dev-tools/maintenance/cloc-report.sh summary --json
  bash dev-tools/maintenance/cloc-report.sh diff develop...HEAD
  bash dev-tools/maintenance/cloc-report.sh diff develop...HEAD --by-module --json
  bash dev-tools/maintenance/cloc-report.sh design
  bash dev-tools/maintenance/cloc-report.sh architecture --json
  bash dev-tools/maintenance/cloc-report.sh service-docs
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

cleanup_files() {
  rm -f "$@"
}

temp_files=()

register_temp_file() {
  temp_files+=("$1")
}

create_temp_file() {
  local temp_file
  temp_file="$(mktemp)"
  register_temp_file "$temp_file"
  printf '%s\n' "$temp_file"
}

cleanup_registered_temp_files() {
  if [[ ${#temp_files[@]} -gt 0 ]]; then
    cleanup_files "${temp_files[@]}"
  fi
}

trap cleanup_registered_temp_files EXIT

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

is_service_docs_path() {
  [[ "$1" == services/*/README.md || "$1" == services/*/design/* ]]
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
    service-docs)
      is_service_docs_path "$path"
      ;;
    markdown)
      is_markdown_path "$path"
      ;;
  esac
}

build_tracked_inventory_file() {
  local output_file="$1"
  git ls-files >"$output_file"
}

build_diff_inventory_file() {
  local git_range="$1"
  local output_file="$2"
  local changed_file

  while IFS= read -r -d '' changed_file; do
    printf '%s\n' "$changed_file" >>"$output_file"
  done < <(git diff --name-only --diff-filter=ACMR -z "$git_range" --)
}

populate_mode_file_list_from_inventory() {
  local mode="$1"
  local inventory_file="$2"
  local output_file="$3"
  local tracked_file

  while IFS= read -r tracked_file; do
    [[ -n "$tracked_file" ]] || continue
    if should_include_file "$mode" "$tracked_file"; then
      printf '%s\n' "$tracked_file" >>"$output_file"
    fi
  done <"$inventory_file"
}

populate_mode_file_list() {
  local mode="$1"
  local output_file="$2"
  local inventory_file
  inventory_file="$(create_temp_file)"
  build_tracked_inventory_file "$inventory_file"
  populate_mode_file_list_from_inventory "$mode" "$inventory_file" "$output_file"
}

build_mode_json_from_inventory() {
  local mode="$1"
  local inventory_file="$2"
  local output_json="$3"
  local file_list
  file_list="$(create_temp_file)"

  populate_mode_file_list_from_inventory "$mode" "$inventory_file" "$file_list"
  if [[ ! -s "$file_list" ]]; then
    cat >"$output_json" <<'EOF'
{"SUM":{"blank":0,"comment":0,"code":0,"nFiles":0}}
EOF
  else
    cloc --quiet --skip-uniqueness --json --by-file --list-file="$file_list" >"$output_json"
  fi
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

run_by_module_mode_with_inventory() {
  local inventory_file="$1"
  local scope_label="$2"
  shift 2

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

  source_json="$(create_temp_file)"
  prod_json="$(create_temp_file)"
  tests_json="$(create_temp_file)"

  print_banner "Running by-module rollup from $scope_label inventories..."
  build_mode_json_from_inventory source "$inventory_file" "$source_json"
  build_mode_json_from_inventory prod "$inventory_file" "$prod_json"
  build_mode_json_from_inventory tests "$inventory_file" "$tests_json"

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

  return "$status"
}

run_by_module_mode() {
  local inventory_file
  inventory_file="$(create_temp_file)"
  build_tracked_inventory_file "$inventory_file"
  run_by_module_mode_with_inventory "$inventory_file" "tracked source/prod/tests" "$@"
  return $?
}

run_summary_mode_with_inventory() {
  local inventory_file="$1"
  local scope_label="$2"
  shift 2

  local repo_json
  local source_json
  local prod_json
  local tests_json
  local markdown_json
  local design_json
  local architecture_json
  local service_docs_json
  local output_mode="table"
  local arg
  local status

  for arg in "$@"; do
    case "$arg" in
      --json)
        output_mode="json"
        ;;
      *)
        echo "summary only supports the wrapper flag --json." >&2
        return 1
        ;;
    esac
  done

  repo_json="$(create_temp_file)"
  source_json="$(create_temp_file)"
  prod_json="$(create_temp_file)"
  tests_json="$(create_temp_file)"
  markdown_json="$(create_temp_file)"
  design_json="$(create_temp_file)"
  architecture_json="$(create_temp_file)"
  service_docs_json="$(create_temp_file)"

  print_banner "Running summary rollup from $scope_label inventories..."
  build_mode_json_from_inventory repo "$inventory_file" "$repo_json"
  build_mode_json_from_inventory source "$inventory_file" "$source_json"
  build_mode_json_from_inventory prod "$inventory_file" "$prod_json"
  build_mode_json_from_inventory tests "$inventory_file" "$tests_json"
  build_mode_json_from_inventory markdown "$inventory_file" "$markdown_json"
  build_mode_json_from_inventory design "$inventory_file" "$design_json"
  build_mode_json_from_inventory architecture "$inventory_file" "$architecture_json"
  build_mode_json_from_inventory service-docs "$inventory_file" "$service_docs_json"

  python3 - \
    "$repo_json" "$source_json" "$prod_json" "$tests_json" \
    "$markdown_json" "$design_json" "$architecture_json" "$service_docs_json" \
    "$output_mode" <<'PY'
import json
import sys
from pathlib import Path

paths = [Path(arg) for arg in sys.argv[1:9]]
output_mode = sys.argv[9]
labels = [
    ("repo", paths[0]),
    ("source", paths[1]),
    ("prod", paths[2]),
    ("tests", paths[3]),
    ("markdown", paths[4]),
    ("design", paths[5]),
    ("architecture", paths[6]),
    ("service_docs", paths[7]),
]

rows = []
for label, path in labels:
    obj = json.loads(path.read_text())
    summary = obj["SUM"]
    rows.append({"scope": label, "files": int(summary["nFiles"]), "code": int(summary["code"])})

if output_mode == "json":
    print(json.dumps({"scopes": rows}, indent=2))
    raise SystemExit(0)

headers = [("scope", "scope"), ("files", "files"), ("code", "code")]
widths = {}
for key, label in headers:
    widths[key] = max(len(label), *(len(str(row[key])) for row in rows))

print("  ".join(label.ljust(widths[key]) for key, label in headers))
for row in rows:
    print("  ".join(str(row[key]).ljust(widths[key]) for key, _ in headers))
PY
  status=$?

  return "$status"
}

run_summary_mode() {
  local inventory_file
  inventory_file="$(create_temp_file)"
  build_tracked_inventory_file "$inventory_file"
  run_summary_mode_with_inventory "$inventory_file" "tracked repo/source/docs" "$@"
  return $?
}

run_diff_mode() {
  local git_range="${1:-}"
  shift || true

  local by_module="false"
  local output_mode="table"
  local arg
  local inventory_file
  local status

  if [[ -z "$git_range" ]]; then
    echo "diff requires a git diff range, for example: develop...HEAD" >&2
    return 1
  fi

  for arg in "$@"; do
    case "$arg" in
      --by-module)
        by_module="true"
        ;;
      --json)
        output_mode="json"
        ;;
      *)
        echo "diff only supports the wrapper flags --by-module and --json." >&2
        return 1
        ;;
    esac
  done

  inventory_file="$(create_temp_file)"
  build_diff_inventory_file "$git_range" "$inventory_file"

  if [[ "$by_module" == "true" ]]; then
    if [[ "$output_mode" == "json" ]]; then
      run_by_module_mode_with_inventory "$inventory_file" "changed files in $git_range" --json
    else
      run_by_module_mode_with_inventory "$inventory_file" "changed files in $git_range"
    fi
    status=$?
  else
    if [[ "$output_mode" == "json" ]]; then
      run_summary_mode_with_inventory "$inventory_file" "changed files in $git_range" --json
    else
      run_summary_mode_with_inventory "$inventory_file" "changed files in $git_range"
    fi
    status=$?
  fi
  return "$status"
}

run_cloc_mode_with_inventory() {
  local inventory_file="$1"
  shift
  local mode="$1"
  local banner="$2"
  shift 2

  local file_list
  local status
  file_list="$(create_temp_file)"

  populate_mode_file_list_from_inventory "$mode" "$inventory_file" "$file_list"
  print_banner "$banner"
  # Count tracked file footprint rather than deduplicated content so split
  # buckets remain additive and predictable across modes.
  cloc --quiet --skip-uniqueness --list-file="$file_list" "$@"
  status=$?
  return "$status"
}

run_cloc_mode() {
  local mode="$1"
  local banner="$2"
  shift 2

  local inventory_file
  local status
  inventory_file="$(create_temp_file)"

  build_tracked_inventory_file "$inventory_file"
  run_cloc_mode_with_inventory "$inventory_file" "$mode" "$banner" "$@"
  status=$?
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
  summary)
    run_summary_mode "$@"
    ;;
  diff)
    run_diff_mode "$@"
    ;;
  design)
    run_cloc_mode design "Running cloc in design mode (tracked files under design/)..." "$@"
    ;;
  architecture)
    run_cloc_mode architecture "Running cloc in architecture mode (tracked files under design/architecture/)..." "$@"
    ;;
  service-docs)
    run_cloc_mode service-docs "Running cloc in service-docs mode (tracked service-local docs under services/*/README.md and services/*/design/)..." "$@"
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
