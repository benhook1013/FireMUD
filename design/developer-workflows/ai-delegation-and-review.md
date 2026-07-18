# AI Delegation And Review

Use this guide when the main thread is considering subagents or independent verification.

## Delegation

- Delegate to `gpt-5.6-luna` at `high` for routine bounded investigation, mechanical patches, and straightforward test updates. Use Luna at `xhigh` for substantial delegated work and independent review, and at `max` for known-hard cross-file correctness work, exhaustive audits, or a failed `xhigh` attempt.
- Escalate to `gpt-5.6-sol` at `medium` when broad synthesis, ambiguous evidence, or general reasoning matters more than coding-agent throughput; it is a task-type escalation, not the automatic next tier after Luna. Sol at `high` is the absolute subagent ceiling and still requires explicit human approval under root guidance. Do not use Terra as a normal routing tier or delegate product or architecture design decisions.
- Delegate bounded repository investigation when it provides meaningful context or cost savings; do not delegate merely to avoid a few routine tool calls. Keep a single main-thread workflow unless a human asks otherwise.
- Subagents may not run Gradle, Docker, smoke, or repository-wide validation unless the main thread delegates one named command. The main thread runs consolidated validation after integration.

## Independent Review Evidence

- For an exhaustive audit, require a per-item coverage ledger, named source and design documents read, and an incomplete-review gate. An unsupported "No findings" statement is not exhaustive evidence.
- Use a fresh-context reviewer at the required delegation tier when independent verification materially reduces risk. The main thread remains responsible for checking the evidence and deciding whether the review is complete.
