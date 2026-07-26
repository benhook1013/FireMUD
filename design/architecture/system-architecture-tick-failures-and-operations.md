# FireMUD Tick System: Failures & Operations

This document focuses on **failure modes, recovery flows, and operational guidance** for the tick system.

It is aimed at both developers and operators who need to understand what happens when executors crash, Redis has issues, or ticks must be replayed.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

## Implementation Notes

This document describes the full target-state recovery and operator model. The current live substrate is narrower and should be read alongside the `02.18.7` through `02.18.9` slice docs:

- the live durable ownership row is currently `{tenantId, gameInstanceId}`-scoped;
- the live owner/status API is `GetRuntimeOwnershipStatus`, not yet a full region-scoped status surface;
- the live fence token is opaque and compare-and-match based;
- the live `tick_batch` / `tick_effect` ledger is real and now carries the current gameplay-command selected-work manifest on `tick_batch`, including current-boundary `enqueueSeq`, source metadata, and digest-checked replay reuse for surviving staged batches, but timer/retry/remote-follow-up source-claim manifests, region-scoped replay controller breadth, and cross-region result-return semantics described below remain target-state follow-through.

Naming convention: API, workflow, and EffectId prose uses `regionEpoch`. Snake-case forms such as `region_epoch`, `last_region_epoch`, and `target_region_epoch` are reserved for explicitly identified SQL/storage fields, Redis payloads/keys, or schema examples.

## What This Covers

- Crash recovery and replay behavior.
- Idempotency rules tied to the region-scoped tick timeline `(regionEpoch, tickId)`.
- Handling stuck or partial tick entries.
- Design checklist for new tick-driven commands.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` contain the main failure-handling and operational rules:

- **Crash Recovery and Replay** – how executors recover from failure and resume processing safely.
- **Domain Idempotency Rules (Region Epoch + TickId in PostgreSQL)** – how `(regionEpoch, tickId)` enforce idempotent domain mutations.
- **Design Checklist for New Tick-Driven Commands** – review checklist for new commands to ensure they follow tick invariants.
- **Tick Execution and Redis Integration** – failure scenarios and invariants around the canonical commit pattern.
- **Cross-Region Command Execution and Result Relay** – constraints for cross-region retries and replay.

When implementing new failure-handling flows or adding operational procedures, ensure the detailed behavior is captured in `system-architecture-ticks.md` and reflected in the appropriate runbooks (for example, Redis incident runbooks).

## Crash Recovery and Replay

Tick recovery is driven by durable PostgreSQL tick state plus domain-level idempotency rules, with Redis acting only as a volatile coordination layer:

- On executor crash or failover, a new worker acquires the region lease, re-establishes the authoritative recovery baseline from the durable tick-batch, tick effect ledger, follow-up tables, and the active status/progress surface: current live uses `GetRuntimeOwnershipStatus` plus `ObserveRuntimeTickProgress` for the owner, opaque `executorFence`, and committed `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` progress; target-state uses `RegionStatus`/`GetRegionTickStatus`. It then inspects any surviving `tick:{tenantRegionTag}:pending`, `retry:{tenantRegionTag}`, and timer keys only as optional coordination hints while replay converges from durable state.
- Redis is treated as a volatile coordination layer with **at-least-once** semantics; network retries, executor failover, and AOF replay can all cause the same logical effect to be attempted more than once.
- Domain services rely on `(regionEpoch, tickId)` and effect guards to ensure that replays do not double-apply logical effects even when Redis state is partially lost.

### Durable Commit vs Coordination-Cleared Boundaries

Failure handling assumes the same two-boundary model defined in the main tick design:

- `durable_committed`:
  - Ledger rows for `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId)` are terminal (`APPLIED` or `ABANDONED`), and
  - The current-live `GetRuntimeOwnershipStatus` reports the same opaque `executorFence` recorded by the batch, the live `regionEpoch` matches, and the live commit boundary has advanced under that same fenced write. `RegionStatus` is a target-state durable projection in examples where the live ownership/status surface is not available.
- `coordination_cleared`:
  - Redis `pending`/lock coordination for that tick is no longer in flight.

Crash-window behavior:

- Crash before `durable_committed`:
  - Tick work remains replayable; recovery replays remaining `SCHEDULED` effects using idempotent handlers and ledger rules.
- Crash after `durable_committed` but before `coordination_cleared`:
  - Recovery must finish coordination cleanup before allowing the next tick to stage new work.
  - The durable watermark does not regress, and heartbeat chronology does not rewind.

### Tick Effect Ledger and Replay Guarantees

To make replays observable and bounded, Game Session maintains a **tick effect ledger** in PostgreSQL. Conceptually, the ledger captures the same coordination timeline described in the Redis and tick architecture docs:

- Every tick effect that has been durably claimed or staged for execution (for example, rows associated with a tick batch, replay-eligible retry work, or durable follow-up records) is mirrored into a Game Session–owned ledger table (for example `tick_effects`) with columns such as:
  - `tenant_id`, `game_instance_id`, `playable_state_scope`, `region_id`, `region_epoch`, `tick_id`
  - `tick_batch_id`
  - `effect_key` (stable, human-readable descriptor passed through from staging)
  - `target_aggregate_type`, `target_aggregate_id` (required target aggregate identity)
  - `automation_dispatch_id` (required for script-generated effects; nullable for non-scripting commands)
  - `command_id`
  - `status` ∈ {`SCHEDULED`, `APPLIED`, `ABANDONED`}
  - `reason` / `outcome`
  - `created_at`, `updated_at`
- Target-state requires one durable tick-batch record per `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`, owned by Game Session, and stores at minimum:
  - `tick_batch_id`
  - `lease_token` (or equivalent fencing token captured at batch allocation time)
  - `expected_effect_count`
  - `status`
  - selected-work manifest entries for the batch
  - optional `pending_digest` or equivalent integrity field
  - `created_at`, `updated_at`
- The selected-work manifest is required for deterministic replay and source cleanup. At minimum it records, per selected source item:
  - `source_kind`
  - source item identity
  - `entity_id`
  - the canonical ordering tuple used when the batch was formed
  - source-claim/removal state
- Per-source minimum fields are:
  - `command`: `command_id`, queue family, enqueue sequence
  - `timer`: timer member ID, `due_ms`, normalized due-tick value used for ordering
  - `retry`: retry member/effect identity, `retry_count`, `next_eligible_tick_id`
  - `remote_followup`: durable follow-up row ID, `target_region_epoch`, `due_tick_id`
- Recovery tooling may store and inspect richer per-source payloads, but deterministic replay and cleanup must remain possible from the documented fields above.
- Any service-level schema or storage doc that introduces the concrete `tick_batch` / manifest tables must mirror these minimum fields explicitly rather than redefining a narrower contract locally.
- The current live tick-batch table enforces uniqueness only on its durable `tick_batch_id` key (`tick_batch_tick_batch_id_key` / `idx_tick_batch_tick_batch_id`); it does not yet enforce the coordinate tuple.
- Completing the target-state invariant requires a migration that audits and reconciles duplicate coordinate tuples, then adds and proves a unique PostgreSQL constraint/index on `(tenant_id, game_instance_id, region_id, region_epoch, tick_id)`. Until then, the recorded `tick_batch_id`, lease/fence checks, and application duplicate detection are the current safeguards but do not provide database-level tuple uniqueness.
- Batch allocation is lease-fenced using the recorded `lease_token` (or equivalent fencing token).
- Recovery that observes multiple durable rows for the same coordinates treats the region as inconsistent, pauses it, and requires reconcile tooling before normal ticks resume.
- Current live boundary note: gameplay commands do not yet use the fuller target-state `BOUND_TO_BATCH` vocabulary. Instead, the command ledger exposes `enqueueSeq`, `STAGED`, `DRAINED`, and later terminal/requeue outcomes, while the sealed batch manifest digest is used to ensure replay reuses only matching staged batches instead of silently mutating an older batch contract.
- For any `(tenant_id, game_instance_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id)` there must eventually be **exactly one terminal state**:
  - `status = APPLIED` – effect successfully committed to domain state.
  - `status = ABANDONED` – effect intentionally skipped or judged unrecoverable.
- A duplicate handler attempt may return a replay/no-op outcome such as `replay_ok`, but that outcome is recorded in service metrics/audit and never as a third ledger status; when the effect is already reflected in durable state, its ledger row is `APPLIED`.
- Rows must not remain in `SCHEDULED` beyond the emitted replay-convergence budget; stuck rows are treated as operational smells and surfaced via metrics and alerts.

#### Replay Convergence Budget (Normative)

To keep replay-controller alerting and runbooks deterministic, the replay path uses an explicit convergence budget:

- `tick_effects_replay_convergence_budget_seconds{scope}` is the canonical emitted budget for each active region-sized gameplay scope.
- Default formula:
  - `replay_convergence_budget_seconds = max(60, ceil(20 * tick_interval_ms / 1000))`
  - For common tick cadences, this yields a practical minimum budget of `60s`.
- Prometheus recording rules should also expose:
  - `tick_effects_pending_oldest_age_seconds{scope}` = `time() - tick_effects_pending_oldest_scheduled_timestamp_seconds`
  - `tick_effects_replay_slo_breached{scope}` when oldest pending age exceeds the emitted convergence budget
  - `tick_effects_replay_starved{scope}` when `tick_effects_pending_total > 0` but replay batches do not advance for longer than the emitted convergence budget
- Alerting guidance:
  - Warning/P1 when `tick_effects_replay_slo_breached` is sustained beyond one budget window for an otherwise running region.
  - Escalate the region to `DEGRADED` or `STALLED` and require scoped remediation when the oldest pending age exceeds multiple budget windows or when `tick_effects_replay_starved` remains true.
- Environment overlays may raise the budget for extreme workloads, but they must emit the canonical budget metric rather than hiding the threshold inside PromQL.

Canonical alert names for shared rulesets:

- `TickEffectsReplaySloBreached`
  - Fires when `tick_effects_replay_slo_breached` is sustained and the region has exceeded its emitted convergence budget.
- `TickEffectsReplayStarved`
  - Fires when `tick_effects_replay_starved` is sustained, indicating the replay controller is not servicing a region with pending work.

Environment overlays may tune durations or routing, but they should preserve these alert names and the shared labels (`service`, `severity`, `owner`, `runbook`) so Logging & Admin and incident runbooks remain stable.

#### Ownership Summary

Tick-related durable structures are intentionally split between a central ledger and per-service guards:

- **Game Session Service**
  - Owns the global tick effect ledger tables (for example `tick_effects`) and any cross-region follow-up tables that encode scheduled work between regions.
  - Defines the canonical projection of `EffectId` into ledger schema and is responsible for convergence of `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` to `APPLIED` or `ABANDONED`.
- **Domain services (Entity Management, World Management, etc.)**
  - Own their own idempotency guard tables (for example `entity_tick_state`, `tick_effect_guard`) in their respective schemas.
  - Use those guards to implement per-aggregate `last_tick_id` and operation-level idempotency patterns, but do not introduce additional “mini-ledgers” for tick effects.

New designs must not create ad-hoc ledger tables for tick effects in other services; they should either extend the Game Session–owned ledger/follow-up schema or add domain-local guard tables that project the existing `EffectId`.

Replay of a tick is driven from ledger state:

- When reprocessing a tick, the executor loads ledger rows for that `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId>` with `status = SCHEDULED` and:
  - Re-queues/re-runs effects whose domain targets do not yet reflect `APPLIED`, then marks those rows `APPLIED`.
  - Marks effects `ABANDONED` with a precise reason when replay determines they are no longer valid (expired session, entity gone, descheduled tick, and so on).
  - Marks effects `APPLIED` and skips domain calls when it determines the effect has already been applied idempotently.
- Every Lua script that stages effects is required to include the `effect_key` used in the ledger so Redis `pending` entries can always be correlated with ledger rows; staging scripts that cannot be tied back to a ledger identity are rejected.
- Recovery rules for Redis/SQL mismatch are explicit:
  - durable tick-batch + `SCHEDULED` ledger rows exist, Redis `pending` missing:
    - replay proceeds from PostgreSQL using the durable batch manifest and `SCHEDULED` ledger rows; it does not rely on re-materializing the old tick through the normal hot-path staging scripts.
    - First implementation treats normal tick Lua scripts as hot-path guards only. Recovery drives effects directly to `APPLIED`/`ABANDONED`, reconciles any surviving source claims/entries against the manifest, and then clears stale coordination residue.
  - Redis `pending` exists without a durable tick-batch:
    - treat the Redis entry as orphaned coordination state, clear it, alert, and do not commit work from it.
  - durable tick-batch and Redis `pending` disagree on expected effect count or digest:
    - mark the batch inconsistent, pause the region, and require reconcile tooling before resuming normal ticks.

### Command Record Convergence Under Replay and Reset

Command recovery must converge just like effect recovery:

- Any accepted command that is still `RECEIVED` or `ENQUEUED` when a reset or tail-loss reconcile occurs and that is not durably tied to a surviving `tick_batch_id` must be marked `TERMINAL` with explicit status fields:
  - `executionOutcome = LOST_BEFORE_STAGING`
  - `gameplayResult` set by the command type's documented terminal mapping (for the shared default, `NOT_APPLIED` unless a more specific command contract says otherwise)
- Commands that are `BOUND_TO_BATCH` follow the batch/effect replay path and converge based on the batch's terminal command status mapping.
  - For commands, this means they converge to terminal command status fields (`executionOutcome`, `gameplayResult`) based on the documented command mapping for those batch-bound effects; do not collapse command status into effect-ledger status names alone.
- Whenever recovery, reset, or purge terminalizes a command with a reason, it must persist the structured `failureCode` and `failureMessage` pair together on the authoritative command status record. A nonblank operator purge reason is retained as `failureMessage` rather than being left only in logs or audit metadata.
- Reconciliation of command records is part of the same operational scope as ledger replay/reset tooling; operators must not need a separate ad-hoc command repair path just to clear dedupe rows stranded before staging.
- This keeps command deduplication safe: the same `commandId` can be retried by clients for status lookup without leaving an unexecutable, permanently non-terminal record behind.
- For the canonical shared command terminal mapping table and worked examples, see `system-architecture-tick-execution-flows.md` under `Canonical Command Terminal Mapping Table`.

Minimum command-status surface for operators and clients:

- Status is keyed by `(tenantId, gameInstanceId, commandId)`.
- It exposes at least:
  - `ackLevel`
  - `ingressStatus`
  - `executionOutcome`
  - `gameplayResult`
  - `failureCode` and `failureMessage` when terminalization has a reason
  - `tickBatchId`
  - bound tick coordinates when present (`regionId`, `regionEpoch`, `tickId`)
- Canonical control-plane naming for first implementation is:
  - `GetGameplayCommandStatus` for authoritative lookup
  - optional `StreamCommandOutcomes` for advisory event delivery
- `executionOutcome` uses the shared terminal vocabulary:
  - `APPLIED`
  - `ABANDONED`
  - `LOST_BEFORE_STAGING`
- `gameplayResult` uses the shared player-facing vocabulary:
  - `SUCCESS`
  - `PARTIAL`
  - `FAILED`
  - `TIMEOUT`
  - `NOT_APPLIED`
- `LOST_BEFORE_STAGING` is a first-class terminal execution outcome, not an internal-only repair code.
- Durable storage rule:
  - The authoritative status surface must persist both `executionOutcome` and `gameplayResult`, either on the command-ingress row itself or in a durable outcome projection keyed by `(tenantId, gameInstanceId, commandId)`.
  - When terminalization has a reason, it must persist `failureCode` and `failureMessage` together on that same authoritative surface; neither field may be used as a substitute for the other.
  - Recovery and reset tooling update that durable status surface directly; they do not rely on Redis queues or in-memory command trackers to answer `GetGameplayCommandStatus`.
  - Schema docs may use storage-oriented names such as `execution_outcome` / `gameplay_result`, but the logical command-status contract remains the camel-case field set above.

### EffectId, Ledger Rows, and Guard Keys

The canonical `EffectId` described in `system-architecture-transactions.md` (a stable identity derived from `tenantId`, `gameInstanceId`, resolved `playableStateScope`, `regionId`, `regionEpoch`, `tickId`, `effectKey`, `targetAggregateType`, and `targetAggregateId`) is the logical key that ties together:

- Tick coordination in Redis.
- Tick effect ledger rows in PostgreSQL.
- Per-aggregate and operation-level idempotency guards in domain schemas.

In schema terms:

- The tick effect ledger’s primary or unique key is a projection of `EffectId`:
  - At minimum `(tenant_id, game_instance_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id)`; the gameplay-state namespace and target aggregate fields are part of the logical identity, whether the physical schema also encodes target identity in `effect_key` or stores it separately.
  - Additional columns such as `command_id` or `automation_dispatch_id` may exist for queryability and scripting correlation, but they do not remove target aggregate identity from `EffectId`.
- Guard tables such as `tick_effect_guard` implement the same identity for multi-effect operations:
  - Their primary key `(tenant_id, game_instance_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id)` is the guard-side projection of `EffectId` for the logical effect being protected.
  - Handlers must not invent alternative idempotency keys for tick-driven effects; they should derive their guard keys directly from the same components used to compute `EffectId`.

The ledger makes replay visible operationally via metrics such as:

- `tick_effects_pending_total{scope}`
- `tick_effects_applied_total{scope}`
- `tick_effects_abandoned_total{scope,reason}`
- `tick_effects_replayed_total{scope}` (or, where available, `tick_effect_outcome_total{outcome="replay_ok"}` for service-level detail)
- `tick_effects_pending_oldest_scheduled_timestamp_seconds{scope}` – helper metric tracking the oldest `created_at` among SCHEDULED rows for each approved bounded gameplay scope.
- `tick_effects_pending_oldest_age_seconds{scope}` – recording rule for the current age of the oldest `SCHEDULED` row.
- `tick_effects_replay_convergence_budget_seconds{scope}` – emitted budget for how long replay may take before the scope is considered unhealthy.
- `tick_effects_replay_slo_breached{scope}` – recording rule indicating oldest pending age has exceeded the emitted budget.
- `tick_effects_replay_starved{scope}` – recording rule indicating replay batches are not advancing despite pending work.
- `tick_durable_commit_total{scope}` – count of ticks that reached the durable commit boundary.
- `tick_coordination_cleared_total{scope}` – count of ticks whose Redis coordination state reached the in-flight clearance boundary.
- `tick_cleanup_lag_ms{scope}` – lag from durable commit to coordination-cleared for each tick.

Alerts fire when:

- Pending (`SCHEDULED`) counts remain above thresholds for longer than a tick window, or
- The abandoned ratio grows unexpectedly for a region or effect type, or
- The oldest SCHEDULED effect in a region exceeds the emitted replay budget as indicated by `tick_effects_pending_oldest_age_seconds` and `tick_effects_replay_slo_breached`.

These metrics complement the Redis- and lock-level health metrics described in the Redis architecture and operations docs.

Operators diagnosing stalled regions, replay storms, or ledger backlogs should use these metrics in combination with the Tick Health & Ledger dashboard exported under `design/observability/grafana/tick-health-ledger.json` and the incident flows described in `system-architecture-tick-incident-runbook.md`.

Replay fairness is part of the operational contract, not just an implementation detail:

- Dashboards and alerts should show regions where `tick_effects_pending_total > 0` but `tick_effects_replay_batches_total` is not increasing over the same interval.
- Sustained `tick_effects_replay_scan_lag_ms` growth for a subset of regions should be treated as replay-controller starvation, even if a hot region is still making progress.

### Ledger Replay Controller

Responsibility for driving ledger rows to a terminal outcome lies with the Game Session Service:

- A background “ledger replay controller” in Game Session:
  - Periodically scans for `SCHEDULED` rows that have exceeded the emitted replay-convergence budget for a given `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId)`.
  - Replays eligible effects using the same idempotent handlers the tick pipeline uses, marking rows `APPLIED` when domain state confirms success.
  - Marks rows `ABANDONED` with a precise reason when replay is no longer safe or meaningful (for example, entities removed, sessions expired, or region/tenant/cluster resets that bumped `regionEpoch`).
  - Enforces bounded fairness across active scopes so replay does not starve smaller tenants/regions behind one hot backlog:
    - Scans and replays in bounded batches per `<tenantId, gameInstanceId, regionId>`.
    - Uses round-robin (or weighted-fair) scheduling across regions rather than draining one region completely before touching others.
    - Emits `tick_effects_replay_scan_lag_ms{scope}` and `tick_effects_replay_batches_total{scope}` so starvation is visible without reintroducing raw tenant/game-instance/region labels to Prometheus.
- The controller also runs on service startup for each region to converge any lingering `SCHEDULED` rows before normal tick processing resumes.
- For incident handling, the same replay logic is exposed via coordination tooling (for example, an admin CLI or maintenance API) so operators can explicitly drive convergence for a selected `(tenantId, gameInstanceId, regionId, regionEpoch)` when guided by runbooks in the Redis operations docs.
- Convergence SLO contract (required):
  - `SCHEDULED` rows should converge to terminal `APPLIED`/`ABANDONED` within the emitted `tick_effects_replay_convergence_budget_seconds` window.
  - If oldest `SCHEDULED` age exceeds the emitted budget for a region, the region is escalated to `DEGRADED`/`STALLED` and incident runbooks require scoped remediation.

#### Inconclusive Old-Epoch Effect Reconciliation

When a reset or epoch fence leaves an old-epoch effect in `SCHEDULED` and normal domain inspection cannot yet prove whether the effect was applied, recovery must use a bounded, out-of-band attestation under the original `EffectId`. The attestation:

- retains the original `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` and the maintenance/recovery fence that authorized the reset;
- reads the authoritative domain guard/state and any existing `APPLIED` reflection without invoking current-epoch staging or replay;
- records an auditable attestation outcome and retries only within the emitted replay-convergence budget; and
- terminalizes the original ledger row as `APPLIED` when durable state proves the effect occurred, or as `ABANDONED` with an explicit reconciliation reason such as `OLD_EPOCH_RECONCILIATION_INCONCLUSIVE` when the bounded attestation cannot establish safe application.

Current-epoch executors must never re-drive the old `EffectId`. Any feature that needs to carry work across the epoch boundary requires separately designed maintenance or saga/outbox tooling that creates a new current-epoch identity.

Common scenarios and invariants:

- **Primary crash, AOF fully up to date**
  - Redis: `pending`, locks, timers, and retry metadata preserved for recent ticks.
  - PostgreSQL: all committed effects durably stored.
  - Invariants: no double-apply; at-most-one executor per region after lease re-acquisition.
  - Action: new executor replays any surviving `pending` entries; ticks complete or are retried automatically.
- **Crash during AOF window (tail loss)**
  - Redis: some recent `pending`/lock/queue keys for the last ticks may be missing.
  - PostgreSQL: effects applied before the crash remain; very recent, in-flight effects may or may not have been applied.
  - Invariants: no double-apply; some ticks may be lost or need manual reconstruction.
  - Action:
    - Metrics and dashboards surface gaps or stuck regions.
    - Operators treat serious tail-loss as a trigger to run the **ledger replay controller** (and, where appropriate, the scoped reset/reconcile flows) for the affected `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch)` combinations.
    - The controller drives any lingering `SCHEDULED` effects in the tail-loss window to terminal `APPLIED` or `ABANDONED` outcomes based on idempotent domain state. It does **not** attempt to re-stage older ticks through the normal tick-staging Lua scripts; any need to move effects across epochs or tick ranges is handled only by dedicated maintenance tooling that understands ledger state.
    - The same reconcile scope also converges accepted-but-unbound command records to terminal command status fields (for example `executionOutcome = LOST_BEFORE_STAGING` with default `gameplayResult = NOT_APPLIED`) so ingress dedupe state does not strand commands indefinitely after coordination loss.
- **GC pause > `lock_ttl_ms` but < `lease_ttl_ms`**
  - Redis: locks may expire and be reacquired; `pending` remains; lease still held by original executor.
  - PostgreSQL: any effects applied before the pause remain consistent; replays of the same `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` are treated as no-ops by idempotent handlers.
  - Invariants: no double-apply; lease ownership unchanged; at-most-one executor per region.
  - Action: late work that fails token checks is retried; regions with persistent over-TTL behavior may be marked degraded until configuration or workload is adjusted.
- **GC pause > `lease_ttl_ms` (lease lost)**
  - Redis: lease may move to a new executor; `pending` and queues are preserved subject to the AOF window.
  - PostgreSQL: effects applied by the old executor before losing the lease remain consistent; the new executor replays the same effects with idempotent handlers.
  - Invariants: no double-apply; at-most-one active executor per region enforced by lease tokens.
  - Action: work the old executor attempts to perform after lease loss is discarded; the new executor drives recovery; the region may be degraded until behavior stabilizes.
- **Redis coordination cluster outage**
  - Redis: coordination keys temporarily unavailable; a tail window of recent `pending`/lock/queue state may be lost depending on failure and AOF configuration.
  - PostgreSQL: remains authoritative; effects committed before the outage are not rolled back.
  - Invariants: no double-apply; some ticks may be lost or skipped, but already-applied effects are not undone.
  - Action: Game Session halts ticks/commands for affected regions, following the Redis outage policy; after Redis recovers, the recovery subsystem and operators decide whether to skip, retry, or repair missing ticks.

These scenarios assume the Redis AOF is configured as described in `system-architecture-redis.md`. If AOF or replication settings differ, the same idempotency rules still apply, but the tail window for potential tick loss may change.

## Stalled Regions and Downstream Behavior

Stalled regions are those that still hold `tick-executor-lease:{tenantRegionTag}` but have not made forward progress (for example, no successful commits or too many consecutive failures) for several multiples of `tick_interval_ms`, as defined in the concepts doc. Once a region is classified as stalled:

- The scheduler stops scheduling new ticks for that `<tenantId, gameInstanceId, regionId>` until progress recovers or operators intervene.
- New gameplay commands targeting that region are rejected with a clear “region unavailable”–style error instead of being accepted and queued behind a non-advancing executor.
- The existing executor continues renewing the lease for a short grace period so no other instance takes over and attempts the same failing work against unhealthy dependencies.
- If a large number of regions on the same instance become stalled, that instance’s readiness/liveness may be marked unhealthy so orchestration can restart it once downstream services (for example PostgreSQL, other gRPC services) are healthy again.

Lease expiry and failover remain about coordination safety (avoiding split-brain on Redis state). Stalled-region handling layers a progress watchdog on top: leases describe who owns a region’s coordination keys, while stalled/healthy state describes whether that owner is actually advancing game state.

## Stuck Pending Entries and Recovery

In rare cases, a `tick:{tenantRegionTag}:pending` entry may remain present even though repeated replays cannot complete successfully (for example, due to a persistent domain bug). A small recovery subsystem handles these **stuck ticks**:

- A background watcher scans metrics and/or a compact Redis/PostgreSQL index of `pending` entries to identify candidates, such as:
  - `pending` keys that have existed across multiple tick intervals with exhausted retries.
  - Regions where `tick:{tenantRegionTag}:pending` has not advanced despite repeated recovery attempts.
- Candidate stuck ticks are enqueued into a `tick_recovery` queue or table with metadata such as `<tenantId, gameInstanceId, regionId, tickId, firstSeenAt, lastRetryAt>`.
- An automated recovery worker:
  - Uses the same idempotent handlers and ledger/guard patterns as normal ticks to drive any effects associated with the stuck tick to terminal `APPLIED` or `ABANDONED` outcomes, and records any higher-level tick or command status (for example `FAILED`/`SKIPPED`) as a **derived view** over those effect-level results rather than as an independent convergence mechanism.
  - Clears `tick:{tenantRegionTag}:pending` and associated retry metadata via a dedicated, idempotent helper path.
  - Emits detailed logs and metrics for audit and dashboards.
- Operator tooling allows manual override for complex cases (for example, suspected data corruption), with two typical modes:
  - **Recommendation mode** – the system proposes recoveries; operators approve or override.
  - **Auto-recovery mode** – low-risk patterns are resolved automatically once thresholds are met.

Retry and timer queues are protected against unbounded growth:

- Retry queues (`retry:{tenantRegionTag}`) are ZSETs keyed by `next_eligible_tick_id` (target region timeline tick IDs). Scripts accept the scheduler’s current `(regionEpoch, tickId)` context, process at most `N` entries per invocation, and enforce a maximum retry budget per action.
- Timer keys (`timer:{tenantRegionTag}`) are ZSETs keyed by `due_ms` (absolute wall-clock milliseconds); scripts accept `now_ms` as a caller-supplied `ARGV` value (never Redis `TIME`), pop at most `N` timers per call, and delete processed members.
- Defensive limits (for example, maximum timers per region) trigger alerts or throttling if exceeded so bugs cannot create unbounded timer or retry growth.

Entity Management provides the reference example for per-aggregate tick idempotency; see `microservices/entity-management-service/README.md#tick-idempotency`.

## Domain Idempotency Rules (Region Epoch + TickId in PostgreSQL)

Domain services must treat the **region-scoped tick timeline** `(regionEpoch, tickId)` as the canonical idempotency token for tick-driven effects.

`tickId` is monotonic only **within a given `regionEpoch`** and may restart at `0` after a region-scoped or tenant-scoped reset that bumps `regionEpoch`. Any idempotency scheme that keys only on `tickId` (without `regionEpoch`) is therefore unsafe across resets.

Two patterns are used:

- **Per-aggregate last-tick state**
  - This pattern is allowed only for aggregates that are provably updated at most once per tick within a region epoch.
  - It is a narrow exception, not the default for gameplay-visible mutations.
  - Typical safe uses are once-per-tick watermark-style updates or aggregates whose design guarantees a single logical writer/effect per tick.
  - Aggregates that may receive multiple legitimate effects in one tick must not use this pattern.
  - A shadow tick-state record such as `entity_tick_state` is keyed by the complete `(tenant_id, game_instance_id, playable_state_scope, region_id, target_aggregate_type, aggregate_id)` identity, not by the aggregate identifier alone.
  - The shadow state stores at minimum:
    - `last_region_epoch`
    - `last_tick_id`
    - (plus tenant/game-instance/region identifiers or a foreign key implying them)
  - When applying a tick effect:
    - The handler resolves and reads the shadow tick-state row using the complete `(tenant_id, game_instance_id, playable_state_scope, region_id, target_aggregate_type, aggregate_id)` key; an aggregate-only or aggregate-type-free lookup is invalid.
    - If `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)` for that exact row, the update is treated as a replay or out-of-order attempt and becomes a no-op (or, in strict modes, a validation-only check).
    - If `(last_region_epoch, last_tick_id) < (currentRegionEpoch, currentTickId)`, the handler applies the change and updates `(last_region_epoch, last_tick_id) = (currentRegionEpoch, currentTickId)` on that same composite-key row in the same transaction as the domain mutation.
- **Operation-level effect guard**
  - This is the default pattern for gameplay-visible mutations.
  - Operations that may touch multiple aggregates or legitimately apply multiple distinct effects to the same aggregate in a single tick (for example trades, combat damage, healing, AoE damage, room occupancy changes, drops/pickups, or multi-target buffs) use a small guard table such as `tick_effect_guard` keyed by:
    - `tenant_id`
    - `game_instance_id`
    - `playable_state_scope`
    - `region_id`
    - `region_epoch`
    - `tick_id`
    - `effect_key` – a deterministic identifier describing the logical effect (for example `entity:<entityId>:award:achievement:<achievementId>` or `room:<roomId>:drop:item:<itemId>`).
    - `target_aggregate_type`
    - `target_aggregate_id`
  - Inside the same transaction as the domain update, the handler attempts to insert `(tenant_id, game_instance_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id)`:
    - If the insert succeeds, the effect is new for this tick and the handler applies all associated state changes.
    - If any insert conflicts on primary key, the handler must verify the complete expected target guard set and the corresponding authoritative state for the operation. Only a complete, consistent set is a replay/no-op; a partial conflict is an incomplete operation that must be reconciled with the original EffectId or fail closed, never treated as proof that all targets completed.

Examples:

- **Once-per-tick aggregate watermark (per-aggregate last-tick state)**
  - `AdvanceRegionAuraWatermark` receives `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, targetAggregateType, targetAggregateId)`.
  - The design guarantees this aggregate is advanced at most once per tick.
  - It reads the shadow tick state for the complete `(tenantId, gameInstanceId, playableStateScope, regionId, targetAggregateType, targetAggregateId)` key and applies the update only when `(last_region_epoch, last_tick_id) < (regionEpoch, tickId)`.
  - If `(last_region_epoch, last_tick_id) >= (regionEpoch, tickId)`, the handler treats the request as a replay/out-of-order and returns without changing state.
- **Trade between two entities (operation-level effect guard)**
  - `TradeItem` receives `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, fromEntityId, toEntityId, itemId)` and creates one EffectId projection per affected inventory aggregate.
  - It computes `effectKey = "trade:" + fromEntityId + ":" + toEntityId + ":" + itemId`.
  - In one transaction it:
    - Attempts to insert `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType=INVENTORY, targetAggregateId)` into `tick_effect_guard` for each affected inventory aggregate.
    - If the insert conflicts, it treats the call as a replay and returns success without modifying inventories.
    - If the insert succeeds, it debits the item from `fromEntityId`, credits it to `toEntityId`, and commits both inventory changes and the guard-row insert together.

Operationally:

- Every tick-driven write path must use either the per-aggregate `last_tick_id` pattern or the operation-level guard pattern.
- The default review assumption is that a gameplay-visible mutation requires an effect guard unless the design explicitly proves “at most one logical effect per aggregate per tick”.
- Domain handlers treat Redis locks and leases as opaque; they never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` directly.
- Operations that cannot be made idempotent or compensatable at the domain layer—for example payments, emails, or webhooks into third-party systems—must not be executed directly inside tick-driven handlers. Those flows must use the saga/outbox patterns in `system-architecture-transactions.md` so they can tolerate retries and partial failures independently of tick replay.

### Effect Identity, Endpoint Semantics, and Outcome Metrics

Tick-driven domain calls use a **canonical effect identity** and a shared contract for retries:

- Effect identity is derived deterministically from tick context and target aggregate identity; conceptually it includes:
  - `tenantId`
  - `gameInstanceId`
  - `playableStateScope`
  - `regionId`
  - `regionEpoch`
  - `tickId` (region-scoped)
  - `effectKey` (stable descriptor such as `damage:entity:<id>:command:<commandId>`; for scripting effects it incorporates `automationDispatchId`)
  - `targetAggregateType` (for example `ENTITY`, `INVENTORY`, `ROOM_STATE`, `QUEST`, `ACHIEVEMENT`)
  - `targetAggregateId`
  - Optional `domainScope` when needed to avoid collisions across independently owned domains.
- Game Session derives this identity while computing tick outcomes and passes it to each tick-invoked handler; handlers must not generate fresh random IDs for idempotency in tick paths.

Every gRPC endpoint that can be invoked from tick execution must document and implement:

- **Duplicate handling** – repeated calls with the same effect identity must return OK / “already applied” semantics (for example, no new HP or inventory changes) instead of logical errors that drive infinite retries.
- **Already-in-desired-state handling** – if the target state already reflects the intended outcome, the endpoint returns OK and does not emit additional side effects.
- **Retry classification** – errors must be classified as retryable (transient infrastructure issues) vs terminal (invalid inputs, missing aggregates). Terminal errors must move the corresponding ledger/guard entry into a terminal state (for example `ABANDONED`) instead of leaving work stuck in a retry loop.

Endpoints participating in tick-driven effects should also emit a small, standardized metric:

- `tick_effect_outcome_total{service, effect_type, outcome}`
  - `service` – owning microservice (for example `entity-management-service`).
  - `effect_type` – low-cardinality side-effect category (for example `entity_state`, `inventory`, `quest`, `room_state`), **not** the full effect identity.
  - `outcome` – `first_apply`, `replay_ok`, or `guard_error` (for unexpected failures at the idempotency boundary).

This metric provides a cross-service view of how often replay paths are exercised and highlights handlers that are not honoring the canonical idempotency contract.

### Side-Effect Categories and Idempotency Strategies

Different side-effect categories may use different persistence primitives, but each must be explicitly tied back to tick-driven effect identity:

- **Award-once side effects** (items, currency, XP):
  - Use a durable “effect applied” ledger keyed by the effect identity (or a unique equivalent) with insert-if-absent semantics.
  - On uniqueness conflict, treat the call as an idempotent replay and return OK without applying additional changes.
- **Monotonic state changes** (achievements, unlocks):
  - Use monotonic fields or unique constraints such as `(tenantId, playerId, achievementId)` plus a mapping to effect identity when they trigger additional rewards or notifications.
- **Notifications and events**:
  - Use transactional outbox entries keyed by effect identity (often `eventId == effectId`) so producers deduplicate sends; consumers may still apply defensive deduplication.
- **Versioned aggregates and CAS-style updates**:
  - Compare-and-set/version checks must treat replay-stale writes as OK/no-op outcomes instead of “conflict” errors that cause unbounded retries.

Together with the tick effect ledger, these patterns ensure that Redis coordination and replay logic remain simple while each domain service guarantees that its persistent mutations and outward side effects are safe to attempt multiple times.

## Tick-Aware Reset Scenarios

Coordination resets are expressed in terms of Redis scopes (region, tenant, cluster) in `system-architecture-redis-reset-and-recovery.md`. From the tick system’s perspective, they also have **timeline and ledger effects**:

- **Region-scoped reset**
  - Timeline impact:
    - For the affected `<tenantId, gameInstanceId, regionId>`, region-scoped tick coordination keys for the current `regionEpoch` (for example `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, `tick-executor-lease:{tenantRegionTag}`) are dropped according to the reset policy matrix.
    - Tenant-scoped coordination such as `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` and current `sessionctx:*` context remains in place unless a broader tenant- or cluster-scoped reset is explicitly invoked; region resets are not expected to evict gameplay sessions. The reset-sensitive `session:auth:*` family is not deleted by a region-only prefix scan, but preserved sessions must pass auth/revocation validation and any required re-authentication before rebind.
    - Region-authoritative `tick:{tenantRegionTag}:session-binding:*` keys are still region-scoped and are dropped with the rest of `tick:{tenantRegionTag}:*`; preserved sessions must be rebound through the session-to-region bridge before normal command intake resumes.
    - A new `regionEpoch` is established; subsequent ticks for that region advance on the **new (bumped) `regionEpoch`** starting at `tickId=0` on the coordination timeline described in `system-architecture-redis.md`.
  - Ledger behavior:
    - Tick effect ledger rows with `status = SCHEDULED` for the affected `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>` must not remain indefinitely pending.
    - The scoped ledger reconcile inspects each row's durable domain guard/state and any existing `APPLIED` ledger or domain-state reflection before choosing a terminal outcome. Effects already reflected in durable state are marked `APPLIED`; effects whose domain state confirms they were not applied and cannot be safely re-driven across the reset are marked `ABANDONED` with a reset reason such as `RESET_REGION_SCOPED`.
    - The reset step must not bulk-mark every `SCHEDULED` row `ABANDONED`; an inconclusive row is held for out-of-band, epoch-fenced reconciliation under its original EffectId until the state proves applied or unapplied. It must never be sent through normal current-epoch replay.
    - Only features that explicitly document alternative behavior may opt into re-scheduling selected SCHEDULED effects under the new epoch, and such behavior must be implemented via dedicated reset tooling, not ad-hoc replay.
  - Player impact:
    - In-flight actions within the tail-loss envelope may be dropped or replayed, but authoritative domain state remains consistent due to idempotency guards.
    - Players may observe “lost” commands around the reset boundary; game UX should frame this as a brief rewind/hiccup at the coordination layer, not silent corruption.

- **Tenant-scoped reset**
  - Timeline impact:
    - All regions for a given `tenantId` have their region-scoped tick coordination keys cleared and `regionEpoch` bumped.
    - Cross-region flows (for example follow-ups) resume only under the new epochs; stale follow-ups from previous epochs are ignored or reconciled.
    - Session and authentication keys follow the canonical reset-policy matrix: a tenant reset always invalidates `session:auth:*`; gameplay/session-context records are preserved only when the explicit `--preserve-sessions` policy is recorded, and preserved sessions must rebind after auth validation.
  - Ledger behavior:
    - Tick effect ledger rows for the tenant with `status = SCHEDULED` follow the per-effect reconciliation rule above: inspect durable domain state and any existing `APPLIED` reflection first, mark confirmed reflections `APPLIED`, and mark `ABANDONED` with a tenant-scoped reset reason such as `RESET_TENANT_SCOPED` only when domain state confirms the effect was unapplied and cannot be safely re-driven across the reset.
    - Re-scheduling across epochs is allowed only for features that explicitly document this requirement and provide dedicated tooling.
  - Player impact:
    - The tenant experiences a “clean slate” for tick coordination: timers, retries, and queued commands are cleared.
    - Long-lived domain state (characters, inventory, world) is preserved; tick-driven features must be designed so players can naturally continue from durable state.

- **Cluster-scoped reset**
  - Timeline impact:
    - All `<tenantId, gameInstanceId, regionId>` pairs on the deployment lose region-scoped tick coordination keys and receive new epochs.
    - This is effectively a deliberate, unbounded tail-loss event for all regions and must be treated as a rare, planned operation.
  - Ledger behavior:
    - `SCHEDULED` ledger rows for all affected regions follow the per-effect reconciliation rule above: durable domain state or an existing `APPLIED` reflection wins, and the ledger reconcile tooling may use a cluster reset reason such as `RESET_CLUSTER_SCOPED` only for effects confirmed unapplied and not safely re-drivable across the reset.
    - Only in exceptional, explicitly designed cases should migration or batch re-drive tooling attempt to carry work across a cluster reset; such tools must document their expectations and failure modes in the owning feature’s design docs.
  - Player impact:
    - All active regions experience at least a brief pause while epochs are re-established and ticks resume under the new coordination state.
    - UX and communications for planned cluster resets should set expectations (maintenance windows, possible brief rollbacks).

In all three cases, the **goal is convergence**:

- For each `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` there must eventually be exactly one terminal ledger state (`APPLIED` or `ABANDONED`), regardless of resets.
- Reset tooling and Game Session control flows must ensure that no tick remains forever “half-applied” in the ledger (for example, perpetually `SCHEDULED` with no chance of replay), by running a per-effect tick-effect-ledger reconcile for the relevant `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch)` combinations as part of the reset flow. The reconcile must inspect durable domain state before terminalizing each row and may abandon only effects confirmed unapplied.

These expectations should be reflected in the coordination reset tooling described in `system-architecture-redis-operations.md` and the reset policy matrix in `system-architecture-redis-reset-and-recovery.md`.

## Cross-Region Failure Semantics (Conceptual)

Cross-region tick-driven flows (such as combat actions that affect entities in multiple regions) are designed to be **best-effort and eventually consistent** by default rather than globally atomic across regions:

- Each leg of a cross-region flow (origin-region effects, target-region effects, and any results relayed back) uses its own `EffectId` and converges independently to `APPLIED` or `ABANDONED` in the tick effect ledger.
- Origin regions derive a high-level **command outcome** (for example `SUCCESS`, `PARTIAL`, `FAILED`) from the combination of leg outcomes and timeouts, and surface that to players via messaging and UI.
- Features that truly require “all-or-nothing across regions” semantics must build that coordination explicitly on top of the tick primitives (for example using saga/outbox patterns outside the tick loop as described in `system-architecture-transactions.md`); it is not provided implicitly by the tick engine.

Operationally:

- Timeouts waiting for remote results are treated as equivalent to a failed remote leg; origin-ledger entries for those coordinating effects converge to `ABANDONED` with a timeout reason and a corresponding `PARTIAL` or `FAILED` high-level outcome.
- If the origin scope is canonical `PAUSED` or `STALLED`, timeout convergence is owned by the documented recovery/controller path rather than by a normally advancing origin tick clock. Operators should not expect paused regions to age coordinator deadlines automatically without that recovery path.
- Explicit `ABANDONED` outcomes from a target region (for example, entity no longer valid, region reset, or unrecoverable domain error) are treated the same way at the origin.
- Late remote results are handled by the required lifecycle in `system-architecture-tick-execution-flows.md`:
  - Once origin has reached timeout-abandoned terminal state, late replies are either explicitly ignored (`LATE_RESULT_IGNORED`) or reconciled by a documented feature-specific compensation flow (`LATE_RESULT_RECONCILED`), never silently merged.
- Compensation beyond simple local corrections (for example refunding currency, undoing non-idempotent external side effects) must be orchestrated via saga/outbox flows outside the tick loop, not by attempting cross-region rollback inside tick execution.

### Cross-Region Follow-Ups and Region-Epoch Changes

Region resets and epoch bumps intentionally sever the old coordination timeline for a `<tenantId, gameInstanceId, regionId>`. Cross-region follow-ups must behave predictably across those boundaries:

- Follow-up rows and tick effects are always tied to a specific `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)`; they **never** silently migrate to a new epoch.
- When a target region’s `regionEpoch` is bumped (via region/tenant/cluster reset):
  - Any undrained follow-ups or `SCHEDULED` ledger rows for the old epoch are treated as **terminal for that epoch** only after per-effect reconciliation inspects durable domain guard/state and any existing `APPLIED` reflection.
  - The ledger replay controller marks confirmed reflections `APPLIED` and converges only effects confirmed unapplied to `ABANDONED` with a reset-specific reason (for example `RESET_REGION_SCOPED` or `RESET_TENANT_SCOPED`), rather than attempting to “replay them into the new epoch”. If the result remains inconclusive, recovery follows the bounded old-epoch attestation/reconciliation contract above under the original EffectId; normal current-epoch replay is forbidden.
  - Target-region tick executors ignore old-epoch work based on epoch/tick guards in their coordination scripts; they only stage and apply effects for the current epoch.
- Origin regions:
  - Observe the `ABANDONED` outcomes (or timeouts that result in `ABANDONED`) for remote legs and compute the appropriate high-level command outcome (`PARTIAL` or `FAILED`).
  - Surface player-facing feedback consistent with that outcome (for example “your cross-region trade failed due to region reset; your currency has been refunded”).

Designs that genuinely need to carry cross-region work across region resets or epoch changes must be treated as **exceptional** and implemented using out-of-band saga/outbox workflows with their own reset/runbook stories, not as normal tick behavior.

### Cross-Region Follow-Up Record Contract (Required)

Cross-region follow-ups are durable PostgreSQL records owned by Game Session (or another explicitly designated tick coordinator) that represent “work created in one region but owned by entities in another”. To keep cross-region correctness independent of best-effort Redis hints, follow-up records must satisfy a minimal, explicit contract:

- **Identity and scoping**
  - Each follow-up is tied to a specific target region timeline and effect identity, including at minimum:
    - `tenant_id`, `target_game_instance_id`, resolved `playable_state_scope`, `target_region_id`, `target_region_epoch`
    - `due_tick_id` in the target region timeline (preferred; do not use wall-clock due-time fields for cross-region follow-up eligibility)
    - `effect_key` (stable, deterministic), `target_aggregate_type`, `target_aggregate_id`, and any additional EffectId projection fields needed for traceability.
  - `due_tick_id` is computed from the target region’s durable status surface (for example `GetRegionTickStatus` / `RegionStatus.lastCommittedTickId`), not from Redis hint keys:
    - Canonical baseline: `due_tick_id = target_last_committed_tick_id + delta_ticks` (for immediate eligibility, `delta_ticks = 1`).
    - The writer must persist `target_region_epoch` and `due_tick_id` together from the same read so eligibility is deterministic across retries and failover.
- **Uniqueness / de-duplication**
  - The follow-up table must prevent duplicate scheduling of the same logical follow-up for the same target timeline and state namespace (for example via a unique key that includes `(tenant_id, target_game_instance_id, playable_state_scope, target_region_id, target_region_epoch, effect_key, target_aggregate_type, target_aggregate_id)` or an equivalent projection that matches the feature’s semantics).
- **Claiming and concurrency**
  - Draining follow-ups into a tick must use database-side concurrency control (for example `FOR UPDATE SKIP LOCKED` or an atomic “claim” update) so that only one executor can claim a follow-up at a time, even during failover or when multiple workers are racing around lease changes.
- **Epoch boundaries**
  - Follow-ups must never silently “carry over” to a new epoch:
    - When `target_region_epoch` changes, old-epoch follow-ups converge to terminal outcomes (typically `ABANDONED` with a reset/topology reason) unless explicit maintenance tooling re-schedules them into the new epoch.
- **Topology changes**
  - Mapping-changing split/merge operations must bump `regionEpoch` for affected source/target regions before follow-up draining resumes (see required topology protocol in `system-architecture-ticks.md`).
  - If region split/merge changes which region owns the target entity, topology-change tooling must either:
    - Rewrite the follow-up to the new `(target_region_id, target_region_epoch)` with an explicit audit trail, or
    - Mark it `ABANDONED` with a topology-change reason when replaying it under the new mapping is not valid.

## Remote Hint Markers and Resets

Cross-region flows may use best-effort Redis hint markers such as `remote:{tenantInstanceTag}:<entityId>` (for target entities, with the tag derived from target `<tenantId, gameInstanceId>`) to reduce latency when draining remote follow-ups. Operationally:

- These markers are **latency hints only**:
  - They may be overwritten, duplicated, or lost.
  - Correctness is derived from durable follow-up rows in PostgreSQL, not from the presence of `remote:*` keys.
- The marker key must be TTL-bounded so the hint keyspace cannot grow without bound:
  - Canonical write form: `SET remote:{tenantInstanceTag}:<entityId> 1 PX remote_hint_ttl_ms` with default `remote_hint_ttl_ms = 60_000`.
  - TTL refresh happens when new durable follow-ups are recorded for that target entity (and optionally while backlog remains due); expiry is treated as normal and must not be interpreted as “no work exists”.
- Region-level coordination resets do not attempt to delete `remote:*` keys because these keys are tenant-scoped rather than region-scoped.
- Tenant- and cluster-scoped coordination resets may drop `remote:*` keys alongside other coordination state for the affected tenant; losing them remains safe because they only affect how quickly remote follow-ups are noticed, not whether those follow-ups eventually apply.
- After a region reset, the next tick executor:
  - Resumes draining due follow-ups from PostgreSQL into its normal tick pipeline.
  - Treats any stale or missing `remote:*` markers as affecting only how quickly it notices new work, not whether the work is eventually applied.

When debugging cross-region issues, operators should rely on PostgreSQL follow-up tables, tick effect ledgers, and the metrics described in the execution-flow docs rather than assuming `remote:*` keys are authoritative.

## Testing Tick Idempotency and Redis Replays

Because crash recovery relies on idempotent handlers, each service with tick-driven logic should include integration tests that simulate Redis-style replays:

- Invoke the same handler multiple times with identical `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId, payload)` and assert that:
  - The first call mutates state as expected.
  - Subsequent calls are treated as replays and do not apply additional logical effects (HP changes, inventory moves, etc.).
- Repeat the same `effectKey` against a different `targetAggregateType` or `targetAggregateId` and assert that the distinct target is not incorrectly deduplicated. For script-generated effects, also vary `automationDispatchId` and prove that its deterministic contribution to `effectKey` keeps fan-out commands distinct.
- Exercise both idempotency strategies:
  - Per-aggregate `last_tick_id` tables (for single-entity updates).
  - Operation-level `tick_effect_guard` tables (for multi-entity effects).
- Where practical, share a small test harness that:
  - Constructs synthetic `tick:{tenantRegionTag}:pending` payloads.
  - Drives the same sequence of domain calls multiple times, mimicking replay of a pending tick after a crash.
  - Verifies that final PostgreSQL state is identical regardless of how many times the tick is “reapplied”.
- Include crash-window tests for the two-boundary model:
  - Crash before durable commit: verify replay convergence and no double-apply.
  - Crash after durable commit but before coordination cleanup: verify no watermark regression, no duplicate effects, and cleanup completion before next tick staging.

CI pipelines should run these replay tests; changes to tick handlers that break idempotency ought to fail tests before reaching production.

## Design Checklist for New Tick-Driven Commands

When introducing a new command type that will run under tick control, design docs and code reviews should explicitly cover:

- **Is this command tick-driven?**
  - Does it run because an entry is dequeued from `tick:{tenantRegionTag}:queue:<entityId>` or because a tick timer/retry fired?
  - If not, it may follow different idempotency rules and does not belong in this section.
- **What is the idempotency key?**
  - For single-aggregate updates: which complete `(tenant_id, game_instance_id, playable_state_scope, region_id, target_aggregate_type, aggregate_id)` key plus `(last_region_epoch, last_tick_id)` (or equivalent) fields and table enforce “at most one update per tick timeline” for that aggregate?
  - For multi-aggregate or multi-effect operations: what is the `effect_key` used in `tick_effect_guard`, and how is it derived deterministically from the command payload?
- **Where is the guard persisted?**
  - Which schema/table holds the per-aggregate “last applied tick” state or `tick_effect_guard` entries?
  - Is there a primary key or unique index that enforces the idempotency key at the database level?
- **What happens on replay?**
  - What does the handler do when it detects that the guard already exists or `(last_region_epoch, last_tick_id) >= (currentRegionEpoch, currentTickId)`?
  - Is the “replay” outcome clearly documented and tested (no new logical effects, optional consistency verification)?
- **Are there any non-idempotent external effects?**
  - If the handler sends email, charges a payment method, or calls an external API with irreversible effects, how is that separated from the tick-driven part (for example, via an outbox entry processed by a saga)?

Pull requests that add new tick-driven commands should link back to this checklist and show how each item is satisfied before the feature is considered complete.
