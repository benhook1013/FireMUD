#!/usr/bin/env bash
set -euo pipefail

preview_delete_timeout="${PREVIEW_DELETE_TIMEOUT:-600}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
delete_script="${PREVIEW_DELETE_SCRIPT:-${script_dir}/../shared/delete-hosted-namespace.sh}"

if [[ "${1:-}" == "--delete-runtime" ]]; then
  if [[ $# -ne 2 ]]; then
    echo "usage: $0 --delete-runtime <runtime_namespace>" >&2
    exit 1
  fi
  runtime_namespace="$2"
  if [[ ! "$runtime_namespace" =~ ^pr-[1-9][0-9]*$ ]]; then
    echo "runtime namespace must match canonical pr-[1-9][0-9]* identity" >&2
    exit 2
  fi
  if ! [[ "$preview_delete_timeout" =~ ^[1-9][0-9]*$ ]] ||
    ((${#preview_delete_timeout} > 4)) ||
    ((10#$preview_delete_timeout > 3600)); then
    echo "PREVIEW_DELETE_TIMEOUT must be an integer between 1 and 3600" >&2
    exit 2
  fi
  PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS="$preview_delete_timeout" \
    bash "$delete_script" "$runtime_namespace" "$runtime_namespace"
  exit $?
fi

apply=false
retire_terminal_identities=false
for arg in "$@"; do
  case "$arg" in
    --apply)
      apply=true
      ;;
    --retire-terminal-identities)
      retire_terminal_identities=true
      ;;
    *)
      echo "usage: $0 [--apply] [--retire-terminal-identities]" >&2
      exit 1
      ;;
  esac
done

if [[ "$retire_terminal_identities" == true ]]; then
  if [[ "$apply" != true ]]; then
    echo "--retire-terminal-identities requires --apply" >&2
    exit 2
  fi
  if [[ "${HOSTED_IDENTITY_MODE:-}" != hosted-controller ]]; then
    echo "refusing terminal identity retirement unless HOSTED_IDENTITY_MODE=hosted-controller" >&2
    exit 2
  fi
  hosted_identity_requester_kubeconfig="${HOSTED_IDENTITY_REQUESTER_KUBECONFIG:-}"
  if [[ -z "$hosted_identity_requester_kubeconfig" ]] ||
    [[ ! -r "$hosted_identity_requester_kubeconfig" ]]; then
    echo "HOSTED_IDENTITY_REQUESTER_KUBECONFIG must name a readable requester kubeconfig" >&2
    exit 2
  fi
fi

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GITHUB_REPOSITORY is required" >&2
  exit 1
fi

if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required" >&2
  exit 1
fi

eligibility_script="${PREVIEW_ELIGIBILITY_SCRIPT:-${script_dir}/preview-eligibility.py}"
identity_request_script="${HOSTED_IDENTITY_REQUEST_SCRIPT:-${script_dir}/../shared/request-hosted-identity.sh}"
identity_wait_script="${HOSTED_IDENTITY_WAIT_SCRIPT:-${script_dir}/wait-for-hosted-identity.sh}"

evaluate_retention_eligibility() {
  local subject="$1"
  local pr_number="$2"
  local pr_metadata metadata_without_tabs metadata_tab_count
  local pr_state pr_base_ref pr_author pr_labels_base64 pr_labels_json eligibility_output

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
    echo "Keeping ${subject}: PR #${pr_number} metadata is unavailable or malformed"
    return 1
  fi
  metadata_without_tabs="${pr_metadata//$'\t'/}"
  metadata_tab_count=$((${#pr_metadata} - ${#metadata_without_tabs}))
  if [[ "$pr_metadata" == *$'\n'* ]] || (( metadata_tab_count != 3 )); then
    echo "Keeping ${subject}: PR #${pr_number} metadata transport is not one four-field record"
    return 1
  fi
  IFS=$'\t' read -r pr_state pr_base_ref pr_author pr_labels_base64 <<<"$pr_metadata"
  if [[ -z "$pr_state" || -z "$pr_base_ref" || -z "$pr_author" || -z "$pr_labels_base64" ]]; then
    echo "Keeping ${subject}: PR #${pr_number} metadata transport is incomplete"
    return 1
  fi
  if ! pr_labels_json="$(printf '%s' "$pr_labels_base64" | base64 --decode 2>/dev/null)"; then
    echo "Keeping ${subject}: PR #${pr_number} label transport is malformed"
    return 1
  fi
  if ! eligibility_output="$(
    python3 "$eligibility_script" \
      --operation retain \
      --state "$pr_state" \
      --base-ref "$pr_base_ref" \
      --author "$pr_author" \
      --labels-json "$pr_labels_json"
  )"; then
    echo "Keeping ${subject}: PR #${pr_number} eligibility could not be evaluated"
    return 1
  fi
  if [[ "$eligibility_output" != *$'\n'* ]]; then
    echo "Keeping ${subject}: PR #${pr_number} eligibility result is malformed"
    return 1
  fi
  eligibility_first_line="${eligibility_output%%$'\n'*}"
  eligibility_second_line="${eligibility_output#*$'\n'}"
  if [[ "$eligibility_second_line" == *$'\n'* ]] ||
    [[ "$eligibility_first_line" != "eligible=true" && "$eligibility_first_line" != "eligible=false" ]] ||
    [[ "$eligibility_second_line" != reason=?* ]]; then
    echo "Keeping ${subject}: PR #${pr_number} eligibility result is malformed"
    return 1
  fi
  eligible="${eligibility_first_line#eligible=}"
  reason="${eligibility_second_line#reason=}"
}

retire_hosted_identity() {
  local identity_name="$1"
  local identity_json runtime_lookup

  # Runtime deletion above must complete first. The requester credential is
  # deliberately used only for the retained identity API and never for runtime
  # namespace cleanup.
  if ! runtime_lookup="$(
    kubectl get namespace "$identity_name" --ignore-not-found -o name
  )"; then
    echo "Unable to verify that runtime namespace ${identity_name} is absent; refusing identity retirement." >&2
    return 1
  fi
  if [[ -n "$runtime_lookup" ]]; then
    if [[ "$runtime_lookup" != "namespace/${identity_name}" ]]; then
      echo "Runtime namespace ${identity_name} absence check returned an unexpected identity; refusing retirement." >&2
    else
      echo "Runtime namespace ${identity_name} still exists; refusing identity retirement." >&2
    fi
    return 1
  fi

  if ! identity_json="$(
    KUBECONFIG="$hosted_identity_requester_kubeconfig" \
      kubectl -n firemud-system get hostedenvironmentidentity "$identity_name" \
        --ignore-not-found -o json
  )"; then
    echo "Unable to determine whether HostedEnvironmentIdentity/${identity_name} exists; refusing retirement." >&2
    return 1
  fi
  if [[ -z "$identity_json" ]]; then
    echo "HostedEnvironmentIdentity/${identity_name} is already absent; no retirement required."
    return 0
  fi
  if ! jq -e \
      --arg identity_name "$identity_name" \
      '(.apiVersion == "platform.firemud.dev/v1alpha1") and
       (.kind == "HostedEnvironmentIdentity") and
       (.metadata.namespace == "firemud-system") and
       (.metadata.name == $identity_name)' \
      <<<"$identity_json" >/dev/null; then
    echo "HostedEnvironmentIdentity/${identity_name} lookup returned an unexpected object; refusing retirement." >&2
    return 1
  fi

  if ! KUBECONFIG="$hosted_identity_requester_kubeconfig" \
    bash "$identity_request_script" "$identity_name" Retired; then
    return 1
  fi
  if ! KUBECONFIG="$hosted_identity_requester_kubeconfig" \
    bash "$identity_wait_script" --retired "$identity_name" 600; then
    return 1
  fi
  if ! KUBECONFIG="$hosted_identity_requester_kubeconfig" \
    kubectl -n firemud-system delete hostedenvironmentidentity "$identity_name" \
      --ignore-not-found --wait=true --timeout=180s; then
    return 1
  fi
}

recover_stranded_hosted_identities() {
  local identity_list_json candidate_rows candidate_row identity_json identity_name phase pr_number

  if ! identity_list_json="$(
    kubectl -n firemud-system get hostedenvironmentidentities.platform.firemud.dev -o json
  )"; then
    echo "Unable to list HostedEnvironmentIdentity recovery candidates; retirement recovery is incomplete." >&2
    return 1
  fi
  if ! candidate_rows="$(
    jq -r '
      if .apiVersion != "platform.firemud.dev/v1alpha1" or
         .kind != "HostedEnvironmentIdentityList" or
         (.items | type) != "array" then
        error("unexpected HostedEnvironmentIdentity list")
      else
        .items[]
        | select(.status.phase == "RuntimeAbsent" or
                 .status.phase == "Retiring" or
                 .status.phase == "Retired")
        | @base64
      end
    ' <<<"$identity_list_json"
  )"; then
    echo "HostedEnvironmentIdentity recovery candidates were malformed; retirement recovery is incomplete." >&2
    return 1
  fi

  while IFS= read -r candidate_row; do
    [[ -n "$candidate_row" ]] || continue
    if ! identity_json="$(printf '%s' "$candidate_row" | base64 --decode 2>/dev/null)" ||
      ! identity_name="$(
        jq -er '
          select(.apiVersion == "platform.firemud.dev/v1alpha1" and
                 .kind == "HostedEnvironmentIdentity" and
                 .metadata.namespace == "firemud-system" and
                 (.metadata.name | type) == "string" and
                 (.spec.desiredState == "Active" or .spec.desiredState == "Retired") and
                 (.status.phase == "RuntimeAbsent" or
                  .status.phase == "Retiring" or
                  .status.phase == "Retired"))
          | .metadata.name
        ' <<<"$identity_json"
      )"; then
      echo "Keeping malformed HostedEnvironmentIdentity recovery candidate." >&2
      continue
    fi
    if [[ ! "$identity_name" =~ ^pr-([1-9][0-9]*)$ ]]; then
      echo "Keeping HostedEnvironmentIdentity/${identity_name}: identity is not a canonical PR preview"
      continue
    fi
    if [[ -n "${runtime_names_seen[$identity_name]:-}" ]]; then
      continue
    fi
    pr_number="${BASH_REMATCH[1]}"
    phase="$(jq -r '.status.phase' <<<"$identity_json")"
    if ! evaluate_retention_eligibility "HostedEnvironmentIdentity/${identity_name}" "$pr_number"; then
      continue
    fi
    if [[ "$eligible" == true ]]; then
      echo "Keeping HostedEnvironmentIdentity/${identity_name}: PR #${pr_number} remains preview-eligible"
      continue
    fi
    case "$reason" in
      dependency-bot | unsupported-base-branch | pr-not-open)
        ;;
      *)
        echo "Keeping HostedEnvironmentIdentity/${identity_name}: PR #${pr_number} eligibility reason is not authoritative for retirement (reason=${reason})"
        continue
        ;;
    esac

    echo "Recovering HostedEnvironmentIdentity/${identity_name} from phase ${phase}: PR #${pr_number} is not preview-eligible (reason=${reason})"
    if ! retire_hosted_identity "$identity_name"; then
      retirement_failures=$((retirement_failures + 1))
    fi
  done <<<"$candidate_rows"
}

if ! namespace_rows_output="$(
  kubectl get namespaces -l firemud.dev/preview=true \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.firemud\.dev/pr-number}{"\n"}{end}'
)"; then
  echo "Unable to list current preview namespaces; refusing stale cleanup" >&2
  exit 1
fi
mapfile -t namespace_rows < <(printf '%s\n' "$namespace_rows_output" | sed '/^$/d')

if (( ${#namespace_rows[@]} == 0 )); then
  echo "No preview namespaces found."
fi

runtime_deletion_failures=0
retirement_failures=0
declare -A runtime_names_seen=()
for row in "${namespace_rows[@]}"; do
  namespace="${row%%$'\t'*}"
  pr_number="${row#*$'\t'}"
  release_name="$namespace"

  if [[ ! "$namespace" =~ ^pr-([1-9][0-9]*)$ ]]; then
    echo "Keeping ${namespace}: namespace is not a canonical PR preview runtime"
    continue
  fi
  namespace_pr_number="${BASH_REMATCH[1]}"
  if [[ ! "$pr_number" =~ ^[1-9][0-9]*$ ]] || [[ "$pr_number" != "$namespace_pr_number" ]]; then
    echo "Keeping ${namespace}: firemud.dev/pr-number label does not match the namespace"
    continue
  fi
  runtime_names_seen["$namespace"]=1

  if ! evaluate_retention_eligibility "$namespace" "$pr_number"; then
    continue
  fi

  if [[ "$eligible" == "true" ]]; then
    echo "Keeping ${namespace}: PR #${pr_number} remains preview-eligible"
    continue
  fi

  case "$reason" in
    dependency-bot | unsupported-base-branch | pr-not-open)
      ;;
    *)
      echo "Keeping ${namespace}: PR #${pr_number} eligibility reason is not authoritative for pruning (reason=${reason})"
      continue
      ;;
  esac

  echo "Pruning ${namespace}: PR #${pr_number} is not preview-eligible (reason=${reason})"
  if [[ "$apply" == true ]]; then
    if ! bash "$delete_script" "$namespace" "$release_name"; then
      runtime_deletion_failures=$((runtime_deletion_failures + 1))
      echo "Hosted runtime deletion failed for ${namespace}; skipping identity retirement and continuing stale cleanup." >&2
      continue
    fi
    if [[ "$retire_terminal_identities" == true ]]; then
      if ! retire_hosted_identity "$namespace"; then
        retirement_failures=$((retirement_failures + 1))
      fi
    fi
  fi
done

if [[ "$retire_terminal_identities" == true ]]; then
  if ! recover_stranded_hosted_identities; then
    retirement_failures=$((retirement_failures + 1))
  fi
fi

if (( runtime_deletion_failures > 0 || retirement_failures > 0 )); then
  echo "${runtime_deletion_failures} hosted runtime deletion(s) and ${retirement_failures} hosted identity retirement(s) failed; stale cleanup is incomplete." >&2
  exit 1
fi
