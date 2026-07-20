# FireMUD Tick Incident Runbook

This runbook describes operator actions for **tick-related incidents**, including stalled regions, replay storms, durable commit/coordination cleanup divergence, and stuck tick effect ledger entries.

For the detailed tick design, see:

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-tick-concepts-and-invariants.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`

Redis coordination behavior and reset flows are defined in:

- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-redis-reset-and-recovery.md`

## Implementation Notes

This runbook is written for the target tick/region model (`tenantId` + `regionId`). If your current deployment only exposes coarser tick pause controls (for example pausing by `tenantId` + `game_instance_id`), follow the same decision logic but apply it at the closest available scope and record the scope mismatch in the incident timeline for follow-up.

When applying scope substitution, use a deterministic mapping source (control-plane lookup or game-instance registry), record the resolved region set, and include the mapping evidence in the incident notes so post-incident reconciliation is auditable.

## Incident Types

- **Stalled tick region** (lease held but no forward progress)
- **Tick replay storm or excessive replays**
- **Durable commit/coordination cleanup divergence**
- **Stuck tick effect ledger entries** (`SCHEDULED` rows that never converge)

Each scenario below assumes Redis/database metrics are wired according to the Redis and tick operations docs. If Grafana/Prometheus/Kibana/Jaeger is degraded, use fallback procedures from `system-architecture-observability-incident-runbook.md` and prioritize authoritative tick controls and service logs.

## Trace Preconditions (For Tick Root Cause)

Tick incidents often benefit from trace-level diagnosis, but mitigation must not block on trace availability.

- Baseline expectation: consult the environment's advertised tracing capability and covered tick workflows. Tracing may be explicitly disabled; approximately 1% is only a calibration seed where sampling capability is proved.
- If `tick_execute` / `tick_apply_effect` traces are too sparse:
  - Apply service-scoped escalation only if that capability is advertised and proved, with an incident identity, volume budget, automatic expiry, and verified reversion.
  - If collector tail-sampling by `tenantId`/`regionId` is supported, prefer scoped escalation for the impacted region and remove it after triage.
- If the environment does not satisfy collector capability requirements for scoped escalation, treat it as service-scoped-only.
- If traces remain unavailable, continue with metrics + logs and proceed with region/tenant reset decisions using runbook thresholds.
- Missing sampled traces are not evidence that a tick/effect did not execute.

## Stalled Tick Region

### Detect (Stalled tick region)

- Alerts fire on tick health, for example:
  - `tick_status{scope,status="STALLED"}` or `tick_status{scope,status="DEGRADED"}` being `1` for a sustained window.
  - `tick_execution_time_ms_p95` / `tick_execution_time_ms_p99` ratios vs `tick_lock_ttl_ms` exceeding the degraded thresholds described in `system-architecture-tick-concepts-and-invariants.md`.
- Redis coordination metrics and dashboards show:
  - A region holding `tick-executor-lease:{tenantRegionTag}` for longer than expected without advancing `tickId`.
  - Growing `tick_retry_queue_depth` or `tick_command_queue_depth` for the affected `<tenantId, regionId>`.
- Logs and traces:
  - Game Session logs show repeated retries or warnings for the affected region.
  - Jaeger traces for `tick_execute` or equivalent spans show long durations or repeated retries for the same region.

### Decide (Stalled tick region)

- If the stall is brief and metrics already show recovery (status returns to `RUNNING`, queues drain, execution time ratios return to healthy ranges), continue to monitor without intervention.
- If the region remains stalled or degraded long enough that the shared tick-health paging conditions would still be firing for that scope, plan a **region-scoped** coordination reset for the affected `<tenantId, regionId>` as described in `system-architecture-redis-reset-and-recovery.md`.
  - In shared rulesets, this means the same conditions that would keep the finalized per-region tick-health paging alert active for that region, starting with sustained `tick_status{scope,status="STALLED"} == 1` and any environment overlay that pages on prolonged `DEGRADED` state.
  - Treat `tick_status{scope,status="STALLED"} == 1` sustained through the environment’s alert hold time as an intervention threshold by itself.
  - Also treat sustained `tick_status{scope,status="DEGRADED"} == 1` together with continued over-threshold `tick_execution_time_ms_p95` / `tick_execution_time_ms_p99` ratios versus `tick_lock_ttl_ms`, or continued growth in `tick_retry_queue_depth` / `tick_command_queue_depth`, as sufficient to intervene before the region flips fully to `STALLED`.
- Only escalate to a **tenant-scoped** or **cluster-wide** reset if multiple regions for the same tenant show similar symptoms or if Redis incident runbooks indicate broader coordination corruption.

### Act (Stalled tick region)

1. **Quiesce tick work for the region**
   - Pause tick scheduling for the affected `<tenantId, regionId>` using the Game Session controls described in the tick architecture and Redis reset docs.
   - Ensure no new executor instances are attempting to acquire the region lease while you inspect metrics.
2. **Inspect metrics and traces**
   - Use the Tick Health dashboard to confirm:
     - `tick_status` indicates stalled or degraded state.
     - `tick_execution_time_ms_*` ratios and queue depths support the stalled diagnosis.
   - Use Jaeger to inspect `tick_execute` spans for this region to verify whether the stall is due to downstream services, coordination, or domain logic.
3. **Apply a region-scoped coordination reset**
   - Follow the **Per-region reset** flow in `system-architecture-redis-reset-and-recovery.md`, scoping the Job to:
     - `tick:{tenantRegionTag}:*`
     - `timer:{tenantRegionTag}`
     - `retry:{tenantRegionTag}`
     - `tick-executor-lease:{tenantRegionTag}`
   - Do not delete domain data or non-coordination prefixes.
4. **Resume ticks and verify recovery**
   - Resume tick scheduling for the region.
   - Confirm via dashboards that:
     - `tick_status{scope,status="RUNNING"}` is `1`.
     - `tick_execution_time_ms_*` ratios fall back into healthy envelopes.
     - Command and retry queue depths stabilize.
   - Review `tick_effects_pending_total` for the region to ensure the ledger is draining and not accumulating new stuck rows.

## Tick Scheduler Pressure

### Detect (Tick scheduler pressure)

- Alerts fire from the scheduler-pressure family:
  - `TickSchedulerRejectingWork`
  - `TickSchedulerQueueDepthHigh`
  - `TickSchedulerMergeRateHigh`
- Metrics show the bounded scheduler staying above its configured pressure thresholds rather than only showing a one-off burst:
  - `game_session_tick_scheduler_rejection_consecutive_cycles`
  - `game_session_tick_scheduler_merge_consecutive_cycles`
  - `game_session_tick_scheduler_queue_depth_consecutive_cycles`
- Compare those gauges with the exported threshold metrics instead of assuming defaults:
  - `game_session_tick_scheduler_rejection_alert_threshold_cycles`
  - `game_session_tick_scheduler_merge_alert_threshold_count`
  - `game_session_tick_scheduler_merge_alert_threshold_cycles`
  - `game_session_tick_scheduler_queue_depth_alert_threshold_count`
  - `game_session_tick_scheduler_queue_depth_alert_threshold_cycles`
- Supporting diagnosis should also inspect:
  - `game_session_tick_scheduler_executor_queue_depth`
  - `game_session_tick_scheduler_pending_sessions`
  - `game_session_tick_scheduler_rejected_total`
  - `game_session_tick_scheduler_merged_total`

### Decide (Tick scheduler pressure)

- If the consecutive-cycle gauges fall back below threshold quickly and queue depth drains, treat it as a transient burst and keep monitoring.
- If scheduler-pressure alerts remain active:
  - treat the issue as capacity/saturation pressure in the current bounded fan-out model, not as a signal to change gameplay timing automatically;
  - investigate executor saturation, active session count, downstream tick duration, and recent deployment/configuration changes before changing thresholds;
  - prefer scaling or reducing the pressure source over loosening alert thresholds without evidence.
- If scheduler pressure is sustained together with stalled-region symptoms, follow the stalled-region flow first for the affected scopes and then revisit scheduler capacity once region health recovers.

### Act (Tick scheduler pressure)

1. **Confirm the pressure shape**
   - Check whether the alert is driven mainly by rejections, merge cycles, queue depth cycles, or all three together.
   - Confirm the live threshold values from the exported threshold gauges so you are not triaging against stale defaults.
2. **Measure capacity pressure**
   - Inspect `game_session_tick_scheduler_executor_queue_depth` and `game_session_tick_scheduler_pending_sessions`.
   - Correlate with tick execution duration metrics and any recent increase in active session count.
3. **Inspect runtime cause**
   - Review Game Session logs for scheduler-pressure summaries and threshold-reached warnings.
   - Use traces and dashboards to determine whether pressure is caused by long tick execution, downstream service latency, or unusually high command volume.
4. **Mitigate**
   - If pressure is caused by degraded downstream dependencies, handle those dependencies first.
   - If pressure is caused by sustained player/runtime load, scale the Game Session runtime or reduce the concurrent session load on the affected deployment.
   - Do not introduce ad hoc gameplay-timing changes during the incident; the current scheduler policy is observability-first rather than auto-throttling.
5. **Verify recovery**
   - Confirm the consecutive-cycle gauges return to `0`.
   - Confirm queue depth returns to a normal envelope and alert counters stop increasing.
   - Record which metric crossed threshold first so later threshold tuning has real evidence.

## Tick Replay Storm or Excessive Replays

### Detect (Tick replay storm)

- Metrics and dashboards show:
  - Elevated `gamesession_tick_replayed_total` relative to `gamesession_tick_executed_total` (or equivalent service-specific counters) for one or more regions.
  - `tick_effect_outcome_total{outcome="replay_ok"}` significantly higher than `tick_effect_outcome_total{outcome="first_apply"}` for specific `effect_type` or services.
  - Redis tail-loss metrics (`redis_coordination_tail_loss_ms`) repeatedly approaching or breaching the SLO envelope, indicating frequent coordination replays.
- Logs and traces:
  - Game Session and domain services log frequent idempotent replays or guard conflicts.
  - Jaeger traces for tick-driven flows show the same effect identities being attempted repeatedly.

### Decide (Tick replay storm)

- If replays are elevated only during a short-lived Redis incident already covered by the Redis incident runbook, prioritize resolving the underlying Redis problem and accept a temporary increase in replays.
- If replay rates remain high after Redis metrics and tail-loss have returned to normal:
  - Treat this as a domain-level idempotency or design issue in the services contributing the most `replay_ok` outcomes.
  - Focus on those services and effect types first; do not attempt broad coordination resets unless the ledger or coordination metrics also indicate corruption.

### Act (Tick replay storm)

1. **Identify hot services and effect types**
   - Use `tick_effect_outcome_total` dashboards to find:
     - Services with the highest `replay_ok` counts.
     - `effect_type` values that dominate replay traffic.
2. **Inspect domain idempotency behavior**
   - Review the relevant domain service docs and code to ensure:
     - Per-aggregate `last_tick_id` or operation-level guard tables (`tick_effect_guard`) are implemented as described in the tick architecture docs.
     - External side effects are separated via saga/outbox flows rather than being executed directly in tick-driven handlers.
3. **Use traces to pinpoint replays**
   - In Jaeger, search for spans tagged with the effect identity for the hot `effect_type` and inspect:
     - How many times the same effect identity is attempted.
     - Whether replays are driven by Redis tail-loss, downstream timeouts, or domain-level classification of errors.
4. **Mitigate and follow up**
   - For infrastructure-driven replays:
     - Investigate Redis tail-loss, database timeouts, or service saturation using the Redis and scaling runbooks.
   - For domain-driven replays:
     - Fix idempotency guards, error classification, or handler logic so that effects converge to `first_apply` with fewer retries.
   - Consider temporarily reducing tick fan-out or region density for heavily affected regions until replay rates normalize.

## Durable Commit/Coordination Cleanup Divergence

### Detect (durable commit/coordination cleanup divergence)

- Alert: `TickCleanupLagHigh` fires (`tick_cleanup_lag_ms` sustained above the configured threshold).
- Metrics and dashboards show:
  - `tick_durable_commit_total` continues increasing, but `tick_coordination_cleared_total` lags for the same regions.
  - `tick_cleanup_lag_ms` remains elevated for affected `<tenantId, regionId>` scopes.
- Logs and traces:
  - Game Session logs show repeated cleanup retries or failed transitions from durable commit to coordination-cleared.
  - `tick_execute` traces show long or repeated cleanup-related phases after durable state has been committed.

### Decide (durable commit/coordination cleanup divergence)

- If lag clears quickly and counters re-align, continue monitoring without intervention.
- If lag persists:
  - Treat as a coordination cleanup incident first (not a domain correctness incident) unless ledger/backlog signals also indicate stuck effects.
  - Prefer region-scoped remediation before tenant- or cluster-scoped actions.
- If cleanup lag is coupled with growing ledger backlog (`tick_effects_pending_total`) and stale `SCHEDULED` age, run replay-controller and ledger remediation flow in parallel.

### Act (durable commit/coordination cleanup divergence)

1. **Scope affected regions**
   - Identify regions where `tick_durable_commit_total - tick_coordination_cleared_total` stays non-zero and growing.
   - Correlate with `tick_cleanup_lag_ms` to confirm sustained divergence.
2. **Inspect cleanup path**
   - Check Game Session logs and traces for cleanup-token mismatches, Redis write failures, or retry exhaustion in cleanup phases.
   - Validate Redis health (latency, memory pressure, tail-loss) using Redis coordination dashboards.
3. **Apply scoped remediation**
   - For isolated regions, pause and resume tick scheduling to force a clean cleanup cycle.
   - If a region remains stuck, execute the region-scoped coordination reset flow in `system-architecture-redis-reset-and-recovery.md`.
   - If ledger backlog also accumulates, trigger ledger replay-controller remediation for the same scope.
4. **Verify convergence**
   - Confirm `tick_cleanup_lag_ms` returns to normal envelope.
   - Confirm `tick_coordination_cleared_total` catches up with durable commits for affected regions.
   - Ensure no sustained growth remains in `tick_effects_pending_total` for the remediated scope.

## Stuck Tick Effect Ledger Entries

### Detect (Stuck tick effect ledger entries)

- Dashboards and metrics show:
  - `tick_effects_pending_total{scope}` remaining high for specific regions even after coordination and domain metrics suggest normal operation.
  - `tick_effects_pending_oldest_age_seconds{scope}` exceeding `tick_effects_replay_convergence_budget_seconds{scope}`.
  - `tick_effects_replay_slo_breached{scope}` indicating replay is outside the normative convergence budget.
  - Replay fairness signals distinguish two failure shapes:
    - `tick_effects_pending_total > 0` while `tick_effects_replay_batches_total` does not advance for the same region, or `tick_effects_replay_starved{scope}` becomes `1`.
    - `tick_effects_replay_scan_lag_ms{scope}` grows for a subset of regions even though the controller is still making progress elsewhere.
- Logs and traces:
  - Game Session logs may show repeated attempts to process the same effects or gaps in processing for certain tick IDs.
  - Traces for those tick IDs show missing or incomplete spans for expected domain calls.

### Decide (Stuck tick effect ledger entries)

- Determine whether:
  - The ledger reflects truly stuck work (the domain effects have not been applied), or
  - Domain state has already converged and the ledger simply has not been updated to `APPLIED` or `ABANDONED`.
- If the backlog is confined to a single region and limited to a small number of tick IDs, prefer targeted remediation over broad resets.
- If many ticks across multiple regions share the same symptoms, consider whether a schema, deployment, or coordination issue is preventing ledger updates, and consult the Redis and tick architecture docs before taking action.

### Act (Stuck tick effect ledger entries)

1. **Inspect ledger and domain state**
   - Use SQL or service-level admin APIs to query `tick_effects` (or the equivalent ledger table) for the affected `<tenantId, regionId>`:
     - Identify the oldest `SCHEDULED` entries and their associated `tickId` and `effectKey`.
   - Compare `tick_effects_pending_oldest_age_seconds` with `tick_effects_replay_convergence_budget_seconds` to distinguish “brief replay delay” from “budget breach that requires active remediation”.
   - For a small sample, inspect domain state (for example entity HP, inventory, room state) to determine whether the effects have already been applied.
2. **Classify outcomes**
   - If domain state clearly reflects the intended effect:
     - Treat those ledger rows as **logically applied but not marked**.
   - If domain state does not reflect the effect and coordination queues are empty:
     - Treat those rows as genuinely stuck and decide whether to:
       - Re-enqueue work by re-running the appropriate tick flows, or
       - Mark them `ABANDONED` if the effect is no longer valid (expired sessions, deleted entities, or invalid commands).
   - Inspect replay fairness before choosing remediation:
     - If `tick_effects_replay_batches_total` is flat for the affected region, treat the controller as not servicing that region at all.
     - If replay batches are increasing elsewhere but `tick_effects_replay_scan_lag_ms` continues rising for the affected region, or `tick_effects_replay_starved` is asserted, treat the controller as unfair/starved rather than idle.
3. **Apply targeted remediation**
   - For “applied but not marked” rows:
     - Do not update ledger rows directly to `APPLIED` from ad hoc SQL, a generic admin endpoint, or a one-off script.
     - Run the service-owned verifier/reconcile path for the affected `EffectId` so the owning domain can prove the effect is already reflected in authoritative state or in its durable replay guard, then let that path transition the ledger row to `APPLIED` with outcome/reason `REPLAY_NOOP` and an audit record.
     - If no verifier exists for that effect family, treat the row as not safely proven and choose a safe re-run or service-owned dead-letter/quarantine path. Use `ABANDONED` only when authoritative evidence or declared feature policy proves non-application is valid; never invent a manual `APPLIED` correction.
   - For genuinely stuck rows:
     - If it is safe to re-run the effects, enqueue follow-up commands or trigger replay using the same idempotent handlers that tick execution uses.
     - If effects are no longer valid, mark rows `ABANDONED` with precise reasons (for example `EXPIRED`, `INVALID_TARGET`, `REGION_RESET_SCOPED`) so they stop appearing as pending.
     - If neither application nor abandonment is proven after technical retries are exhausted, move the service-owned recovery record to dead letter or quarantine while leaving the ledger outcome unresolved for supported disposition.
   - For replay-controller starvation:
     - Reduce per-region replay batch monopolization or other hot-region pressure first.
     - If the region remains starved, run scoped replay-controller remediation for the affected `<tenantId,regionId>` before escalating to broader reset actions.
4. **Prevent recurrence**
   - Review Game Session and domain handlers to ensure:
     - Ledger status transitions happen atomically with domain commits where required.
     - Errors that prevent ledger updates are surfaced clearly via logs, metrics, and traces.
   - Add or tighten alerts on `tick_effects_pending_total`, `tick_effects_replay_batches_total`, and `tick_effects_replay_scan_lag_ms` so both idle and unfair replay-controller behavior are detected earlier.

## Using Traces During Tick Incidents

For all of the scenarios above, Jaeger should be treated as a first-class diagnostic tool:

- Search for spans representing tick scheduling and execution (for example `tick_schedule`, `tick_execute`) filtered by `tenantId`, `regionId`, and, where available, `tickId`.
- For stalled regions, look for long-running or repeated spans for the same tick IDs and cross-reference with domain service spans to identify downstream bottlenecks.
- For replay storms, search by effect identity attributes (for example `effectKey`, `effect_type`) and verify how often the same identity appears in recent traces.

The Tracing architecture doc (`system-architecture-tracing.md`) includes example Jaeger queries and attribute conventions to make these investigations repeatable.
