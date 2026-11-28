---
name: firemud-pr-report
description: Use for a FireMUD "PR report", review-count history, or merge-readiness summary; do not use for implementing fixes or requesting CodeRabbit reviews.
metadata:
  short-description: Report FireMUD PR review history
---

# FireMUD PR reporting

Read-only. Read the PR's review history from FireController with the `firecontroller-reviews` skill (`reviews runs`, `reviews readiness`), the record named in [Pull Request Lifecycle](../../../design/developer-workflows/pr-lifecycle.md#review-and-status). For each round report the reviewed head, raw and useful counts, new versus repeat findings, decisions, and what remains. Judge readiness only against [Review Completion Criteria](../../../design/developer-workflows/pr-lifecycle.md#review-completion-criteria). Do not request reviews, touch CI, resume paused tasks, or merge.
