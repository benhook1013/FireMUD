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

assert_job_contains() {
  local workflow="$1"
  local job="$2"
  local expected="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  awk -v job="$job" -v expected="$expected" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, expected) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"
}

assert_job_excludes() {
  local workflow="$1"
  local job="$2"
  local forbidden="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if awk -v job="$job" -v forbidden="$forbidden" '
    $0 == "  " job ":" { in_job = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, forbidden) { found = 1 }
    END { exit !found }
  ' "$path"; then
    echo "$workflow job $job must not contain: $forbidden" >&2
    exit 1
  fi
}

required_condition="github.event.action != 'edited' || github.event.changes.base.ref != null"
ci_path="$ROOT_DIR/.github/workflows/ci.yml"
runtime_images_path="$ROOT_DIR/.github/workflows/runtime-images.yml"
pr_image_publisher_path="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
smoke_path="$ROOT_DIR/.github/workflows/smoke.yml"
image_wait_path="$ROOT_DIR/dev-tools/hosted/shared/wait-for-runtime-images.sh"

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
grep -Fq "mode-{4}" "$runtime_images_path"

for job in image-meta pr-local-smoke; do
  assert_job_condition runtime-images.yml "$job" "$required_condition"
done

for job in build-base-image build-runtime-images smoke-full; do
  assert_job_contains runtime-images.yml "$job" "github.event_name != 'pull_request'"
done

assert_job_contains runtime-images.yml pr-local-smoke 'SMOKE_IMAGE_LOCAL_ONLY:'
assert_job_contains runtime-images.yml pr-local-smoke 'build-local-smoke-images.sh'
assert_job_contains runtime-images.yml pr-local-smoke 'actions/upload-artifact@'
for forbidden in 'packages: write' 'docker/login-action@' 'push: true' 'type=registry,ref='; do
  assert_job_excludes runtime-images.yml pr-local-smoke "$forbidden"
done

grep -Fq 'workflow_run:' "$pr_image_publisher_path"
grep -Fq "github.event.workflow_run.event == 'pull_request'" "$pr_image_publisher_path"
grep -Fq "github.event.workflow_run.conclusion == 'success'" "$pr_image_publisher_path"
grep -Fq 'github.event.workflow_run.head_repository.full_name == github.repository' "$pr_image_publisher_path"
grep -Fq 'actions: read' "$pr_image_publisher_path"
grep -Fq 'packages: write' "$pr_image_publisher_path"
grep -Fq 'pr-runtime-images-${{ github.event.workflow_run.head_sha }}' "$pr_image_publisher_path"
grep -Fq 'docker push "$image"' "$pr_image_publisher_path"
if grep -Fq 'actions/checkout@' "$pr_image_publisher_path"; then
  echo "trusted PR image publisher must not checkout or execute PR source" >&2
  exit 1
fi

grep -Fq 'publish-pr-runtime-images.yml/runs?event=workflow_run' "$image_wait_path"
grep -Fq 'wait_for_pr_publisher' "$image_wait_path"

grep -Fq 'const baseSha = context.payload.pull_request.base.sha;' "$smoke_path"
grep -Fq 'const mergeSha = context.sha;' "$smoke_path"
grep -Fq 'head_sha: headSha,' "$smoke_path"
grep -Fq 'mode-required' "$smoke_path"
grep -Fq 'run.display_title === expectedDisplayTitle' "$smoke_path"
grep -Fq 'pullRequest.base?.sha === baseSha' "$smoke_path"
grep -Fq 'pullRequest.head?.sha === headSha' "$smoke_path"
grep -Fq 'github.rest.actions.listJobsForWorkflowRun' "$smoke_path"
grep -Fq 'job.name === "Smoke Tests (Full Stack) / Smoke Tests (Full Stack)"' "$smoke_path"
grep -Fq 'fullSmokeJob.status !== "completed"' "$smoke_path"
grep -Fq 'fullSmokeJob.conclusion !== "success"' "$smoke_path"
if grep -Fq 'const matching = runs.find((run) => run.head_sha === headSha);' "$smoke_path"; then
  echo "Smoke Gate must not accept a runtime-images run by head SHA alone" >&2
  exit 1
fi

python3 - "$smoke_path" "$runtime_images_path" <<'PY'
import re
import sys
from pathlib import Path

smoke_path, runtime_images_path = map(Path, sys.argv[1:])
smoke = smoke_path.read_text(encoding="utf-8")
runtime_images = runtime_images_path.read_text(encoding="utf-8")


def quoted_entries(source, start_marker, end_marker):
    start = source.index(start_marker) + len(start_marker)
    end = source.index(end_marker, start)
    return re.findall(r'["\']([^"\']+)["\']', source[start:end])


full_prefixes = quoted_entries(
    smoke,
    "const fullRelevantPrefixes = [",
    "];",
)
full_files = quoted_entries(
    smoke,
    "const fullRelevantFiles = new Set([",
    "]);",
)
runtime_paths = set(
    quoted_entries(
        runtime_images,
        "\n    paths:\n",
        "\n  push:\n",
    )
)

runtime_images_fixture = """on:
  pull_request:
    paths:
      - 'services/example/**'
      - 'literal  push: value'
  push:
    branches: [main]
"""
assert quoted_entries(
    runtime_images_fixture,
    "\n    paths:\n",
    "\n  push:\n",
) == ["services/example/**", "literal  push: value"]


def path_pattern_covers_prefix(pattern, prefix):
    if pattern.startswith("!"):
        return False
    if pattern in {prefix, f"{prefix}**"}:
        return True
    return pattern.endswith("**") and prefix.startswith(pattern.removesuffix("**"))


assert path_pattern_covers_prefix("services/**", "services/common-library/")
assert not path_pattern_covers_prefix("web-client/**", "services/common-library/")

missing_prefixes = [
    prefix
    for prefix in full_prefixes
    if not any(path_pattern_covers_prefix(pattern, prefix) for pattern in runtime_paths)
]
missing_files = [file for file in full_files if file not in runtime_paths]

if missing_prefixes or missing_files:
    details = []
    if missing_prefixes:
        details.append(f"prefixes={missing_prefixes}")
    if missing_files:
        details.append(f"files={missing_files}")
    raise SystemExit(
        "runtime-images.yml must trigger for every smoke.yml full-smoke declaration: "
        + ", ".join(details)
    )

print("smoke/runtime-images full-scope parity checks passed")
PY

echo "PR retarget workflow contract checks passed"
