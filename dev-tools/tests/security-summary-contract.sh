#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/security.yml"

grep -Fq 'github.paginate(github.rest.issues.listComments' "$workflow"
grep -Fq 'per_page: 100' "$workflow"
grep -Fq 'comment.user?.login === "github-actions[bot]"' "$workflow"
grep -Fq 'comment.created_at' "$workflow"
grep -Fq 'if (comment.id === existing?.id) continue;' "$workflow"
grep -Fq 'github.rest.pulls.get' "$workflow"
grep -Fq 'currentPullRequest.state !== "open"' "$workflow"
grep -Fq 'currentPullRequest.head.sha !== expectedHeadSha' "$workflow"
grep -Fq 'currentPullRequest.base.ref !== expectedBaseRef' "$workflow"
grep -Fq 'currentPullRequest.base.sha !== expectedBaseSha' "$workflow"

test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT
script_path="$test_root/security-summary.js"
python3 - "$workflow" "$script_path" <<'PY'
import sys
import textwrap
from pathlib import Path

workflow_path, script_path = map(Path, sys.argv[1:])
lines = workflow_path.read_text(encoding="utf-8").splitlines()
anchor = next(
    index
    for index, line in enumerate(lines)
    if line.strip() == "- name: 💬 Publish Security Summary Comment"
)
start = next(index for index in range(anchor, len(lines)) if lines[index].strip() == "script: |")
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
const calls = { paginate: [], deleted: [], updated: [], created: [] };
let currentPullRequest;
const commentsList = async () => undefined;
const github = {
  rest: {
    pulls: {
      get: async () => ({ data: currentPullRequest }),
    },
    issues: {
      listComments: commentsList,
      deleteComment: async (input) => calls.deleted.push(input.comment_id),
      updateComment: async (input) => calls.updated.push(input),
      createComment: async (input) => calls.created.push(input),
    },
  },
  paginate: async (method, input) => {
    calls.paginate.push({ method, input });
    if (method !== commentsList) throw new Error(`unexpected paginate method: ${String(method)}`);
    return [
      ...Array.from({ length: 35 }, (_, index) => ({
        id: 1000 + index,
        user: { login: "github-actions[bot]" },
        body: `### Unrelated Summary ${index + 1}`,
        created_at: "2026-09-01T00:00:00Z",
      })),
      {
        id: 11,
        user: { login: "github-actions[bot]" },
        body: "### Security Summary\nnewest",
        created_at: "2026-09-20T00:00:00Z",
        updated_at: "2026-09-18T00:00:00Z",
      },
      {
        id: 10,
        user: { login: "github-actions[bot]" },
        body: "### Security Summary\nstale",
        created_at: "2026-09-19T00:00:00Z",
        updated_at: "2026-09-21T00:00:00Z",
      },
      {
        id: 12,
        user: { login: "contributor" },
        body: "### Security Summary\nspoof",
        created_at: "2026-09-18T00:00:00Z",
        updated_at: "2026-09-21T00:00:00Z",
      },
    ];
  },
};
const context = {
  repo: { owner: "owner", repo: "repo" },
  issue: { number: 42 },
  payload: {
    pull_request: {
      head: { sha: "a".repeat(40) },
      base: { ref: "main", sha: "b".repeat(40) },
    },
  },
};
const core = { info: () => {} };
currentPullRequest = {
  state: "open",
  head: { sha: context.payload.pull_request.head.sha },
  base: {
    ref: context.payload.pull_request.base.ref,
    sha: context.payload.pull_request.base.sha,
  },
};
const run = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
const resetCalls = () => {
  calls.paginate.length = 0;
  calls.deleted.length = 0;
  calls.updated.length = 0;
  calls.created.length = 0;
};
run(github, context, core).then(async () => {
  if (calls.paginate.length !== 1 || calls.paginate[0].input.per_page !== 100) throw new Error("security comments must be paginated");
  if (calls.updated.length !== 1 || calls.updated[0].comment_id !== 10) throw new Error("oldest bot summary was not updated");
  if (calls.deleted.length !== 1 || calls.deleted[0] !== 11) throw new Error("only later bot summary should be deleted");
  if (calls.created.length !== 0) throw new Error("existing bot summary should be updated");

  resetCalls();
  currentPullRequest.head.sha = "c".repeat(40);
  await run(github, context, core);
  if (calls.paginate.length !== 0) throw new Error("stale security summary must not list comments");
  if (calls.deleted.length !== 0 || calls.updated.length !== 0 || calls.created.length !== 0) {
    throw new Error("stale security summary must not mutate comments");
  }
  console.log("security summary behavioral contract passed");
}).catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

echo "security summary contract passed"
