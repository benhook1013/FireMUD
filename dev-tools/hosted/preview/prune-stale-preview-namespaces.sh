#!/usr/bin/env bash
set -euo pipefail

delete_runtime_namespace() {
  local runtime_namespace="$1"
  if [[ ! "$runtime_namespace" =~ ^(dev|pr-[1-9][0-9]*)$ ]]; then
    echo "runtime namespace is not canonical: ${runtime_namespace}" >&2
    return 2
  fi

  if ! kubectl get namespace "$runtime_namespace" >/dev/null 2>&1; then
    echo "Runtime namespace ${runtime_namespace} is already absent."
    return 0
  fi

  kubectl delete namespace "$runtime_namespace" --ignore-not-found --wait=false
  kubectl wait --for=delete "namespace/${runtime_namespace}" --timeout="${PREVIEW_DELETE_TIMEOUT:-10m}"
  echo "Runtime namespace ${runtime_namespace} is absent."
}

if [[ "${1:-}" == "--delete-runtime" ]]; then
  if [[ $# -ne 2 ]]; then
    echo "usage: $0 --delete-runtime <runtime_namespace>" >&2
    exit 1
  fi
  delete_runtime_namespace "$2"
  exit $?
fi

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
      --jq '
        def labels_valid:
          ((.labels? | type) == "array")
          and all(.labels[]?; (type == "object") and ((.name? | type) == "string"));
        [
          .state,
          .base.ref,
          .user.login,
          (if labels_valid then any(.labels[]?; .name == "preview:paused") else false end),
          (if labels_valid then "valid" else "invalid" end)
        ] | @tsv' 2>/dev/null
  )"; then
    IFS=$'\t' read -r pr_state pr_base_ref pr_author pr_paused labels_valid <<<"$pr_metadata"
  else
    echo "Refusing to prune ${namespace}: GitHub API metadata is unavailable for PR #${pr_number}" >&2
    exit 1
  fi

  eligibility_output="$(
    python3 "$eligibility_script" \
      --operation retain \
      --state "$pr_state" \
      --base-ref "$pr_base_ref" \
      --author "$pr_author" \
      --labels-json "$(if [[ "$labels_valid" == valid ]]; then
        if [[ "$pr_paused" == true ]]; then
          printf '%s' '[{"name":"preview:paused"}]'
        else
          printf '%s' '[]'
        fi
      else
        printf '%s' 'malformed'
      fi)"
  )"
  eligible="$(sed -n 's/^eligible=//p' <<<"$eligibility_output")"
  reason="$(sed -n 's/^reason=//p' <<<"$eligibility_output")"

  if [[ "$eligible" == "true" ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} remains preview-eligible"
    continue
  fi

  echo "Pruning ${namespace}: PR #${pr_number} is not preview-eligible (reason=${reason})"
  if [[ "$apply" == true ]]; then
    if [[ -n "${PREVIEW_DELETE_SCRIPT:-}" ]]; then
      bash "$PREVIEW_DELETE_SCRIPT" "$namespace"
    else
      delete_runtime_namespace "$namespace"
    fi
  fi
done
