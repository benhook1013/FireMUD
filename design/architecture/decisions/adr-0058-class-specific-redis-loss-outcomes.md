# ADR 0058: Class-Specific Redis Loss Outcomes

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `REDIS-01`
- Primary capability: `SF-2.2` Redis coordination, cache, reset, and recovery boundaries
- Affected capabilities: `SF-2.1`, `SF-2.3`, `PO-3.4`, `GR-1.4`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `REDIS-01`, including authority-model validation and durable-Redis/PostgreSQL/zero-loss alternatives

## Context

PostgreSQL is the durable authority and Redis is a disposable coordination substrate. The existing `max(2000ms, 2 * tick_interval_ms)` formula is not a defensible product RPO: it is not derived from AOF fsync, replica acknowledgement, or measured failover, and it maps poorly to player effects. A future timer written inside a recent loss window can disappear much later, while a 50 ms tick turns the two-second floor into forty ticks.

Making Redis another durable gameplay authority would create competing sources of truth. Persisting every lease renewal, heartbeat, and hint synchronously in PostgreSQL would create unnecessary write amplification. The correct split is durable intent for player-significant/correctness-bearing work and disposable Redis projections for liveness and scheduling.

## Decision

PostgreSQL and owning domain stores remain the durable record. Redis coordination, queues, indexes, locks, leases, session liveness, caches, and wake-up hints are rebuildable or terminalizable projections and never the sole proof of player-significant success.

Loss guarantees are class-specific:

| Work/data class | Required outcome after Redis loss |
| --- | --- |
| Accepted player command | A durable command record remains; rebuild/requeue or explicitly terminalize as not applied. It never silently vanishes or reports false success. |
| Staged effect or retry | Durable intent and owner guards converge to applied, replay-no-op, or abandoned without logical duplication. |
| Correctness-bearing timer | Durable timer intent exists outside Redis and its scheduling projection is rebuilt. |
| Explicitly lossy timer or advisory hint | Delay/drop is allowed only under its feature-declared budget and player semantics. |
| Session, lease, cache, or wake-up key | Total Redis loss is allowed; consequence is pause, reconnect/reauthentication, cache miss, or latency—never canonical-state loss or resurrection. |
| Premium, financial, cross-tenant, or unique external effect | RPO zero after acknowledgement through the owning transaction/outbox; Redis is optional coordination only. |
| Empty-Redis cold start or reset | Uses the fenced reset/rebuild contract and is outside normal-failover loss SLOs. |

Every player-significant or correctness-bearing intent is durably recorded before acknowledgement. This does not require PostgreSQL writes for every Redis lease renewal, heartbeat, lock transition, cache mutation, or wake-up hint.

Redis retains an operational metric named `redis_unreplicated_write_window_slo_ms`. Its value is environment-specific and established through measured AOF, replication, promotion, and failover evidence rather than tick cadence. `ticks_exposed = ceil(window_ms / tick_interval_ms)` may be emitted for diagnostics only; it never authorizes silent command, effect, timer, financial, or external-state loss.

A loss-window breach enlarges the scope requiring durable-ledger reconstruction, explicit terminalization, or operator reconciliation. It does not weaken the class-specific outcomes.

## Consequences

- Redis can be reset or rebuilt without treating it as a backup-restored gameplay database.
- Correctness-bearing commands, effects, and timers require durable intent and reconstruction/terminalization paths.
- Explicitly lossy work must declare that product behavior rather than inherit a global Redis exception.
- Infrastructure SLO values require measured environment evidence and alerting.
- More durable intent records and rebuild logic add PostgreSQL/storage work, but ephemeral liveness transitions remain Redis-only.

## Alternatives Considered

### Redis as Durable Gameplay Authority

Rejected because AOF and asynchronous replication do not provide a clean zero-loss authority, and durable Redis schemas/backups would duplicate PostgreSQL truth.

### PostgreSQL for Every Coordination Transition

Rejected because persisting leases, heartbeats, locks, cache entries, and hints would add severe write amplification without improving player correctness.

### Zero-Loss Consensus Coordination

Rejected for the current scope because partitions would stall/reject gameplay and add a new quorum service while domain idempotency remains necessary.

### Tick-Derived Product RPO Formula

Rejected because tick cadence is not evidence of the infrastructure replication-loss window or the eventual player consequence of a lost coordination write.

## Implementation and Proof Obligations

Each Redis prefix/work source must declare its class, durable reconstruction source, loss outcome, reset behavior, and alert. Proof must cover lost command queue entry, staged effect replay, correctness timer rebuild, declared lossy timer drop, session/cache total loss, no resurrection, financial/external RPO-zero handoff, promotion lag, empty Redis, and loss-window breach with affected durable-row/backlog counts.

## Reversibility and Revisit Triggers

Infrastructure SLO values can change without changing the outcome matrix. Revisit the authority split only if measured PostgreSQL intent cost is unacceptable or a future consensus substrate can replace—not duplicate—the durable command/effect authority.
