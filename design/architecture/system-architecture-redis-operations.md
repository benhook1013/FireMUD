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

## Canonical Coordination Reset Sequence

This section is the normative source for the multi-step Coordination Redis reset/recovery workflow. Other runbooks should point here and then describe only scope choice, session policy, evidence, and scenario-specific abort or storage steps.

Canonical sequence:

1. `coordination-maintenance pause --operation reset ...`
2. `coordination-maintenance reset ...`
3. `coordination-maintenance reconcile-ledger ...`
4. `coordination-maintenance converge-commands ...`
5. `coordination-maintenance init-meta ...`
6. `coordination-maintenance rebind-sessions ...` when the chosen scope preserves gameplay sessions after clearing region-local bindings
7. `coordination-maintenance smoke-check ...`
8. `coordination-maintenance resume ...`

Rules:

- `pause` is the canonical lock-acquiring step and must drive the chosen scope to canonical `PAUSED` before storage-level wipe or prefix deletion occurs.
- `reset` is the only supported step that bumps `region_epoch` and emits the authoritative old/new epoch evidence for downstream reconciliation.
- `reconcile-ledger` and `converge-commands` are required before traffic resumes; replay-first workflows may use those verbs without a preceding `reset`, but reset workflows must not skip them.
- `init-meta` re-establishes `tick:{tenantRegionTag}:meta` from the durable baseline after the reset step.
- `rebind-sessions` is conditional. Region-scoped resets preserve gameplay sessions by default, tenant-scoped resets do so only when `--preserve-sessions` is recorded, and cluster-scoped resets invalidate gameplay sessions by default.
- `smoke-check` is the resume gate proving the new epoch can acquire leases, stage work, converge, and clean up correctly.
- `resume` is the canonical success-path release step. If the workflow aborts before `resume`, operators must use `coordination-maintenance release-lock ...` rather than inventing an alternate unlock sequence.

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
3. Keep the control-plane path and maintenance tooling alive long enough to execute the canonical reset handshake; do not stop the very components required to pause, fence, audit, and verify the workflow.
4. Start the [Canonical Coordination Reset Sequence](#canonical-coordination-reset-sequence) for the affected scope and keep the same maintenance lock token through the full workflow.
5. Perform the storage-level reset between the canonical `reset` and `reconcile-ledger` steps by stopping Redis, deleting or recreating the AOF volume, and restarting Redis with the desired AOF configuration.
6. Finish the canonical sequence, including `smoke-check`, before resuming ticks and player traffic.
7. If the workflow aborts before `resume`, release the lock explicitly with `coordination-maintenance release-lock ...`.

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

1. Monitor `redis_replication_lag_ms{redis_role="coordination",nodeId,upstreamNodeId}` as the canonical promotion-lag metric, with `redis_replication_offset_lag_bytes{...}` as supporting evidence.
2. Compare the worst candidate-promotion lag against the same tail-loss SLO used elsewhere:
   - acceptable: `redis_replication_lag_ms <= 0.5 * tail_loss_budget_ms`
   - warning: `0.5 * tail_loss_budget_ms < redis_replication_lag_ms < tail_loss_budget_ms`
   - red: `redis_replication_lag_ms >= tail_loss_budget_ms`
3. If lag is in the acceptable band, promotion is acceptable from a replay perspective.
4. If lag is in the warning band, investigate immediately and delay promotion unless the failover risk of waiting is worse than accepting a wider tail-loss window.
5. If lag crosses the red line, either wait for recovery or treat promotion as a deliberate drop-recent-coordination-state event with `pause -> promote -> scoped reset/rebuild` under the normal maintenance-lock and epoch-fencing workflow.

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
3. execute the [Canonical Coordination Reset Sequence](#canonical-coordination-reset-sequence) for that scope
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

- operate on tenant-scoped gameplay/bootstrap prefixes such as `session:game:{tenantGameplayTag}:*` and the current `sessionctx:<tenantId>:*` family
- run at most one cleanup worker at a time per Coordination Redis deployment
- use bounded `SCAN` with modest `COUNT` values and strict time budgets
- delete via `UNLINK` where possible to avoid blocking the event loop
- acquire a short-lived per-tenant cleanup lock such as `session-cleanup-lock:<tenantId>`
- yield between batches and abort early when Redis latency or load is elevated
- resume from the last cursor or continuation token across bounded runs
- emit cleanup metrics such as `session.cleanup_scanned_total`, `session.cleanup_deleted_total`, and `session.cleanup_duration_seconds`, with tenant context in logs
- provide a dry-run mode before modifying keys in operator-driven cleanup tooling

Canonical cleanup workflow:

1. `coordination-maintenance pause --operation cleanup --scope tenant --tenant <tenantId>`
2. `coordination-maintenance session-cleanup --scope tenant --tenant <tenantId> --maintenance-lock-token <token> [--dry-run] [--resume-token <token>]`
3. `coordination-maintenance resume --scope tenant --tenant <tenantId> --maintenance-lock-token <token>` on success, or `coordination-maintenance release-lock --maintenance-lock-token <token>` on failure or operator abort

The cleanup command is the only supported mutating path for session-schema cleanup. Ad hoc cleanup Jobs must call this verb rather than encoding their own lock, continuation, or abort behavior.

Default runbooks should still prefer fixing deployments and relying on TTL over aggressive keyspace scrubbing.

## Maintenance Job Coordination

Redis maintenance flows such as session cleanup, scoped resets, normalization migrations, unknown-prefix scanning, and split-brain recovery can place non-trivial load on Coordination Redis. Other operations such as coordinated backups, restore coordination recovery, and topology-changing scaling use the same pause/status/epoch control plane and can invalidate each other if they overlap. To keep behavior predictable:

- one control-plane actor orchestrates heavy maintenance per deployment
- one deployment-wide maintenance lock serializes incompatible backup, restore, reset, cleanup, migration, and topology-changing scale operations
- coordinated backup jobs must acquire this lock before pausing ticks and must fail closed if an incompatible maintenance operation is already active
- restore coordination recovery, scoped resets, normalization migrations, split-brain recovery, session cleanup, and topology-changing scale changes must acquire this lock before they pause or mutate coordination state
- read-only low-impact scanners may run only when they are declared compatible with the active operation and still back off on Redis health degradation
- dashboards and health endpoints should expose a simple “maintenance in progress” signal while such a job is active
- fine-grained locks such as `session-cleanup-lock:<tenantId>` and `coord-reset:{tenantRegionTag}` should still be used inside the broader deployment-wide rule, but they do not replace it
- maintenance jobs must back off or abort when Redis health signals show elevated latency, `used_cpu_sys`, `used_memory`, or elevated error rates

Canonical maintenance-lock behavior:

- lock identity: one active record per Coordination Redis deployment / gameplay environment boundary
- minimum fields: `operation`, `scope_type`, `tenantId`, `regionId`, `actor`, `startedAt`, `expiresAt`, `compatibilityClass`, and an evidence or incident reference
- acquisition is fail-closed for incompatible operations; operators may only break the lock with an explicit stale-lock or break-glass evidence record
- acquisition owner: `coordination-maintenance pause --operation ...` is the canonical lock-acquiring command for multi-step backup, restore, reset, cleanup, migration, and topology-change workflows
- refresh owner: every subsequent mutating CLI verb in that workflow refreshes the same lock using `maintenanceLockToken`; lock refresh is not a second independent acquisition
- success release owner: `coordination-maintenance resume ...` is the canonical success-path release step once the scope has safely returned to `RUNNING`
- failure release owner: `coordination-maintenance release-lock ...` is the canonical failure or operator-abort release step when the workflow stops before resume
- backup CronJobs treat lock-acquisition failure as a skipped/failed backup attempt and emit the normal backup freshness metrics instead of running without the lock
- restore recovery and reset tooling must refresh or complete the lock before TTL expiry so another actor cannot start a conflicting pause/reset sequence mid-flow

Canonical maintenance-active signal:

- metric: `coordination_maintenance_active{scope_type,tenantId,regionId,operation}`
- health/readiness projection: environments may expose an equivalent health field, but the metric name above is the canonical observability contract used by dashboards and Logging & Admin.

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
3. drive the affected scope to canonical `PAUSED` through `coordination-maintenance pause --operation migration ...`, then continue using the [Canonical Coordination Reset Sequence](#canonical-coordination-reset-sequence)
4. deploy services using the new normalization helpers before the canonical `reset` step
5. start a fresh Coordination Redis deployment or logical database with an empty keyspace after the canonical `reset` step
6. finish the remaining canonical sequence with the same maintenance lock token and rebuild coordination state from PostgreSQL plus fresh activity

### Runbook: In-Place Normalization Migration

In-place normalization migration is not a first-implementation operator path. Use the reset-based migration above until a future slice ships dedicated rewrite tooling with scope inventory, follow-up handling, audit output, and post-migration verification.

This remains a future advanced option when dropping all coordination state is unacceptable:

1. freeze topology
2. pause or drain ticks and new commands for affected scope
3. rewrite keys from old hash tags to new ones using explicit-prefix tooling
4. validate shard-locality and smoke behavior
5. resume ticks and commands
6. perform any later cluster resharding as a separate maintenance step
