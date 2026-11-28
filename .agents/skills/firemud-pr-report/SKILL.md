---
name: firemud-pr-report
description: Use for a FireMUD "PR report", review-count history, or merge-readiness summary; do not use for implementing fixes or requesting CodeRabbit reviews.
metadata:
  short-description: Report FireMUD PR review history
---

# FireMUD PR reporting

Read-only. Read the PR's review history from FireController with the `firecontroller-reviews` skill (`reviews runs`, `reviews readiness`), plus its threads and latest review summary as [Review And Status](../../../design/developer-workflows/pr-lifecycle.md#review-and-status) requires. Report each round as [Review Orchestration](../../../design/developer-workflows/pr-lifecycle.md#review-orchestration) lists. Judge readiness only against [Review Completion Criteria](../../../design/developer-workflows/pr-lifecycle.md#review-completion-criteria). Do not request reviews, touch CI, resume paused tasks, or merge.
