# FireMUD Redis Incident Runbook

This runbook summarizes operator actions for **Redis-related incidents**, including coordination outages, cache issues, and AOF problems.

For the full design and invariants, see:

- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-operations.md` (including the Redis Metrics Catalog section for metric names and alerting guidance)

## Implementation Notes

The incident flows below describe the target operator model. In the current implementation:

- Game Session already exposes durable command-status lookup and current-boundary runtime ownership inspection through the control-plane gRPC surface.
- Accepted-but-unstaged commands already converge to `LOST_BEFORE_STAGING` during startup recovery, so command status is no longer purely transient Redis state.
- The full `coordination-maintenance` replay/reset orchestration referenced below is not yet shipped as a complete repo-local CLI/control-plane surface; where the runbook names those verbs, read them as target-state recovery tooling rather than a claim that every step is already available today.

Region-scoped coordination key examples use `{tenantRegionTag}`, the canonical opaque tag for `<tenantId, gameInstanceId, regionId>`; instance-scoped automation examples use `{tenantInstanceTag}`, the canonical opaque tag for `<tenantId, gameInstanceId>`. The tag carries the game-instance identity even when the raw identifier is not repeated in the key.

## Incident Types

- **Coordination Redis outage or high latency**
- **Cache/Rate-Limit Redis degradation**
- **AOF corruption, truncation, or disk pressure**
- **Session schema or TTL problems**
- **Mis-sharded or mis-keyed coordination keys**

## Coordination Redis Outage or Degradation

1. **Detect**
   - Alerts fire on tick duration, Redis latency, or error rates.
   - Logs show failures acquiring locks or writing tick entries.
2. **Stabilize**
   - Close new login, join, `PLAY`, reconnect/rebind, token issuance/refresh, and protected control-plane admission while required authority cannot be established.
   - If gameplay coordination remains healthy but token authority alone is unavailable, existing admitted bindings may continue only through their last renewed authority-freshness lease and terminate at the 60-second maximum. Do not add per-command registry reads.
   - If the complete Coordination Redis role is unavailable, halt correctness-sensitive gameplay mutations; bounded socket recovery does not authorize local-only processing.
   - Pause non-critical background scripts that depend heavily on Coordination Redis.
3. **Recover**
   - Follow cluster or node failover procedures documented in `design/architecture/system-architecture-redis-operations.md`.
   - If a reset is required, use the coordination reset model and key enumeration strategy from `design/architecture/system-architecture-redis-reset-and-recovery.md` to clear affected prefixes safely.
4. **Inspect durable command outcomes**
   - After replay/reset work, use the canonical `GetGameplayCommandStatus` surface described in `design/architecture/system-architecture-tick-failures-and-operations.md` to confirm commands converged to their final durable outcomes.
   - Do not treat raw Redis queue/key inspection as the primary operator answer for player-visible command status after remediation.

### Coordination Redis Recovery Behaviour

When Coordination Redis recovers after an outage or severe degradation:

- **Tick executors**
  - Do not attempt to resume in-flight locks or leases based on in-memory state.
  - Re-establish the authoritative recovery baseline from PostgreSQL tick-batch records, the tick effect ledger, follow-up tables, and `RegionStatus`; surviving Redis keys are inspected only as coordination residue and hints.
  - If `pending` survives for a region, the next executor correlates it to the durable `tick_batch_id` and then replays the tick as described in the tick system design.
  - If `pending` is missing (for example due to AOF tail loss), treat this as “coordination state may have been partially lost” rather than silently skipping work:
    - Advance to the next `tickId` only after running the tick effect ledger replay controller / reconcile tooling for the affected scope so any lingering `SCHEDULED` effects converge to `APPLIED` or `ABANDONED` with an explicit tail-loss reason.
    - Converge accepted command records that were never durably bound to a surviving batch to terminal command status fields such as `executionOutcome = LOST_BEFORE_STAGING` and the command-type-appropriate `gameplayResult` (shared default `NOT_APPLIED`); do not leave dedupe-only command records stranded. See `system-architecture-tick-execution-flows.md` under `Canonical Command Terminal Mapping Table` for the canonical shared mapping.
    - Use the resulting ledger outcomes plus service-level idempotency guards to validate that no effect remains indefinitely half-applied.
- **Leases**
  - Discard any in-memory lease tokens; executors must reacquire `tick-executor-lease:{tenantRegionTag}` in Redis and treat previously held leases as invalid.
- **Sessions**
  - If `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` keys survive, reconnect flows behave normally.
  - If session keys are lost while game instances remain `RUNNING` in PostgreSQL, treat reconnect attempts as “no active binding” (clients may need to perform a fresh `LOGIN` or be rebound to the existing instance depending on ownership rules).

## Cache/Rate-Limit Redis Issues

1. **Detect**
   - Elevated cache miss rates, eviction spikes, or Redis memory alerts.
2. **Stabilize**
   - Throttle non-essential cache usage or temporarily reduce cache TTLs if documented.
   - Confirm that coordination keys are not co-located with caches.
3. **Recover**
   - Scale the cache deployment or provision additional nodes.
   - Flush or reset specific cache prefixes if needed; do not reset coordination prefixes from the cache deployment.

## Session Schema and TTL Cleanup

When session-related metrics indicate schema or TTL problems, use this scoped cleanup procedure instead of ad-hoc `DEL` commands:

1. **Detect the issue**
   - Watch `session.cas_unsupported_schema_total` and reconnect error rates for non-zero values outside brief rollout windows.
   - Interpretation: services and Lua scripts are out of sync on the highest `schemaVersion` in use for `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` keys, session payloads have been corrupted, or a major TTL reduction has left an undesirable tail of long-lived sessions.
1. **Align deployments**
   - Verify and correct deployments so all Game Session Service instances run a version whose CAS script understands the highest `schemaVersion` currently present in Redis (follow the “scripts first, writers second” rule from the Redis architecture docs).
1. **Run the canonical session cleanup workflow**
   - Use the session schema/TTL cleanup flow described in [Session Schema Cleanup and Large Keyspaces](./system-architecture-redis-operations.md#session-schema-cleanup-and-large-keyspaces):
     - Run the single supported `coordination-maintenance recover --mode session-schema-cleanup --scope tenant --tenant <tenantId>`, using `--dry-run` first if the blast radius is uncertain. The operation owns the maintenance lock and invokes session cleanup as an internal recovery phase, not a separately supported public verb.
     - Configure the recovery request to delete keys with unsupported `schemaVersion` values or aggressively reduce their TTL so they expire quickly when performing a TTL cut-over.
     - Let the recover operation release its lock on success; use the separately audited `coordination-maintenance release-lock ...` control only after an operator abort or failed workflow.
1. **Verify recovery**
   - Monitor `session.cas_unsupported_schema_total`, reconnect error rates, and Redis key counts for the affected tenant(s) to confirm the issue has cleared.
   - Affected players may need to log in again; no authoritative PostgreSQL data is lost.

## AOF and Persistence Problems

- See the detailed AOF guidance in `design/architecture/system-architecture-redis-operations.md` and the coordination reset model in `design/architecture/system-architecture-redis-reset-and-recovery.md`.
- When disk pressure, corruption, or replay issues are detected:
  - Capture diagnostics and snapshots where safe.
  - Follow only the documented AOF recovery paths:
    - Replay the existing AOF as-is when it is trusted, or
    - Discard coordination state and run the scoped reset/full-wipe flow from `system-architecture-redis-operations.md`.
  - Do not perform manual AOF truncation or file editing.
  - Use idempotent replay and tick system rules to rebuild necessary state.

## Redis Incident Scenarios

The following Redis-focused incident flows build on the general recovery steps above.

### Coordination AOF Tail-Loss SLO Breach

1. **Detect**
   - Tail-loss indicators such as `redis_coordination_tail_loss_ms` regularly exceed the canonical envelope (`tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` from `system-architecture-redis-operations.md`) for one or more `<tenantId, gameInstanceId, regionId>` shards.
   - Region health shows sustained `DEGRADED` or `STALLED` state for those shards.
2. **Decide**
   - For short-lived degradations where gameplay impact is minimal, investigate disk/replication performance, but keep serving traffic.
   - For sustained violations or `STALLED` regions, treat this as a **tick SLO breach** for the affected `<tenantId, gameInstanceId, regionId>` shards and choose exactly one recovery mode first:
     - `replay_first`
       - Use when the region is still on one coherent `regionEpoch`, there is no evidence of mixed-epoch state, no duplicate durable batches, and surviving coordination residue can still be correlated to the durable batch/ledger timeline.
       - Goal: preserve as much in-epoch work as possible by driving lingering `SCHEDULED` rows to `APPLIED` or `ABANDONED` without bumping `regionEpoch`.
     - `reset_first`
       - Use when the region is already `STALLED`, when mixed-epoch or orphaned coordination state is suspected, when duplicate/inconsistent durable batches are detected, or when replay-first fails to make bounded progress within the replay convergence budget.
       - Goal: fence the old timeline with an epoch bump and use the canonical reset handshake to abandon or reconcile old-epoch work explicitly.
3. **Act**
   1. If the chosen mode is `replay_first`:
      - Run `coordination-maintenance recover --mode replay-first ...` for the affected scope without bumping `region_epoch`. The high-level workflow owns ledger and command convergence; those phases are not separate public operator verbs.
      - Watch `tick_effects_pending_oldest_age_seconds`, `tick_effects_replay_slo_breached`, and command convergence for one emitted replay-convergence budget window. Verify that command outcomes settle into the canonical terminal vocabulary described in `system-architecture-tick-execution-flows.md` rather than inventing a replay-only local interpretation.
      - If the replay budget/status gate passes, run `coordination-maintenance resume ... --maintenance-lock-token <token>` to release the same maintenance lock without bumping `regionEpoch`.
      - Escalate to `reset_first` immediately if replay cannot make bounded progress, if inconsistent-state signals appear, or if the region transitions to `STALLED`. Escalation atomically upgrades the existing maintenance lock's compatibility class from `cleanup` to `reset` by compare-and-match on the same `maintenanceLockToken`, retains the original recovery audit lineage, and writes the upgrade audit record before bumping `regionEpoch` or mutating reset keys. The audit record must include the scope, old/new compatibility class, token/workflow lineage, actor, reason, and resulting epoch transition. Do not release and reacquire the lock between recovery modes; if the tooling cannot perform that atomic same-token upgrade and audit ordering, it must leave the scope paused for an explicit operator failure path rather than starting a second recovery lock.
      - Worked example:
        1. Region `(T1, R7)` remains on `region_epoch = 13`, `tick_effects_pending_oldest_age_seconds` exceeds budget, and there is no evidence of mixed-epoch state or duplicate durable batches.
        2. Operator runs `coordination-maintenance recover --mode replay-first --scope region --tenant T1 --region R7`.
        3. The recovery workflow runs its internal ledger and command-convergence phases, converging lingering epoch-13 `SCHEDULED` rows to `APPLIED` or `ABANDONED` without bumping `region_epoch` and driving command records to the canonical terminal vocabulary.
        4. If pending age and stalled signals recover within one emitted budget window, the region stays on epoch `13`; otherwise the operator escalates to `reset_first`.
   2. If the chosen mode is `reset_first`:
      - Execute the [Canonical Coordination Reset Sequence](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence) for the same scope.
      - Keep only the incident-specific choices local to this runbook: scope selection, whether replay-first was exhausted first, whether gameplay sessions are preserved, and what evidence justified escalation.
   3. Verify region health returns to `RUNNING` or bounded `DEGRADED` and `redis_coordination_tail_loss_ms` drops back into the SLO envelope after the chosen recovery mode completes.

Alerts based on `redis_coordination_tail_loss_ms` should follow the conventions in `design/observability/grafana/redis-alerts-snippets.md` so they carry `owner` and `runbook` annotations that point back to this section.

### Mis-Sharded or Mis-Keyed Tick/Coordination Keys

1. **Detect**
   - CI or observability flags keys with unexpected hash tags (for example, multiple `{}` segments or missing `{tenantRegionTag}`).
   - Redis key inspections show coordination prefixes that do not match the documented patterns.
2. **Decide**
   - If mis-keyed data is purely coordination state (no unique business data), prefer a reset over in-place fixes.
   - If the mistake involves non-coordination prefixes that cannot be safely discarded, plan a one-off migration tool.
3. **Act**
   1. For coordination prefixes: follow the region/tenant/cluster reset flow from [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model) and rely on PostgreSQL/idempotent ticks to rebuild state.
   2. For non-coordination prefixes: write a small migration Job that:
      - Iterates the affected prefix (for example `automation:queue:{tenantInstanceTag}:*`).
      - Writes corrected keys using shared builders.
      - Deletes or expires the old keys once consumers have been updated.
   3. Use the `tick-region-logs.json` Kibana saved search to confirm that tick/coordination-related logs for the affected regions no longer show mis-keyed or unknown-prefix warnings after the migration or reset completes.

### Automation Queue Schema Mistakes

1. **Detect**
   - Automation consumers log deserialization errors or unknown `schemaVersion` values for `automation:queue:{tenantInstanceTag}:*` keys.
   - Metrics show sustained failures processing automation work items.
2. **Decide**
   - If automation queues are purely best-effort, consider treating affected items as lost and flushing the prefix.
   - If the workflow requires guaranteed preservation, do not migrate from Redis queue contents; rebuild from the durable PostgreSQL trigger/effect tables and idempotent handlers.
3. **Act**
   1. Pause automation processing for the affected tenants or globally, depending on blast radius.
   2. Choose one explicit remediation path:
      - Best-effort path: flush `automation:queue:{tenantInstanceTag}:*` and restart consumers.
      - Durable path: run the Automation rebuild workflow that re-enqueues from PostgreSQL-backed triggers/quotas, not from Redis queue payload migration.
   3. Resume automation processing and monitor error rates and queue depths until they stabilize.
