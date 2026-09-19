#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
resolver="$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh"
base_image_waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-base-images.sh"
preview_workflow="$ROOT_DIR/.github/workflows/preview.yml"

[[ -f "$resolver" ]] || {
  echo "preview image-tag resolver must exist" >&2
  exit 1
}
[[ -f "$base_image_waiter" ]] || {
  echo "preview base-image waiter must exist" >&2
  exit 1
}

grep -Fq 'steps.effective-image-tag.outputs.image_tag != needs.preview-plan.outputs.base_sha' "$preview_workflow" || {
  echo "preview must wait for a runtime-image workflow when it selects a PR image" >&2
  exit 1
}
grep -Fq 'steps.effective-image-tag.outputs.image_tag == needs.preview-plan.outputs.base_sha' "$preview_workflow" || {
  echo "preview must explicitly record immutable base-image reuse" >&2
  exit 1
}
grep -Fq 'Reuse immutable base runtime images' "$preview_workflow" || {
  echo "preview must name the immutable base-image reuse step" >&2
  exit 1
}
# shellcheck disable=SC2016 # This assertion intentionally matches a literal GitHub expression.
grep -Fq 'bash ./dev-tools/hosted/preview/wait-for-base-images.sh "${{ steps.effective-image-tag.outputs.image_tag }}"' "$preview_workflow" || {
  echo "preview must verify base images before reusing the base SHA" >&2
  exit 1
}
grep -Fq '.github/workflows/docker-images.yml' "$base_image_waiter" || {
  echo "base-image waiter must derive services from docker-images.yml" >&2
  exit 1
}
if grep -Eq 'runtime-images\.yml|publish-pr-runtime-images\.yml' "$base_image_waiter"; then
  echo "base-image waiter must not use PR runtime-image workflow sources" >&2
  exit 1
fi

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
cat > "$fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_GH_FAIL:-}" == "1" ]]; then
  exit 42
fi
jq_expression=""
while [[ $# -gt 0 ]]; do
  if [[ "$1" == "--jq" ]]; then
    jq_expression="${2:-}"
    break
  fi
  shift
done
printf '%s\n' "${FAKE_CHANGED_FILES:-}"
if [[ "${jq_expression}" == *previous_filename* && -n "${FAKE_PREVIOUS_FILENAME:-}" ]]; then
  printf '%s\n' "${FAKE_PREVIOUS_FILENAME}"
fi
EOF
chmod 700 "$fixture_dir/gh"

base_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
cat > "$fixture_dir/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == manifest && "${2:-}" == inspect && $# -eq 3 ]] || exit 2
image="$3"
printf '%s\n' "$image" >> "${FAKE_DOCKER_CALLS:?}"
if [[ "${FAKE_REGISTRY_MODE:-available}" == "hang-first" && ! -e "${FAKE_DOCKER_HANG_MARKER:?}" ]]; then
  : > "$FAKE_DOCKER_HANG_MARKER"
  while :; do :; done
fi
if [[ "${FAKE_REGISTRY_MODE:-available}" == "available" ]]; then
  case "$image" in
    ghcr.io/benhook1013/account-service:*) exit 0 ;;
    ghcr.io/benhook1013/automation-scripting-service:*) exit 0 ;;
    ghcr.io/benhook1013/entity-management-service:*) exit 0 ;;
    ghcr.io/benhook1013/game-design-service:*) exit 0 ;;
    ghcr.io/benhook1013/game-logic-service:*) exit 0 ;;
    ghcr.io/benhook1013/game-session-service:*) exit 0 ;;
    ghcr.io/benhook1013/logging-admin-service:*) exit 0 ;;
    ghcr.io/benhook1013/social-groups-service:*) exit 0 ;;
    ghcr.io/benhook1013/spring-cloud-gateway:*) exit 0 ;;
    ghcr.io/benhook1013/tcp-proxy-service:*) exit 0 ;;
    ghcr.io/benhook1013/world-management-service:*) exit 0 ;;
  esac
fi
exit 1
EOF
chmod 700 "$fixture_dir/docker"

expected_base_services=(
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

success_calls="$fixture_dir/base-image-success-calls"
PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$success_calls" \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0 \
  bash "$base_image_waiter" "$base_sha" >"$fixture_dir/base-image-success-output"
[[ "$(wc -l < "$success_calls")" -eq "${#expected_base_services[@]}" ]] || {
  echo "base-image waiter did not check every docker-images.yml service" >&2
  exit 1
}
for service in "${expected_base_services[@]}"; do
  grep -Fqx "ghcr.io/benhook1013/${service}:${base_sha}" "$success_calls" || {
    echo "base-image waiter did not check $service" >&2
    exit 1
  }
done

timeout_calls="$fixture_dir/base-image-timeout-calls"
if (
  PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$timeout_calls" \
  FAKE_REGISTRY_MODE=missing \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0 \
  bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/base-image-timeout-output" 2>"$fixture_dir/base-image-timeout-error"; then
  echo "base-image waiter must fail when required registry images remain unavailable" >&2
  exit 1
fi
grep -Fq 'Timed out waiting for base runtime images' "$fixture_dir/base-image-timeout-error" || {
  echo "base-image waiter did not report its bounded timeout" >&2
  exit 1
}
[[ "$(wc -l < "$timeout_calls")" -eq "${#expected_base_services[@]}" ]] || {
  echo "base-image timeout path did not check every docker-images.yml service" >&2
  exit 1
}

hang_calls="$fixture_dir/base-image-hang-calls"
hang_marker="$fixture_dir/base-image-hang-marker"
if (
  PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$hang_calls" \
  FAKE_DOCKER_HANG_MARKER="$hang_marker" \
  FAKE_REGISTRY_MODE=hang-first \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0 \
  HOSTED_BASE_IMAGE_PROBE_TIMEOUT_SECONDS=1 \
  bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/base-image-hang-output" 2>"$fixture_dir/base-image-hang-error"; then
  echo "base-image waiter must fail closed when a registry probe hangs" >&2
  exit 1
fi
grep -Fq 'Timed out waiting for base runtime images' "$fixture_dir/base-image-hang-error" || {
  echo "base-image waiter did not report the hanging probe timeout" >&2
  exit 1
}
[[ "$(wc -l < "$hang_calls")" -eq "${#expected_base_services[@]}" ]] || {
  echo "base-image hanging-probe path did not check every docker-images.yml service" >&2
  exit 1
}

run_resolver() {
  local files="$1" expected="$2" previous_filename="${3:-}" actual
  actual="$(
    PATH="$fixture_dir:$PATH" \
    FAKE_CHANGED_FILES="$files" \
    FAKE_PREVIOUS_FILENAME="$previous_filename" \
    GH_TOKEN=contract-token \
    GITHUB_REPOSITORY=benhook1013/FireMUD \
    bash "$resolver" requested-head-tag 2786 base-commit-tag
  )"
  [[ "$actual" == "$expected" ]] || {
    echo "resolver selected $actual for changed files $files; expected $expected" >&2
    exit 1
  }
}

run_resolver '.github/workflows/preview.yml' base-commit-tag
run_resolver 'design/architecture/foo.md' base-commit-tag
run_resolver '.github/actions/setup-python/action.yml' requested-head-tag
run_resolver 'config/python/smoke-requirements.txt' requested-head-tag
run_resolver 'dev-tools/hosted/shared/check-kubectl-version-skew.sh' base-commit-tag
run_resolver 'services/game-logic-service/src/main/Foo.kt' requested-head-tag
# A rename from a runtime-relevant path must keep the PR image selected even
# when the current filename is no longer runtime-relevant.
run_resolver 'design/architecture/new-name.md' requested-head-tag 'dev-tools/build-old-name.sh'

if ! (
  PATH="$fixture_dir:$PATH" \
  FAKE_GH_FAIL=1 \
  GH_TOKEN=contract-token \
  GITHUB_REPOSITORY=benhook1013/FireMUD \
  bash "$resolver" requested-head-tag 2786 base-commit-tag
) >"$fixture_dir/api-failure-output" 2>"$fixture_dir/api-failure-error"; then
  :
else
  echo "resolver must fail closed when the PR-files API fails" >&2
  exit 1
fi
grep -Fq 'refusing to select base images' "$fixture_dir/api-failure-error" || {
  echo "resolver did not explain the fail-closed PR-files API error" >&2
  exit 1
}
[[ ! -s "$fixture_dir/api-failure-output" ]] || {
  echo "resolver emitted an image tag after the PR-files API failed" >&2
  exit 1
}

echo "Preview image-tag contract passed"
