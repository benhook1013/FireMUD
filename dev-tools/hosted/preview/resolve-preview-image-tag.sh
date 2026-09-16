#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <requested_image_tag> [pr_number] [base_image_tag]" >&2
  exit 1
fi

requested_image_tag="$1"
pr_number="${2:-}"
base_image_tag="${3:-}"

if [[ -z "${requested_image_tag}" ]]; then
  echo "requested image tag must not be empty" >&2
  exit 1
fi

runtime_relevant() {
  local file="$1"

  case "${file}" in
    build.gradle.kts | settings.gradle.kts | gradle.properties | .python-version)
      return 0
      ;;
    .github/workflows/runtime-images.yml | .github/workflows/publish-pr-runtime-images.yml | .github/workflows/smoke.yml | .github/workflows/smoke-full.yml)
      return 0
      ;;
    .github/actions/setup-python/* | buildSrc/* | gradle/* | protos/* | docker/* | services/* | dev-tools/smoke/*)
      return 0
      ;;
    config/python/smoke-requirements.txt | config/python/smoke-requirements.in | \
      dev-tools/build-*.sh | \
      dev-tools/certs/generate-*.sh | \
      dev-tools/hosted/controller/smoke-*.sh | \
      dev-tools/verify-*.sh)
      return 0
      ;;
  esac

  return 1
}

if [[ -n "${pr_number}" && -n "${base_image_tag}" ]]; then
  if [[ -z "${GH_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
    echo "GH_TOKEN and GITHUB_REPOSITORY are required when resolving PR file changes" >&2
    exit 1
  fi

  if ! changed_files_output="$(
    gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}/files?per_page=100" --paginate \
      --jq '.[].filename'
  )"; then
    echo "unable to read changed files for pull request ${pr_number}; refusing to select base images" >&2
    exit 1
  fi
  changed_files=()
  if [[ -n "${changed_files_output}" ]]; then
    mapfile -t changed_files <<<"${changed_files_output}"
  fi

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

echo "${requested_image_tag}"
