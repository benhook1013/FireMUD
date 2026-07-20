# Developer Workflows

This directory collects conditional workflows for contributing to and exercising FireMUD. The root [AGENTS.md](../../AGENTS.md) contains the always-on project, authority, and safety rules; use these guides when their trigger applies.

Use the workflow that matches the current contribution, validation, review, or manual product-flow task rather than loading every procedure up front.

## Documents

- [login-session-smoke-tests.md](./login-session-smoke-tests.md) – Walks through the `LOGIN` + `LOOK` path over both WebSocket and Telnet, including expected transcripts and pointers back to the corresponding architecture and implementation-tracking documents.
- [pr-lifecycle.md](./pr-lifecycle.md) – PR status, CodeRabbit, CI, Renovate, merging, and branch/worktree cleanup.
- [validation-and-runtime-proof.md](./validation-and-runtime-proof.md) – Formatting, scoped checks, documentation hygiene, and canonical runtime/smoke proof selection.
- [ai-delegation-and-review.md](./ai-delegation-and-review.md) – Subagent selection, delegation boundaries, and independent review.
