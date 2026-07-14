#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
workflow="$repo_root/.github/workflows/static-analysis-summary.yml"

grep -Fq 'github.rest.actions.listWorkflowRuns({' "$workflow"
grep -Fq 'workflow_id: "codeql.yml",' "$workflow"
grep -Fq 'head_sha: headSha,' "$workflow"
grep -Fq 'return "skipped";' "$workflow"

if grep -Fq 'listWorkflowRunsForRepo' "$workflow"; then
  echo "static analysis summary must query the CodeQL workflow directly" >&2
  exit 1
fi

echo "static analysis summary contract passed"
