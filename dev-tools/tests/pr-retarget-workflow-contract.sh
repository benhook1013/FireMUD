#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

assert_job_condition() {
  local workflow="$1"
  local job="$2"
  local condition="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v condition="$condition" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, condition) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"; then
    echo "$workflow job $job must be gated by PR base-ref changes" >&2
    exit 1
  fi
}

required_condition="github.event.action != 'edited' || github.event.changes.base.ref != null"
ci_path="$ROOT_DIR/.github/workflows/ci.yml"
runtime_images_path="$ROOT_DIR/.github/workflows/runtime-images.yml"
smoke_path="$ROOT_DIR/.github/workflows/smoke.yml"

for path in "$ci_path" "$smoke_path"; do
  grep -Fq 'types: [opened, synchronize, reopened, edited]' "$path"
  grep -Fq "&& 'metadata' || 'required' }}" "$path"
  grep -Fq '  cancel-in-progress: true' "$path"
done

grep -Fq 'PR Metadata Edit (Validation Summary)' "$ci_path"
grep -Fq 'PR Metadata Edit (Validation Gate)' "$ci_path"
if grep -Fq '    name: Validation Gate' "$ci_path"; then
  echo "metadata-only CI runs must not emit the branch-protected Validation Gate name" >&2
  exit 1
fi
grep -Fq 'PR Metadata Edit (Smoke Summary)' "$smoke_path"
grep -Fq 'PR Metadata Edit (Smoke Gate)' "$smoke_path"
if grep -Fq '    name: Smoke Gate' "$smoke_path"; then
  echo "metadata-only smoke runs must not emit the branch-protected Smoke Gate name" >&2
  exit 1
fi

for job in \
  changes \
  buf-check \
  docker-lint \
  shellcheck \
  python-script-validation \
  dev-tool-contract-checks \
  yaml-lint \
  metrics-cardinality-check \
  flyway-migration-sanity-checks \
  grpc-transport-config-sanity-checks \
  workflow-lint \
  helm-lint \
  helm-render-validation \
  docs-check \
  frontend-checks \
  build-and-test \
  validation-summary \
  validation-gate; do
  assert_job_condition ci.yml "$job" "$required_condition"
done

for job in changes smoke-lite smoke-summary smoke-gate; do
  assert_job_condition smoke.yml "$job" "$required_condition"
done

grep -Fq 'types: [opened, synchronize, reopened, edited]' "$runtime_images_path"
grep -Fq "&& 'metadata' || 'required' }}" "$runtime_images_path"
grep -Fq '  cancel-in-progress: true' "$runtime_images_path"
grep -Fq 'run-name: Build Runtime Images ' "$runtime_images_path"
grep -Fq 'github.event.pull_request.base.sha' "$runtime_images_path"
grep -Fq 'github.event.pull_request.head.sha' "$runtime_images_path"
grep -Fq 'github.sha' "$runtime_images_path"

for job in image-meta build-base-image build-runtime-images smoke-full; do
  assert_job_condition runtime-images.yml "$job" "$required_condition"
done

grep -Fq 'const baseSha = context.payload.pull_request.base.sha;' "$smoke_path"
grep -Fq 'const mergeSha = context.sha;' "$smoke_path"
grep -Fq 'run.display_title === expectedDisplayTitle' "$smoke_path"
grep -Fq 'pullRequest.base?.sha === baseSha' "$smoke_path"
grep -Fq 'pullRequest.head?.sha === headSha' "$smoke_path"
if grep -Fq 'const matching = runs.find((run) => run.head_sha === headSha);' "$smoke_path"; then
  echo "Smoke Gate must not accept a runtime-images run by head SHA alone" >&2
  exit 1
fi

echo "PR retarget workflow contract checks passed"
