#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
resolver="$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh"
preview_workflow="$ROOT_DIR/.github/workflows/preview.yml"

[[ -f "$resolver" ]] || {
  echo "preview image-tag resolver must exist" >&2
  exit 1
}

wait_step="$({
  awk '
    /^      - name: Wait for preview runtime images$/ { in_wait_step = 1 }
    in_wait_step { print }
    in_wait_step && /^      - name:/ && $0 !~ /Wait for preview runtime images$/ { exit }
  ' "$preview_workflow"
})"

grep -Fq 'if: ${{ steps.preview-access.outputs.available == '\''true'\'' && steps.preview-capacity.outcome == '\''success'\'' }}' <<<"$wait_step" || {
  echo "preview must wait for runtime images after access and capacity succeed" >&2
  exit 1
}
grep -Fq 'bash ./dev-tools/hosted/shared/wait-for-runtime-images.sh "${{ steps.effective-image-tag.outputs.image_tag }}"' <<<"$wait_step" || {
  echo "preview must wait for the resolved base-SHA or PR image tag" >&2
  exit 1
}
if grep -Fq 'Reuse immutable base runtime images' "$preview_workflow"; then
  echo "preview must not skip the canonical runtime-image wait for base images" >&2
  exit 1
fi
if grep -Fq 'steps.effective-image-tag.outputs.image_tag != needs.preview-plan.outputs.base_sha' "$preview_workflow"; then
  echo "preview must not conditionally skip the base-image wait" >&2
  exit 1
fi
if grep -Fq 'steps.effective-image-tag.outputs.image_tag == needs.preview-plan.outputs.base_sha' "$preview_workflow"; then
  echo "preview must not retain a duplicate base-image reuse branch" >&2
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
