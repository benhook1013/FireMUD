#!/usr/bin/env bash
set -euo pipefail
preview_workflow_name="PR Preview Environment"
eligibility_script="./dev-tools/hosted/preview/preview-eligibility.py"
max_active="${PREVIEW_MAX_ACTIVE:-2}"
preview_handoff_window_seconds=600

parse_namespace_tuple() {
  jq -er '
    def annotation($annotations; $name):
      ($annotations[$name] // "")
      | if type != "string" then
          error("namespace preview annotation is not a string")
        elif test("[|\\r\\n]") then
          error("namespace preview annotation contains an unsafe delimiter")
        else
          .
        end;
    def metadata_field($metadata; $name):
      ($metadata[$name] // "")
      | if type != "string" then
          error("namespace metadata field is not a string")
        elif test("[|\\r\\n]") then
          error("namespace metadata field contains an unsafe delimiter")
        else
          .
        end;
    if type != "object" or (.metadata | type) != "object" then
      error("namespace snapshot is not an object with metadata")
    elif ((.metadata.annotations // {}) | type) != "object" then
      error("namespace annotations are not an object")
    else
      (.metadata.annotations // {}) as $annotations
      | [
          annotation($annotations; "firemud.dev/last-preview-base-sha"),
          annotation($annotations; "firemud.dev/last-preview-head-sha"),
          annotation($annotations; "firemud.dev/last-preview-merge-sha"),
          annotation($annotations; "firemud.dev/last-preview-image-tag"),
          annotation($annotations; "firemud.dev/requested-preview-base-sha"),
          annotation($annotations; "firemud.dev/requested-preview-head-sha"),
          annotation($annotations; "firemud.dev/requested-preview-merge-sha"),
          annotation($annotations; "firemud.dev/requested-preview-image-tag"),
          annotation($annotations; "firemud.dev/proof-preview-base-sha"),
          annotation($annotations; "firemud.dev/proof-preview-head-sha"),
          annotation($annotations; "firemud.dev/proof-preview-merge-sha"),
          annotation($annotations; "firemud.dev/proof-preview-image-tag"),
          annotation($annotations; "firemud.dev/proof-preview-namespace-uid"),
          annotation($annotations; "firemud.dev/proof-retry-count"),
          annotation($annotations; "firemud.dev/proof-retry-base-sha"),
          annotation($annotations; "firemud.dev/proof-retry-head-sha"),
          annotation($annotations; "firemud.dev/proof-retry-merge-sha"),
          annotation($annotations; "firemud.dev/proof-retry-image-tag"),
          metadata_field(.metadata; "uid"),
          metadata_field(.metadata; "resourceVersion")
        ]
      | if (
          (.[13] == "" and .[14] == "" and .[15] == "" and .[16] == "" and .[17] == "")
          or (
            (.[13] | test("^[0-3]$"))
            and (.[14] | test("^[0-9a-fA-F]{40}$"))
            and (.[15] | test("^[0-9a-fA-F]{40}$"))
            and (.[16] | test("^[0-9a-fA-F]{40}$"))
            and (.[17] | test("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$"))
          )
        ) then . else error("namespace proof-retry evidence is malformed") end
      | join("|")
    end
  ' <<<"$1"
}

readonly repair_success=0
readonly repair_skip=10
readonly repair_dispatch=11
readonly repair_abort=20

repair_requested_tuple() {
  local namespace="$1"
  local pr_number="$2"
  local base_sha="$3"
  local head_sha="$4"
  local merge_sha="$5"
  local image_tag="$6"
  local requested_base_sha="$7"
  local requested_head_sha="$8"
  local requested_merge_sha="$9"
  local requested_image_tag="${10}"
  local fresh_namespace_json
  local fresh_namespace_tuple
  local fresh_last_base_sha
  local fresh_last_head_sha
  local fresh_last_merge_sha
  local fresh_last_image_tag
  local fresh_requested_base_sha
  local fresh_requested_head_sha
  local fresh_requested_merge_sha
  local fresh_requested_image_tag
  local fresh_proof_base_sha
  local fresh_proof_head_sha
  local fresh_proof_merge_sha
  local fresh_proof_image_tag
  local fresh_proof_namespace_uid
  local fresh_namespace_uid
  local fresh_namespace_resource_version
  local annotation_failure_namespace_json

  if ! fresh_namespace_json="$(
    kubectl get namespace "${namespace}" --ignore-not-found -o json
  )"; then
    echo "Unable to recheck namespace ${namespace} before annotation repair." >&2
    return "$repair_abort"
  fi
  if [[ -z "${fresh_namespace_json}" ]]; then
    return "$repair_dispatch"
  fi
  if ! fresh_namespace_tuple="$(parse_namespace_tuple "${fresh_namespace_json}")"; then
    echo "Skipping PR #${pr_number}: unable to parse namespace ${namespace} recheck." >&2
    return "$repair_skip"
  fi
  IFS='|' read -r fresh_last_base_sha fresh_last_head_sha fresh_last_merge_sha fresh_last_image_tag \
    fresh_requested_base_sha fresh_requested_head_sha fresh_requested_merge_sha fresh_requested_image_tag \
    fresh_proof_base_sha fresh_proof_head_sha fresh_proof_merge_sha fresh_proof_image_tag \
    fresh_proof_namespace_uid _ _ _ _ _ \
    fresh_namespace_uid \
    fresh_namespace_resource_version <<<"${fresh_namespace_tuple}"
  if [[ "${fresh_last_base_sha}" != "${base_sha}" ||
    "${fresh_last_head_sha}" != "${head_sha}" ||
    "${fresh_last_merge_sha}" != "${merge_sha}" ||
    "${fresh_last_image_tag}" != "${image_tag}" ]]; then
    echo "Skipping preview repair for PR #${pr_number}: deployed preview tuple changed during annotation repair check."
    return "$repair_skip"
  fi
  if [[ "${fresh_proof_base_sha}" != "${base_sha}" ||
    "${fresh_proof_head_sha}" != "${head_sha}" ||
    "${fresh_proof_merge_sha}" != "${merge_sha}" ||
    "${fresh_proof_image_tag}" != "${image_tag}" ||
    -z "${fresh_namespace_uid}" ||
    "${fresh_proof_namespace_uid}" != "${fresh_namespace_uid}" ]]; then
    echo "Preview ${namespace} deployed tuple is missing proof for ${base_sha}/${head_sha}/${merge_sha}/${image_tag}; dispatching retry."
    return "$repair_dispatch"
  fi
  if [[ "${fresh_requested_base_sha}" == "${base_sha}" &&
    "${fresh_requested_head_sha}" == "${head_sha}" &&
    "${fresh_requested_merge_sha}" == "${merge_sha}" &&
    "${fresh_requested_image_tag}" == "${image_tag}" ]]; then
    echo "Preview ${namespace} already aligned to ${base_sha}/${head_sha}/${merge_sha}/${image_tag}"
    return "$repair_success"
  fi
  if [[ "${fresh_requested_base_sha}" != "${requested_base_sha}" ||
    "${fresh_requested_head_sha}" != "${requested_head_sha}" ||
    "${fresh_requested_merge_sha}" != "${requested_merge_sha}" ||
    "${fresh_requested_image_tag}" != "${requested_image_tag}" ]]; then
    echo "Skipping preview repair for PR #${pr_number}: requested preview tuple changed during annotation repair check."
    return "$repair_skip"
  fi
  if ! kubectl annotate namespace "${namespace}" \
    "firemud.dev/requested-preview-base-sha=${base_sha}" \
    "firemud.dev/requested-preview-head-sha=${head_sha}" \
    "firemud.dev/requested-preview-merge-sha=${merge_sha}" \
    "firemud.dev/requested-preview-image-tag=${image_tag}" \
    --overwrite \
    --resource-version "${fresh_namespace_resource_version}" >/dev/null; then
    if ! annotation_failure_namespace_json="$(
      kubectl get namespace "${namespace}" --ignore-not-found -o json
    )"; then
      echo "Unable to recheck namespace ${namespace} after annotation failure; refusing preview reconcile." >&2
      return "$repair_abort"
    fi
    if [[ -n "${annotation_failure_namespace_json}" ]]; then
      echo "Skipping PR #${pr_number}: unable to annotate namespace ${namespace}; namespace still exists." >&2
      return "$repair_skip"
    fi
    echo "Namespace ${namespace} was deleted during requested-head repair; dispatching replacement."
    return "$repair_dispatch"
  fi
  if [[ -z "${requested_base_sha}${requested_head_sha}${requested_merge_sha}${requested_image_tag}" ]]; then
    echo "Repaired missing requested preview tuple for ${namespace}"
  else
    echo "Repaired stale requested preview tuple for ${namespace}"
  fi
  return "$repair_success"
}

prepare_preview_dispatch() {
  local namespace="$1"
  local pr_number="$2"
  local base_sha="$3"
  local head_sha="$4"
  local merge_sha="$5"
  local image_tag="$6"
  local require_deployed_tuple="$7"
  local priority="$8"
  local expected_namespace_uid="$9"
  local fresh_namespace_json
  local fresh_namespace_tuple
  local current_base_sha
  local current_head_sha
  local current_merge_sha
  local current_image_tag
  local requested_base_sha
  local requested_head_sha
  local requested_merge_sha
  local requested_image_tag
  local proof_base_sha
  local proof_head_sha
  local proof_merge_sha
  local proof_image_tag
  local proof_namespace_uid
  local retry_count
  local retry_base_sha
  local retry_head_sha
  local retry_merge_sha
  local retry_image_tag
  local namespace_uid
  local namespace_resource_version
  local next_retry_count
  local reservation_namespace_json

  proof_retry_reservation_count=""
  proof_retry_previous_count=""
  proof_retry_previous_base_sha=""
  proof_retry_previous_head_sha=""
  proof_retry_previous_merge_sha=""
  proof_retry_previous_image_tag=""
  proof_retry_reservation_namespace_uid=""
  proof_retry_reservation_resource_version=""
  proof_retry_reservation_base_sha=""
  proof_retry_reservation_head_sha=""
  proof_retry_reservation_merge_sha=""
  proof_retry_reservation_image_tag=""

  if ! fresh_namespace_json="$(
    kubectl get namespace "${namespace}" --ignore-not-found -o json
  )"; then
    echo "Unable to recheck namespace ${namespace} before preview dispatch." >&2
    return 1
  fi
  if [[ -z "${fresh_namespace_json}" ]]; then
    if [[ "$require_deployed_tuple" == true ]]; then
      echo "Skipping PR #${pr_number}: namespace ${namespace} disappeared before proof retry dispatch." >&2
      return 1
    fi
    if [[ "$priority" != true && "$available_slots" -le 0 ]]; then
      if ! refresh_available_slots; then
        echo "Unable to refresh preview capacity for ordinary PR #${pr_number}; refusing dispatch." >&2
        dispatch_outcome_failed=true
        return 1
      fi
      if [[ "$available_slots" -le 0 ]]; then
        echo "Skipping ordinary PR #${pr_number}: preview capacity is still full after refresh."
        return 1
      fi
    fi
    return 0
  fi
  if ! fresh_namespace_tuple="$(parse_namespace_tuple "${fresh_namespace_json}")"; then
    echo "Skipping PR #${pr_number}: malformed namespace proof-retry evidence; refusing dispatch." >&2
    return 1
  fi
  IFS='|' read -r current_base_sha current_head_sha current_merge_sha current_image_tag \
    requested_base_sha requested_head_sha requested_merge_sha requested_image_tag \
    proof_base_sha proof_head_sha proof_merge_sha proof_image_tag proof_namespace_uid \
    retry_count retry_base_sha retry_head_sha retry_merge_sha retry_image_tag \
    namespace_uid namespace_resource_version <<<"${fresh_namespace_tuple}"
  if [[ "$require_deployed_tuple" == true &&
    ( -z "$expected_namespace_uid" || "$namespace_uid" != "$expected_namespace_uid" ) ]]; then
    echo "Skipping PR #${pr_number}: namespace ${namespace} was recreated before proof retry dispatch; refusing to charge its retry budget." >&2
    return 1
  fi
  if [[ "${current_base_sha}" != "${base_sha}" ||
    "${current_head_sha}" != "${head_sha}" ||
    "${current_merge_sha}" != "${merge_sha}" ||
    "${current_image_tag}" != "${image_tag}" ]]; then
    if [[ "$require_deployed_tuple" == true ]]; then
      echo "Skipping PR #${pr_number}: deployed preview tuple changed before proof retry dispatch." >&2
      return 1
    fi
    return 0
  fi
  if [[ "${proof_base_sha}" == "${base_sha}" &&
    "${proof_head_sha}" == "${head_sha}" &&
    "${proof_merge_sha}" == "${merge_sha}" &&
    "${proof_image_tag}" == "${image_tag}" &&
    -n "${namespace_uid}" &&
    "${proof_namespace_uid}" == "${namespace_uid}" ]]; then
    echo "Skipping PR #${pr_number}: proof completed before retry dispatch." >&2
    return 2
  fi
  if [[ "${retry_base_sha}" == "${base_sha}" &&
    "${retry_head_sha}" == "${head_sha}" &&
    "${retry_merge_sha}" == "${merge_sha}" &&
    "${retry_image_tag}" == "${image_tag}" ]]; then
    next_retry_count=$((10#${retry_count} + 1))
  else
    next_retry_count=1
  fi
  if (( next_retry_count > 3 )); then
    echo "Skipping PR #${pr_number}: proof retry budget exhausted for ${base_sha}/${head_sha}/${merge_sha}/${image_tag}." >&2
    return 3
  fi
  if ! reservation_namespace_json="$(kubectl annotate namespace "${namespace}" \
    "firemud.dev/proof-retry-count=${next_retry_count}" \
    "firemud.dev/proof-retry-base-sha=${base_sha}" \
    "firemud.dev/proof-retry-head-sha=${head_sha}" \
    "firemud.dev/proof-retry-merge-sha=${merge_sha}" \
    "firemud.dev/proof-retry-image-tag=${image_tag}" \
    --overwrite \
    --resource-version "${namespace_resource_version}" -o json)"; then
    echo "Skipping PR #${pr_number}: Namespace ${namespace} changed before proof retry evidence could be recorded." >&2
    return 1
  fi
  proof_retry_reservation_count="${next_retry_count}"
  proof_retry_previous_count="${retry_count}"
  proof_retry_previous_base_sha="${retry_base_sha}"
  proof_retry_previous_head_sha="${retry_head_sha}"
  proof_retry_previous_merge_sha="${retry_merge_sha}"
  proof_retry_previous_image_tag="${retry_image_tag}"
  proof_retry_reservation_namespace_uid="${namespace_uid}"
  proof_retry_reservation_resource_version="$(jq -er --arg uid "${namespace_uid}" \
    '.metadata | select(.uid == $uid) | .resourceVersion | select(type == "string" and length > 0)' \
    <<<"${reservation_namespace_json}" 2>/dev/null || true)"
  proof_retry_reservation_base_sha="${base_sha}"
  proof_retry_reservation_head_sha="${head_sha}"
  proof_retry_reservation_merge_sha="${merge_sha}"
  proof_retry_reservation_image_tag="${image_tag}"
  echo "Recorded proof retry ${next_retry_count}/3 for PR #${pr_number} (${base_sha}/${head_sha}/${merge_sha}/${image_tag})."
  return 0
}

compensate_rejected_proof_retry() {
  local namespace="$1"
  local pr_number="$2"
  local fresh_namespace_json
  local fresh_namespace_tuple
  local current_base_sha
  local current_head_sha
  local current_merge_sha
  local current_image_tag
  local retry_count
  local retry_base_sha
  local retry_head_sha
  local retry_merge_sha
  local retry_image_tag
  local namespace_uid
  local namespace_resource_version
  local -a annotations=()

  if [[ -z "${proof_retry_reservation_count}" ]]; then
    return 0
  fi
  if ! fresh_namespace_json="$(kubectl get namespace "${namespace}" --ignore-not-found -o json)"; then
    echo "Unable to verify proof-retry reservation for PR #${pr_number}; leaving its budget charged." >&2
    return 1
  fi
  if [[ -z "${fresh_namespace_json}" ]] ||
    ! fresh_namespace_tuple="$(parse_namespace_tuple "${fresh_namespace_json}")"; then
    echo "Unable to verify proof-retry reservation for PR #${pr_number}; leaving its budget charged." >&2
    return 1
  fi
  IFS='|' read -r current_base_sha current_head_sha current_merge_sha current_image_tag \
    _ _ _ _ _ _ _ _ _ retry_count retry_base_sha retry_head_sha retry_merge_sha retry_image_tag \
    namespace_uid namespace_resource_version <<<"${fresh_namespace_tuple}"
  if [[ "${namespace_uid}" != "${proof_retry_reservation_namespace_uid}" ||
    -z "${proof_retry_reservation_resource_version}" ||
    "${namespace_resource_version}" != "${proof_retry_reservation_resource_version}" ||
    "${current_base_sha}" != "${proof_retry_reservation_base_sha}" ||
    "${current_head_sha}" != "${proof_retry_reservation_head_sha}" ||
    "${current_merge_sha}" != "${proof_retry_reservation_merge_sha}" ||
    "${current_image_tag}" != "${proof_retry_reservation_image_tag}" ||
    "${retry_count}" != "${proof_retry_reservation_count}" ||
    "${retry_base_sha}" != "${proof_retry_reservation_base_sha}" ||
    "${retry_head_sha}" != "${proof_retry_reservation_head_sha}" ||
    "${retry_merge_sha}" != "${proof_retry_reservation_merge_sha}" ||
    "${retry_image_tag}" != "${proof_retry_reservation_image_tag}" ||
    -z "${namespace_resource_version}" ]]; then
    echo "Proof-retry reservation for PR #${pr_number} changed before confirmed-rejection compensation; leaving its budget charged." >&2
    return 1
  fi

  if [[ -n "${proof_retry_previous_count}" ]]; then
    annotations+=(
      "firemud.dev/proof-retry-count=${proof_retry_previous_count}"
      "firemud.dev/proof-retry-base-sha=${proof_retry_previous_base_sha}"
      "firemud.dev/proof-retry-head-sha=${proof_retry_previous_head_sha}"
      "firemud.dev/proof-retry-merge-sha=${proof_retry_previous_merge_sha}"
      "firemud.dev/proof-retry-image-tag=${proof_retry_previous_image_tag}"
    )
  else
    annotations+=(
      firemud.dev/proof-retry-count-
      firemud.dev/proof-retry-base-sha-
      firemud.dev/proof-retry-head-sha-
      firemud.dev/proof-retry-merge-sha-
      firemud.dev/proof-retry-image-tag-
    )
  fi
  if ! kubectl annotate namespace "${namespace}" "${annotations[@]}" \
    --overwrite --resource-version "${namespace_resource_version}" >/dev/null; then
    echo "Unable to release rejected proof-retry reservation for PR #${pr_number}; leaving its budget charged." >&2
    return 1
  fi
  echo "Released proof-retry reservation for PR #${pr_number} after GitHub confirmed dispatch rejection."
}

active_count="$(kubectl get namespaces -l firemud.dev/preview=true -o name | sed '/^$/d' | wc -l)"
available_slots=$((max_active - active_count))
dispatch_outcome_failed=false
refresh_available_slots() {
  local live_namespaces live_count
  if ! live_namespaces="$(kubectl get namespaces -l firemud.dev/preview=true -o name)"; then
    return 1
  fi
  live_count="$(printf '%s\n' "$live_namespaces" | sed '/^$/d' | wc -l)"
  available_slots=$((max_active - live_count))
}
dispatch_sent=false
completed_preview_source_updated_at=""
preview_source_skipped_render() {
  local run_id="$1" attempt="$2" head="$3"
  jq -e --argjson id "$run_id" --argjson attempt "$attempt" --arg head "$head" '
    [.jobs[] | select(.name == "Render Sanitized Preview Artifact")] as $renders
    | ($renders | length) <= 1
      and all($renders[]; .run_id == $id
        and ((has("run_attempt") | not) or .run_attempt == $attempt)
        and .head_sha == $head and .status == "completed" and .conclusion == "skipped")
  '
}

automatic_preview_has_tuple() {
  local run_id="$1" attempt="$2" head="$3" tuple="$4" pr="$5" event="$6" jobs latest jobs_available=true
  local dispatch_tuple="${tuple#Preview resolved tuple }"
  local dispatch_title="Preview dispatch ${dispatch_tuple// /-}"
  # 0: exact active tuple, 1: proven nonmatch, 2: unavailable/unfinished proof.
  jobs="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/attempts/${attempt}/jobs?per_page=100")" || jobs_available=false
  latest="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}")" || return 2
  jq -e '
    type == "object"
    and (.id | type == "number" and . > 0 and . == floor)
    and (.run_attempt | type == "number" and . > 0 and . == floor)
    and (.head_sha | type == "string" and test("^[0-9a-f]{40}$"))
    and (.event | type == "string" and length > 0)
    and (.path | type == "string" and length > 0)
    and (.status | IN("requested", "queued", "in_progress", "waiting", "pending", "completed"))
    and (if .status == "completed" then (.conclusion | type == "string" and length > 0) else true end)
    ' <<<"$latest" >/dev/null || return 2
  if [[ "$event" == repository_dispatch ]]; then
    jq -e --argjson id "$run_id" --arg head "$head" --arg title "$dispatch_title" \
      --arg event "$event" --arg workflow_name "$preview_workflow_name" \
      --argjson workflow_id "$preview_workflow_id" --arg repository "$GITHUB_REPOSITORY" \
      --arg default_branch "$DEFAULT_BRANCH" '
      .id == $id and .head_sha == $head
      and .event == $event and .workflow_id == $workflow_id
      and .repository.full_name == $repository and .head_repository.full_name == $repository
      and .head_branch == $default_branch and .display_title == $title
      and (.name == $workflow_name or .name == .display_title)
      and (.path | IN(".github/workflows/preview.yml",
        ".github/workflows/preview.yml@" + $default_branch,
        ".github/workflows/preview.yml@refs/heads/" + $default_branch))
    ' <<<"$latest" >/dev/null || return 1
  else
    jq -e --argjson id "$run_id" --arg head "$head" --arg pr "$pr" --arg event "$event" \
      --argjson workflow_id "$preview_workflow_id" --arg repository "$GITHUB_REPOSITORY" \
      --arg pr_title "PR preview ${pr}" '
      .id == $id and .head_sha == $head
      and .event == $event and .workflow_id == $workflow_id
      and .repository.full_name == $repository and .head_repository.full_name == $repository
      and ([.pull_requests[]? | select(.number == ($pr | tonumber))] | length) == 1
      and .display_title == $pr_title
      and (.path == ".github/workflows/preview.yml" or ($event == "pull_request" and .path == (".github/workflows/preview.yml@refs/pull/" + $pr + "/merge")))
    ' <<<"$latest" >/dev/null || return 1
  fi
  if jq -e '.status == "completed" and .conclusion != "success"' <<<"$latest" >/dev/null; then return 1; fi
  [[ "$jobs_available" == true ]] || return 2
  jq -e --argjson attempt "$attempt" '.run_attempt == $attempt' <<<"$latest" >/dev/null || return 2
  if [[ "$event" == repository_dispatch ]]; then
    jq -e --argjson id "$run_id" --argjson attempt "$attempt" --arg head "$head" '
      .jobs as $jobs
      | (($jobs | type) == "array") and (.total_count == ($jobs | length)) and (.total_count < 100)
        and all($jobs[]; type == "object" and (.name | type == "string")
          and (.run_id | type == "number" and . > 0 and . == floor)
          and (.head_sha | type == "string" and test("^[0-9a-f]{40}$"))
          and (.status | type == "string") and (.conclusion | type == "string"))
        and ([$jobs[] | select(.name == "Preview Plan")] | length) == 1
        and any($jobs[]; .name == "Preview Plan" and .run_id == $id
          and ((has("run_attempt") | not) or (.run_attempt | type == "number" and . == $attempt))
          and .head_sha == $head and .status == "completed" and .conclusion == "success"
          and (.steps | type == "array")
          and all(.steps[]; type == "object" and (.name | type == "string")
            and (.status | type == "string") and (.conclusion | type == "string"))
          and ([.steps[] | select(.name == "Explain preview skip")] | length) <= 1)
    ' <<<"$jobs" >/dev/null || return 2
    if jq -e '.status == "completed" and .conclusion == "success"' <<<"$latest" >/dev/null &&
      jq -e '
        any(.jobs[] | select(.name == "Preview Plan") | .steps[];
          .name == "Explain preview skip" and .status == "completed" and .conclusion == "success")
      ' <<<"$jobs" >/dev/null; then
      preview_source_skipped_render "$run_id" "$attempt" "$head" <<<"$jobs" >/dev/null || return 2
      # A completed, successful, explicitly explained no-op cannot still hand off a render.
      return 1
    fi
    if ! jq -e --argjson id "$run_id" --argjson attempt "$attempt" --arg head "$head" '
      .jobs as $jobs
      | ([$jobs[] | select(.name == "Render Sanitized Preview Artifact")] | length) == 1
        and any($jobs[]; .name == "Render Sanitized Preview Artifact" and .run_id == $id
          and ((has("run_attempt") | not) or (.run_attempt | type == "number" and . == $attempt))
          and .head_sha == $head and .status == "completed" and .conclusion == "success"
          and (.steps | type == "array")
          and all(.steps[]; type == "object" and (.name | type == "string")
            and (.status | type == "string") and (.conclusion | type == "string"))
          and ([.steps[] | select(.name == "Upload sanitized render artifact")] | length) == 1
          and any(.steps[]; .name == "Upload sanitized render artifact"
            and .status == "completed" and .conclusion == "success"))
    ' <<<"$jobs" >/dev/null; then
      return 2
    fi
    if jq -e '.status == "completed" and .conclusion == "success"' <<<"$latest" >/dev/null; then
      completed_preview_source_updated_at="$(jq -r '.updated_at // empty' <<<"$latest")"
      return 3
    fi
    return 0
  fi
  if [[ "$event" == pull_request_target ]]; then return 2; fi
  jq -e --argjson id "$run_id" --argjson attempt "$attempt" --arg head "$head" '
      .jobs as $jobs
      | (($jobs | type) == "array") and (.total_count == ($jobs | length))
        and (.total_count < 100)
        and ([$jobs[] | select(.name == "Preview Plan")] | length == 1)
        and any($jobs[];
          .name == "Preview Plan" and .run_id == $id
          and ((has("run_attempt") | not) or (.run_attempt | type == "number" and . == $attempt))
          and .head_sha == $head and .status == "completed" and .conclusion == "success"
          and (.steps | type == "array")
          and all(.steps[]; type == "object" and (.name | type == "string")
            and (.status | type == "string") and (.conclusion | type == "string"))
          and ([.steps[] | select(.name | startswith("Preview resolved tuple "))] | length <= 1)
          and ([.steps[] | select(.name | startswith("Preview event tuple "))] | length <= 1)
          and ([.steps[] | select(.name == "Explain preview skip")] | length <= 1)
          and (if ([.steps[] | select(.name | startswith("Preview resolved tuple "))] | length) == 1 then
            any(.steps[]; (.name | test("^Preview resolved tuple pr-[1-9][0-9]* base-[0-9a-f]{40} head-[0-9a-f]{40} merge-[0-9a-f]{40}$"))
              and .status == "completed" and .conclusion == "success")
          else
            any(.steps[]; (.name | test("^Preview event tuple pr-[1-9][0-9]* base-[0-9a-f]{40} head-[0-9a-f]{40} merge-[0-9a-f]{40}$"))
              and .status == "completed" and .conclusion == "success")
          end))
    ' <<<"$jobs" >/dev/null || return 2
  if jq -e '.status == "completed" and .conclusion == "success"' <<<"$latest" >/dev/null &&
    jq -e '
      any(.jobs[] | select(.name == "Preview Plan") | .steps[];
        .name == "Explain preview skip" and .status == "completed" and .conclusion == "success")
    ' <<<"$jobs" >/dev/null; then
    preview_source_skipped_render "$run_id" "$attempt" "$head" <<<"$jobs" >/dev/null || return 2
    return 1
  fi
  if jq -e --arg tuple "$tuple" '
    any(.jobs[] | select(.name == "Preview Plan") | .steps[]; .name == $tuple)
    ' <<<"$jobs" >/dev/null; then
    :
  elif jq -e '
    any(.jobs[] | select(.name == "Preview Plan") | .steps[];
      (.name | test("^Preview resolved tuple pr-[1-9][0-9]* base-[0-9a-f]{40} head-[0-9a-f]{40} merge-[0-9a-f]{40}$"))
      and .status == "completed" and .conclusion == "success")
    ' <<<"$jobs" >/dev/null; then
    return 1
  elif jq -e --arg prefix "Preview event tuple pr-${pr} base-" '
    any(.jobs[] | select(.name == "Preview Plan") | .steps[];
      (.name | startswith($prefix)) and .status == "completed" and .conclusion == "success")
    ' <<<"$jobs" >/dev/null; then
    # An event marker alone cannot establish either a deployable plan or a completed no-op.
    return 2
  else
    return 2
  fi
  jq -e --arg tuple "$tuple" '
    any(.jobs[] | select(.name == "Preview Plan") | .steps[]; .name == $tuple)
    ' <<<"$jobs" >/dev/null || return 1
  if jq -e '.status == "completed" and .conclusion == "success"' <<<"$latest" >/dev/null; then
    completed_preview_source_updated_at="$(jq -r '.updated_at // empty' <<<"$latest")"
    # The trusted consumer may be queued after the active-run inventory.
    # Defer one cycle after an exact successful source completion.
    return 3
  fi
}

completed_preview_inventory() {
  local event="$1" filter="$2" pages
  # The handoff window measures completion (updated_at), not
  # creation. An older run can finish or be rerun just before the scan.
  pages="$(timeout 30s gh api --method GET --paginate --slurp \
    "repos/${GITHUB_REPOSITORY}/actions/workflows/${preview_workflow_id}/runs" \
    -f "event=${event}" -f status=completed -f per_page=100 -f "$filter")" || return 1
  jq -ce --argjson workflow_id "$preview_workflow_id" --arg event "$event" '
    select(type == "array" and length > 0 and length <= 10)
    | .[0].total_count as $count
    | select(($count | type) == "number" and $count >= 0 and $count < 1000
             and $count == ($count | floor))
    | select(all(.[]; type == "object" and .total_count == $count
        and (.workflow_runs | type == "array" and length <= 100)))
    | [.[].workflow_runs[]] as $runs
    | select(($runs | length) == $count
        and ([$runs[].id] | unique | length) == $count
        and all($runs[]; type == "object" and (.id | type == "number" and . > 0 and . == floor)
          and .workflow_id == $workflow_id and (.run_attempt | type == "number" and . > 0 and . == floor)
          and .event == $event and .status == "completed"
          and (.conclusion | type == "string") and (.name | type == "string")
          and (.display_title | type == "string") and (.head_branch | type == "string")
          and (.head_sha | type == "string" and test("^[0-9a-f]{40}$"))
          and (.path | type == "string")
          and (.updated_at | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
          and (.repository.full_name | type == "string") and (.head_repository.full_name | type == "string")
          and (if $event == "pull_request" then (.pull_requests | type == "array") else true end)))
    | {workflow_runs: $runs}
  ' <<<"$pages"
}

recent_successful_source_handoff() {
  local pr_number="$1" head_sha="$2" tuple_step="$3"
  local source_runs dispatch_source_runs source_candidates source_candidate_count source_inventory_now
  local run_id run_attempt run_event run_head run_updated_at lookup_status source_updated_epoch now_epoch source_age
  local consumer_runs consumer_candidates consumer_candidate_count
  local consumer_run_id consumer_attempt consumer_head consumer_status consumer_created_after
  local run_name="Preview dispatch pr-${pr_number}-base-${base_sha}-head-${head_sha}-merge-${merge_sha}"
  local pr_title="PR preview ${pr_number}"

  source_inventory_now="$(date -u +%s)"
  if ! source_runs="$(completed_preview_inventory pull_request "head_sha=${head_sha}")" ||
    ! dispatch_source_runs="$(completed_preview_inventory repository_dispatch "branch=${DEFAULT_BRANCH}")"; then
    echo "Recent completed preview-source inventory is unavailable, malformed, or truncated; retry budget unchanged." >&2
    return 2
  fi
  if ! source_candidates="$(printf '%s\n' "$source_runs" "$dispatch_source_runs" | jq -r -s \
    --arg repository "$GITHUB_REPOSITORY" --arg head "$head_sha" \
    --arg default_branch "$DEFAULT_BRANCH" --arg workflow_name "$preview_workflow_name" \
    --arg pr_title "$pr_title" --arg run_name "$run_name" \
    --argjson workflow_id "$preview_workflow_id" \
    --argjson now "$source_inventory_now" --argjson window "$preview_handoff_window_seconds" \
    --argjson pr_number "$pr_number" '
      def positive_int: type == "number" and . > 0 and . == floor;
      [ (.[0].workflow_runs[]
        | select(type == "object" and (.id | positive_int) and (.run_attempt | positive_int)
          and .workflow_id == $workflow_id and .event == "pull_request"
          and .status == "completed" and .conclusion == "success"
          and (.name == $workflow_name or .name == .display_title)
          and .display_title == $pr_title
          and .repository.full_name == $repository and .head_repository.full_name == $repository
          and .head_sha == $head
          and (.path == ".github/workflows/preview.yml"
            or .path == (".github/workflows/preview.yml@refs/pull/" + ($pr_number | tostring) + "/merge"))
          and ([.pull_requests[]? | select(.number == $pr_number)] | length) == 1)
        | [.id, .run_attempt, .event, .head_sha, .updated_at]),
        (.[1].workflow_runs[]
        | select(type == "object" and (.id | positive_int) and (.run_attempt | positive_int)
          and .workflow_id == $workflow_id and .event == "repository_dispatch"
          and .status == "completed" and .conclusion == "success"
          and (.name == $workflow_name or .name == .display_title)
          and .display_title == $run_name
          and .repository.full_name == $repository and .head_repository.full_name == $repository
          and .head_branch == $default_branch
          and (.path | IN(".github/workflows/preview.yml",
            ".github/workflows/preview.yml@" + $default_branch,
            ".github/workflows/preview.yml@refs/heads/" + $default_branch)))
        | [.id, .run_attempt, .event, .head_sha, .updated_at])
      ]
      | map(.[4] as $updated_at
        | ($updated_at | fromdateiso8601) as $updated
        | select($updated <= $now and ($now - $updated) < $window)
        | .)
      | sort_by(.[0]) | reverse | .[] | @tsv
    ')"; then
    echo "Unable to parse recent completed preview sources for PR #${pr_number}; retry budget unchanged." >&2
    return 2
  fi
  source_candidate_count="$(printf '%s\n' "$source_candidates" | sed '/^$/d' | wc -l)"
  if (( source_candidate_count > 3 )); then
    echo "Recent completed preview-source lookup exceeded its three-run bound; retry budget unchanged." >&2
    return 2
  fi

  while IFS=$'\t' read -r run_id run_attempt run_event run_head run_updated_at; do
    [[ -n "$run_id" ]] || continue
    completed_preview_source_updated_at=""
    lookup_status=0
    automatic_preview_has_tuple "$run_id" "$run_attempt" "$run_head" "$tuple_step" "$pr_number" "$run_event" || lookup_status=$?
    if [[ "$lookup_status" -eq 2 ]]; then
      echo "Recent preview-source attempt ${run_id} could not be verified; retry budget unchanged." >&2
      return 2
    fi
    [[ "$lookup_status" -eq 3 ]] || continue
    if [[ "$completed_preview_source_updated_at" != "$run_updated_at" ]]; then
      echo "Recent preview-source inventory changed during exact-attempt lookup; retry budget unchanged." >&2
      return 2
    fi
    if [[ ! "$completed_preview_source_updated_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
      ! source_updated_epoch="$(date -u -d "$completed_preview_source_updated_at" +%s 2>/dev/null)" ||
      ! now_epoch="$(date -u +%s)"; then
      echo "Recent preview-source completion time is unavailable; retry budget unchanged." >&2
      return 2
    fi
    source_age=$((now_epoch - source_updated_epoch))
    if (( source_age < 0 )); then
      echo "Recent preview-source completion time is in the future; retry budget unchanged." >&2
      return 2
    fi
    if (( source_age >= preview_handoff_window_seconds )); then
      continue
    fi

    consumer_created_after="$(date -u -d "@$((now_epoch - preview_handoff_window_seconds))" +%Y-%m-%dT%H:%M:%SZ)"
    if ! consumer_runs="$(gh api --method GET \
      "repos/${GITHUB_REPOSITORY}/actions/workflows/${consumer_workflow_id}/runs" \
      -f event=workflow_run -f per_page=100 -f "branch=${DEFAULT_BRANCH}" \
      -f "created=>=${consumer_created_after}")"; then
      echo "Unable to recheck trusted consumers after recent source completion; retry budget unchanged." >&2
      return 2
    fi
    if ! jq -e --argjson workflow_id "$consumer_workflow_id" 'type == "object"
      and (.total_count | type == "number" and . >= 0 and . == floor)
      and (.workflow_runs | type == "array" and length <= 100)
      and (.total_count == (.workflow_runs | length))
      and all(.workflow_runs[]; type == "object" and (.id | type == "number" and . > 0 and . == floor)
        and .workflow_id == $workflow_id and (.run_attempt | type == "number" and . > 0 and . == floor)
        and (.event | type == "string") and (.status | type == "string")
        and (.name | type == "string") and (.display_title | type == "string")
        and (.head_branch | type == "string")
        and (.head_sha | type == "string" and test("^[0-9a-f]{40}$"))
        and (.path | type == "string")
        and (.repository.full_name | type == "string") and (.head_repository.full_name | type == "string"))' <<<"$consumer_runs" >/dev/null; then
      echo "Fresh trusted-consumer inventory is malformed or truncated; retry budget unchanged." >&2
      return 2
    fi
    if ! consumer_candidates="$(jq -r \
      --arg repository "$GITHUB_REPOSITORY" --arg default_branch "$DEFAULT_BRANCH" \
      --arg pr_title "$pr_title" --arg run_name "$run_name" \
      --argjson workflow_id "$consumer_workflow_id" '
        def positive_int: type == "number" and . > 0 and . == floor;
        [ .workflow_runs[]
          | select(type == "object" and (.id | positive_int) and (.run_attempt | positive_int)
            and .workflow_id == $workflow_id and .event == "workflow_run"
            and (.status | IN("requested", "queued", "in_progress", "waiting", "pending"))
            and .head_branch == $default_branch
            and .repository.full_name == $repository and .head_repository.full_name == $repository
            and (.path | IN(".github/workflows/hosted-identity-request.yml",
              ".github/workflows/hosted-identity-request.yml@" + $default_branch,
              ".github/workflows/hosted-identity-request.yml@refs/heads/" + $default_branch))
            and (.display_title == $pr_title or .display_title == $run_name))
          | [.id, .run_attempt, .head_sha]
        ] | sort_by(.[0]) | reverse | .[] | @tsv
      ' <<<"$consumer_runs")"; then
      echo "Unable to parse fresh trusted-consumer inventory; retry budget unchanged." >&2
      return 2
    fi
    consumer_candidate_count="$(printf '%s\n' "$consumer_candidates" | sed '/^$/d' | wc -l)"
    if (( consumer_candidate_count > 3 )); then
      echo "Fresh trusted-consumer lookup exceeded its three-run bound; retry budget unchanged." >&2
      return 2
    fi
    while IFS=$'\t' read -r consumer_run_id consumer_attempt consumer_head; do
      [[ -n "$consumer_run_id" ]] || continue
      consumer_status=0
      trusted_consumer_has_tuple "$consumer_run_id" "$consumer_attempt" "$consumer_head" \
        "Trusted preview deploy tuple pr-${pr_number} base-${base_sha} head-${head_sha} merge-${merge_sha}" || consumer_status=$?
      if [[ "$consumer_status" -eq 0 ]]; then
        echo "Skipping proof retry for PR #${pr_number}: trusted consumer run ${consumer_run_id} for ${base_sha}/${head_sha}/${merge_sha} is already queued or in progress."
        return 0
      elif [[ "$consumer_status" -eq 2 ]]; then
        echo "Fresh trusted-consumer run ${consumer_run_id} could not be verified; retry budget unchanged." >&2
        return 2
      fi
    done <<<"$consumer_candidates"
    echo "Deferring proof retry for PR #${pr_number}: exact successful preview source run ${run_id} is within the ${preview_handoff_window_seconds}-second trusted-consumer handoff window."
    return 3
  done <<<"$source_candidates"
  return 1
}

trusted_consumer_has_tuple() {
  local run_id="$1" attempt="$2" run_head="$3" tuple="$4" jobs latest jobs_available=true
  # A workflow_run display title is inherited from its source. Use the
  # trusted validator's completed attempt evidence to bind the tuple.
  jobs="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/attempts/${attempt}/jobs?per_page=100")" || jobs_available=false
  latest="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}")" || return 2
  jq -e --argjson id "$run_id" --argjson workflow_id "$consumer_workflow_id" \
    --arg repository "$GITHUB_REPOSITORY" --arg default_branch "$DEFAULT_BRANCH" --arg head "$run_head" '
    .id == $id and .workflow_id == $workflow_id and .event == "workflow_run" and .head_sha == $head
    and (.run_attempt | type == "number" and . > 0 and . == floor)
    and .repository.full_name == $repository and .head_repository.full_name == $repository
    and .head_branch == $default_branch
    and (.path | IN(".github/workflows/hosted-identity-request.yml",
      ".github/workflows/hosted-identity-request.yml@" + $default_branch,
      ".github/workflows/hosted-identity-request.yml@refs/heads/" + $default_branch))
    and (.status | IN("requested", "queued", "in_progress", "waiting", "pending", "completed"))
  ' <<<"$latest" >/dev/null || return 2
  if jq -e '.status == "completed"' <<<"$latest" >/dev/null; then return 1; fi
  [[ "$jobs_available" == true ]] || return 2
  jq -e --argjson attempt "$attempt" '.run_attempt == $attempt' <<<"$latest" >/dev/null || return 2
  jq -e --argjson id "$run_id" --argjson attempt "$attempt" --arg head "$run_head" '
    .jobs as $jobs
    | (($jobs | type) == "array") and (.total_count == ($jobs | length)) and (.total_count < 100)
      and ([$jobs[] | select(.name == "Validate Trusted Preview Target")] | length == 1)
      and any($jobs[]; .name == "Validate Trusted Preview Target" and .run_id == $id
        and ((has("run_attempt") | not) or (.run_attempt | type == "number" and . == $attempt))
        and .head_sha == $head and .status == "completed")
  ' <<<"$jobs" >/dev/null || return 2
  jq -e --arg tuple "$tuple" '
    any(.jobs[] | select(.name == "Validate Trusted Preview Target" and .conclusion == "success");
      ([.steps[]? | select(.name | startswith("Trusted preview deploy tuple "))] | length) == 1
      and any(.steps[]?; .name == $tuple and .status == "completed" and .conclusion == "success"))
  ' <<<"$jobs" >/dev/null || return 1
}

dispatch_candidates() {
  while IFS=$'\t' read -r _ pr_number head_sha pr_author pr_base_ref pr_state labels_json_base64; do
  if ! labels_json="$(printf '%s' "$labels_json_base64" | base64 --decode 2>/dev/null)" ||
    [[ -z "$labels_json" ]] ||
    ! jq -e 'type == "array"' <<<"$labels_json" >/dev/null; then
    echo "Skipping PR #${pr_number}: label metadata could not be decoded as a JSON array." >&2
    continue
  fi
  if ! live_pr_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")" ||
    [[ "$(jq -r '.state // empty' <<<"${live_pr_json}")" != open ]] ||
    [[ "$(jq -r '.head.repo.full_name // empty' <<<"${live_pr_json}")" != "${GITHUB_REPOSITORY}" ]] ||
    [[ "$(jq -r '.base.repo.full_name // empty' <<<"${live_pr_json}")" != "${GITHUB_REPOSITORY}" ]]; then
    echo "Skipping PR #${pr_number}: current pull-request ownership or state is not trusted." >&2
    continue
  fi
  if ! head_sha="$(jq -er '.head.sha | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' <<<"${live_pr_json}")" ||
    ! pr_base_ref="$(jq -er '.base.ref | select(type == "string" and test("^[A-Za-z0-9._/-]+$"))' <<<"${live_pr_json}")" ||
    ! pr_author="$(jq -er '.user.login | select(type == "string" and length > 0)' <<<"${live_pr_json}")" ||
    ! live_labels_json="$(jq -c '.labels | if type == "array" then [.[] | {name: .name}] else empty end' <<<"${live_pr_json}")"; then
    echo "Skipping PR #${pr_number}: current pull-request identity or labels are malformed." >&2
    continue
  fi
  head_sha="${head_sha,,}"
  if ! jq -e 'type == "array"' <<<"${live_labels_json}" >/dev/null; then
    echo "Skipping PR #${pr_number}: current label metadata is malformed." >&2
    continue
  fi
  labels_json="${live_labels_json}"
  pr_state=open
  eligibility_output="$(
    python3 "$eligibility_script" \
      --operation deploy \
      --state "$pr_state" \
      --base-ref "$pr_base_ref" \
      --author "$pr_author" \
      --labels-json "$labels_json"
  )"
  eligible="$(sed -n 's/^eligible=//p' <<<"$eligibility_output")"
  reason="$(sed -n 's/^reason=//p' <<<"$eligibility_output")"
  priority="$(sed -n 's/^priority=//p' <<<"$eligibility_output")"
  if [[ "$eligible" != "true" ]]; then
    echo "Skipping preview reconcile for PR #${pr_number}: not preview-eligible (reason=${reason}, author=${pr_author}, base=${pr_base_ref})"
    continue
  fi

  if [[ "$dispatch_sent" == "true" ]]; then
    echo "Skipping PR #${pr_number}: the preview dispatch cycle already stopped after a request attempt."
    continue
  fi

  namespace="pr-${pr_number}"
  namespace_exists=false
  current_base_sha=""
  current_head_sha=""
  current_merge_sha=""
  current_image_tag=""
  requested_base_sha=""
  requested_head_sha=""
  requested_merge_sha=""
  requested_image_tag=""
  proof_base_sha=""
  proof_head_sha=""
  proof_merge_sha=""
  proof_image_tag=""
  proof_namespace_uid=""
  namespace_uid=""
  if ! namespace_json="$(
    kubectl get namespace "${namespace}" --ignore-not-found -o json
  )"; then
    echo "Unable to inspect namespace ${namespace}; refusing preview reconcile." >&2
    exit 1
  fi
  if [[ -n "${namespace_json}" ]]; then
    namespace_exists=true
    if ! namespace_tuple="$(parse_namespace_tuple "${namespace_json}")"; then
      echo "Skipping PR #${pr_number}: unable to parse namespace ${namespace} snapshot." >&2
      continue
    fi
    IFS='|' read -r current_base_sha current_head_sha current_merge_sha current_image_tag \
      requested_base_sha requested_head_sha requested_merge_sha requested_image_tag \
      proof_base_sha proof_head_sha proof_merge_sha proof_image_tag proof_namespace_uid \
      _ _ _ _ _ \
      namespace_uid _ <<<"${namespace_tuple}"
  elif [[ "$priority" != "true" && "$available_slots" -le 0 ]]; then
    if ! refresh_available_slots; then
      echo "Unable to refresh preview capacity for ordinary PR #${pr_number}; refusing dispatch." >&2
      dispatch_outcome_failed=true
      continue
    fi
    if [[ "$available_slots" -le 0 ]]; then
      echo "Skipping ordinary PR #${pr_number}: preview capacity is still full after refresh."
      continue
    fi
  fi

  mergeable="$(jq -r 'if (.mergeable | type) == "boolean" then (.mergeable | tostring) else "invalid" end' <<<"${live_pr_json}")"
  mergeable_state="$(jq -r 'if (.mergeable_state | type) == "string" then .mergeable_state else "invalid" end' <<<"${live_pr_json}")"
  if [[ "${mergeable}" != true || "${mergeable_state}" == invalid ||
    "${mergeable_state}" == unknown || "${mergeable_state}" == dirty ||
    "${mergeable_state}" == conflicting ]]; then
    echo "Skipping preview reconcile for PR #${pr_number}: current base/head merge is not ready (mergeable=${mergeable}, state=${mergeable_state})."
    continue
  fi
  if ! base_ref_json="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${pr_base_ref}")" ||
    ! base_sha="$(jq -er '.object.sha | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' <<<"${base_ref_json}")" ||
    ! merge_sha="$(jq -er '.merge_commit_sha | select(type == "string" and test("^[0-9a-fA-F]{40}$"))' <<<"${live_pr_json}")"; then
    echo "Skipping preview reconcile for PR #${pr_number}: current base ref or merge SHA is unavailable." >&2
    continue
  fi
  base_sha="${base_sha,,}"
  merge_sha="${merge_sha,,}"
  if ! merge_commit_json="$(gh api "repos/${GITHUB_REPOSITORY}/commits/${merge_sha}")" ||
    ! jq -e \
      --arg merge_sha "${merge_sha}" \
      --arg base_sha "${base_sha}" \
      --arg head_sha "${head_sha}" \
      '.sha == $merge_sha and (.parents | type) == "array" and (.parents | length) == 2 and .parents[0].sha == $base_sha and .parents[1].sha == $head_sha' \
      <<<"${merge_commit_json}" >/dev/null; then
    echo "Skipping preview reconcile for PR #${pr_number}: current merge SHA does not have the exact base/head parents." >&2
    continue
  fi
  if ! image_tag="$(bash ./dev-tools/hosted/preview/resolve-preview-image-tag.sh \
    "${merge_sha}" "${pr_number}" "${base_sha}")"; then
    echo "Skipping preview reconcile for PR #${pr_number}: runtime image identity could not be resolved." >&2
    continue
  fi

  if [[ "${current_base_sha}" == "${base_sha}" &&
    "${current_head_sha}" == "${head_sha}" &&
    "${current_merge_sha}" == "${merge_sha}" &&
    "${current_image_tag}" == "${image_tag}" &&
    "${proof_base_sha}" == "${base_sha}" &&
    "${proof_head_sha}" == "${head_sha}" &&
    "${proof_merge_sha}" == "${merge_sha}" &&
    "${proof_image_tag}" == "${image_tag}" &&
    -n "${namespace_uid}" &&
    "${proof_namespace_uid}" == "${namespace_uid}" ]]; then
    if [[ "${requested_base_sha}" != "${base_sha}" ||
      "${requested_head_sha}" != "${head_sha}" ||
      "${requested_merge_sha}" != "${merge_sha}" ||
      "${requested_image_tag}" != "${image_tag}" ]]; then
      repair_status="$repair_success"
      if repair_requested_tuple \
        "$namespace" "$pr_number" "$base_sha" "$head_sha" "$merge_sha" "$image_tag" \
        "$requested_base_sha" "$requested_head_sha" "$requested_merge_sha" "$requested_image_tag"; then
        repair_status="$repair_success"
      else
        repair_status=$?
      fi
      case "$repair_status" in
        "$repair_success" | "$repair_skip")
          continue
          ;;
        "$repair_dispatch")
          namespace_exists=false
          ;;
        "$repair_abort")
          exit 1
          ;;
        *)
          echo "Unexpected requested-head repair status: ${repair_status}" >&2
          exit 1
          ;;
      esac
    else
      echo "Preview ${namespace} already aligned to ${base_sha}/${head_sha}/${merge_sha}/${image_tag}"
      continue
    fi
  fi

  if [[ "${current_base_sha}" == "${base_sha}" &&
    "${current_head_sha}" == "${head_sha}" &&
    "${current_merge_sha}" == "${merge_sha}" &&
    "${current_image_tag}" == "${image_tag}" ]]; then
    echo "Preview ${namespace} deployed tuple is missing proof for ${base_sha}/${head_sha}/${merge_sha}/${image_tag}; dispatching retry."
  fi

  if [[ "$namespace_exists" != "true" ]]; then
    if [[ "$priority" != "true" && "$available_slots" -le 0 ]]; then
      if ! refresh_available_slots; then
        echo "Unable to refresh preview capacity for ordinary PR #${pr_number}; refusing dispatch." >&2
        dispatch_outcome_failed=true
        continue
      fi
      if [[ "$available_slots" -le 0 ]]; then
        echo "Skipping ordinary PR #${pr_number}: preview capacity is still full after refresh."
        continue
      fi
    fi
  fi

  if ! preview_candidates="$(
    jq -c \
      --arg workflow_name "${preview_workflow_name}" \
      --argjson workflow_id "$preview_workflow_id" \
      --argjson consumer_workflow_id "$consumer_workflow_id" \
      --arg repository "$GITHUB_REPOSITORY" \
      --arg default_branch "${DEFAULT_BRANCH}" \
      --arg pr_title "PR preview ${pr_number}" \
      --argjson pr_number "$pr_number" \
      --arg head_sha "$head_sha" \
      --arg run_name "Preview dispatch pr-${pr_number}-base-${base_sha}-head-${head_sha}-merge-${merge_sha}" '
          if type != "array" then
            error("workflow runs are not an array")
          else
            if any(.[];
                (type != "object")
                or ((.id | type) != "number")
                or ((.name | type) != "string")
                or ((.head_branch | type) != "string")
                or ((.display_title | type) != "string")
                or ((.status | type) != "string")
                or ((.firemud_reconciler_branch_filtered | type) != "boolean")
              ) then
              error("workflow run entry is malformed")
            else
              def positive_int: type == "number" and . > 0 and . == floor;
              def native_same_repo:
                .repository.full_name == $repository
                and .head_repository.full_name == $repository
                and (.id | positive_int) and (.run_attempt | positive_int);
              def native_preview:
                native_same_repo and .workflow_id == $workflow_id
                and (.name == $workflow_name or .name == .display_title);
              def trusted_dispatch:
                native_preview and .event == "repository_dispatch"
                and .head_branch == $default_branch
                and (.head_sha | type == "string" and test("^[0-9a-f]{40}$"))
                and (.path == ".github/workflows/preview.yml" or .path == (".github/workflows/preview.yml@" + $default_branch)
                  or .path == (".github/workflows/preview.yml@refs/heads/" + $default_branch))
                and .display_title == $run_name;
              def provisional_pr:
                native_preview and (.event == "pull_request" or .event == "pull_request_target")
                and (.path == ".github/workflows/preview.yml"
                  or (.event == "pull_request" and .path == (".github/workflows/preview.yml@refs/pull/" + ($pr_number | tostring) + "/merge")))
                and (.display_title == $pr_title or (.event == "pull_request" and .display_title == $run_name))
                and .head_sha == $head_sha
                and ([.pull_requests[]? | select(.number == $pr_number)] | length) == 1;
              def trusted_consumer:
                native_same_repo and .workflow_id == $consumer_workflow_id and .event == "workflow_run"
                and .head_branch == $default_branch
                and (.head_sha | type == "string" and test("^[0-9a-f]{40}$"))
                and (.path | IN(".github/workflows/hosted-identity-request.yml",
                  ".github/workflows/hosted-identity-request.yml@" + $default_branch,
                  ".github/workflows/hosted-identity-request.yml@refs/heads/" + $default_branch))
                and (.display_title == $pr_title or .display_title == $run_name);
              .[]
              | select(
                  (
                    (.firemud_reconciler_branch_filtered == false)
                    or (.firemud_reconciler_branch_filtered == true and .head_branch == $default_branch)
                  )
                  and (trusted_dispatch or provisional_pr or trusted_consumer)
                  and (
                    .status == "requested"
                    or .status == "queued"
                    or .status == "in_progress"
                    or .status == "waiting"
                    or .status == "pending"
                  )
                )
              | if trusted_dispatch then {id, exact: true}
                else {id, run_attempt, event, head_sha, exact: false} end
            end
          end
        ' <<<"${active_preview_runs_json}"
  )"; then
    echo "Unable to parse active preview workflow runs; refusing preview reconcile." >&2
    exit 1
  fi

  active_preview_run_id="$(jq -rs '[.[] | select(.exact)][0].id // empty' <<<"$preview_candidates")"
  active_preview_lookup_unknown=false
  completed_preview_handoff_run_id=""
  if [[ -z "$active_preview_run_id" ]]; then
    # A same-head title is not proof: the base can move independently.
    tuple_step="Preview resolved tuple pr-${pr_number} base-${base_sha} head-${head_sha} merge-${merge_sha}"
    consumer_tuple="Trusted preview deploy tuple pr-${pr_number} base-${base_sha} head-${head_sha} merge-${merge_sha}"
    lookup_count=0
    while IFS=$'\t' read -r run_id run_attempt run_event run_head; do
      [[ -n "$run_id" ]] || continue
      if (( lookup_count >= 3 )); then
        active_preview_lookup_unknown=true
        break
      fi
      lookup_count=$((lookup_count + 1))
      lookup_status=0
      if [[ "$run_event" == workflow_run ]]; then
        trusted_consumer_has_tuple "$run_id" "$run_attempt" "$run_head" "$consumer_tuple" || lookup_status=$?
      else
        automatic_preview_has_tuple "$run_id" "$run_attempt" "$run_head" "$tuple_step" "$pr_number" "$run_event" || lookup_status=$?
      fi
      if [[ "$lookup_status" -eq 0 ]]; then
        active_preview_run_id="$run_id"
        break
      elif [[ "$lookup_status" -eq 3 ]]; then
        completed_preview_handoff_run_id="$run_id"
        break
      elif [[ "$lookup_status" -eq 2 ]]; then
        active_preview_lookup_unknown=true
      fi
    done < <(jq -rs '[.[] | select(.exact | not)] | sort_by(.id) | reverse | .[] | [.id, .run_attempt, .event, .head_sha] | @tsv' <<<"$preview_candidates")
  fi

  if [[ -n "${active_preview_run_id}" ]]; then
    echo "Skipping proof retry for PR #${pr_number}: preview source or trusted consumer run ${active_preview_run_id} for ${base_sha}/${head_sha}/${merge_sha} is already queued or in progress."
    continue
  fi
  if [[ -n "${completed_preview_handoff_run_id}" ]]; then
    echo "Deferring proof retry for PR #${pr_number}: successful exact preview source run ${completed_preview_handoff_run_id} may still be handing off to the trusted consumer."
    continue
  fi
  if [[ "$active_preview_lookup_unknown" == true ]]; then
    echo "Skipping proof retry for PR #${pr_number}: active preview tuple lookup unavailable; retry budget unchanged."
    continue
  fi

  recent_handoff_status=0
  recent_successful_source_handoff "$pr_number" "$head_sha" "$tuple_step" || recent_handoff_status=$?
  case "$recent_handoff_status" in
    0 | 3)
      continue
      ;;
    2)
      echo "Skipping proof retry for PR #${pr_number}: recent preview handoff lookup unavailable; retry budget unchanged." >&2
      continue
      ;;
    1)
      ;;
    *)
      echo "Unexpected recent preview handoff status: ${recent_handoff_status}" >&2
      exit 1
      ;;
  esac

  require_deployed_tuple=false
  if [[ "${namespace_exists}" == "true" &&
    "${current_base_sha}" == "${base_sha}" &&
    "${current_head_sha}" == "${head_sha}" &&
    "${current_merge_sha}" == "${merge_sha}" &&
    "${current_image_tag}" == "${image_tag}" ]]; then
    require_deployed_tuple=true
  fi
  # A consumer may finish deployment and proof during the active-run lookup.
  local proof_retry_reservation_count=""
  local proof_retry_previous_count=""
  local proof_retry_previous_base_sha=""
  local proof_retry_previous_head_sha=""
  local proof_retry_previous_merge_sha=""
  local proof_retry_previous_image_tag=""
  local proof_retry_reservation_namespace_uid=""
  local proof_retry_reservation_resource_version=""
  local proof_retry_reservation_base_sha=""
  local proof_retry_reservation_head_sha=""
  local proof_retry_reservation_merge_sha=""
  local proof_retry_reservation_image_tag=""
  prepare_preview_dispatch "$namespace" "$pr_number" "$base_sha" "$head_sha" "$merge_sha" "$image_tag" \
    "$require_deployed_tuple" "$priority" "$namespace_uid" || continue

  echo "Dispatching preview render for PR #${pr_number} (${base_sha}/${head_sha}/${merge_sha}/${image_tag}) from trusted ref ${DEFAULT_BRANCH}"
  dispatch_exit=0
  if dispatch_response="$(gh api --include \
    --method POST \
    "repos/${GITHUB_REPOSITORY}/dispatches" \
    -f event_type=preview-deploy \
    -f 'client_payload[action]=deploy' \
    -f "client_payload[pr_number]=${pr_number}" \
    -f "client_payload[head_sha]=${head_sha}" \
    -f "client_payload[base_ref]=${pr_base_ref}" \
    -f "client_payload[base_sha]=${base_sha}" \
    -f "client_payload[merge_sha]=${merge_sha}" \
    -f "client_payload[image_tag]=${image_tag}" \
    -f 'client_payload[preview_domain]=preview.firedevops.net')"; then
    dispatch_exit=0
  else
    dispatch_exit=$?
  fi
  dispatch_status_line_count="$(grep -cE '^HTTP/[0-9.]+ [0-9]{3} ' <<<"${dispatch_response}" || true)"
  dispatch_http_status="$(awk '/^HTTP\/[0-9.]+ [0-9][0-9][0-9] / { print $2; exit }' <<<"${dispatch_response}")"
  dispatch_retry_after="$(awk 'BEGIN { IGNORECASE=1 } { sub(/\r$/, "") } tolower($0) ~ /^retry-after:[[:space:]]*/ { sub(/^[^:]*:[[:space:]]*/, ""); print; exit }' <<<"${dispatch_response}")"
  dispatch_rate_remaining="$(awk 'BEGIN { IGNORECASE=1 } { sub(/\r$/, "") } tolower($0) ~ /^x-ratelimit-remaining:[[:space:]]*/ { sub(/^[^:]*:[[:space:]]*/, ""); print; exit }' <<<"${dispatch_response}")"
  dispatch_rate_reset="$(awk 'BEGIN { IGNORECASE=1 } { sub(/\r$/, "") } tolower($0) ~ /^x-ratelimit-reset:[[:space:]]*/ { sub(/^[^:]*:[[:space:]]*/, ""); print; exit }' <<<"${dispatch_response}")"
  dispatch_error_body="$(awk 'BEGIN { body=0 } { sub(/\r$/, ""); if (!body && $0 == "") { body=1; next } if (body) print }' <<<"${dispatch_response}")"
  dispatch_rate_limit_body=false
  if jq -e 'type == "object" and ((.message // "") | type == "string" and test("rate limit"; "i"))' \
    <<<"${dispatch_error_body}" >/dev/null 2>&1; then
    dispatch_rate_limit_body=true
  fi
  rate_limited_dispatch=false
  if [[ "${dispatch_http_status}" == 429 ]] ||
    [[ "${dispatch_http_status}" == 403 &&
      ( "${dispatch_retry_after}" =~ ^[0-9]+$ ||
        "${dispatch_rate_remaining}" == 0 ||
        "${dispatch_rate_limit_body}" == true ) ]]; then
    rate_limited_dispatch=true
  fi
  if [[ "${dispatch_exit}" -eq 0 && "${dispatch_status_line_count}" -eq 1 &&
    "${dispatch_http_status}" == 204 ]]; then
    dispatch_sent=true
  elif [[ "${dispatch_status_line_count}" -eq 1 && "${dispatch_http_status}" =~ ^4[0-9][0-9]$ ]]; then
    dispatch_outcome_failed=true
    echo "GitHub confirmed preview dispatch rejection for PR #${pr_number} (HTTP ${dispatch_http_status})."
    if ! compensate_rejected_proof_retry "$namespace" "$pr_number"; then
      echo "The rejected dispatch could not be safely compensated; its proof-retry budget remains charged." >&2
    fi
    if [[ "${rate_limited_dispatch}" == true ]]; then
      rate_limit_wait_seconds=60
      if [[ "${dispatch_retry_after}" =~ ^[0-9]+$ ]]; then
        rate_limit_wait_seconds=$((10#${dispatch_retry_after}))
      elif [[ "${dispatch_rate_remaining}" == 0 && "${dispatch_rate_reset}" =~ ^[0-9]+$ ]]; then
        current_epoch="$(date -u +%s)"
        rate_limit_wait_seconds=$((10#${dispatch_rate_reset} - current_epoch))
        (( rate_limit_wait_seconds > 0 )) || rate_limit_wait_seconds=60
      fi
      echo "GitHub rate-limited preview dispatch; stopping this cycle and waiting ${rate_limit_wait_seconds} seconds before another attempt."
      dispatch_sent=true
      sleep "${rate_limit_wait_seconds}"
    fi
  else
    dispatch_outcome_failed=true
    echo "Preview dispatch outcome for PR #${pr_number} is ambiguous (gh=${dispatch_exit}, HTTP=${dispatch_http_status:-unknown}); proof-retry budget remains charged." >&2
    dispatch_sent=true
  fi
  done
}

candidate_rows="$(
  gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls?state=open&per_page=100" \
    --jq '.[] | [(if (.labels | map(.name) | any(. == "preview:priority")) then 0 else 1 end), .number, .head.sha, .user.login, .base.ref, .state, (.labels | map({name: .name}) | tojson | @base64)] | @tsv' \
    | sort -t $'\t' -k1,1n -k2,2n
)"
if [[ -n "$candidate_rows" ]]; then
  active_preview_run_sets=()
  for active_status in requested queued in_progress waiting pending; do
    for active_run_scope in branch pull_request pull_request_target workflow_run; do
      if [[ "$active_run_scope" == branch ]]; then
        active_run_query="repos/${GITHUB_REPOSITORY}/actions/runs?branch=${DEFAULT_BRANCH}&status=${active_status}&per_page=100"
        active_run_branch_filtered=true
      elif [[ "$active_run_scope" == pull_request || "$active_run_scope" == pull_request_target ]]; then
        active_run_query="repos/${GITHUB_REPOSITORY}/actions/runs?event=${active_run_scope}&status=${active_status}&per_page=100"
        active_run_branch_filtered=false
      else
        active_run_query="repos/${GITHUB_REPOSITORY}/actions/runs?event=workflow_run&status=${active_status}&per_page=100"
        active_run_branch_filtered=false
      fi
      if ! active_preview_run_pages_json="$(gh api --paginate --slurp "$active_run_query")"; then
        echo "Unable to inspect active preview workflow runs; refusing preview reconcile." >&2
        exit 1
      fi
      if ! active_preview_runs_for_query="$(
        jq -c \
          --argjson branch_filtered "$active_run_branch_filtered" '
            if type != "array" then
              error("workflow run pages are not an array")
            else
              [
                .[] as $page
                | if ($page | type) != "object" or ($page.workflow_runs | type) != "array" then
                    error("workflow run page is malformed")
                  else
                    $page.workflow_runs[]
                  end
                | if type != "object" then
                    error("workflow run entry is malformed")
                  else
                    . + {firemud_reconciler_branch_filtered: $branch_filtered}
                  end
              ]
            end
          ' <<<"$active_preview_run_pages_json"
      )"; then
        echo "Unable to normalize active preview workflow runs; refusing preview reconcile." >&2
        exit 1
      fi
      active_preview_run_sets+=("$active_preview_runs_for_query")
    done
  done
  if ! active_preview_runs_json="$(
    printf '%s\n' "${active_preview_run_sets[@]}" | jq -c -s '
      if any(.[]; type != "array") then
        error("active preview workflow run result is not an array")
      else
        add
        | group_by(.id)
        | map(
            . as $runs
            | if any($runs[];
                .name != $runs[0].name
                or .head_branch != $runs[0].head_branch
                or .display_title != $runs[0].display_title
              ) then
                error("active preview workflow run metadata differs between query scopes")
              else
                $runs[0] + {
                  firemud_reconciler_branch_filtered: all(
                    $runs[];
                    .firemud_reconciler_branch_filtered == true
                  )
                }
              end
          )
      end
    '
  )"; then
    echo "Unable to merge active preview workflow runs; refusing preview reconcile." >&2
    exit 1
  fi
  if ! preview_workflow_id="$(gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/preview.yml" | jq -er --arg name "$preview_workflow_name" '
    select(.name == $name and .path == ".github/workflows/preview.yml")
    | .id | select(type == "number" and . > 0 and . == floor)
  ')"; then
    echo "Unable to establish canonical Preview workflow identity; refusing preview reconcile." >&2
    exit 1
  fi
  if ! consumer_workflow_id="$(gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/hosted-identity-request.yml" | jq -er '
    select(.name == "Trusted Hosted Identity Request" and .path == ".github/workflows/hosted-identity-request.yml")
    | .id | select(type == "number" and . > 0 and . == floor)
  ')"; then
    echo "Unable to establish canonical trusted preview consumer identity; refusing preview reconcile." >&2
    exit 1
  fi
  dispatch_candidates <<<"$candidate_rows"
fi
if [[ "$dispatch_outcome_failed" == true ]]; then
  echo "One or more preview dispatch outcomes or capacity checks failed; review this job before another repair cycle." >&2
  exit 1
fi
