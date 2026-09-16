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

echo "Preview image-tag contract passed"
