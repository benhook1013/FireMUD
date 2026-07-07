#!/usr/bin/env bash
set -euo pipefail

# TEMP PLAN
# Track the next cloc-report improvements here and remove completed items as
# they land. This header is temporary and should be cleaned up after the next
# round of work.
#
# Active findings / planned fixes:
# - Tighten FireMUD-specific classification so prod/tests splits handle more
#   than Gradle-style src/test roots, including repo-owned verification code
#   under dev-tools/tests and validation-style test scripts.
# - Decide whether FireMUD should keep prod/tests only or add a separate
#   verification bucket for smoke/validation tooling that is test-like but not
#   service-local src/test code.
# - Add a debug/inventory mode that prints file -> bucket -> rule so
#   classification stays inspectable and easy to correct.
# - Add by-module reporting instead of only by-service, covering services/*,
#   shared common-* modules, web-client, protos, dev-tools, buildSrc, gradle,
#   and optionally design.
# - Add FireMUD-shaped docs modes later, for example design, architecture, and
#   possibly service-docs, after the canonical inventory exists.
# - Add diff-scoped reporting later using git diff-backed inventories and the
#   same classifier rather than introducing a separate counting path.
# - Add short summary/paste-friendly output only after the underlying inventory
#   and bucketing are trustworthy.

usage() {
  cat <<'EOF'
Usage: bash dev-tools/maintenance/cloc-report.sh [repo|source|prod|tests|markdown] [extra cloc args...]

Modes:
  repo      Broad repository footprint across source, docs, config, CI, and scripts.
  source    Source-focused count across build logic, scripts, protos, services, and web client code, including tests.
  prod      Source-focused count excluding test directories, test fixtures, and common test file naming patterns.
  tests     Test-only count across standard test directories, test fixtures, and common test file naming patterns.
  markdown  Markdown-only count across the repository.

Examples:
  bash dev-tools/maintenance/cloc-report.sh
  bash dev-tools/maintenance/cloc-report.sh source
  bash dev-tools/maintenance/cloc-report.sh prod
  bash dev-tools/maintenance/cloc-report.sh tests
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

is_test_path() {
  local path="$1"
  local base
  base="$(basename "$path")"

  case "$path" in
    */src/test/*|*/src/testFixtures/*|*/src/integrationTest/*|*/src/e2e/*|*/src/e2eTest/*|*/__tests__/*)
      return 0
      ;;
  esac

  case "$base" in
    *.test.js|*.test.jsx|*.test.ts|*.test.tsx|*.spec.js|*.spec.jsx|*.spec.ts|*.spec.tsx)
      return 0
      ;;
    playwright.config.js|playwright.config.ts|playwright.config.cjs|playwright.config.mjs)
      return 0
      ;;
    cypress.config.js|cypress.config.ts|cypress.config.cjs|cypress.config.mjs)
      return 0
      ;;
  esac

  return 1
}

should_include_file() {
  local mode="$1"
  local path="$2"

  case "$mode" in
    repo)
      return 0
      ;;
    source)
      is_source_path "$path"
      ;;
    prod)
      is_source_path "$path" && ! is_test_path "$path"
      ;;
    tests)
      is_source_path "$path" && is_test_path "$path"
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

run_cloc_mode() {
  local mode="$1"
  local banner="$2"
  shift 2

  local file_list
  local status
  file_list="$(mktemp)"

  populate_mode_file_list "$mode" "$file_list"
  print_banner "$banner"
  cloc --quiet --list-file="$file_list" "$@"
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
