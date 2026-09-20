#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/static-analysis-summary.yml"

grep -Fq 'github.paginate(github.rest.actions.listWorkflowRuns' "$workflow"
grep -Fq 'workflowRunFor("codeql.yml")' "$workflow"
grep -Fq 'workflowRunFor("license-scan.yml")' "$workflow"
grep -Fq 'head_sha: headSha,' "$workflow"
grep -Fq 'return "skipped";' "$workflow"
grep -Fq 'run_attempt' "$workflow"
grep -Fq 'checkRunWorkflowId' "$workflow"
grep -Fq 'github.paginate(github.rest.checks.listForRef' "$workflow"
grep -Fq 'github.paginate(github.rest.issues.listComments' "$workflow"
grep -Fq 'currentPullRequest.head?.sha !== headSha' "$workflow"
grep -Fq 'context.payload.workflow_run.display_title !== expectedSourceTitle' "$workflow"
grep -Fq 'finalPullRequest.base?.sha !== baseSha' "$workflow"
grep -Fq 'workflowRun?.display_title === expectedTitle' "$workflow"
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
start = next(index for index, line in enumerate(lines) if line.strip() == "script: |")
base_indent = len(lines[start]) - len(lines[start].lstrip())
body = []
for line in lines[start + 1 :]:
    if line.strip() and len(line) - len(line.lstrip()) <= base_indent:
        break
    body.append(line)
script_path.write_text(textwrap.dedent("\n".join(body)) + "\n", encoding="utf-8")
PY

node - "$script_path" <<'NODE'
const fs = require("node:fs");
const script = fs.readFileSync(process.argv[2], "utf8");
const headSha = "a".repeat(40);
const baseSha = "b".repeat(40);
let currentHeadSha = headSha;
let currentBaseSha = baseSha;
let pullRequestReads = 0;
let changeBaseDuringRun = false;
const calls = { paginate: [], deleted: [], updated: [], created: [] };
const codeqlListWorkflowRuns = async () => undefined;
const checksListForRef = async () => undefined;
const commentsList = async () => undefined;
const github = {
  rest: {
    pulls: { get: async () => {
      pullRequestReads += 1;
      if (changeBaseDuringRun && pullRequestReads === 2) currentBaseSha = "c".repeat(40);
      return { data: { state: "open", head: { sha: currentHeadSha }, base: { sha: currentBaseSha } } };
    } },
    actions: { listWorkflowRuns: codeqlListWorkflowRuns },
    checks: { listForRef: checksListForRef },
    issues: {
      listComments: commentsList,
      deleteComment: async (input) => calls.deleted.push(input.comment_id),
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
          display_title: `License Checks pr-42 base-${baseSha} head-${headSha}`,
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
      return [
        {
          id: 1,
          user: { login: "contributor" },
          body: "### Static Analysis Summary\nspoof",
          updated_at: "2026-09-21T00:00:00Z",
        },
        {
          id: 2,
          user: { login: "github-actions[bot]" },
          body: "### Static Analysis Summary\nold",
          updated_at: "2026-09-19T00:00:00Z",
        },
        {
          id: 3,
          user: { login: "github-actions[bot]" },
          body: "### Static Analysis Summary\nnew",
          updated_at: "2026-09-20T00:00:00Z",
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
      name: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
      path: ".github/workflows/codeql.yml",
      head_sha: headSha,
      head_branch: "feature",
      display_title: `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`,
      pull_requests: [{ number: 42 }],
    },
  },
};
const core = { info: () => {} };
const run = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
run(github, context, core).then(() => {
  if (calls.paginate.length !== 4) throw new Error(`expected four paginated calls, got ${calls.paginate.length}`);
  if (!calls.paginate.every(({ input }) => input.per_page === 100)) throw new Error("all API listings must request page size 100");
  if (calls.updated.length !== 1 || calls.updated[0].comment_id !== 3) throw new Error("newest bot summary was not updated");
  if (calls.deleted.length !== 1 || calls.deleted[0] !== 2) throw new Error("only the stale bot summary should be deleted");
  if (calls.created.length !== 0) throw new Error("existing bot summary should be updated");
  if (!calls.updated[0].body.includes("❌ Static analysis checks failed")) throw new Error("new failure was masked by the older success");
  if (!calls.updated[0].body.includes("CodeQL gate: `failure`")) throw new Error("latest CodeQL gate result was not selected");
  currentHeadSha = "d".repeat(40);
  return run(github, context, core).then(() => {
    if (calls.paginate.length !== 4 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
      throw new Error("superseded source head should not query runs or alter comments");
    }
    currentHeadSha = headSha;
    currentBaseSha = baseSha;
    pullRequestReads = 0;
    context.payload.workflow_run.display_title = `CodeQL Analysis pr-42 base-${"c".repeat(40)} head-${headSha}`;
    return run(github, context, core).then(() => {
      if (calls.paginate.length !== 4 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
        throw new Error("source run for another base must not query runs or alter comments");
      }
      context.payload.workflow_run.display_title = `CodeQL Analysis pr-42 base-${baseSha} head-${headSha}`;
      context.payload.workflow_run.path = ".github/workflows/unrelated.yml";
      return run(github, context, core).then(() => {
        if (calls.paginate.length !== 4 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
          throw new Error("an unrelated source workflow must not query runs or alter comments");
        }
        context.payload.workflow_run.path = ".github/workflows/codeql.yml";
        pullRequestReads = 0;
        changeBaseDuringRun = true;
        return run(github, context, core).then(() => {
          if (calls.paginate.length !== 7 || calls.updated.length !== 1 || calls.deleted.length !== 1) {
            throw new Error("base changes during summarization must not query or alter comments");
          }
          console.log("static analysis summary behavioral contract passed");
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
