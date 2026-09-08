#!/usr/bin/env bash
set -euo pipefail

delete_runtime_namespace() {
  local runtime_namespace="$1"
  if [[ ! "$runtime_namespace" =~ ^(dev|pr-[1-9][0-9]*)$ ]]; then
    echo "runtime namespace is not canonical: ${runtime_namespace}" >&2
    return 2
  fi

  runtime_lookup="$(
    kubectl get namespace "$runtime_namespace" --ignore-not-found -o name
  )"
  if [[ -z "$runtime_lookup" ]]; then
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
eligibility_script="${PREVIEW_ELIGIBILITY_SCRIPT:-${script_dir}/preview-eligibility.py}"

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

  if ! pr_metadata="$(
    gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" \
      --jq '
        [
          .state,
          .base.ref,
          .user.login,
          (.labels | tojson | @base64)
        ] | @tsv' 2>/dev/null
  )"; then
    echo "Keeping ${namespace}: PR #${pr_number} metadata is unavailable or malformed"
    continue
  fi
  metadata_without_tabs="${pr_metadata//$'\t'/}"
  metadata_tab_count=$((${#pr_metadata} - ${#metadata_without_tabs}))
  if [[ "$pr_metadata" == *$'\n'* ]] || (( metadata_tab_count != 3 )); then
    echo "Keeping ${namespace}: PR #${pr_number} metadata transport is not one four-field record"
    continue
  fi
  IFS=$'\t' read -r pr_state pr_base_ref pr_author pr_labels_base64 <<<"$pr_metadata"
  if [[ -z "$pr_state" || -z "$pr_base_ref" || -z "$pr_author" || -z "$pr_labels_base64" ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} metadata transport is incomplete"
    continue
  fi
  if ! pr_labels_json="$(printf '%s' "$pr_labels_base64" | base64 --decode 2>/dev/null)"; then
    echo "Keeping ${namespace}: PR #${pr_number} label transport is malformed"
    continue
  fi
  if ! eligibility_output="$(
    python3 "$eligibility_script" \
      --operation retain \
      --state "$pr_state" \
      --base-ref "$pr_base_ref" \
      --author "$pr_author" \
      --labels-json "$pr_labels_json"
  )"; then
    echo "Keeping ${namespace}: PR #${pr_number} eligibility could not be evaluated"
    continue
  fi
  if [[ "$eligibility_output" != *$'\n'* ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} eligibility result is malformed"
    continue
  fi
  eligibility_first_line="${eligibility_output%%$'\n'*}"
  eligibility_second_line="${eligibility_output#*$'\n'}"
  if [[ "$eligibility_second_line" == *$'\n'* ]] ||
    [[ "$eligibility_first_line" != "eligible=true" && "$eligibility_first_line" != "eligible=false" ]] ||
    [[ "$eligibility_second_line" != reason=?* ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} eligibility result is malformed"
    continue
  fi
  eligible="${eligibility_first_line#eligible=}"
  reason="${eligibility_second_line#reason=}"

  if [[ "$eligible" == "true" ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} remains preview-eligible"
    continue
  fi

  case "$reason" in
    preview-paused | dependency-bot | unsupported-base-branch | pr-not-open)
      ;;
    *)
      echo "Keeping ${namespace}: PR #${pr_number} eligibility reason is not authoritative for pruning (reason=${reason})"
      continue
      ;;
  esac

  echo "Pruning ${namespace}: PR #${pr_number} is not preview-eligible (reason=${reason})"
  if [[ "$apply" == true ]]; then
    if [[ -n "${PREVIEW_DELETE_SCRIPT:-}" ]]; then
      bash "$PREVIEW_DELETE_SCRIPT" "$namespace" "$release_name"
    else
      delete_runtime_namespace "$namespace"
    fi
  fi
done
