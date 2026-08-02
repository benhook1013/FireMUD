# AI Delegation And Review

Use this guide when the main thread is considering subagents or independent verification.

## Delegation

- Delegate to `gpt-5.6-luna` at `high` for routine bounded investigation, mechanical patches, and straightforward test updates. Use Luna at `xhigh` for substantial delegated work and independent review, and at `max` for known-hard cross-file correctness work, exhaustive audits, or a failed `xhigh` attempt. Luna reasoning levels are not approval-gated.
- Escalate to `gpt-5.6-sol` at `medium` when broad synthesis, ambiguous evidence, or general reasoning matters more than coding-agent throughput; it is a task-type escalation, not the automatic next tier after Luna. Sol at `high` is the absolute subagent ceiling and still requires explicit human approval under root guidance. Do not use Terra as a normal routing tier or delegate product or architecture design decisions.
- Delegate bounded repository investigation when it provides meaningful context or cost savings; do not delegate merely to avoid a few routine tool calls. Keep a single main-thread workflow unless a human asks otherwise.
- Subagent validation is a closed allowlist. A subagent may run only the exact validation commands named in its task; prohibiting Gradle or another canonical command does not authorize standalone linters, approximations, ad hoc flags, or other substitute checks. If no validation is named, report it as deferred. The main thread runs consolidated validation after integration.
- A delegated task ends when its assigned edits and explicitly named checks are complete. Return immediately rather than adding self-selected wording passes, structural audits, cleanup, or substitute validation merely to appear thorough.
- Subagents must never invoke CodeRabbit CLI or hosted review, post review commands, start or stop CodeRabbit, resolve review threads, or otherwise alter review state. They must not commit, push, retarget branches, or open or merge PRs unless the main thread explicitly delegates that exact action. They must never revert, delete, overwrite, stage, or commit concurrent edits outside their assigned file set; report an overlap and stop instead.

## Independent Review Evidence

- For an exhaustive audit, require a per-item coverage ledger, named source and design documents read, and an incomplete-review gate. An unsupported "No findings" statement is not exhaustive evidence.
- Use hosted or CLI CodeRabbit as the default iterative PR defect-discovery and fix-verification path. Spend model-backed independent review only for a consequential initial check before CodeRabbit is available, a risk CodeRabbit cannot cover, or an explicitly requested audit.
- When model-backed review is justified, use a fresh-context reviewer at the required delegation tier. The main thread remains responsible for checking the evidence and deciding whether the review is complete.
- Do not spend subagent review tokens duplicating general PR review that available CodeRabbit hosted or CLI capacity can perform. End-of-run reporting must summarize how many subagents were used, their bounded roles, and whether they changed files or only supplied evidence.
