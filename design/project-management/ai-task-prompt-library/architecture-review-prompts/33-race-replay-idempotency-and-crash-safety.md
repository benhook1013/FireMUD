# Architecture Review Prompt: Race Conditions, Replay Safety, Idempotency, and Crash Safety

Best used for:

- reviewing corruption risk, replay/idempotency holes, ownership/fencing weaknesses, and crash-recovery gaps across implemented systems

Read the following sources first. Follow references only when a listed doc clearly delegates a canonical contract needed to judge a finding. Then inspect the concrete code paths implicated by the docs and current branch state.

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-transactions.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/project-management/vertical-slices/02.18-task-list-service-boundary-and-audit-hardening-vertical-slice.md`
- `design/project-management/vertical-slices/02.18.7-task-list-durable-command-ingress-and-status-ledger-vertical-slice.md`
- `design/project-management/vertical-slices/02.18.8-task-list-tick-batch-and-effect-ledger-hardening-vertical-slice.md`
- `design/project-management/vertical-slices/02.18.9-task-list-region-epoch-fencing-and-runtime-ownership-vertical-slice.md`
- `design/project-management/service-status-game-session-service.md`
- `design/project-management/service-status-entity-management-service.md`
- `design/project-management/service-status-automation-scripting-service.md`

Discuss and review FireMUD's protections related to race conditions, replayability, idempotency, atomicity, and any other behavior that could result in data corruption or operator-hostile recovery work.

Context:

- Repo: `/home/ben/src/FireMUD-wsl-copy`
- Read `AGENTS.md` first and follow it as canonical instructions.
- FireMUD is intended to host many games, and the system must protect game data without relying on frequent live investigations or manual repair.
- The goal is to understand both the high-level durability model and the concrete implementation seams that prevent partial writes, duplicate effects, replay bugs, or corruption after crashes.

What to look for:

- race conditions in gameplay, runtime ownership, session, inventory, scripting, or control-plane flows
- replayability and idempotency protections on commands, timers, jobs, events, publish flows, and external callbacks
- replayability and idempotency protections on account, entitlement, or other non-gameplay durable callbacks when they affect operator recovery burden
- atomicity boundaries across Redis, SQL, gRPC, and asynchronous handoff paths
- hard-crash mid-action behavior and whether retries can safely resume or re-run work
- duplicate delivery, duplicate submission, and out-of-order execution handling
- partial-write or half-committed state risks
- optimistic concurrency, fencing, ownership, and lease semantics
- Redis atomicity, Lua correctness, and key-versioning patterns
- DB transaction assumptions that break under retries, reconnects, or concurrent operators
- recovery and auditability gaps that would force manual data repair
- places where the architecture docs promise durable behavior but the implementation or current substrate does not yet support it

What I want in the output:

1. Findings first, ordered by severity
2. Focus on real corruption risks, replay/idempotency holes, crash-safety gaps, and weak ownership or atomicity patterns
3. Include concrete file references
4. Distinguish:
   - fix now
   - fix soon
   - design follow-up
5. Call out whether each finding is mainly about:
   - race condition
   - idempotency gap
   - replay safety gap
   - crash recovery gap
   - ownership or fencing weakness
   - atomicity boundary mismatch
6. Prefer high-signal risks over generalized theory

Constraints:

- Default to static review unless a small targeted test/run materially helps confirm a concern
- Do not make code changes unless explicitly asked
- Do not spend time re-explaining already accepted slice docs unless it directly supports a finding
- Keep the review grounded in protecting operator time and hosted game data
- Record reusable lessons in `design/project-management/ai-observations.md` if you discover them

Helpful framing:

- Assume the system should survive retries, reconnects, duplicate inputs, and hard crashes without corrupting player or game state
- Be skeptical of "single writer in practice" or "probably fine" assumptions when the code or contract does not enforce them
- Prefer end-to-end reasoning across service boundaries, not just local transactional correctness
- Prefer corruption-risk and recovery-burden findings over general framework/style issues unless those issues directly create replay or crash-safety risk
