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
smoke_scope_script="$fixture_dir/runtime-smoke-scope.js"
refresh_selector_script="$fixture_dir/runtime-refresh-selector.js"
failed_reconcile_script="$fixture_dir/failed-runtime-reconcile.js"

python3 - "$WORKFLOW" "$RUNTIME_WORKFLOW" "$validation_script" "$publisher_script" "$runtime_script" "$resolver_script" "$dispatch_script" "$smoke_scope_script" "$refresh_selector_script" "$failed_reconcile_script" <<'PY'
import re
import sys
from pathlib import Path

import yaml

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
runtime_definition = yaml.load(runtime_workflow, Loader=yaml.BaseLoader)
image_meta = runtime_definition["jobs"]["image-meta"]
smoke_scope = next(step for step in image_meta["steps"] if step.get("name") == "Detect PR smoke scopes")
Path(sys.argv[8]).write_text(smoke_scope["with"]["script"], encoding="utf-8")
refresh_job = runtime_definition["jobs"]["dispatch-pr-base-refreshes"]
refresh_script = next(step["with"]["script"] for step in refresh_job["steps"] if "with" in step and "script" in step["with"])
deadline_start = refresh_script.index("const dispatcherStartedAt = Date.now();")
deadline_end = refresh_script.index("const workflowRun = context.payload.workflow_run;", deadline_start)
selector_start = refresh_script.index("const runtimePrefixes = [")
selector_end = refresh_script.index("const dependencyBotAuthors = new Set([", selector_start)
Path(sys.argv[9]).write_text(
    refresh_script[deadline_start:deadline_end] + refresh_script[selector_start:selector_end],
    encoding="utf-8",
)
publisher_definition = yaml.load(workflow, Loader=yaml.BaseLoader)
reconcile_job = publisher_definition["jobs"]["reconcile-failed-pr-runtime"]
reconcile_step = next(step for step in reconcile_job["steps"] if step.get("name") == "Reconcile exact failed original runtime")
Path(sys.argv[10]).write_text(reconcile_step["with"]["script"], encoding="utf-8")
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

node - "$resolver_script" "$dispatch_script" "$RUNTIME_WORKFLOW" "$WORKFLOW" "$fixture_dir/download-inputs.json" "$smoke_scope_script" "$refresh_selector_script" <<'JS'
const fs = require("fs");
const assert = require("assert/strict");
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const resolver = new AsyncFunction("github", "context", "core", "Date", "setTimeout", fs.readFileSync(process.argv[2], "utf8"));
const dispatch = new AsyncFunction("github", "context", fs.readFileSync(process.argv[3], "utf8"));
const smokeScope = new AsyncFunction("github", "context", "core", fs.readFileSync(process.argv[7], "utf8"));
const refreshSelector = new AsyncFunction("github", "context", "core", "Date", "AbortSignal",
  `${fs.readFileSync(process.argv[8], "utf8")}\nreturn hasRuntimeChange;`);
const sha = character => character.repeat(40);
const title = `Build Runtime Images secure-pr-artifact pr-42 base-${sha("b")} head-${sha("a")} merge-${sha("c")} mode-required`;
const repository = { full_name: "benhook1013/FireMUD", default_branch: "develop" };
const source = { id: 4242, workflow_id: 777, run_attempt: 1, path: ".github/workflows/runtime-images.yml", name: title, display_title: title, event: "repository_dispatch", repository, head_repository: repository, head_branch: "develop", head_sha: sha("b"), status: "completed", conclusion: "success" };
const baseContext = { eventName: "repository_dispatch", ref: "refs/heads/develop", repo: {owner: "benhook1013", repo: "FireMUD"}, runId: 4242, payload: { repository, action: "pr-runtime-image-publication", client_payload: { source_run_id: "4242" } } };
async function resolve({ context = baseContext, runs = [source], workflow = { id: 777, name: "Build Runtime Images", path: source.path }, error = false, jobs = [] } = {}) {
  let reads = 0, clock = 0;
  const output = {};
  const github = { paginate: async () => jobs, rest: { actions: {
    getWorkflow: async () => ({data: workflow}),
    getWorkflowRun: async request => { assert.equal(request.run_id, 4242); reads++; if (error) throw Error("API unavailable"); return {data: runs[Math.min(reads - 1, runs.length - 1)]}; },
  } } };
  await resolver(github, context, {notice: () => {}, setOutput: (key, value) => {output[key] = value;}}, {now: () => {clock += 60000; return clock;}}, fn => fn());
  if (jobs.some(job => (job.steps ?? []).some(step => step.conclusion === "success"))) {
    assert.deepEqual(output, {}, "exception attempts must expose no publication source");
    return {reads, output};
  }
  assert.equal(output.title, title);
  assert.equal(output.artifact_name, `pr-runtime-images-pr-merge-${sha("c")}`);
  assert.equal(JSON.parse(output.source).status, "completed");
  return {reads, output};
}
const runtimeWorkflow = fs.readFileSync(process.argv[4], "utf8");
const diagnosticPaths = [
  "dev-tools/validation/collect-gradle-compile-failure.py",
  "dev-tools/validation/compile-failure-facts.init.gradle",
];
async function smokeDecision(paths) {
  const entries = paths.map(filename => ({filename}));
  const outputs = {};
  await smokeScope(
    {rest: {pulls: {listFiles: "listFiles"}}, paginate: async () => entries},
    {eventName: "pull_request", repo: {}, payload: {pull_request: {number: 42, changed_files: entries.length}}},
    {warning: () => {}, setOutput: (key, value) => {outputs[key] = value;}},
  );
  return outputs.runtime_smoke_required;
}
const refreshEntries = [];
let refreshNow = Date.parse("2026-01-08T00:00:00Z");
const refreshDeadline = refreshNow + 12 * 60 * 1000;
const refreshSignals = [];
const refreshRequests = [];
const runtimeSelector = refreshSelector(
  {rest: {pulls: {listFiles: async request => {
    refreshRequests.push(request);
    refreshNow += 250;
    assert.equal(request.per_page, 100);
    assert.equal(request.page, 1);
    return {data: refreshEntries};
  }}}},
  {repo: {}, payload: {}},
  {warning: () => {}},
  {now: () => refreshNow},
  {timeout: milliseconds => {
    const signal = {timeoutMs: milliseconds};
    refreshSignals.push(signal);
    return signal;
  }},
);
async function refreshDecision(paths) {
  refreshEntries.splice(0, refreshEntries.length, ...paths.map(filename => ({filename})));
  const requestIndex = refreshRequests.length;
  const nowBeforeRequest = refreshNow;
  const hasRuntimeChange = await runtimeSelector;
  const result = await hasRuntimeChange({number: 42, changed_files: refreshEntries.length});
  assert.equal(refreshRequests.length, requestIndex + 1, "changed-file selection performs exactly one direct page request for a short inventory");
  const {request, ...normalOptions} = refreshRequests[requestIndex];
  assert.deepEqual(normalOptions, {pull_number: 42, per_page: 100, page: 1}, "changed-file request preserves its exact normal option shape");
  assert.deepEqual(Object.keys(request).sort(), ["signal"], "changed-file request has only the source-derived AbortSignal option");
  assert.equal(request.signal, refreshSignals.at(-1));
  assert.equal(request.signal.timeoutMs, refreshDeadline - nowBeforeRequest,
    "changed-file request timeout is computed from the remaining actual wall-clock budget");
  return result;
}
const handoffCondition = runtimeWorkflow.split("  dispatch-pr-runtime-publication:\n")[1].match(/^    if: \$\{\{ (.*) \}\}$/m)[1]
  .replace(/needs\.([a-z-]+)/g, (_, key) => `needs[${JSON.stringify(key)}]`);
const admitted = new Function("github", "needs", "always", "cancelled", "format", `return ${handoffCondition};`);
const trigger = {event_name: "repository_dispatch", event: {action: "pr-runtime-base-refresh", repository}, ref: "refs/heads/develop"};
const success = {"image-meta": {result: "success", outputs: {controller_smoke_required: "false"}}, "pr-local-smoke": {result: "success", outputs: {publishable: "true"}}, "pr-controller-smoke": {result: "skipped"}};
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
for (const publishable of ["false", "", undefined]) {
  assert.equal(admits(trigger, {...success, "pr-local-smoke": {result: "success", outputs: {publishable}}}), false);
}
(async () => {
  for (const path of diagnosticPaths) {
    assert.equal(await smokeDecision([path]), "false", `${path} must not require runtime smoke`);
    assert.equal(await refreshDecision([path]), false, `${path} must not select a runtime refresh`);
  }
  assert.equal(await smokeDecision(diagnosticPaths), "false", "diagnostics-only changes must not require runtime smoke");
  assert.equal(await refreshDecision(diagnosticPaths), false, "diagnostics-only changes must not select a runtime refresh");
  for (const path of ["services/game-session-service/Dockerfile", "dev-tools/build-local-smoke-images.sh"]) {
    assert.equal(await smokeDecision([path]), "true", `${path} must continue to require runtime smoke`);
    assert.equal(await refreshDecision([path]), true, `${path} must continue to select a runtime refresh`);
  }
  const requestCountBeforeDeadline = refreshRequests.length;
  const signalCountBeforeDeadline = refreshSignals.length;
  refreshNow = refreshDeadline;
  const hasRuntimeChange = await runtimeSelector;
  await assert.rejects(hasRuntimeChange({number: 42, changed_files: 1}), error =>
    error.code === "REQUEST_DEADLINE_EXPIRED" && /request not issued/.test(error.message),
  "changed-file lookup must fail closed at the actual fan-out wall-clock deadline");
  assert.equal(refreshRequests.length, requestCountBeforeDeadline,
    "an expired changed-file deadline must not reach the GitHub API mock");
  assert.equal(refreshSignals.length, signalCountBeforeDeadline,
    "an expired deadline must not manufacture an already-expired AbortSignal");
  let dispatched;
  await dispatch({rest: {repos: {createDispatchEvent: async payload => {dispatched = payload;}}}}, baseContext);
  assert.deepEqual(dispatched, {...baseContext.repo, event_type: "pr-runtime-image-publication", client_payload: {source_run_id: "4242"}});
  const direct = await resolve();
  const exceptionJob = {name: "PR Full-Stack Smoke (runtime-required=true)", steps: [{
    name: "Known canonical transition PLAY failure with successful authenticated cleanup",
    status: "completed", conclusion: "success"}]};
  assert.deepEqual((await resolve({jobs: [exceptionJob]})).output, {});
  assert.equal((await resolve({jobs: [{...exceptionJob, steps: [{...exceptionJob.steps[0], conclusion: "skipped"}]}]})).output.title, title);
  const pending = {...source, status: "in_progress", conclusion: null};
  assert.equal((await resolve({runs: [pending, source]})).reads, 2);
  const callbackContext = {...baseContext, eventName: "workflow_run", payload: {repository, workflow_run: source}};
  const callback = await resolve({context: callbackContext});
  assert.equal(callback.output.title, direct.output.title);
  assert.equal(callback.output.artifact_name, direct.output.artifact_name);
  const prSource = {...source, event: "pull_request", head_sha: sha("a"), head_branch: "feature/ci"};
  const prCallback = {...callbackContext, payload: {repository, workflow_run: prSource}};
  await assert.rejects(resolve({runs: [prSource]}), /publication source run provenance is not trusted/);
  assert.equal((await resolve({runs: [prSource], context: prCallback})).output.artifact_name, direct.output.artifact_name);
  const publisherWorkflow = fs.readFileSync(process.argv[5], "utf8");
  assert.ok(publisherWorkflow.includes("if: ${{ needs.source.outputs.source != '' }}"), "no source must skip privileged publication");
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

# Execute the actual failed-source reconciler with mocked GitHub APIs. The clock
# and AbortSignal mocks preserve its wall-clock semantics without network access.
node - "$failed_reconcile_script" <<'JS'
const assert = require("node:assert/strict");
const fs = require("node:fs");
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const reconcile = new AsyncFunction(
  "github", "context", "core", "Date", "AbortSignal", "setTimeout",
  fs.readFileSync(process.argv[2], "utf8"),
);
const sha = letter => letter.repeat(40);
const repository = "benhook1013/FireMUD";
const owner = "benhook1013";
const repo = "FireMUD";
const base = sha("b");
const head = sha("a");
const merge = sha("c");
const sourceTitle = `Build Runtime Images secure-pr-artifact pr-42 base-${base} head-${head} merge-${merge} mode-required`;
const ciTitle = `CI — Validation pr-42 base-${base} head-${head}`;
const source = {
  id: 4242, workflow_id: 777, run_attempt: 1, path: ".github/workflows/runtime-images.yml",
  name: "Build Runtime Images", display_title: sourceTitle, event: "pull_request",
  status: "completed", conclusion: "failure", repository: {full_name: repository},
  head_repository: {full_name: repository}, head_sha: head, head_branch: "feature/ci",
  created_at: "2026-01-01T00:00:00Z",
};
const ciRun = {
  id: 7001, workflow_id: 778, run_attempt: 1, path: ".github/workflows/ci.yml",
  name: "CI — Validation", display_title: ciTitle, event: "pull_request", status: "completed",
  conclusion: "success", repository: {full_name: repository}, head_repository: {full_name: repository},
  head_sha: head, head_branch: "feature/ci",
};
const pullRequest = {
  number: 42, state: "open", head: {sha: head, ref: "feature/ci", repo: {full_name: repository}},
  base: {ref: "develop", repo: {full_name: repository}}, mergeable: true, merge_commit_sha: merge,
};
const gate = {
  id: 8001, run_id: ciRun.id, run_attempt: ciRun.run_attempt, head_sha: head,
  workflow_name: "CI — Validation", name: "Validation Gate", status: "completed", conclusion: "success",
  check_run_url: `https://api.github.com/repos/${repository}/check-runs/8001`,
  steps: [
    {name: "Preserve successful required gate on metadata-only edit", status: "completed", conclusion: "skipped"},
    {name: "Enforce validation success", status: "completed", conclusion: "success"},
  ],
};
const typedRefresh = {
  ...source, id: 8500, event: "repository_dispatch", head_branch: "develop", head_sha: base,
  status: "completed", conclusion: "cancelled", created_at: "2026-01-08T00:00:00Z",
};

async function runReconcile({
  ambiguousPost = true, expireFirstRequest = false, initialDiscoveryUnavailable = false,
  ciRuns = [ciRun], ciNativeRuns = {}, ciJobsByRun = {}, postVisibilityRuns = null,
  postVisibilityFailures = [], preflightRefreshRuns = [], refreshNativeRuns = {},
} = {}) {
  let now = Date.parse("2026-01-08T00:00:00Z");
  let expire = expireFirstRequest;
  let failInitialDiscovery = initialDiscoveryUnavailable;
  let visibleRun = null;
  let postAttempts = 0;
  const requests = [];
  const inventoryRequests = [];
  const nativeJobRunIds = [];
  const nativeRefreshRunIds = [];
  const nativeRefreshReadCounts = new Map();
  const signals = [];
  const dispatchRequests = [];
  const warnings = [];
  const infos = [];
  class FixtureDate extends Date {
    constructor(...args) { super(...(args.length ? args : [now])); }
    static now() { return now; }
  }
  const timeoutSignals = {
    timeout(milliseconds) {
      assert(Number.isSafeInteger(milliseconds) && milliseconds > 0,
        "AbortSignal.timeout must receive positive remaining wall-clock time");
      const expiresAt = now + milliseconds;
      const signal = {timeoutMs: milliseconds, get aborted() { return now >= expiresAt; }};
      signals.push(signal);
      return signal;
    },
  };
  const record = (name, options, expectedNormal, oneShot = false) => {
    assert(options && typeof options === "object", `${name} must receive its normal request object`);
    const {request, ...normal} = options;
    assert.deepEqual(normal, expectedNormal, `${name} must preserve its exact normal request shape`);
    assert(request && typeof request === "object", `${name} must receive the AbortSignal request option`);
    assert.deepEqual(Object.keys(request).sort(), oneShot ? ["retries", "signal"] : ["signal"],
      `${name} must preserve its exact request-option shape`);
    if (oneShot) assert.equal(request.retries, 0, "repository dispatch must explicitly disable transport retries");
    assert(signals.includes(request.signal), `${name} must use the source-derived wall-clock AbortSignal`);
    assert(Number.isSafeInteger(request.signal.timeoutMs) && request.signal.timeoutMs > 0,
      `${name} must use a positive remaining deadline`);
    assert.equal(request.signal.aborted, false, `${name} must not be issued with an already-expired signal`);
    requests.push({name, options, signal: request.signal});
    if (expire) {
      expire = false;
      now += request.signal.timeoutMs;
      assert.equal(request.signal.aborted, true, "API mock must observe AbortSignal expiry as wall time advances");
      const error = new Error("mocked API request aborted at its deadline");
      error.name = "TimeoutError";
      throw error;
    }
    return request.signal;
  };
  const context = {
    eventName: "workflow_run",
    repo: {owner, repo},
    payload: {
      repository: {full_name: repository, default_branch: "develop"},
      workflow_run: source,
    },
  };
  const github = {
    rest: {
      actions: {
        getWorkflow: async options => {
          const workflowId = options.workflow_id;
          record("actions.getWorkflow", options, {owner, repo, workflow_id: workflowId});
          return {data: workflowId === "runtime-images.yml"
            ? {id: 777, path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images"}
            : {id: 778, path: ".github/workflows/ci.yml", name: "CI — Validation"}};
        },
        getWorkflowRun: async options => {
          record("actions.getWorkflowRun", options, {owner, repo, run_id: options.run_id});
          if (options.run_id === source.id) return {data: source};
          const refresh = preflightRefreshRuns.find(run => run.id === options.run_id);
          if (refresh || Object.hasOwn(refreshNativeRuns, options.run_id)) {
            nativeRefreshRunIds.push(options.run_id);
            const readIndex = nativeRefreshReadCounts.get(options.run_id) ?? 0;
            nativeRefreshReadCounts.set(options.run_id, readIndex + 1);
            const responses = Object.hasOwn(refreshNativeRuns, options.run_id)
              ? refreshNativeRuns[options.run_id] : refresh;
            const response = Array.isArray(responses) ? responses[Math.min(readIndex, responses.length - 1)] : responses;
            if (response instanceof Error) throw response;
            return {data: response};
          }
          return {data: ciNativeRuns[options.run_id] ?? ciRun};
        },
        listJobsForWorkflowRunAttempt: async options => {
          const {run_id, attempt_number, page} = options;
          record("actions.listJobsForWorkflowRunAttempt", options, {
            owner, repo, run_id, attempt_number, per_page: 100, page,
          });
          assert.equal(attempt_number, (ciNativeRuns[run_id] ?? ciRun).run_attempt);
          assert(Number.isInteger(page) && page > 0);
          nativeJobRunIds.push(run_id);
          const jobs = ciJobsByRun[run_id] ?? [gate];
          return {data: {total_count: jobs.length, jobs: jobs.slice((page - 1) * 100, page * 100)}};
        },
        listWorkflowRuns: async options => {
          const {workflow_id: workflowId, event, head_sha: requestedHead, created, per_page, page} = options;
          const normal = {owner, repo, workflow_id: workflowId, event, per_page, page};
          if (requestedHead !== undefined) normal.head_sha = requestedHead;
          if (created !== undefined) normal.created = created;
          record("actions.listWorkflowRuns", options, normal);
          inventoryRequests.push(options);
          if (failInitialDiscovery && workflowId === "runtime-images.yml" &&
              event === "pull_request" && created === undefined) {
            failInitialDiscovery = false;
            throw Object.assign(new Error("fixture initial runtime discovery unavailable"), {status: 503});
          }
          let runs = [];
          if (workflowId === "ci.yml") runs = ciRuns;
          if (workflowId === "runtime-images.yml" && event === "repository_dispatch") {
            if (visibleRun) {
              const failure = postVisibilityFailures.shift();
              if (failure) {
                throw Object.assign(new Error("fixture visibility failure"), {
                  status: failure.status, response: {headers: failure.headers},
                });
              }
              runs = postVisibilityRuns ?? [visibleRun];
            } else {
              runs = preflightRefreshRuns;
            }
          }
          return {data: {total_count: runs.length, workflow_runs: runs}};
        },
      },
      pulls: {
        get: async options => {
          record("pulls.get", options, {owner, repo, pull_number: 42});
          return {data: pullRequest};
        },
      },
      git: {
        getRef: async options => {
          record("git.getRef", options, {owner, repo, ref: "heads/develop"});
          return {data: {ref: "refs/heads/develop", object: {sha: base}}};
        },
      },
      repos: {
        getCommit: async options => {
          record("repos.getCommit", options, {owner, repo, ref: merge});
          return {data: {sha: merge, parents: [{sha: base}, {sha: head}]}};
        },
        createDispatchEvent: async options => {
          record("repos.createDispatchEvent", options, {
            owner, repo, event_type: "pr-runtime-base-refresh",
            client_payload: {pr_number: "42", base_ref: "develop", base_sha: base, head_sha: head, merge_sha: merge},
          }, true);
          postAttempts += 1;
          dispatchRequests.push(options);
          now += 1000;
          visibleRun = {
            id: 9001, workflow_id: 777, run_attempt: 1,
            path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images",
            display_title: sourceTitle, event: "repository_dispatch", status: "queued", conclusion: null,
            repository: {full_name: repository}, head_repository: {full_name: repository},
            head_branch: "develop", head_sha: base, created_at: new FixtureDate().toISOString(),
          };
          if (ambiguousPost) throw new Error("simulated ambiguous dispatch response");
        },
      },
    },

  };
  let failure = null;
  try {
    await reconcile(github, context, {warning: message => warnings.push(message), info: message => infos.push(message)},
      FixtureDate, timeoutSignals, (resolve, milliseconds) => { now += milliseconds; resolve(); });
  } catch (error) {
    failure = error;
  }
  return {failure, now, requests, signals, inventoryRequests, nativeJobRunIds, nativeRefreshRunIds,
    dispatchRequests, postAttempts, warnings, infos};
}

(async () => {
  const uncertainPost = await runReconcile();
  assert.equal(uncertainPost.failure, null, "visible exact refresh reconciles an ambiguous response");
  assert.equal(uncertainPost.postAttempts, 1, "an uncertain repository dispatch POST is never retransmitted");
  assert.equal(uncertainPost.dispatchRequests.length, 1);
  assert.equal(uncertainPost.dispatchRequests[0].request.retries, 0,
    "the production one-shot request explicitly disables Octokit retries");
  assert(uncertainPost.warnings.some(message => message.includes("no retry issued") && message.includes("ambiguous dispatch response")));
  assert(uncertainPost.infos.some(message => message.includes("exact refresh is visible")));
  assert(uncertainPost.requests.some(({name}) => name === "actions.listWorkflowRuns"),
    "the one-shot handoff must be followed by source-derived visibility observation");
  const postVisibilityFloor = "2026-01-08T00:00:00.000Z..*";
  const assertExactPostVisibilityFilter = result => {
    const observationReads = result.inventoryRequests.filter(request => request.created === postVisibilityFloor);
    assert(observationReads.length > 0, "post-POST observation must query from the dispatch timestamp second");
    for (const request of observationReads) {
      const {request: requestOptions, ...normalOptions} = request;
      assert.deepEqual(normalOptions, {
        owner, repo, workflow_id: "runtime-images.yml", event: "repository_dispatch",
        created: postVisibilityFloor, per_page: 100, page: 1,
      }, "visibility must be a complete bounded native producer query on the exact post-POST time filter");
      assert.deepEqual(Object.keys(requestOptions).sort(), ["signal"],
        "post-POST visibility query carries only its source-derived deadline signal");
    }
    assert.equal(result.postAttempts, 1, "post-POST visibility must never retransmit a dispatch");
  };
  assertExactPostVisibilityFilter(uncertainPost);
  assert(uncertainPost.requests.every(({signal}) => signal.timeoutMs > 0),
    "every mocked GitHub request receives a positive wall-clock AbortSignal budget");
  const rateLimitedVisibility = await runReconcile({
    postVisibilityFailures: [{status: 403, headers: {"retry-after": "20"}}],
  });
  assert.equal(rateLimitedVisibility.failure, null);
  assert.equal(rateLimitedVisibility.now - uncertainPost.now, 20000);
  assertExactPostVisibilityFilter(rateLimitedVisibility);
  const deniedVisibility = await runReconcile({postVisibilityFailures: [{status: 403}]});
  assert.match(deniedVisibility.failure?.message, /fixture visibility failure/);
  assert.equal(deniedVisibility.now, uncertainPost.now);
  assertExactPostVisibilityFilter(deniedVisibility);
  const retriedVisibilityDeadline = await runReconcile({
    postVisibilityFailures: [{status: 429, headers: {"retry-after": "300"}}],
  });
  assert.match(retriedVisibilityDeadline.failure?.message, /visibility is unknown/);
  assert.equal(retriedVisibilityDeadline.now, uncertainPost.now + 120000);
  assertExactPostVisibilityFilter(retriedVisibilityDeadline);
  const pagedJobs = await runReconcile({
    ciJobsByRun: {[ciRun.id]: [...Array.from({length: 100}, (_, i) => ({id: 500 + i, name: "Sibling"})), gate]},
  });
  assert.equal(pagedJobs.failure, null);
  const jobPages = pagedJobs.requests.filter(row => row.name === "actions.listJobsForWorkflowRunAttempt");
  assert.deepEqual(jobPages.map(row => row.options.page), [1, 2, 1, 2]);
  assert.equal(new Set(jobPages.map(row => row.signal)).size, jobPages.length);
  assert.equal(pagedJobs.postAttempts, 1);
  const sameSecondVisible = await runReconcile({
    postVisibilityRuns: [{
      id: 9001, workflow_id: 777, run_attempt: 1,
      path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images",
      display_title: sourceTitle, event: "repository_dispatch", status: "queued", conclusion: null,
      repository: {full_name: repository}, head_repository: {full_name: repository},
      head_branch: "develop", head_sha: base, created_at: "2026-01-08T00:00:00Z",
    }],
  });
  assert.equal(sameSecondVisible.failure, null,
    "a unique exact native producer created in the POST's timestamp second is visible");
  assert.equal(sameSecondVisible.postAttempts, 1);
  assertExactPostVisibilityFilter(sameSecondVisible);
  const stalePostVisibility = await runReconcile({
    postVisibilityRuns: [{
      id: 9001, workflow_id: 777, run_attempt: 1,
      path: ".github/workflows/runtime-images.yml", name: "Build Runtime Images",
      display_title: sourceTitle, event: "repository_dispatch", status: "queued", conclusion: null,
      repository: {full_name: repository}, head_repository: {full_name: repository},
      head_branch: "develop", head_sha: base, created_at: "2026-01-07T23:59:59Z",
    }],
  });
  assert.match(stalePostVisibility.failure?.message ?? "", /visibility is unknown/,
    "an otherwise exact native run created before the dispatch second cannot confirm the POST");
  assert.equal(stalePostVisibility.postAttempts, 1);
  assert.equal(stalePostVisibility.inventoryRequests.filter(request =>
    request.created === postVisibilityFloor
  ).length, 24, "out-of-filter stale evidence remains unaccepted throughout the bounded observation window");
  assertExactPostVisibilityFilter(stalePostVisibility);
  for (const conclusion of ["cancelled", "timed_out"]) {
    for (const ambiguousPost of [false, true]) {
      const unusable = {...typedRefresh, conclusion, run_attempt: 2};
      const recovered = await runReconcile({preflightRefreshRuns: [unusable], ambiguousPost});
      assert.equal(recovered.failure, null, `verified ${conclusion} typed refresh must not suppress recovery`);
      assert.deepEqual(recovered.nativeRefreshRunIds, [unusable.id, unusable.id],
        "both initial and final preflight must freshly verify the same positive native attempt");
      const nativeReads = recovered.requests.filter(row =>
        row.name === "actions.getWorkflowRun" && row.options.run_id === unusable.id);
      assert.equal(new Set(nativeReads.map(row => row.signal)).size, 2,
        "each terminal-result verification uses a fresh pre-dispatch deadline signal");
      assert.equal(recovered.dispatchRequests.length, 1);
      assertExactPostVisibilityFilter(recovered);
    }
  }
  const combinedUnusable = await runReconcile({preflightRefreshRuns: [
    typedRefresh, {...typedRefresh, id: typedRefresh.id + 1, conclusion: "timed_out"},
  ]});
  assert.equal(combinedUnusable.failure, null);
  assert.deepEqual(combinedUnusable.nativeRefreshRunIds,
    [typedRefresh.id, typedRefresh.id + 1, typedRefresh.id, typedRefresh.id + 1],
    "every exact terminal candidate must be freshly verified at both preflight fences");
  assertExactPostVisibilityFilter(combinedUnusable);
  for (const change of [
    {id: typedRefresh.id + 1}, {run_attempt: 0}, {run_attempt: 2}, {workflow_id: 778},
    {path: ".github/workflows/other.yml"}, {path: `${typedRefresh.path}@refs/heads/develop`},
    {name: "other"}, {name: sourceTitle}, {display_title: sourceTitle.replace(base, sha("d"))},
    {event: "pull_request"}, {repository: {full_name: "fork/FireMUD"}},
    {head_repository: {full_name: "fork/FireMUD"}}, {head_branch: "feature/ci"},
    {head_sha: sha("d")}, {head_sha: "invalid"}, {status: "in_progress", conclusion: null},
    {status: undefined}, {conclusion: "timed_out"}, {conclusion: "failure"},
    {conclusion: "success"}, {conclusion: undefined},
  ]) {
    const drifted = await runReconcile({
      preflightRefreshRuns: [typedRefresh], refreshNativeRuns: {[typedRefresh.id]: {...typedRefresh, ...change}},
    });
    assert.equal(drifted.failure, null);
    assert.equal(drifted.postAttempts, 0, `native terminal-result drift must retain the hold: ${JSON.stringify(change)}`);
    assert.deepEqual(drifted.nativeRefreshRunIds, [typedRefresh.id], "unproven native evidence is never retried");
    assert(drifted.warnings.some(message => message.includes("unproven or superseded")));
  }
  const finalPreflightDrift = await runReconcile({
    preflightRefreshRuns: [typedRefresh],
    refreshNativeRuns: {[typedRefresh.id]: [typedRefresh, {...typedRefresh, run_attempt: 2}]},
  });
  assert.equal(finalPreflightDrift.failure, null);
  assert.equal(finalPreflightDrift.postAttempts, 0, "a superseding attempt at the final native fence retains the hold");
  assert.deepEqual(finalPreflightDrift.nativeRefreshRunIds, [typedRefresh.id, typedRefresh.id]);
  for (const unavailable of [undefined, null, ...[403, 404, 503].map(status =>
    Object.assign(new Error("fixture native terminal evidence unavailable"), {status}))]) {
    const held = await runReconcile({
      preflightRefreshRuns: [typedRefresh], refreshNativeRuns: {[typedRefresh.id]: unavailable},
    });
    assert.equal(held.failure, null);
    assert.equal(held.postAttempts, 0, "unavailable native terminal evidence cannot authorize recovery");
    assert.deepEqual(held.nativeRefreshRunIds, [typedRefresh.id], "unavailable verification is not retried");
    assert(held.warnings.some(message => message.includes("recovery remains held without retry")));
  }
  for (const change of [
    {status: "queued", conclusion: null}, {status: "in_progress", conclusion: null},
    {status: "completed", conclusion: "success"}, {status: "completed", conclusion: "skipped"},
    {status: "completed", conclusion: null},
  ]) {
    const held = await runReconcile({preflightRefreshRuns: [{...typedRefresh, ...change}]});
    assert.equal(held.failure, null);
    assert.equal(held.postAttempts, 0, "all other exact typed results continue suppressing recovery");
    assert.deepEqual(held.nativeRefreshRunIds, [], "only cancelled/timed_out results enter native verification");
  }
  const legacyFailedPlay = {...typedRefresh, id: typedRefresh.id + 1, conclusion: "failure"};
  const legacyFailureHold = await runReconcile({preflightRefreshRuns: [typedRefresh, legacyFailedPlay]});
  assert.equal(legacyFailureHold.failure, null);
  assert.equal(legacyFailureHold.postAttempts, 0,
    "a legacy failed PLAY typed refresh retains its hold even alongside a verified cancelled result");
  assert.deepEqual(legacyFailureHold.nativeRefreshRunIds, [typedRefresh.id]);
  assert(legacyFailureHold.warnings.some(message => message.includes(String(legacyFailedPlay.id)) &&
    message.includes("failed") && message.includes("explicit investigation, not automatic replay")),
  "legacy failure holds must be reported separately from supported terminal recovery");
  const preexistingUnusableReceipt = await runReconcile({
    preflightRefreshRuns: [typedRefresh], postVisibilityRuns: [typedRefresh],
  });
  assert.match(preexistingUnusableReceipt.failure?.message ?? "", /visibility is unknown/,
    "a verified unusable ID in the dispatch second remains in the complete pre-POST snapshot, not a new receipt");
  assert.deepEqual(preexistingUnusableReceipt.nativeRefreshRunIds, [typedRefresh.id, typedRefresh.id],
    "post-POST visibility never reclassifies terminal producer outcomes");
  assertExactPostVisibilityFilter(preexistingUnusableReceipt);
  for (const conclusion of ["cancelled", "timed_out", "failure"]) {
    const terminalReceipt = await runReconcile({postVisibilityRuns: [{...typedRefresh, id: 9001, conclusion}]});
    assert.equal(terminalReceipt.failure, null, `a new exact ${conclusion} run still establishes POST receipt, not success`);
    assert.deepEqual(terminalReceipt.nativeRefreshRunIds, [], "receipt must not require a usable terminal producer result");
    assertExactPostVisibilityFilter(terminalReceipt);
  }
  const unavailableInitialDiscovery = await runReconcile({initialDiscoveryUnavailable: true});
  assert.match(unavailableInitialDiscovery.failure?.message ?? "", /initial runtime discovery unavailable/);
  assert.equal(unavailableInitialDiscovery.postAttempts, 0,
    "unavailable initial source inventory is not treated as absence or permission to dispatch");
  assert.equal(unavailableInitialDiscovery.inventoryRequests[0].event, "pull_request");
  assert.equal(unavailableInitialDiscovery.inventoryRequests[0].workflow_id, "runtime-images.yml");
  const newestCancelledCi = {
    ...ciRun, id: 7002, status: "completed", conclusion: "cancelled",
  };
  const olderSuccessfulCi = {...ciRun, id: 7001, status: "completed", conclusion: "success"};
  const cancelledNewestMissingEvidence = await runReconcile({
    ciRuns: [newestCancelledCi, olderSuccessfulCi],
    ciNativeRuns: {[newestCancelledCi.id]: newestCancelledCi, [olderSuccessfulCi.id]: olderSuccessfulCi},
    ciJobsByRun: {[newestCancelledCi.id]: [], [olderSuccessfulCi.id]: [gate]},
  });
  assert.equal(cancelledNewestMissingEvidence.failure, null);
  assert.equal(cancelledNewestMissingEvidence.postAttempts, 0,
    "a cancelled newest CI candidate with missing native jobs cannot reveal older success or authorize refresh");
  assert.deepEqual(cancelledNewestMissingEvidence.nativeJobRunIds,
    [newestCancelledCi.id, newestCancelledCi.id, newestCancelledCi.id],
    "classification retries only the newest cancelled attempt and never falls back to older CI evidence");
  const expiredRequest = await runReconcile({expireFirstRequest: true});
  assert.equal(expiredRequest.failure?.name, "TimeoutError", "request abortion at the wall-clock deadline remains visible");
  assert.equal(expiredRequest.requests.length, 1, "deadline abortion prevents subsequent GitHub API requests");
  assert.equal(expiredRequest.requests[0].name, "actions.getWorkflow");
  assert.equal(expiredRequest.requests[0].signal.timeoutMs, 120000,
    "the reconciler starts with its exact two-minute pre-dispatch wall-clock budget");
  assert.equal(expiredRequest.requests[0].signal.aborted, true, "the API mock observed the signal expire");
  assert.equal(expiredRequest.postAttempts, 0, "deadline abortion fails closed before repository dispatch");
  process.stdout.write("Failed-source reconciliation terminal-preflight, deadline and one-shot fixtures passed\n");
  process.stdout.write("Legacy failed PLAY hold fixture passed; explicit recovery remains separate\n");
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
{"id":${source_run_id},"run_attempt":1,"workflow_id":${workflow_id},"name":"Build Runtime Images","path":".github/workflows/runtime-images.yml","event":"pull_request","status":"completed","conclusion":"success","display_title":"${source_title}","head_sha":"${head_sha}","head_branch":"feature/ci","repository":{"full_name":"${repository}"},"head_repository":{"full_name":"${repository}"}}
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
  repos/benhook1013/FireMUD/git/ref/heads/*)
    if [[ -n "${BASE_REF_CALLS:-}" ]]; then
      printf '%s\n' "$2" >> "$BASE_REF_CALLS"
      [[ "$2" == "$EXPECTED_BASE_REF_ENDPOINT" ]] || exit 2
      [[ "${MOCK_BASE_REF_MISSING:-}" != true ]] || exit 1
    fi
    cat "$FIXTURE_DIR/base-ref.json"
    ;;
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

# Execute the real credential-free metadata admission for a feature-base bot PR.
# Preview admission is a separate privileged boundary; exact tuple checks remain.
PATH="$fake_bin:$PATH" FIXTURE_DIR="$fixture_dir" python3 - "$RUNTIME_WORKFLOW" <<'PY'
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import quote

fixture = Path(os.environ["FIXTURE_DIR"])
workflow = Path(sys.argv[1]).read_text()
section = workflow.split("      - name: Derive image tags\n", 1)[1].split("      - name: Verify successful CI push for manual publication\n", 1)[0]
script = section.split("        run: |\n", 1)[1]
script = "\n".join(line[10:] if line.startswith("          ") else line for line in script.splitlines())
script_template = script
paths = [fixture / name for name in ("pull-request.json", "merge-commit.json", "base-ref.json")]
originals = [path.read_text() for path in paths]
request, merge, branch = map(json.loads, originals)
request["base"]["ref"] = "feature/stack-parent"
request["user"]["login"] = "renovate[bot]"
request["labels"] = []
branch["ref"] = "refs/heads/feature/stack-parent"
env = {
    **os.environ,
    "GITHUB_REPOSITORY": "benhook1013/FireMUD",
    "GITHUB_REF": "refs/heads/develop",
    "DEFAULT_BRANCH": "develop",
    "EVENT_ACTION": "pr-runtime-base-refresh",
    "EVENT_PR_NUMBER": str(request["number"]),
    "EVENT_BASE_REF": request["base"]["ref"],
    "EVENT_BASE_SHA": branch["object"]["sha"],
    "EVENT_HEAD_SHA": request["head"]["sha"],
    "EVENT_MERGE_SHA": merge["sha"],
    "GITHUB_RUN_ID": "4242",
    "GITHUB_RUN_ATTEMPT": "1",
    "GITHUB_SHA": merge["sha"],
    "GITHUB_OUTPUT": str(fixture / "runtime-meta-output"),
}

def admit(current_request, current_merge, current_branch, overrides=None, event_name="repository_dispatch"):
    for path, payload in zip(paths, (current_request, current_merge, current_branch)):
        path.write_text(json.dumps(payload))
    Path(env["GITHUB_OUTPUT"]).write_text("")
    calls = fixture / "runtime-meta-ref-calls"
    calls.write_text("")
    current_env = {**env, **(overrides or {})}
    current_env.update({
        "BASE_REF_CALLS": str(calls),
        "EXPECTED_BASE_REF_ENDPOINT": "repos/benhook1013/FireMUD/git/ref/heads/" + quote(current_env["EVENT_BASE_REF"], safe="/"),
    })
    script = script_template.replace("${{ github.event_name }}", event_name)
    return subprocess.run(["bash", "-c", script], env=current_env, text=True, capture_output=True)

try:
    accepted = admit(request, merge, branch)
    assert accepted.returncode == 0, accepted.stderr
    output = Path(env["GITHUB_OUTPUT"]).read_text()
    assert f"merge_sha={merge['sha']}\n" in output
    assert f"base_ref={request['base']['ref']}\n" in output
    assert f"image_tag=pr-merge-{merge['sha']}\n" in output
    assert f"base_image=ghcr.io/benhook1013/firemud-base:{merge['sha']}\n" in output
    assert "canonical_play_transition=false\n" in output
    labeled_request = json.loads(json.dumps(request))
    labeled_request["labels"] = [{"name": "canonical-play-transition"}]
    assert admit(labeled_request, merge, branch).returncode == 0
    assert "canonical_play_transition=true\n" in Path(env["GITHUB_OUTPUT"]).read_text()
    # Event labels are a separate marker path; only typed-refresh admission may
    # export verified live-label authority through this metadata output.
    assert admit(labeled_request, merge, branch, event_name="pull_request").returncode == 0
    assert "canonical_play_transition=false\n" in Path(env["GITHUB_OUTPUT"]).read_text()
    for labels in (None, [], [{"name": "other"}], "canonical-play-transition"):
        unlabeled_request = {**request, "labels": labels}
        assert admit(unlabeled_request, merge, branch).returncode == 0
        assert "canonical_play_transition=false\n" in Path(env["GITHUB_OUTPUT"]).read_text()
    assert admit(labeled_request, merge, branch, {"EVENT_ACTION": "other"}).returncode != 0
    assert branch["object"]["sha"] not in dict(line.split("=", 1) for line in output.splitlines())["base_image"]
    literal_marker = fixture / "must-not-execute"
    valid_refs = ("feature/topic+fix", "feature/@/parent", "feature/世界", "feature/#fragment", "feature/%encoded",
                  f"feature/$(touch${{IFS}}{literal_marker})")
    for base_ref in valid_refs:
        ref_request, ref_branch = json.loads(json.dumps(request)), json.loads(json.dumps(branch))
        ref_request["base"]["ref"] = base_ref
        ref_branch["ref"] = "refs/heads/" + base_ref
        accepted_ref = admit(ref_request, merge, ref_branch, {"EVENT_BASE_REF": base_ref})
        assert accepted_ref.returncode == 0, (base_ref, accepted_ref.stderr)
        assert f"base_ref={base_ref}\n" in Path(env["GITHUB_OUTPUT"]).read_text()
        direct_pr = admit(ref_request, merge, ref_branch, {"EVENT_BASE_REF": base_ref}, event_name="pull_request")
        assert direct_pr.returncode == 0, (base_ref, direct_pr.stderr)
        assert f"base_ref={base_ref}\n" in Path(env["GITHUB_OUTPUT"]).read_text()
        assert not literal_marker.exists(), "base ref shell metacharacters executed instead of remaining literal env data"
        stale_ref = {**ref_branch, "object": {"sha": "d" * 40}}
        assert admit(ref_request, merge, stale_ref, {"EVENT_BASE_REF": base_ref}).returncode != 0
        assert admit(ref_request, merge, ref_branch,
                     {"EVENT_BASE_REF": base_ref, "MOCK_BASE_REF_MISSING": "true"}).returncode != 0
    for base_ref in ("", "feature/../bad", "feature/bad ref", "feature/ref.lock", "feature/@{bad", "feature/ref?query", "feature/line\nbreak"):
        rejected_ref = admit(request, merge, branch, {"EVENT_BASE_REF": base_ref})
        assert rejected_ref.returncode != 0, f"runtime metadata admitted invalid ref {base_ref!r}"
        assert not (fixture / "runtime-meta-ref-calls").read_text(), "invalid ref reached GitHub lookup"
    assert admit(request, merge, {**branch, "ref": "refs/heads/feature/other"}).returncode != 0
    for mutation in ("stale-base", "stale-head", "wrong-parents", "fork", "closed", "retarget", "missing-merge", "wrong-default-ref"):
        mutated_request, mutated_merge, mutated_branch = [json.loads(json.dumps(payload)) for payload in (labeled_request, merge, branch)]
        overrides = {}
        if mutation == "stale-base":
            mutated_branch["object"]["sha"] = "d" * 40
        elif mutation == "stale-head":
            mutated_request["head"]["sha"] = "d" * 40
        elif mutation == "wrong-parents":
            mutated_merge["parents"].reverse()
        elif mutation == "fork":
            mutated_request["head"]["repo"]["full_name"] = "outsider/FireMUD"
        elif mutation == "closed":
            mutated_request["state"] = "closed"
        elif mutation == "retarget":
            mutated_request["base"]["ref"] = "feature/other-parent"
        elif mutation == "missing-merge":
            overrides["EVENT_MERGE_SHA"] = ""
        elif mutation == "wrong-default-ref":
            overrides["GITHUB_REF"] = "refs/heads/feature/untrusted"
        rejected = admit(mutated_request, mutated_merge, mutated_branch, overrides)
        assert rejected.returncode != 0, f"runtime metadata admitted {mutation}"
        assert "canonical_play_transition=true\n" not in Path(env["GITHUB_OUTPUT"]).read_text()
finally:
    for path, original in zip(paths, originals):
        path.write_text(original)
PY

# A stacked grandchild has no published feature-base image, JARs, or cache.
# Execute both real source-build scripts; only their Gradle and Docker sinks
# are simulated. No actual Gradle process, Docker command, or registry is used.
python3 - "$ROOT_DIR" "$fixture_dir" "$merge_sha" "$base_sha" <<'PY'
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import yaml

root, fixture = map(Path, sys.argv[1:3])
merge_sha, feature_base_sha = sys.argv[3:5]
workflow = yaml.load((root / ".github/workflows/runtime-images.yml").read_text(), Loader=yaml.BaseLoader)
job = workflow["jobs"]["pr-local-smoke"]
assert job["permissions"] == {"contents": "read"}
assert job["env"]["SMOKE_IMAGE_LOCAL_ONLY"] == "true"
assert job["env"]["SMOKE_MINIO_LOCAL_ONLY"] == "true"
gradle_setup = [step for step in job["steps"] if step.get("uses", "").startswith("gradle/actions/setup-gradle@")]
assert len(gradle_setup) == 1 and gradle_setup[0]["with"]["cache-read-only"] == "true"
build_step = next(step for step in job["steps"] if step.get("name") == "Build local PR runtime images")
assert build_step["env"]["MERGE_SHA"] == "${{ needs.image-meta.outputs.merge_sha }}"
assert 'bash ./dev-tools/build-local-smoke-images.sh "$MERGE_SHA"' in build_step["run"]
assert build_step["id"] == "runtime-images-build"
evidence = next(step for step in job["steps"] if step.get("name") == "Retain runtime bootJar compile-failure evidence")
assert evidence["if"] == "${{ failure() && steps.runtime-images-build.outcome == 'failure' }}"
assert evidence["continue-on-error"] == "true"
assert evidence["uses"] == "actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9"
assert evidence["with"]["name"] == "runtime-bootjar-compile-failure-${{ github.run_id }}-attempt-${{ github.run_attempt }}"
assert "steps.runtime-images-build.outputs.runtime_compile_failure_path" in evidence["with"]["path"]
assert evidence["with"]["if-no-files-found"] == "warn"
assert evidence["with"]["retention-days"] == "3"
for dependency in ("collect-gradle-compile-failure.py", "compile-failure-facts.init.gradle"):
    path = "dev-tools/validation/" + dependency
    assert path not in workflow["on"]["pull_request"]["paths"]
for source_path in ("services/**", "dev-tools/build-local-smoke-images.sh", "dev-tools/build-compose-service-jars.sh"):
    assert source_path in workflow["on"]["pull_request"]["paths"]

source_tree = fixture / "stacked-source"
(source_tree / "dev-tools/validation").mkdir(parents=True)
(source_tree / "fake-bin").mkdir()
for name in ("build-local-smoke-images.sh", "build-compose-service-jars.sh"):
    shutil.copyfile(root / "dev-tools" / name, source_tree / "dev-tools" / name)
for name in ("collect-gradle-compile-failure.py", "compile-failure-facts.init.gradle"):
    shutil.copyfile(root / "dev-tools/validation" / name, source_tree / "dev-tools/validation" / name)
services = [
    "account-service", "automation-scripting-service", "entity-management-service",
    "game-design-service", "game-logic-service", "game-session-service",
    "logging-admin-service", "social-groups-service", "spring-cloud-gateway",
    "tcp-proxy-service", "world-management-service",
]
# Begin with neither generated artifacts nor any local image/dependency cache.
assert not (source_tree / "services").exists()
assert not (source_tree / "images.json").exists()
(source_tree / "empty-gradle-home").mkdir()
assert not list((source_tree / "empty-gradle-home").iterdir())
(source_tree / "services.json").write_text(json.dumps(services))
(source_tree / "command-sink.py").write_text(r'''
import json
import os
from pathlib import Path
import sys

root = Path(os.environ["STACKED_SOURCE_TREE"])
services = json.loads((root / "services.json").read_text())
kind, *args = sys.argv[1:]
merge = os.environ["STACKED_MERGE_SHA"]
base_image = f"ghcr.io/benhook1013/firemud-base:{merge}"
assert os.environ["STACKED_FEATURE_BASE_SHA"] not in " ".join(args), "feature-base publication must not be assumed"
assert not any("cache-from" in arg for arg in args), "source build must work without a published cache"
log = root / "commands.jsonl"
if kind == "gradle":
    assert not log.exists(), "current bootJar tasks must precede every image build"
    assert args == [
        "--no-configuration-cache", "--max-workers=1", "--no-parallel",
        "-Dorg.gradle.jvmargs=-Xmx2560m -Dfile.encoding=UTF-8", "-PincludeLoadTesting=false",
        *[f":{service}:bootJar" for service in services],
        "--info", "--console=plain",
    ], args
    for service in services:
        libs = root / "services" / service / "build/libs"
        libs.mkdir(parents=True)
        (libs / "current-boot.jar").write_text("fresh bootJar fixture")
elif kind == "docker":
    assert args[0] == "build", "no registry pull, login, push, or image discovery is allowed"
    events = [json.loads(line) for line in log.read_text().splitlines()]
    assert events[0]["kind"] == "gradle"
    assert all((root / "services" / service / "build/libs/current-boot.jar").is_file() for service in services)
    images_path = root / "images.json"
    images = json.loads(images_path.read_text()) if images_path.exists() else []
    if not images:
        assert args == ["build", "--file", str(root / "docker/base.Dockerfile"), "--tag", base_image, str(root)]
        image = base_image
    else:
        assert images[0] == base_image, "merge-local base must be built before service images"
        service = services[len(images) - 1]
        image = f"ghcr.io/benhook1013/{service}:{merge}"
        assert args == [
            "build", "--build-context", f"repo_root={root}", "--build-arg", f"BASE_IMAGE={base_image}",
            "--file", str(root / "services" / service / "Dockerfile"), "--tag", image,
            str(root / "services" / service / "build/libs"),
        ], args
    images.append(image)
    images_path.write_text(json.dumps(images))
else:
    raise AssertionError(f"unexpected command sink {kind}")
with log.open("a") as output:
    output.write(json.dumps({"kind": kind, "args": args}) + "\n")
''')
(source_tree / "dev-tools/validation/run-gradle-with-dependency-recovery.sh").write_text('''#!/usr/bin/env bash
set -euo pipefail
[[ $# -ge 4 && "$1" == --runner && "$2" == "$STACKED_SOURCE_TREE/dev-tools/validation/run-locked-gradle.sh" && "$3" == -- ]]
runner="$2"
shift 3
exec bash "$runner" "$@"
''')
(source_tree / "dev-tools/validation/run-locked-gradle.sh").write_text('''#!/usr/bin/env bash
set -euo pipefail
exec python3 "$STACKED_SOURCE_TREE/command-sink.py" gradle "$@"
''')
docker = source_tree / "fake-bin/docker"
docker.write_text('''#!/usr/bin/env bash
set -euo pipefail
exec python3 "$STACKED_SOURCE_TREE/command-sink.py" docker "$@"
''')
docker.chmod(0o755)
env = {
    **os.environ,
    "PATH": str(source_tree / "fake-bin") + os.pathsep + os.environ["PATH"],
    "STACKED_SOURCE_TREE": str(source_tree), "STACKED_MERGE_SHA": merge_sha,
    "STACKED_FEATURE_BASE_SHA": feature_base_sha,
    "GRADLE_USER_HOME": str(source_tree / "empty-gradle-home"),
}
completed = subprocess.run(["bash", str(source_tree / "dev-tools/build-local-smoke-images.sh"), merge_sha],
                           env=env, text=True, capture_output=True)
assert completed.returncode == 0, completed.stdout + completed.stderr
images = json.loads((source_tree / "images.json").read_text())
assert images == [f"ghcr.io/benhook1013/firemud-base:{merge_sha}",
                  *[f"ghcr.io/benhook1013/{service}:{merge_sha}" for service in services]]
commands = [json.loads(line) for line in (source_tree / "commands.jsonl").read_text().splitlines()]
assert [command["kind"] for command in commands] == ["gradle", *(["docker"] * 12)]
assert not list((source_tree / "empty-gradle-home").iterdir()), "simulation must not populate a real Gradle cache"
PY

run_validation() {
  local source_event="${1:-pull_request}"
  local source_branch="${2:-feature/ci}"
  local source_sha="${3:-$head_sha}"
  local publication_trigger="${4:-workflow_run}"
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
  PUBLICATION_TRIGGER="$publication_trigger" \
  SOURCE_RUN_ID="$source_run_id" \
  SOURCE_RUN_ATTEMPT="${SOURCE_RUN_ATTEMPT:-1}" \
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
if SOURCE_RUN_ATTEMPT=2 run_validation >/dev/null 2>"$fixture_dir/attempt-error"; then
  echo "publisher accepted an artifact after the resolved source attempt changed" >&2
  exit 1
fi
grep -Fq 'source workflow attempt changed before artifact validation' "$fixture_dir/attempt-error"
if run_validation pull_request feature/ci "$head_sha" repository_dispatch 2>"$fixture_dir/typed-source-error"; then
  echo "typed publication accepted a pull-request producer" >&2
  exit 1
fi
grep -Fq 'typed publication requires a repository_dispatch source run' "$fixture_dir/typed-source-error"
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
  '.github/workflows/runtime-images.yml@refs/heads/feature/.hidden' \
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
run_validation repository_dispatch develop "$default_branch_sha" repository_dispatch
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
if run_validation repository_dispatch develop "$default_branch_sha" repository_dispatch; then
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
  python3 - "$digest" <<'PY'
import sys

print(f"Digest: {sys.argv[1]}")
print("manifest detail " * 8192)
PY
  if [[ "${RUNTIME_TARGET_MODE:-}" == inspect-failure ]]; then
    exit 42
  fi
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
inspect_status=0
if run_runtime inspect-failure >"$fixture_dir/inspect-failure-output" 2>"$fixture_dir/inspect-failure-error"; then
  echo "runtime publisher accepted an imagetools inspection with a nonzero exit" >&2
  exit 1
else
  inspect_status=$?
fi
if [[ "$inspect_status" -ne 42 ]]; then
  echo "runtime publisher changed the imagetools inspection exit status (expected 42, got $inspect_status)" >&2
  exit 1
fi
grep -Fq 'imagetools exit 42' "$fixture_dir/inspect-failure-error"
if [[ -e "$fixture_dir/runtime-registry-marker" ]]; then
  echo "runtime publisher created an alias after imagetools inspection failed" >&2
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
