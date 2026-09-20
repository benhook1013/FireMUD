#!/usr/bin/env bash
# shellcheck disable=SC2016 # Literal GitHub and shell expressions are contract fixtures.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"

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
require_contains 'current pull request merge SHA differs from source metadata'
require_contains 'runtime service manifest is not the exact allowed service list'
require_contains 'runtime provenance JSON schema contains missing or extra keys'
require_contains 'registry remains untouched'
require_contains 'docker manifest inspect "$image"'
require_contains 'source_image_ids=()'
require_contains 'docker pull "$image"'
require_contains 'does not match the validated source artifact'
require_contains 'uses: ./.github/actions/setup-gh'
if grep -Fq -- 'pr-runtime-images-${{ github.event.workflow_run.head_sha }}' "$WORKFLOW"; then
  echo "publisher must not fall back to a PR head-SHA artifact name" >&2
  exit 1
fi
if grep -Fq -- 'IMAGE_TAG: ${{ github.event.workflow_run.head_sha }}' "$WORKFLOW"; then
  echo "publisher must not derive its image tag from the PR head SHA" >&2
  exit 1
fi

fixture_dir="$(mktemp -d)"
trap 'rm -rf -- "$fixture_dir"' EXIT
validation_script="$fixture_dir/publisher-validation.py"
publisher_script="$fixture_dir/publisher.sh"

python3 - "$WORKFLOW" "$validation_script" "$publisher_script" <<'PY'
import re
import sys
from pathlib import Path

workflow_path = Path(sys.argv[1])
validation_path = Path(sys.argv[2])
publisher_path = Path(sys.argv[3])
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

cat > "$fake_bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${1:-}" == api ]] || exit 2
case "${2:-}" in
  repos/benhook1013/FireMUD/actions/runs/4242) cat "$FIXTURE_DIR/source-run.json" ;;
  repos/benhook1013/FireMUD/pulls/42) cat "$FIXTURE_DIR/pull-request.json" ;;
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
  SOURCE_RUN_EVENT='pull_request' \
  SOURCE_RUN_CONCLUSION='success' \
  SOURCE_HEAD_BRANCH='feature/ci' \
  SOURCE_HEAD_SHA="$head_sha" \
  SOURCE_HEAD_REPOSITORY="$repository" \
  SOURCE_RUN_REPOSITORY="$repository" \
  SOURCE_WORKFLOW_ID="$workflow_id" \
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
    if [[ "$service" == account-service && -f "$PULL_STATE" ]]; then
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
    [[ "$service" == account-service ]] || exit 2
    : > "$PULL_STATE"
    ;;
  push)
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
rm -f "$fixture_dir/registry-marker" "$fixture_dir/pull-state"
if PR_RUNTIME_ARTIFACT_DIR="$artifact_dir" IMAGE_TAG="pr-merge-$merge_sha" \
   PULL_STATE="$fixture_dir/pull-state" REGISTRY_MARKER="$fixture_dir/registry-marker" \
   PATH="$fake_bin:$PATH" bash "$publisher_script"; then
  echo "publisher accepted a mismatched pre-existing image tag" >&2
  exit 1
fi
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "publisher pushed a missing tag before rejecting a mismatched existing tag" >&2
  exit 1
fi

echo 'PR runtime image publication contract checks passed (live GitHub and registry operations are not run)'
