# FireMUD Redis Operations & Migrations

This document captures the canonical operator model for Coordination Redis. It complements the conceptual guarantees in [`system-architecture-redis.md`](./system-architecture-redis.md) and the Lua authoring patterns in [`system-architecture-redis-lua-patterns.md`](./system-architecture-redis-lua-patterns.md).

The invariants and contracts in [`system-architecture-redis.md`](./system-architecture-redis.md) remain authoritative. This doc focuses on named operational flows and migration posture.

## Default Operator Flows

- select the appropriate AOF profile (`dev_local`, `hobby_self_hosted`, or `production_clustered`) and watch the associated size/restart targets
- run named coordination reset and script-upgrade flows when metrics or the Lua Compatibility Registry indicate they are required

Other procedures and tuning advice here are advanced and should not be expanded into bespoke one-off sequences. New remediation paths should be expressed in terms of these named flows wherever possible.

## Documentation Map

- [`system-architecture-redis-metrics-catalog.md`](./system-architecture-redis-metrics-catalog.md)
  - Redis SLO metrics, tick/coordination metrics, cache metrics, alerting signals, and coordination size/complexity budgets
- [`system-architecture-redis-script-rollout-and-compatibility.md`](./system-architecture-redis-script-rollout-and-compatibility.md)
  - Lua compatibility modes, rollout matrix, registry expectations, and script upgrade runbooks

## Redis SLOs & Budgets

This section centralizes the normative targets for Redis behavior that other docs reference. Individual environments may tune concrete values, but changes should be treated as deliberate SLO updates rather than silent drift.

### Coordination Redis Core Targets

- **Tail-loss window**
  - production-like profiles target `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)`
  - ephemeral profiles may accept wider or unbounded tail loss but must be clearly labeled as such and must not validate tail-loss SLOs
  - a sustained breach of this envelope is a tick-SLO violation, not just a Redis anomaly: coordination state inside the breach window can no longer be trusted for automatic replay decisions
  - even under breach, domain-level idempotency and `EffectId` rules still prevent double-application; what degrades is the size of the trusted replay window and the amount of manual or tooling-driven reconciliation required
- **Restart time**
  - planned restarts for `hobby_self_hosted` and `production_clustered` nodes should typically complete within 30–60 seconds
- **Script runtime**
  - tick- and session-related Lua scripts are expected to complete within roughly 10–20 ms per invocation under normal load
- **Coordination memory share**
  - coordination prefixes should normally occupy no more than about 30–40% of `maxmemory` on Coordination Redis with `noeviction`

### Cache/Rate-Limit Redis Core Targets

- cache/eviction pressure should drive resizing or cache-design review, not become accepted steady-state behavior
- rate-limit and TTL-only cache key counts should remain within modest, documented per-tenant envelopes
- operators should track per-prefix hit/miss behavior, backing DB/service load correlation, chat-cache health, and automation-cache usage after resets or major cache changes so cache behavior remains visible without treating Cache Redis as a correctness boundary

## AOF Size and Restart Budget

Goal: keep Coordination Redis restart behavior predictable and avoid unbounded AOF growth.

Targets:

- soft AOF size limit per node of roughly 1–2 GiB for small/self-hosted deployments
- typical restart time of 30–60 seconds during planned maintenance
- steady-state daily AOF growth normally below about 250–500 MiB/day per node

Operators should wire alerts directly to these metrics and treat sustained growth or restart-time breach as a signal to resize, split load, or stop misusing Coordination Redis as a general-purpose data store.

### Runbook: AOF Too Large or Restarts Too Slow

1. Confirm via metrics or `INFO` that AOF size, restart time, or daily growth is outside the agreed budget.
2. Schedule a maintenance window.
3. Stop game services for affected scope, or globally for small/self-hosted deployments.
4. Begin the authoritative tick reset handshake for the affected scope before any Redis wipe:
   - pause ticks and new command intake
   - bump `region_epoch` in PostgreSQL so surviving executors become stale by definition
5. Reset Coordination Redis for the fenced scope by stopping Redis, deleting or recreating the AOF volume, and restarting Redis with the desired AOF configuration.
6. Complete the remaining reset handshake:
   - reconcile old-epoch ledger rows
   - converge accepted-but-unbound command records
   - reinitialize `tick:{tenantRegionTag}:meta`
   - run the post-reset smoke check
7. Resume ticks and player traffic once services are healthy.

Manual AOF surgery is not supported. Either the AOF is trusted and replayed as-is, or it is discarded and Redis restarts from a clean keyspace.

## Cache/Rate-Limit Redis Reset

Goal: provide a simple, explicit runbook for resetting Cache/Rate-Limit Redis without entangling it with Coordination Redis resets.

Cache/Rate-Limit Redis is fully reset-tolerant for the prefixes listed in [`system-architecture-redis-cache.md`](./system-architecture-redis-cache.md) and the reset policy matrix in [`system-architecture-redis-reset-and-recovery.md`](./system-architecture-redis-reset-and-recovery.md). A reset:

- drops cache and rate-limit keys such as `inventory:*`, `character-cache:*`, `world-dynamic:*`, `room:*`, `view:room-look:*`, `chat:*`, `automation:*`, and `ratelimit:*`
- does not affect Coordination Redis keys such as `tick:*`, `timer:*`, `retry:*`, `session:*`, or `tick-executor-lease:*`
- increases load on backing services temporarily but must not lose authoritative game data

### Runbook: Environment-Scoped Cache Reset

1. Identify the Cache/Rate-Limit deployment and verify it is distinct from Coordination Redis.
2. Assess impact and communicate expected temporary DB/service load and rate-limit reset effects.
3. Perform the reset:
   - single-node: stop or disconnect clients, `FLUSHDB` or `FLUSHALL` only if dedicated, restart Redis
   - clustered: use bounded prefix-scoped deletion over known cache families
4. Monitor cache hit/miss behavior, DB/service load, and rate-limit behavior after reset.
5. Fix the underlying key-shape, TTL, or cache-design issue if the reset was triggered by design drift.

## Reset Tolerance Classes

FireMUD classifies coordination-backed workloads by reset tolerance:

- **reset-tolerant**
  - tick locks, `pending` entries, timers, retry queues, and conflict metadata
- **reset-sensitive**
  - gameplay/auth session prefixes such as `session:game:*` and `session:auth:*`
  - certain automation queues or non-critical analytics that can be recomputed or re-enqueued
- **reset-forbidden**
  - future workloads that would treat Redis as a durable component of a long-lived contract

Any new feature that wants to use Coordination Redis must declare its reset tolerance class in design docs and, where necessary, use separate deployments/prefixes or stronger durable stores.

## Replica Promotion and Missed Writes

Goal: handle Redis replica promotion without violating tick and replay guarantees.

Facts:

- Coordination Redis uses asynchronous replication.
- A promoted replica may be missing recent coordination writes.
- The new primary’s keyspace is authoritative after promotion.

Behavior:

- modest promotion lag is equivalent to a small AOF tail-loss window
- replay safety is preserved by lease/lock/epoch validation and PostgreSQL-backed effect ledgers

Runbook:

1. monitor replication lag
2. if lag is within target envelope, promotion is acceptable from a replay perspective
3. if lag is in warning band, investigate and consider delaying promotion
4. if lag crosses the red line, either wait for recovery or treat promotion as a deliberate drop-recent-coordination-state event with pause, promote, and scoped rebuild/reset

## Key Shape Mistakes and Coordination Resets

Coordination keys are treated as reset-tolerant, volatile, and backed by PostgreSQL plus replay.

Before performing any coordination reset, operators should walk a short pre-reset validation checklist:

- confirm PostgreSQL is healthy
- verify tick effect ledger status for the target scope
- ensure game traffic is quiesced for the affected scope
- record operator intent and affected scope

### Scoped Tick Effect Ledger Reconcile

Every coordination reset that affects tick execution must include a tick-effect-ledger reconcile step that drives old-epoch rows to `APPLIED` or `ABANDONED` and ensures new executors do not resume `SCHEDULED` work from the old epoch.

### Runbook: Mis-Sharded Coordination Keys

1. detect the issue through CI, logs, or metrics
2. choose the smallest safe scope
3. execute the canonical reset workflow for that scope:
   - `coordination-maintenance pause ...`
   - bump `region_epoch`
   - `coordination-maintenance reset ...`
   - `coordination-maintenance reconcile-ledger ...`
   - `coordination-maintenance converge-commands ...`
   - `coordination-maintenance init-meta ...`
   - `coordination-maintenance smoke-check ...`
   - `coordination-maintenance resume ...`
4. resume traffic only according to the chosen scope’s session policy

### Key Enumeration Strategy for Scoped Resets

Cluster-safe scoped resets rely on prefix-scoped `SCAN` per master under strict operational preconditions:

1. pause the target region or scope
2. acquire a scoped reset lock
3. enumerate only known prefix families
4. scan each master with modest `COUNT` and strict time budgets
5. delete via `UNLINK` where possible
6. repeat until stable

### Unknown-Prefix Detection and Hygiene

A lightweight unknown-prefix scanner periodically scans with conservative budgets, compares observed prefixes against the canonical catalogs, emits unknown-prefix metrics, and never mutates keys. It exists to surface drift between implementation and design before it becomes a larger incident.

## Session Schema Cleanup and Large Keyspaces

Session schema cleanup is a hygiene and recovery tool, not a normal steady-state path. When cleanup is required after a schema change or persistent unsupported-schema drift:

- operate on per-tenant prefixes such as `session:game:<tenantId>:*`
- run at most one cleanup worker at a time per Coordination Redis deployment
- use bounded `SCAN` with modest `COUNT` values and strict time budgets
- delete via `UNLINK` where possible to avoid blocking the event loop
- acquire a short-lived per-tenant cleanup lock such as `session-cleanup-lock:<tenantId>`
- yield between batches and abort early when Redis latency or load is elevated
- resume from the last cursor or continuation token across bounded runs
- emit cleanup metrics such as `session.cleanup_scanned_total`, `session.cleanup_deleted_total`, and `session.cleanup_duration_seconds`, with tenant context in logs
- provide a dry-run mode before modifying keys in operator-driven cleanup tooling

Default runbooks should still prefer fixing deployments and relying on TTL over aggressive keyspace scrubbing.

## Maintenance Job Coordination

Redis maintenance flows such as session cleanup, scoped resets, normalization migrations, unknown-prefix scanning, and split-brain recovery can place non-trivial load on Coordination Redis. To keep behavior predictable:

- one control-plane actor orchestrates Redis-heavy maintenance per deployment
- only one Redis-intensive maintenance job should run at a time per deployment
- dashboards and health endpoints should expose a simple “maintenance in progress” signal while such a job is active
- fine-grained locks such as `session-cleanup-lock:<tenantId>` and `coord-reset:{tenantRegionTag}` should still be used inside the broader “one heavy job at a time” rule
- maintenance jobs must back off or abort when Redis health signals show elevated latency, `used_cpu_sys`, `used_memory`, or elevated error rates

## Dual-Leader Detection and Coordination Reset

Goal: detect Redis split-brain or conflicting primaries and recover through a coordinated reset before duplicate logical effects can escape the tick subsystem.

Signals include:

- repeated stale-lease or unsupported-epoch outcomes for the same region
- PostgreSQL epoch validation rejecting conflicting writes
- Redis/Sentinel/Cluster alerts showing simultaneous primaries
- explicit dual-leader metrics such as `redis_coordination_dual_leader_detected_total`

Runbook:

1. pause tick scheduling for affected scope
2. verify PostgreSQL and Redis have converged on a single authoritative epoch and primary
3. perform scoped coordination reset for each affected region
4. if the deployment cannot be isolated safely, perform a cluster-scoped reset
5. resume ticks only once Redis, PostgreSQL, and epoch metadata are consistent

## Normalization and Hash-Tag Migration

Goal: change how `tenantId` / `regionId` normalization and hash tags are formed without breaking shard-local assumptions.

### Runbook: Normalization Migration via Reset

1. implement the new normalization version in shared helpers
2. schedule a maintenance window
3. drive the affected scope to canonical `PAUSED`
4. deploy services using the new normalization helpers
5. bump `region_epoch`
6. start a fresh Coordination Redis deployment or logical database with an empty keyspace
7. complete the rest of the canonical reset workflow
8. resume traffic and rebuild coordination state from PostgreSQL plus fresh activity

### Runbook: In-Place Normalization Migration

This is an advanced option when dropping all coordination state is unacceptable:

1. freeze topology
2. pause or drain ticks and new commands for affected scope
3. rewrite keys from old hash tags to new ones using explicit-prefix tooling
4. validate shard-locality and smoke behavior
5. resume ticks and commands
6. perform any later cluster resharding as a separate maintenance step
