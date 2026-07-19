# ADR 0052: Redis Liveness Lease with Durable Executor Fence

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-02`
- Primary capability: `GR-1.3` Region leases, fencing, and executor coordination
- Affected capabilities: `GR-1.2`, `GR-1.4`, `SF-2.2`, `SF-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TICK-02`, including validation, PostgreSQL-only alternative, and independent tie-break passes

## Context

Redis is already required for region queues, locks, timers, pending state, and low-latency liveness, while PostgreSQL is required for durable batches, effects, commit watermarks, and recovery. A Redis lease alone cannot stop SQL work from a paused stale executor. A loosely sequenced Redis lease plus PostgreSQL fence also permits an acquire/loss race in which a claimant installs a durable fence after losing the lease.

Making PostgreSQL the sole expiring lease would remove that two-store handoff but introduce continuous deadline updates, WAL and connection pressure across independently ticking regions without allowing gameplay to continue when Redis is unavailable. FireMUD therefore retains the two-part model with an explicit install-and-revalidate protocol.

## Decision

The identities are distinct:

- `regionEpoch` is the durable semantic timeline generation and changes only for reset, topology replacement, or another intentional timeline severance.
- `executorFence` is a never-reused durable ownership generation installed once for each successful new lease ownership generation. Renewal does not change it.
- The Redis region lease token is an opaque ephemeral proof of current liveness possession.
- Entity lock tokens prove only their individual Redis locks and are not region ownership generations.

Canonical ownership protocol:

1. Acquire the region's Redis lease using a unique opaque ownership token and bounded TTL.
2. Install a new PostgreSQL `RegionStatus.executorFence` with compare-and-set against the expected `regionEpoch` and prior ownership state; record the owner and a non-secret correlation of the Redis ownership token.
3. Revalidate that the same Redis lease token is still current after fence installation. A claimant that lost the lease before revalidation remains inert even if its fence transaction committed.
4. Only then may the executor stage work.
5. Every region lease renewal and tick/lock Lua mutation compares the expected Redis token and region epoch. Renewal never advances `executorFence`.
6. Every durable batch creation, ledger transition, commit watermark, recovery write, and region control mutation compares the current PostgreSQL region epoch and executor fence.
7. Immediately before dispatching authoritative domain effects, revalidate Redis lease possession. Stable effect identity and domain guards protect the residual race after dispatch.
8. A new owner acquires Redis, installs a newer durable fence, and reconciles or abandons prior-fence unfinished batches before staging a new tick.

Redis outage, partition, lease expiry, or lease uncertainty stops region execution immediately. PostgreSQL outage may allow bounded Redis renewal to avoid unnecessary ownership churn, but no new durable batch, effect dispatch, commit, or success result begins until the durable fence is revalidated.

A reset first advances `regionEpoch` and invalidates/advances durable ownership, then clears or rebuilds Redis. Ordinary executor handoff changes only `executorFence`; it does not bump the epoch. PostgreSQL-only execution is never a fallback for Redis lease failure.

## Consequences

- Stale executors are rejected both in Redis coordination and durable control writes.
- Region lease renewal remains cheap and avoids continuous PostgreSQL expiry updates.
- Ownership acquisition is a deliberate two-store protocol with more states and recovery obligations than a single-store lease.
- Neither Redis-only nor PostgreSQL-only availability is sufficient for new tick progress.
- Durable fence comparisons add no separate database round trip where the tick-control mutation already writes PostgreSQL, but ownership installation and reconciliation add takeover latency.

## Alternatives Considered

### PostgreSQL-Only Expiring Region Lease

Rejected for the current architecture because high-frequency renewal would add database write churn while Redis remains a required tick dependency. It remains the strongest simplification alternative if measured region counts or Redis/SQL handoff incidents show the dual-store protocol is worse.

### Redis Lease Alone

Rejected because a paused executor may retain in-flight SQL authority after losing Redis ownership.

### Separate Consensus Service

Rejected because it adds another operational authority without removing the PostgreSQL durable-write fence or Redis tick dependency.

## Implementation and Proof Obligations

True region-partitioned ownership remains partial. Proof must cover acquire/loss before fence installation, loss after installation but before revalidation, paused stale executor, Redis partition, PostgreSQL outage, takeover with unfinished batches, renewal without fence advancement, reset ordering, and effect dispatch racing lease loss. No path may fall back to unfenced execution.

## Reversibility and Revisit Triggers

The fence and token fields permit migration to PostgreSQL-only leasing without changing effect identity or region epochs. Revisit if measured renewal load is negligible and two-store handoff failures dominate, Redis is removed from the tick hot path, or a dedicated consensus substrate becomes justified.
