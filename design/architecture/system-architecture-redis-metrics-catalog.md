# FireMUD Redis Metrics Catalog

This document summarizes the canonical Redis-related metrics, alerting surfaces, and size/complexity budgets referenced across the Redis architecture, incident runbooks, and service designs.

Implementation status: the canonical mode-aware tick execution/TTL/ratio families below are target-only and currently unavailable. Live Game Session records `game_session_tick_duration_ms` without the required bounded `scope_class`/`tick_mode` labels; the recording rules and dashboards are templates until the producer and label contract are implemented and proved.

## Coordination Redis Core Metrics

- `redis_aof_current_size_bytes`
- `redis_coordination_aof_growth_bytes_total`
- `redis_coordinator_restart_duration_seconds`
- `redis_coordination_tail_loss_ms{scope}` (current compatibility exposure series; `scope` is a bounded Redis deployment bucket)
- `redis_unreplicated_write_window_ms{scope}` (target measured exposure, pre-aggregated as the worst eligible candidate within the bounded deployment/environment/ruleset scope)
- `redis_unreplicated_write_window_slo_breached{scope}` (target measured-SLO breach series)
- `redis_coordination_tail_loss_budget_ms` (current compatibility recording rule; one deployment-wide bounded scalar equal to the maximum `clamp_min(2 * tick_interval_ms, 2000)` across `tick_interval_ms{scope_class}`)
- `redis_coordination_tail_loss_slo_breached{scope}` (current compatibility recording rule comparing each bounded exposure scope with that scalar budget, not the target measured-SLO breach)
- `redis_replication_lag_ms{redis_role,scope}`
- `redis_replication_offset_lag_bytes{redis_role,scope}`
- `coordination_maintenance_active{scope_type,scope_bucket,operation}`
- error and outcome metrics for stale lease, stale lock, unsupported epoch, and similar replay/coordination failures
- size and count metrics for coordination prefixes such as `tick:*`, `timer:*`, `retry:*`, `session:*`, and `tick-executor-lease:*`
- over-budget and oversize counters such as:
  - `redis_tick_pending_oversized_total`
  - `redis_tick_pending_effects_over_budget_total`
  - `redis_tick_command_queue_overflow_total`
  - `redis_tick_timers_over_budget_total`
  - `redis_session_payload_oversized_total`

For measured exposure, replication-lag, replication-offset, and dashboard-comparison metrics, bounded `scope` consistently identifies one Coordination Redis deployment together with its canonical environment class and active configuration/ruleset. `redis_unreplicated_write_window_ms{scope}` and the exported replication metrics are pre-aggregated worst-eligible-candidate values within that scope. Because no replica identity label is exported, these metrics do not identify individual candidates or replicas; exact candidate and node IDs remain control-plane and structured-log evidence. The current compatibility budget is deliberately deployment-wide: `redis_coordination_tail_loss_budget_ms` has no `scope` or `scope_class` label and is the maximum cadence-derived budget across the bounded `tick_interval_ms{scope_class}` classes. `redis_coordination_tail_loss_slo_breached{scope}` compares each bounded exposure scope with that scalar; `scope` and `scope_class` are not aliases. The target measured-SLO breach series is distinct from this current compatibility recording rule, which is derived from the tick-based exposure budget.

## Session Schema and Cleanup Metrics

- `session.cas_unsupported_schema_total`
- `session.cleanup_scanned_total`
- `session.cleanup_deleted_total`
- `session.cleanup_duration_seconds`

## Coordination and Tick Metrics

- `tick_interval_ms{scope_class}`
- `tick_execution_time_ms_bucket{scope_class,tick_mode,le}`
- `tick_execution_time_ms_p95{scope_class,tick_mode}`
- `tick_execution_time_ms_p99{scope_class,tick_mode}`
- `tick_lock_ttl_ms{scope_class}`
- `solo_lock_ttl_ms{scope_class}`
- `tick_execution_safety_ratio_p99{scope_class,tick_mode}`
- `tick_status{scope_class,status}`
- `current_tick_state{scope_class,state}`
- `current_tick_terminal_at_ms{scope_class}`
- `tick_retry_queue_depth{scope_class}`
- `tick_command_queue_depth{scope_class}`
- `tick_current_id{scope_class}`
- `tick_pending_oldest_id{scope_class}`
- `tick_durable_commit_total{scope_class}`
- `tick_coordination_cleared_total{scope_class}`
- `tick_cleanup_lag_ms{scope_class}`

`scope_class` is the required label for bounded operational rollups of the tick metric families above. Its value is one of `region`, `game_instance`, `tenant`, or `cluster`, identifying the aggregation class rather than an individual runtime scope. Every related tick metric must use the same mapping for a deployment: a region-level rollup is labelled `region`, a game-instance rollup `game_instance`, a tenant rollup `tenant`, and a deployment-wide rollup `cluster`. The label never contains a tenant, game-instance, playable-state, or region identifier, and exact diagnosis remains on control-plane/runtime-health records and structured logs. `tick_mode` is a second bounded label on execution-time samples and is exactly `normal` or `solo`; it never contains a command, actor, or runtime identity.

Recording rules must preserve both bounded labels through aggregation (for example, `sum by (scope_class, tick_mode, le)` for the execution histogram). Normal samples use `tick_lock_ttl_ms{scope_class}` and solo-budget samples use `solo_lock_ttl_ms{scope_class}`; the resulting `tick_execution_safety_ratio_p99{scope_class,tick_mode}` must select the denominator by `tick_mode` rather than blend normal and solo samples. Producers and dashboards must not mix this class/mode mapping with a raw `scope` label or infer an individual region from a class-level series.

### Remote Follow-Up Drainage

- `remote_followups_due_total`
- `remote_followups_drain_lag_ms`
- `remote_followups_backlog_over_budget_total`

### Tick Effect Ledger

- `tick_effects_pending_total{scope_class}`
- `tick_effects_applied_total{scope_class}`
- `tick_effects_abandoned_total{scope_class,reason}`
- `tick_effects_pending_oldest_scheduled_timestamp_seconds{scope_class}`
- `tick_effects_pending_oldest_age_seconds{scope_class}`
- `tick_effects_replay_convergence_budget_seconds{scope_class}`
- `tick_effects_replay_slo_breached{scope_class}`
- `tick_effects_replay_scan_lag_ms{scope_class}`
- `tick_effects_replay_batches_total{scope_class}`
- `tick_effects_replay_starved{scope_class}`

### Service-Level Replay Metrics

- `gamesession_tick_replayed_total{scope_class}`
- `gamesession_tick_executed_total{scope_class}`

### Dual-Leader and Reset Metrics

- `redis_coordination_dual_leader_detected_total{scope}`
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

- emit bounded scope series such as `scope`, `region_class`, or another explicitly documented operational bucket rather than raw tenant, game-instance, or region identifiers
- provide bounded aggregate and operational region-class rollups; do not imply an exact Prometheus time series for every tenant, game instance, or region
- avoid adding extra high-cardinality labels such as per-command IDs on core coordination metrics
- Treat every `scope` or `scope_bucket` label in this catalog as a bounded bucket, never as a raw `tenantId`, `gameInstanceId`, or `regionId` value.
- Use control-plane APIs and structured logs/audit records for exact tenant/game-instance/region diagnosis; do not recover exact scope by expanding metric label cardinality.

Metric rollups are bounded operational summaries, not the authoritative exact-scope diagnostic view. Exact tenant, game-instance, and region status and identity come from control-plane reads and structured logs/audit records; a metric dashboard may correlate those records with bounded rollups but must not claim that a `scope` or `scope_bucket` series identifies one exact runtime scope.

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

Replica-promotion dashboards should use the pre-aggregated `redis_replication_lag_ms` as the canonical decision metric and compare it directly against the measured `redis_unreplicated_write_window_slo_ms` for the same Coordination Redis deployment, canonical environment class, and active configuration/ruleset. The metric already represents the worst candidate replica in that scope. `scope` is the bounded deployment/environment/ruleset mapping defined above; exact candidate, node, and upstream identities belong in structured logs or control-plane evidence, never in Prometheus labels:

- acceptable band: `redis_replication_lag_ms <= 0.5 * redis_unreplicated_write_window_slo_ms`
- warning band: `0.5 * redis_unreplicated_write_window_slo_ms < redis_replication_lag_ms < redis_unreplicated_write_window_slo_ms`
- red line: `redis_replication_lag_ms >= redis_unreplicated_write_window_slo_ms`
