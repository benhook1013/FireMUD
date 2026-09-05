#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 7 ]]; then
  echo "usage: $0 <pr_number> <priority_pr_number> <previous_head_sha> <previous_image_tag> <previous_hostname> <previous_summary> <reclaiming|reclaimed|retained|failure>" >&2
  exit 1
fi
if [[ -z "${GITHUB_REPOSITORY:-}" || -z "${GH_TOKEN:-}" ]]; then
  echo "GITHUB_REPOSITORY and GH_TOKEN are required" >&2
  exit 1
fi

pr_number="$1"
priority_pr_number="$2"
previous_head_sha="$3"
previous_image_tag="$4"
previous_hostname="$5"
previous_summary="$6"
phase="$7"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Historical summaries remain visible evidence, but their hidden lifecycle
# markers must not be interpreted as the current state by body-wide readers.
sanitize_historical_summary() {
  printf '%s' "$1" | sed -E 's/<!--[[:space:]]*firemud-preview-[[:alnum:]_-]+[[:space:]]*-->//g'
}

previous_summary="$(sanitize_historical_summary "$previous_summary")"

case "$phase" in
  reclaiming)
    summary="$(cat <<EOF
## ⚠️ Preview Reclaim In Progress

- PR: #${pr_number}
- Current hosted environment: unavailable for use during guarded reclaim
- Requested by priority PR: #${priority_pr_number}
- Previous head SHA: \`${previous_head_sha:-unavailable}\`
- Previous image tag: \`${previous_image_tag:-unavailable}\`
- Previous host: https://${previous_hostname}
- Historical proof: retained below when a prior workflow summary was available
EOF
)"
    ;;
  reclaimed)
    summary="$(bash "${script_dir}/write-preview-summary.sh" \
      reclaimed \
      "$pr_number" \
      "$previous_head_sha" \
      "$previous_image_tag" \
      "$previous_hostname" \
      unavailable \
      "$priority_pr_number")"
    ;;
  retained)
    summary="$(cat <<EOF
## ℹ️ Preview Reclaim Cancelled

- PR: #${pr_number}
- Current hosted environment: retained
- Reclaim request from priority PR: #${priority_pr_number}
- Head SHA: \`${previous_head_sha:-unavailable}\`
- Image tag: \`${previous_image_tag:-unavailable}\`
- Host: https://${previous_hostname}
- Previous proof: retained below as historical evidence; this cancellation does not create new proof
EOF
)"
    ;;
  failure)
    summary="$(bash "${script_dir}/write-preview-summary.sh" \
      failure \
      "$pr_number" \
      "$previous_head_sha" \
      "$previous_image_tag" \
      "$previous_hostname" \
      unavailable \
      "reclaim to priority PR #${priority_pr_number}")"
    ;;
  *)
    echo "unsupported reclaim publication phase: $phase" >&2
    exit 1
    ;;
esac

body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT
{
  printf '%s\n' '<!-- firemud-preview-summary -->'
  case "$phase" in
    reclaiming)
      printf '%s\n' '<!-- firemud-preview-reclaimed -->' '<!-- firemud-preview-reclaiming -->'
      ;;
    reclaimed)
      printf '%s\n' '<!-- firemud-preview-reclaimed -->'
      ;;
    retained)
      printf '%s\n' '<!-- firemud-preview-reclaim-cancelled -->'
      ;;
    failure)
      printf '%s\n' '<!-- firemud-preview-reclaim-failed -->'
      ;;
  esac
  printf '%s\n' '### Preview Summary' '' "$summary"
  if [[ -n "$previous_summary" ]]; then
    printf '%s\n' '' '<details>' '<summary>Previous preview result (historical)</summary>' '' "$previous_summary" '' '</details>'
  fi
} > "$body_file"

preview_comment_rows="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/issues/${pr_number}/comments" \
  --jq '
    .[]
    | select(
        .user.login == "github-actions[bot]"
        and (
          ((.body // "") | contains("<!-- firemud-preview-summary -->"))
          or ((.body // "") | startswith("### Preview Summary"))
        )
      )
    | [
        (.id | tostring),
        (if ((.updated_at // "") | length) > 0 then .updated_at else (.created_at // "") end)
      ]
    | @tsv' \
  )"

sort_preview_comment_rows() {
  local comment_rows="$1"
  local comment_id
  local timestamp
  local parsed_timestamp

  while IFS=$'\t' read -r comment_id timestamp; do
    if [[ -z "$comment_id" ]]; then
      continue
    fi
    if [[ -n "$timestamp" ]]; then
      parsed_timestamp="$(date -u -d "$timestamp" +%s 2>/dev/null || printf '0')"
    else
      parsed_timestamp=0
    fi
    printf '%s\t%s\n' "$parsed_timestamp" "$comment_id"
  done <<< "$comment_rows" | LC_ALL=C sort -t $'\t' -k1,1n -k2,2n
}

sorted_preview_comment_rows="$(sort_preview_comment_rows "$preview_comment_rows")"
comment_id="$(awk -F '\t' 'NF >= 2 { latest = $2 } END { print latest }' <<< "$sorted_preview_comment_rows")"

if [[ -n "$comment_id" ]]; then
  while IFS=$'\t' read -r _ duplicate_comment_id; do
    if [[ -z "$duplicate_comment_id" || "$duplicate_comment_id" == "$comment_id" ]]; then
      continue
    fi
    gh api --method DELETE "repos/${GITHUB_REPOSITORY}/issues/comments/${duplicate_comment_id}" >/dev/null
  done <<< "$sorted_preview_comment_rows"
  gh api --method PATCH "repos/${GITHUB_REPOSITORY}/issues/comments/${comment_id}" -F "body=@${body_file}" >/dev/null
else
  gh api --method POST "repos/${GITHUB_REPOSITORY}/issues/${pr_number}/comments" -F "body=@${body_file}" >/dev/null
fi
