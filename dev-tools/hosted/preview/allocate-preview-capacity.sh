#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <target_namespace> <max_active> <target_pr_number> <target_head_sha>" >&2
  exit 1
fi

target_namespace="$1"
max_active="$2"
target_pr_number="$3"
target_head_sha="$4"
priority_label="preview:priority"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
eligibility_script="${PREVIEW_ELIGIBILITY_SCRIPT:-${script_dir}/preview-eligibility.py}"
delete_script="${PREVIEW_DELETE_SCRIPT:-${script_dir}/../shared/delete-hosted-namespace.sh}"
publish_reclaimed_script="${PREVIEW_RECLAIMED_PUBLISH_SCRIPT:-${script_dir}/publish-preview-reclaimed.sh}"
publish_attempts="${PREVIEW_RECLAIM_PUBLISH_ATTEMPTS:-3}"
publish_retry_delay_seconds="${PREVIEW_RECLAIM_PUBLISH_RETRY_DELAY_SECONDS:-2}"

if ! [[ "$max_active" =~ ^[0-9]+$ ]]; then
  echo "max_active must be an integer, got: $max_active" >&2
  exit 1
fi
if ! [[ "$publish_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "PREVIEW_RECLAIM_PUBLISH_ATTEMPTS must be a positive integer" >&2
  exit 1
fi
if ! [[ "$publish_retry_delay_seconds" =~ ^[0-9]+$ ]]; then
  echo "PREVIEW_RECLAIM_PUBLISH_RETRY_DELAY_SECONDS must be a non-negative integer" >&2
  exit 1
fi
if ! [[ "$target_pr_number" =~ ^[1-9][0-9]*$ ]] || [[ "$target_namespace" != "pr-${target_pr_number}" ]]; then
  echo "target namespace and PR number do not match: ${target_namespace}, ${target_pr_number}" >&2
  exit 1
fi
if [[ -z "${GITHUB_REPOSITORY:-}" || -z "${GH_TOKEN:-}" ]]; then
  echo "GITHUB_REPOSITORY and GH_TOKEN are required" >&2
  exit 1
fi

emit_output() {
  local name="$1"
  local value="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$name" "$value" >> "$GITHUB_OUTPUT"
  fi
}

get_pr_state() {
  local pr_number="$1"
  gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" \
    --jq '[.state, .head.sha, (.labels | map(.name) | any(. == "preview:priority"))] | @tsv'
}

find_unsatisfied_priority_pr() {
  local priority_rows
  local pr_number
  local head_sha
  local head_repository
  local pr_author
  local pr_base_ref
  local pr_state
  local eligibility_output
  local eligible
  local namespace
  local namespace_owner
  local namespace_head

  if ! priority_rows="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls?state=open&per_page=100" \
    --jq '.[] | select(.labels | map(.name) | any(. == "preview:priority")) | [.number, .head.sha, .head.repo.full_name, .user.login, .base.ref, .state] | @tsv')"; then
    echo "Unable to query current priority pull requests" >&2
    return 1
  fi
  while IFS=$'\t' read -r pr_number head_sha head_repository pr_author pr_base_ref pr_state; do
    if [[ -z "$pr_number" ]]; then
      continue
    fi
    if [[ "$head_repository" != "$GITHUB_REPOSITORY" ]]; then
      continue
    fi
    if ! eligibility_output="$(python3 "$eligibility_script" \
      --operation deploy \
      --state "$pr_state" \
      --base-ref "$pr_base_ref" \
      --author "$pr_author")"; then
      echo "Unable to evaluate preview eligibility for priority PR #${pr_number}" >&2
      return 1
    fi
    eligible="$(sed -n 's/^eligible=//p' <<<"$eligibility_output")"
    if [[ "$eligible" != "true" && "$eligible" != "false" ]]; then
      echo "Invalid preview eligibility result for priority PR #${pr_number}" >&2
      return 1
    fi
    if [[ "$eligible" != "true" ]]; then
      continue
    fi
    namespace="pr-${pr_number}"
    namespace_owner="$(kubectl get namespace "$namespace" -o jsonpath='{.metadata.labels.firemud\.dev/pr-number}' 2>/dev/null || true)"
    namespace_head="$(kubectl get namespace "$namespace" -o jsonpath='{.metadata.annotations.firemud\.dev/last-preview-head-sha}' 2>/dev/null || true)"
    if [[ "$namespace_owner" != "$pr_number" || "$namespace_head" != "$head_sha" ]]; then
      printf '%s\n' "$pr_number"
      return
    fi
  done <<<"$priority_rows"
}

mapfile -t namespace_rows < <(
  kubectl get namespaces -l firemud.dev/preview=true \
    -o jsonpath='{range .items[*]}{.metadata.creationTimestamp}{"|"}{.metadata.name}{"|"}{.metadata.labels.firemud\.dev/pr-number}{"|"}{.metadata.annotations.firemud\.dev/preview-allocated-at}{"|"}{.metadata.annotations.firemud\.dev/last-preview-head-sha}{"|"}{.metadata.annotations.firemud\.dev/last-preview-image-tag}{"\n"}{end}' \
    | sed '/^$/d'
)

active_count=0
target_exists=false
target_allocation_timestamp=""
candidate_rows=()
for row in "${namespace_rows[@]}"; do
  IFS='|' read -r created_at namespace pr_number allocated_at previous_head_sha previous_image_tag <<<"$row"
  allocation_timestamp="${allocated_at:-$created_at}"
  if [[ "$namespace" == "$target_namespace" ]]; then
    if [[ "$pr_number" != "$target_pr_number" ]]; then
      echo "Target namespace ${target_namespace} has unexpected PR ownership label ${pr_number}" >&2
      exit 1
    fi
    target_exists=true
    target_allocation_timestamp="$allocation_timestamp"
    continue
  fi
  active_count=$((active_count + 1))
  if ! [[ "$pr_number" =~ ^[1-9][0-9]*$ ]] || [[ "$namespace" != "pr-${pr_number}" ]]; then
    echo "Skipping ${namespace}: namespace and PR ownership label are not canonical" >&2
    continue
  fi
  candidate_rows+=("${allocation_timestamp}|${namespace}|${pr_number}|${previous_head_sha}|${previous_image_tag}")
done

if [[ -z "$target_allocation_timestamp" ]]; then
  target_allocation_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
fi
emit_output allocation_timestamp "$target_allocation_timestamp"
emit_output reclaimed_pr ""

echo "Active preview namespaces excluding ${target_namespace}: ${active_count}"
echo "Configured preview capacity limit: ${max_active}"
target_metadata="$(get_pr_state "$target_pr_number")"
IFS=$'\t' read -r target_state current_target_head target_is_priority <<<"$target_metadata"
if [[ "$target_state" != "open" || "$current_target_head" != "$target_head_sha" ]]; then
  echo "Refusing capacity action for stale target PR #${target_pr_number}" >&2
  exit 1
fi
if [[ "$target_is_priority" != "true" ]]; then
  if ! unsatisfied_priority_pr="$(find_unsatisfied_priority_pr)"; then
    echo "Refusing ordinary allocation because priority intent could not be evaluated" >&2
    exit 1
  fi
  if [[ -n "$unsatisfied_priority_pr" ]]; then
    echo "Yielding ordinary PR #${target_pr_number}: priority PR #${unsatisfied_priority_pr} has no current preview" >&2
    exit 1
  fi
fi
if [[ "$target_exists" == "true" ]]; then
  exit 0
fi
if (( active_count < max_active )); then
  exit 0
fi
if (( active_count > max_active )); then
  echo "Preview pool is over capacity (${active_count}/${max_active}); one reclaim cannot allocate ${target_namespace}" >&2
  exit 1
fi

if [[ "$target_is_priority" != "true" ]]; then
  echo "Preview capacity exhausted; PR #${target_pr_number} does not have ${priority_label}" >&2
  exit 1
fi

sorted_candidates=()
if (( ${#candidate_rows[@]} > 0 )); then
  mapfile -t sorted_candidates < <(printf '%s\n' "${candidate_rows[@]}" | sort)
fi
selected=""
for row in "${sorted_candidates[@]}"; do
  IFS='|' read -r allocated_at namespace pr_number previous_head_sha previous_image_tag <<<"$row"
  if ! candidate_metadata="$(get_pr_state "$pr_number" 2>/dev/null)"; then
    echo "Skipping ${namespace}: PR #${pr_number} metadata is unavailable"
    continue
  fi
  IFS=$'\t' read -r candidate_state _ candidate_is_priority <<<"$candidate_metadata"
  if [[ "$candidate_state" != "open" || "$candidate_is_priority" == "true" ]]; then
    continue
  fi
  selected="$row"
  break
done

if [[ -z "$selected" ]]; then
  echo "Preview capacity exhausted; every reclaimable slot is priority-protected" >&2
  exit 1
fi

IFS='|' read -r selected_allocated_at selected_namespace selected_pr selected_head selected_image <<<"$selected"

previous_comment_id="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/issues/${selected_pr}/comments" \
  --jq '.[] | select(.user.login == "github-actions[bot]" and ((.body | contains("<!-- firemud-preview-summary -->")) or (.body | startswith("### Preview Summary")))) | .id' \
  | tail -n 1)"
previous_summary=""
if [[ -n "$previous_comment_id" ]]; then
  previous_summary="$(gh api "repos/${GITHUB_REPOSITORY}/issues/comments/${previous_comment_id}" --jq '.body')"
fi

publish_reclaim_state() {
  local phase="$1"
  local attempt

  for ((attempt = 1; attempt <= publish_attempts; attempt++)); do
    if bash "$publish_reclaimed_script" \
      "$selected_pr" \
      "$target_pr_number" \
      "$selected_head" \
      "$selected_image" \
      "${selected_namespace}.preview.firedevops.net" \
      "$previous_summary" \
      "$phase"; then
      return 0
    fi
    if (( attempt < publish_attempts )); then
      echo "Retrying ${phase} preview status publication (${attempt}/${publish_attempts})" >&2
      sleep "$publish_retry_delay_seconds"
    fi
  done
  return 1
}

if ! publish_reclaim_state reclaiming; then
  echo "Refusing reclaim because the conservative victim status could not be published" >&2
  exit 1
fi

# Re-read both GitHub label states and Kubernetes ownership immediately before
# deletion. The job-level lifecycle lock prevents another managed preview
# deploy, proof, or cleanup from racing this destructive boundary.
revalidation_failure=""
if target_metadata="$(get_pr_state "$target_pr_number")"; then
  IFS=$'\t' read -r target_state current_target_head target_is_priority <<<"$target_metadata"
  if [[ "$target_state" != "open" || "$current_target_head" != "$target_head_sha" || "$target_is_priority" != "true" ]]; then
    revalidation_failure="target PR #${target_pr_number} is no longer the current priority target"
  fi
else
  revalidation_failure="target PR #${target_pr_number} state could not be revalidated"
fi

if candidate_metadata="$(get_pr_state "$selected_pr")"; then
  IFS=$'\t' read -r candidate_state _ candidate_is_priority <<<"$candidate_metadata"
  if [[ -z "$revalidation_failure" && ( "$candidate_state" != "open" || "$candidate_is_priority" == "true" ) ]]; then
    revalidation_failure="candidate PR #${selected_pr} is no longer an ordinary open PR"
  fi
elif [[ -z "$revalidation_failure" ]]; then
  revalidation_failure="candidate PR #${selected_pr} state could not be revalidated"
fi

namespace_state_available=true
if ! current_owner="$(kubectl get namespace "$selected_namespace" -o jsonpath='{.metadata.labels.firemud\.dev/pr-number}')"; then
  namespace_state_available=false
  current_owner=""
fi
if ! current_created_at="$(kubectl get namespace "$selected_namespace" -o jsonpath='{.metadata.creationTimestamp}')"; then
  namespace_state_available=false
  current_created_at=""
fi
if ! current_allocated_at="$(kubectl get namespace "$selected_namespace" -o jsonpath='{.metadata.annotations.firemud\.dev/preview-allocated-at}')"; then
  namespace_state_available=false
  current_allocated_at=""
fi
if ! current_head="$(kubectl get namespace "$selected_namespace" -o jsonpath='{.metadata.annotations.firemud\.dev/last-preview-head-sha}')"; then
  namespace_state_available=false
  current_head=""
fi
if ! current_image="$(kubectl get namespace "$selected_namespace" -o jsonpath='{.metadata.annotations.firemud\.dev/last-preview-image-tag}')"; then
  namespace_state_available=false
  current_image=""
fi
current_effective_allocated_at="${current_allocated_at:-$current_created_at}"
namespace_intact=false
if [[ "$namespace_state_available" == "true" &&
  "$current_owner" == "$selected_pr" &&
  "$current_effective_allocated_at" == "$selected_allocated_at" &&
  "$current_head" == "$selected_head" &&
  "$current_image" == "$selected_image" ]]; then
  namespace_intact=true
fi
if [[ -z "$revalidation_failure" && "$namespace_intact" != "true" ]]; then
  revalidation_failure="namespace ${selected_namespace} identity or allocation changed"
fi

if [[ -n "$revalidation_failure" ]]; then
  if [[ "$namespace_intact" == "true" ]]; then
    if ! publish_reclaim_state retained; then
      echo "Unable to publish retained status; conservative unavailable status remains" >&2
    fi
  fi
  echo "Refusing reclaim because ${revalidation_failure}" >&2
  exit 1
fi

echo "Reclaiming oldest ordinary preview ${selected_namespace} for priority PR #${target_pr_number}"
if ! bash "$delete_script" "$selected_namespace" "$selected_namespace"; then
  echo "Preview deletion failed; retaining the conservative unavailable status because intact Ready state cannot be proven" >&2
  exit 1
fi
if ! publish_reclaim_state reclaimed; then
  echo "Final reclaimed status publication failed; the conservative unavailable status remains in place" >&2
fi

emit_output reclaimed_pr "$selected_pr"
emit_output reclaimed_namespace "$selected_namespace"
