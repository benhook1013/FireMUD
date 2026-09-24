#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <merge_sha> [pr_number] [base_image_tag]" >&2
  exit 1
fi

merge_sha="$1"
pr_number="${2:-}"
base_image_tag="${3:-}"

if [[ ! "${merge_sha}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "merge SHA must be exactly 40 hexadecimal characters" >&2
  exit 1
fi
merge_sha="${merge_sha,,}"

if [[ -n "${base_image_tag}" && ! "${base_image_tag}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "base image tag must be exactly 40 hexadecimal characters" >&2
  exit 1
fi
base_image_tag="${base_image_tag,,}"
merge_image_tag="pr-merge-${merge_sha}"

runtime_relevant() {
  local file="$1"

  case "${file}" in
    build.gradle.kts | settings.gradle.kts | gradle.properties | gradlew | gradlew.bat | .python-version)
      return 0
      ;;
    .github/workflows/runtime-images.yml | .github/workflows/publish-pr-runtime-images.yml | .github/workflows/smoke.yml | .github/workflows/smoke-full.yml | .dockerignore)
      return 0
      ;;
    .github/actions/setup-python/* | .github/actions/load-workflow-tool-versions/* | buildSrc/* | gradle/* | protos/* | docker/* | services/* | dev-tools/smoke/*)
      return 0
      ;;
    config/python/smoke-requirements.txt | config/python/smoke-requirements.in | \
      config/workflow-tool-versions.env | \
      dev-tools/backups/verify-backups.sh | \
      dev-tools/backups/pg-dump-s3-selection.shlib | \
      dev-tools/backups/smoke-backup-verifier-image.sh | \
      dev-tools/build-compose-service-jars.sh | \
      dev-tools/build-local-smoke-images.sh | \
      dev-tools/certs/generate-dev-certs.sh | \
      dev-tools/hosted/controller/smoke-paused-controller-image.sh | \
      dev-tools/verify-compose-health.sh | \
      dev-tools/verify-fresh-bootstrap.sh | \
      dev-tools/verify-restart-state.sh | \
      dev-tools/verify-smoke-images.sh)
      return 0
      ;;
  esac

  return 1
}

if [[ -n "${pr_number}" && -n "${base_image_tag}" ]]; then
  if [[ ! "${pr_number}" =~ ^[1-9][0-9]{0,50}$ ]]; then
    echo "pull request number must be a canonical positive decimal integer" >&2
    exit 1
  fi
  if [[ -z "${GH_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
    echo "GH_TOKEN and GITHUB_REPOSITORY are required when resolving PR file changes" >&2
    exit 1
  fi

  if ! pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")"; then
    echo "unable to read pull request ${pr_number}; refusing to select base images" >&2
    exit 1
  fi
  if [[ "$(jq -r '.head.repo.full_name // empty' <<<"${pull_request_json}")" != "${GITHUB_REPOSITORY}" ]] ||
    [[ "$(jq -r '.base.repo.full_name // empty' <<<"${pull_request_json}")" != "${GITHUB_REPOSITORY}" ]]; then
    echo "pull request ${pr_number} is not owned by the current repository; refusing to select base images" >&2
    exit 1
  fi
  if ! expected_file_count="$(jq -er '.changed_files | select(type == "number" and floor == . and . > 0)' <<<"${pull_request_json}")"; then
    echo "pull request ${pr_number} did not provide a positive changed-file count; refusing to select base images" >&2
    exit 1
  fi
  if ! current_base_ref="$(jq -er '.base.ref | select(type == "string" and length > 0)' <<<"${pull_request_json}")" ||
    [[ ! "${current_base_ref}" =~ ^[A-Za-z0-9._/-]+$ ]]; then
    echo "pull request ${pr_number} base ref is missing or invalid; refusing to select base images" >&2
    exit 1
  fi
  if ! current_base_ref_json="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${current_base_ref}")" ||
    ! current_base_sha="$(jq -er '.object.sha | select(type == "string")' <<<"${current_base_ref_json}")" ||
    [[ ! "${current_base_sha}" =~ ^[0-9a-fA-F]{40}$ ]] ||
    [[ "${current_base_sha,,}" != "${base_image_tag}" ]]; then
    echo "pull request ${pr_number} current base branch ref is missing or does not match the requested base image; refusing to select base images" >&2
    exit 1
  fi
  current_base_sha="${current_base_sha,,}"
  if ! current_head_sha="$(jq -er '.head.sha | select(type == "string")' <<<"${pull_request_json}")" ||
    [[ ! "${current_head_sha}" =~ ^[0-9a-fA-F]{40}$ ]]; then
    echo "pull request ${pr_number} head SHA is missing or invalid; refusing to select images" >&2
    exit 1
  fi
  current_head_sha="${current_head_sha,,}"
  if ! current_merge_sha="$(jq -er '.merge_commit_sha | select(type == "string")' <<<"${pull_request_json}")" ||
    [[ ! "${current_merge_sha}" =~ ^[0-9a-fA-F]{40}$ ]] ||
    [[ "${current_merge_sha,,}" != "${merge_sha}" ]]; then
    echo "pull request ${pr_number} merge SHA is missing or does not match the requested tested merge; refusing to select images" >&2
    exit 1
  fi
  if ! merge_commit_json="$(gh api "repos/${GITHUB_REPOSITORY}/commits/${merge_sha}")" ||
    ! jq -e \
      --arg merge_sha "${merge_sha}" \
      --arg base_sha "${current_base_sha}" \
      --arg head_sha "${current_head_sha}" \
      '.sha == $merge_sha and (.parents | type) == "array" and (.parents | length) == 2 and .parents[0].sha == $base_sha and .parents[1].sha == $head_sha' \
      <<<"${merge_commit_json}" >/dev/null; then
    echo "pull request ${pr_number} merge commit does not have the exact current base/head parents; refusing to select images" >&2
    exit 1
  fi

  # Trusted branch-SHA images exist only for main/develop. A stacked base is
  # another PR branch, so its head SHA is not a published runtime-image
  # identity. Always build and select the exact tested merge for stacked PRs,
  # even when the child itself has no runtime-changing paths.
  if [[ "${current_base_ref}" != main && "${current_base_ref}" != develop ]]; then
    echo "${merge_image_tag}"
    exit 0
  fi

  if ! changed_files_json="$(
    gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}/files?per_page=100" --paginate --slurp
  )"; then
    echo "unable to read changed files for pull request ${pr_number}; refusing to select base images" >&2
    exit 1
  fi

  if ! changed_files_output="$(python3 -c '
import json
import sys

try:
    pages = json.load(sys.stdin)
except json.JSONDecodeError as exc:
    raise SystemExit(f"invalid pull request file response: {exc}")

if not isinstance(pages, list) or not pages:
    raise SystemExit("pull request file response must contain at least one page")

files = []
entry_count = 0
for page in pages:
    if not isinstance(page, list):
        raise SystemExit("pull request file response contains a non-list page")
    for entry in page:
        if not isinstance(entry, dict) or not isinstance(entry.get("filename"), str) or not entry["filename"]:
            raise SystemExit("pull request file response contains an invalid file entry")
        entry_count += 1
        files.append(entry["filename"])
        previous = entry.get("previous_filename")
        if previous is not None:
            if not isinstance(previous, str) or not previous:
                raise SystemExit("pull request file response contains an invalid rename source")
            files.append(previous)

if not files:
    raise SystemExit("pull request file response contains no files")

print(json.dumps({"entry_count": entry_count, "paths": files}))
' <<<"${changed_files_json}")"; then
    echo "unable to validate changed files for pull request ${pr_number}; refusing to select base images" >&2
    exit 1
  fi
  if ! listed_file_count="$(jq -er '.entry_count | select(type == "number" and floor == . and . > 0)' <<<"${changed_files_output}")" ||
    [[ "$listed_file_count" != "$expected_file_count" ]]; then
    echo "pull request changed-file list is incomplete (expected ${expected_file_count}, received ${listed_file_count:-invalid}); refusing to select base images" >&2
    exit 1
  fi
  mapfile -t changed_files < <(jq -er '.paths[] | select(type == "string")' <<<"${changed_files_output}")

  has_runtime_change=false
  for file in "${changed_files[@]}"; do
    if runtime_relevant "${file}"; then
      has_runtime_change=true
      break
    fi
  done

  if [[ "${has_runtime_change}" == "false" ]]; then
    echo "${base_image_tag}"
    exit 0
  fi
fi

echo "${merge_image_tag}"
