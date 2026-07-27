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

This runbook is written for the target tick/region model (`tenantId` + `gameInstanceId` + `regionId`). Incident queries must carry `tenantId`, `gameInstanceId`, `regionId`, and `regionEpoch` together, using exact storage projections such as `game_instance_id` and `region_epoch` where applicable. If your current deployment only exposes coarser tick pause controls (for example pausing by `tenantId` + `gameInstanceId`), follow the same decision logic but apply it at the closest available scope and record the scope mismatch in the incident timeline for follow-up.

When applying scope substitution, use a deterministic authoritative mapping source (control-plane lookup or game-instance registry) to resolve region IDs, bind the resolved region set to that source's mapping generation or a maintenance lease, and keep that lease held through the complete operation. Where a lease cannot be held, every mutating step must use a CAS check against the captured mapping generation and complete set. Revalidate the generation/lease and complete set immediately before execution and at each CAS-fenced step. If the generation changes, the lease expires or is lost, or the set no longer matches, fail closed without executing any further stale substitution. Record the version-validated region set and mapping evidence in the incident notes so post-incident reconciliation is auditable. Resolve each region's current `regionEpoch` and lease fence separately from authoritative control-plane/runtime-health evidence.

If regional pause or reset controls are unavailable and a broader tenant/game-instance control is proposed as a substitute, the operator must first enumerate every affected game instance and region from the deterministic mapping source, bind that enumeration to its mapping generation or maintenance lease, and record the expected blast radius. Keep the lease held through the broader action or CAS-fence every mutating step to that generation and complete set. Revalidate the generation/lease and complete region set immediately before execution and at each CAS-fenced step; a changed, expired, lost, or mismatched binding fails the operation. The broader action requires an explicit blast-radius impact approval/gate before execution; the incident record must retain the approval identity, control request, version-validated resolved instance/region set, reason, and timing. Never silently substitute a broader pause or reset merely because a regional control is unavailable.

Bounded metrics identify the operational bucket (for example, stalled, replay pressure, or cleanup divergence); they do not by themselves authorize an exact region action. Before pausing or resetting a specific region, the operator must resolve the exact `<tenantId, gameInstanceId, regionId>` through the deterministic mapping source, then obtain the current `regionEpoch` and lease fence from authoritative control-plane/runtime-health evidence. If the mapping, health evidence, epoch, or fence is unavailable, stale, or mismatched, keep the scope paused and use the broader-scope approval gate rather than guessing from metric labels or Redis keys.

Canonical API and workflow scope names in this runbook use `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, and `regionEpoch`. SQL and storage examples may use `tenant_id`, `game_instance_id`, `playable_state_scope`, `region_id`, and `region_epoch`; these are aliases for the same fields, not different scopes.

Prometheus labels in this runbook are bounded categories only. `scope_class` identifies a controlled bucket such as `region`, `game_instance`, `tenant`, or `cluster`; it never contains a raw identifier. Operators resolve the exact tuple and current epoch/fence from durable tick-batch/ledger and control-plane records before taking action. Metrics select the incident family; they do not establish which game instance or region is authoritative.

## Incident Types

- **Stalled tick region** (lease held but no forward progress)
- **Tick replay storm or excessive replays**
- **Durable commit/coordination cleanup divergence**
- **Stuck tick effect ledger entries** (`SCHEDULED` rows that never converge)

Each scenario below assumes Redis/database metrics are wired according to the Redis and tick operations docs. If Grafana/Prometheus/Kibana/Jaeger is degraded, use fallback procedures from `system-architecture-observability-incident-runbook.md` and prioritize authoritative tick controls and service logs.

## Trace Preconditions (For Tick Root Cause)

Tick incidents often benefit from trace-level diagnosis, but mitigation must not block on trace availability.

All trace-specific guidance in this runbook is conditional on the environment advertising and proving the named workflow-tracing capability. Without that proof, use metrics and structured logs for detection, diagnosis, and mitigation.

Normal incident escalation groups by `<tenantId, gameInstanceId>`; `regionId` remains a diagnostic and reset-selection dimension within that group. Any wider tenant or cluster escalation must first enumerate the affected game instances and their regions from the authoritative mapping source and carry that exact version-validated blast radius through approval and execution.

- Metrics and structured logs are the dependable baseline in every environment; mitigation must proceed without traces.
- Use `tick_execute` / `tick_apply_effect` traces only when the environment advertises and proves the named workflow-tracing capability.
- Apply temporary service-scoped sampling escalation only when that control is advertised and proved, and record start/end times.
- Use collector tail-sampling by `tenantId`/`gameInstanceId`/`regionId` only when the environment advertises and proves scoped escalation; remove it after triage.
- If the relevant capability is absent or traces remain unavailable, continue with metrics and logs and proceed with region/tenant reset decisions using runbook thresholds.

## Stalled Tick Region

### Detect (Stalled tick region)

- Alerts fire on tick health, for example:
  - `tick_status{scope_class,status="STALLED"}` or `tick_status{scope_class,status="DEGRADED"}` being `1` for a sustained window.
  - `tick_execution_time_ms_p95` / `tick_execution_time_ms_p99` ratios vs `tick_lock_ttl_ms` exceeding the degraded thresholds described in `system-architecture-tick-concepts-and-invariants.md`.
- Redis coordination metrics and dashboards show:
  - A region holding `tick-executor-lease:{tenantRegionTag}` for longer than expected without advancing `tickId`.
  - Growing `tick_retry_queue_depth` or `tick_command_queue_depth` for the affected `<tenantId, gameInstanceId, regionId>`.
- Logs and optional workflow traces:
  - Game Session logs show repeated retries or warnings for the affected region.
  - When the Trace Preconditions are satisfied, Jaeger traces for `tick_execute` or equivalent spans show long durations or repeated retries for the same region.

### Decide (Stalled tick region)

- If the stall is brief and metrics already show recovery (status returns to `RUNNING`, queues drain, execution time ratios return to healthy ranges), continue to monitor without intervention.
- If the region remains stalled or degraded long enough that the shared tick-health paging conditions would still be firing for that scope, plan a **region-scoped** coordination reset for the affected `<tenantId, gameInstanceId, regionId>` as described in `system-architecture-redis-reset-and-recovery.md`.
  - In shared rulesets, this means the same conditions that would keep the finalized per-region tick-health paging alert active for that region, starting with sustained `tick_status{scope_class,status="STALLED"} == 1` and any environment overlay that pages on prolonged `DEGRADED` state.
  - Treat `tick_status{scope_class,status="STALLED"} == 1` sustained through the environment’s alert hold time as an intervention threshold by itself.
  - Also treat sustained `tick_status{scope_class,status="DEGRADED"} == 1` together with continued over-threshold `tick_execution_time_ms_p95` / `tick_execution_time_ms_p99` ratios versus `tick_lock_ttl_ms`, or continued growth in `tick_retry_queue_depth` / `tick_command_queue_depth`, as sufficient to intervene before the region flips fully to `STALLED`.
- Only escalate to a **tenant-scoped** or **cluster-wide** reset if multiple regions for the same `<tenantId, gameInstanceId>` group show similar symptoms or if Redis incident runbooks indicate broader coordination corruption, and only after enumerating every affected game instance and region and passing the explicit blast-radius approval/gate required above.

### Act (Stalled tick region)

1. **Quiesce tick work for the region**
   - Pause tick scheduling for the affected `<tenantId, gameInstanceId, regionId>` using the Game Session controls described in the tick architecture and Redis reset docs.
   - Ensure no new executor instances are attempting to acquire the region lease while you inspect metrics.
2. **Inspect metrics and optional workflow traces**
   - Use the Tick Health dashboard to confirm:
     - `tick_status` indicates stalled or degraded state.
     - `tick_execution_time_ms_*` ratios and queue depths support the stalled diagnosis.
   - When the Trace Preconditions are satisfied, use Jaeger to inspect `tick_execute` spans for this region to verify whether the stall is due to downstream services, coordination, or domain logic. Otherwise, use the correlated metrics and Game Session logs.
3. **Apply a region-scoped coordination reset**
   - Follow the **Per-region reset** flow in `system-architecture-redis-reset-and-recovery.md`, scoping the Job to:
     - `tick:{tenantRegionTag}:meta`
     - `tick:{tenantRegionTag}:pending`
     - `tick:{tenantRegionTag}:queue:<entityId>`
     - `tick:{tenantRegionTag}:lock:<entityId>`
     - `tick:{tenantRegionTag}:session-binding:<entityId>` (the narrow region-authoritative session-to-region bridge; preserved gameplay sessions must rebind after cleanup)
     - `timer:{tenantRegionTag}`
     - `retry:{tenantRegionTag}`
     - `tick-executor-lease:{tenantRegionTag}`
     - After Game Session cleanup, invoke an Automation & Scripting-scoped cleanup/rebuild for `automation:timer:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId`; Automation & Scripting owns those prefixes and rebuilds them from durable schedules, trigger-instance rows, and the active status/progress adapter.
     - `tick-events-lease:{tenantRegionTag}`, `tick-events:{tenantRegionTag}`, and all per-consumer `tick-events-offset:{tenantRegionTag}:<consumerId>` keys as reset-tolerant observer hints; consumers reacquire leases and re-establish baselines from the active status/progress adapter and durable domain state.
   - Do not delete domain data or non-coordination prefixes.
4. **Resume ticks and verify recovery**
   - Resume tick scheduling for the region only after both Game Session coordination cleanup and the Automation & Scripting cleanup/rebuild have completed, followed by the canonical post-reset smoke gate.
   - Confirm via dashboards that:
     - `tick_status{scope_class,status="RUNNING"}` is `1`.
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
   - When the Trace Preconditions are satisfied, use workflow traces and dashboards to determine whether pressure is caused by long tick execution, downstream service latency, or unusually high command volume. Otherwise, use correlated metrics and structured logs.
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
- Logs and optional workflow traces:
  - Game Session and domain services log frequent idempotent replays or guard conflicts.
  - When the Trace Preconditions are satisfied, Jaeger traces for tick-driven flows show the same effect identities being attempted repeatedly.

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
3. **Use proved workflow traces to pinpoint replays**
   - When the Trace Preconditions are satisfied, search Jaeger for spans tagged with the effect identity for the hot `effect_type` and inspect:
     - How many times the same effect identity is attempted.
     - Whether replays are driven by Redis tail-loss, downstream timeouts, or domain-level classification of errors.
   - Otherwise, use replay counters, Redis tail-loss metrics, and structured service logs for the same diagnosis.
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
  - `tick_cleanup_lag_ms` remains elevated for affected `<tenantId, gameInstanceId, regionId>` scopes.
- Logs and optional workflow traces:
  - Game Session logs show repeated cleanup retries or failed transitions from durable commit to coordination-cleared.
  - When the Trace Preconditions are satisfied, `tick_execute` traces show long or repeated cleanup-related phases after durable state has been committed.

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
   - Check Game Session logs for cleanup-token mismatches, Redis write failures, or retry exhaustion in cleanup phases. When the Trace Preconditions are satisfied, correlate those findings with workflow traces.
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
  - `tick_effects_pending_total{scope_class}` remaining high for an approved bounded scope bucket even after coordination and domain metrics suggest normal operation.
  - `tick_effects_pending_oldest_age_seconds{scope_class}` exceeding `tick_effects_replay_convergence_budget_seconds{scope_class}`.
  - `tick_effects_replay_slo_breached{scope_class}` indicating replay is outside the normative convergence budget.
  - Replay fairness signals distinguish two failure shapes:
    - `tick_effects_pending_total > 0` while `tick_effects_replay_batches_total` does not advance for the same region, or `tick_effects_replay_starved{scope_class}` becomes `1`.
    - `tick_effects_replay_scan_lag_ms{scope_class}` grows for a subset of regions even though the controller is still making progress elsewhere.
- Logs and optional workflow traces:
  - Game Session logs may show repeated attempts to process the same effects or gaps in processing for certain tick IDs.
  - When the Trace Preconditions are satisfied, traces for those tick IDs show missing or incomplete spans for expected domain calls.

### Decide (Stuck tick effect ledger entries)

- Determine whether:
  - The ledger reflects truly stuck work (the domain effects have not been applied), or
  - Domain state has already converged and the ledger simply has not been updated to `APPLIED` or `ABANDONED`.
- If the backlog is confined to a single region and limited to a small number of tick IDs, prefer targeted remediation over broad resets.
- If many ticks across multiple regions share the same symptoms, consider whether a schema, deployment, or coordination issue is preventing ledger updates, and consult the Redis and tick architecture docs before taking action.

### Act (Stuck tick effect ledger entries)

Normal replay and re-enqueue in this section apply only to effects from the current authoritative `regionEpoch`. An old-epoch effect is never replayed through the current-epoch handlers merely because it remains `SCHEDULED`.

1. **Inspect ledger and domain state**
   - Use SQL or service-level admin APIs to query `tick_effects` (or the equivalent ledger table) for the exact affected `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>` scope; do not mix rows from another `playableStateScope` or `regionEpoch`:
     - Identify the oldest `SCHEDULED` entries and carry each complete `EffectId` tuple: `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId>`. `effectKey` is part of the identity, not a replacement for the other tuple fields.
   - The exact complete `EffectId` tuple is authoritative for effect-level replay and reconciliation only. Phase-specific execution identities remain authoritative in their owning phase records; do not use an `EffectId` to replace admission, staging, commit, or follow-up-leg identity.
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
     - For a current-epoch row, run the service-owned verifier/reconcile path selected by the effect family using that exact `EffectId` tuple as its input, including `effectKey` and target aggregate identity, within the exact `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>` scope. The owning domain must prove the effect is already reflected in authoritative state or in its durable replay guard, then let that path transition the ledger row to `APPLIED` with an audit/`tick_effect_outcome_total{outcome="replay_ok"}` record when the domain call is a replay no-op. Replay no-op is an attempt outcome, not a third ledger status. Do not reconcile the same effect identity across region epochs.
     - If no verifier exists for that effect family, treat the row as not safely proven and choose re-run or `ABANDONED` remediation instead of inventing a manual `APPLIED` correction.
   - For genuinely stuck current-epoch rows:
     - If it is safe to re-run the effects, enqueue follow-up commands or trigger replay using the same idempotent handlers that tick execution uses.
     - If effects are no longer valid, mark rows `ABANDONED` with precise reasons (for example `EXPIRED`, `INVALID_TARGET`, `REGION_RESET_SCOPED`) so they stop appearing as pending.
   - For old-epoch rows, use the authority-fenced attestation under the original `EffectId` described in [Inconclusive Old-Epoch Reconciliation Policy](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy). Any business continuation across the epoch boundary must use an explicitly approved maintenance or saga/outbox path that creates a new current-epoch identity; it must not re-drive the original effect through normal replay.
   - For replay-controller starvation:
     - Reduce per-region replay batch monopolization or other hot-region pressure first.
     - If the region remains starved, run scoped replay-controller remediation for the affected `<tenantId,gameInstanceId,regionId,regionEpoch>` before escalating to broader reset actions.
4. **Prevent recurrence**
   - Review Game Session and domain handlers to ensure:
     - Ledger status transitions happen atomically with domain commits where required.
     - Errors that prevent ledger updates are surfaced clearly via logs and metrics, with workflow traces when the Trace Preconditions are satisfied.
   - Add or tighten alerts on `tick_effects_pending_total`, `tick_effects_replay_batches_total`, and `tick_effects_replay_scan_lag_ms` so both idle and unfair replay-controller behavior are detected earlier.

## Using Proved Workflow Traces During Tick Incidents

For all of the scenarios above, workflow traces are optional diagnostics rather than the operational baseline:

For effect-level replay and reconciliation, the only exact correlation key is the complete canonical `EffectId` tuple: `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId>`. `effectKey`, `targetAggregateType`, and the exact `targetAggregateId` are part of that identity; `effect_type` and other trace attributes are exploratory filters only and cannot establish an exact effect match, authorize reconciliation, or justify a ledger transition. Command, source-claim, coordinator, and completion identities remain authoritative for their own execution phases and must not be replaced by `EffectId`.

- Only when the environment advertises and proves the named workflow-tracing capability, use Jaeger to search for spans representing tick scheduling and execution (for example `tick_schedule`, `tick_execute`) filtered by `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, and, where available, `tickId`.
- When that capability is proved, inspect stalled regions for long-running or repeated spans for the same tick IDs and cross-reference domain service spans to identify downstream bottlenecks.
- When that capability is proved, use trace attributes such as `effect_type`, `effectKey`, and target fields only to find candidate spans, then verify every candidate against the full durable `EffectId` tuple before correlating or reconciling it.
- If the capability is absent or unproved, use metrics and structured logs for each of these investigations and do not delay mitigation for trace collection.

The Tracing architecture doc (`system-architecture-tracing.md`) includes example Jaeger queries and attribute conventions to make these investigations repeatable.
