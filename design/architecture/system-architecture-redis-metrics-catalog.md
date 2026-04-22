# FireMUD Redis Metrics Catalog

This document summarizes the canonical Redis-related metrics, alerting surfaces, and size/complexity budgets referenced across the Redis architecture, incident runbooks, and service designs.

## Coordination Redis Core Metrics

- `redis_aof_current_size_bytes`
- `redis_coordination_aof_growth_bytes_total`
- `redis_coordinator_restart_duration_seconds`
- `redis_coordination_tail_loss_ms{tenantId,regionId}`
- `redis_replication_lag_ms{redis_role,nodeId,upstreamNodeId}`
- `redis_replication_offset_lag_bytes{redis_role,nodeId,upstreamNodeId}`
- `coordination_maintenance_active{scope_type,tenantId,regionId,operation}`
- error and outcome metrics for stale lease, stale lock, unsupported epoch, and similar replay/coordination failures
- size and count metrics for coordination prefixes such as `tick:*`, `timer:*`, `retry:*`, `session:*`, and `tick-executor-lease:*`
- over-budget and oversize counters such as:
  - `redis_tick_pending_oversized_total`
  - `redis_tick_pending_effects_over_budget_total`
  - `redis_tick_command_queue_overflow_total`
  - `redis_tick_timers_over_budget_total`
  - `redis_session_payload_oversized_total`

## Session Schema and Cleanup Metrics

- `session.cas_unsupported_schema_total`
- `session.cleanup_scanned_total`
- `session.cleanup_deleted_total`
- `session.cleanup_duration_seconds`

## Coordination and Tick Metrics

- `tick_interval_ms{tenantId,regionId}`
- `tick_execution_time_ms_bucket{tenantId,regionId,le}`
- `tick_execution_time_ms_p95{tenantId,regionId}`
- `tick_execution_time_ms_p99{tenantId,regionId}`
- `tick_lock_ttl_ms{tenantId,regionId}`
- `tick_status{tenantId,regionId,status}`
- `current_tick_state{tenantId,regionId,state}`
- `current_tick_terminal_at_ms{tenantId,regionId}`
- `tick_retry_queue_depth{tenantId,regionId}`
- `tick_command_queue_depth{tenantId,regionId}`
- `tick_current_id{tenantId,regionId}`
- `tick_pending_oldest_id{tenantId,regionId}`
- `tick_durable_commit_total{tenantId,regionId}`
- `tick_coordination_cleared_total{tenantId,regionId}`
- `tick_cleanup_lag_ms{tenantId,regionId}`

### Remote Follow-Up Drainage

- `remote_followups_due_total{tenantId,regionId}`
- `remote_followups_drain_lag_ms{tenantId,regionId}`
- `remote_followups_backlog_over_budget_total{tenantId,regionId}`

### Tick Effect Ledger

- `tick_effects_pending_total{tenantId,regionId}`
- `tick_effects_applied_total{tenantId,regionId}`
- `tick_effects_abandoned_total{tenantId,regionId,reason}`
- `tick_effects_pending_oldest_scheduled_timestamp_seconds{tenantId,regionId}`
- `tick_effects_pending_oldest_age_seconds{tenantId,regionId}`
- `tick_effects_replay_convergence_budget_seconds{tenantId,regionId}`
- `tick_effects_replay_slo_breached{tenantId,regionId}`
- `tick_effects_replay_scan_lag_ms{tenantId,regionId}`
- `tick_effects_replay_batches_total{tenantId,regionId}`
- `tick_effects_replay_starved{tenantId,regionId}`

### Service-Level Replay Metrics

- `gamesession_tick_replayed_total{tenantId,regionId}`
- `gamesession_tick_executed_total{tenantId,regionId}`

### Dual-Leader and Reset Metrics

- `redis_coordination_dual_leader_detected_total{tenantId,regionId}`
- `redis_coordination_reset_total{scope}`

## Cache and Rate-Limit Redis Metrics

- standard Redis metrics from `INFO` or exporters such as memory, eviction, hit/miss, and blocked-clients gauges
- approximate key counts and bytes per cache prefix
- active `ratelimit:*` key counts per tenant
- allow/deny counters per bucket and time window

Example service-level cache metrics:

- `cache.character_cache_hits_total` / `cache.character_cache_misses_total`
- `cache.inventory_hits_total` / `cache.inventory_misses_total`
- `cache.room_hits_total` / `cache.room_misses_total`
- `cache.view_room_look_hits_total` / `cache.view_room_look_misses_total`
- `cache.inventory_keys`
- `cache.room_keys`
- `cache.world_dynamic_keys`
- `cache.view_room_look_keys`
- oversize counters such as `cache.inventory_oversized_payload_total`

Any new cache prefix family must:

- declare at least one hit/miss counter pair
- document any key-count gauges or oversize counters that enforce size/complexity budgets
- describe how those metrics appear in dashboards or alerts so operators can tell when the cache is ineffective or mis-sized

## Cardinality Policy

To keep monitoring systems stable:

- emit per-`<tenantId, regionId>` series only for active regions and/or bound expensive histograms to top-N worst regions
- provide aggregated rollups alongside per-region views
- avoid adding extra high-cardinality labels such as per-command IDs on core coordination metrics

## AOF Profiles

Recommended AOF configuration profiles tie the restart and size targets back to concrete Redis settings:

| Profile | Use Case | Persistence Settings | Notes |
| --- | --- | --- | --- |
| `dev_local` | single-developer, non-player-facing experiments | `appendonly yes`, `appendfsync everysec`, `aof-use-rdb-preamble yes`, small `maxmemory` | AOF is primarily for debugging |
| `hobby_self_hosted` | small/self-hosted games with real players | `appendonly yes`, `appendfsync everysec` or carefully accepted `no`, `aof-use-rdb-preamble yes`, sized to keep replay within target | expected to honor restart and tail-loss budgets |
| `production_clustered` | multi-tenant or higher-scale deployments | `appendonly yes`, platform-recommended fsync policy, `aof-use-rdb-preamble yes`, coordinated per-node sizing | per-node AOF and restart time must stay within agreed budgets |

Profile selection in standard environments should be explicit:

- local developer environments and disposable experiments use `dev_local`
- CI and short-lived preview environments use `dev_local` unless a test explicitly validates higher-tier SLOs
- hobby/self-hosted player-facing environments use `hobby_self_hosted`
- staging and production-like clustered environments use `production_clustered` unless a documented exception exists

## Coordination Size and Complexity Budgets

To keep Coordination Redis predictable:

- `tick:{tenantRegionTag}:pending`
  - target serialized payload size around 32–64 KB
  - target staged effects per tick around 128
- `tick:{tenantRegionTag}:queue:<entityId>`
  - hard safety cap around 50–100 commands per entity queue
- `timer:{tenantRegionTag}`
  - hard safety cap around a few thousand to ten thousand timers per region depending on deployment
- `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`
  - target serialized session value size around 16–32 KB

Exceeding a budget must result in explicit logs, metrics, and controlled failure modes rather than silent degradation or unbounded growth.

## Rule-of-Thumb Coordination Capacity Per Region

For modest self-hosted deployments:

- active entity locks should typically remain within a few hundred per region
- pending tick entries should stay within a few ticks worth of work, not thousands of uncommitted entries
- timers and retry items should usually stay within tens of thousands per busy region
- session keys should approximate one key per active session

These are conservative heuristics used to spot unintended coordination misuse early.

## Tail-Loss and Budget Alerts

Alerts and dashboards should reference the explicit SLOs and budgets defined in [`system-architecture-redis-operations.md`](./system-architecture-redis-operations.md), using clear labels and scope-aware wording so incidents tie back to agreed envelopes rather than vague “Redis is bad” signals.

Replica-promotion dashboards should use `redis_replication_lag_ms` as the canonical decision metric and compare it directly against `tail_loss_budget_ms` for the affected deployment:

- acceptable band: `redis_replication_lag_ms <= 0.5 * tail_loss_budget_ms`
- warning band: `0.5 * tail_loss_budget_ms < redis_replication_lag_ms < tail_loss_budget_ms`
- red line: `redis_replication_lag_ms >= tail_loss_budget_ms`
