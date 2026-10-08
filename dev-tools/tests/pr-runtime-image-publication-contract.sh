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

require_contains 'run-name: Publish PR Runtime Images ${{ github.event.workflow_run.display_title ||'
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
require_contains 'group: publish-pr-merge-images-${{ needs.source.outputs.title }}'
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
resolver_script="$fixture_dir/resolver.js"
dispatch_script="$fixture_dir/dispatch.js"

python3 - "$WORKFLOW" "$RUNTIME_WORKFLOW" "$validation_script" "$publisher_script" "$runtime_script" "$resolver_script" "$dispatch_script" <<'PY'
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
validation_section = workflow[validate:login]
match = re.search(r"(?ms)^          python3 - <<'PY'\n(?P<script>.*?)^          PY$", validation_section)
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
def javascript_step(text, name):
    section = text.split("      - name: " + name + "\n", 1)[1]
    section = section.split("          script: |\n", 1)[1]
    lines = []
    for line in section.splitlines():
        if line and not line.startswith("            "):
            break
        lines.append(line[12:] if line.startswith("            ") else line)
    return "\n".join(lines)

Path(sys.argv[6]).write_text(javascript_step(workflow, "Resolve successful exact source run"))
runtime_workflow = runtime_workflow_path.read_text(encoding="utf-8")
Path(sys.argv[7]).write_text(javascript_step(runtime_workflow, "Dispatch exact runtime publication source"))
handoff = runtime_workflow.split("  dispatch-pr-runtime-publication:\n", 1)[1].split("  smoke-full:\n", 1)[0]
for required in ["needs: [image-meta, pr-local-smoke, pr-controller-smoke]", "github.event_name == 'repository_dispatch'", "github.event.action == 'pr-runtime-base-refresh'", "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)", "needs.image-meta.result == 'success'", "needs.pr-local-smoke.result == 'success'", "needs.pr-controller-smoke.result == 'success'", "needs.image-meta.outputs.controller_smoke_required == 'false'", "needs.pr-controller-smoke.result == 'skipped'", "contents: write"]:
    if required not in handoff:
        raise SystemExit("trusted handoff prerequisite missing: " + required)
if any(forbidden in handoff for forbidden in ["actions/checkout", "packages: write", "actions: write", "secrets:"]):
    raise SystemExit("trusted handoff must not consume source or other write credentials")
resolver = workflow.split("  source:\n", 1)[1].split("  publish:\n", 1)[0]
if "actions: read" not in resolver or any(value in resolver for value in ["actions/checkout", "contents: write", "packages: write"]):
    raise SystemExit("source resolver must be no-checkout and read-only")
if "workflow_dispatch:" in workflow or "types: [pr-runtime-image-publication]" not in workflow:
    raise SystemExit("publisher handoff must retain default-branch repository dispatch")
for job in ["pr-local-smoke", "pr-controller-smoke"]:
    section = re.split(r"\n  [a-z][a-z-]*:", runtime_workflow.split("  " + job + ":\n", 1)[1], maxsplit=1)[0]
    if "contents: read" not in section or "contents: write" in section or "packages: write" in section:
        raise SystemExit("PR source jobs must remain read-only")
runtime_start = runtime_workflow.index("- name: Promote smoke-tested service digests")
runtime_run = runtime_workflow.index("        run: |\n", runtime_start) + len("        run: |\n")
runtime_script = runtime_workflow[runtime_run:]
runtime_path.write_text(
    "\n".join(line[10:] if line.startswith("          ") else line for line in runtime_script.splitlines()) + "\n",
    encoding="utf-8",
)
PY

node - "$resolver_script" "$dispatch_script" "$RUNTIME_WORKFLOW" "$WORKFLOW" "$fixture_dir/download-inputs.json" <<'JS'
const fs = require("fs");
const assert = require("assert/strict");
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const resolver = new AsyncFunction("github", "context", "core", "Date", "setTimeout", fs.readFileSync(process.argv[2], "utf8"));
const dispatch = new AsyncFunction("github", "context", fs.readFileSync(process.argv[3], "utf8"));
const sha = character => character.repeat(40);
const title = `Build Runtime Images secure-pr-artifact pr-42 base-${sha("b")} head-${sha("a")} merge-${sha("c")} mode-required`;
const repository = { full_name: "benhook1013/FireMUD", default_branch: "develop" };
const source = { id: 4242, workflow_id: 777, path: ".github/workflows/runtime-images.yml", name: title, display_title: title, event: "repository_dispatch", repository, head_repository: repository, head_branch: "develop", head_sha: sha("b"), status: "completed", conclusion: "success" };
const baseContext = { eventName: "repository_dispatch", ref: "refs/heads/develop", repo: {owner: "benhook1013", repo: "FireMUD"}, runId: 4242, payload: { repository, action: "pr-runtime-image-publication", client_payload: { source_run_id: "4242" } } };
async function resolve({ context = baseContext, runs = [source], workflow = { id: 777, name: "Build Runtime Images", path: source.path }, error = false } = {}) {
  let reads = 0, clock = 0;
  const output = {};
  const github = { rest: { actions: {
    getWorkflow: async () => ({data: workflow}),
    getWorkflowRun: async request => { assert.equal(request.run_id, 4242); reads++; if (error) throw Error("API unavailable"); return {data: runs[Math.min(reads - 1, runs.length - 1)]}; },
  } } };
  await resolver(github, context, {setOutput: (key, value) => {output[key] = value;}}, {now: () => {clock += 60000; return clock;}}, fn => fn());
  assert.equal(output.title, title);
  assert.equal(output.artifact_name, `pr-runtime-images-pr-merge-${sha("c")}`);
  assert.equal(JSON.parse(output.source).status, "completed");
  return {reads, output};
}
const runtimeWorkflow = fs.readFileSync(process.argv[4], "utf8");
const handoffCondition = runtimeWorkflow.split("  dispatch-pr-runtime-publication:\n")[1].match(/^    if: \$\{\{ (.*) \}\}$/m)[1]
  .replace(/needs\.([a-z-]+)/g, (_, key) => `needs[${JSON.stringify(key)}]`);
const admitted = new Function("github", "needs", "always", "cancelled", "format", `return ${handoffCondition};`);
const trigger = {event_name: "repository_dispatch", event: {action: "pr-runtime-base-refresh", repository}, ref: "refs/heads/develop"};
const success = {"image-meta": {result: "success", outputs: {controller_smoke_required: "false"}}, "pr-local-smoke": {result: "success"}, "pr-controller-smoke": {result: "skipped"}};
function admits(event = trigger, results = success, cancelled = false) {
  return admitted(event, results, () => true, () => cancelled, (pattern, value) => pattern.replace("{0}", value));
}
assert.equal(admits(), true);
assert.equal(admits(trigger, {...success, "image-meta": {result: "success", outputs: {controller_smoke_required: "true"}}, "pr-controller-smoke": {result: "success"}}), true);
for (const event of [{...trigger, event_name: "pull_request"}, {...trigger, ref: "refs/heads/feature"}, {...trigger, event: {...trigger.event, action: "other"}}]) assert.equal(admits(event), false);
for (const job of ["image-meta", "pr-local-smoke", "pr-controller-smoke"]) {
  for (const result of ["failure", "cancelled"]) assert.equal(admits(trigger, {...success, [job]: {...success[job], result}}), false);
}
assert.equal(admits(trigger, {...success, "image-meta": {result: "success", outputs: {controller_smoke_required: "true"}}}), false);
assert.equal(admits(trigger, {...success, "image-meta": {result: "success", outputs: {}}}), false);
assert.equal(admits(trigger, success, true), false);
(async () => {
  let dispatched;
  await dispatch({rest: {repos: {createDispatchEvent: async payload => {dispatched = payload;}}}}, baseContext);
  assert.deepEqual(dispatched, {...baseContext.repo, event_type: "pr-runtime-image-publication", client_payload: {source_run_id: "4242"}});
  const direct = await resolve();
  const pending = {...source, status: "in_progress", conclusion: null};
  assert.equal((await resolve({runs: [pending, source]})).reads, 2);
  const callbackContext = {...baseContext, eventName: "workflow_run", payload: {repository, workflow_run: source}};
  const callback = await resolve({context: callbackContext});
  assert.equal(callback.output.title, direct.output.title);
  assert.equal(callback.output.artifact_name, direct.output.artifact_name);
  const prSource = {...source, event: "pull_request", head_sha: sha("a"), head_branch: "feature/ci"};
  const prCallback = {...callbackContext, payload: {repository, workflow_run: prSource}};
  assert.equal((await resolve({runs: [prSource]})).output.artifact_name, direct.output.artifact_name);
  assert.equal((await resolve({runs: [prSource], context: prCallback})).output.artifact_name, direct.output.artifact_name);
  const publisherWorkflow = fs.readFileSync(process.argv[5], "utf8");
  const download = publisherWorkflow.split("      - name: Download successful PR image artifacts\n")[1]
    .split("      - name: Validate exact PR source and artifact before registry login\n")[0];
  const inputs = Object.fromEntries([...download.matchAll(/^          (name|path|run-id): (.+)$/gm)]
    .map(match => [match[1], match[2]]));
  assert.equal(inputs.name, "${{ needs.source.outputs.artifact_name }}");
  assert.equal(inputs.path, "/tmp/pr-runtime-artifacts/${{ needs.source.outputs.artifact_name }}");
  assert.equal(inputs["run-id"], "${{ fromJSON(needs.source.outputs.source).id }}");
  assert.ok(publisherWorkflow.includes("artifact_name: ${{ steps.resolve.outputs.artifact_name }}"));
  const upload = runtimeWorkflow.split("      - name: Upload preview image artifact\n")[1]
    .split("      - name: Summarize secure PR runtime artifact\n")[0];
  assert.ok(upload.includes("actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9"));
  assert.equal(upload.includes("overwrite: true"), false);
  fs.writeFileSync(process.argv[6], JSON.stringify({inputs, output: direct.output}));
  for (const suffix of ["refs/heads/develop", "refs/pull/42/merge", sha("b")]) {
    const suffixed = {...source, path: `${source.path}@${suffix}`};
    assert.equal((await resolve({runs: [suffixed]})).output.title, direct.output.title);
    assert.equal((await resolve({runs: [suffixed], context: {...callbackContext, payload: {repository, workflow_run: suffixed}}})).output.title, direct.output.title);
  }
  for (const path of ["other.yml@refs/heads/develop", `${source.path}.bak@refs/heads/develop`,
      `${source.path}@`, `${source.path}@@`, `${source.path}@refs//heads/develop`,
      `${source.path}@refs/heads/.hidden`, `${source.path}@refs/heads/foo.lock`,
      `${source.path}@refs/heads/foo.`, `${source.path}@refs/heads/a..b`,
      `${source.path}@refs/heads/a@{b}`, `${source.path}@refs/heads/a b`,
      `${source.path}@refs/heads/a\\b`, `${source.path}@refs/heads/a?b`]) {
    const malformed = {...source, path};
    await assert.rejects(resolve({runs: [malformed]}), /provenance/);
    await assert.rejects(resolve({runs: [malformed], context: {...callbackContext, payload: {repository, workflow_run: malformed}}}), /provenance/);
  }
  for (const change of [{status: "completed", conclusion: "failure"}, {head_repository: {full_name: "fork/FireMUD"}}, {repository: {full_name: "fork/FireMUD"}}, {workflow_id: 778}, {path: "other.yml"}, {event: "push"}, {head_branch: "feature"}, {display_title: "unbound"}, {head_sha: "bad"}]) {
    await assert.rejects(resolve({runs: [{...source, ...change}]}));
  }
  await assert.rejects(resolve({runs: [pending]}), /bounded wait/);
  await assert.rejects(resolve({error: true}), /API unavailable/);
  await assert.rejects(resolve({context: {...baseContext, ref: "refs/heads/feature"}}));
  await assert.rejects(resolve({context: {...baseContext, payload: {...baseContext.payload, client_payload: {source_run_id: "4242", extra: true}}}}));
  await assert.rejects(resolve({context: {...baseContext, payload: {...baseContext.payload, client_payload: {source_run_id: "../4242"}}}}));
  await assert.rejects(resolve({context: {...callbackContext, payload: {repository, workflow_run: {...source, head_sha: sha("d")}}}}), /differs from callback/);
  console.log("Exact source handoff and terminal-resolution fixtures passed.");
})().catch(error => { console.error(error); process.exitCode = 1; });
JS

artifact_root="$fixture_dir/artifacts"
fake_bin="$fixture_dir/bin"
mkdir -p "$fake_bin"

repository='benhook1013/FireMUD'
source_run_id='4242'
workflow_id='777'
pr_number='42'
base_sha='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
head_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
merge_sha='cccccccccccccccccccccccccccccccccccccccc'
source_title="Build Runtime Images secure-pr-artifact pr-${pr_number} base-${base_sha} head-${head_sha} merge-${merge_sha} mode-required"
artifact_dir="$(python3 - "$fixture_dir/download-inputs.json" "$artifact_root" <<'PY'
import json
import sys
from pathlib import Path

fixture = json.loads(Path(sys.argv[1]).read_text())
name = fixture["output"]["artifact_name"]
destination = fixture["inputs"]["path"].replace("${{ needs.source.outputs.artifact_name }}", name)
destination = Path(sys.argv[2]) / Path(destination).relative_to("/tmp/pr-runtime-artifacts")
# download-artifact@9000827 extracts a single selected artifact directly into path,
# without adding a directory for its name. Populate that actual destination below.
destination.mkdir(parents=True)
print(destination)
PY
)"

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
{"id":${source_run_id},"workflow_id":${workflow_id},"name":"Build Runtime Images","path":".github/workflows/runtime-images.yml","event":"pull_request","status":"completed","conclusion":"success","display_title":"${source_title}","head_sha":"${head_sha}","head_branch":"feature/ci","repository":{"full_name":"${repository}"},"head_repository":{"full_name":"${repository}"}}
EOF
cat > "$fixture_dir/pull-request.json" <<EOF
{"number":${pr_number},"state":"open","user":{"login":"human"},"labels":[],"mergeable":true,"mergeable_state":"clean","head":{"sha":"${head_sha}","ref":"feature/ci","repo":{"full_name":"${repository}"}},"base":{"sha":"${base_sha}","ref":"develop","repo":{"full_name":"${repository}"}},"merge_commit_sha":"${merge_sha}"}
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
  repos/benhook1013/FireMUD/pulls/42)
    if [[ -n "${PULL_REQUEST_SEQUENCE_DIR:-}" ]]; then
      count_file="$PULL_REQUEST_SEQUENCE_DIR/count"
      count=0
      if [[ -f "$count_file" ]]; then
        count="$(<"$count_file")"
      fi
      count=$((count + 1))
      printf '%s' "$count" > "$count_file"
      sequence_file="$PULL_REQUEST_SEQUENCE_DIR/${count}.json"
      if [[ -f "$sequence_file" ]]; then
        cat "$sequence_file"
      else
        cat "$PULL_REQUEST_SEQUENCE_DIR/last.json"
      fi
    else
      cat "$FIXTURE_DIR/pull-request.json"
    fi
    ;;
  repos/benhook1013/FireMUD/git/ref/heads/develop) cat "$FIXTURE_DIR/base-ref.json" ;;
  repos/benhook1013/FireMUD/git/ref/heads/*) cat "$FIXTURE_DIR/base-ref.json" ;;
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
  if [[ -n "${PULL_REQUEST_SEQUENCE_DIR:-}" ]]; then
    rm -f "$PULL_REQUEST_SEQUENCE_DIR/count"
  fi
  PATH="$fake_bin:$PATH" \
  FIXTURE_DIR="$fixture_dir" \
  PULL_REQUEST_SEQUENCE_DIR="${PULL_REQUEST_SEQUENCE_DIR:-}" \
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

set_source_run_path() {
  python3 - "$fixture_dir/source-run.json" "$1" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["path"] = sys.argv[2]
path.write_text(json.dumps(payload), encoding="utf-8")
PY
}

run_validation
for valid_ref in \
  "main" \
  "feature+metadata" \
  "refs/heads/develop" \
  "refs/heads/feature]/test" \
  "refs/heads/feature/@/test" \
  "refs/heads/feature./test" \
  "$head_sha"; do
  set_source_run_path ".github/workflows/runtime-images.yml@$valid_ref"
  run_validation || {
    echo "publisher rejected valid workflow path ref suffix: $valid_ref" >&2
    exit 1
  }
done
for invalid_path in \
  '.github/workflows/other.yml@main' \
  '.github/workflows/runtime-images.yml@' \
  '.github/workflows/runtime-images.yml@feature bad' \
  '.github/workflows/runtime-images.yml@feature..bad' \
  '.github/workflows/runtime-images.yml@@' \
  '.github/workflows/runtime-images.yml@refs/heads/trailing.' \
  '.github/workflows/runtime-images.yml@refs/heads/feature[/test' \
  '.github/workflows/runtime-images.yml@.hidden' \
  '.github/workflows/runtime-images.yml@feature/.lock'; do
  set_source_run_path "$invalid_path"
  if run_validation >/dev/null 2>"$fixture_dir/path-error"; then
    echo "publisher accepted unrelated or malformed workflow path: $invalid_path" >&2
    exit 1
  fi
  grep -Fq 'source workflow API path is not the trusted runtime-images workflow' "$fixture_dir/path-error"
done
set_source_run_path '.github/workflows/runtime-images.yml'
run_validation
# Both API name representations are valid; display_title remains the exact identity.
python3 - "$fixture_dir/source-run.json" "$source_title" <<'PY'
import json
import sys
from pathlib import Path
p = Path(sys.argv[1]); data = json.loads(p.read_text()); data["name"] = sys.argv[2]; p.write_text(json.dumps(data))
PY
run_validation
for mutation in name display_title; do
  python3 - "$fixture_dir/source-run.json" "$mutation" <<'PY'
import json
import sys
from pathlib import Path
p = Path(sys.argv[1]); data = json.loads(p.read_text()); data[sys.argv[2]] = "forged"; p.write_text(json.dumps(data))
PY
  if run_validation; then
    echo "publisher accepted forged source $mutation" >&2
    exit 1
  fi
  python3 - "$fixture_dir/source-run.json" "$source_title" <<'PY'
import json
import sys
from pathlib import Path
p = Path(sys.argv[1]); data = json.loads(p.read_text()); data["name"] = "Build Runtime Images"; data["display_title"] = sys.argv[2]; p.write_text(json.dumps(data))
PY
done
run_validation
grep -Fxq "IMAGE_TAG=pr-merge-$merge_sha" "$fixture_dir/github-env"
grep -Fxq "PR_RUNTIME_ARTIFACT_DIR=$artifact_dir" "$fixture_dir/github-env"
artifact_name="${artifact_dir##*/}"
mv "$artifact_dir" "$artifact_root/pr-runtime-images-pr-merge-dddddddddddddddddddddddddddddddddddddddd"
if run_validation; then
  echo "wrong merge artifact directory was accepted" >&2
  exit 1
fi
mv "$artifact_root/pr-runtime-images-pr-merge-dddddddddddddddddddddddddddddddddddddddd" "$artifact_dir"
mkdir "$artifact_root/nested"
mv "$artifact_dir" "$artifact_root/nested/$artifact_name"
if run_validation; then
  echo "nested artifact directory was accepted" >&2
  exit 1
fi
mv "$artifact_root/nested/$artifact_name" "$artifact_dir"
mv "$artifact_dir"/* "$artifact_root/"
rmdir "$artifact_dir"
if run_validation; then
  echo "flat download root was accepted" >&2
  exit 1
fi
mkdir "$artifact_dir"
mv "$artifact_root/pr-runtime-services.txt" "$artifact_root/pr-runtime-provenance.json" "$artifact_root/pr-runtime-images.tar.gz" "$artifact_dir/"
if [[ -e "$fixture_dir/registry-marker" ]]; then
  echo "valid source validation reached a registry operation" >&2
  exit 1
fi

sequence_dir="$fixture_dir/pull-request-sequence"
mkdir -p "$sequence_dir"
python3 - "$fixture_dir/pull-request.json" "$sequence_dir" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
directory = Path(sys.argv[2])
pending = dict(source, mergeable=None, mergeable_state="unknown")
Path(directory / "1.json").write_text(json.dumps(pending), encoding="utf-8")
Path(directory / "2.json").write_text(json.dumps(source), encoding="utf-8")
Path(directory / "last.json").write_text(json.dumps(pending), encoding="utf-8")
PY
PULL_REQUEST_SEQUENCE_DIR="$sequence_dir" run_validation
test "$(<"$sequence_dir/count")" -eq 2

python3 - "$sequence_dir" <<'PY'
import json
import sys
from pathlib import Path

directory = Path(sys.argv[1])
pending = json.loads((directory / "1.json").read_text(encoding="utf-8"))
for number in (1, 2, 3):
    (directory / f"{number}.json").write_text(json.dumps(pending), encoding="utf-8")
(directory / "last.json").write_text(json.dumps(pending), encoding="utf-8")
PY
if PULL_REQUEST_SEQUENCE_DIR="$sequence_dir" run_validation; then
  echo "publisher accepted a pull request whose transient mergeability never resolved" >&2
  exit 1
fi
test "$(<"$sequence_dir/count")" -eq 3
unset PULL_REQUEST_SEQUENCE_DIR

python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["base"]["ref"] = "feature/stack"
payload["labels"] = [{"name": "preview:priority"}]
path.write_text(json.dumps(payload), encoding="utf-8")
PY
run_validation
python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["labels"] = []
path.write_text(json.dumps(payload), encoding="utf-8")
PY
if run_validation; then
  echo "publisher accepted an unlabelled stacked pull request" >&2
  exit 1
fi

python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["labels"] = [{"name": "preview:priority"}]
path.write_text(json.dumps(payload), encoding="utf-8")
PY

default_branch_sha='eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'
python3 - "$fixture_dir/pull-request.json" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
payload["base"]["ref"] = "develop"
payload["labels"] = [{"name": "preview:priority"}]
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
