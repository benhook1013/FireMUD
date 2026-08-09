# Repository Health Check

Run this workflow only when the human explicitly requests a repository health check. It is a bounded maintenance pass, not a calendar-driven job, a per-PR checklist, or a replacement for product, architecture, PR-lifecycle, validation, or infrastructure procedures.

## Scope And Inspection Window

Unless the human specifies another period, inspect the preceding seven days. Establish the current `main` and `develop` heads before interpreting historical results, and inspect the latest relevant run even when it falls outside the window so an old failure is not mistaken for current state. Treat a queued or running job as potentially stalled after 60 minutes or when it materially exceeds comparable recent runs.

Use [AGENTS.md](../../AGENTS.md) as the always-on authority. Reuse [PR lifecycle](./pr-lifecycle.md) for GitHub Actions and Renovate mechanics, [validation and runtime proof](./validation-and-runtime-proof.md) for repository changes, and the infrastructure documentation for hosted-environment behavior. This workflow coordinates those checks without duplicating their detailed procedures.

## AI Observations

Review every entry in [AI observations](../project-management/ai-observations.md). Check whether its expected pattern already exists in current guidance, tooling, implementation, and proof. Subject to the human's request and the normal validation rules, make a bounded durable improvement when one is still needed. Remove an entry only when evidence shows it is addressed, obsolete, or disproved. Retain it only when a genuine blocker or deliberate postponement remains, recording the reason and reconsideration trigger. Aim to reduce the inbox to zero.

## Unattended Automation And CI

Inspect workflows triggered directly by pushes to `main` and `develop`, scheduled workflows, and relevant manually dispatched platform-maintenance workflows. For `workflow_run` chains, identify the originating workflow, event, branch, and head SHA before assigning a branch lane. Inspect current failures, `timed_out` and `action_required` conclusions, missing expected downstream runs, repeated signatures, and possible stalls. Compare failures with later runs for the same workflow and target SHA or branch so superseded history is not treated as current breakage.

Report each actionable result with its workflow, run URL, target identity, concise cause, and current owner or next decision when known. Use the workflow's event provenance rather than GitHub's displayed branch alone.

## Shared Hosted Environments

Resolve the current `develop` SHA. Confirm that dev-demo records it as its reconciled target, that a real rollout and hosted `LOGIN`/`LOOK` smoke completed for that target, and that the dev-demo reconciler is not repeatedly dispatching the same unhealthy target. Inspect preview reconciler and namespace-janitor health for systemic cluster access, reconciliation, or capacity problems. Use workflow summaries, deployment stages, and reconciled-target evidence alongside top-level workflow conclusions.

## Aged Renovate Work

Inspect every open Renovate PR older than 24 hours. For each one, check its head SHA, last update time, mergeability, required checks, and relevant automerge configuration. Explain whether CI, conflicts, stale base state, update policy, compatibility waiting, or Renovate scheduling/behavior prevents automerge, and state whether waiting remains reasonable or focused manual attention is likely to help.

## Recurring Operational Friction

Compare only evidence encountered during the observation, CI, hosted-environment, and Renovate checks. Treat a pattern as recurring only when at least two independent examples support the same underlying cause. Report the shared seam, examples, operational cost, and likely durable prevention. Route a human-accepted follow-up through [recurring code-review sweeps](../project-management/recurring-code-review-sweeps.md) rather than creating another sweep process.

## Report

Return one concise report containing actions completed while consuming observations, actionable findings, items waiting on an external condition or deliberate postponement, healthy surfaces and evidence checked, recurring-pattern candidates supported by multiple examples, and anything that could not be inspected. Classify validation and runtime evidence as `confirmed`, `unrun`, `partial`, or `unavailable`.
