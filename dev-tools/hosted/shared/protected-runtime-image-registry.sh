#!/usr/bin/env bash
# Sourced by trusted branch runtime selection, digest pinning, and promotion.

services=(
  account-service
  automation-scripting-service
  entity-management-service
  game-design-service
  game-logic-service
  game-session-service
  logging-admin-service
  social-groups-service
  spring-cloud-gateway
  tcp-proxy-service
  world-management-service
)

declare -A registry_tokens=()
registry_manifest_state() {
  local image="$1" output_name="$2"
  local image_path="${image#ghcr.io/}"
  local repository_path="${image_path%:*}"
  local tag="${image_path##*:}"
  local manifest_url="https://ghcr.io/v2/${repository_path}/manifests/${tag}"
  local registry_token="${registry_tokens[$repository_path]:-}"
  local status

  [[ -n "${GHCR_USERNAME:-}" && -n "${GHCR_TOKEN:-}" ]] || {
    echo "GHCR credentials are unavailable for authenticated manifest preflight: $image" >&2
    return 1
  }
  if [[ -z "$registry_token" ]]; then
    if ! registry_token="$(curl --silent --show-error --location --fail \
      --connect-timeout 10 --max-time 30 \
      --user "${GHCR_USERNAME}:${GHCR_TOKEN}" \
      --get \
      --data-urlencode 'service=ghcr.io' \
      --data-urlencode "scope=repository:${repository_path}:pull" \
      --header 'Accept: application/json' \
      'https://ghcr.io/token' | jq -er '.token // .access_token // empty')"; then
      echo "GHCR bearer-token exchange failed for $image; refusing to infer absence." >&2
      return 1
    fi
    registry_tokens["$repository_path"]="$registry_token"
  fi
  # `docker manifest inspect "$image"` conflates a 404 with auth,
  # registry, and network failures, so it is never an absence check.
  if ! status="$(curl --silent --show-error --location --head \
    --connect-timeout 10 --max-time 30 \
    --header "Authorization: Bearer ${registry_token}" \
    --header 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    --output /dev/null --write-out '%{http_code}' "$manifest_url")"; then
    echo "Authenticated OCI manifest HEAD failed for $image; refusing to infer absence." >&2
    return 1
  fi
  case "$status" in
    200) printf -v "$output_name" '%s' existing ;;
    404) printf -v "$output_name" '%s' missing ;;
    *)
      echo "Authenticated OCI manifest HEAD returned HTTP ${status} for $image; refusing to push." >&2
      return 1
      ;;
  esac
}

registry_digest() {
  local image="$1"
  local inspect_output inspect_status
  local line digest=''
  if inspect_output="$(docker buildx imagetools inspect "$image")"; then
    :
  else
    inspect_status=$?
    echo "Unable to inspect exact registry digest for $image (imagetools exit $inspect_status); refusing to publish." >&2
    return "$inspect_status"
  fi
  while IFS= read -r line; do
    [[ "$line" == Digest:* ]] || continue
    if [[ -n "$digest" || ! "$line" =~ ^Digest:[[:space:]]+(sha256:[0-9a-f]{64})[[:space:]]*$ ]]; then
      echo "Registry manifest for $image has malformed or ambiguous digest metadata." >&2
      return 1
    fi
    digest="${BASH_REMATCH[1]}"
  done <<<"$inspect_output"
  [[ -n "$digest" ]] || {
    echo "Registry manifest for $image has no exact digest." >&2
    return 1
  }
  printf '%s\n' "$digest"
}

assert_current_branch_head() {
  local current_sha
  current_sha="$(gh api \
    "repos/${GITHUB_REPOSITORY}/git/ref/heads/${HEAD_BRANCH}" \
    --jq '.object.sha')"
  [[ "$current_sha" == "$HEAD_SHA" ]] || {
    echo "Refusing canonical runtime publication: ${HEAD_BRANCH} moved from ${HEAD_SHA} to ${current_sha}." >&2
    exit 1
  }
}

select_existing_fixed_digests() {
  local service image state digest manifest missing
  local -a entries=() missing_services=()
  for service in "${services[@]}"; do
    image="ghcr.io/benhook1013/${service}:${HEAD_SHA}"
    registry_manifest_state "$image" state || return
    if [[ "$state" == missing ]]; then
      missing_services+=("$service")
      continue
    fi
    digest="$(registry_digest "$image")" || return
    entries+=("$service=$digest")
  done
  manifest="$(encode_digest_inventory "${entries[@]}")" || return
  validate_digest_inventory "$manifest" false || return
  missing="$(printf '%s\n' "${missing_services[@]}" | jq -nRc '[inputs | select(length > 0)]')" || return
  printf 'fixed_digests=%s\nmissing_services=%s\n' "$manifest" "$missing" >> "$GITHUB_OUTPUT"
}

encode_digest_inventory() {
  printf '%s\n' "$@" | jq -nRcS '[inputs | select(length > 0) | split("=") | {key:.[0],value:.[1]}] | from_entries'
}

validate_digest_inventory() {
  local manifest="$1" complete="${2:-true}" inventory
  inventory="$(printf '%s\n' "${services[@]}" | jq -nRc '[inputs] | sort')" || return
  jq -e --argjson services "$inventory" --argjson complete "$complete" '
    type == "object" and
    (if $complete then keys == $services else (keys - $services | length) == 0 end) and
    all(.[]; type == "string" and test("^sha256:[0-9a-f]{64}$"))
  ' <<<"$manifest" >/dev/null
}

pin_runtime_digests() {
  local fixed_manifest="${FIXED_RUNTIME_DIGESTS:-}" manifest service digest
  local -a entries=()
  validate_digest_inventory "$fixed_manifest" false || return
  for service in "${services[@]}"; do
    digest="$(jq -r --arg service "$service" '.[$service] // empty' <<<"$fixed_manifest")" || return
    if [[ -z "$digest" ]]; then
      [[ "$CANDIDATE_TAG" =~ ^candidate-[0-9a-f]{40}-[0-9]+-[0-9]+$ ]] || return 1
      digest="$(registry_digest "ghcr.io/benhook1013/$service:$CANDIDATE_TAG")" || return
    fi
    entries+=("$service=$digest")
  done
  manifest="$(encode_digest_inventory "${entries[@]}")" || return
  validate_digest_inventory "$manifest" || return
  printf 'runtime_digests=%s\n' "$manifest" >> "$GITHUB_OUTPUT"
}

# Check the entire smoke-qualified set and every existing fixed tag before
# writing an alias. A reused fixed tag must stay present and unchanged.
declare -A smoke_sources=() retained_fixed=()
preflight_fixed_digest_set() {
  local manifest="${RUNTIME_DIGESTS:-}" fixed_manifest="${FIXED_RUNTIME_DIGESTS:-}"
  local service repository fixed state expected actual retained_digest
  validate_digest_inventory "$manifest" || return
  validate_digest_inventory "$fixed_manifest" false || return
  for service in "${services[@]}"; do
    repository="ghcr.io/benhook1013/$service"
    fixed="$repository:$HEAD_SHA"
    expected="$(jq -er --arg service "$service" '.[$service]' <<<"$manifest")" || return
    retained_digest="$(jq -r --arg service "$service" '.[$service] // empty' <<<"$fixed_manifest")" || return
    if [[ -n "$retained_digest" && "$retained_digest" != "$expected" ]]; then
      echo "Retained fixed runtime digest for $service differs from the tested digest." >&2
      return 1
    fi
    # The promotion workflow retains this per-service fence through its final tag check.
    # shellcheck disable=SC2034
    retained_fixed[$service]="$retained_digest"
    smoke_sources[$service]="$repository@$expected"
    actual="$(registry_digest "${smoke_sources[$service]}")" || return
    if [[ "$actual" != "$expected" ]]; then
      echo "Registry source for $service does not match tested digest $expected (got $actual)." >&2
      return 1
    fi
    registry_manifest_state "$fixed" state || return
    if [[ "$state" == missing ]]; then
      if [[ -n "$retained_digest" ]]; then
        echo "Fixed runtime tag $fixed disappeared after smoke qualification." >&2
        return 1
      fi
      continue
    fi
    actual="$(registry_digest "$fixed")" || return
    if [[ "$actual" != "$expected" ]]; then
      echo "Refusing to overwrite fixed runtime tag $fixed: existing digest $actual differs from tested digest $expected." >&2
      return 1
    fi
  done
}
