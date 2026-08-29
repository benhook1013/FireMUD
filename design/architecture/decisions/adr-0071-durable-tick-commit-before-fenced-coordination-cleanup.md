# ADR 0071: Durable Tick Commit Before Fenced Coordination Cleanup

## Status

Accepted

## Implementation Status

Durable-first commit and independently fenced cleanup are target state; complete batch/source-claim ordering, heartbeat publication, cleanup fencing, and crash-window proof are not yet claimed.

## Canonical Design

- [Tick System and Runtime Design](../system-architecture-ticks.md)
- [Tick Failure and Operations](../system-architecture-tick-failures-and-operations.md)
- [Redis Lua Patterns](../system-architecture-redis-lua-patterns.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-19`
- Primary capability: `GR-1.4` runtime recovery, replay, and reconciliation
- Affected capabilities: `SF-2.3`, `GR-1.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent validation of atomic ordering, crash windows, cleanup fencing, heartbeat publication, and collapsed-boundary alternatives
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-19`

## Context

Durable gameplay commit and removal of Redis coordination state are different boundaries. Treating Redis cleanup as evidence of domain commit can silently lose work, while retaining old pending state after a durable commit can block or interfere with the next tick. A crash can occur between every PostgreSQL, heartbeat, and Redis step, so recovery cannot depend on a cross-store atomic transaction.

The lifecycle needs one durable authority, an exact cleanup identity, and an explicit rule for heartbeat publication before cleanup without admitting the next tick early.

## Decision

Game Session owns the tick lifecycle. Redis contains disposable coordination state and does not share lifecycle authority.

For a work-bearing tick, before Redis staging, Game Session durably creates the unique tick batch, selected-work manifest, and `SCHEDULED` effect ledger rows. Selected source work remains discoverable until that durable association exists. Domain effects execute under immutable effect identities and request digests, with authoritative owner guards preventing duplicate logical mutation. A truly empty cadence boundary follows [ADR 0077](./adr-0077-durable-global-effect-fanout-and-lightweight-idle-ticks.md)'s fenced empty-tick watermark/heartbeat path instead of creating a batch.

For a work-bearing tick, `durable_committed` is one durable visibility boundary in which:

- every effect ledger row belonging to the batch is terminal as `APPLIED` or `ABANDONED`;
- the tick batch is `COMMITTED`; and
- `RegionStatus.lastCommittedTickId` advances for the same exact `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` scope under the same current `executorFence`.

Redis absence or cleanup never proves that a domain effect committed or that `durable_committed` was reached.

Heartbeat publication follows `durable_committed` and may precede coordination cleanup. Recovery provides the heartbeat through either a transactional heartbeat outbox written with the durable commit or deterministic successor synthesis from authoritative `RegionStatus`. Consumers deduplicate exact delivery by `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`, where heartbeat `tickId` is the committed `lastCommittedTickId`; a monotonic consumer projection may instead upsert the highest committed tick only within the same `(tenantId, gameInstanceId, regionId, regionEpoch)` scope.

`coordination_cleared` is a separate live boundary. Cleanup uses an atomic exact compare-and-delete of Redis pending and lock state matching the complete immutable pending-envelope set for the expected epoch, tick, and `tick_batch_id`, together with ownership tokens and the required fence relationship. A count or digest may be retained as a diagnostic consistency hint, but neither authorizes cleanup without exact set equality. A stale owner cannot delete successor coordination. A successor uses a dedicated fenced recovery-cleanup path for old-owner state; it does not impersonate the prior owner or advance commit state through cleanup.

For a work-bearing tick, tick `N+1` remains gated while tick `N` has an unresolved durable batch or matching coordination state that has not reached `coordination_cleared`. Admission checks both durable batch state and Redis coordination; the absence of one cannot override unresolved evidence in the other. A truly empty tick instead uses ADR 0077's fenced watermark commit as its durable progress and admission boundary and does not wait for a nonexistent batch.

A durable cleanup audit marker may be recorded for historical diagnostics, but it is optional and is not a correctness authority. Current clearance is established by the exact fenced cleanup and admission checks.

There is no PostgreSQL-plus-Redis transaction. Safety comes from durable-first ordering, immutable correlation identity, owner guards, executor fencing, idempotent publication and cleanup, and recovery at every intermediate boundary.

## Consequences

- Durable gameplay truth cannot be inferred from disposable Redis state.
- Heartbeats can advertise committed progress without placing Redis cleanup on the durable commit critical path.
- Duplicate or reconstructed heartbeats are harmless to deduplicating consumers.
- Exact cleanup prevents an old executor from deleting a successor's pending state or locks.
- The next tick cannot bypass either an unresolved durable batch or uncleared coordination.
- Additional outbox or deterministic synthesis, cleanup correlation fields, fenced recovery logic, and crash-window proof increase implementation and operational complexity.

## Alternatives Considered

### Publish Heartbeat Only After Coordination Cleanup

Rejected because it makes a disposable Redis cleanup delay or outage suppress an already durable progress watermark. Scheduler admission remains cleanup-gated separately, so heartbeat publication does not need to carry clearance semantics.

### Clear Coordination Before Durable Commit

Rejected because a crash could erase the only runnable projection before authoritative effects and the commit watermark are durable, creating silent loss or ambiguous recovery.

### Collapse Commit and Cleanup into One State

Rejected because PostgreSQL commit and Redis cleanup cannot be one atomic boundary. A combined acknowledgement would either treat cleanup as false proof of commit or hide the post-commit cleanup window required for safe recovery.

## Implementation and Proof Obligations

Proof for work-bearing ticks must cover durable batch creation before Redis staging; source discoverability before durable association; duplicate domain attempts with one guarded logical mutation; atomic terminal-ledger, batch-commit, and watermark visibility under the current fence; crash before and after every durable-commit step; heartbeat outbox or deterministic successor synthesis; duplicate heartbeat consumer handling; crash before heartbeat publication; heartbeat before cleanup; exact equality of the complete pending-envelope set for the expected epoch, tick, batch, ownership tokens, and fence relationship as the sole cleanup authority, with digest/count retained only as diagnostic evidence; stale-owner cleanup rejection; successor recovery cleanup; Redis pending with no batch; batch with no Redis pending; conflicting digest/count; Redis loss after commit; and rejection of tick `N+1` until both durable and coordination gates permit it. Empty-tick proof follows ADR 0077 and must cover the fenced watermark path and its admission/race boundary without a batch.

The current implementation and runtime proof are not claimed to satisfy this decision.

## Reversibility and Revisit Triggers

Heartbeat transport and optional cleanup-audit retention may change without altering the two-boundary model. Revisit heartbeat-after-commit publication only if all consumers intentionally adopt clearance rather than commit semantics. Revisit the absence of a cross-store transaction only if Redis coordination is replaced by a substrate that participates in the same durable transaction without weakening availability or recovery.
