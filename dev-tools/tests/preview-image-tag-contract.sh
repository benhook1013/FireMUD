#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
resolver="$ROOT_DIR/dev-tools/hosted/preview/resolve-preview-image-tag.sh"
base_image_waiter="$ROOT_DIR/dev-tools/hosted/preview/wait-for-base-images.sh"
preview_workflow="$ROOT_DIR/.github/workflows/preview.yml"
trusted_workflow="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"

command -v python3 >/dev/null 2>&1 || {
  echo "python3 with PyYAML is required for the preview image-tag contract." >&2
  exit 1
}
python3 -c 'import yaml' >/dev/null 2>&1 || {
  echo "python3 with PyYAML is required for the preview image-tag contract." >&2
  exit 1
}

assert_base_image_reuse_contract() {
  local workflow="$1"
  python3 - "$workflow" <<'PY'
import sys
from pathlib import Path

import yaml

workflow_path = Path(sys.argv[1])
workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8")) or {}
expected_condition = (
    "needs.validate-target.outputs.image_tag == "
    "needs.validate-target.outputs.base_sha"
)
expected_waiter = (
    'bash ./dev-tools/hosted/preview/wait-for-base-images.sh'
)
matches = []
for job_id, job in workflow.get("jobs", {}).items():
    if not isinstance(job, dict):
        continue
    for index, step in enumerate(job.get("steps", [])):
        if isinstance(step, dict) and step.get("name") == "Wait for immutable base runtime images":
            matches.append((job_id, index, step, job.get("steps", [])))

if len(matches) != 1:
    raise SystemExit(
        "trusted workflow must contain exactly one Wait for immutable base runtime images step"
    )

job_id, index, step, steps = matches[0]
condition = step.get("if")
if not isinstance(condition, str) or expected_condition not in condition:
    raise SystemExit(
        "Wait for immutable base runtime images must be guarded by the exact base-SHA equality"
    )

run = step.get("run")
if (
    not isinstance(run, str)
    or expected_waiter not in run
    or '"${{ needs.validate-target.outputs.base_sha }}"' not in run
):
    raise SystemExit(
        "Wait for immutable base runtime images must invoke wait-for-base-images.sh"
    )

required_run_fragments = (
    'docker_config="$(mktemp -d -- "$RUNNER_TEMP/preview-base-docker-config.XXXXXX")"',
    "export DOCKER_CONFIG",
    "trap cleanup_docker_config EXIT",
    'rm -rf -- "$docker_config"',
)
missing_fragments = [fragment for fragment in required_run_fragments if fragment not in run]
if missing_fragments:
    raise SystemExit(
        "Wait for immutable base runtime images must use a fresh isolated Docker config "
        f"with guarded cleanup; missing {missing_fragments!r}"
    )

for preceding_step in steps[:index]:
    if not isinstance(preceding_step, dict):
        continue
    uses = preceding_step.get("uses")
    login_inputs = preceding_step.get("with")
    if (
        isinstance(uses, str)
        and uses.startswith("docker/login-action@")
        and isinstance(login_inputs, dict)
        and login_inputs.get("registry") == "ghcr.io"
    ):
        raise SystemExit(
            "no GHCR login credential may precede Wait for immutable base runtime images"
        )
PY
}

[[ -f "$resolver" ]] || {
  echo "preview image-tag resolver must exist" >&2
  exit 1
}
[[ -f "$base_image_waiter" ]] || {
  echo "preview base-image waiter must exist" >&2
  exit 1
}

grep -Fq 'needs.validate-target.outputs.image_tag != needs.validate-target.outputs.base_sha' "$trusted_workflow" || {
  echo "preview must wait for a runtime-image workflow when it selects a PR image" >&2
  exit 1
}
grep -Fq 'resolve-preview-image-tag.sh' "$preview_workflow" || {
  echo "credential-free preview source must render the resolved image tag" >&2
  exit 1
}
grep -Fq 'resolve-preview-image-tag.sh' "$trusted_workflow" || {
  echo "trusted lifecycle must independently resolve the image tag" >&2
  exit 1
}
assert_base_image_reuse_contract "$trusted_workflow"

assert_branch_publication_contract() {
  local runtime_workflow="$1" docker_workflow="$2"
  python3 - "$runtime_workflow" "$docker_workflow" <<'PY'
import sys
from pathlib import Path

import yaml

runtime_path, docker_path = map(Path, sys.argv[1:])
runtime = yaml.safe_load(runtime_path.read_text(encoding="utf-8")) or {}
docker = yaml.safe_load(docker_path.read_text(encoding="utf-8")) or {}
on = runtime.get(True, runtime.get("on", {}))
if "push" in on:
    raise SystemExit("runtime image publication must not start directly from push")
workflow_run = on.get("workflow_run")
if not isinstance(workflow_run, dict) or workflow_run.get("workflows") != ["CI — Validation"]:
    raise SystemExit("runtime image publication must be driven by CI workflow_run")
if workflow_run.get("types") != ["completed"] or workflow_run.get("branches") != ["main", "develop"]:
    raise SystemExit("runtime workflow_run must be completed-only and branch-scoped")

image_meta_if = runtime["jobs"]["image-meta"].get("if", "")
for required in (
    "github.event.workflow_run.event == 'push'",
    "github.event.workflow_run.conclusion == 'success'",
    "github.event.workflow_run.head_branch == 'main'",
    "github.event.workflow_run.head_branch == 'develop'",
):
    if required not in image_meta_if:
        raise SystemExit(f"image-meta is missing exact CI/branch guard: {required}")

build_text = str(runtime["jobs"]["build-runtime-images"])
if "needs.image-meta.outputs.candidate_tag" not in build_text:
    raise SystemExit("runtime service build must publish only candidate tags")
if "needs.build-base-image.outputs.digest" not in build_text:
    raise SystemExit("runtime service build must bind to the exact base digest")

smoke = runtime["jobs"]["smoke-full"]
if smoke.get("with", {}).get("image_tag") != "${{ needs.image-meta.outputs.candidate_tag }}":
    raise SystemExit("full-stack smoke must consume the staged candidate tag")

publish = runtime["jobs"]["publish-runtime-images"]
if publish.get("needs") != ["image-meta", "build-runtime-images", "smoke-full"]:
    raise SystemExit("canonical runtime publication must depend on the exact smoke job")
publish_text = "\n".join(
    step.get("run", "")
    for step in publish.get("steps", [])
    if isinstance(step, dict)
)
for required in (
    "assert_current_branch_head",
    "docker buildx imagetools create --tag",
    "Refusing to overwrite fixed runtime tag",
):
    if required not in publish_text:
        raise SystemExit(f"canonical promotion is missing: {required}")
if "needs.smoke-full.result == 'success'" not in str(publish.get("if", "")):
    raise SystemExit("canonical promotion must require successful full-stack smoke")

docker_text = str(docker)
if "docker/build-push-action@" in docker_text or "packages: write" in docker_text:
    raise SystemExit("legacy docker-images workflow must not publish runtime services")
docker_on = docker.get(True, docker.get("on", {}))
if not isinstance(docker_on, dict) or set(docker_on) != {"workflow_dispatch"}:
    raise SystemExit(
        "legacy docker-images workflow must expose only the manual workflow_dispatch trigger"
    )
PY
}

assert_branch_publication_contract \
  "$ROOT_DIR/.github/workflows/runtime-images.yml" \
  "$ROOT_DIR/.github/workflows/docker-images.yml"
grep -Fq 'const validFileEntries = changedFiles.every((file) =>' "$ROOT_DIR/.github/workflows/runtime-images.yml" || {
  echo "PR runtime smoke detection must validate every changed-file entry" >&2
  exit 1
}
grep -Fq '|| !validFileEntries' "$ROOT_DIR/.github/workflows/runtime-images.yml" || {
  echo "malformed changed-file entries must require both PR smoke scopes" >&2
  exit 1
}

grep -Fq '.github/workflows/runtime-images.yml' "$base_image_waiter" || {
  echo "base-image waiter must derive services from runtime-images.yml" >&2
  exit 1
}
if grep -Fq 'publish-pr-runtime-images.yml' "$base_image_waiter"; then
  echo "base-image waiter must not use the PR publisher workflow as its service source" >&2
  exit 1
fi

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

negative_runtime_workflow="$fixture_dir/negative-runtime-images.yml"
sed "s/github.event.workflow_run.conclusion == 'success'/github.event.workflow_run.conclusion != 'failure'/" \
  "$ROOT_DIR/.github/workflows/runtime-images.yml" >"$negative_runtime_workflow"
if assert_branch_publication_contract \
  "$negative_runtime_workflow" "$ROOT_DIR/.github/workflows/docker-images.yml" \
  >"$fixture_dir/negative-runtime-output" 2>&1; then
  echo "runtime publication contract accepted an unsuccessful CI workflow_run guard" >&2
  exit 1
fi
grep -Fq 'image-meta is missing exact CI/branch guard' "$fixture_dir/negative-runtime-output" || {
  echo "runtime publication contract did not reject an unsuccessful CI workflow_run guard" >&2
  exit 1
}

negative_base_reuse_workflow="$fixture_dir/negative-base-reuse.yml"
cp "$trusted_workflow" "$negative_base_reuse_workflow"
python3 - "$negative_base_reuse_workflow" <<'PY'
import sys
from pathlib import Path

import yaml

workflow_path = Path(sys.argv[1])
workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
expected_condition = (
    "needs.validate-target.outputs.image_tag == "
    "needs.validate-target.outputs.base_sha"
)
for job in workflow["jobs"].values():
    if not isinstance(job, dict):
        continue
    steps = job.get("steps", [])
    for index, step in enumerate(steps):
        if isinstance(step, dict) and step.get("name") == "Wait for immutable base runtime images":
            step["if"] = "${{ needs.validate-target.outputs.image_tag != needs.validate-target.outputs.base_sha }}"
            steps.insert(
                index,
                {
                    "name": "Former base-image condition placement",
                    "if": "${{ " + expected_condition + " }}",
                    "run": "echo condition is intentionally misplaced",
                },
            )
            workflow_path.write_text(yaml.safe_dump(workflow, sort_keys=False), encoding="utf-8")
            break
    else:
        continue
    break
else:
    raise SystemExit("negative fixture could not find the base-image wait step")
PY
if assert_base_image_reuse_contract "$negative_base_reuse_workflow" \
  >"$fixture_dir/negative-base-reuse-output" 2>&1; then
  echo "preview base-image contract accepted a misplaced base-SHA equality" >&2
  exit 1
fi
grep -Fq 'must be guarded by the exact base-SHA equality' \
  "$fixture_dir/negative-base-reuse-output" || {
  echo "preview base-image contract did not reject a misplaced base-SHA equality" >&2
  exit 1
}
cat > "$fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_GH_FAIL:-}" == "1" ]]; then
  exit 42
fi
if [[ "$*" == *"/files?"* ]]; then
  python3 - <<'PY'
import json
import os

entries = [
    {"filename": filename}
    for filename in os.environ.get("FAKE_CHANGED_FILES", "").splitlines()
    if filename
]
previous = os.environ.get("FAKE_PREVIOUS_FILENAME")
if previous:
    entries[0]["previous_filename"] = previous
print(json.dumps([entries]))
PY
elif [[ "$*" == *"/git/ref/heads/develop"* ]]; then
  printf '%s\n' '{"ref":"refs/heads/develop","object":{"sha":"cccccccccccccccccccccccccccccccccccccccc"}}'
elif [[ "$*" == *"/commits/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"* ]]; then
  parent_base=cccccccccccccccccccccccccccccccccccccccc
  parent_head=dddddddddddddddddddddddddddddddddddddddd
  if [[ "${FAKE_GH_STALE_PARENTS:-}" == "1" ]]; then
    parent_base=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
  fi
  printf '%s\n' '{"sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","parents":[{"sha":"'"$parent_base"'"},{"sha":"'"$parent_head"'"}]}'
else
  printf '%s\n' '{"changed_files":1,"base":{"ref":"develop","sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","repo":{"full_name":"benhook1013/FireMUD"}},"head":{"sha":"'"${FAKE_GH_HEAD_SHA:-dddddddddddddddddddddddddddddddddddddddd}"'","repo":{"full_name":"benhook1013/FireMUD"}},"merge_commit_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'
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
  exec sleep 3600
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

mkdir -p "$fixture_dir/noncanonical-workflow/.github/workflows"
cat > "$fixture_dir/noncanonical-workflow/.github/workflows/runtime-images.yml" <<'EOF'
jobs:
  build-runtime-images:
    strategy:
      matrix:
        service: account-service
EOF
if (
  cd "$fixture_dir/noncanonical-workflow"
  PATH="$fixture_dir:$PATH" \
    HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0 \
    HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0 \
    bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/noncanonical-workflow-output" 2>"$fixture_dir/noncanonical-workflow-error"; then
  echo "base-image waiter must reject a non-list service matrix" >&2
  exit 1
fi
grep -Fq 'must be a non-empty list of strings' "$fixture_dir/noncanonical-workflow-error" || {
  echo "base-image waiter did not explain the non-list service matrix" >&2
  exit 1
}

mkdir -p "$fixture_dir/malformed-workflow/.github/workflows"
cat > "$fixture_dir/malformed-workflow/.github/workflows/runtime-images.yml" <<'EOF'
jobs:
  build-runtime-images:
    strategy:
      matrix:
        service: [account-service
EOF
if (
  cd "$fixture_dir/malformed-workflow"
  PATH="$fixture_dir:$PATH" \
    HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0 \
    HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0 \
    bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/malformed-workflow-output" 2>"$fixture_dir/malformed-workflow-error"; then
  echo "base-image waiter must reject malformed runtime-images.yml" >&2
  exit 1
fi
grep -Fq 'unable to parse runtime-image workflow' "$fixture_dir/malformed-workflow-error" || {
  echo "base-image waiter did not report malformed runtime-images.yml" >&2
  exit 1
}

sleep_zero_calls="$fixture_dir/base-image-sleep-zero-calls"
if (
  PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$sleep_zero_calls" \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=1 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0 \
  bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/base-image-sleep-zero-output" 2>"$fixture_dir/base-image-sleep-zero-error"; then
  echo "base-image waiter must reject a zero polling sleep" >&2
  exit 1
fi
grep -Fq 'sleep value must be a positive integer' "$fixture_dir/base-image-sleep-zero-error" || {
  echo "base-image waiter did not explain the zero polling sleep" >&2
  exit 1
}
[[ ! -e "$sleep_zero_calls" ]] || {
  echo "base-image waiter probed images after rejecting a zero polling sleep" >&2
  exit 1
}

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
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=5 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=1 \
  bash "$base_image_waiter" "$base_sha" >"$fixture_dir/base-image-success-output"
[[ "$(wc -l < "$success_calls")" -eq "${#expected_base_services[@]}" ]] || {
  echo "base-image waiter did not check every runtime-images.yml service" >&2
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
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=2 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=2 \
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
  echo "base-image timeout path did not check every runtime-images.yml service" >&2
  exit 1
}

zero_timeout_calls="$fixture_dir/base-image-zero-timeout-calls"
if (
  PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$zero_timeout_calls" \
  FAKE_REGISTRY_MODE=missing \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=1 \
  bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/base-image-zero-timeout-output" 2>"$fixture_dir/base-image-zero-timeout-error"; then
  echo "base-image waiter must fail immediately for a zero global timeout" >&2
  exit 1
fi
grep -Fq 'Timed out waiting for base runtime images' "$fixture_dir/base-image-zero-timeout-error" || {
  echo "base-image waiter did not report its zero global timeout" >&2
  exit 1
}
[[ ! -e "$zero_timeout_calls" ]] || {
  echo "base-image waiter probed images after a zero global timeout" >&2
  exit 1
}

leading_zero_calls="$fixture_dir/base-image-leading-zero-calls"
if (
  PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$leading_zero_calls" \
  FAKE_REGISTRY_MODE=missing \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=0000 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=0008 \
  HOSTED_BASE_IMAGE_PROBE_TIMEOUT_SECONDS=0009 \
  bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/base-image-leading-zero-output" 2>"$fixture_dir/base-image-leading-zero-error"; then
  echo "base-image waiter must treat leading-zero timeout values as decimal" >&2
  exit 1
fi
grep -Fq 'Timed out waiting for base runtime images' "$fixture_dir/base-image-leading-zero-error" || {
  echo "base-image waiter did not report the leading-zero zero-timeout fixture" >&2
  exit 1
}
[[ ! -e "$leading_zero_calls" ]] || {
  echo "base-image waiter probed images after the leading-zero zero-timeout fixture" >&2
  exit 1
}

hang_calls="$fixture_dir/base-image-hang-calls"
hang_marker="$fixture_dir/base-image-hang-marker"
if (
  PATH="$fixture_dir:$PATH" \
  FAKE_DOCKER_CALLS="$hang_calls" \
  FAKE_DOCKER_HANG_MARKER="$hang_marker" \
  FAKE_REGISTRY_MODE=hang-first \
  HOSTED_BASE_IMAGE_WAIT_TIMEOUT_SECONDS=1 \
  HOSTED_BASE_IMAGE_WAIT_SLEEP_SECONDS=1 \
  HOSTED_BASE_IMAGE_PROBE_TIMEOUT_SECONDS=30 \
  bash "$base_image_waiter" "$base_sha"
) >"$fixture_dir/base-image-hang-output" 2>"$fixture_dir/base-image-hang-error"; then
  echo "base-image waiter must fail closed when a registry probe hangs" >&2
  exit 1
fi
grep -Fq 'Timed out waiting for base runtime images' "$fixture_dir/base-image-hang-error" || {
  echo "base-image waiter did not report the hanging probe timeout" >&2
  exit 1
}
[[ "$(wc -l < "$hang_calls")" -eq 1 ]] || {
  echo "base-image hanging-probe path should stop after the global deadline" >&2
  exit 1
}

merge_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
base_image_tag=cccccccccccccccccccccccccccccccccccccccc
runtime_image_tag="pr-merge-${merge_sha}"
run_resolver() {
  local files="$1" expected="$2" previous_filename="${3:-}" actual
  actual="$(
    PATH="$fixture_dir:$PATH" \
    FAKE_CHANGED_FILES="$files" \
    FAKE_PREVIOUS_FILENAME="$previous_filename" \
    GH_TOKEN=contract-token \
    GITHUB_REPOSITORY=benhook1013/FireMUD \
    bash "$resolver" "$merge_sha" 2786 "$base_image_tag"
  )"
  [[ "$actual" == "$expected" ]] || {
    echo "resolver selected $actual for changed files $files; expected $expected" >&2
    exit 1
  }
}

run_resolver '.github/workflows/preview.yml' "$base_image_tag"
run_resolver '.github/workflows/docker-images.yml' "$base_image_tag"
run_resolver 'dev-tools/build-old-name.sh' "$base_image_tag"
run_resolver 'design/architecture/foo.md' "$base_image_tag"
run_resolver '.github/actions/setup-python/action.yml' "$runtime_image_tag"
run_resolver '.github/actions/load-workflow-tool-versions/action.yml' "$runtime_image_tag"
run_resolver '.dockerignore' "$runtime_image_tag"
run_resolver 'config/python/smoke-requirements.txt' "$runtime_image_tag"
run_resolver 'config/workflow-tool-versions.env' "$runtime_image_tag"
run_resolver 'dev-tools/backups/verify-backups.sh' "$runtime_image_tag"
run_resolver 'dev-tools/backups/pg-dump-s3-selection.shlib' "$runtime_image_tag"
run_resolver 'dev-tools/backups/smoke-backup-verifier-image.sh' "$runtime_image_tag"
run_resolver 'dev-tools/hosted/shared/check-kubectl-version-skew.sh' "$base_image_tag"
run_resolver 'services/game-logic-service/src/main/Foo.kt' "$runtime_image_tag"
run_resolver 'gradlew' "$runtime_image_tag"
run_resolver 'gradlew.bat' "$runtime_image_tag"
# A rename from a runtime-relevant path must keep the PR image selected even
# when the current filename is no longer runtime-relevant.
run_resolver 'design/architecture/new-name.md' "$runtime_image_tag" 'dev-tools/build-local-smoke-images.sh'

if ! (
  PATH="$fixture_dir:$PATH" \
  FAKE_GH_FAIL=1 \
  GH_TOKEN=contract-token \
  GITHUB_REPOSITORY=benhook1013/FireMUD \
  bash "$resolver" "$merge_sha" 2786 "$base_image_tag"
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

if (
  PATH="$fixture_dir:$PATH" \
  FAKE_GH_STALE_PARENTS=1 \
  GH_TOKEN=contract-token \
  GITHUB_REPOSITORY=benhook1013/FireMUD \
  bash "$resolver" "$merge_sha" 2786 "$base_image_tag"
) >"$fixture_dir/stale-parent-output" 2>"$fixture_dir/stale-parent-error"; then
  echo "resolver accepted a merge commit with stale parents" >&2
  exit 1
fi
grep -Fq 'exact current base/head parents' "$fixture_dir/stale-parent-error" || {
  echo "resolver did not reject stale merge parents" >&2
  exit 1
}

if (
  PATH="$fixture_dir:$PATH" \
  FAKE_GH_HEAD_SHA=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee \
  GH_TOKEN=contract-token \
  GITHUB_REPOSITORY=benhook1013/FireMUD \
  bash "$resolver" "$merge_sha" 2786 "$base_image_tag"
) >"$fixture_dir/stale-head-output" 2>"$fixture_dir/stale-head-error"; then
  echo "resolver accepted a merge commit for a stale head" >&2
  exit 1
fi
grep -Fq 'exact current base/head parents' "$fixture_dir/stale-head-error" || {
  echo "resolver did not reject a stale merge head" >&2
  exit 1
}

echo "Preview image-tag contract passed"
