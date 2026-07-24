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

  if ! awk -v job="$job" -v expected="$expected" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, expected) { matched = 1 }
    END { exit !(found && matched) }
  ' "$path"; then
    echo "$workflow job $job must contain: $expected" >&2
    exit 1
  fi
}

assert_job_excludes() {
  local workflow="$1"
  local job="$2"
  local forbidden="$3"
  local path="$ROOT_DIR/.github/workflows/$workflow"

  if ! awk -v job="$job" -v forbidden="$forbidden" '
    $0 == "  " job ":" { in_job = 1; found = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && index($0, forbidden) { forbidden_found = 1 }
    END { exit !(found && !forbidden_found) }
  ' "$path"; then
    echo "$workflow job $job must exist and must not contain: $forbidden" >&2
    exit 1
  fi
}

require_contains() {
  local path="$1"
  local expected="$2"

  if ! grep -Fq -- "$expected" "$path"; then
    echo "$path must contain: $expected" >&2
    exit 1
  fi
}

require_exact_line() {
  local path="$1"
  local expected="$2"

  if ! grep -Fxq -- "$expected" "$path"; then
    echo "$path must contain the exact line: $expected" >&2
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
  require_contains "$path" 'types: [opened, synchronize, reopened, edited]'
  require_contains "$path" "&& 'metadata' || 'required' }}"
  require_contains "$path" '  cancel-in-progress: true'
done

require_contains "$ci_path" 'PR Metadata Edit (Validation Summary)'
require_contains "$ci_path" 'PR Metadata Edit (Validation Gate)'
if grep -Fq '    name: Validation Gate' "$ci_path"; then
  echo "metadata-only CI runs must not emit the branch-protected Validation Gate name" >&2
  exit 1
fi
require_contains "$smoke_path" 'PR Metadata Edit (Smoke Summary)'
require_contains "$smoke_path" 'PR Metadata Edit (Smoke Gate)'
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

require_contains "$runtime_images_path" 'types: [opened, synchronize, reopened, edited]'
require_contains "$runtime_images_path" "&& 'metadata' || 'required' }}"
require_contains "$runtime_images_path" '  cancel-in-progress: true'
require_contains "$runtime_images_path" 'run-name: Build Runtime Images '
require_contains "$runtime_images_path" "format('secure-pr-artifact pr-{0}"
require_contains "$runtime_images_path" 'github.event.pull_request.base.sha'
require_contains "$runtime_images_path" 'github.event.pull_request.head.sha'
require_contains "$runtime_images_path" 'github.sha'
require_contains "$runtime_images_path" 'mode-{4}'
require_contains "$image_wait_path" 'display_title = run.get("display_title", "")'
require_contains "$image_wait_path" 'display_title.startswith("Build Runtime Images secure-pr-artifact ")'
require_contains "$image_wait_path" 'and f" head-{head_sha} " in display_title'
require_contains "$image_wait_path" 'and display_title.endswith(" mode-required")'
require_contains "$image_wait_path" 'gh api --paginate --slurp'

for job in image-meta pr-local-smoke; do
  assert_job_condition runtime-images.yml "$job" "$required_condition"
done
assert_job_contains runtime-images.yml pr-local-smoke 'timeout-minutes: 25'

for job in build-base-image build-runtime-images smoke-full; do
  assert_job_contains runtime-images.yml "$job" "github.event_name != 'pull_request'"
done

assert_job_contains runtime-images.yml pr-local-smoke 'SMOKE_IMAGE_LOCAL_ONLY:'
assert_job_contains runtime-images.yml pr-local-smoke 'build-local-smoke-images.sh'
assert_job_contains runtime-images.yml pr-local-smoke 'actions/upload-artifact@'
for forbidden in 'packages: write' 'docker/login-action@' 'push: true' 'type=registry,ref='; do
  assert_job_excludes runtime-images.yml pr-local-smoke "$forbidden"
done

require_contains "$pr_image_publisher_path" 'workflow_run:'
require_exact_line "$pr_image_publisher_path" 'permissions: {}'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal GitHub expression.
require_contains "$pr_image_publisher_path" 'run-name: Publish PR Runtime Images head-${{ github.event.workflow_run.head_sha }}'
require_contains "$pr_image_publisher_path" "github.event.workflow_run.event == 'pull_request'"
require_contains "$pr_image_publisher_path" "github.event.workflow_run.conclusion == 'success'"
require_contains "$pr_image_publisher_path" 'github.event.workflow_run.head_repository.full_name == github.repository'
require_contains "$pr_image_publisher_path" "startsWith(github.event.workflow_run.display_title, 'Build Runtime Images secure-pr-artifact ')"
require_contains "$pr_image_publisher_path" 'actions: read'
require_contains "$pr_image_publisher_path" 'packages: write'
assert_job_excludes publish-pr-runtime-images.yml publish 'contents: read'
# shellcheck disable=SC2016 # These are literal GitHub expression and shell source contracts.
require_contains "$pr_image_publisher_path" 'pr-runtime-images-${{ github.event.workflow_run.head_sha }}'
# shellcheck disable=SC2016 # This assertion intentionally matches the unevaluated publisher script.
require_contains "$pr_image_publisher_path" 'docker push "$image"'
# shellcheck disable=SC2016 # These assertions intentionally match unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'docker manifest inspect "$image"'
require_contains "$pr_image_publisher_path" 'Fixed image tag already exists; preserving first publication'
require_contains "$pr_image_publisher_path" 'max_push_attempts=3'
# shellcheck disable=SC2016 # These assertions intentionally match unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'backoff_seconds=$((5 * 2 ** (push_attempt - 1)))'
# shellcheck disable=SC2016 # This assertion intentionally matches unevaluated publisher shell.
require_contains "$pr_image_publisher_path" 'sleep "$backoff_seconds"'
if grep -Fq 'actions/checkout@' "$pr_image_publisher_path"; then
  echo "trusted PR image publisher must not checkout or execute PR source" >&2
  exit 1
fi

require_contains "$image_wait_path" 'publish-pr-runtime-images.yml/runs?event=workflow_run'
require_contains "$image_wait_path" 'wait_for_pr_publisher'
# shellcheck disable=SC2016 # This assertion intentionally matches a literal shell default expression.
require_contains "$image_wait_path" 'publisher_timeout_seconds="${HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS:-${timeout_seconds}}"'
# shellcheck disable=SC2016 # This assertion intentionally matches the unevaluated publisher deadline.
require_contains "$image_wait_path" 'local publisher_deadline=$((SECONDS + publisher_timeout_seconds))'

image_wait_fixture_dir="$(mktemp -d)"
trap 'rm -rf "$image_wait_fixture_dir"' EXIT
cat >"$image_wait_fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$*" == *"publish-pr-runtime-images.yml"* ]]; then
  cat <<'JSON'
[{"workflow_runs":[{"id":201,"status":"completed","conclusion":"success","html_url":"https://example.test/publisher/201","display_title":"Publish PR Runtime Images head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","created_at":"2026-07-24T00:03:00Z"}]}]
JSON
else
  cat <<'JSON'
[{"workflow_runs":[{"id":102,"status":"completed","conclusion":"skipped","html_url":"https://example.test/runtime/102","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-metadata","created_at":"2026-07-24T00:02:00Z"}]},{"workflow_runs":[{"id":101,"status":"completed","conclusion":"success","html_url":"https://example.test/runtime/101","event":"pull_request","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","display_title":"Build Runtime Images secure-pr-artifact pr-1 base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa merge-cccccccccccccccccccccccccccccccccccccccc mode-required","created_at":"2026-07-24T00:01:00Z"}]}]
JSON
fi
EOF
chmod +x "$image_wait_fixture_dir/gh"
PATH="$image_wait_fixture_dir:$PATH" \
GH_TOKEN=contract-token \
GITHUB_REPOSITORY=example/FireMUD \
HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS=5 \
HOSTED_IMAGE_WAIT_SLEEP_SECONDS=0 \
bash "$image_wait_path" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  >"$image_wait_fixture_dir/output"
require_contains "$image_wait_fixture_dir/output" 'Matching runtime-images workflow 101 succeeded'
require_contains "$image_wait_fixture_dir/output" 'Trusted PR image publisher 201 succeeded'

assert_job_excludes runtime-images.yml smoke-full 'pull-requests: write'
if (assert_job_excludes runtime-images.yml missing-job 'pull-requests: write') 2>/dev/null; then
  echo "assert_job_excludes must fail when the requested job is absent" >&2
  exit 1
fi

require_contains "$smoke_path" 'const baseSha = context.payload.pull_request.base.sha;'
require_contains "$smoke_path" 'const mergeSha = context.sha;'
require_contains "$smoke_path" 'head_sha: headSha,'
require_contains "$smoke_path" 'mode-required'
require_contains "$smoke_path" 'Build Runtime Images secure-pr-artifact pr-'
require_contains "$smoke_path" 'run.display_title !== expectedDisplayTitle'
require_contains "$smoke_path" 'const pullRequests = run.pull_requests ?? [];'
require_contains "$smoke_path" 'pullRequests.length === 0 || pullRequests.some'
require_contains "$smoke_path" 'pullRequest.base?.sha === baseSha'
require_contains "$smoke_path" 'pullRequest.head?.sha === headSha'
require_contains "$smoke_path" 'github.rest.actions.listJobsForWorkflowRun'
require_contains "$smoke_path" 'job.name === "Smoke Tests (Full Stack) / Smoke Tests (Full Stack)"'
require_contains "$smoke_path" 'fullSmokeJob.status !== "completed"'
require_contains "$smoke_path" 'fullSmokeJob.conclusion !== "success"'
if grep -Fq 'const matching = runs.find((run) => run.head_sha === headSha);' "$smoke_path"; then
  echo "Smoke Gate must not accept a runtime-images run by head SHA alone" >&2
  exit 1
fi

python3 - "$smoke_path" "$runtime_images_path" <<'PY'
import re
import sys
from pathlib import Path

import yaml

smoke_path, runtime_images_path = map(Path, sys.argv[1:])
smoke = smoke_path.read_text(encoding="utf-8")
runtime_images = runtime_images_path.read_text(encoding="utf-8")


def quoted_entries(source, start_marker, end_marker):
    start = source.index(start_marker) + len(start_marker)
    end = source.index(end_marker, start)
    return re.findall(r'["\']([^"\']+)["\']', source[start:end])


def pull_request_paths(source, label):
    try:
        workflow = yaml.load(source, Loader=yaml.BaseLoader)
    except yaml.YAMLError as exc:
        raise AssertionError(f"{label} is not valid YAML: {exc}") from exc
    if not isinstance(workflow, dict):
        raise AssertionError(f"{label} must contain a workflow mapping")
    triggers = workflow.get("on")
    if not isinstance(triggers, dict) or "pull_request" not in triggers:
        raise AssertionError(f"{label} must define on.pull_request")
    pull_request = triggers["pull_request"]
    if not isinstance(pull_request, dict):
        raise AssertionError(f"{label} on.pull_request must be a mapping")
    paths = pull_request.get("paths")
    if not isinstance(paths, list) or not paths or not all(isinstance(path, str) for path in paths):
        raise AssertionError(f"{label} on.pull_request.paths must be a non-empty string list")
    return set(paths)


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
runtime_paths = pull_request_paths(runtime_images, "runtime-images.yml")

runtime_images_fixture = """on:
  pull_request:
    paths:
      - 'services/example/**'
      - 'literal  push: value'
  push:
    branches: [main]
"""
assert pull_request_paths(runtime_images_fixture, "runtime fixture") == {
    "services/example/**",
    "literal  push: value",
}

for invalid_source, expected_message in (
    ("on:\n  push:\n    branches: [main]\n", "must define on.pull_request"),
    ("on:\n  pull_request:\n    paths:\n      invalid: value\n", "must be a non-empty string list"),
):
    try:
        pull_request_paths(invalid_source, "invalid runtime fixture")
    except AssertionError as exc:
        assert expected_message in str(exc)
    else:
        raise AssertionError(f"invalid runtime fixture {expected_message}")


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
