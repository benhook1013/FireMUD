#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/static-analysis-summary.yml"

if ! awk '
  $0 == "  publish-summary:" { in_job = 1; next }
  in_job && $0 ~ /^  [^ ]/ { exit 1 }
  in_job && $0 == "    timeout-minutes: 15" { found = 1 }
  END { exit(found ? 0 : 1) }
' "$workflow"; then
  echo "publish-summary must retain a 15-minute timeout" >&2
  exit 1
fi

grep -Fq 'github.paginate(github.rest.actions.listWorkflowRuns' "$workflow"
grep -Fq 'workflowRunFor("codeql.yml", "CodeQL Gate")' "$workflow"
grep -Fq 'workflowRunFor("license-scan.yml", "License Gate")' "$workflow"
grep -Fq 'head_sha: headSha,' "$workflow"
grep -Fq 'return "skipped";' "$workflow"
grep -Fq 'run_attempt' "$workflow"
grep -Fq 'job.run_attempt !== candidate.run_attempt' "$workflow"
if grep -Fq 'github.rest.checks.listForRef' "$workflow"; then
  echo "static analysis summary must not reuse a head-level check snapshot across attempts" >&2
  exit 1
fi
grep -Fq 'github.paginate(github.rest.issues.listComments' "$workflow"
grep -Fq 'per_page: 100' "$workflow"
grep -Fq 'cancel-in-progress: false' "$workflow"
# GitHub expressions are literal workflow text, not shell expansions.
# shellcheck disable=SC2016
grep -Fq 'group: static-analysis-summary-pr-${{ needs.resolve-summary-target.outputs.pull-number }}' "$workflow"
grep -Fq 'const headSha = currentPullRequest.head?.sha;' "$workflow"
grep -Fq 'context.payload.workflow_run.display_title !== expectedSourceTitle' "$workflow"
grep -Fq 'finalPullRequest.base?.sha !== baseSha' "$workflow"
grep -Fq 'run?.display_title === expectedTitle' "$workflow"
grep -Fq 'comment.user?.login === "github-actions[bot]"' "$workflow"
grep -Fq 'if (comment.id === existing?.id) continue;' "$workflow"

if grep -Fq 'listWorkflowRunsForRepo' "$workflow"; then
  echo "static analysis summary must query the CodeQL workflow directly" >&2
  exit 1
fi

test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
script_path="$test_root/static-analysis-summary.js"
python3 - "$workflow" "$script_path" <<'PY'
import sys
import textwrap
from pathlib import Path

workflow_path, script_path = map(Path, sys.argv[1:])
lines = workflow_path.read_text(encoding="utf-8").splitlines()
step_name = "- name: 💬 Publish Static Analysis Summary Comment"
step_start = next(
    (index for index, line in enumerate(lines) if line.strip() == step_name),
    None,
)
if step_start is None:
    raise SystemExit(f"missing workflow step: {step_name}")
step_indent = len(lines[step_start]) - len(lines[step_start].lstrip())
start = None
for index in range(step_start + 1, len(lines)):
    line = lines[index]
    if line.strip() and len(line) - len(line.lstrip()) <= step_indent:
        break
    if line.strip() == "script: |":
        start = index
        break
if start is None:
    raise SystemExit(f"missing script block after workflow step: {step_name}")
base_indent = len(lines[start]) - len(lines[start].lstrip())
body = []
for line in lines[start + 1 :]:
    if line.strip() and len(line) - len(line.lstrip()) <= base_indent:
        break
    body.append(line)
script_path.write_text(textwrap.dedent("\n".join(body)) + "\n", encoding="utf-8")
PY

# Execute the actual pre-queue resolver and verify the job-level lock boundary.
python3 - "$workflow" "$test_root/summary-target.js" <<'PY_TARGET'
import sys
from pathlib import Path
import yaml
workflow = yaml.load(Path(sys.argv[1]).read_text(), Loader=yaml.BaseLoader)
assert "concurrency" not in workflow, "unvalidated callbacks must not share a workflow queue"
resolver = workflow["jobs"]["resolve-summary-target"]
publisher = workflow["jobs"]["publish-summary"]
assert resolver["permissions"] == {}, "resolver must not receive write authority"
assert resolver["if"] == "${{ github.event.workflow_run.event == 'pull_request' }}"
assert publisher["needs"] == ["resolve-summary-target"]
assert publisher["if"] == "${{ needs.resolve-summary-target.outputs.pull-number != '' }}"
assert publisher["concurrency"] == {
    "group": "static-analysis-summary-pr-${{ needs.resolve-summary-target.outputs.pull-number }}",
    "cancel-in-progress": "false",
    "queue": "max",
}
step = next(step for step in resolver["steps"] if step.get("id") == "target")
assert resolver["outputs"]["pull-number"] == "${{ steps.target.outputs.pull-number }}"
Path(sys.argv[2]).write_text(step["with"]["script"])
PY_TARGET
node - "$test_root/summary-target.js" <<'NODE_TARGET'
const assert = require("node:assert/strict");
const fs = require("node:fs");
const resolve = new Function("context", "core", fs.readFileSync(process.argv[2], "utf8"));
const head = "a".repeat(40), base = "b".repeat(40);
function source(number = 42, workflow = "CodeQL Analysis", sha = head) {
  return {event: "pull_request", name: workflow,
    path: workflow === "CodeQL Analysis" ? ".github/workflows/codeql.yml" : ".github/workflows/license-scan.yml",
    display_title: `${workflow} pr-${number} base-${base} head-${sha}`, head_sha: sha,
    head_repository: {id: 123}, head_branch: "shared", pull_requests: [{number}]};
}
function group(run) {
  const outputs = {};
  resolve({payload: {workflow_run: run}}, {setOutput: (key, value) => { outputs[key] = value; }});
  return outputs["pull-number"] ? `static-analysis-summary-pr-${outputs["pull-number"]}` : null;
}
const a = group(source()), b = group(source(99));
assert.notEqual(a, b, "same source branch must not couple different PR queues");
assert.equal(a, group(source(42, "License Gate")), "producer channels must serialize per PR");
assert.equal(a, group(source(42, "CodeQL Analysis", "c".repeat(40))), "head advance must keep the PR queue");
for (const pull_requests of [[{number: 99}, {number: 42}], [{number: 42}, {number: 99}]]) {
  assert.equal(group({...source(), pull_requests}), a, "association order must not select another PR");
}
for (const override of [
  {event: "push"}, {event: "schedule"}, {display_title: "malformed"},
  {head_sha: "d".repeat(40)}, {path: ".github/workflows/unrelated.yml"}, {name: "forged"},
  {head_repository: {id: 0}}, {head_repository: null}, {head_branch: ""},
  {pull_requests: null}, {pull_requests: [{number: 99}]},
  {pull_requests: [{number: 42}, {number: 42}]},
]) assert.equal(group({...source(), ...override}), null, JSON.stringify(override));
// Fork identity is not authority: keep a valid association routable; the existing
// publisher independently verifies the live PR repository/branch before any write.
assert.equal(group({...source(), head_repository: {id: 456}}), a);
const pending = new Map();
pending.set(a, "42-final"); pending.set(b, "99-old");
const push = group({...source(), event: "push"});
if (push) pending.set(push, "push");
assert.equal(pending.get(a), "42-final", "other PR and push cannot replace PR42 pending work");
assert.equal(pending.get(b), "99-old");
console.log("static summary target and queue isolation contract passed");
NODE_TARGET

node - "$script_path" <<'NODE'
const fs = require("node:fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const headSha = "a".repeat(40);
const baseSha = "b".repeat(40);
let currentHeadSha = headSha;
let currentBaseSha = baseSha;
let currentBaseRef = "develop";
let pullRequestReads = 0;
let changeBaseDuringRun = false;
let changeHeadDuringRun = false;
let changeHeadDuringComments = false;
let deleteStatus = null;
let existingBody = "### Static Analysis Summary\nold";
const calls = { paginate: [], deleted: [], updated: [], created: [] };
const codeqlListWorkflowRuns = async () => undefined;
const checksListForRef = async () => undefined;
const commentsList = async () => undefined;
const jobRequests = [];
const github = {
  request: async (route, input) => {
    if (route !== "GET /repos/{owner}/{repo}/actions/runs/{run_id}/attempts/{attempt_number}/jobs") throw new Error("unexpected native request");
    jobRequests.push(input);
    if (input.per_page !== 100) throw new Error("candidate must use bounded jobs");
    const fromAttempt = (row) => ({
      ...row, run_id: input.run_id, run_attempt: input.attempt_number, head_sha: currentHeadSha,
    });
    if (input.run_id === 270) {
      return {data: {total_count: 1, jobs: [fromAttempt({id: 2701,
        name: "github.event.action == 'edited' && 'PR Metadata Edit (CodeQL Gate)' || 'CodeQL Gate'",
        status: "completed", conclusion: "skipped"})]}};
    }
    // Reuse the existing status fixtures as native jobs. Each callback reads them
    // from this same selected-attempt response, not via a real head-check query.
    const callCount = calls.paginate.length;
    const fixtures = await github.paginate(checksListForRef, {ref: currentHeadSha, per_page: 100});
    calls.paginate.splice(callCount);
    let jobs = fixtures.filter(row => row.details_url.includes(`/runs/${input.run_id}/`)).map(fromAttempt);
    if (input.attempt_number === 2) jobs = jobs.map(job => ({...job, id: job.id + 10000,
      status: "in_progress", conclusion: null}));
    return {data: {total_count: jobs.length, jobs}};
  },
  rest: {
    pulls: { get: async () => {
      pullRequestReads += 1;
      if (changeHeadDuringRun && pullRequestReads === 2) currentHeadSha = "e".repeat(40);
      if (changeBaseDuringRun && pullRequestReads === 2) currentBaseSha = "c".repeat(40);
      return { data: { state: "open", head: { sha: currentHeadSha, repo: { id: 123 }, ref: "feature" }, base: { sha: currentBaseSha, ref: currentBaseRef } } };
    } },
    actions: { listWorkflowRuns: codeqlListWorkflowRuns },
    checks: { listForRef: checksListForRef },
    issues: {
      listComments: commentsList,
      deleteComment: async (input) => {
        calls.deleted.push(input.comment_id);
        if (deleteStatus !== null) throw { status: deleteStatus };
      },
      updateComment: async (input) => calls.updated.push(input),
      createComment: async (input) => calls.created.push(input),
    },
  },
  paginate: async (method, input) => {
    calls.paginate.push({ method, input });
    if (method === codeqlListWorkflowRuns) {
      if (input.workflow_id === "codeql.yml") {
        return [
          {
            id: 100,
            event: "pull_request",
            head_sha: headSha,
            display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
            created_at: "2026-09-19T00:00:00Z",
            run_attempt: 1,
            status: "completed",
            conclusion: "success",
          },
          {
            id: 200,
            event: "pull_request",
            head_sha: headSha,
            display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
            created_at: "2026-09-20T00:00:00Z",
            run_attempt: 1,
            status: "completed",
            conclusion: "failure",
          },
          {
            // A later metadata-only edit run: its gate is skipped under a
            // non-required name and must not mask the substantive failure.
            id: 270,
            event: "pull_request",
            head_sha: headSha,
            display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
            created_at: "2026-09-20T12:00:00Z",
            run_attempt: 1,
            status: "completed",
            conclusion: "success",
          },
          {
            id: 250,
            event: "pull_request",
            head_sha: headSha,
            display_title: `CodeQL Analysis pr-99 base-${baseSha} head-${headSha}`,
            created_at: "2026-09-21T00:00:00Z",
            run_attempt: 1,
            status: "completed",
            conclusion: "success",
          },
          {
            id: 260,
            event: "pull_request",
            head_sha: headSha,
            display_title: `CodeQL Analysis pr-42 base-${"c".repeat(40)} head-${headSha}`,
            created_at: "2026-09-21T01:00:00Z",
            run_attempt: 1,
            status: "completed",
            conclusion: "success",
          },
        ];
      }
      return [
        {
          id: 300,
          event: "pull_request",
          head_sha: headSha,
          display_title: `License Gate pr-42 base-${baseSha} head-${headSha}`,
          created_at: "2026-09-20T00:00:00Z",
          run_attempt: 1,
          status: "completed",
          conclusion: "success",
        },
      ];
    }
    if (method === checksListForRef) {
      return [
        {
          id: 1001,
          name: "CodeQL Gate",
          status: "completed",
          conclusion: "success",
          created_at: "2026-09-19T00:01:00Z",
          completed_at: "2026-09-19T00:02:00Z",
          details_url: "https://github.example/actions/runs/100/job/1001",
        },
        {
          id: 2701,
          // Initial checks precede the new metadata run; candidate jobs classify it later.
          name: "Unrelated earlier snapshot",
          status: "completed",
          conclusion: "skipped",
          created_at: "2026-09-20T12:00:01Z",
          completed_at: "2026-09-20T12:00:01Z",
          details_url: "https://github.example/actions/runs/270/job/2701",
        },
        {
          id: 2001,
          name: "CodeQL Gate",
          status: "completed",
          conclusion: "failure",
          created_at: "2026-09-20T00:01:00Z",
          completed_at: "2026-09-20T00:02:00Z",
          details_url: "https://github.example/actions/runs/200/job/2001",
        },
        {
          id: 1002,
          name: "CodeQL Analysis (java)",
          status: "completed",
          conclusion: "success",
          created_at: "2026-09-19T00:01:00Z",
          completed_at: "2026-09-19T00:02:00Z",
          details_url: "https://github.example/actions/runs/100/job/1002",
        },
        {
          id: 2002,
          name: "CodeQL Analysis (java)",
          status: "completed",
          conclusion: "failure",
          created_at: "2026-09-20T00:01:00Z",
          completed_at: "2026-09-20T00:02:00Z",
          details_url: "https://github.example/actions/runs/200/job/2002",
        },
        {
          id: 3001,
          name: "License Gate",
          status: "completed",
          conclusion: "success",
          created_at: "2026-09-20T00:01:00Z",
          completed_at: "2026-09-20T00:02:00Z",
          details_url: "https://github.example/actions/runs/300/job/3001",
        },
      ];
    }
    if (method === commentsList) {
      if (changeHeadDuringComments) currentHeadSha = "9".repeat(40);
      return [
        ...Array.from({ length: 35 }, (_, index) => ({
          id: 10000 + index,
          user: { login: "github-actions[bot]" },
          body: `### Unrelated Summary ${index + 1}`,
          created_at: "2026-09-01T00:00:00Z",
        })),
        {
          id: 1,
          user: { login: "contributor" },
          body: "### Static Analysis Summary\nspoof",
          created_at: "2026-09-18T00:00:00Z",
          updated_at: "2026-09-21T00:00:00Z",
        },
        {
          id: 2,
          user: { login: "github-actions[bot]" },
          body: existingBody,
          created_at: "2026-09-19T00:00:00Z",
          updated_at: "2026-09-21T00:00:00Z",
        },
        {
          id: 3,
          user: { login: "github-actions[bot]" },
          body: "### Static Analysis Summary\nnew",
          created_at: "2026-09-20T00:00:00Z",
          updated_at: "2026-09-19T00:00:00Z",
        },
        {
          id: 4,
          user: { login: "github-actions[bot]" },
          body: "### Unrelated Summary\nnot a static-analysis summary",
          created_at: "2026-09-18T00:00:00Z",
          updated_at: "2026-09-22T00:00:00Z",
        },
      ];
    }
    throw new Error(`unexpected paginate method: ${String(method)}`);
  },
};
const context = {
  repo: { owner: "owner", repo: "repo" },
  payload: {
    workflow_run: {
      event: "pull_request",
      name: "CodeQL Analysis",
      path: ".github/workflows/codeql.yml",
      head_sha: headSha,
      head_branch: "feature",
      head_repository: { id: 123 },
      display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
      pull_requests: [{ number: 42 }],
    },
  },
};
const core = { info: () => {} };
const run = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
// Model the two serialized workflow_run callbacks against a persistent comment store.
async function assertCompletionOrders() {
  const advancedHeadSha = "f".repeat(40);
  const originalPaginate = github.paginate;
  const originalIssues = github.rest.issues;
  const originalTimeout = global.setTimeout;
  global.setTimeout = () => { throw new Error("completion snapshots must not sleep or poll"); };
  try {
    for (const first of ["codeql", "license"]) {
      for (const absentCounterpart of [false, true]) {
        currentBaseSha = baseSha;
        currentBaseRef = "develop";
        const completed = new Set([first]);
        let currentTuplePublished = true;
        let comments = [];
        let creates = 0;
        let updates = 0;
        github.rest.issues = {
          ...originalIssues,
          createComment: async ({ body }) => {
            creates += 1;
            comments.push({ id: 42, user: { login: "github-actions[bot]" }, body });
          },
          updateComment: async ({ comment_id, body }) => {
            updates += 1;
            const comment = comments.find(({ id }) => id === comment_id);
            if (!comment) throw new Error("completion must update the same bot comment");
            comment.body = body;
          },
          deleteComment: async () => { throw new Error("single summary must not be deleted"); },
        };
        github.paginate = async (method, input) => {
          const rows = await originalPaginate(method, input);
          if (method === commentsList) return comments;
          const requestedHead = method === codeqlListWorkflowRuns ? input.head_sha : input.ref;
          const liveHead = requestedHead === advancedHeadSha;
          const proofHead = liveHead ? advancedHeadSha : headSha;
          const runOffset = (currentBaseSha === baseSha ? 0 : 1000) + (liveHead ? 2000 : 0);
          if (method === codeqlListWorkflowRuns) {
            if (!currentTuplePublished) return rows;
            const key = input.workflow_id === "codeql.yml" ? "codeql" : "license";
            if (!completed.has(key) && absentCounterpart) return [];
            return rows.filter((row) => [100, 200, 300].includes(row.id)).map((row) => ({ ...row,
              id: row.id + runOffset,
              head_sha: proofHead,
              display_title: row.display_title.replace(`base-${baseSha}`, `base-${currentBaseSha}`).replace(`head-${headSha}`, `head-${proofHead}`),
              status: completed.has(key) ? "completed" : "in_progress",
              conclusion: completed.has(key) ? "success" : null,
            }));
          }
          return rows.filter((row) => !absentCounterpart || completed.has(row.id === 3001 ? "license" : "codeql"))
            .map((row) => ({ ...row,
              id: row.id + runOffset,
              details_url: row.details_url.replace(/runs\/(\d+)/, (_, id) => `runs/${Number(id) + runOffset}`),
              status: completed.has(row.id === 3001 ? "license" : "codeql") ? "completed" : "in_progress",
              conclusion: completed.has(row.id === 3001 ? "license" : "codeql")
                ? (!liveHead && currentBaseSha !== baseSha && row.id === 2001 ? "failure" : "success") : null,
            }));
        };
        const callback = async (key) => {
          const name = key === "codeql" ? "CodeQL Analysis" : "License Gate";
          Object.assign(context.payload.workflow_run, {
            name, path: `.github/workflows/${key === "codeql" ? "codeql" : "license-scan"}.yml`,
            display_title: `${name} pr-42 base-${baseSha} head-${headSha}`,
          });
          calls.paginate.length = 0;
          await run(github, context, core);
          if (calls.paginate.length !== 3) throw new Error("each callback must take exactly one snapshot of both producers and comments");
        };
        await callback(first);
        if (creates !== 1 || updates !== 0 || comments.length !== 1 || !comments[0].body.includes("still running")) {
          throw new Error(`${first}-first callback must create one honest pending summary`);
        }
        const counterpart = first === "codeql" ? "license" : "codeql";
        const pendingLabel = counterpart === "codeql" ? "CodeQL gate" : "License gate";
        const pendingStatus = absentCounterpart ? "pending" : "in_progress";
        if (!comments[0].body.includes(`${pendingLabel}: \`${pendingStatus}\``)) throw new Error("counterpart pending result missing");
        completed.add(counterpart);
        await callback(counterpart);
        if (creates !== 1 || updates !== 1 || comments.length !== 1 || !comments[0].body.includes("checks passed")) {
          throw new Error(`${first}-first completion sequence did not converge in the same comment`);
        }
        // A delayed earlier callback must not regress the terminal snapshot.
        await callback(first);
        if (creates !== 1 || updates !== 1 || comments.length !== 1) throw new Error("delayed source callback must retain the final comment");
        // A late older-base event may replace the pending current-base callback.
        // Both base advancement and retargeting must wake a new current-tuple snapshot.
        for (const [nextBase, nextRef] of [["c".repeat(40), "develop"], ["d".repeat(40), "codex/stack-parent"]]) {
          currentBaseSha = nextBase;
          currentBaseRef = nextRef;
          // callback() deliberately retains the original base in its source title.
          await callback(first);
          if (creates !== 1 || comments.length !== 1 || !comments[0].body.includes("CodeQL gate: `failure`")) {
            throw new Error("older-base wakeup must summarize current-base failure after advancement or retargeting");
          }
          const writes = updates;
          await callback(counterpart);
          if (updates !== writes) throw new Error("older-base callback must not regress the current-base snapshot");
        }
        // Both producer orders must recover current-head proof even if the
        // final wake is an obsolete head and all current-head wakes coalesced.
        currentHeadSha = advancedHeadSha;
        const writesBeforeHeadAdvance = updates;
        await callback(first);
        if (updates !== writesBeforeHeadAdvance + 1 ||
            !comments[0].body.includes(currentHeadSha)) {
          throw new Error("older-head wake must publish new head identity in the existing comment");
        }
        if (!comments[0].body.includes("CodeQL gate: `success`")) {
          throw new Error("stale-head wake must replace the old failure with live-head success");
        }
        const writesAfterHeadAdvance = updates;
        await callback(counterpart);
        if (updates !== writesAfterHeadAdvance || creates !== 1 || comments.length !== 1) {
          throw new Error("counterpart callback must leave the live-head summary unchanged");
        }
        currentHeadSha = headSha;
        currentBaseSha = "e".repeat(40);
        currentTuplePublished = false;
        await callback(first);
        if (!comments[0].body.includes("CodeQL gate: `pending`") || !comments[0].body.includes("License gate: `pending`")) {
          throw new Error("completed older-base source and checks cannot substitute for missing current-tuple proof");
        }
      }
    }
  } finally {
    currentBaseSha = baseSha;
    currentBaseRef = "develop";
    github.paginate = originalPaginate;
    github.rest.issues = originalIssues;
    global.setTimeout = originalTimeout;
  }
}
async function assertAssociationSelection() {
  const source = context.payload.workflow_run;
  Object.assign(source, { name: "CodeQL Analysis", path: ".github/workflows/codeql.yml",
    display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}` });
  const reset = () => {
    pullRequestReads = 0;
    for (const values of Object.values(calls)) values.length = 0;
  };
  for (const associations of [[{number: 99}, {number: 42}], [{number: 42}, {number: 99}]]) {
    reset(); source.pull_requests = associations;
    await run(github, context, core);
    if (calls.updated.length !== 1 || calls.paginate.some(({input}) => input.issue_number && input.issue_number !== 42)) {
      throw new Error("unrelated associations must not hide or redirect canonical PR publication");
    }
  }
  for (const associations of [[{number: 42}, {number: 42}], [{number: 99}], null]) {
    reset(); source.pull_requests = associations;
    await run(github, context, core);
    if (pullRequestReads || calls.paginate.length || calls.updated.length || calls.deleted.length) {
      throw new Error("missing or duplicate matching association must fail before provider reads");
    }
  }
  source.pull_requests = [{number: 42}];
  for (const [repository, branch] of [[null, "feature"], [{id: 0}, "feature"], [{id: 123}, ""], [{id: 456}, "feature"], [{id: 123}, "different"]]) {
    reset(); source.head_repository = repository; source.head_branch = branch;
    await run(github, context, core);
    if (calls.paginate.length || calls.updated.length || calls.deleted.length) {
      throw new Error("invalid or mismatched concurrency identity must not publish");
    }
  }
  source.head_repository = {id: 123}; source.head_branch = "feature";
}
async function assertInterruptedRecovery() {
  const originalPaginate = github.paginate;
  const reset = () => {
    currentHeadSha = headSha; currentBaseSha = baseSha; pullRequestReads = 0;
    Object.assign(context.payload.workflow_run, {
      name: "CodeQL Analysis", path: ".github/workflows/codeql.yml", head_sha: headSha,
      display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
    });
    for (const values of Object.values(calls)) values.length = 0;
  };
  reset();
  changeHeadDuringRun = true;
  await run(github, context, core);
  if (calls.updated.length || calls.created.length || calls.deleted.length) {
    throw new Error("head movement during reconciliation must not write stale proof");
  }
  changeHeadDuringRun = false;
  reset();
  changeHeadDuringComments = true;
  await run(github, context, core);
  changeHeadDuringComments = false;
  if (calls.updated.length || calls.created.length || calls.deleted.length) {
    throw new Error("head movement while listing comments must not write stale proof");
  }
  reset();
  github.paginate = async () => { throw new Error("provider interrupted"); };
  let failed = false;
  try { await run(github, context, core); } catch (error) {
    if (error.message !== "provider interrupted") throw error;
    failed = true;
  } finally { github.paginate = originalPaginate; }
  if (!failed || calls.updated.length || calls.created.length || calls.deleted.length) {
    throw new Error("failed snapshot must propagate without publication");
  }
  // A retained/pending callback or explicit rerun can recover; no false success
  // or automatic retry is invented when the provider is unavailable.
  await run(github, context, core);
  if (calls.updated.length !== 1 || !calls.updated[0].body.includes("checks failed")) {
    throw new Error("later callback must recover after interrupted reconciliation");
  }
}
run(github, context, core).then(async () => {
  if (calls.paginate.length !== 3) throw new Error(`expected three paginated calls, got ${calls.paginate.length}`);
  if (!calls.paginate.every(({ input }) => input.per_page === 100)) throw new Error("all API listings must request page size 100");
  if (calls.updated.length !== 1 || calls.updated[0].comment_id !== 2) throw new Error("oldest bot summary was not updated");
  if (calls.deleted.length !== 1 || calls.deleted[0] !== 3) throw new Error("only the later duplicate should be deleted");
  if (calls.created.length !== 0) throw new Error("existing bot summary should be updated");
  if (!calls.updated[0].body.includes("❌ Static analysis checks failed")) throw new Error("new failure was masked by the older success");
  if (!calls.updated[0].body.includes("CodeQL gate: `failure`")) throw new Error("latest CodeQL gate result was not selected");
  const canonicalBody = calls.updated[0].body;
  context.payload.workflow_run.name = "Forged workflow name";
  await run(github, context, core);
  if (calls.paginate.length !== 3 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
    throw new Error("unrelated source names must not query runs or alter comments");
  }
  context.payload.workflow_run.name = "CodeQL Analysis";
  context.payload.workflow_run.head_sha = "invalid";
  return run(github, context, core).then(() => {
    if (calls.paginate.length !== 3 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
      throw new Error("malformed source head should not query runs or alter comments");
    }
    context.payload.workflow_run.head_sha = headSha;
    currentHeadSha = headSha;
    currentBaseSha = baseSha;
    pullRequestReads = 0;
    context.payload.workflow_run.display_title = `CodeQL Analysis pr-42 base-invalid head-${headSha}`;
    return run(github, context, core).then(() => {
      if (calls.paginate.length !== 3 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
        throw new Error("source run with a malformed canonical title must not query runs or alter comments");
      }
      context.payload.workflow_run.display_title = `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`;
      context.payload.workflow_run.path = ".github/workflows/unrelated.yml";
      return run(github, context, core).then(() => {
        if (calls.paginate.length !== 3 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
          throw new Error("an unrelated source workflow must not query runs or alter comments");
        }
        context.payload.workflow_run.path = ".github/workflows/codeql.yml";
        pullRequestReads = 0;
        changeBaseDuringRun = true;
        return run(github, context, core).then(() => {
          if (calls.paginate.length !== 6 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
            throw new Error("base changes during summarization must not alter comments");
          }
          currentHeadSha = headSha;
          currentBaseSha = baseSha;
          pullRequestReads = 0;
          changeBaseDuringRun = false;
          context.payload.workflow_run.display_title = `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`;
          calls.paginate.length = 0;
          calls.deleted.length = 0;
          calls.updated.length = 0;
          calls.created.length = 0;
          context.payload.workflow_run.name = `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`;
          deleteStatus = 404;
          return run(github, context, core).then(async () => {
            if (calls.deleted.length !== 1 || calls.deleted[0] !== 3) {
              throw new Error("HTTP 404 duplicate deletion must be ignored after the delete attempt");
            }
            calls.paginate.length = 0;
            calls.deleted.length = 0;
            calls.updated.length = 0;
            calls.created.length = 0;
            existingBody = canonicalBody;
            deleteStatus = null;
            await run(github, context, core);
            if (calls.updated.length || calls.created.length) throw new Error("identical static summaries must not write comments");
            if (calls.deleted.length !== 1 || calls.deleted[0] !== 3) throw new Error("unchanged static summaries still remove duplicates");
            existingBody = "### Static Analysis Summary\nold";
            calls.paginate.length = 0;
            calls.deleted.length = 0;
            deleteStatus = 500;
            return run(github, context, core).then(
              () => { throw new Error("non-404 duplicate deletion errors must propagate"); },
              async (error) => {
                if (error?.status !== 500) throw error;
                deleteStatus = null;
                await assertCompletionOrders();
                await assertInterruptedRecovery();
                await assertAssociationSelection();
                if (!jobRequests.some(input => input.run_id === 270)) throw new Error("metadata race was not classified from candidate jobs");
                const savedRequest = github.request;
                const savedPaginate = github.paginate;
                github.paginate = async (method, input) => {
                  const rows = await savedPaginate(method, input);
                  return method === codeqlListWorkflowRuns && input.workflow_id === "codeql.yml"
                    ? rows.map(row => row.id === 200 ? {...row, run_attempt: 2, status: "in_progress", conclusion: null} : row)
                    : rows;
                };
                const rerunStart = calls.updated.length;
                await run(github, context, core);
                const rerunWrites = calls.updated.slice(rerunStart);
                github.paginate = savedPaginate;
                if (!jobRequests.some(input => input.run_id === 200 && input.attempt_number === 2) ||
                    !rerunWrites.some(row => row.body.includes("CodeQL gate: \`in_progress\`")) ||
                    rerunWrites.some(row => row.body.includes("checks passed"))) {
                  throw new Error("a prior attempt's status cannot replace the selected running rerun");
                }
                github.request = async (_, input) => ({ data: { total_count: 1, jobs: [{
                  id: 1, run_id: input.run_id, run_attempt: input.attempt_number, head_sha: currentHeadSha,
                  name: "Unrelated job", status: "completed", conclusion: "success",
                }] } });
                const unknownStart = calls.updated.length;
                await run(github, context, core);
                const unknownWrites = calls.updated.slice(unknownStart);
                if (!unknownWrites.length || unknownWrites.some(row => row.body.includes("checks passed"))) throw new Error("unclassified successful jobs must not yield a green summary");
                if (!unknownWrites.some(row => row.body.includes("in_progress"))) throw new Error("unclassified candidate must remain pending");
                const savedActions = github.rest.actions;
                let currentAttempt = 1;
                github.rest.actions = {...savedActions, getWorkflowRun: async ({run_id}) => ({data: {
                  id: run_id, run_attempt: currentAttempt, event: "pull_request", head_sha: currentHeadSha,
                  display_title: `CodeQL Analysis pr-42 base-${currentBaseSha} head-${currentHeadSha}`,
                }})};
                for (const duplicate of [false, true]) {
                  github.request = async (route, input) => {
                    const response = await savedRequest(route, input);
                    if (input.run_id !== 200) return response;
                    const jobs = response.data.jobs;
                    const gate = jobs.find(job => job.name === "CodeQL Gate");
                    if (duplicate) jobs.push({...gate, id: gate.id + 50000});
                    else gate.run_attempt += 1;
                    return {data: {jobs, total_count: jobs.length}};
                  };
                  const unavailableStart = calls.updated.length;
                  await run(github, context, core);
                  if (!calls.updated.slice(unavailableStart).some(row =>
                      row.body.includes("Static analysis evidence is unavailable") &&
                      row.body.includes("CodeQL gate: \`unavailable\`"))) {
                    throw new Error("untrustworthy exact-attempt inventory must not leave a green summary");
                  }
                }
                currentAttempt = 2;
                const staleAttemptWrites = calls.updated.length + calls.created.length;
                let staleAttemptRefused = false;
                try { await run(github, context, core); } catch (error) {
                  staleAttemptRefused = error.message.includes("cannot confirm the current workflow attempt");
                } finally { github.rest.actions = savedActions; }
                if (!staleAttemptRefused || calls.updated.length + calls.created.length !== staleAttemptWrites) {
                  throw new Error("an older ambiguous snapshot must not overwrite a newer summary");
                }
                const writesBefore = calls.updated.length + calls.created.length;
                github.request = async () => ({ data: { total_count: 101, jobs: [] } });
                let refused = false;
                try { await run(github, context, core); } catch (error) {
                  refused = error.message.includes("jobs are incomplete");
                } finally { github.request = savedRequest; }
                if (!refused || calls.updated.length + calls.created.length !== writesBefore) throw new Error("incomplete candidate jobs must not publish success");
                console.log("static analysis summary behavioral contract passed");
              },
            );
          });
        });
      });
    });
  });
}).catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

echo "static analysis summary contract passed"
