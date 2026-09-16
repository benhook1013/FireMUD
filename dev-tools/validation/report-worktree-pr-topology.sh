#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: report-worktree-pr-topology.sh [--repo OWNER/REPO] [--include-renovate] [--json]
       report-worktree-pr-topology.sh --pr N [--repo OWNER/REPO] [--include-renovate] [--json]

Reports local worktrees and branches alongside open GitHub pull requests. Renovate
pull requests are excluded by default so active product lanes are easy to inspect.
Use --pr for a bounded exact branch/SHA selected-stack report; --json emits the
machine-readable form for either inventory or selected-stack mode.
EOF
}

repo=""
include_renovate=false
pr_number=""
json=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      repo="${2:-}"
      [[ -n "$repo" ]] || { echo "--repo requires OWNER/REPO" >&2; exit 2; }
      shift 2
      ;;
    --include-renovate)
      include_renovate=true
      shift
      ;;
    --pr)
      pr_number="${2:-}"
      [[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || { echo "--pr requires a positive pull request number" >&2; exit 2; }
      shift 2
      ;;
    --json)
      json=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

helper="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/report-worktree-pr-topology.py"
[[ -f "$helper" ]] || { echo "topology reporter helper is missing: $helper" >&2; exit 1; }
helper_args=()
[[ -z "$repo" ]] || helper_args+=(--repo "$repo")
[[ "$include_renovate" == "true" ]] && helper_args+=(--include-renovate)
[[ -z "$pr_number" ]] || helper_args+=(--pr "$pr_number")
[[ "$json" == "true" ]] && helper_args+=(--json)
exec python3 "$helper" "${helper_args[@]+"${helper_args[@]}"}"
