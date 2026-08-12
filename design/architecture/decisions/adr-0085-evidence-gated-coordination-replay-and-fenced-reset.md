# ADR 0085: Evidence-Gated Coordination Replay and Fenced Reset

## Status

Accepted

## Implementation Status

Evidence-gated replay/reset is accepted target state. The automatic evidence gate, durable external maintenance fence, complete CLI, scope escalation, and focused fault-injection proof remain incomplete.

## Canonical Design

- [Redis Reset and Recovery](../system-architecture-redis-reset-and-recovery.md)
- [Redis Operations](../system-architecture-redis-operations.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `REDIS-05`
- Primary capability: `SF-2.2` Redis coordination and recovery
- Affected capabilities: `PO-4.4`, `PO-3.4`, `GR-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of replay-first recovery, always-reset recovery, reset scope, and hierarchical maintenance-lock alternatives
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `REDIS-05`

## Context

Coordination Redis may retain complete state, lose a tail, or contain mixed residue after an outage, script defect, or operator incident. Durable tick batches, epochs, effect ledgers, command outcomes, and other owner records determine whether that residue still describes one coherent timeline. Redis state under repair cannot prove its own coherence or safely own the operation that repairs it.

Replay may preserve valid work when durable evidence is coherent, but replay against missing or contradictory evidence can extend an invalid timeline. Reset fences that timeline and rebuilds coordination, but unnecessarily resetting every incident abandons recoverable work and widens player impact.

## Decision

Coordination recovery selects between `replay_first` and `reset_first` from durable evidence. Selection is automatic and auditable rather than an operator guess based only on surviving Redis keys.

`replay_first` is allowed only when durable evidence proves one coherent region epoch and batch timeline. Replay is bounded by an explicit convergence budget. It may correlate surviving coordination residue to that proven durable timeline, reconcile effects and command outcomes, and resume without an epoch reset only if convergence completes within the bound.

Recovery chooses `reset_first` immediately when required evidence is missing, contradictory, orphaned, duplicated, associated with a stalled region, or otherwise fails to prove one coherent epoch and batch timeline. A replay attempt also transitions immediately to `reset_first` when it stops making bounded progress. It does not continue optimistically through uncertainty.

Accepting loss is limited to disposable hints whose loss contract already permits disappearance. Even then, durable reconciliation runs first and proves that no authoritative effect, batch, command outcome, or other required work remains unresolved. Accept-loss is not a substitute for reconciliation and cannot classify unknown coordination state as harmless.

The initial maintenance model serializes conflicting maintenance operations across the deployment. One durable maintenance operation and fence, stored outside the Redis deployment under repair, owns recovery admission and prevents a second conflicting reset, replay, cleanup, restore, or topology-changing operation from starting concurrently. Redis-local locks may assist execution but are not the authority for this fence.

Reset uses the smallest scope whose complete affected key set, durable work set, producers, and consumers can be proven. If that completeness cannot be established, recovery escalates to the next broader scope rather than performing a narrow reset that may leave related stale state active. Every reset follows the canonical fenced reset and reconciliation sequence before resume.

## Consequences

- Coherent durable timelines may preserve recoverable work through bounded replay without an epoch reset.
- Missing or inconsistent evidence fails safely into a fenced reset rather than allowing speculative replay.
- Disposable hint loss remains acceptable only after durable reconciliation establishes that authoritative work has converged.
- A durable external maintenance fence survives loss or replacement of the Redis deployment being repaired.
- Deployment-wide serialization is simple and prevents conflicting maintenance, but unrelated maintenance may wait behind an active operation.
- Reset blast radius remains as small as can be proven complete and expands when scope evidence is uncertain.
- Recovery requires durable evidence classification, convergence budgets, maintenance-operation persistence, and scope-completeness checks.

## Alternatives Considered

### Always Reset

Rejected because immediately resetting every incident abandons work that a coherent durable epoch and batch timeline could safely recover. Reset remains the immediate path whenever the replay proof is absent or convergence is not bounded.

### Hierarchical Maintenance Locks

Deferred because region-, tenant-, and deployment-level lock compatibility introduces overlap, promotion, expiry, and partial-acquisition semantics during the same incidents in which coordination is already suspect. Initial deployment-wide serialization is retained until measured maintenance contention justifies a separately proven hierarchy.

## Implementation and Proof Obligations

Proof must cover automatic classification from durable epoch and batch evidence; coherent replay with Redis residue present or absent; missing, contradictory, orphaned, and duplicate evidence; stalled regions; replay convergence-budget expiry and non-progress; immediate transition to reset-first; effect and command reconciliation; and accept-loss restricted to disposable hints after durable convergence.

Maintenance proof must cover the durable operation and fence outside Redis under repair; duplicate and competing operator attempts; process crash and takeover; Redis loss while maintenance is active; serialization of every conflicting initial maintenance class; stale execution rejection; success and failure release; and no resume before fenced reconciliation completes.

Reset proof must establish the complete affected key, durable-work, producer, and consumer scope; smallest-scope selection when completeness is proven; escalation when it is not; epoch fencing; rebuild; reconciliation; and post-reset progress without stale-state reintroduction.

The current coordination-maintenance CLI is incomplete and does not yet prove the full replay, classification, external fencing, reset, convergence, scope-escalation, and resume contract. The automatic evidence gate, durable maintenance operation, complete CLI behavior, and focused fault-injection proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Replay budgets and durable-evidence query implementation may be calibrated without changing the proof gate. Revisit deployment-wide serialization only when measured conflicting-maintenance wait time justifies hierarchical concurrency and the replacement proves overlap, promotion, expiry, partial acquisition, takeover, and stale-operation fencing. Revisit scope selection only with a stronger completeness proof; uncertainty continues to require escalation.
