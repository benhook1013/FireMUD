#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"

python3 - "$CI_WORKFLOW" <<'PY'
from pathlib import Path
import sys

import yaml

path = Path(sys.argv[1])
workflow = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
job = workflow["jobs"]["validation-summary"]
steps = job["steps"]
summary_steps = [
    step for step in steps
    if step.get("name") == "💬 Publish Validation Summary Comment"
]
if len(summary_steps) != 1:
    raise SystemExit("ci validation-summary must contain exactly one publisher step")
script = summary_steps[0]["with"]["script"]
required_fragments = [
    "github.rest.pulls.get",
    'currentPullRequest.state !== "open"',
    "currentPullRequest.head.sha !== expectedHeadSha",
    "currentPullRequest.base.ref !== expectedBaseRef",
    "currentPullRequest.base.sha !== expectedBaseSha",
    "github.paginate(github.rest.issues.listComments",
    "per_page: 100",
    'comment.user?.login === "github-actions[bot]"',
    "comment.created_at",
    "const existing = summaryComments.reduce",
]
for fragment in required_fragments:
    if fragment not in script:
        raise SystemExit(f"validation-summary publisher is missing {fragment!r}")
if script.index("github.rest.pulls.get") > script.index("github.paginate(github.rest.issues.listComments"):
    raise SystemExit("validation-summary must verify the current PR before listing comments")
PY

script_b64="$({
  python3 - "$CI_WORKFLOW" <<'PY'
import base64
from pathlib import Path
import sys

import yaml

workflow = yaml.load(Path(sys.argv[1]).read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
script = next(
    step["with"]["script"]
    for step in workflow["jobs"]["validation-summary"]["steps"]
    if step.get("name") == "💬 Publish Validation Summary Comment"
)
print(base64.b64encode(script.encode("utf-8")).decode("ascii"))
PY
})"

SCRIPT_B64="$script_b64" node <<'NODE'
const assert = require("node:assert/strict");

const script = Buffer.from(process.env.SCRIPT_B64, "base64")
  .toString("utf8")
  .replace(/\$\{\{\s*needs\.[^}]+\}\}/g, "success");

const context = {
  repo: { owner: "example", repo: "firemud" },
  issue: { number: 42 },
  payload: {
    pull_request: {
      head: { sha: "h" },
      base: { ref: "develop", sha: "b" },
    },
  },
};

function buildHarness(currentPullRequest, comments) {
  const calls = { paginate: 0, updates: [], deletes: [], creates: [] };
  const github = {
    rest: {
      pulls: {
        get: async () => ({ data: currentPullRequest }),
      },
      issues: {
        updateComment: async (request) => calls.updates.push(request),
        deleteComment: async (request) => calls.deletes.push(request),
        createComment: async (request) => calls.creates.push(request),
      },
    },
    paginate: async () => {
      calls.paginate += 1;
      return comments;
    },
  };
  const core = {
    info: () => {},
    summary: {
      addRaw() { return this; },
      addList() { return this; },
      async write() {},
    },
  };
  return { github, core, calls };
}

async function run(currentPullRequest, comments) {
  const { github, core, calls } = buildHarness(currentPullRequest, comments);
  const execute = new Function("github", "context", "core", `return (async () => {\n${script}\n})()`);
  await execute(github, context, core);
  return calls;
}

(async () => {
  const staleCalls = await run(
    { state: "open", head: { sha: "new-head" }, base: { ref: "develop", sha: "b" } },
    [],
  );
  assert.equal(staleCalls.paginate, 0, "stale PRs must not list comments");
  assert.deepEqual(staleCalls.updates, [], "stale PRs must not update comments");
  assert.deepEqual(staleCalls.deletes, [], "stale PRs must not delete comments");
  assert.deepEqual(staleCalls.creates, [], "stale PRs must not create comments");

  const currentPullRequest = {
    state: "open",
    head: { sha: "h" },
    base: { ref: "develop", sha: "b" },
  };
  const firstPageNoise = Array.from({ length: 35 }, (_, index) => ({
    id: 1000 + index,
    user: { login: "github-actions[bot]" },
    body: `### Unrelated Summary ${index + 1}`,
    created_at: "2025-12-01T00:00:00Z",
  }));
  const currentCalls = await run(currentPullRequest, [
    ...firstPageNoise,
    {
      id: 1,
      user: { login: "contributor" },
      body: "### Validation Summary\nspoofed contributor comment",
      created_at: "2026-01-03T00:00:00Z",
      updated_at: "2026-01-03T00:00:00Z",
    },
    {
      id: 2,
      user: { login: "github-actions[bot]" },
      body: "### Validation Summary\nold bot summary",
      created_at: "2026-01-01T00:00:00Z",
      updated_at: "2026-01-03T00:00:00Z",
    },
    {
      id: 3,
      user: { login: "github-actions[bot]" },
      body: "### Validation Summary\n<!-- firemud-validation-summary -->\nnew bot summary",
      created_at: "2026-01-02T00:00:00Z",
      updated_at: "2026-01-01T00:00:00Z",
    },
  ]);
  assert.deepEqual(
    currentCalls.updates.map((request) => request.comment_id),
    [2],
    "the oldest bot-owned summary must be updated",
  );
  assert.deepEqual(
    currentCalls.deletes.map((request) => request.comment_id),
    [3],
    "only later bot-owned duplicates may be deleted",
  );
  assert.deepEqual(currentCalls.creates, [], "an existing bot summary must be reused");
  assert.equal(currentCalls.paginate, 1, "comment listing must be paginated once");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
NODE
