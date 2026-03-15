# FireMUD Redis Operations & Migrations

This document captures **operational runbooks and migration procedures** for Coordination Redis. It complements the conceptual guarantees in `system-architecture-redis.md` and the Lua authoring patterns in `system-architecture-redis-lua-patterns.md`.

The invariants and contracts in `system-architecture-redis.md` remain authoritative. This file focuses on **“when X happens, do 1–2–3”** guidance.

## Default Operator Flows

- Selecting an appropriate **AOF profile** (`dev_local`, `hobby_self_hosted`, or `production_clustered`) and watching the associated size/restart targets.
- Running the named **coordination reset** and **script upgrade** flows when metrics or the Lua Compatibility Registry indicate they are required.

Other procedures and tuning advice here are **advanced** and should not be expanded into bespoke, one-off sequences; new remediation paths should be expressed in terms of these named flows wherever possible.

## Table of Contents

- [FireMUD Redis Operations & Migrations](#firemud-redis-operations--migrations)
  - [Redis SLOs & Budgets](#redis-slos--budgets)
  - [AOF Size and Restart Budget](#aof-size-and-restart-budget)
  - [Lua Compatibility Registry & Script Upgrades](#lua-compatibility-registry--script-upgrades)
  - [Replica Promotion and Missed Writes](#replica-promotion-and-missed-writes)
  - [Cache/Rate-Limit Redis Reset](#cacherate-limit-redis-reset)
  - [Key Shape Mistakes and Coordination Resets](#key-shape-mistakes-and-coordination-resets)
  - [Normalization and Hash-Tag Migration](#normalization-and-hash-tag-migration)

---

## Redis SLOs & Budgets

This section centralizes the **normative targets** for Redis behavior that other docs reference. Individual environments may tune these values, but changes should be treated as **deliberate SLO updates**, not silent drift.

### Coordination Redis – Core Targets

- **Tail-loss window**
  - Production-like profiles (`hobby_self_hosted`, `production_clustered`) target a tail-loss envelope computed as:
    - `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)`
  - In common deployments this corresponds to roughly 1–2 seconds of coordination activity per `<tenantId, regionId>`, but the formula above is authoritative.
  - Ephemeral profiles (`dev_local`, certain CI stacks) may accept wider or unbounded tail-loss, but must be clearly labelled as such and **must not** be used to validate tail-loss SLOs.
  - From the tick system’s perspective (see `system-architecture-tick-concepts-and-invariants.md`), any sustained breach of this envelope is a **tick SLO violation**, not just a Redis metric anomaly: it means coordination state inside the tail-loss window can no longer be treated as reliably reflecting the tick effect ledger.
  - Even under SLO breach, domain-level idempotency and the canonical `EffectId` contract still prevent **double-application** of tick effects; what degrades is:
    - The size of the window where Redis coordination state can be trusted for **automatic** replay decisions.
    - The amount of manual or tooling-driven reconciliation required to drive lingering `SCHEDULED` ledger rows to `APPLIED` or `ABANDONED` (see the ledger replay controller in `system-architecture-tick-failures-and-operations.md`).
- **Restart time**
  - For `hobby_self_hosted` and `production_clustered` Coordination Redis nodes, planned restarts (including AOF/RDB replay) should typically complete within **30–60 seconds**.
  - Restarts that routinely exceed this window are treated as signals to adjust AOF size, hardware, or topology rather than “just slower maintenance”.
- **Script runtime**
  - Tick- and session-related Lua scripts are expected to complete well within tick latency targets: **< 10–20 ms** per invocation under normal load.
  - The Lua Script Registry records a `max_execution_ms` hint per script; observability tracks percentiles and flags scripts that consistently approach or exceed their budget.
- **Coordination memory share**
  - Coordination Redis uses `maxmemory-policy noeviction` and is sized so that **coordination prefixes** (`tick:*`, `timer:*`, `retry:*`, `session:*`, `tick-executor-lease:*`, etc.) normally occupy **≤ 30–40 %** of `maxmemory`.
  - Sustained usage above this fraction is treated as either a sizing problem or a misuse of Coordination Redis (for example, cache-like data being stored on the coordination role).

### Cache/Rate-Limit Redis – Core Targets

- **Eviction posture**
  - Cache/Rate-Limit Redis is allowed to evict keys, but sustained high eviction rates while `used_memory` is near `maxmemory` indicate the cache is mis-sized or mis-designed.
  - Operators should treat “eviction under pressure” as a prompt to adjust cache budgets or reduce what is cached, not as a steady-state behavior.
- **Keycount envelopes**
  - Rate-limit prefixes (`ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]`) should stay within a **few thousand active keys per tenant** across all live windows for small/self-hosted deployments; larger clusters may raise this envelope with explicit review.
  - Chat and similar TTL-only caches should remain within a modest, documented number of keys per tenant; sustained growth beyond those envelopes should trigger investigation for missing TTLs or mis-keyed prefixes.

Alerts and dashboards should reference these SLOs explicitly (for example, “tail_loss_ms > 2000 for region X” or “coordination used_memory / maxmemory > 0.4 for Y minutes”) so incidents are tied directly to the agreed budgets.

### Cache SLOs & Alerting

While Cache/Rate-Limit Redis is non-authoritative, its behavior under reset and eviction still impacts player experience and database load. Operators should track at least:

- Per-prefix hit/miss ratios – especially for `inventory:*`, `character-cache:*`, `world-dynamic:*`, `room:*`, and `view:room-look:*` – with alerts when miss rates spike for sustained periods after a reset or configuration change.
- Database/service load – simple gauges or rate metrics for backing PostgreSQL queries (for example, inventory/world snapshot reads) so resets or eviction storms that materially increase DB load are visible and can be correlated with cache behavior.
- Chat cache health – keycount and eviction trends for `chat:*` prefixes so misconfigured TTLs or buffer lengths show up before they crowd out other caches.
- Automation cache usage – basic keycount and enqueue/drop metrics for `automation:queue:*` / `automation:quota:*`, mainly to detect drift from their documented best-effort semantics.

These cache SLOs complement the coordination SLOs without treating Cache/Rate-Limit Redis as a correctness boundary: alerts should drive tuning (TTL, payload size, “what we cache”) or capacity changes rather than attempts to diagnose correctness issues inside Redis itself.

## AOF Size and Restart Budget

**Goal:** Keep Coordination Redis restart behavior predictable and avoid unbounded AOF growth.

### Targets

- Soft AOF size limit per node: **1–2 GiB** for small/self-hosted deployments; larger clusters may accept proportionally larger files if restart budgets remain within the targets below.
- Typical restart time (AOF or RDB+AOF replay): **30–60 seconds** during planned maintenance for Coordination Redis nodes.
- Effective daily AOF growth for coordination workloads should normally stay below **~250–500 MiB/day** per node in steady state; sustained growth beyond that envelope warrants investigation.

These targets are enforced via a small set of metrics and dashboards:

- Metrics:
  - `redis_aof_current_size_bytes` (or equivalent from `INFO persistence`) per Coordination Redis node.
  - `redis_aof_rewrite_in_progress` and `redis_aof_rewrite_time_sec` to monitor rewrite behavior.
  - `redis_coordinator_restart_duration_seconds` (custom) emitted by coordination maintenance jobs during controlled restarts.
  - `redis_coordination_aof_growth_bytes_total` (custom) derived from periodic samples of AOF size to approximate daily growth.
- Dashboards:
  - An **AOF health panel** that plots AOF size per node, restart duration (where measured), and daily growth estimates against the soft limits above.
  - A **coordination capacity panel** that correlates AOF size with coordination key counts and per-region footprints so operators can see which regions or tenants are contributing to growth.

Operators should wire alerts directly to these metrics (for example, warn when AOF size crosses the soft limit, or when restart duration and growth remain above targets for several days) rather than relying on ad-hoc `INFO` calls.

---

## Lua Script Compatibility Modes and Rollout Matrix

Lua script evolution is coordinated via the Lua Script Registry and must treat running Coordination Redis nodes (and their AOF history) as bounded but non-empty logs. To keep behavior predictable, script changes are classified into a small set of **compatibility modes**:

- `compatible`
  - Changes are backward- and forward-compatible with existing key shapes and `schemaVersion` ranges.
  - Examples:
    - Performance optimizations that do not change script return codes or key layout.
    - Adding support for a new `schemaVersion` while continuing to handle older versions.
  - Rollout:
    - May be deployed without coordination resets; normal rolling deployments are sufficient.

- `requires_region_reset`
  - Changes are safe only when region-scoped coordination state is cleared (for example, when key shapes change in a way that cannot be interpreted safely by both old and new scripts).
  - Examples:
    - Changing the internal structure of `tick:{tenantRegionTag}:pending` in a way that cannot be mapped from old shapes.
  - Rollout:
    - For each affected `<tenantId, regionId>`, coordinate:
      - Pause ticks for that region.
      - Perform a region-scoped reset as described in `system-architecture-redis-reset-and-recovery.md`.
      - Deploy the new script version and resume ticks.

- `requires_tenant_reset`
  - Similar to `requires_region_reset`, but scope is all regions for a tenant.
  - Examples:
    - Key shape or semantics changes that span all regions of a tenant and cannot be isolated per region.
  - Rollout:
    - Pause ticks for the tenant.
    - Perform a tenant-scoped reset.
    - Deploy the new script version for the tenant’s coordination workloads and resume ticks.

- `requires_cluster_reset`
  - Changes require coordination state to be cleared for all tenants/regions on the Coordination Redis deployment.
  - Examples:
    - Fundamental shifts in normalization or hash-tagging that cannot be migrated in place.
  - Rollout:
    - Plan a maintenance window.
    - Follow the cluster-scoped reset runbooks in this file and in `system-architecture-redis-reset-and-recovery.md`.

The Lua Script Registry (see `system-architecture-redis-lua-patterns.md`) records the compatibility mode for each script version. Operationally:

- CI should reject script changes that downgrade from a stricter mode to a looser one without an explicit design update.
- Script rollout plans must reference the compatibility mode and, where non-`compatible`, link to the corresponding reset runbook section (region, tenant, or cluster).

## Cache/Rate-Limit Redis Reset

**Goal:** Provide a simple, explicit runbook for resetting Cache/Rate-Limit Redis when cache state is corrupted, oversized, or needs to be flushed, without entangling it with Coordination Redis resets.

Cache/Rate-Limit Redis is designed to be **fully reset-tolerant** for the prefixes listed in the Cache/Rate-Limit Key Catalog in `system-architecture-redis-cache.md` and the reset policy matrix in `system-architecture-redis-reset-and-recovery.md`. A reset:

- Drops cache and rate-limit keys such as:
  - `inventory:<tenantId>:<containerId>`
  - `character-cache:<tenantId>:<characterId>`
  - `world-dynamic:<tenantId>:<aggregateId>`
  - `room:<tenantId>:<gameInstanceId>:<roomInstanceId>`
  - `view:room-look:<tenantId>:<gameInstanceId>:<roomInstanceId>`
  - `chat:*` (including `chat:city:*`)
  - `automation:queue:<tenantId>:*` / `automation:quota:<tenantId>:<scriptId>`
  - `ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]`
- Does **not** affect Coordination Redis keys (`tick:*`, `timer:*`, `retry:*`, `session:*`, `tick-executor-lease:*`, etc.).
- Increases load on backing services and PostgreSQL temporarily, but must not cause loss of authoritative game data.

### When to Reset Cache/Rate-Limit Redis

Typical triggers:

- A mis-keyed cache prefix caused unbounded growth or high eviction pressure that cannot be resolved quickly by configuration changes alone.
- Cache values are known to be corrupt (for example, a serialization bug that affected a whole environment).
- Rate-limit buckets need to be cleared as part of an incident response or configuration change (for example, after fixing an overly aggressive rate-limit policy).
- A one-time maintenance task requires verifying behavior starting from a cold cache (for example, measuring cold-start performance or DB load).

Before resetting:

- Confirm that the issue is confined to Cache/Rate-Limit Redis; Coordination Redis resets follow the scoped reset flows in `system-architecture-redis-reset-and-recovery.md` and **must not** be combined with cache resets by accident.
- Verify that affected services are prepared for a cold cache:
  - Entities, inventories, world snapshots, and chat history are durable in PostgreSQL.
  - Automation and rate-limit logic are designed to tolerate sudden loss of `automation:*` and `ratelimit:*` keys as described in the cache catalog.

### Runbook: Environment-Scoped Cache Reset

1. **Identify the Cache/Rate-Limit deployment**
   - Determine which Redis instance/cluster is wired via `FIREMUD_REDIS_CACHE_HOST` / `FIREMUD_REDIS_CACHE_PORT` (or `FIREMUD_REDIS_CACHE_URL`) for the target environment.
   - Double-check that `FIREMUD_REDIS_COORD_*` points to a **different** endpoint; if not, fix the misconfiguration first (see `system-architecture-redis-usage-and-profiles.md`).

2. **Assess impact and communicate**
   - Estimate which prefixes are used in this environment by consulting:
     - The Cache/Rate-Limit Key Catalog in `system-architecture-redis-cache.md`.
     - Any environment-specific notes in service READMEs (for example, chat TTLs, inventory cache usage).
   - Communicate expected effects to operators and, if relevant, players:
     - Temporary increases in DB and service read load (inventory, world snapshots, character graphs, chat history).
     - Reset of gateway rate-limit buckets and automation quotas (for example, short-term bursts may be allowed until quotas rebuild).

3. **Perform the reset**
   - For single-node deployments:
     - Stop Cache/Rate-Limit Redis or disconnect clients.
     - Use `FLUSHDB` on the cache database (or `FLUSHALL` only if the instance is dedicated exclusively to Cache/Rate-Limit Redis and does not share databases with other workloads).
     - Restart Redis and allow services to reconnect.
   - For clustered deployments:
     - Use a small, prefix-scoped reset tool/script that:
       - Iterates over the known cache prefix families from the catalog (for example `inventory:*`, `character-cache:*`, `world-dynamic:*`, `room:*`, `view:room-look:*`, `chat:*`, `automation:*`, `ratelimit:*`).
       - Deletes keys in bounded batches per shard/slot to avoid overwhelming the cluster.
     - Avoid full keyspace scans where possible; treat this as a maintenance operation with clear start/end times.

4. **Monitor after reset**
   - Watch:
     - Cache hit/miss metrics for the affected prefixes (for example `cache.inventory_*`, `cache.character_*`, `cache.world_dynamic_*`, `cache.room_*`, `cache.view_room_look_*`, `cache.chat_*`, and automation cache metrics).
     - DB and service read load for backing services (Entity Management, World Management, Social & Groups, Automation).
     - Gateway rate-limit behavior (`ratelimit:*` metrics) to ensure policy behavior returns to expected levels.
   - Confirm that:
     - No correctness regressions appear (for example, inventories and world state remain consistent; chat history falls back to PostgreSQL where required).
     - Cache key counts rebuild within the expected size/complexity budgets documented in `system-architecture-redis-cache.md`.

5. **Follow up**
   - If the reset was triggered by a mis-keyed prefix or configuration bug:
     - Fix the underlying design or configuration (for example, key shape, TTL, or payload size).
     - Update the Cache/Rate-Limit Key Catalog and relevant service README(s) to reflect the corrected prefix, TTL, and budgets.
   - If resets are becoming frequent for the same prefix family:
     - Revisit whether that workload is a good fit for Cache/Rate-Limit Redis at all, or whether it should be:
       - Demoted to in-memory caches inside the service, or
       - Promoted to a more explicit durable store or a different design.

## Redis Metrics Catalog

This section summarizes the **canonical Redis-related metrics** that other docs reference (Redis hub, reset model, incident runbook, and service designs). It is not exhaustive, but changes here should remain consistent with metric names used elsewhere.

### Coordination Redis – Core Metrics

These metrics support AOF targets, tail-loss SLOs, and basic coordination health:

- **AOF size and growth**
  - `redis_aof_current_size_bytes` (or platform-equivalent): per-node AOF size for Coordination Redis.
  - `redis_coordination_aof_growth_bytes_total` (custom): derived daily growth estimate based on sampled AOF sizes.
  - `redis_coordinator_restart_duration_seconds` (custom): restart duration for planned maintenance or scripted restarts.
- **Tail-loss and replay**
  - Tail-loss gauges/counters as defined in [Tail-Loss SLO Observability](#tail-loss-slo-observability), tagged by `<tenantId, regionId>`.
  - Error metrics from Lua scripts that signal replay or lease/lock issues (for example, `STALE_LEASE`, `STALE_LOCK`), as described in `system-architecture-redis-lua-patterns.md`.
- **Coordination key health**
- Size and count metrics for core prefixes such as `tick:{tenantRegionTag}:pending`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `session:game:<tenantId>:<gameInstanceId>:<sessionId>`, used to enforce the size and complexity budgets later in this file.
  - Oversize/over-budget counters referenced in the **Coordination Size and Complexity Budgets** section (for example, `redis_tick_pending_oversized_total`, `redis_tick_pending_effects_over_budget_total`, `redis_tick_command_queue_overflow_total`, `redis_tick_timers_over_budget_total`, `redis_session_payload_oversized_total`).

### Session Schema and Cleanup Metrics

The session schema and TTL cleanup flows in `system-architecture-redis-incident-runbook.md` and the session design in `system-architecture-redis.md` rely on:

- `session.cas_unsupported_schema_total` – counts CAS attempts against session keys with unsupported `schemaVersion` values.
- Cleanup job metrics:
  - `session.cleanup_scanned_total`
  - `session.cleanup_deleted_total`
  - `session.cleanup_duration_seconds`

These metrics help decide when to run schema/TTL cleanup jobs and to verify that cleanup has converged.

### Coordination & Tick Metrics Catalog

To make coordination and tick health observable in a consistent way across services, new features should reuse (or extend) the following metric names and tags. Names below use the Prometheus `snake_case` form expected in dashboards and alerts; dotted variants in other docs refer to the same metrics conceptually.

- **Coordination Redis / tail-loss**
  - `redis_coordination_tail_loss_ms{tenantId,regionId}` – observed tail-loss per `<tenantId, regionId>` compared to the SLO envelope. This is the canonical metric for “tail_loss_ms” used in SLO examples and runbooks.
  - `redis_coordination_used_memory_bytes{role="coordination"}` – memory used by Coordination Redis.
  - `redis_coordination_keys_total{role="coordination",prefix}` – approximate key counts per coordination prefix family (for example `tick`, `timer`, `retry`, `session`, `tick-executor-lease`).
- **Tick execution**
  - `tick_interval_ms{tenantId,regionId}` – effective tick cadence for each active region. This is a required companion metric for any environment that evaluates dynamic tail-loss or pause-budget rules derived from `tick_interval_ms`.
  - `tick_execution_time_ms_bucket{tenantId,regionId,le}` – histogram of tick execution time per `<tenantId, regionId>`.
  - Recording rules derived from this histogram should expose:
    - `tick_execution_time_ms_p95{tenantId,regionId}` – p95 tick execution time.
    - `tick_execution_time_ms_p99{tenantId,regionId}` – p99 tick execution time.
  - `tick_lock_ttl_ms{tenantId,regionId}` – gauge or recording rule representing the effective lock TTL for ticks in each region.
  - `tick_status{tenantId,regionId,status}` – one-hot gauge that encodes region state via a bounded `status` label (for example `status="RUNNING"|"PAUSED"|"STALLED"|"DEGRADED"`). Exactly one series per `<tenantId, regionId>` should be `1` at a time.
  - `tick_retry_queue_depth{tenantId,regionId}` – current depth of retry queues.
  - `tick_command_queue_depth{tenantId,regionId}` – aggregate per-region command queue depth.
  - `tick_current_id{tenantId,regionId}` – the last committed tick id for the region, used as a coarse tick progression watermark.
  - `tick_pending_oldest_id{tenantId,regionId}` – the oldest tick id still considered “in-flight” (pending, retrying, or awaiting ledger convergence), used to estimate effective loss/replay windows.
  - `tick_durable_commit_total{tenantId,regionId}` – count of ticks that reached the durable commit boundary (heartbeat/RegionStatus watermark).
  - `tick_coordination_cleared_total{tenantId,regionId}` – count of ticks whose coordination state reached the in-flight clearance boundary.
  - `tick_cleanup_lag_ms{tenantId,regionId}` – lag from durable commit to coordination-cleared for each tick; sustained growth indicates cleanup/recovery pressure even when durable commit continues.
- **Tick effect ledger**
  - `tick_effects_pending_total{tenantId,regionId}` – count of ledger rows with `status=SCHEDULED`.
  - `tick_effects_applied_total{tenantId,regionId}` – cumulative applied effects.
  - `tick_effects_abandoned_total{tenantId,regionId,reason}` – cumulative abandoned effects by reason (for example `RESET_REGION_SCOPED`, `RESET_TENANT_SCOPED`, `RESET_CLUSTER_SCOPED`, `EXPIRED`, `INVALID_TARGET`).
  - `tick_effects_pending_oldest_scheduled_timestamp_seconds{tenantId,regionId}` – helper metric recording the oldest `created_at` timestamp among SCHEDULED ledger rows for each region.
  - `tick_effects_pending_oldest_age_seconds{tenantId,regionId}` – recording rule for the current age of the oldest `SCHEDULED` row.
  - `tick_effects_replay_convergence_budget_seconds{tenantId,regionId}` – emitted replay-convergence budget for the region. Default formula: `max(60, ceil(20 * tick_interval_ms / 1000))`.
  - `tick_effects_replay_slo_breached{tenantId,regionId}` – recording rule indicating replay age has exceeded the emitted convergence budget.
  - `tick_effects_replay_scan_lag_ms{tenantId,regionId}` – replay-controller lag between “oldest replay-eligible `SCHEDULED` row” and latest replay scan for the region.
  - `tick_effects_replay_batches_total{tenantId,regionId}` – replay-controller batch executions used to verify fairness across regions and detect starvation.
  - `tick_effects_replay_starved{tenantId,regionId}` – recording rule indicating replay batches are not advancing despite pending work for longer than the emitted convergence budget.
- **Service-level tick replay metrics**
  - `gamesession_tick_replayed_total{tenantId,regionId}` – count of ticks that were replayed by the Game Session Service for each region (used to monitor how often idempotent recovery paths are exercised).
  - `gamesession_tick_executed_total{tenantId,regionId}` – count of ticks executed by the Game Session Service (denominator for replay ratios).
    These metrics are owned by Game Session but are treated as required for production deployments; additional services that want similar visibility should follow the same naming pattern (for example `<service>_tick_replayed_total` / `<service>_tick_executed_total`).
- **Split-brain / dual-leader detection**
  - `redis_coordination_dual_leader_detected_total{tenantId,regionId}` – count of detected dual-leader events for a region.
  - `redis_coordination_reset_total{scope}` – count of coordination resets by scope (`region`, `tenant`, `cluster`).

All these metrics and recording rules should, where cardinality allows, include tags such as:

- `tenantId`, `regionId`
- `redis_role` (for example `coordination`, `cache`)
- `region_epoch` (where appropriate)

#### Cardinality Policy (Required)

Metrics tagged by `tenantId` and `regionId` can become high-cardinality in multi-tenant or highly sharded deployments. To keep monitoring systems stable:

- Only emit per-`<tenantId, regionId>` time series for **active** regions (recently ticked or recently holding a lease) and/or cap to a bounded “top N worst regions” set for expensive histograms.
- Provide aggregated rollups alongside per-region views (for example service-level tick duration percentiles and “count of degraded/stalled regions” gauges) so dashboards and alerts do not require scanning every region label.
- Avoid introducing additional high-cardinality labels (for example per-command IDs) on these core coordination metrics; keep detailed investigations in logs/traces keyed by correlation IDs and `EffectId`.

This catalog does not preclude additional, service-specific metrics, but it provides a shared vocabulary for dashboards and alerts that tie together Redis, ticks, and ledger behavior.

### Cache / Rate-Limit Redis Metrics

Cache and rate-limit metrics complement the cache design in `system-architecture-redis-cache.md`:

- Basic Redis metrics from `INFO` or exporters:
  - `used_memory`, `maxmemory`, eviction counters, `keyspace_hits`, `keyspace_misses`, and `blocked_clients` per cache deployment.
- Prefix- or tenant-scoped cache metrics:
  - Approximate key counts and bytes per cache prefix (for example, `inventory:*`, `world-dynamic:*`, `view:room-look:*`, `chat:*`) so noisy cache prefixes can be identified when Cache Redis is under pressure.
- Rate-limiting:
  - Total active `ratelimit:*` keys per tenant.
  - Hit/miss or allow/deny counters per bucket/time window, aligned with the `ratelimit:<tenantId>:<bucket>:<timeWindow>[:<shard>]` patterns.

In addition, cache-aware services should expose per-prefix hit/miss counters and simple gauges:

- Example cache metrics:
  - `cache.character_cache_hits_total` / `cache.character_cache_misses_total`
  - `cache.inventory_hits_total` / `cache.inventory_misses_total`
  - `cache.room_hits_total` / `cache.room_misses_total`
  - `cache.view_room_look_hits_total` / `cache.view_room_look_misses_total`
- Example gauges and guards:
  - `cache.inventory_keys` (approximate number of active `inventory:*` keys).
  - `cache.room_keys`, `cache.world_dynamic_keys`, `cache.view_room_look_keys`.
  - Oversize counters such as `cache.inventory_oversized_payload_total` when payloads exceed configured size bounds.

Exact names may vary, but designs introducing new cache prefixes must:

- Declare at least one hit/miss counter pair for that prefix family.
- Document any key-count gauges or oversize counters that enforce the size/complexity budgets defined in the cache design.
- Describe how these metrics appear in dashboards/alerts so operators can see when a cache is ineffective (low hit rate) or under- or over-sized (runaway key counts, frequent oversize payloads).

Service and environment docs that introduce new Redis metrics should either:

- Reuse the names and patterns above, or
- Extend this catalog with new entries so the naming and semantics remain consistent across the Redis hub, incident runbook, and per-service designs.

Recommended AOF configuration profiles tie these targets back to concrete Redis settings:

| Profile | Use Case | Persistence Settings (example) | Notes |
| --- | --- | --- | --- |
| `dev_local` | Single-developer, non-player-facing experiments | `appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, small `maxmemory` tuned for laptop resources | Tail-loss and restart time are less critical; AOF is primarily for debugging. Coordination SLOs do not apply. |
| `hobby_self_hosted` | Small/self-hosted games with real players | `appendonly yes`, `appendfsync everysec` (or `no` with careful risk acceptance), `aof-use-rdb-preamble yes`, `maxmemory` sized to keep AOF replay within the 30–60s target and coordination keys well under memory caps | This profile is expected to honor the AOF size and restart budgets in this section. Tail-loss envelopes in the main Redis doc assume configurations in this tier or better. |
| `production_clustered` | Multi-tenant or higher-scale deployments | `appendonly yes`, `appendfsync everysec` (or platform-recommended fsync policy), `aof-use-rdb-preamble yes`, coordinated `maxmemory` and shard sizing so per-node AOF size and restart times stay within agreed budgets | Platform SLOs for coordination availability and replay are evaluated against this profile; deviation (for example, disabling AOF) must be treated as an explicit architectural change. |

Concrete values may be tuned per environment, but deployments should always document which profile they approximate and ensure that observability dashboards validate AOF size and restart behavior against the chosen profile’s expectations.

### Profile Selection in Standard Environments

To reduce ambiguity between conceptual profiles and concrete deployments:

- **Local development** (for example `docker/dev` or `./gradlew devUp`) is expected to approximate the `dev_local` profile:
  - `redis-coord` runs with AOF enabled and modest `maxmemory`, primarily for debugging and replay during development.
  - `redis-cache` runs as a separate process/container without shared volumes, aligned with the Cache/Rate-Limit role described in [Redis Cache & Rate Limiting](./system-architecture-redis-cache.md).
- **CI and preview stacks** typically use a variant of `dev_local` or an explicitly **ephemeral coordination** profile:
  - Coordination Redis may run with reduced or disabled AOF when tests are fully reset-tolerant.
  - Environments that intentionally treat coordination state as disposable must be labelled accordingly and must not be used to validate tail-loss SLOs or replay behaviour.
- **Hobby/self-hosted** environments should map closely to the `hobby_self_hosted` profile:
  - AOF enabled with `appendonly yes` and restart budgets tuned to the 30–60 second range described above.
  - Coordination and cache deployments separated as distinct services or pods, even when co-located on a single node.
- **Staging and production** must approximate the `production_clustered` profile:
  - Coordination Redis deployed with AOF and clearly defined shard sizing.
  - Role-specific endpoints (`FIREMUD_REDIS_COORD_*` and `FIREMUD_REDIS_CACHE_*`) documented per environment and surfaced in operator runbooks.

When adding new environments, explicitly state in their deployment docs which profile they approximate and how AOF, `maxmemory`, and clustering settings align with the targets in this section.

### Tail-Loss SLO Observability

The tail-loss envelope described in [System Architecture: Redis](./system-architecture-redis.md#redis-availability-consistency-and-safety-guarantees) must be observable, not just conceptual. Operators should wire alerts that connect coordination health directly to measurable signals:

- **Core metrics**
  - Tick progression and watermarks per `<tenantId, regionId>` (for example, `tick_current_id`, `tick_pending_oldest_id`).
  - Retry and command queue depths (`tick_retry_queue_depth`, `tick_command_queue_depth`) broken down by region.
  - Coordination Redis AOF size and growth metrics from above (`redis_aof_current_size_bytes`, `redis_coordination_aof_growth_bytes_total`).
  - Lua outcome counters, especially `"STALE_LEASE"`, `"STALE_LOCK"`, `"UNSUPPORTED_SCHEMA_VERSION"`, and explicit tail-loss/replay indicators if exposed.
- **Example alert conditions**
  - Repeated gaps where the effective loss window (difference between committed tick watermarks and last applied tick) exceeds **2×** the configured tick interval or **2 seconds**, whichever is larger, for a given `<tenantId, regionId>`.
  - Sustained growth in retry or pending queue depth for a region that suggests ticks are not draining and replay is backing up.
  - AOF growth consistently above the stated budget (for example, >500 MiB/day) combined with increasing restart times, which may widen practical tail-loss windows during planned maintenance.

These alerts should link directly to dashboards that show affected prefixes, regions, and tenants, so operators can decide whether to initiate a coordination reset, slow work for specific tenants, or adjust capacity.

### Coordination Metrics & Thresholds Contract

The metrics below form the **minimum contract** for Coordination Redis observability in player-facing environments. They tie directly into the coordination health model and reset/degradation behaviour.

> **Core vs extended signals**
>
> - **Core** metrics are required before hosting real players; they drive halt/degrade decisions and region health.
> - **Extended** metrics are recommended for diagnosis and tuning but not strictly required for small/self-hosted deployments.

| Metric | Threshold (example) | Duration | Tier | Operator action |
| --- | --- | --- | --- | --- |
| `redis_lua_script_load_failures_total` | ≥1 per shard | ~5m | Core | Mark affected shards degraded, pause ticks until scripts reload; investigate script registry/rollout issues. |
| `redis_lua_script_missing_for_region_total` | ≥1 per region | Immediate | Core | Stop scheduling ticks for that `<tenantId, regionId>` until every master hosting the region has the script loaded. |
| `redis_lua_script_runtime_ms_p99` | Sustained high share of lock TTL headroom (for example p99 script runtime > ~0.25 × `tick_lock_ttl_ms`) | ~3m | Core | Treat as an early warning for the canonical health model; degrade only when combined with tick-runtime and progress signals (`tick_execution_time_ms_p99 / tick_lock_ttl_ms`, stalled/cleanup lag), then slow fan-out and shed new commands until latency recovers. |
| `redis_tick_lock_ttl_exceeded_total` | >5 occurrences per region | ~5m | Core | Treat as a headroom breach; mark the region degraded, review workloads, and consider slowing ticks. |
| `redis_coordination_oom_errors_total` | ≥1 | ~1m | Core | Critical incident — halt ticks for affected regions, investigate `maxmemory`/payload sizes, and restore headroom before resuming. |
| `redis_tick_pending_stuck_total` | >0 for a region | ≥2 tick intervals | Core | Halt ticks for that region, inspect tick effect ledger, repair or reset coordination state, then resume. |
| `redis_tick_command_queue_overflow_total` | ≥1 per entity | Immediate | Extended | Treat as per-entity overload; shed or deny further commands for that entity until queue depth returns within budget. |
| `redis_tick_timers_over_budget_total` | >0 sustained per region | ~10m | Extended | Region has too many timers; either slow tick rate and/or reduce timer density via design changes. |
| `redis_session_payload_oversized_total` | ≥1 per tenant | ~10m | Extended | Audit session payload shape; strip non-essential fields and enforce size limits before writing session state. |

Deployments may tighten or relax these thresholds, but the **classes of behaviour** above—script unavailability, high script runtimes, repeated lock/TTL breaches, coordination `OOM`/evictions, stuck `pending` entries, and runaway queue/timer/session sizes—are treated as canonical red lines that justify automated degradation and paging.

### Coordination Size and Complexity Budgets

To keep Coordination Redis predictable and avoid single-key pathologies that blow AOF size or latency SLOs, FireMUD applies conservative envelopes to core coordination structures. These budgets are expressed in two layers:

- **Global safety invariants** – hard caps that are always enforced (for example, maximum per-entity queue depth and maximum per-region timer cardinality).
- **Alerting thresholds** – soft budgets that drive warnings and degraded-region behaviour but may be tuned per deployment.

New code is expected to honour both: never exceed the hard caps, and stay within the soft budgets unless a design explicitly extends them and updates this section.

- `tick:{tenantRegionTag}:pending` (per region, one in-flight tick)
  - Target maximum serialized payload size: roughly **32–64 KB**.
  - Target maximum staged effects per tick: on the order of **≤128** effect entries.
  - If either envelope is exceeded:
    - The tick executor logs a structured warning with `<tenantId, regionId, regionEpoch, tickId>` and approximate payload size/effect count.
    - Metrics such as `redis_tick_pending_oversized_total` and `redis_tick_pending_effects_over_budget_total` are incremented.
    - The region may be marked **degraded**; command intake can be throttled or shed until the workload is reduced.
- `tick:{tenantRegionTag}:queue:<entityId>` (per-entity command queue)
  - Global safety cap on queue depth per entity: **≤50–100** commands (exact value defined in shared configuration and applied uniformly).
  - When a queue reaches this cap:
    - New commands for that entity are **always** rejected or shed with a clear error (for example “queue full / region under load”) rather than growing unbounded queues.
    - A `redis_tick_command_queue_overflow_total` metric is incremented, tagged by `<tenantId, regionId>`, so operators can see which regions are hitting the cap frequently.
- `timer:{tenantRegionTag}` (per-region timer ZSET)
  - Global safety cap on timer cardinality per region: on the order of **a few thousand** timers (for example ≤10 000; exact value defined in shared configuration and applied uniformly).
  - Per-tick processing remains bounded by a configured `maxTimersPerTick` value; timers beyond that budget are processed in later ticks.
  - When cardinality or per-tick processing budgets are exceeded:
    - The region is marked **degraded** and emits `redis_tick_timers_over_budget_total` metrics.
    - Additional timer insertions beyond the hard cap are **shed** with a clear error or dropped according to simple, documented rules (for example, rejecting new timers for low-priority effects first).
- `session:game:<tenantId>:<gameInstanceId>:<sessionId>` (per-player session payload)
  - Target maximum serialized session value size: roughly **≤16–32 KB**.
  - Session payloads may contain routing, bindings, and lightweight metadata, but must not embed large blobs or full gameplay history.
  - When a session value exceeds the budget:
    - The binding logic logs a warning and increments `redis_session_payload_oversized_total`.
    - Implementations treat oversize sessions as an error in the caller; they may drop optional fields, reject binding, or force a fresh `LOGIN` rather than writing very large values.

These budgets complement the AOF size and restart targets in this document. Environments may tune concrete numeric thresholds, but the contract remains the same: coordination keys stay small and bounded, and exceeding a budget results in **explicit logs + metrics + controlled failure modes**, not silent degradation or unbounded growth.

### Runbook: AOF too large or restarts too slow

1. Confirm via metrics or `INFO`:
   - AOF size substantially exceeds the soft limit for the profile you are running (for example, > 2 GiB on a small self-hosted node), or
   - Restart time is routinely above 60 seconds for Coordination Redis nodes, or
   - Daily AOF growth is consistently above ~500 MiB/day per node without a clear explanation (for example, a deliberate large-scale test).
2. Schedule a maintenance window.
3. Stop game services for affected tenants/regions (or globally for a small/self-hosted deployment).
4. Begin the authoritative tick reset handshake for affected scope(s) before any Redis wipe:
   - Pause ticks/new command intake (if not already paused).
   - Bump `region_epoch` in PostgreSQL for affected regions so any surviving executors become stale by definition.
5. Reset Coordination Redis for the fenced scope:
   - Stop Redis.
   - Delete or recreate the volume that holds the AOF.
   - Start Redis with an empty keyspace and the desired AOF configuration (`appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, etc.).
6. Complete the remaining reset handshake steps:
   - Run scoped ledger reconcile so old-epoch `SCHEDULED` rows converge to terminal effect outcomes.
   - Converge accepted-but-unbound command records to terminal command status with explicit `executionOutcome` / `gameplayResult` values.
   - Reinitialize `tick:{tenantRegionTag}:meta` from durable baselines.
   - Run the post-reset smoke check before resuming traffic.
7. Resume ticks and player traffic once services are healthy.
   - Expect players to re-login or restart games.
   - Coordination state is rebuilt from PostgreSQL and fresh gameplay activity.

If metrics show **spiky but short-lived** AOF growth (for example, a load test that briefly increases AOF by a few hundred MiB and then stabilizes), you may choose to defer a reset until the next planned maintenance window. Sustained, unexplained growth or restart times outside the budget should be treated as signals of either:

- Misuse of Coordination Redis (for example, using it as a general-purpose cache or log), or
- A need to raise capacity or move to a more appropriate Redis profile as described in the main Redis architecture doc.

Manual AOF “surgery” is **not supported**. Either the AOF is trusted and replayed as-is, or it is discarded and Redis restarts from a clean keyspace.

### Rule-of-Thumb Coordination Capacity per Region

**Goal:** Give operators a simple mental model for when a single `<tenantId, regionId>` is likely exceeding healthy coordination usage.

These are approximate guidelines for typical tick intervals (for example `tick_interval_ms >= 250`) and modestly sized worlds on a small/self-hosted deployment. Larger clusters with more memory and CPU can scale beyond these values, but **ratios and trends** remain useful signals.

- **Per-region coordination footprint (steady state)**
  - Active entity locks: typically **≤ a few hundred** per region; spikes are expected during busy ticks but should not remain at thousands for long durations.
  - Pending tick entries: on the order of **a few ticks worth of work**, not thousands of uncommitted `pending` entries.
  - Timers and retry queue items: typically **≤ tens of thousands** per busy region; consistently higher counts indicate that timers or retries are being used as general-purpose data stores.
  - Session keys: roughly **one key per active session** for that region, expiring when sessions end or age out.
- **Operator guidance**
  - If a single `<tenantId, regionId>` routinely exceeds these envelopes and is responsible for a disproportionate share of memory usage or AOF growth:
    - Review gameplay and automation features for that tenant/region to ensure they are not using Coordination Redis for long-lived data.
    - Prefer simple global caps and admission-control limits first; **per-tenant caps** on active regions, sessions, timers, or queued commands are treated as advanced tuning knobs that require explicit design review and documentation.
    - If mis-keyed or runaway coordination state is suspected, use the relevant coordination reset runbooks (either per-region or per-tenant) to drop volatile state and rebuild from PostgreSQL.

These rules of thumb are intentionally conservative. For a single-admin hobby deployment, they help distinguish “normal busy evening” from “this one tenant/region is using Redis in a way the architecture did not intend”.

---

### Session Schema Cleanup and Large Keyspaces

Session schema cleanup is a **hygiene and recovery tool**, not a required part of normal operation. It is used when session payloads change shape or when unknown `schemaVersion` values appear frequently in metrics:

- The primary safety mechanisms for sessions are:
  - TTL-based expiry governed by the derived `session_expiration_ms` window (`FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`).
  - The CAS script’s conservative behaviour for unknown `schemaVersion` values (no mutation + explicit `"UNSUPPORTED_SCHEMA_VERSION"` outcome and metrics).
- In steady state, it is acceptable to:
  - Rely on TTL for natural drainage of older or unknown-version sessions.
  - Treat occasional `"UNSUPPORTED_SCHEMA_VERSION"` results as deployment-mismatch signals that prompt rollout fixes rather than continuous keyspace scrubbing.

When runbooks call for explicit cleanup (for example, after a major schema change or to address a large number of unknown-version sessions in a specific tenant), cleanup tools must be designed for **large keyspaces**:

- **Scope**
  - Operate on a **per-tenant prefix** such as `session:game:<tenantId>:*`; avoid scanning the entire Redis keyspace when only some tenants are affected.
  - Prefer targeted selectors (for example, `session:game:<tenantId>:*` with filters inside the tool) over global `SCAN` patterns.
- **Bounded SCAN usage**
  - Run **at most one cleanup worker at a time** per Coordination Redis deployment; do not schedule overlapping cleanup jobs for multiple tenants or run them from multiple services concurrently.
  - Use Redis `SCAN` with modest `COUNT` values (for example, 100–1000) to avoid long blocking periods.
  - Enforce a maximum runtime per invocation (for example, 10–30 seconds) and exit cleanly when the time budget is exhausted; subsequent runs resume from the last cursor or continuation token.
  - Insert small delays between batches so scans do not monopolize CPU or I/O on the Redis node; cleanup jobs are treated as **maintenance tasks**, not continuous background load.
- **Observability**
  - Emit metrics such as `session.cleanup_scanned_total`, `session.cleanup_deleted_total`, and `session.cleanup_duration_seconds` to capture how much work the cleaner performs and its impact.
  - Log tenant identifiers and approximate key counts so operators can correlate cleanup activity with changes in memory usage and `"UNSUPPORTED_SCHEMA_VERSION"` metrics.
- **Coordination**
  - Cleanup jobs acquire a short-lived coordination lock (for example `session-cleanup-lock:<tenantId>`) before scanning and release it when they pause; this ensures only one cleanup worker touches a tenant’s keyspace at a time and prevents conflict with normal session scripts.
  - Cleaners throttle their work by yielding after each batch (for example sleeping a few milliseconds or waiting for a small timer) when throughput is high, and they stop/exit gracefully if they detect elevated Redis latency or when another maintenance job holds the same lock.
  - Cleanup and maintenance tooling must respect the same per-region/tenant boundaries as production scripts, log every key or prefix they modify (with `tenantId` / `regionId` context), and expose a dry-run mode so operators can preview the impact before making changes.

Default runbooks should prefer **fixing deployments** (aligning scripts and writers) and relying on TTL over running aggressive cleanup jobs. Session cleanup tools remain available for exceptional cases where operators explicitly choose to trade short-lived overhead for faster convergence of session keyspace to a new schema.

### Maintenance Job Coordination

Several Redis maintenance flows (session cleanup, scoped coordination resets, normalization migrations, unknown-prefix scanning, and split-brain recovery) can place non-trivial load on Coordination Redis. To keep their behavior predictable:

- **Single maintenance actor per deployment**
  - The Logging & Admin Service (or an equivalent control-plane component) is the single orchestrator for Redis-heavy maintenance Jobs.
  - Ad-hoc scripts must not start independent cleanup or reset loops; they should delegate to the versioned maintenance CLI and its entrypoints.
- **Cross-job coordination**
  - Each job type uses its own fine-grained locks (`session-cleanup-lock:<tenantId>`, `coord-reset:{tenantRegionTag}`, etc.), but the control plane additionally enforces that **only one Redis-intensive maintenance job** runs at a time per deployment.
  - When a job is active, dashboards and health endpoints should expose a simple “maintenance in progress” signal so operators know not to schedule another heavy task.
- **Backoff on elevated load**
  - All maintenance jobs (including scanners) must monitor basic Redis health (latency, `used_cpu_sys`, `used_memory`, and error rates) and pause or abort early when the node is already under pressure.

Docs that introduce new maintenance flows must reference this section and describe how their jobs participate in the shared coordination model.

### Runbook: Explicit Coordination Reset (Full Wipe)

**Goal:** Provide a clear mechanism for deliberately starting Coordination Redis from an empty keyspace while keeping the normal posture “AOF persists across rollouts”.

This full wipe is intentionally **rare** – it is used for controlled scenarios such as:

- Validating reset-tolerant behavior in a test or preview environment.
- Recovering from mis-keyed coordination prefixes where dropping state is acceptable.
- Applying a Lua change classified as `requires_cluster_reset`.

The steps mirror the “AOF too large” runbook but are driven by operator intent rather than metric thresholds:

1. **Plan scope and reset mode**
   - Decide whether you need:
     - A **scoped coordination reset** (region/tenant) using the Coordination Reset Model handshake in `system-architecture-redis-reset-and-recovery.md`, or
     - A **full wipe** of a Coordination Redis deployment (this runbook).
   - Use scoped reset by default. Use full wipe only when scope cannot be safely isolated or when `requires_cluster_reset` compatibility demands it.
   - For reset-sensitive prefixes (for example `session:*`), require explicit operator sign-off and player-impact communication before proceeding.
   - Verify that **Coordination Redis and Cache/Rate-Limit Redis are distinct deployments**. Coordination reset tooling and jobs must refuse to run if `FIREMUD_REDIS_COORD_HOST:PORT == FIREMUD_REDIS_CACHE_HOST:PORT`, since a reset in that topology would also discard cache/rate-limit state and violate the role separation guarantees.
2. **Fence the timeline before any Redis wipe**
   - Execute the first two steps of the authoritative reset handshake from `system-architecture-redis-reset-and-recovery.md`:
     - Pause ticks and stop accepting new gameplay commands for the affected scope using the Game Session admin/control APIs.
     - Bump `region_epoch` in PostgreSQL for the affected scope so any surviving executors are stale by definition.
   - Wait until no executor in the target scope is allowed to create new durable tick batches or new coordination keys under the old epoch.
3. **Run reset tooling (storage-level wipe)**
   - For Kubernetes/Helm deployments:
     - Run the coordination-reset Job or script provided with the charts (for example, the `redis-aof-reset` Job under `charts/firemud/templates/redis-aof-reset-job.yaml`), which:
       - Stops or disconnects the target Redis instance.
       - Deletes or recreates the PersistentVolume/volume contents that hold the AOF.
       - Restarts Redis with the desired AOF configuration (`appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, etc.).
   - For local dev / Docker Compose:
     - Use the dedicated Gradle task or helper script (for example, `./gradlew devRedisReset` once implemented) that:
       - Stops the dev stack.
       - Clears the Redis data directory/volume used for Coordination Redis.
       - Restarts the stack so Redis comes up with an empty coordination keyspace.
4. **Complete the remaining reset handshake**
   - Before resuming gameplay, run the remaining control-plane reset steps for the affected scope:
     - Reconcile old-epoch `SCHEDULED` ledger rows to terminal `APPLIED`/`ABANDONED`.
     - Converge accepted-but-unbound command records to `TERMINAL` with `executionOutcome = LOST_BEFORE_STAGING`.
     - Reinitialize `tick:{tenantRegionTag}:meta` (`region_epoch`, `current_tick_id`) from durable baselines.
5. **Verify health**
   - Ensure Coordination Redis is reachable and scripts preload successfully (no persistent `NOSCRIPT` errors).
   - Run a lightweight smoke test:
     - Schedule a tick for a test region.
     - Confirm that locks, `pending`, and timers can be created and cleared as expected.
6. **Resume gameplay**
   - Unpause ticks and re-enable command intake for the affected tenants/regions.
   - Expect players to re-login or restart games where reset-sensitive prefixes were dropped; coordination state (locks, queues, timers, and possibly sessions) is rebuilt from PostgreSQL and fresh tick activity.

This runbook intentionally reuses the same order as the authoritative reset handshake. Any storage-level wipe that occurs before pause + epoch fencing is considered an invalid reset sequence and must not be used in production-like environments.

Normal Helm upgrades and restarts **do not** run this reset by default. The reset is always an explicit, operator-driven action guarded by this runbook so that “AOF persists across rollouts” remains the common case.

## Reset Tolerance Classes

Not all Redis-backed workloads tolerate coordination resets equally. FireMUD therefore classifies coordination prefixes and features by **reset tolerance**:

- **Reset-tolerant** – workloads that can safely discard their Redis state and rebuild from PostgreSQL and fresh activity using the idempotent replay rules:
  - Tick locks, `pending` entries, timers, retry queues, and conflict metadata.
- **Reset-sensitive** – workloads where a reset is acceptable but has visible impact and may require explicit operator sign-off or tenant scoping:
  - Gameplay/auth session prefixes (`session:game:*`, `session:auth:*`) where reset may force re-login or re-authentication.
  - Certain automation queues or non-critical analytics that can be recomputed or re-enqueued.
- **Reset-forbidden** – workloads that must not be dropped by generic coordination reset tooling:
  - Future features that treat Redis as a durable component of a long-lived contract (for example, replay timelines, high-value automation contracts, or analytics streams that cannot be recomputed).

Today, Coordination Redis is intentionally used for reset-tolerant workloads plus explicitly documented reset-sensitive session prefixes (`session:game:*`, `session:auth:*`). Any new feature that wants to use Coordination Redis must explicitly declare its reset tolerance class in design docs and, where necessary, use:

- Separate deployments or prefixes with their own runbooks, or
- Stronger durable stores (for example PostgreSQL or Kafka) as the primary record of long-lived streams, with Redis limited to cache/index roles that remain reset-tolerant.

Reset tooling and runbooks in this document apply **only** to reset-tolerant workloads unless explicitly noted.

## Session TTL & Reset Operator Flows

Session lifetimes and coordination resets interact in predictable ways. The table below captures recommended operator flows for common scenarios; refer to [Environment & Secrets – Authentication Variables](./infrastructure/environment-and-secrets.md#authentication) and [Redis Architecture – Session Keys and Gameplay Binding](./system-architecture-redis.md#session-keys-and-gameplay-binding) for full details.

| Scenario | Steps | Redis impact | Player behavior | Optional cleanup |
| --- | --- | --- | --- | --- |
| Decrease JWT/session TTL without reset | Lower `FIREMUD_AUTH_JWT_EXPIRATION_MS` and/or `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`, roll out services. | New `session:game:<tenantId>:<gameInstanceId>:<sessionId>` and `session:auth:<scope>:<tokenHash>` keys (for example `session:auth:account:<accountId>:<tokenHash>` and `session:auth:tenant:<tenantId>:<tokenHash>`) get shorter TTLs; existing keys keep their original TTL until they expire naturally. | Existing sessions continue until their original TTLs; new logins and reconnects enforce the tighter lifetime immediately. | Not required. Operators may optionally run per‑tenant session cleanup (delete `session:game:<tenantId>:*`) to accelerate convergence if memory is tight. |
| Decrease JWT/session TTL and intentionally force reconnect | Same as above, then proactively delete session keys for selected tenants/regions (for example, `session:game:<tenantId>:*`) during a maintenance window. | Coordination Redis drops affected session keys immediately; memory for those sessions is reclaimed at once. | All affected players must log in again; reconnect attempts for deleted sessions behave like expired sessions. | Recommended when making a large TTL reduction and wanting a clean cut-over or when reclaiming session memory quickly. |
| Full coordination wipe with many active sessions | Follow the **Explicit Coordination Reset (Full Wipe)** runbook; all coordination prefixes, including `session:*`, are dropped for the affected deployment/scope. | Coordination Redis restarts with an empty keyspace for coordination prefixes (locks, timers, queues, `session:*`, etc.). | Gameplay sessions are terminated; players must log in again and sessions are recreated under the new coordination state. | No separate session cleanup is needed; the full wipe drops `session:*` keys. Operators should communicate expected reconnect behavior to players and monitor memory/latency as sessions rebuild. |

### Cluster-Safe Session Cleanup Procedure (No `KEYS`)

When runbooks call for per-tenant session cleanup (for example, deleting `session:game:<tenantId>:*` to accelerate a TTL cut-over), the procedure must be safe for Redis Cluster and large keyspaces:

- Never use `KEYS session:game:<tenantId>:*`.
- Iterate over **each master node** and run `SCAN` with `MATCH session:game:<tenantId>:*` using small `COUNT` values, strict time budgets per run (for example 10–30 seconds), and rate limiting between batches.
- Delete via `UNLINK` (preferred) to keep deletions non-blocking; fall back to `DEL` only when `UNLINK` is unavailable.
- Treat cleanup as a maintenance job: run one worker at a time per deployment/tenant, emit metrics, and stop early if Redis latency is elevated.

## Lua Compatibility Registry & Script Upgrades

**Goal:** Roll out Lua script changes safely, knowing when coordination state must be reset.

### Inputs

The **Lua Compatibility Registry** lives in the shared `firemud-common` module alongside key builders and Lua descriptors. It is owned by the **platform/coordination maintainers** (not individual services) and declares, per script:

- `schemaVersionsSupported`.
- `KEYS`/`ARGV` contract.
- `outcomesSupported` (explicit, low-cardinality outcome enum) and per-outcome caller policy (retryable, terminal, fatal-on-unknown).
- A compatibility tag and rationale:
  - `compatible` – the new script is **behavior-preserving** for all `(KEYS, ARGV, Redis state)` combinations produced by current services and supported `schemaVersion` values.
  - `requires_region_reset` / `requires_tenant_reset` / `requires_cluster_reset` – the new script changes behavior for existing state or inputs (including AOF replay of old calls) and therefore requires the corresponding reset scope (or explicit multi-version/migration strategy).
- Optional metadata such as:
  - A brief **compatibility rationale** (for example, “refactor only; behavior verified via golden tests”).
  - The minimum/maximum `schemaVersion` values known to exist in production deployments.

For the purposes of this registry, **`compatible` is intentionally narrow**:

- It does **not** allow changes that:
  - Alter return codes for any valid input.
  - Turn a previous no-op into a mutating path (or vice versa).
  - Change how existing `schemaVersion` payloads are interpreted when replayed from AOF.
- It only allows:
  - Internal refactors that preserve both return values and key mutations.
  - Additional observability (metrics, logs) that does not affect control flow.
  - Targeted bug fixes where the previous behavior was already *outside* the documented contract; such fixes must be explicitly called out in the rationale.

All other changes must be tagged with the appropriate `requires_*_reset` scope (or accompanied by explicit multi-version handling and data migration for affected keys).

#### Concrete examples: `compatible` vs `requires_*_reset`

To make the boundary less subjective, use these examples as guidance:

- Changes that are **not compatible** (must be `requires_region_reset`, `requires_tenant_reset`, or `requires_cluster_reset`, or multi-version), even if they “feel minor”:
  - Changing a script’s return code for any valid input (for example, from `"OK"` to `"ALREADY_APPLIED"`), because callers and AOF replay may observe different outcomes.
  - Turning an error/early-return path into a mutating path (for example, previously returning `"STALE_LOCK"` without writes, now attempting a best-effort recovery write).
  - Reinterpreting existing `schemaVersion = N` payload fields (for example, changing how a flag or counter is mapped to behavior) without first draining or migrating data written under the old semantics.
  - Introducing new keys or members that would be created on AOF replay for historic entries (for example, emitting additional ZSET members for already-processed ticks).
- Changes that can be **compatible** when proven by tests:
  - Pure refactors that reorder internal logic but, under golden tests, produce identical key mutations and return codes for all supported `schemaVersion` fixtures and `(KEYS, ARGV)` combinations.
  - Adding **extra observability only** (for example, incrementing a metrics counter or emitting structured logs) without branching on those signals.
  - Fixing behavior that was already outside the documented contract (for example, a bug where a script sometimes failed to enforce a documented `STALE_LEASE` check) when the compatibility rationale calls this out explicitly and golden tests cover both before/after states.

When in doubt, default to the smallest safe `requires_*_reset` scope or introduce explicit multi-version handling; optimistic “this is probably compatible” classifications without golden tests are not acceptable.

### Runbook: Upgrading scripts

1. Classify changes:
   - For each modified script, decide whether the change is intended to be behavior-preserving (`compatible`) or intentionally changes semantics (`requires_region_reset`, `requires_tenant_reset`, `requires_cluster_reset`, or multi-version).
   - For any script tagged `compatible`:
     - Update the registry rationale to describe why it is compatible.
     - Add or update **compatibility tests** in `firemud-common` that exercise a representative set of Redis fixtures (including edge cases and partially applied states) and prove that running the old script vs the new script with the same `(KEYS, ARGV)` yields identical:
       - Return values, and
       - Key mutations for all keys in the script’s descriptor.
     - For outcomes documented as non-mutating, include assertions proving zero writes (including no TTL refresh side effects).
     - Include a caller-contract check: unknown outcomes are treated as fatal by call sites and are surfaced via metrics/alerts.
     - CI must run these golden tests and fail if any observable behavior diverges.
   - For scripts tagged `requires_*_reset` or scripts that introduce multi-version handling, document the upgrade expectations in the registry (for example, “v2 adds support for schemaVersion=3; old data must be drained or migrated before support for schemaVersion=1 is removed”).
2. Run the **coordination upgrade planner** (dev-tools):
   - Compares the current deployment’s registry to the target version.
   - Reports whether a **coordination reset** is required based on scripts tagged `requires_*_reset` and any recorded multi-version windows that have closed.
3. If all changes are `compatible` and their compatibility tests pass:
   - Deploy new scripts and services as part of the normal rollout.
   - Rely on existing `NOSCRIPT` handling and Lua preload behavior.
4. If any script is tagged `requires_*_reset` (and not covered by a live multi-version strategy):
   - Use the upgrade planner’s reset plan at the **smallest required scope**:
     - `requires_region_reset`:
       1. Pause ticks/intake for affected `<tenantId, regionId>` only.
       2. Run region-scoped coordination reset tooling plus tick reset handshake for those regions.
       3. Verify scripts and smoke ticks for affected regions, then resume scoped traffic.
     - `requires_tenant_reset`:
       1. Pause ticks/intake for affected tenant(s).
       2. Run tenant-scoped reset tooling plus handshake and scoped ledger reconcile.
       3. Verify and resume tenant traffic.
     - `requires_cluster_reset`:
       1. Pause ticks/intake for the deployment scope.
       2. Stop Coordination Redis instance(s).
       3. Delete/recreate AOF volume(s) or bring up a fresh deployment.
       4. Restart Redis with empty coordination keyspace and preload scripts.
       5. Run cluster-scope handshake and health checks before resuming traffic.
   - Full deployment AOF wipe is valid only for `requires_cluster_reset` (or explicit operator decision outside compatibility flow with incident sign-off).
5. Confirm reset safety before resuming:
   - Verify the tick effect ledger reports no `SCHEDULED` rows for the affected `(tenantId, regionId)` pairs.
   - Ensure any in-flight commands are either retried or marked `ABANDONED` at the domain layer.

The registry, together with its golden compatibility tests in `firemud-common`, remains the **single source of truth** for whether coordination state can be safely replayed across script versions (`compatible`) or must be reset (`requires_*_reset` or explicit migration).

---

## Replica Promotion and Missed Writes

**Goal:** Handle Redis replica promotion without violating tick/replay guarantees.

### Facts

- Coordination Redis uses **asynchronous replication**.
- A promoted replica may be missing recent coordination writes.
- The **new primary’s keyspace is authoritative** after promotion.

### Behavior

- Promotion from a replica with modest lag is equivalent to a small AOF tail-loss window:
  - Missing keys are treated as if they never existed.
  - Ticks/retries/timers are re-enqueued from surviving state/PostgreSQL or skipped within the accepted tail-loss envelope.
- Replay safety is preserved because:
  - Mutating scripts validate lease tokens, lock tokens, `tickId`, and `region_epoch` before writing.
  - The tick effect ledger and idempotency guards in PostgreSQL remain the source of truth for “has this effect applied?”

### Lag envelopes (tie lag to tick interval)

- **Target:** p99 replication lag < ~0.25 × `tick_interval_ms`.
- **Warning:** sustained lag between ~0.25 × and 1.0 × `tick_interval_ms`.
- **Red line:** lag ≥ 1× `tick_interval_ms` for a shard.

### Runbook: Promotion decisions

1. Monitor replication lag via metrics (see Observability section in the main Redis doc).
2. If lag is within the **target** envelope:
   - Automatic or manual promotion is acceptable from a replay perspective; expect at most ~one tick of lost coordination state.
3. If lag is in the **warning** band:
   - Investigate underlying causes (capacity, network, slow scripts) and consider delaying promotions until lag recovers.
4. If lag crosses the **red line** for a shard:
   - Avoid automatic promotion from that replica.
   - Either:
     - Wait for lag to return to the target envelope, or
     - Treat promotion as a **deliberate “drop recent coordination state” event**:
       - Pause ticks for affected tenants/regions.
       - Promote the replica.
       - Use coordination reset tooling to rebuild state from PostgreSQL and new activity.

Smaller self-hosted deployments may prefer a single primary (with an optional replica only for observability/manual promotion) and treat any promotion with significant lag as equivalent to resetting coordination state for affected regions.

---

## Key Shape Mistakes and Coordination Resets

**Goal:** Remediate mis-keyed or mis-sharded coordination keys without complex in-place surgery.

**Coordination keys** (`tick:*`, `timer:*`, `retry:*`, `remote:*`, leases, and tick-related locks) are treated as reset-tolerant, volatile, and backed by PostgreSQL + replay.

Before performing any coordination reset (region/tenant/cluster scope), operators should walk a short **pre-reset validation checklist**:

- Confirm that PostgreSQL is healthy:
  - Core tables for tick coordination (for example, tick effect ledger, `coordination_meta`/leadership tables) are reachable and not reporting corruption or constraint failures.
  - Saga state for workflows that depend on coordination (for example, game startup/shutdown) is in a consistent state or can tolerate replay from the last committed step.
- Verify tick effect ledger status for the target scope:
  - Identify `SCHEDULED` (and any legacy `IN_PROGRESS`) tick effects for the affected `(tenantId, regionId, region_epoch)` combinations so that the ledger reconcile step can drive them to `APPLIED` or `ABANDONED` as part of the reset handshake.
- Ensure game traffic is quiesced for the affected scope:
  - Tick scheduling and new command intake are paused for the relevant `<tenantId, regionId>` or tenants.
  - Any long-running maintenance or backfill jobs that depend on coordination keys are stopped.
- Record operator intent:
  - Capture which tenants/regions are being reset, why the reset is needed, and which Redis deployment/role is affected, so the action is auditable alongside normal break-glass events.

Only after these checks pass should a reset proceed; if any item cannot be satisfied, treat the situation as an incident and resolve the underlying domain/database issues before discarding coordination state.

### Scoped Tick Effect Ledger Reconcile

Every coordination reset that affects tick execution (region/tenant/cluster scope) must include a **tick effect ledger reconcile** step that makes the ledger converge for the old epoch:

- The reset tooling invokes a scoped reconcile routine (for example a `coord-maint tick-ledger-reconcile` subcommand) that:
  - Selects tick effect ledger rows and cross-region follow-up rows for the affected `(tenantId, regionId, region_epoch)` combinations with `status = SCHEDULED` (and any legacy `IN_PROGRESS`).
  - For each row, determines whether the corresponding effect has already been durably applied by consulting domain state, and:
    - Marks rows `APPLIED` where domain state clearly reflects the intended effect.
    - Marks rows `ABANDONED` with a reset-specific reason (for example `RESET_REGION_SCOPED`, `RESET_TENANT_SCOPED`, or `RESET_CLUSTER_SCOPED`) where the effect is no longer valid or cannot be safely replayed.
- New executors **do not** resume `SCHEDULED` work from the old epoch:
  - Any re-drive across epochs is performed only by explicit, feature-specific tooling that reads the old-epoch rows and re-creates fresh effects in the new epoch under well-documented rules.

This reconcile step is the concrete enforcement of the convergence guarantees in the tick failures/operations doc: for each `(tenantId, regionId, region_epoch, tickId, effectKey)` the ledger eventually reaches a single terminal state (`APPLIED` or `ABANDONED`), even when coordination state is dropped.

### Runbook: Mis-sharded coordination keys

1. Detect the issue:
   - Hash-tag or key-shape mistakes discovered via CI, logs, or metrics (for example, CROSSSLOT errors or inconsistent region placement).
2. Pause affected workloads:
   - Pause tick scheduling globally or for affected tenants/regions.
3. Reset Coordination Redis for the affected scope:
   - For node-wide issues: reset the Coordination Redis keyspace (per-node) using the AOF reset procedure, but only for reset-tolerant prefixes.
   - For tenant/region-local issues: use tooling that targets only those prefixes or logical databases; reset-sensitive workloads may require additional confirmation or tenant scoping, and reset-forbidden workloads must be excluded.
4. Resume ticks and sessions:
   - Allow coordination keys to rebuild naturally from PostgreSQL and fresh gameplay activity.

Fine-grained, live migration of mis-sharded coordination keys is **not** the default; it is considered an advanced, optional extension when dropping coordination state is unacceptable for particular tenants.

### Key Enumeration Strategy for Scoped Resets (Cluster-Safe)

Redis Cluster does not provide a cheap, precise way to “list all keys in a hash slot” or “list all keys for a given hash tag”. Scoped reset tooling therefore relies on **prefix-scoped SCAN per master** under strict operational preconditions.

Region-level resets (targeting one `{tenantRegionTag}`) enumerate keys as follows:

1. **Pause the region**: tick scheduling is stopped for the affected `<tenantId, regionId>` and executors release `tick-executor-lease:{tenantRegionTag}`.
2. **Acquire a reset lock**: tooling acquires a short-lived “reset in progress” lock scoped to the region (for example `coord-reset:{tenantRegionTag}`) so two resets cannot run concurrently for the same region.
3. **Enumerate by known prefix families**: tooling does not attempt to “discover” arbitrary keys; it scans only the explicitly cataloged families for that region, for example:
   - `tick:{tenantRegionTag}:*` (locks, queues, pending, and any other tick-local keys)
   - `timer:{tenantRegionTag}` (and any supporting timer keys that share the same tag)
   - `retry:{tenantRegionTag}`
   - `tick-executor-lease:{tenantRegionTag}`
4. **SCAN per master node**: for each master in the cluster, run `SCAN` with `MATCH <pattern>` and a modest `COUNT` (for example 100–1000), respecting strict time budgets (for example 10–30 seconds per invocation) and rate limiting between batches so the reset job cannot monopolize Redis CPU or I/O.
5. **Delete with `UNLINK`**: delete discovered keys via `UNLINK` (preferred) or `DEL` (fallback) so deletions do not block the Redis event loop for large values.
6. **Repeat until stable**: because the cluster can still accept background writes, the tool may need multiple passes. The precondition “ticks paused for the region” is what makes convergence practical: new keys for that `{tenantRegionTag}` should not be created while the reset runs.

Operational risks and tradeoffs:

- Even scoped `SCAN` is still O(keys scanned) and can add latency if run without budgets; reset tooling must always be rate-limited and observable.
- Maintaining a per-region “key index set” to make deletes exact is intentionally not part of the baseline design: it adds continuous write amplification and creates another correctness-critical structure that itself would need reset semantics.

Tenant- and cluster-scoped resets follow the same principles but target broader sets of prefixes and possibly multiple `{tenantRegionTag}` values. For all scopes, reset tooling should rely on the central reset policy matrix (`system-architecture-redis-reset-and-recovery.md`) and shared key builders rather than ad-hoc key name patterns.

### Unknown-Prefix Detection and Hygiene

Scoped reset tooling intentionally operates over **known prefix families** only; it does not attempt to discover arbitrary keys. To catch mis-keyed or unexpected prefixes before they cause problems:

- A lightweight **unknown-prefix scanner** periodically:
  - Uses `SCAN` with conservative `COUNT` values over the Coordination Redis keyspace.
  - Compares discovered key prefixes against the canonical catalogs:
    - Reset policy matrix and coordination catalogs in `system-architecture-redis-reset-and-recovery.md`.
    - Cache/Rate-Limit key catalog in `system-architecture-redis-cache.md`.
  - Emits metrics (for example, `redis_unknown_prefix_keys_total` tagged with the raw prefix) and logs samples of unknown keys.
- The scanner must:
  - Run at most one worker per deployment and treat itself as a low-priority **maintenance job** with strict runtime and rate limits similar to session cleanup.
  - Never mutate keys; it is purely observational.
  - Be wired into incident runbooks so repeated unknown-prefix growth prompts design review and, where appropriate, a targeted reset or one-off migration Job.

This detector does not replace the canonical catalogs; it acts as a guardrail that surfaces drift between implementation and design.

## Dual-Leader Detection & Coordination Reset

**Goal:** Detect Redis split-brain or conflicting primaries for coordination slots and recover via a coordinated reset before duplicate logical effects can escape the tick subsystem.

### Signals

- Repeated `STALE_LEASE`, `UNSUPPORTED_EPOCH`, or other Lua script responses that reference inconsistent `region_epoch` values for the same `<tenantId, regionId>`.
- PostgreSQL epoch validation rejecting writes because a second executor attempted to bump the same `coordination_meta` row with an older epoch.
- Redis/Sentinel/Cluster alerts showing simultaneous primaries for the same hash slot or other signs of split-brain.
- Explicit dual-leader metrics such as `redis_coordination_dual_leader_detected_total{tenantId,regionId}` raised by lease-checking scripts or the control plane.

### Runbook (control-plane implementation)

1. The Logging & Admin Service (or a future dedicated coordination manager) pauses tick scheduling for the affected `<tenantId, regionId>` pairs via Game Session’s admin/control APIs (or globally if multiple slots are impacted).
2. It verifies, using Postgres and Redis health APIs/metrics, that the coordination metadata table’s `region_epoch` reflects the highest-authoritative epoch and that Redis has converged to a single primary for the impacted slots.
3. For each affected `<tenantId, regionId>`, it performs a **scoped coordination reset** as described in `system-architecture-redis-reset-and-recovery.md`:
   - Bump `region_epoch` in the coordination metadata so all existing leases become stale.
   - Clear `tick:{tenantRegionTag}:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}` using the versioned reset tooling.
   - Drive the tick effect ledger for the old epoch to convergence (SCHEDULED → APPLIED/ABANDONED with reset-specific reasons) per the tick failures/operations doc.
4. Only if the split-brain is caused by a corrupted or misconfigured Coordination Redis deployment that cannot be isolated to specific regions/tenants, perform a **cluster-scoped reset**:
   - Stop Redis or fail over to a clean node.
   - Delete or recreate the AOF volume so the keyspace resets.
   - Restart Redis, preload scripts, and allow the Game Session Service to acquire new epochs.
5. Resume ticks via the same control APIs only once Redis, PostgreSQL, and the epoch metadata are consistent to guarantee a single executor is in charge again.

Treat split-brain as an operational incident, not a service-level retry: the reset deliberately drops volatile coordination state and rebuilds it from PostgreSQL so that the single-authority invariant is re-established before gameplay continues. The Logging & Admin control plane is expected to automate these steps for narrow, clearly diagnosed cases (for example, per-region incidents) while still surfacing alerts and audit logs for operators.

---

## Normalization and Hash-Tag Migration

**Goal:** Change how `tenantId` / `regionId` normalization and hash tags are formed without breaking shard-local assumptions.

See the “Hash Tag Normalization” section in `system-architecture-redis.md` for the conceptual contract. This section focuses on the operational steps.

### Runbook: Normalization migration via reset (simplest path)

1. Plan the change:
   - Implement the new normalization version (for example `NORMALIZATION_V2`) in shared helpers.
   - Ensure the new normalization keeps `<tenantId, regionId>` stable and valid.
2. Schedule a maintenance window.
3. Pause ticks and stop accepting new commands for affected tenants/regions (or globally).
4. Deploy services using the new normalization helpers.
5. Start a fresh Coordination Redis deployment (or logical database) with an empty keyspace:
   - Point Game Session and other coordination clients at the new deployment.
   - Do not attempt to migrate existing coordination keys.
6. Resume ticks and player traffic:
   - Existing game instances may require restart or reconnection.
   - Coordination state is rebuilt from PostgreSQL and new activity.

### Runbook: In-place normalization migration (advanced option)

When dropping all coordination state is not acceptable, operators may implement a dedicated migration tool using the shared key builders:

1. Freeze topology:
   - Avoid Redis Cluster resharding during the normalization migration.
2. Pause or drain ticks and new commands for affected tenants/regions.
3. Migrate keys:
   - For each affected prefix, rewrite keys from old hash tags to new ones using a maintenance tool that:
     - Operates on explicit prefixes (no full-keyspace scans).
     - Preserves values and semantics across the move.
4. Validate:
   - Confirm that keys for a given `<tenantId, regionId>` share the expected hash tag.
   - Run smoke tests to verify tick and session behavior.
5. Resume ticks and commands.
6. Separately, perform any required Redis Cluster resharding as a **later, independent maintenance** once normalization is stable.

For most self-hosted deployments, the **reset-based migration** is preferred. In-place migrations are reserved for cases where coordination state for specific tenants cannot simply be dropped.
