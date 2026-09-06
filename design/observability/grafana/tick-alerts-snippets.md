# Tick Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for tick execution and ledger alerts. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets. The mode-aware execution/TTL/ratio and replay age/budget/SLO snippets are target-only/currently unavailable until Game Session emits and proves the canonical metric families and bounded labels. Every `scope_class` selector below is a bounded aggregation class (`region`, `game_instance`, `tenant`, or `cluster`), not an individual region or other raw runtime identity; use control-plane records for exact diagnosis.

## Tick Execution Health

Example alert for tick execution time approaching unsafe ratios relative to lock TTL:

```yaml
- alert: TickExecutionUnsafeRatio
  expr: ((tick_execution_time_ms_p99{scope_class=~"^(region|game_instance|tenant|cluster)$",tick_mode="normal"} / on (scope_class, tick_mode) label_replace(tick_lock_ttl_ms{scope_class=~"^(region|game_instance|tenant|cluster)$"}, "tick_mode", "normal", "scope_class", ".*")) or (tick_execution_time_ms_p99{scope_class=~"^(region|game_instance|tenant|cluster)$",tick_mode="solo"} / on (scope_class, tick_mode) label_replace(solo_lock_ttl_ms{scope_class=~"^(region|game_instance|tenant|cluster)$"}, "tick_mode", "solo", "scope_class", ".*"))) > 0.75
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stalled-tick-region
  annotations:
    summary: Tick execution time approaching unsafe fraction of lock TTL
    description: Tick p99 execution time is nearing or exceeding the configured mode-specific lock TTL for one or more bounded scope-class rollups. Normal samples use tick_lock_ttl_ms; solo-budget samples use solo_lock_ttl_ms. Investigate control-plane/runtime-health evidence, workload density, and approved tick fan-out or safety settings first. Do not change gameplay cadence as an ad-hoc mitigation; any intentional cadence change must follow the controlled pause, epoch-advance, timer-reconstruction procedure in [ADR 0073](../../architecture/decisions/adr-0073-evidence-calibrated-tick-budgets-and-lock-ttls.md).
```

This rule assumes the **canonical metric contract** from:

- `design/architecture/system-architecture-redis-operations.md` (tick + Redis metrics catalog)
- `design/architecture/system-architecture-tick-concepts-and-invariants.md` (ratio thresholds and interpretation)

Concretely:

- `tick_execution_time_ms_p99{scope_class,tick_mode}` is a recording rule derived from `tick_execution_time_ms_bucket{scope_class,tick_mode,le}`; `tick_mode` is exactly `normal` or `solo`.
- `tick_lock_ttl_ms{scope_class}` is emitted (or recorded) per approved bounded gameplay `scope_class` for normal samples, while `solo_lock_ttl_ms{scope_class}` carries the derived solo-budget TTL. The alert selects the denominator by `tick_mode` and never blends normal and solo samples.
- Because the TTL families do not carry `tick_mode`, each denominator is given its branch's constant mode with `label_replace` before the explicit `on (scope_class, tick_mode)` join. This keeps the numerator's mode label on each ratio series so the `or` set operation cannot let the normal branch suppress the solo branch.

Do not use “Timer-in-seconds” histograms under `_ms` names; producers must either emit millisecond-valued histograms/summaries or publish explicit `_seconds` metrics and define separate `_ms` recording rules with unambiguous unit conversions.

## Tick Effect Replay Health

Example alert for stuck `SCHEDULED` rows in the tick effect ledger:

```yaml
- alert: TickEffectsReplaySloBreached
  expr: tick_effects_replay_slo_breached{scope_class=~"^(region|game_instance|tenant|cluster)$"} > 0
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick effect replay convergence budget is breached
    description: The oldest pending tick effect exceeds the emitted replay convergence budget for one or more bounded scope-class rollups; investigate replay pressure, durable state, and approved fan-out or safety settings.

- alert: TickCleanupLagHigh
  expr: tick_cleanup_lag_ms{scope_class=~"^(region|game_instance|tenant|cluster)$"} > 15000
  for: 10m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#durable-commitcoordination-cleanup-divergence
  annotations:
    summary: Tick durable commit and coordination cleanup are diverging
    description: Cleanup lag from durable commit to coordination-cleared is elevated for one or more bounded scope-class rollups; investigate replay pressure and coordination cleanup behavior.

- alert: TickEffectsReplayStarved
  expr: tick_effects_replay_starved{scope_class=~"^(region|game_instance|tenant|cluster)$"} > 0
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay controller is starved
    description: The canonical starvation signal reports pending work without replay-batch progress beyond the emitted convergence budget for one or more bounded scope-class rollups. Investigate replay-controller fairness, approved fan-out, and safety settings.

- alert: TickReplayScanLagHigh
  expr: tick_effects_replay_scan_lag_ms{scope_class=~"^(region|game_instance|tenant|cluster)$"} > 300000
  for: 15m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Tick replay scan lag is high
    description: Replay scan lag is growing for one or more bounded scope-class rollups even while the replay controller may continue making progress elsewhere; investigate replay-controller fairness and starvation separately from the canonical replay SLO and starvation signals.
```

This assumes the canonical `tick_effects_pending_oldest_age_seconds{scope_class}`, `tick_effects_replay_convergence_budget_seconds{scope_class}`, `tick_effects_replay_slo_breached{scope_class}`, `tick_effects_replay_starved{scope_class}`, and `tick_effects_replay_scan_lag_ms{scope_class}` metrics/recordings. The SLO and starvation recordings apply the emitted convergence budget; the alert rules add only their Alertmanager persistence windows. `TickReplayScanLagHigh` is a distinct supplemental target-only alert and does not replace the canonical SLO or starvation alerts; `300000ms` is its shared template threshold, and profile overlays may tune it only through the evidence-backed owner contract.

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

## Pending Replay Restore Pressure

The Game Session pending-replay restore path exposes a service-process-wide consecutive-failure signal. It deliberately has no tenant or game-instance labels; use the durable tick batch and runtime-health records to identify the exact affected scope.

```yaml
- alert: TickPendingReplayRestoreFailures
  expr: tick_pending_replay_restore_consecutive_failures >= tick_pending_replay_restore_alert_threshold_failures
  for: 1m
  labels:
    service: game-session-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-tick-incident-runbook.md#stuck-tick-effect-ledger-entries
  annotations:
    summary: Pending replay restore failures are preventing coordination convergence
    description: The Game Session process has observed repeated fail-closed pending-replay restore failures. Redis lease loss and non-committing restore results preserve the durable batch and prevent tick advancement; inspect the current lease, pending projection, durable PENDING_REPLAY batch, and runtime-health evidence before remediation.
```

The alert uses the exported threshold rather than embedding the bootstrap value. The producer clears `tick_pending_replay_restore_consecutive_failures` only after the restore script commits and any durable Redis-only requeue bookkeeping succeeds. The related counters are `tick_pending_replay_restore_failures_total` and `tick_pending_replay_restore_alert_total`; the threshold gauge is `tick_pending_replay_restore_alert_threshold_failures`.
