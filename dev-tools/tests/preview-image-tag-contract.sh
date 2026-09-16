#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
resolver="$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh"
preview_workflow="$ROOT_DIR/.github/workflows/preview.yml"

[[ -f "$resolver" ]] || {
  echo "preview image-tag resolver must exist" >&2
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

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
cat > "$fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_GH_FAIL:-}" == "1" ]]; then
  exit 42
fi
printf '%s\n' "${FAKE_CHANGED_FILES:-}"
EOF
chmod 700 "$fixture_dir/gh"

run_resolver() {
  local files="$1" expected="$2" actual
  actual="$(
    PATH="$fixture_dir:$PATH" \
    FAKE_CHANGED_FILES="$files" \
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
