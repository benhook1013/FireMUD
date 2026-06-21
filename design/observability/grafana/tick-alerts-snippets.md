# Tick Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for tick execution and ledger alerts. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Tick Execution Health

Example alert for tick execution time approaching unsafe ratios relative to lock TTL:

```yaml
- alert: TickExecutionUnsafeRatio
  expr: (tick_execution_time_ms_p99 / tick_lock_ttl_ms) > 0.75
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Tick execution time approaching unsafe fraction of lock TTL
    description: Tick p99 execution time is nearing or exceeding the configured lock TTL for one or more regions. Investigate tick health and region density before adjusting tick cadence.
```

This rule assumes the **canonical metric contract** from:

- `design/architecture/system-architecture-redis-operations.md` (tick + Redis metrics catalog)
- `design/architecture/system-architecture-tick-concepts-and-invariants.md` (ratio thresholds and interpretation)

Concretely:

- `tick_execution_time_ms_p99` is a recording rule derived from `tick_execution_time_ms_bucket{scope,le}`.
- `tick_lock_ttl_ms` is emitted (or recorded) per approved bounded gameplay `scope` and represents the lock/lease TTL budget used by tick executors.

Do not use “Timer-in-seconds” histograms under `_ms` names; producers must either emit millisecond-valued histograms/summaries or publish explicit `_seconds` metrics and define separate `_ms` recording rules with unambiguous unit conversions.

## Tick Effect Ledger Backlog

Example alert for stuck `SCHEDULED` rows in the tick effect ledger:

```yaml
- alert: TickEffectLedgerBacklog
  expr: tick_effects_pending_total > 0 and (time() - tick_effects_pending_oldest_scheduled_timestamp_seconds) > 300
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick effect ledger has pending rows beyond grace window
    description: One or more regions have SCHEDULED tick effects that have not converged to APPLIED or ABANDONED within the expected grace window.

- alert: TickCleanupLagHigh
  expr: tick_cleanup_lag_ms > 15000
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#durable-commitcoordination-cleanup-divergence
  annotations:
    summary: Tick durable commit and coordination cleanup are diverging
    description: Cleanup lag from durable commit to coordination-cleared is elevated for one or more regions; investigate replay pressure and coordination cleanup behavior.

- alert: TickReplayFairnessStarved
  expr: tick_effects_pending_total > 0 and increase(tick_effects_replay_batches_total[15m]) == 0
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay controller is not servicing pending regions fairly
    description: One or more regions still have pending ledger work, but replay batches are not being executed for those regions. Investigate replay-controller fairness and starvation.

- alert: TickReplayScanLagHigh
  expr: tick_effects_replay_scan_lag_ms > 300000
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay scan lag indicates controller starvation
    description: Replay scan lag is growing for one or more regions even though the replay controller remains active elsewhere.
```

This assumes a helper metric such as `tick_effects_pending_oldest_scheduled_timestamp_seconds` that tracks the oldest `SCHEDULED` entry per region.

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
