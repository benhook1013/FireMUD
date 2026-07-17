# Domain Review Orchestration Plan

Use this plan to run the architecture review prompts as a coordinated convergence pass without opening one independent review thread per prompt.

Scope for this pass:

- Include the 15 domain prompts listed in the suggested lane split.
- Exclude `25-monetization-and-account-lifecycle.md` unless a blocker discovered elsewhere requires a billing or entitlement decision.
- Use `30-per-service-deep-dive-template.md` only as a follow-up when a convergence pass identifies a service that still has unresolved implementation-blocking ambiguity.

Required setup:

- Start each lane with its domain prompt, not with `00-fresh-reread-issue-only-preamble.md`.
- Treat `00-fresh-reread-issue-only-preamble.md` as a convergence follow-up prompt to send after a round of design changes, clarifications, or blocker resolution work.
- On every pass, re-read the listed documents from disk in their current state before answering.
- Keep the review boundary tight. Follow references only when a listed document clearly delegates a canonical contract needed to resolve a contradiction or implementation-blocking gap.
- Stay in design-review mode only. Do not implement code, propose commits, or expand scope into speculative future architecture.

Recommended execution model:

- Run a small number of parallel review lanes instead of one lane per prompt.
- Let each lane review a cohesive architecture domain that already shares contracts and likely blockers.
- Use one coordinating reviewer to merge duplicate findings, detect cross-lane contradictions, and decide whether another pass or a service deep dive is needed.
- After a batch of doc updates, send `00-fresh-reread-issue-only-preamble.md` together with the lane's original domain prompt set to force a fresh issue-only reread from disk.
- Prefer keeping the same lane reviewers alive across multiple convergence rounds so each lane retains its working context while still being forced to restart reasoning from disk by the reread preamble.

Suggested lane split:

1. Core architecture lane
   Prompts: `10-main-architecture-overview.md`, `11-game-loop-and-tick-core.md`, `12-redis-runtime-and-data-contracts.md`
   Focus: service boundaries, tick ownership, transactional rules, Redis ownership and mutation contracts
2. Operations lane
   Prompts: `13-redis-operations-and-recovery.md`, `20-observability-contracts.md`, `21-operations-runbooks-and-recovery.md`
   Focus: operator actions, failure handling, recovery, alerts, traces, verification contracts
3. Authoring lane
   Prompts: `17-scripting-dsl-and-runtime.md`, `18-designer-tooling-and-modding.md`, `19-world-and-content-authoring.md`
   Focus: authoring lifecycle, validation, packaging, publish semantics, rollback and designer workflows
4. Data and platform lane
   Prompts: `16-persistence-assets-and-migrations.md`, `22-environments-and-secrets.md`, `23-deployment-cicd-and-platform-security.md`
   Focus: persistence ownership, asset handling, migrations, environment isolation, deployment and rollback safety, platform security
5. Access and client lane
   Prompts: `14-networking-protocols-and-reconnection.md`, `15-auth-sessions-and-multi-tenancy.md`, `26-user-journeys-and-ux.md`
   Focus: client entry flows, protocol handling, reconnection, session and tenant boundaries, whether user journeys are actually implementable

Why use lanes instead of one prompt per thread:

- Several prompt sets share the same canonical contracts and would otherwise report the same blockers multiple times.
- Lanes reduce duplicate reading and reduce synthesis work after the review.
- Related prompts are easier to judge together when a blocker spans multiple documents in the same subsystem.

Lane output contract:

- Return at most 5 issues, ordered by severity.
- Report implementation-blocking issues first.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain in that lane, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section.
- Do not spend space praising clear sections or summarizing intended behavior.

Recommended lane lifecycle:

1. Initial pass
   Send the lane's domain prompt set only.
2. Tightening pass
   After design edits or decisions, send `00-fresh-reread-issue-only-preamble.md` plus the same lane prompt set to restart the review from disk and check whether blockers remain.
3. Optional final confirmation
   Repeat the reread pattern only if the previous pass still found meaningful blockers or if the scope changed materially.
4. Lane reuse policy
   Keep the same lane reviewer session alive for 3-4 convergence rounds when the scope is stable. Close and respawn only when the review domain changes materially or the lane starts producing low-value/stale feedback despite the reread preamble.

Coordinator responsibilities:

- Merge duplicate findings across lanes.
- Collapse wording variants into a single canonical blocker when multiple lanes identify the same underlying issue.
- Identify cross-lane contradictions where one lane assumes a contract another lane leaves undefined or defines differently.
- Decide whether any remaining blockers are truly architecture-level or should be handled by a service-specific review using `30-per-service-deep-dive-template.md`.
- Stop when the remaining open items are either:
  - implementation-blocking decisions that need human resolution, or
  - worthwhile non-blocking follow-ups that should not delay implementation

Escalation rules:

- Run a per-service deep dive only when a blocker is localized to one service or one service boundary and the cross-domain pass is no longer sufficient.
- Bring monetization back into scope only when another lane depends on unresolved entitlement, subscription, or account-lifecycle behavior.
- Do not reopen already-settled areas for low-probability edge cases, wording nits, or optional future-scale enhancements.

Suggested final synthesis format:

1. Canonical blocking issues
   One merged list across all lanes, ordered by implementation impact
2. Cross-lane contradictions to resolve
   Only items where different architecture domains currently imply incompatible implementations
3. Suggested follow-ups
   High-value non-blocking clarifications, cleanup, examples, or refactors

Stop condition:

- End the review once the merged blocker list is stable and the remaining items are clearly non-blocking follow-ups or targeted deep-dive candidates.
