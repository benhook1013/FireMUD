# Tick Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for tick execution and ledger alerts. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets. The mode-aware execution/TTL/ratio snippets are target-only/currently unavailable until Game Session emits and proves the canonical metric families and bounded labels. Every `scope_class` selector below is a bounded aggregation class (`region`, `game_instance`, `tenant`, or `cluster`), not an individual region or other raw runtime identity; use control-plane records for exact diagnosis.

## Tick Execution Health

Example alert for tick execution time approaching unsafe ratios relative to lock TTL:

```yaml
- alert: TickExecutionUnsafeRatio
  expr: ((tick_execution_time_ms_p99{scope_class=~".+",tick_mode="normal"} / on (scope_class) group_left() tick_lock_ttl_ms{scope_class=~".+"}) or (tick_execution_time_ms_p99{scope_class=~".+",tick_mode="solo"} / on (scope_class) group_left() solo_lock_ttl_ms{scope_class=~".+"})) > 0.75
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Tick execution time approaching unsafe fraction of lock TTL
    description: Tick p99 execution time is nearing or exceeding the configured mode-specific lock TTL for one or more bounded scope-class rollups. Normal samples use tick_lock_ttl_ms; solo-budget samples use solo_lock_ttl_ms. Investigate the corresponding control-plane/runtime-health evidence and workload density before adjusting tick cadence.
```

This rule assumes the **canonical metric contract** from:

- `design/architecture/system-architecture-redis-operations.md` (tick + Redis metrics catalog)
- `design/architecture/system-architecture-tick-concepts-and-invariants.md` (ratio thresholds and interpretation)

Concretely:

- `tick_execution_time_ms_p99{scope_class,tick_mode}` is a recording rule derived from `tick_execution_time_ms_bucket{scope_class,tick_mode,le}`; `tick_mode` is exactly `normal` or `solo`.
- `tick_lock_ttl_ms{scope_class}` is emitted (or recorded) per approved bounded gameplay `scope_class` for normal samples, while `solo_lock_ttl_ms{scope_class}` carries the derived solo-budget TTL. The alert selects the denominator by `tick_mode` and never blends normal and solo samples.

Do not use “Timer-in-seconds” histograms under `_ms` names; producers must either emit millisecond-valued histograms/summaries or publish explicit `_seconds` metrics and define separate `_ms` recording rules with unambiguous unit conversions.

## Tick Effect Ledger Backlog

Example alert for stuck `SCHEDULED` rows in the tick effect ledger:

```yaml
- alert: TickEffectLedgerBacklog
  expr: tick_effects_pending_total{scope_class=~".+"} > 0 and on (scope_class) (time() - tick_effects_pending_oldest_scheduled_timestamp_seconds{scope_class=~".+"}) > 300
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick effect ledger has pending rows beyond grace window
    description: One or more bounded scope-class rollups have SCHEDULED tick effects that have not converged to APPLIED or ABANDONED within the expected grace window.

- alert: TickCleanupLagHigh
  expr: tick_cleanup_lag_ms{scope_class=~".+"} > 15000
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#durable-commitcoordination-cleanup-divergence
  annotations:
    summary: Tick durable commit and coordination cleanup are diverging
    description: Cleanup lag from durable commit to coordination-cleared is elevated for one or more bounded scope-class rollups; investigate replay pressure and coordination cleanup behavior.

- alert: TickReplayFairnessStarved
  expr: tick_effects_pending_total{scope_class=~".+"} > 0 and on (scope_class) increase(tick_effects_replay_batches_total{scope_class=~".+"}[15m]) == 0
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay controller is not servicing pending scope classes fairly
    description: One or more bounded scope-class rollups still have pending ledger work, but replay batches are not being executed for those scope classes. Investigate replay-controller fairness and starvation.

- alert: TickReplayScanLagHigh
  expr: tick_effects_replay_scan_lag_ms{scope_class=~".+"} > 300000
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay scan lag indicates controller starvation
    description: Replay scan lag is growing for one or more bounded scope-class rollups even though the replay controller remains active elsewhere.
```

This assumes a helper metric such as `tick_effects_pending_oldest_scheduled_timestamp_seconds{scope_class}` that tracks the oldest `SCHEDULED` entry per approved bounded gameplay scope.

## Tick Scheduler Pressure

Example alerts for the bounded fan-out scheduler's merge and rejection pressure:

```yaml
- alert: TickSchedulerRejectingWork
  expr: game_session_tick_scheduler_rejection_consecutive_cycles >= game_session_tick_scheduler_rejection_alert_threshold_cycles
  for: 1m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#tick-scheduler-pressure
  annotations:
    summary: Tick scheduler is rejecting session work
    description: The bounded tick scheduler has sustained scheduler cycles with rejected session submissions. Investigate executor saturation, queue depth, and session count before gameplay timing starts degrading materially.

- alert: TickSchedulerQueueDepthHigh
  expr: game_session_tick_scheduler_queue_depth_consecutive_cycles >= game_session_tick_scheduler_queue_depth_alert_threshold_cycles
  for: 1m
  labels:
    service: game-session-service
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#tick-scheduler-pressure
  annotations:
    summary: Tick scheduler executor queue depth is persistently high
    description: The bounded executor behind the tick scheduler has spent enough consecutive scheduler cycles above the configured queue-depth threshold to trip the alert contract. Investigate recent merge/rejection rates and the number of active sessions.

- alert: TickSchedulerMergeRateHigh
  expr: game_session_tick_scheduler_merge_consecutive_cycles >= game_session_tick_scheduler_merge_alert_threshold_cycles
  for: 1m
  labels:
    service: game-session-service
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#tick-scheduler-pressure
  annotations:
    summary: Tick scheduler is merging overlapping work heavily
    description: The bounded fan-out scheduler has spent enough consecutive scheduler cycles above the configured merge threshold that overlapping pulses are now an operator-visible pressure condition.
```

These rules assume the current scheduler metric contract from `TickScheduler`:

- `game_session_tick_scheduler_rejected_total`
- `game_session_tick_scheduler_merged_total`
- `game_session_tick_scheduler_executor_queue_depth`
- `game_session_tick_scheduler_pending_sessions`
- `game_session_tick_scheduler_rejection_consecutive_cycles`
- `game_session_tick_scheduler_merge_consecutive_cycles`
- `game_session_tick_scheduler_queue_depth_consecutive_cycles`
- `game_session_tick_scheduler_rejection_alert_threshold_cycles`
- `game_session_tick_scheduler_merge_alert_threshold_count`
- `game_session_tick_scheduler_merge_alert_threshold_cycles`
- `game_session_tick_scheduler_queue_depth_alert_threshold_count`
- `game_session_tick_scheduler_queue_depth_alert_threshold_cycles`

Environment overlays may tune exact thresholds, but should preserve the alert names, owner, and runbook routing so scheduler pressure is visible and actionable. The alert expressions should prefer the exported threshold/cycle gauges over re-hardcoding numeric defaults in rule files, while dashboards should still show the raw queue-depth, merge, and rejection counters for diagnosis.
