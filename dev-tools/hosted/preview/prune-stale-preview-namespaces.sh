#!/usr/bin/env bash
set -euo pipefail

apply=false
for arg in "$@"; do
  case "$arg" in
    --apply)
      apply=true
      ;;
    *)
      echo "usage: $0 [--apply]" >&2
      exit 1
      ;;
  esac
done

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GITHUB_REPOSITORY is required" >&2
  exit 1
fi

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required" >&2
  exit 1
fi

mapfile -t namespace_rows < <(
  kubectl get namespaces -l firemud.dev/preview=true \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.firemud\.dev/pr-number}{"\n"}{end}' \
    | sed '/^$/d'
)

if (( ${#namespace_rows[@]} == 0 )); then
  echo "No preview namespaces found."
  exit 0
fi

for row in "${namespace_rows[@]}"; do
  namespace="${row%%$'\t'*}"
  pr_number="${row#*$'\t'}"
  release_name="$namespace"

  if [[ -z "$pr_number" || "$pr_number" == "$namespace" ]]; then
    echo "Skipping ${namespace}: missing firemud.dev/pr-number label"
    continue
  fi

  pr_state=""
  if pr_state="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" --jq '.state' 2>/dev/null)"; then
    :
  else
    pr_state="missing"
  fi

  if [[ "$pr_state" == "open" ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} is open"
    continue
  fi

  echo "Pruning ${namespace}: PR #${pr_number} state is ${pr_state}"
  if [[ "$apply" == true ]]; then
    bash "$(dirname "$0")/delete-preview-namespace.sh" "$namespace" "$release_name"
  fi
done
