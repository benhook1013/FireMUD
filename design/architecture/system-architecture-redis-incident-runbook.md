# FireMUD Redis Incident Runbook

This runbook summarizes operator actions for **Redis-related incidents**, including coordination outages, cache issues, and AOF problems.

For the full design and invariants, see:

- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-operations.md` (including the Redis Metrics Catalog section for metric names and alerting guidance)

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
   - Reduce incoming player load if necessary (rate-limit new sessions).
   - Pause non-critical background scripts that depend heavily on Coordination Redis.
3. **Repair**
   - Follow cluster or node failover procedures documented in `design/architecture/system-architecture-redis-operations.md`.
   - If a reset is required, use the coordination reset model and key enumeration strategy from `design/architecture/system-architecture-redis-reset-and-recovery.md` to clear affected prefixes safely.

### Coordination Redis Recovery Behaviour

When Coordination Redis recovers after an outage or severe degradation:

- **Tick executors**
  - Do not attempt to resume in-flight locks or leases based on in-memory state.
  - Re-establish the authoritative recovery baseline from PostgreSQL tick-batch records, the tick effect ledger, follow-up tables, and `RegionStatus`; surviving Redis keys are inspected only as coordination residue and hints.
  - If `pending` survives for a region, the next executor correlates it to the durable `tick_batch_id` and then replays the tick as described in the tick system design.
  - If `pending` is missing (for example due to AOF tail loss), treat this as “coordination state may have been partially lost” rather than silently skipping work:
    - Advance to the next `tickId` only after running the tick effect ledger replay controller / reconcile tooling for the affected scope so any lingering `SCHEDULED` effects converge to `APPLIED` or `ABANDONED` with an explicit tail-loss reason.
    - Converge accepted command records that were never durably bound to a surviving batch to terminal command outcomes such as `executionOutcome = LOST_BEFORE_STAGING`; do not leave dedupe-only command records stranded.
    - Use the resulting ledger outcomes plus service-level idempotency guards to validate that no effect remains indefinitely half-applied.
- **Leases**
  - Discard any in-memory lease tokens; executors must reacquire `tick-executor-lease:{tenantRegionTag}` in Redis and treat previously held leases as invalid.
- **Sessions**
  - If `session:game:<tenantId>:<gameInstanceId>:<sessionId>` keys survive, reconnect flows behave normally.
  - If session keys are lost while game instances remain `RUNNING` in PostgreSQL, treat reconnect attempts as “no active binding” (clients may need to perform a fresh `LOGIN` or be rebound to the existing instance depending on ownership rules).

## Cache/Rate-Limit Redis Issues

1. **Detect**
   - Elevated cache miss rates, eviction spikes, or Redis memory alerts.
2. **Stabilize**
   - Throttle non-essential cache usage or temporarily reduce cache TTLs if documented.
   - Confirm that coordination keys are not co-located with caches.
3. **Repair**
   - Scale the cache deployment or provision additional nodes.
   - Flush or reset specific cache prefixes if needed; do not reset coordination prefixes from the cache deployment.

## Session Schema and TTL Cleanup

When session-related metrics indicate schema or TTL problems, use this scoped cleanup procedure instead of ad-hoc `DEL` commands:

1. **Detect the issue**
   - Watch `session.cas_unsupported_schema_total` and reconnect error rates for non-zero values outside brief rollout windows.
   - Interpretation: services and Lua scripts are out of sync on the highest `schemaVersion` in use for `session:game:<tenantId>:<gameInstanceId>:<sessionId>` keys, session payloads have been corrupted, or a major TTL reduction has left an undesirable tail of long-lived sessions.
1. **Align deployments**
   - Verify and correct deployments so all Game Session Service instances run a version whose CAS script understands the highest `schemaVersion` currently present in Redis (follow the “scripts first, writers second” rule from the Redis architecture docs).
1. **Run the session cleanup Job**
   - Use the session schema/TTL cleanup Job described in [Session Schema Cleanup and Large Keyspaces](./system-architecture-redis-operations.md#session-schema-cleanup-and-large-keyspaces):
     - Scope the Job to one tenant at a time by prefix (for example `session:game:<tenantId>:*`).
     - Configure it to delete keys with unsupported `schemaVersion` values or aggressively reduce their TTL so they expire quickly when performing a TTL cut-over.
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
   - Tail-loss indicators such as `redis_coordination_tail_loss_ms` or `tail_loss_ticks` regularly exceed the canonical envelope (`tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` from `system-architecture-redis-operations.md`) for one or more `<tenantId, regionId>` shards.
   - Region health shows sustained `DEGRADED` or `STALLED` state for those shards.
2. **Decide**
   - For short-lived degradations where gameplay impact is minimal, investigate disk/replication performance, but keep serving traffic.
   - For sustained violations or `STALLED` regions:
     - Treat this as a **tick SLO breach** for the affected `<tenantId, regionId>` shards.
     - Plan both:
       - A region- or tenant-scoped coordination reset, and
       - A run of the tick effect ledger replay controller for the same scope to converge any lingering `SCHEDULED` rows (see `system-architecture-tick-failures-and-operations.md#tick-effect-ledger-and-replay-guarantees` and `#ledger-replay-controller`).
3. **Act**
   1. Pause tick scheduling for affected `<tenantId, regionId>` scopes.
   2. Run the corresponding coordination reset Job (region or tenant scope) as described in [Coordination Reset Model](./system-architecture-redis-reset-and-recovery.md#coordination-reset-model).
   3. Trigger the ledger replay controller for the same scope to drive stale `SCHEDULED` tick effects to terminal `APPLIED` or `ABANDONED` outcomes based on idempotent domain state, and converge any accepted-but-unbound command records to `TERMINAL` with `executionOutcome = LOST_BEFORE_STAGING`.
   4. Verify region health returns to `RUNNING` or bounded `DEGRADED` and `tail_loss_ms` drops back into the SLO envelope before resuming ticks.

Alerts based on `redis_coordination_tail_loss_ms` should follow the conventions in `design/observability/grafana/core-alerts-snippets.md` so they carry `owner` and `runbook` annotations that point back to this section.

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
      - Iterates the affected prefix (for example `automation:queue:<tenantId>:*`).
      - Writes corrected keys using shared builders.
      - Deletes or expires the old keys once consumers have been updated.
   3. Use the `tick-region-logs.json` Kibana saved search to confirm that tick/coordination-related logs for the affected regions no longer show mis-keyed or unknown-prefix warnings after the migration or reset completes.

### Automation Queue Schema Mistakes

1. **Detect**
   - Automation consumers log deserialization errors or unknown `schemaVersion` values for `automation:queue:<tenantId>:*` keys.
   - Metrics show sustained failures processing automation work items.
2. **Decide**
   - If automation queues are purely best-effort, consider treating affected items as lost and flushing the prefix.
   - If the workflow requires guaranteed preservation, do not migrate from Redis queue contents; rebuild from the durable PostgreSQL trigger/effect tables and idempotent handlers.
3. **Act**
   1. Pause automation processing for the affected tenants or globally, depending on blast radius.
   2. Choose one explicit remediation path:
      - Best-effort path: flush `automation:queue:<tenantId>:*` and restart consumers.
      - Durable path: run the Automation rebuild workflow that re-enqueues from PostgreSQL-backed triggers/quotas, not from Redis queue payload migration.
   3. Resume automation processing and monitor error rates and queue depths until they stabilize.
