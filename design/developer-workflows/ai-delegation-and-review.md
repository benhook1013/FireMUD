# AI Delegation And Review

Use this guide when the main thread is considering subagents, independent verification, or Codex Spark.

## Delegation

- `gpt-5.6-luna` is the default for bounded repository reading, exhaustive inventories, mechanical or well-specified edits, focused investigation, and targeted tests. The main thread defines the boundary and reviews the result; Luna is not independent final-review evidence.
- Reserve `gpt-5.6-terra` for adversarial independent verification, ambiguous cross-contract reasoning, or recovery after Luna fails a declared coverage gate. Do not delegate product or architecture design decisions.
- Delegate bounded repository investigation when it provides meaningful context or cost savings; do not delegate merely to avoid a few routine tool calls. Keep a single main-thread workflow unless a human asks otherwise.
- Subagents may not run Gradle, Docker, smoke, or repository-wide validation unless the main thread delegates one named command. The main thread runs consolidated validation after integration.

## Spark And Review Evidence

- Write each human-dispatched Codex Spark handover prompt or review brief to ignored `tmp/firemud-spark-reviews/`; inspect the same file for appended responses.
- For an exhaustive Spark audit, require a per-item coverage ledger, named source/design documents read, and an incomplete-review gate. An unsupported "No findings" statement is not exhaustive evidence.
- Treat Spark as opportunistic defect discovery. If it does not meet its coverage gate, use Luna for exhaustive bounded checking and Terra for independent final verification rather than treating the Spark run as complete review.
