---
name: firemud-pr-report
description: Use for a FireMUD "PR report", review-count history, or merge-readiness summary; do not use for implementing fixes or requesting CodeRabbit reviews.
metadata:
  short-description: Report FireMUD PR review history
---

# FireMUD PR reporting

Use when asked for a FireMUD "PR report", review-count history, or merge-readiness summary; do not use for implementing fixes or requesting CodeRabbit reviews.

Resolve the current checkout root with `git rev-parse --show-toplevel`, then read [Pull Request Lifecycle](../../../design/developer-workflows/pr-lifecycle.md) relative to this `SKILL.md`. Gather and format the report according to that guide from existing hosted summaries/threads and owning-lane CLI checkpoint comments. Keep reporting read-only: do not trigger reviews, mutate CI, resume paused tasks, or merge.
