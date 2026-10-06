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

python3 - "$repo_root/.github/workflows/security.yml" <<'PYTHON'
from pathlib import Path
import sys
import yaml

workflow = yaml.load(Path(sys.argv[1]).read_text(), Loader=yaml.BaseLoader)
condition = workflow["jobs"]["security-summary"]["if"]
expected = "${{ !cancelled() && github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository && github.actor != 'dependabot[bot]' && (github.event.action != 'edited' || github.event.changes.base.ref != null) }}"
if condition != expected:
    raise SystemExit("Optional summary must preserve cancellation, native-PR trust and substantive-event predicates")
PYTHON

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
let deleteStatus = null;
let existingBody = "### Security Summary\nstale";
const commentsList = async () => undefined;
const github = {
  rest: {
    pulls: {
      get: async () => ({ data: currentPullRequest }),
    },
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
        body: existingBody,
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

  existingBody = calls.updated[0].body;
  resetCalls();
  await run(github, context, core);
  if (calls.updated.length || calls.created.length) throw new Error("identical security summaries must not write comments");
  if (calls.deleted.length !== 1 || calls.deleted[0] !== 11) throw new Error("unchanged security summaries must still delete duplicates");
  existingBody = "### Security Summary\nstale";

  resetCalls();
  currentPullRequest.head.sha = "c".repeat(40);
  await run(github, context, core);
  if (calls.paginate.length !== 0) throw new Error("stale security summary must not list comments");
  if (calls.deleted.length !== 0 || calls.updated.length !== 0 || calls.created.length !== 0) {
    throw new Error("stale security summary must not mutate comments");
  }
  currentPullRequest.head.sha = context.payload.pull_request.head.sha;
  calls.paginate.length = 0;
  calls.deleted.length = 0;
  calls.updated.length = 0;
  calls.created.length = 0;
  deleteStatus = 404;
  await run(github, context, core);
  if (calls.deleted.length !== 1 || calls.deleted[0] !== 11) {
    throw new Error("HTTP 404 duplicate deletion must be ignored after the delete attempt");
  }
  calls.paginate.length = 0;
  calls.deleted.length = 0;
  calls.updated.length = 0;
  calls.created.length = 0;
  deleteStatus = 500;
  let rejected = false;
  try {
    await run(github, context, core);
  } catch (error) {
    rejected = error?.status === 500;
  }
  if (!rejected) throw new Error("non-404 duplicate deletion errors must propagate");
  console.log("security summary behavioral contract passed");
}).catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
NODE

# Execute the checked-in scanner step using the explicit Actions Bash shell.
# The fake scanner and reports stay in one disposable directory; no scan/network occurs.
python3 - "$workflow" "$repo_root/config/security/trivy.yaml" <<'PYTHON'
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import yaml

workflow = yaml.load(Path(sys.argv[1]).read_text(), Loader=yaml.BaseLoader)
steps = workflow["jobs"]["trivy-scan"]["steps"]
scanner = next(step for step in steps if step.get("name") == "🔍 Run Trivy and Save Report")
upload = next(step for step in steps if step.get("name") == "📤 Upload Trivy Report")
if scanner.get("shell") != "bash" or upload.get("if") != "always()":
    raise SystemExit("Trivy must use explicit pipefail Bash and retain always-uploaded reports")
settings = yaml.safe_load(Path(sys.argv[2]).read_text())
if settings.get("exit-code") != 1 or settings.get("severity") != ["CRITICAL", "HIGH"] or settings.get("ignore-unfixed") is not True:
    raise SystemExit("Trivy failure policy and filters must remain intact")
with tempfile.TemporaryDirectory(prefix="firemud-trivy-pipeline-") as directory:
    root = Path(directory)
    scanner_path = root / "trivy"
    scanner_path.write_text('#!/bin/sh\nprintf "scanner-result-%s\\n" "$FAKE_TRIVY_EXIT"\nexit "$FAKE_TRIVY_EXIT"\n')
    scanner_path.chmod(0o755)
    for exit_status in (0, 1, 2):
        environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"], FAKE_TRIVY_EXIT=str(exit_status))
        # actions/runner explicit shell:bash -> bash --noprofile --norc -e -o pipefail {0}.
        script = root / "step.sh"
        script.write_text(scanner["run"])
        result = subprocess.run(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", str(script)], cwd=root, env=environment, capture_output=True, text=True)
        if result.returncode != exit_status:
            raise SystemExit(f"Trivy exit {exit_status} was masked: {result.returncode}")
        if (root / "trivy-report.txt").read_text() != f"scanner-result-{exit_status}\n":
            raise SystemExit("tee must retain the report even when the scanner fails")
        if ("::endgroup::" in result.stdout) != (exit_status == 0):
            raise SystemExit("Failed scanner pipelines must stop the step")
print("Trivy pipeline contract passed: scanner success/vulnerability/error with reports retained")
PYTHON

echo "security summary contract passed"
