#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash dev-tools/maintenance/cloc-report.sh [repo|source|markdown] [extra cloc args...]

Modes:
  repo      Broad repository footprint across source, docs, config, CI, and scripts.
  source    Source-focused count across build logic, scripts, protos, services, and web client code.
  markdown  Markdown-only count across the repository.

Examples:
  bash dev-tools/maintenance/cloc-report.sh
  bash dev-tools/maintenance/cloc-report.sh source
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

case "$mode" in
  repo)
    echo "Running cloc in repo mode (source + docs + config + CI), excluding generated/dependency trees..."
    cloc "${repo_dirs[@]}" "${exclude[@]}" "$@"
    ;;
  source)
    echo "Running cloc in source mode (build logic, scripts, protos, services, web client), excluding generated/dependency trees..."
    cloc "${source_dirs[@]}" "${exclude[@]}" "$@"
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
