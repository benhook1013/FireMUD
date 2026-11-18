# AI Delegation And Review

Use this guide when an orchestrator delegates to subagents or commissions an independent review. Model choice is in [model routing](./model-routing.md). Roles are defined in [AGENTS.md](../../AGENTS.md#roles).

## Overseer Arrangement

This applies only when the human sets up an Overseer.

- The Overseer holds direction, priorities, consequential decisions, evidence adjudication, cross-lane coordination, and contact with the human. It delegates operational work (searches, audits, edits, tests, tooling) to orchestrators or subagents rather than doing it itself; if no capacity is available, it reports that.
- On routine wakes the Overseer reads pending coordination and acts only when a decision or integration is due.
- When a worker's behavior or understanding of its authority is wrong, the Overseer gives a direct, bounded correction and checks it took effect.
- Workers own routine choices within accepted design, including fixes, retries, and test failures. They escalate only a real conflict with accepted design, a change to authority, priority, or scope, or a blocker that needs the Overseer, with a short evidence pointer and a proposed next step. They do not send debugging noise or ask permission for authorized steps.
- The Overseer may fix small agent-process documentation itself (delegating the edit, checking the result, and publishing it) without routine human approval. This does not cover product, architecture, or design documents or consequential decisions, and does not widen merge or closure authority.
- When assigned, the Overseer stewards [AI observations](../project-management/ai-observations.md): at existing checkpoints it notices worthwhile batches, has helpers group and check them, assigns improvements, and removes entries shown addressed, obsolete, or disproved. Otherwise workers only append.
- Lanes contact each other only when the human allows it and there is a real coordination need (a shared resource, a review-slot claim, a dependency). Keep such messages to a sentence or two. No acknowledgement loops or status polling.
- Keep track of where instructions came from. Do not claim "the human said" something without a traceable source, and do not let an old message override a later direction.
- Task briefs hold durable goals and constraints; progress records hold the current phase, next step, and blocker. Replace stale state rather than appending.

## General Delegation Rules

- Delegate bounded investigation, bulk reading, mechanical edits, or focused checks when it saves real cost or context. Do not delegate to avoid a few tool calls, and do not split trivial work. Keep one orchestrator per workstream.
- Do not delegate product or architecture decisions.
- **Every delegation prompt states:** the absolute worktree path and branch (and the commit, when the evidence depends on it); the exact write scope; the governing design, tracker, or workflow doc; the success condition; the exact validation commands allowed, or `none` with whether it is not applicable or deferred; and the standard limits block below.
- The subagent checks `pwd` and `git branch --show-current` (and `git rev-parse HEAD` when a commit is pinned) before reading or editing, and reports any mismatch instead of proceeding. It reports if the revision drifted before handoff.
- Documentation delegation names the canonical owner and what each secondary document should say locally. A request for repeated detail does not justify duplicating it.
- For a review fix, the orchestrator decides the finding and states the smallest intended change first. The subagent does not widen a local fix into a new mechanism; if a wider change seems needed, it reports back.
- Scope follows correctness, not file counts. Name every file the correct change needs; if that crosses a PR limit, the orchestrator handles topology under [PR lifecycle](./pr-lifecycle.md#change-and-merge-policy).
- Validation in a subagent is limited to the commands named. No substitutes, approximations, or extra checks.
- A delegated task ends when its edits and named checks are done. No self-chosen extra passes. Discovery and review assignments finish their declared scope; if blocked, they report what was covered, what was not, and why.
- Reuse a finished subagent only for a direct continuation of the same task. Unrelated work gets a fresh, descriptively named subagent.
- Prefer native background waits that report back to the parent. One observer per transition; no duplicate polling.

**Standard limits block** (paste into every delegation prompt):

> Do not inspect or change review, CI, or PR state: no review requests or commands, no CI triggers or reruns, no resolving threads, no commit, push, retarget, open, merge, or close. Do not revert, delete, overwrite, stage, or commit anything outside your assigned files; preserve unrelated edits and stop to report if your files have overlapping changes.

The orchestrator may lift a specific line of that block for a specific task by saying so explicitly. Merge authority is never delegated.

## Integrating Delegated Work

- The orchestrator verifies subagent claims itself: it inspects the returned diff against the assigned scope and governing docs, checks interactions with concurrent changes, and rejects unrequested edits. Passing validation is not a substitute for reading the diff.
- A handoff reports changed files, the named checks and their results (or not applicable or deferred), assumptions, and open concerns. A bare "done" is not a handoff.
- At the end of a substantial round, report in one line which models and tiers were used for which roles, any substitution or reuse, and why capacity was left unused if substantial work remained.

## Independent Review Evidence

- Routine PR review runs through FireController's Hosted and CLI channels. Do not spawn subagents to review or re-review fix batches during normal PR polishing.
- Commission a model-based review only for: a consequential check before the controller is available, a risk the controller cannot cover, a fresh whole-domain assessment, a deliberate audit, or an explicit request from the human or Overseer. Use a fresh-context reviewer, preferably from a different model family than the author, given the governing authority, the exact artifact and scope, and necessary facts, but not earlier findings or a desired conclusion.
- An exhaustive audit requires per-item coverage, the sources read, and an explicit statement of anything not covered. "No findings" without that is not evidence.
- Record commissioned review passes, findings, decisions, and fixes in FireController with the `firecontroller-use` skill. Do not keep parallel ledgers or status files. Scratch notes are fine but are not the record. If the controller is unavailable, keep the evidence and report the recording gap.
- A completed review of a declared scope does not claim more than that scope.
