#!/usr/bin/env bash
set -euo pipefail

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

mode="${1:-repo}"
if [[ $# -gt 0 ]]; then
  shift
fi

exclude_dirs=".git,.gradle,build,node_modules,target,bower_components,dist,coverage,out,.next,site,generated,grpc-docs"
exclude=(--exclude-dir="$exclude_dirs")

repo_dirs=(
  .github
  buildSrc
  charts
  config
  design
  dev-tools
  docker
  gradle
  k8s
  protos
  services
  web-client
)

source_dirs=(
  buildSrc
  dev-tools
  gradle
  protos
  services
  web-client
)

source_exclude_dir_names=(
  .git
  .gradle
  build
  node_modules
  target
  bower_components
  dist
  coverage
  out
  .next
  site
  generated
  grpc-docs
)

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

populate_source_file_lists() {
  local prod_list="$1"
  local test_list="$2"
  local -a find_cmd
  local dir

  find_cmd=(find)
  for dir in "${source_dirs[@]}"; do
    find_cmd+=("$dir")
  done
  find_cmd+=("(" "-type" "d" "(")
  for ((i = 0; i < ${#source_exclude_dir_names[@]}; i++)); do
    if ((i > 0)); then
      find_cmd+=("-o")
    fi
    find_cmd+=("-name" "${source_exclude_dir_names[i]}")
  done
  find_cmd+=(")" "-prune" ")" "-o" "-type" "f" "-print")

  while IFS= read -r file; do
    if is_test_path "$file"; then
      printf '%s\n' "$file" >>"$test_list"
    else
      printf '%s\n' "$file" >>"$prod_list"
    fi
  done < <("${find_cmd[@]}")
}

run_source_split_mode() {
  local mode="$1"
  shift

  local prod_list
  local test_list
  prod_list="$(mktemp)"
  test_list="$(mktemp)"
  trap 'rm -f "$prod_list" "$test_list"' RETURN

  populate_source_file_lists "$prod_list" "$test_list"

  case "$mode" in
    prod)
      echo "Running cloc in prod mode (source only, excluding tests and test fixtures), excluding generated/dependency trees..."
      cloc --list-file="$prod_list" "$@"
      ;;
    tests)
      echo "Running cloc in tests mode (test directories, fixtures, and test-named source files only), excluding generated/dependency trees..."
      cloc --list-file="$test_list" "$@"
      ;;
  esac
}

case "$mode" in
  repo)
    echo "Running cloc in repo mode (source + docs + config + CI), excluding generated/dependency trees..."
    cloc "${repo_dirs[@]}" "${exclude[@]}" "$@"
    ;;
  source)
    echo "Running cloc in source mode (build logic, scripts, protos, services, web client), excluding generated/dependency trees..."
    cloc "${source_dirs[@]}" "${exclude[@]}" "$@"
    ;;
  prod)
    run_source_split_mode prod "$@"
    ;;
  tests)
    run_source_split_mode tests "$@"
    ;;
  markdown)
    echo "Running cloc in markdown mode (Markdown files only), excluding generated/dependency trees..."
    cloc . --include-ext=md "${exclude[@]}" "$@"
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
