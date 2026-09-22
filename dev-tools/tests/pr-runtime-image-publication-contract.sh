#!/usr/bin/env bash
# shellcheck disable=SC2016 # Literal GitHub and shell expressions are contract fixtures.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
RUNTIME_WORKFLOW="$ROOT_DIR/.github/workflows/runtime-images.yml"

require_contains() {
  local expected="$1"
  grep -Fq -- "$expected" "$WORKFLOW" || {
    echo "publisher workflow must contain: $expected" >&2
    exit 1
  }
}

require_contains 'run-name: Publish PR Runtime Images ${{ github.event.workflow_run.display_title }}'
require_contains 'pr-merge-'
require_contains 'pr-runtime-provenance.json'
require_contains 'source run title does not contain exact PR/base/head/merge/mode metadata'
require_contains 'source workflow API path is not the trusted runtime-images workflow'
require_contains 'source workflow API event differs from the event'
require_contains 'typed refresh source run is not on the repository default branch'
require_contains 'current pull request merge SHA differs from source metadata'
require_contains 'current base branch ref SHA differs from source metadata'
require_contains 'merge commit parents do not exactly match declared base and head SHAs'
require_contains 'runtime service manifest is not the exact allowed service list'
require_contains 'runtime provenance JSON schema contains missing or extra keys'
require_contains 'registry remains untouched'
require_contains 'docker manifest inspect "$image"'
require_contains '--head'
require_contains '--connect-timeout 10 --max-time 30'
if grep -Fq -- '--request HEAD' "$WORKFLOW"; then
  echo "publisher manifest probe must use curl --head" >&2
  exit 1
fi
require_contains 'refusing to infer absence'
require_contains 'https://ghcr.io/token'
require_contains 'Authorization: Bearer'
require_contains 'GHCR_TOKEN'
require_contains 'source_image_ids=()'
require_contains 'docker pull "$image"'
require_contains 'does not match the validated source artifact'
require_contains 'uses: ./.github/actions/setup-gh'
require_contains 'group: publish-pr-merge-images-${{ github.event.workflow_run.display_title }}'
require_contains 'cancel-in-progress: false'
if grep -Fq -- 'pr-runtime-images-${{ github.event.workflow_run.head_sha }}' "$WORKFLOW"; then
  echo "publisher must not fall back to a PR head-SHA artifact name" >&2
  exit 1
fi
if grep -Fq -- 'IMAGE_TAG: ${{ github.event.workflow_run.head_sha }}' "$WORKFLOW"; then
  echo "publisher must not derive its image tag from the PR head SHA" >&2
  exit 1
fi

runtime_require_contains() {
  local expected="$1"
  grep -Fq -- "$expected" "$RUNTIME_WORKFLOW" || {
    echo "runtime publisher workflow must contain: $expected" >&2
    exit 1
  }
}

runtime_require_contains 'registry_manifest_state()'
runtime_require_contains '--head'
runtime_require_contains '--connect-timeout 10 --max-time 30'
if grep -Fq -- '--request HEAD' "$RUNTIME_WORKFLOW"; then
  echo "runtime manifest probe must use curl --head" >&2
  exit 1
fi
runtime_require_contains 'refusing to infer absence'
runtime_require_contains 'GHCR_TOKEN'
runtime_require_contains 'registry_tokens=()'
runtime_require_contains 'existing_digest="$(registry_digest "$target")"'
runtime_require_contains 'Refusing to overwrite fixed runtime tag'

fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT
validation_script="$fixture_dir/publisher-validation.py"
publisher_script="$fixture_dir/publisher.sh"
runtime_script="$fixture_dir/runtime.sh"

python3 - "$WORKFLOW" "$RUNTIME_WORKFLOW" "$validation_script" "$publisher_script" "$runtime_script" <<'PY'
import re
import sys
from pathlib import Path

workflow_path = Path(sys.argv[1])
runtime_workflow_path = Path(sys.argv[2])
validation_path = Path(sys.argv[3])
publisher_path = Path(sys.argv[4])
runtime_path = Path(sys.argv[5])
workflow = workflow_path.read_text(encoding="utf-8")
download = workflow.index("- name: Download successful PR image artifacts")
validate = workflow.index("- name: Validate exact PR source and artifact before registry login")
login = workflow.index("- name: Login to GHCR")
publish = workflow.index("- name: Publish fixed PR image tags")
if not download < validate < login < publish:
    raise SystemExit("source/artifact validation must precede registry login and publication")
if "github.event.workflow_run.head_repository.full_name == github.repository" not in workflow:
    raise SystemExit("publisher must reject fork-owned source runs")
match = re.search(r"(?ms)^          python3 - <<'PY'\n(?P<script>.*?)^          PY$", workflow)
if match is None:
    raise SystemExit("publisher validation script was not found")
script = match.group("script")
validation_path.write_text(
    "\n".join(line[10:] if line.startswith("          ") else line for line in script.splitlines()) + "\n",
    encoding="utf-8",
)
publish_start = workflow.index("      - name: Publish fixed PR image tags")
publish_run = workflow.index("        run: |\n", publish_start) + len("        run: |\n")
publish_end = len(workflow)
publish_script = workflow[publish_run:publish_end]
publisher_path.write_text(
    "\n".join(line[10:] if line.startswith("          ") else line for line in publish_script.splitlines()) + "\n",
    encoding="utf-8",
)
runtime_workflow = runtime_workflow_path.read_text(encoding="utf-8")
runtime_start = runtime_workflow.index("- name: Promote smoke-tested service digests")
runtime_run = runtime_workflow.index("        run: |\n", runtime_start) + len("        run: |\n")
runtime_script = runtime_workflow[runtime_run:]
runtime_path.write_text(
    "\n".join(line[10:] if line.startswith("          ") else line for line in runtime_script.splitlines()) + "\n",
    encoding="utf-8",
)
PY

artifact_root="$fixture_dir/artifacts"
fake_bin="$fixture_dir/bin"
mkdir -p "$artifact_root/pr-runtime-images-pr-merge-cccccccccccccccccccccccccccccccccccccccc" "$fake_bin"

repository='benhook1013/FireMUD'
source_run_id='4242'
workflow_id='777'
pr_number='42'
base_sha='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
head_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
merge_sha='cccccccccccccccccccccccccccccccccccccccc'
source_title="Build Runtime Images secure-pr-artifact pr-${pr_number} base-${base_sha} head-${head_sha} merge-${merge_sha} mode-required"
artifact_dir="$artifact_root/pr-runtime-images-pr-merge-$merge_sha"

cat > "$artifact_dir/pr-runtime-services.txt" <<'EOF'
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
backup-verifier
EOF
printf 'placeholder archive\n' > "$artifact_dir/pr-runtime-images.tar.gz"
cat > "$artifact_dir/pr-runtime-provenance.json" <<EOF
{"repository":"${repository}","prNumber":${pr_number},"baseSha":"${base_sha}","headSha":"${head_sha}","mergeSha":"${merge_sha}","imageTag":"pr-merge-${merge_sha}","sourceRunId":${source_run_id},"services":["account-service","automation-scripting-service","entity-management-service","game-design-service","game-logic-service","game-session-service","logging-admin-service","social-groups-service","spring-cloud-gateway","tcp-proxy-service","world-management-service","backup-verifier"]}
EOF

cat > "$fixture_dir/source-run.json" <<EOF
{"id":${source_run_id},"workflow_id":${workflow_id},"name":"${source_title}","path":".github/workflows/runtime-images.yml","event":"pull_request","status":"completed","conclusion":"success","display_title":"${source_title}","head_sha":"${head_sha}","head_branch":"feature/ci","repository":{"full_name":"${repository}"},"head_repository":{"full_name":"${repository}"}}
EOF
cat > "$fixture_dir/pull-request.json" <<EOF
{"number":${pr_number},"state":"open","head":{"sha":"${head_sha}","ref":"feature/ci","repo":{"full_name":"${repository}"}},"base":{"sha":"${base_sha}","ref":"develop","repo":{"full_name":"${repository}"}},"merge_commit_sha":"${merge_sha}"}
EOF
cat > "$fixture_dir/merge-commit.json" <<EOF
{"sha":"${merge_sha}","parents":[{"sha":"${base_sha}"},{"sha":"${head_sha}"}]}
EOF
cat > "$fixture_dir/base-ref.json" <<EOF
{"ref":"refs/heads/develop","object":{"sha":"${base_sha}"}}
EOF

cat > "$fake_bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == api ]] || exit 2
case "${2:-}" in
  repos/benhook1013/FireMUD/actions/runs/4242) cat "$FIXTURE_DIR/source-run.json" ;;
  repos/benhook1013/FireMUD/pulls/42) cat "$FIXTURE_DIR/pull-request.json" ;;
  repos/benhook1013/FireMUD/git/ref/heads/develop) cat "$FIXTURE_DIR/base-ref.json" ;;
  repos/benhook1013/FireMUD/commits/cccccccccccccccccccccccccccccccccccccccc) cat "$FIXTURE_DIR/merge-commit.json" ;;
  *) echo "unexpected endpoint: ${2:-}" >&2; exit 2 ;;
esac
EOF
chmod +x "$fake_bin/gh"
cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
printf 'registry operation unexpectedly reached validation: %s\n' "$*" >> "$REGISTRY_MARKER"
exit 99
EOF
chmod +x "$fake_bin/docker"

run_validation() {
  local source_event="${1:-pull_request}"
  local source_branch="${2:-feature/ci}"
  local source_sha="${3:-$head_sha}"
  local environment_file="$fixture_dir/github-env"
  : > "$environment_file"
  PATH="$fake_bin:$PATH" \
  FIXTURE_DIR="$fixture_dir" \
  REGISTRY_MARKER="$fixture_dir/registry-marker" \
  PR_RUNTIME_ARTIFACT_ROOT="$artifact_root" \
  CURRENT_REPOSITORY="$repository" \
  SOURCE_RUN_ID="$source_run_id" \
  SOURCE_RUN_URL="https://example.test/runs/$source_run_id" \
  SOURCE_RUN_TITLE="$source_title" \
  SOURCE_RUN_EVENT="$source_event" \
  SOURCE_RUN_CONCLUSION='success' \
  SOURCE_HEAD_BRANCH="$source_branch" \
  SOURCE_HEAD_SHA="$source_sha" \
  SOURCE_HEAD_REPOSITORY="$repository" \
  SOURCE_RUN_REPOSITORY="$repository" \
  SOURCE_WORKFLOW_ID="$workflow_id" \
  DEFAULT_BRANCH='develop' \
  GITHUB_ENV="$environment_file" \
  GH_TOKEN='test-token' \
  python3 "$validation_script"
}

run_validation
grep -Fxq "IMAGE_TAG=pr-merge-$merge_sha" "$fixture_dir/github-env"
grep -Fxq "PR_RUNTIME_ARTIFACT_DIR=$artifact_dir" "$fixture_dir/github-env"
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "valid source validation reached a registry operation" >&2
  exit 1
fi

default_branch_sha='eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["base"]["sha"] = "d" * 40
path.write_text(json.dumps(payload), encoding="utf-8")
PY
python3 - "$fixture_dir/source-run.json" "$default_branch_sha" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload.update({"event": "repository_dispatch", "head_sha": sys.argv[2], "head_branch": "develop"})
path.write_text(json.dumps(payload), encoding="utf-8")
PY
run_validation repository_dispatch develop "$default_branch_sha"
grep -Fxq "IMAGE_TAG=pr-merge-$merge_sha" "$fixture_dir/github-env"
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "typed refresh source validation reached a registry operation" >&2
  exit 1
fi

python3 - "$fixture_dir/source-run.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["event"] = "workflow_dispatch"
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if run_validation repository_dispatch develop "$default_branch_sha"; then
  echo "typed refresh validation accepted a source run with the wrong API event" >&2
  exit 1
fi

python3 - "$fixture_dir/source-run.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload.update({"event": "pull_request", "head_sha": "a" * 40, "head_branch": "feature/ci"})
path.write_text(json.dumps(payload), encoding="utf-8")
PY
python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["base"]["sha"] = "b" * 40
path.write_text(json.dumps(payload), encoding="utf-8")
PY

python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["merge_commit_sha"] = "d" * 40
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if run_validation; then
  echo "stale PR merge was accepted" >&2
  exit 1
fi
python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["merge_commit_sha"] = "c" * 40
path.write_text(json.dumps(payload), encoding="utf-8")
PY

python3 - "$artifact_dir/pr-runtime-provenance.json" <<'PY'
import json
import sys
from pathlib import Path

Path(sys.argv[1]).write_text(json.dumps({
    "repository": "benhook1013/FireMUD",
    "prNumber": 42,
    "baseSha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "headSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "mergeSha": "cccccccccccccccccccccccccccccccccccccccc",
    "imageTag": "pr-merge-cccccccccccccccccccccccccccccccccccccccc",
    "sourceRunId": 4242,
    "services": ["account-service", "backup-verifier"],
}), encoding="utf-8")
PY
if run_validation; then
  echo "malformed service provenance was accepted" >&2
  exit 1
fi
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "rejected source reached a registry operation" >&2
  exit 1
fi

cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  image)
    [[ "${2:-}" == inspect && "${3:-}" == --format ]] || exit 2
    image="${5:-}"
    service="${image##*/}"
    service="${service%%:*}"
    if [[ "${PUBLISH_TARGET_MODE:-}" != later-existing && "$service" == account-service && -f "$PULL_STATE" ]]; then
      printf 'sha256:target-account-service\n'
    else
      printf 'sha256:source-%s\n' "$service"
    fi
    ;;
  manifest)
    [[ "${2:-}" == inspect ]] || exit 2
    image="${3:-}"
    service="${image##*/}"
    service="${service%%:*}"
    [[ "$service" == account-service ]]
    ;;
  pull)
    image="${2:-}"
    service="${image##*/}"
    service="${service%%:*}"
    if [[ "${PUBLISH_TARGET_MODE:-}" == later-existing && "$service" == social-groups-service ]]; then
      : > "$PULL_STATE"
    elif [[ "$service" == account-service ]]; then
      : > "$PULL_STATE"
    else
      exit 2
    fi
    ;;
  push)
    if [[ "${PUBLISH_TARGET_MODE:-}" == later-existing && "$*" == *"automation-scripting-service"* ]]; then
      exit 1
    fi
    printf '%s\n' "$*" >> "$REGISTRY_MARKER"
    exit 0
    ;;
  *)
    echo "unexpected docker command: $*" >&2
    exit 2
    ;;
esac
EOF
chmod +x "$fake_bin/docker"
cat > "$fake_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"https://ghcr.io/token"* ]]; then
  printf '{"token":"mock-registry-token"}\n'
  exit 0
fi
if [[ -n "${CURL_STATUS:-}" ]]; then
  printf '%s\n' "$CURL_STATUS"
  exit 0
fi
image_url="${*: -1}"
if [[ "${PUBLISH_TARGET_MODE:-}" == later-existing && "$image_url" == *"/social-groups-service/manifests/"* ]]; then
  printf '200\n'
elif [[ "${PUBLISH_TARGET_MODE:-}" != later-existing && "$image_url" == *"/account-service/manifests/"* ]]; then
  printf '200\n'
else
  printf '404\n'
fi
EOF
chmod +x "$fake_bin/curl"
cat > "$fake_bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$fake_bin/sleep"
rm -f "$fixture_dir/registry-marker" "$fixture_dir/pull-state"
if PR_RUNTIME_ARTIFACT_DIR="$artifact_dir" IMAGE_TAG="pr-merge-$merge_sha" \
   GHCR_USERNAME='test-user' GHCR_TOKEN='test-token' GITHUB_STEP_SUMMARY="$fixture_dir/summary" \
   PULL_STATE="$fixture_dir/pull-state" REGISTRY_MARKER="$fixture_dir/registry-marker" \
   PATH="$fake_bin:$PATH" bash "$publisher_script"; then
  echo "publisher accepted a mismatched pre-existing image tag" >&2
  exit 1
fi
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "publisher pushed a missing tag before rejecting a mismatched existing tag" >&2
  exit 1
fi

rm -f "$fixture_dir/registry-marker" "$fixture_dir/pull-state"
if CURL_STATUS=503 PR_RUNTIME_ARTIFACT_DIR="$artifact_dir" IMAGE_TAG="pr-merge-$merge_sha" \
   GHCR_USERNAME='test-user' GHCR_TOKEN='test-token' GITHUB_STEP_SUMMARY="$fixture_dir/summary" \
   PULL_STATE="$fixture_dir/pull-state" REGISTRY_MARKER="$fixture_dir/registry-marker" \
   PATH="$fake_bin:$PATH" bash "$publisher_script"; then
  echo "publisher treated a registry 5xx as an absent immutable tag" >&2
  exit 1
fi
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "publisher attempted a push after an unknown registry preflight failure" >&2
  exit 1
fi

rm -f "$fixture_dir/registry-marker" "$fixture_dir/pull-state" "$fixture_dir/summary"
if PUBLISH_TARGET_MODE=later-existing PR_RUNTIME_ARTIFACT_DIR="$artifact_dir" IMAGE_TAG="pr-merge-$merge_sha" \
   GHCR_USERNAME='test-user' GHCR_TOKEN='test-token' GITHUB_STEP_SUMMARY="$fixture_dir/summary" \
   PULL_STATE="$fixture_dir/pull-state" REGISTRY_MARKER="$fixture_dir/registry-marker" \
   PATH="$fake_bin:$PATH" bash "$publisher_script"; then
  echo "publisher accepted a failed push in the later-existing-service fixture" >&2
  exit 1
fi
python3 - "$fixture_dir/summary" <<'PY'
import sys
from pathlib import Path

summary = Path(sys.argv[1]).read_text(encoding="utf-8")
unpublished = summary.split("Unpublished fixed tags (not rechecked after the failed push):", 1)[1]
if "`social-groups-service`" in unpublished:
    raise SystemExit("already-available later service was incorrectly reported unpublished")
PY

cat > "$fixture_dir/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == api ]] || exit 2
printf '%s\n' "$HEAD_SHA"
EOF
chmod +x "$fixture_dir/gh"
cat > "$fixture_dir/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"https://ghcr.io/token"* ]]; then
  printf '{"token":"mock-registry-token"}\n'
  exit 0
fi
if [[ -n "${RUNTIME_CURL_STATUS:-}" ]]; then
  printf '%s\n' "$RUNTIME_CURL_STATUS"
  exit 0
fi
case "${RUNTIME_TARGET_MODE:-}" in
  missing) printf '404\n' ;;
  matching|different) printf '200\n' ;;
  non404) printf '503\n' ;;
  *) echo "unexpected runtime target mode" >&2; exit 2 ;;
esac
EOF
chmod +x "$fixture_dir/curl"
cat > "$fixture_dir/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == buildx && "${2:-}" == imagetools && "${3:-}" == inspect ]]; then
  image="${4:-}"
  service="${image##*/}"
  tag="${service##*:}"
  service="${service%%:*}"
  digest='sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  if [[ "${RUNTIME_TARGET_MODE:-}" == different && "$tag" == "$HEAD_SHA" ]]; then
    digest='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
  fi
  printf 'Digest: %s\n' "$digest"
  exit 0
fi
if [[ "${1:-}" == buildx && "${2:-}" == imagetools && "${3:-}" == create ]]; then
  printf '%s\n' "$*" >> "$RUNTIME_REGISTRY_MARKER"
  exit 0
fi
echo "unexpected runtime docker command: $*" >&2
exit 2
EOF
chmod +x "$fixture_dir/docker"

runtime_common_env=(
  HEAD_SHA='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  HEAD_BRANCH='develop'
  CANDIDATE_TAG='candidate'
  GITHUB_REPOSITORY='benhook1013/FireMUD'
  GH_TOKEN='test-token'
  GHCR_USERNAME='test-user'
  GHCR_TOKEN='test-token'
  RUNTIME_REGISTRY_MARKER="$fixture_dir/runtime-registry-marker"
  PATH="$fixture_dir:$PATH"
)
run_runtime() {
  env "${runtime_common_env[@]}" RUNTIME_TARGET_MODE="$1" bash "$runtime_script"
}

rm -f "$fixture_dir/runtime-registry-marker"
run_runtime missing
if [[ "$(wc -l < "$fixture_dir/runtime-registry-marker")" -ne 33 ]]; then
  echo "runtime publisher did not create every explicitly absent alias" >&2
  exit 1
fi

rm -f "$fixture_dir/runtime-registry-marker"
run_runtime matching
if [[ -e "$fixture_dir/runtime-registry-marker" ]]; then
  echo "runtime publisher overwrote matching existing aliases" >&2
  exit 1
fi

rm -f "$fixture_dir/runtime-registry-marker"
if run_runtime different; then
  echo "runtime publisher accepted a differing immutable SHA tag" >&2
  exit 1
fi
if [[ -e "$fixture_dir/runtime-registry-marker" ]]; then
  echo "runtime publisher overwrote a differing immutable SHA tag" >&2
  exit 1
fi

rm -f "$fixture_dir/runtime-registry-marker"
if run_runtime non404; then
  echo "runtime publisher treated a registry failure as an absent tag" >&2
  exit 1
fi
if [[ -e "$fixture_dir/runtime-registry-marker" ]]; then
  echo "runtime publisher published after an unknown registry preflight failure" >&2
  exit 1
fi

echo 'PR runtime image publication contract checks passed (live GitHub and registry operations are not run)'
