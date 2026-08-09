# Developer Workflows

This directory contains repeatable contributor procedures for changing, validating, reviewing, and manually exercising FireMUD. The root [AGENTS.md](../../AGENTS.md) contains repository-wide AI authority, safety, and workflow-routing rules; use these guides only when their trigger applies.

Use the workflow that matches the current contribution, validation, review, or manual product-flow task rather than loading every procedure up front.

## Development Process And Repository Stewardship

- [pr-lifecycle.md](./pr-lifecycle.md) – PR status, CodeRabbit, CI, Renovate, merging, and branch/worktree cleanup.
- [repository-health-check.md](./repository-health-check.md) – Human-requested bounded check of observations, unattended automation, hosted environments, aged Renovate work, and recurring operational friction.
- [validation-and-runtime-proof.md](./validation-and-runtime-proof.md) – Formatting, scoped checks, documentation hygiene, and canonical runtime/smoke proof selection.
- [ai-delegation-and-review.md](./ai-delegation-and-review.md) – Subagent selection, delegation boundaries, and independent review.

## Testing And Playtesting

- [login-session-smoke-tests.md](./login-session-smoke-tests.md) – Walks through the `LOGIN` + `LOOK` path over both WebSocket and Telnet, including expected transcripts and pointers back to the corresponding architecture and implementation-tracking documents.
- [player-playtest-checklist.md](./player-playtest-checklist.md) – Manual player-facing feature and cross-transport playtest workflow.
