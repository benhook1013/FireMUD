#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: report-worktree-pr-topology.sh [--repo OWNER/REPO] [--include-renovate]

Reports local worktrees and branches alongside open GitHub pull requests. Renovate
pull requests are excluded by default so active product lanes are easy to inspect.
EOF
}

repo=""
include_renovate=false

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

command -v gh >/dev/null || { echo "gh CLI is required" >&2; exit 1; }

if [[ -z "$repo" ]]; then
  repo="$(gh repo view --json nameWithOwner --jq '.nameWithOwner')"
fi

echo "Repository: $repo"
echo
echo "Worktrees"
printf 'PATH\tBRANCH\tHEAD\tDIRTY\n'
git worktree list --porcelain | awk '
  function emit() {
    if (path != "") {
      if (branch == "") branch = "(detached)"
      printf "%s\t%s\t%s\n", path, branch, head
    }
  }
  /^worktree / {
    emit()
    path = substr($0, 10)
    head = ""
    branch = ""
  }
  /^HEAD / { head = substr($0, 6) }
  /^branch / {
    branch = $2
    sub("refs/heads/", "", branch)
  }
  END { emit() }
' | while IFS=$'\t' read -r path branch head; do
  dirty="clean"
  [[ -n "$(git -C "$path" status --porcelain)" ]] && dirty="dirty"
  printf '%s\t%s\t%s\t%s\n' "$path" "$branch" "$head" "$dirty"
done

echo
echo "Local branches"
printf 'BRANCH\tUPSTREAM\tHEAD\tLAST_COMMIT\n'
git for-each-ref \
  --format='%(refname:short)|%(upstream:short)|%(objectname:short)|%(committerdate:iso8601-strict)' \
  refs/heads | sort | while IFS='|' read -r branch upstream head committed; do
  printf '%s\t%s\t%s\t%s\n' "$branch" "${upstream:--}" "$head" "$committed"
done

echo
echo "Open pull requests"
printf 'NUMBER\tHEAD\tBASE\tMERGE_STATE\tTITLE\tURL\n'
pr_filter='.[]'
if ! "$include_renovate"; then
  pr_filter='.[] | select(.headRefName | startswith("renovate/") | not)'
fi
gh pr list --repo "$repo" --state open --limit 100 \
  --json number,headRefName,baseRefName,mergeStateStatus,title,url \
  --jq "$pr_filter | [.number, .headRefName, .baseRefName, .mergeStateStatus, .title, .url] | @tsv"
