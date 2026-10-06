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
build_test_steps = workflow["jobs"]["build-and-test"]["steps"]
test_report_steps = [
    step for step in build_test_steps
    if step.get("name") == "📤 Upload JUnit XML Reports"
]
if len(test_report_steps) != 1:
    raise SystemExit("build-and-test must contain exactly one JUnit XML artifact upload")
test_report = test_report_steps[0]
test_report_with = test_report["with"]
if test_report.get("if") != "${{ always() && contains(fromJSON('[\"success\",\"failure\",\"cancelled\"]'), steps.checks.outcome) }}":
    raise SystemExit("JUnit XML upload must run after attempted checks, including failure or cancellation")
attempted_check_outcomes = {"success", "failure", "cancelled"}
outcome_truth_table = {
    "": False,
    "skipped": False,
    "success": True,
    "failure": True,
    "cancelled": True,
}
for outcome, expected in outcome_truth_table.items():
    if (outcome in attempted_check_outcomes) != expected:
        raise SystemExit(f"JUnit XML upload attempted-check guard has wrong result for {outcome!r}")
if test_report.get("uses") != "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a":
    raise SystemExit("JUnit XML upload must use the pinned upload-artifact action")
if test_report_with.get("name") != "junit-${{ matrix.module }}-${{ github.sha }}":
    raise SystemExit("JUnit XML artifact name must identify its module and head")
expected_test_report_path = (
    "${{ matrix.module == 'load-testing' && 'dev-tools/load-testing/build/test-results/**/*.xml' "
    "|| format('services/{0}/build/test-results/**/*.xml', matrix.module) }}"
)
if test_report_with.get("path") != expected_test_report_path:
    raise SystemExit("JUnit XML upload must cover module test-results XML, including load-testing")
if test_report_with.get("if-no-files-found") != "warn":
    raise SystemExit("missing JUnit XML reports must warn without failing builds that did not reach tests")

check_steps = [step for step in build_test_steps if step.get("name") == "🧪 Run Gradle Checks"]
if len(check_steps) != 1:
    raise SystemExit("build-and-test must contain exactly one module validation step")
check_step = check_steps[0]
check_run = check_step.get("run", "")
game_session_integration = (
    "./gradlew :game-session-service:integrationTest :game-session-service:check -PfullCheck"
)
other_module_check = "./gradlew :${{ matrix.module }}:check -PfullCheck"
if '[ "${{ matrix.module }}" = "game-session-service" ]' not in check_run:
    raise SystemExit("Game Session must use its explicit integration-test validation path")
if game_session_integration not in check_run:
    raise SystemExit("Game Session integrationTest must run before its full check in one Gradle invocation")
if other_module_check not in check_run:
    raise SystemExit("non-Game-Session modules must retain the existing full check invocation")
if check_step.get("continue-on-error", "false").lower() == "true":
    raise SystemExit("module validation must remain a required CI gate")
gradle_commands = [line.strip() for line in check_run.splitlines() if line.strip().startswith("./gradlew ")]
if gradle_commands != [game_session_integration, other_module_check]:
    raise SystemExit("module validation must use only the ordered Game Session and unchanged module Gradle gates")

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
    "github.rest.issues.deleteComment",
]
for fragment in required_fragments:
    if fragment not in script:
        raise SystemExit(f"validation-summary publisher is missing {fragment!r}")
if script.index("github.rest.pulls.get") > script.index("github.paginate(github.rest.issues.listComments"):
    raise SystemExit("validation-summary must verify the current PR before listing comments")
PY

python3 - "$ROOT_DIR/.github/workflows/ci.yml" <<'PYTHON'
from pathlib import Path
import sys
import yaml

workflow = yaml.load(Path(sys.argv[1]).read_text(), Loader=yaml.BaseLoader)
condition = workflow["jobs"]["validation-summary"]["if"]
expected = "${{ !cancelled() && github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && github.actor != 'dependabot[bot]' && (github.event.action != 'edited' || github.event.changes.base.ref != null) }}"
if condition != expected:
    raise SystemExit("Optional summary must preserve cancellation, native-PR trust and substantive-event predicates")
PYTHON

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

function buildHarness(currentPullRequest, comments, deleteStatus = null) {
  const calls = { paginate: 0, updates: [], deletes: [], creates: [] };
  const github = {
    rest: {
      pulls: {
        get: async () => ({ data: currentPullRequest }),
      },
      issues: {
        updateComment: async (request) => calls.updates.push(request),
        deleteComment: async (request) => {
          calls.deletes.push(request);
          if (deleteStatus !== null) throw { status: deleteStatus };
        },
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

async function run(currentPullRequest, comments, deleteStatus = null) {
  const { github, core, calls } = buildHarness(currentPullRequest, comments, deleteStatus);
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

  const duplicateComments = [
    ...firstPageNoise,
    {
      id: 2,
      user: { login: "github-actions[bot]" },
      body: "### Validation Summary\nold bot summary",
      created_at: "2026-01-01T00:00:00Z",
    },
    {
      id: 3,
      user: { login: "github-actions[bot]" },
      body: "### Validation Summary\nnew bot summary",
      created_at: "2026-01-02T00:00:00Z",
    },
  ];
  const unchangedCalls = await run(currentPullRequest, duplicateComments.map((comment) =>
    comment.id === 2 ? { ...comment, body: currentCalls.updates[0].body } : comment
  ));
  assert.deepEqual(unchangedCalls.updates, [], "identical summaries must not PATCH");
  assert.deepEqual(unchangedCalls.creates, [], "identical summaries must retain the canonical comment");
  assert.deepEqual(unchangedCalls.deletes.map((request) => request.comment_id), [3], "unchanged summaries still remove duplicates");

  const notFoundCalls = await run(currentPullRequest, duplicateComments, 404);
  assert.deepEqual(
    notFoundCalls.deletes.map((request) => request.comment_id),
    [3],
    "HTTP 404 duplicate deletion must be ignored after the delete attempt",
  );
  await assert.rejects(
    run(currentPullRequest, duplicateComments, 500),
    (error) => error?.status === 500,
    "non-404 duplicate deletion errors must propagate",
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
NODE
