# FireMUD Tick Incident Runbook

This runbook describes operator actions for **tick-related incidents**, including stalled regions, replay storms, durable commit/coordination cleanup divergence, and stuck tick effect ledger entries.

For the detailed tick design, see:

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-tick-concepts-and-invariants.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`

Validation and runtime-proof selection for changes to this runbook follows the shared [Validation and Runtime Proof workflow](../developer-workflows/validation-and-runtime-proof.md); record execution results in PR/CI evidence or the owning implementation tracker.

Redis coordination behavior and reset flows are defined in:

- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-redis-reset-and-recovery.md`

## Implementation Notes

This runbook is written for the target tick/region model (`tenantId` + `gameInstanceId` + `playableStateNamespaceId` + `playableStateScope` + `regionId` + `regionEpoch`). Incident queries must carry that complete tuple together, using exact storage projections such as `game_instance_id`, `playable_state_namespace_id`, `playable_state_scope`, and `region_epoch` where applicable. If your current deployment only exposes coarser tick pause controls (for example pausing by `tenantId` + `gameInstanceId`), follow the same decision logic but apply it at the closest available scope and record the scope mismatch in the incident timeline for follow-up.

When applying scope substitution, use a deterministic authoritative mapping source (control-plane lookup or game-instance registry) to resolve the complete namespace/scope/region tuple, bind the resolved region set to that source's mapping generation or a maintenance lease, and keep that lease held through the complete operation. Where a lease cannot be held, every mutating step must use a CAS check against the captured mapping generation and complete set. Revalidate the generation/lease and complete set immediately before execution and at each CAS-fenced step. If the generation changes, the lease expires or is lost, or the set no longer matches, fail closed without executing any further stale substitution. Record the version-validated namespace/scope/region set and mapping evidence in the incident notes so post-incident reconciliation is auditable. Resolve each region's current `regionEpoch` and lease fence separately from authoritative control-plane/runtime-health evidence.

If regional pause or reset controls are unavailable and a broader tenant/game-instance control is proposed as a substitute, the operator must first enumerate every affected game instance, namespace/scope pair, and region from the deterministic mapping source, bind that enumeration to its mapping generation or maintenance lease, and record the expected blast radius. Keep the lease held through the broader action or CAS-fence every mutating step to that generation and complete set. Revalidate the generation/lease and complete namespace/scope/region set immediately before execution and at each CAS-fenced step; a changed, expired, lost, or mismatched binding fails the operation. The broader action requires an explicit blast-radius impact approval/gate before execution; the incident record must retain the approval identity, control request, version-validated resolved instance/namespace/scope/region set, reason, and timing. Never silently substitute a broader pause or reset merely because a regional control is unavailable.

Bounded metrics identify the operational bucket (for example, stalled, replay pressure, or cleanup divergence); they do not by themselves authorize an exact region action. Before pausing or resetting a specific region, the operator must resolve the exact `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` through the deterministic mapping source and authoritative runtime evidence, then obtain the current lease fence. If the mapping, namespace/scope evidence, health evidence, epoch, or fence is unavailable, stale, or mismatched, keep the scope paused and use the broader-scope approval gate rather than guessing from metric labels or Redis keys.

Canonical API and workflow scope names in this runbook use `tenantId`, `gameInstanceId`, `playableStateNamespaceId`, `playableStateScope`, `regionId`, and `regionEpoch`. SQL and storage examples may use `tenant_id`, `game_instance_id`, `playable_state_namespace_id`, `playable_state_scope`, `region_id`, and `region_epoch`; these are aliases for the same fields, not different scopes.

Prometheus labels in this runbook are bounded categories only. `scope_class` is one of the controlled aggregation classes `region`, `game_instance`, `tenant`, or `cluster`; it never contains a raw identifier or identifies an individual region. Operators resolve the exact tuple and current epoch/fence from durable tick-batch/ledger and control-plane records before taking action. Metrics select the incident family; they do not establish which game instance or region is authoritative.

The canonical mode-aware execution/TTL/ratio metric families are target-only and currently unavailable in the live Game Session producer: absent or stale `tick_execution_time_ms_*`, `tick_lock_ttl_ms`, `solo_lock_ttl_ms`, or derived ratio series are `unknown` and cannot authorize a pause, reset, degradation, or resume decision. Use authoritative runtime-health/control-plane evidence and structured logs until producer and bounded-label proof exists.

## Incident Types

- **Stalled tick region** (lease held but no forward progress)
- **Tick replay storm or excessive replays**
- **Durable commit/coordination cleanup divergence**
- **Stuck tick effect ledger entries** (`SCHEDULED` rows that never converge)

Each scenario below assumes Redis/database metrics are wired according to the Redis and tick operations docs. If Grafana/Prometheus/Kibana/Jaeger is degraded, use fallback procedures from `system-architecture-observability-incident-runbook.md` and prioritize authoritative tick controls and service logs.

## Trace Preconditions (For Tick Root Cause)

Tick incidents often benefit from trace-level diagnosis, but mitigation must not block on trace availability.

All trace-specific guidance in this runbook is conditional on the environment advertising and proving the named workflow-tracing capability. Without that proof, use metrics and structured logs for detection, diagnosis, and mitigation.

Normal incident escalation groups by `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope>`; `regionId` and `regionEpoch` remain diagnostic and reset-selection dimensions within that exact namespace/scope group. Any wider tenant or cluster escalation must first enumerate the affected game instances, namespace/scope pairs, and regions from the authoritative mapping source and carry that exact version-validated blast radius through approval and execution.

- Metrics and structured logs are the dependable baseline in every environment; mitigation must proceed without traces.
- Use `tick_execute` / `tick_apply_effect` traces only when the environment advertises and proves the named workflow-tracing capability. The approximately 1% value is only a calibration seed for high-volume entry paths, not a universal sampling rule or correctness boundary; use that seed only when the named capability is advertised and proven. Otherwise do not use tracing or the calibration seed; no explicit disable action is required.
- Apply temporary service-scoped sampling escalation only when that control is advertised and proved, with a positive TTL/lease, durable rollback/reconciliation that survives operator loss, safe policy reload/rollback, the exact affected service/workflow scope, incident identity, start time, volume budget, automatic expiry, recorded completion, and verified reversion. Remove the elevated policy automatically at the declared deadline. If removal, reload, or rollback fails, the expired elevated configuration is not terminal “last valid” state: continue durable reconciliation, use an emergency disable-to-baseline path when available (with safe reload/rollback and verification), and keep completion pending until measured baseline sampling is restored and proven.
- Use collector tail-sampling by the exact `tenantId`/`gameInstanceId`/`playableStateNamespaceId`/`playableStateScope`/`regionId`/`regionEpoch` tuple only when the environment advertises and proves scoped escalation; require a positive TTL/lease, durable rollback/reconciliation that survives operator loss, safe policy reload/rollback, and record the exact scope, incident identity, start time, volume budget, automatic expiry, completion, and verified reversion. Remove the policy automatically at the declared deadline. If removal, reload, or rollback fails, the expired elevated policy is not terminal “last valid” state: continue durable reconciliation, use an emergency disable-to-baseline path when available (with safe reload/rollback and verification), and keep completion pending until measured baseline sampling is restored and proven.
- If the relevant capability is absent or traces remain unavailable, continue with metrics and logs. In target deployments, use authoritative runtime-health evidence plus runbook thresholds for the target-only region/tenant reset decision; in current deployments, thresholds are diagnostic only and do not authorize a reset. Preserve evidence, keep the affected scope fenced or stopped, use read-only inspection, and escalate through the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback).
- Missing sampled traces are not evidence that a tick/effect did not execute.

## Stalled Tick Region

### Detect (Stalled tick region)

- Alerts fire on tick health, for example:
  - `tick_status{scope_class,status="STALLED"}` or `tick_status{scope_class,status="DEGRADED"}` being `1` for a sustained window; this is a bounded class-level detection/escalation rollup, not an individual-region status.
  - Only when the target-only mode-aware metric capability is advertised and proven, and all required series are present and fresh, use mode-specific ratios such as `tick_execution_time_ms_p95{scope_class,tick_mode="normal"} / on (scope_class) tick_lock_ttl_ms{scope_class}` or `tick_execution_time_ms_p99{scope_class,tick_mode="normal"} / on (scope_class) tick_lock_ttl_ms{scope_class}`, with the corresponding solo forms matched as `tick_execution_time_ms_p95{scope_class,tick_mode="solo"} / on (scope_class) solo_lock_ttl_ms{scope_class}` or `tick_execution_time_ms_p99{scope_class,tick_mode="solo"} / on (scope_class) solo_lock_ttl_ms{scope_class}`, exceeding the degraded thresholds described in `system-architecture-tick-concepts-and-invariants.md`; these ratios identify pressure for investigation, not the authoritative region or action. If the capability or any required series is unavailable or stale, treat the ratios as unknown and direct current incidents to authoritative runtime-health/control-plane evidence and structured logs.
- Redis coordination metrics and dashboards show:
  - A region holding `tick-executor-lease:{tenantRegionTag}` for longer than expected without advancing `tickId`.
  - Growing `tick_retry_queue_depth{scope_class}` or `tick_command_queue_depth{scope_class}` for an approved bounded pressure bucket; `scope_class` is not the exact `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` tuple. Before any region action, resolve that exact tuple from the durable tick ledger and authoritative runtime-health/control-plane records.
- Logs and optional workflow traces:
  - Game Session logs show repeated retries or warnings for the affected region.
  - When the Trace Preconditions are satisfied, Jaeger traces for `tick_execute` or equivalent spans show long durations or repeated retries for the same region.

### Decide (Stalled tick region)

- If the stall is brief and authoritative runtime-health evidence shows recovery to `RUNNING` (with queues draining and class-level execution-time ratios returning to healthy ranges), continue to monitor without intervention.
- **Target state only, once implemented and proven:** If the authoritative runtime-health/control-plane record reports the exact region as `STALLED` or `DEGRADED` long enough to require action, use the class-level tick-health paging conditions as supporting detection/escalation evidence and plan a **region-scoped** coordination reset for the affected `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` as described in `system-architecture-redis-reset-and-recovery.md`.
  - **Current deployments:** These thresholds classify the incident and escalation severity only; they do not authorize a reset. Use read-only inspection, preserve AOF and incident evidence, keep the affected scope fenced or stopped, and escalate through the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback).
  - In shared rulesets, sustained `tick_status{scope_class,status="STALLED"} == 1`, a prolonged `DEGRADED` rollup, or continued queue/ratio pressure starts exact-scope enumeration and authoritative status lookup; none of these class-level signals alone identifies a region, sets its status, or authorizes intervention.
  - Treat the control-plane/runtime-health status and its forward-progress evidence as the intervention threshold. The current live authority is `GetRuntimeOwnershipStatus` with `ObserveRuntimeTickProgress`; target state is `RegionStatus` through `GetRegionTickStatus`.
  - **Target state only, once implemented and proven:** Treat `tick_status{scope_class,status="STALLED"} == 1` sustained through the environment’s alert hold time as a class-level trigger for exact-scope enumeration and authoritative status lookup, not as an intervention threshold by itself. Sustained `DEGRADED` plus over-threshold execution-time ratios or queue growth may raise escalation priority, but region action still requires the exact authoritative region tuple/status above; current deployments treat these class-level signals as diagnostic/escalation evidence only.
- **Target state only, once implemented and proven:** Only escalate to a **tenant-scoped** or **cluster-wide** reset if multiple regions for the same `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope>` group show similar symptoms or if Redis incident runbooks indicate broader coordination corruption, and only after enumerating every affected game instance, namespace/scope pair, and region and passing the explicit blast-radius approval/gate required above.
- **Current deployments:** A broader tenant/cluster blast-radius determination is evidence and escalation only; it does not authorize a reset or broader pause. Enumerate the affected scope from authoritative mapping evidence, preserve evidence, keep affected scopes fenced, and use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback).

### Act (Stalled tick region)

1. **Quiesce tick work for the region**
   - **Target state only, once implemented and proven:** Pause tick scheduling for the affected `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` through the canonical region-scoped control and coordination authority; missing or mismatched namespace/scope/epoch evidence fails closed.
   - **Current deployments:** If quiescing is required, use only the shipped `PauseTicksForScope` at its current `{tenantId, gameInstanceId}` boundary; it is an instance-scoped pause, not a region-scoped pause. Do not infer or substitute a region, tenant, or cluster pause; any broader target action requires the documented authoritative mapping, coordination evidence, and blast-radius approval/gate.
   - **Target state only, once implemented and proven:** Ensure no new executor instances are attempting to acquire the region lease while you inspect metrics.
   - **Current deployments:** Confirm any instance-scoped pause through `GetRuntimeOwnershipStatus` before inspection, and keep affected coordination writers fenced or stopped.
2. **Inspect metrics and optional workflow traces**
   - Use the Tick Health dashboard to confirm:
     - The authoritative runtime-health/control-plane record identifies the exact region as stalled or degraded.
     - `tick_status` rollups, `tick_execution_time_ms_*` ratios, and queue depths support the diagnosis.
   - When the Trace Preconditions are satisfied, use Jaeger to inspect `tick_execute` spans for this region to verify whether the stall is due to downstream services, coordination, or domain logic. Otherwise, use the correlated metrics and Game Session logs.
3. **Apply a region-scoped coordination reset**
   - **Current deployments:** Do not execute this reset or mutate any listed prefix/raw coordination key. Preserve AOF, operation records, and incident evidence; keep the affected scope fenced or stopped; use only the shipped pause/status boundary and read-only inspection; escalate through the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback).
   - **Target state only, once implemented and proven:** Follow the **Per-region reset** flow in `system-architecture-redis-reset-and-recovery.md`, scoping the Job to:
     - `tick:{tenantRegionTag}:meta`
     - `tick:{tenantRegionTag}:pending`
     - `tick:{tenantRegionTag}:queue:<entityId>`
     - `tick:{tenantRegionTag}:lock:<entityId>`
     - `tick:{tenantRegionTag}:session-binding:<entityId>` (the narrow region-authoritative session-to-region bridge; preserved gameplay sessions must rebind after cleanup)
     - `timer:{tenantRegionTag}`
     - `retry:{tenantRegionTag}`
     - `tick-executor-lease:{tenantRegionTag}`
     - **Target state only, once implemented and proven:** after Game Session cleanup, invoke an Automation & Scripting-scoped cleanup/rebuild for `automation:timer:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId`; Automation & Scripting will own those prefixes and rebuild them from durable schedules, trigger-instance rows, and the active status/progress adapter. These target projections are not implemented in the current runtime, so current deployments must not attempt this cleanup/rebuild; keep the affected scope fenced and use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback) instead.
     - `tick-events-lease:{tenantRegionTag}`, `tick-events:{tenantRegionTag}`, and all per-consumer `tick-events-offset:{tenantRegionTag}:<consumerId>` keys as reset-tolerant observer hints; consumers reacquire leases and re-establish baselines from the active status/progress adapter and durable domain state.
   - **Target state only, once implemented and proven:** Do not delete domain data or non-coordination prefixes.
4. **Resume ticks and verify recovery**
   - **Target state only, once implemented and proven:** Resume tick scheduling for the region only after Game Session coordination cleanup and, **once the target Automation projections exist**, the Automation & Scripting cleanup/rebuild have completed, followed by the canonical post-reset smoke gate and all applicable recovery release gates. The current operator fallback has no executable Automation cleanup/rebuild and must not claim this target gate is complete.
   - Confirm via dashboards that:
     - The authoritative runtime-health/control-plane record reports the exact region as `RUNNING`.
     - `tick_status{scope_class,status="RUNNING"}` and `tick_execution_time_ms_*` ratios fall back into healthy class-level envelopes.
     - Command and retry queue depths stabilize.
   - Review `tick_effects_pending_total{scope_class}` for the approved bounded scope bucket to ensure the ledger is draining and not accumulating new stuck rows.
   - **Current deployments:** Do not resume or reopen the affected scope after this reset path. Use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback): keep the scope fenced or stopped, use only the shipped pause/status surface and read-only inspection, preserve evidence, and escalate.

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
- Elevated `gamesession_tick_replayed_total{scope_class}` relative to `gamesession_tick_executed_total{scope_class}` (or equivalent service-specific counters) for one or more approved bounded scope buckets; high counters alone may represent bounded draining and are not a domain idempotency/design finding.
  - `tick_effect_outcome_total{outcome="replay_ok"}` significantly higher than `tick_effect_outcome_total{outcome="first_apply"}` for specific `effect_type` or services.
  - Durable-work evidence shows `tick_effects_replay_slo_breached{scope_class}` asserted and/or `tick_effects_pending_oldest_age_seconds{scope_class}` growing or remaining elevated; this is the evidence required to distinguish a replay breach from bounded draining.
  - The measured Redis unreplicated-write metric (`redis_unreplicated_write_window_ms` or its deployment-specific equivalent) repeatedly approaching or breaching `redis_unreplicated_write_window_slo_ms`, indicating frequent coordination replays.
- Logs and optional workflow traces:
  - Game Session and domain services log frequent idempotent replays or guard conflicts.
  - When the Trace Preconditions are satisfied, Jaeger traces for tick-driven flows show the same effect identities being attempted repeatedly.

### Decide (Tick replay storm)

- If replays are elevated only during a short-lived Redis incident already covered by the Redis incident runbook, prioritize resolving the underlying Redis problem and apply ADR 0058 class-specific outcomes while accepting a temporary increase in replays.
- If Redis exposure has normalized but `tick_effects_replay_slo_breached{scope_class}` remains asserted and/or `tick_effects_pending_oldest_age_seconds{scope_class}` remains elevated across the replay convergence window, with `gamesession_tick_replayed_total{scope_class}` rates still high:
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
     - Whether replays are driven by Redis unreplicated-write exposure, downstream timeouts, or domain-level classification of errors.
   - Otherwise, use replay counters, Redis exposure metrics, and structured service logs for the same diagnosis.
4. **Mitigate and follow up**
   - For infrastructure-driven replays:
     - Investigate Redis unreplicated-write exposure, database timeouts, or service saturation using the Redis and scaling runbooks.
   - For domain-driven replays:
     - Fix idempotency guards, error classification, or handler logic so that effects converge to `first_apply` with fewer retries.
   - Consider temporarily reducing tick fan-out or region density for heavily affected regions until replay rates normalize.

## Durable Commit/Coordination Cleanup Divergence

### Detect (durable commit/coordination cleanup divergence)

- Alert: `TickCleanupLagHigh` fires (`tick_cleanup_lag_ms{scope_class}` sustained above the configured threshold).
- Metrics and dashboards show:
  - `tick_durable_commit_total{scope_class}` continues increasing, but `tick_coordination_cleared_total{scope_class}` lags for the same approved bounded scope buckets.
  - `tick_cleanup_lag_ms{scope_class}` remains elevated for a bounded class rollup; use durable ledger/runtime records to identify affected `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` scopes.
- Logs and optional workflow traces:
  - Game Session logs show repeated cleanup retries or failed transitions from durable commit to coordination-cleared.
  - When the Trace Preconditions are satisfied, `tick_execute` traces show long or repeated cleanup-related phases after durable state has been committed.

### Decide (durable commit/coordination cleanup divergence)

- If lag clears quickly and counters re-align, continue monitoring without intervention.
- If lag persists:
  - Treat as a coordination cleanup incident first (not a domain correctness incident) unless ledger/backlog signals also indicate stuck effects.
  - Prefer region-scoped remediation before tenant- or cluster-scoped actions.
- **Target state only, once implemented and proven:** If cleanup lag is coupled with growing ledger backlog (`tick_effects_pending_total{scope_class}`) and stale `SCHEDULED` age, the recovery controller may run replay-controller and ledger remediation flows in parallel. Current operators must use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback) instead.

### Act (durable commit/coordination cleanup divergence)

1. **Scope affected regions**
   - Identify approved bounded scope buckets where `tick_durable_commit_total{scope_class} - tick_coordination_cleared_total{scope_class}` stays non-zero and growing.
   - Correlate with `tick_cleanup_lag_ms{scope_class}` to confirm sustained divergence.
2. **Inspect cleanup path**
   - Check Game Session logs for cleanup-token mismatches, Redis write failures, or retry exhaustion in cleanup phases. When the Trace Preconditions are satisfied, correlate those findings with workflow traces.
   - Validate Redis health (latency, memory pressure, and unreplicated-write exposure) using Redis coordination dashboards.
3. **Apply scoped remediation**
   - **Target state only, once implemented and proven:** For isolated regions, pause and resume tick scheduling to force a clean cleanup cycle.
   - **Target state only, once implemented and proven:** If a region remains stuck, execute the region-scoped coordination reset flow in `system-architecture-redis-reset-and-recovery.md`.
   - **Target state only, once implemented and proven:** If ledger backlog also accumulates, trigger ledger replay-controller remediation for the same scope.
   - **Current deployments:** Do not pause/resume to force cleanup, execute a scoped reset, or trigger replay-controller remediation. Use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback): read status, preserve AOF and incident evidence, keep the affected scope fenced, and escalate.
4. **Verify convergence**
   - Confirm `tick_cleanup_lag_ms{scope_class}` returns to normal envelope.
   - Confirm `tick_coordination_cleared_total{scope_class}` catches up with durable commits for affected approved bounded scope buckets.
   - Ensure no sustained growth remains in `tick_effects_pending_total{scope_class}` for the remediated approved bounded scope bucket.

## Stuck Tick Effect Ledger Entries

### Detect (Stuck tick effect ledger entries)

- Dashboards and metrics show:
  - `tick_effects_pending_total{scope_class}` remaining high for an approved bounded scope bucket even after coordination and domain metrics suggest normal operation.
  - `tick_effects_pending_oldest_age_seconds{scope_class}` exceeding `tick_effects_replay_convergence_budget_seconds{scope_class}`.
  - `tick_effects_replay_slo_breached{scope_class}` indicating replay is outside the normative convergence budget.
  - Replay fairness signals distinguish two failure shapes:
    - `tick_effects_pending_total{scope_class} > 0` while `tick_effects_replay_batches_total{scope_class}` does not advance for the same approved bounded scope bucket, or `tick_effects_replay_starved{scope_class}` becomes `1`.
    - `tick_effects_replay_scan_lag_ms{scope_class}` grows for a bounded class rollup even though the controller is still making progress elsewhere; use durable ledger/runtime records to identify any affected regions.
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
   - For target-state S1/S2, use SQL or service-level admin APIs to query `tick_effects` (or the equivalent ledger table) for the exact affected `<tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch>` scope; do not mix rows from another `playableStateNamespaceId`, `playableStateScope`, or `regionEpoch`. The current-live schema may expose only the documented narrower `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>` scope; that limitation must remain explicit rather than being treated as namespace-complete S1/S2 evidence:
     - Identify the oldest `SCHEDULED` entries and, for target-state S1/S2, carry and exact-validate each complete `tick_effects` ledger projection: `<effect_id, root_effect_id, typed_operation, tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id>`. This storage projection is keyed by the persisted mutation `EffectId` and retains any enclosing root `EffectId` as lineage/reconciliation context; `playableStateNamespaceId` is a required exact-bound S1/S2 field, and `effectKey` is a ledger/lookup field, not a replacement for the other tuple fields. Current-live may lack that namespace field at its boundary and therefore cannot claim the target-state projection is complete.
   - The complete ledger projection, together with its linked mutation `EffectId`, enclosing-root lineage, and participant-guard evidence, is authoritative for effect-level replay and reconciliation only. Phase-specific execution identities remain authoritative in their owning phase records; do not use an `EffectId` to replace admission, staging, commit, or follow-up-leg identity.
   - Compare `tick_effects_pending_oldest_age_seconds{scope_class}` with `tick_effects_replay_convergence_budget_seconds{scope_class}` to distinguish “brief replay delay” from “budget breach that requires active remediation”.
   - For a small sample, inspect domain state (for example entity HP, inventory, room state) to determine whether the effects have already been applied.
2. **Classify outcomes**
   - If complete authoritative domain state or participant-guard evidence clearly reflects the intended effect:
     - Treat those ledger rows as **logically applied but not marked**; state or guard evidence must be bound to the complete ledger projection and current authority before reconciliation.
   - If domain state does not reflect the effect, do not infer that it is unapplied from empty coordination queues, a missing response, a timeout, or retry exhaustion:
     - Re-enqueue work only after durable authoritative evidence proves that no required mutation succeeded and replay is safe under the applicable current-epoch policy.
     - Mark a row `ABANDONED` only after durable authoritative evidence proves that no required mutation succeeded and the existing recovery policy says the effect is no longer valid (expired sessions, deleted entities, or invalid commands).
     - Otherwise retain the row non-terminal as `SCHEDULED`/reconciliation-required and escalate for evidence-qualified reconciliation.
   - Inspect replay fairness before choosing remediation:
   - If `tick_effects_replay_batches_total{scope_class}` is flat for the affected approved bounded scope bucket, treat the controller as not servicing that bucket at all.
   - If replay batches are increasing elsewhere but `tick_effects_replay_scan_lag_ms{scope_class}` continues rising for the affected approved bounded scope bucket, or `tick_effects_replay_starved{scope_class}` is asserted, treat the controller as unfair/starved rather than idle.
3. **Apply targeted remediation**
   - For “applied but not marked” rows:
     - Do not update ledger rows directly to `APPLIED` from ad hoc SQL, a generic admin endpoint, or a one-off script.
     - Select the verifier/reconcile branch from the deployment mode; do not require target-state fields from the current-live schema or weaken the target-state gate:
       - **Current-live:** Run the service-owned verifier for the exact current-live ledger projection and its persisted mutation `EffectId` (retaining any enclosing root as lineage), including `effectKey` and target aggregate identity, within the narrower `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>` scope. Because `playableStateNamespaceId` is not persisted at this current-live boundary, this branch cannot establish namespace-complete S1/S2 binding and must not infer the namespace from `gameInstanceId`. Read `GetRuntimeOwnershipStatus` (the current instance-scoped ownership boundary) and require the current `regionEpoch` and opaque `executorFence` to match the batch/ledger ownership snapshot. Verify that the current selected-work manifest and its `manifest_digest` match the durable source and ledger projections and the expected participant evidence. This branch may be processed without `sealed_execution_context_digest` or `sealed_execution_context_ref`, which are not present at the current-live boundary. The service-owned verifier must atomically CAS the current owner/epoch/fence, require complete manifest and participant-evidence set equality, transition the matching `SCHEDULED` row(s) to `APPLIED`, and record replay-verification evidence (including the evidence digest/reference and `replay_ok` audit) in one durable transaction. Missing, extra, or conflicting evidence, or any owner/epoch/fence mismatch, affects zero rows and fails closed with no ledger mutation. The owning domain must prove the effect is already reflected in authoritative state or in its durable participant guard; a replay no-op is not a third ledger status.
       - **Target-state:** Read the current region authority from `RegionStatus`/`GetRegionTickStatus` and require exact current `regionEpoch`/`executorFence` equality. For S1/S2, require the complete namespace-bound projection `<effect_id, root_effect_id, typed_operation, tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id>` and exact-compare `playableStateNamespaceId` (as well as every other immutable scope, identity, timeline, and target field) across the ledger, manifest, sealed context, and participant set. Require exactly one immutable sealed execution-context binding: either `sealed_execution_context_digest` or `sealed_execution_context_ref` resolved to its immutable digest, with the referenced context and digest matching the batch and replay request. The complete participant-projection set, sealed context, current authority/fence, and every `SCHEDULED` ledger transition must pass the same evidence-qualified CAS; a missing, stale, mismatched, incomplete, extra, or conflicting value affects zero rows and fails closed with no ledger mutation.
       - In either branch, replay no-op is an attempt outcome, not a third ledger status, and the verifier must not reconcile a mutation under a different persisted `EffectId` across region epochs; any enclosing `rootEffectId` remains lineage and reconciliation context rather than the effect-level identity.
     - If no verifier exists for that effect family, treat the row as not safely proven: retain it non-terminal as `SCHEDULED`/reconciliation-required and escalate to the owning service for positive evidence. Do not re-run or mark `ABANDONED` merely because a verifier is absent.
   - For current-epoch rows with positive durable evidence that the effect is unapplied and an evidence-qualified service-owned replay path:
     - If replay is safe, enqueue follow-up commands or trigger replay using the same idempotent handlers that tick execution uses.
     - If effects are no longer valid, mark rows `ABANDONED` with precise reasons (for example `EXPIRED`, `INVALID_TARGET`, `REGION_RESET_SCOPED`) only after the evidence proves no required mutation succeeded and the recovery policy permits terminalization.
   - Empty queues, missing responses or verifiers, timeouts, and retry exhaustion never qualify as evidence for re-enqueue or `ABANDONED`; retain reconciliation-required/non-terminal state until the required proof exists.
   - For old-epoch rows, first enumerate every durable old-epoch effect and follow-up independently of Redis hints. Use the authority-fenced attestation under the original `EffectId` described in [Inconclusive Old-Epoch Reconciliation Policy](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy); the affected scope cannot reopen while any row remains unresolved. A new epoch lineage may be created only after both the original effect and its source claim are authority-fenced terminal `ABANDONED`; an explicitly approved maintenance or saga/outbox flow may then create a fresh current-epoch identity with durable lineage and revalidated scope. No normal replay may create or drive that new lineage, and the original effect identity must not be re-driven across the epoch boundary.
   - For replay-controller starvation:
     - Reduce per-region replay batch monopolization or other hot-region pressure first.
     - **Target state only, once implemented and proven:** If the region remains starved, run scoped replay-controller remediation for the affected complete `(tenantId, gameInstanceId, playableStateNamespaceId, playableStateScope, regionId, regionEpoch)` after authoritative enumeration and exact validation; incomplete or caller-supplied namespace/scope evidence must fail closed before any replay or maintenance mutation. Escalate to broader reset actions only after that exact scope evidence is qualified. Current operators must use the fail-closed [Current Operator Fallback](./system-architecture-redis-reset-and-recovery.md#current-operator-fallback) instead.
4. **Prevent recurrence**
   - Review Game Session and domain handlers to ensure:
     - Ledger status transitions happen atomically with domain commits where required.
     - Errors that prevent ledger updates are surfaced clearly via logs and metrics, with workflow traces when the Trace Preconditions are satisfied.

   - Add or tighten alerts on `tick_effects_pending_total{scope_class}`, `tick_effects_replay_batches_total{scope_class}`, and `tick_effects_replay_scan_lag_ms{scope_class}` so both idle and unfair replay-controller behavior are detected earlier.

## Deployment-Mode Correlation for Replay Verification

Correlation is a lookup aid, not proof of application. Always establish the exact deployment mode before selecting an identity, and verify the candidate against the complete durable ledger projection, current ownership boundary, and participant/domain evidence.

- **Current-live correlation:** Prefer the durable `commandId` when it is present in the command ledger and selected-work manifest. When no command ID is available, the current implementation may use the documented `command-text:<hash>:slot:<index>` fallback to locate a candidate in the selected-work manifest. That text-and-slot value is only non-authorizing correlation: verify it against the exact scope and epoch, the batch `manifest_digest`, the ledger row, its linked root `EffectId` and participant guards, and authoritative domain state before invoking the service-owned verifier. The fallback is mutable implementation metadata, never a canonical identity, replacement for a missing root `EffectId`, or authority to transition a row to `APPLIED`.
- **Target-state correlation:** Use only the persisted mutation `EffectId` and its complete concrete ledger/participant projection, retaining any enclosing root `EffectId` as lineage/reconciliation context (including the typed participant-guard identity and target aggregate). `commandId`, `effectKey`, command text, and trace attributes may narrow a search but cannot establish identity, authorize reconciliation, or permit an `APPLIED` transition.

## Using Proved Workflow Traces During Tick Incidents

For all of the scenarios above, workflow traces are optional diagnostics rather than the operational baseline:

For target-state S1/S2 effect-level replay and reconciliation, the exact correlation input is the complete namespace-bound durable ledger projection keyed by the persisted mutation `EffectId` and retaining any enclosing root lineage: `<effect_id, root_effect_id, typed_operation, tenant_id, game_instance_id, playable_state_namespace_id, playable_state_scope, region_id, region_epoch, tick_id, effect_key, target_aggregate_type, target_aggregate_id>`. Exact comparison must include `playableStateNamespaceId` and every other projection field before correlation or reconciliation. Current-live may use only its narrower documented projection without `playableStateNamespaceId`; that implementation limitation is not target-state S1/S2 proof and must not be silently filled by `gameInstanceId`. `effectKey`, `targetAggregateType`, and the exact `targetAggregateId` remain required ledger/lookup fields; `effect_type` and other trace attributes are exploratory filters only and cannot establish an exact effect match, authorize reconciliation, or justify a ledger transition. Command, source-claim, coordinator, and completion identities remain authoritative for their own execution phases and must not be replaced by `EffectId`.

- Only when the environment advertises and proves the named workflow-tracing capability, use Jaeger to search for spans representing tick scheduling and execution (for example `tick_schedule`, `tick_execute`) filtered by `tenantId`, `gameInstanceId`, `playableStateNamespaceId`, `playableStateScope`, `regionId`, `regionEpoch`, and, where available, `tickId`; trace filters are diagnostic and never replace authoritative exact-scope validation.
- When that capability is proved, inspect stalled regions for long-running or repeated spans for the same tick IDs and cross-reference domain service spans to identify downstream bottlenecks.
- When that capability is proved, use trace attributes such as `effect_type`, `effectKey`, and target fields only to find candidate spans, then verify every candidate against the full durable ledger projection and its linked root/participant-guard evidence before correlating or reconciling it.
- If the capability is absent or unproved, use metrics and structured logs for each of these investigations and do not delay mitigation for trace collection.

The Tracing architecture doc (`system-architecture-tracing.md`) includes example Jaeger queries and attribute conventions to make these investigations repeatable.
