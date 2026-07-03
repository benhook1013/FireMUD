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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
eligibility_script="${script_dir}/preview-eligibility.py"

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

  pr_metadata=""
  if pr_metadata="$(
    gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" \
      --jq '[.state, .base.ref, .user.login] | @tsv' 2>/dev/null
  )"; then
    IFS=$'\t' read -r pr_state pr_base_ref pr_author <<<"$pr_metadata"
  else
    pr_state="missing"
  fi

  if [[ "$pr_state" != "missing" ]]; then
    eligibility_output="$(
      python3 "$eligibility_script" \
        --operation retain \
        --state "$pr_state" \
        --base-ref "$pr_base_ref" \
        --author "$pr_author"
    )"
    eligible="$(sed -n 's/^eligible=//p' <<<"$eligibility_output")"
    reason="$(sed -n 's/^reason=//p' <<<"$eligibility_output")"
  else
    eligible="false"
    reason="missing"
  fi

  if [[ "$eligible" == "true" ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} remains preview-eligible"
    continue
  fi

  echo "Pruning ${namespace}: PR #${pr_number} is not preview-eligible (reason=${reason})"
  if [[ "$apply" == true ]]; then
    bash "$(dirname "$0")/../shared/delete-hosted-namespace.sh" "$namespace" "$release_name"
  fi
done
