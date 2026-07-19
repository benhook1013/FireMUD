# FireMUD Scripting Operations Cookbook

This document collects the operator-facing playbooks for scripting and automation. It complements [Scripting Quotas & Operations](./system-architecture-scripting-quotas-and-operations.md), which remains the canonical owner for steady-state quota, budget, security, and observability contracts.

## Operational Cookbook: Quotas, Budgets, and Metrics

Use the following patterns to answer common operational questions:

- **"Which scripts are being hard-dropped by per-script quotas or queues?"**
  - Look at `automation_script_triggers_dropped_total{reason="quota"}` for per-script window drops and `automation_script_triggers_dropped_total{reason="concurrency"}` / `automation_script_triggers_dropped_total{reason="concurrency_policy_drop_new"}` for drops caused by concurrency and queue limits.
  - Pair with `script_quota_denied_total` and audit rows with `finalStage=ADMISSION` and `finalOutcome=quota_denied` (or other quota and concurrency outcomes).

- **"Is a tenant being throttled by its own automation budget?"**
  - Check `automation_script_skips_total{scope,reason="tenant_budget_exceeded"}` and audit rows with `finalStage=ADMISSION` and `finalOutcome=tenant_budget_exceeded`.
  - Use `automation_script_tenant_budget_allowed_total{scope, tier}` / `automation_script_tenant_budget_denied_total{scope, tier}` to see which priority tiers are consuming or exhausting bounded runtime budget. Use audit rows, Redis counters, and control-plane reads for tenant-specific drilldown instead of raw metric labels.

- **"Are cluster-wide ceilings causing drops?"**
  - Monitor `automation_script_triggers_dropped_total{reason="cluster_limit_reached"}` alongside `automation_tick_events_enqueued_total` and infrastructure-level CPU/time metrics. This combination indicates pressure at the cluster layer rather than within a single script or tenant.

- **"Are lower-priority scripts being throttled in favor of higher-priority ones?"**
  - Use `automation_script_skips_total{reason="priority_throttled"}` and compare `automation_script_triggers_total` broken out by `priorityTag` to confirm that background work is yielding capacity to high-priority scripts as configured.

- **"Are reloads or version issues causing skips?"**
  - Inspect `automation_script_triggers_total{outcome="skipped_reloading"}`, `automation_script_triggers_total{outcome="rollback_paused"}`, and `automation_script_triggers_dropped_total{reason="version_unavailable"}`. Pair these with event-scope ingress audit records for pre-resolution denials and with `script_event_audit.finalStage=ADMISSION` for handler-scoped denials to distinguish reload pauses, rollback pauses, and missing or failed script versions.
  - For stale control-plane pin visibility, inspect admissions with `finalOutcome=pin_state_unavailable` and corresponding drop metrics keyed by the bounded `finalReason`.

### Tuning Playbook: Misbehaving Scripts

When a script or tenant consumes too many resources, adjust settings in this order:

1. **Per-script cadence and concurrency** – Start with the script’s own knobs in [Scripting Quotas & Operations](./system-architecture-scripting-quotas-and-operations.md#per-script-scheduling-policies): increase `intervalTicks`, reduce `maxConcurrent`, or switch `concurrencyPolicy` from `queue_until_free` to `drop_new` so the script enqueues less often and runs fewer overlapping instances.
2. **Per-script quota window** – If the script still runs too frequently, tighten `SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOW_SECONDS` for that script so abusive patterns are capped before they hit the tick queues.
3. **Per-tenant tier budgets** – When one tenant’s background work threatens others, adjust that tenant’s budgets per tier (for example, reduce `background` capacity), watching `automation_script_skips_total{reason="tenant_budget_exceeded"}`.
4. **Cluster-wide ceilings and capacity** – Only after tuning the above should you raise or lower global ceilings such as `AUTOMATION_TICK_MAX_EVENTS` or cluster CPU budgets. Use the metrics in the parent doc to confirm whether you are cluster-bound or script and tenant-bound.

### Worked Example: Noisy Background Script vs High-Priority Script

Consider two tenants sharing the same Automation & Scripting cluster:

- **Tenant A** – runs a noisy background script `npc-logger` tagged `priorityTag=background` that logs non-critical events frequently.
- **Tenant B** – runs a high-priority script `boss-ai` tagged `priorityTag=high` that drives a raid boss encounter.

Assumptions:

- Per-script quotas allow both scripts to run a reasonable number of times per window under normal conditions.
- Tenant budgets are configured so each tenant has its own automation budget per priority tier.
- Cluster-level ceilings cap total automation work per second across all tenants.

Under light load:

- `npc-logger` and `boss-ai` both operate within their per-script quotas.
- Tenant A and Tenant B remain within their per-tenant budgets.
- Cluster ceilings are not reached; both scripts run as expected.

Under heavy load from Tenant A:

1. **Per-script quota layer**: `npc-logger` may hit its per-script quota first; additional triggers for that script in the current window are skipped with `automation_script_triggers_dropped_total{reason="quota"}` and audit entries with `finalStage=ADMISSION` and `finalOutcome=quota_denied`. `boss-ai` remains within its own per-script quota and continues to run when triggered.
2. **Per-tenant budget layer**: If Tenant A continues to generate background triggers, it may exhaust its tenant-level budget for the `background` tier. Once Tenant A’s background budget is exceeded, further background triggers for Tenant A (including `npc-logger`) are throttled or skipped and the corresponding `automation_script_skips_total{scope,reason="tenant_budget_exceeded"}` bucket increases. Tenant B’s budgets are independent; its `high`-priority `boss-ai` script is unaffected as long as Tenant B stays within its own budgets.
3. **Cluster-level ceilings**: If total automation work across all tenants (including other games) approaches the cluster ceiling, the scheduler continues to admit `high`-priority scripts like `boss-ai` as long as possible and preferentially defers or drops `background` work such as `npc-logger`, reflected in `automation_script_triggers_dropped_total` with reasons like `cluster_limit_reached`.

This example illustrates how the layers interact:

- Per-script quotas prevent any single script from running unboundedly.
- Per-tenant budgets prevent one tenant’s background scripts from starving another tenant’s automation.
- Cluster ceilings ensure the entire cluster remains healthy under extreme load, favoring high-priority, latency-sensitive scripts when trade-offs are required.

## Operational Disable / Throttle Flows

Operators can disable or throttle scripts to respond to failures or abuse:

- **Disable now (hard stop)**:
  - Mark a script as disabled via the Game Design or Logging & Admin tools.
  - The Automation & Scripting Service flips `runtimeStatus=DISABLED` in script metadata.
  - The scheduler stops accepting new triggers for that script immediately (recording `script_event_audit.finalStage=ADMISSION`, `finalOutcome=script_disabled`, and a suitable `finalReason`, such as `admin_hard_disable`), but does not preempt in-flight runs; they are allowed to complete under existing quotas.

- **Soft-disable after current run**:
  - For scripts that should drain gracefully, administrators can set `runtimeStatus=DISABLE_AFTER_DRAIN`.
  - The scheduler continues to run any currently queued triggers up to a small grace window, then transitions the script to `DISABLED` once its active and queued counts reach zero.
  - Subsequent triggers are skipped and logged with `finalStage=ADMISSION`, `finalOutcome=script_disabled`, and a `finalReason` that reflects the drain behavior.

- **Throttling**:
  - Throttling is modeled as a temporary adjustment of per-script and per-tenant budgets rather than a separate toggle.
  - Operators can reduce `SCRIPT_QUOTA_LIMIT`, increase `intervalTicks`, or change `priorityTag` to `background`; the scheduler immediately applies the new configuration when evaluating triggers.
  - In addition, the failure-rate circuit breaker may place a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`, which behaves like a hard disable until an administrator explicitly clears the status; these transitions are captured in `script_event_audit` using canonical `finalOutcome` values (for example `disabled_due_to_errors`, `script_disabled`, `tenant_budget_exceeded`) paired with specific `finalReason` strings.

All disable/enable and throttle actions are idempotent and recorded with the acting principal (where available) via the `actorPrincipal` field, so operators can trace when and why a script stopped executing.

## Rollback & Recovery Scenarios

This section summarizes common failure and rollback scenarios and how operators should respond. It complements the per-feature lifecycle details in the DSL reference and modding framework documents.

- **Script patch `onLoad` failures or patch status `FAILED`**
  - Symptoms:
    - For a given `<tenantId, scriptPatchVersion>`, audit entries with `eventType=onLoad` and `finalStage=DSL_EVAL`, `finalOutcome=sandbox_error` (or other logical failures) so you can distinguish `onLoad` evaluation failures from downstream persistence/handoff problems.
    - Triggers referencing that patch produce `finalStage=ADMISSION`, `finalOutcome=version_unavailable` (or a more specific variant) and drop metrics such as `automation_script_triggers_dropped_total{reason="version_unavailable"}`.
  - Behavior:
    - The Automation & Scripting Service marks the patch `FAILED` for that tenant. Because readiness and `onLoad` run before pinning and have no instance or gameplay side effects, no Game Session pin or epoch changes.
    - No rollback is needed for that failed readiness candidate; running instances continue under their unchanged authoritative pin.
  - Operator actions:
    - Use Game Design tooling to inspect and fix the script configuration, then publish a new patch.
    - Optionally disable the faulty script entirely (`runtimeStatus=DISABLED`) to stop further admission attempts while iterating.

- **Plugin version failures or misbehavior**
  - Symptoms:
    - Plugin lifecycle state in the Automation & Scripting Service shows `pluginState=FAILED` or frequent `sandbox_error` outcomes for `pluginId` / `pluginVersionId`.
    - Audit entries in `script_event_audit` tagged with `pluginId` / `pluginVersionId` show repeated failures, and plugin-specific metrics spike.
  - Behavior:
    - The modding framework keeps `activeVersionId` unchanged when a new plugin version fails validation or initialization; triggers for the failed version are rejected.
  - Operator actions:
    - Use Logging & Admin APIs to set the plugin to a disabled or drain state for affected tenants.
    - If the desired rollback target is older logic, publish a new signed bundle `pluginVersionId` that reintroduces that logic, then promote the new published version to `activeVersionId`. Historical `SUPERSEDED` versions are not reactivated directly.

- **Heavy timer drops or throttled `onInterval` handlers**
  - Symptoms:
    - High counts for `automation_script_triggers_dropped_total{reason="tenant_budget_exceeded"}` or `reason="cluster_limit_reached"` for `eventType=onInterval`.
    - Audit entries for `onInterval` with `finalStage=ADMISSION` and `finalOutcome=quota_denied`, `tenant_budget_exceeded`, or `version_unavailable`.
  - Behavior:
    - Timer-based triggers are at-most-once per scheduled firing; dropped or skipped intervals are not replayed, although future firings may still occur.
  - Operator actions:
    - Reduce cadence (increase `intervalTicks`) or lower priority for noisy timers.
    - Adjust per-tenant budgets or cluster ceilings if drops reflect legitimate load rather than misbehaving scripts.
    - For persistent version-related drops, investigate patch status and either fix and republish or explicitly disable the affected scripts.

In all of these cases, `script_event_audit` is the primary source of truth for what happened to individual triggers, and metrics from the parent doc’s glossary indicate whether the problem is localized to a script/plugin, a tenant budget, or cluster capacity.

## Rollback & Recovery Cookbook

This section summarizes common rollback and recovery scenarios for scripting and automation. It complements the broader backup and recovery guidance in [Backup and Recovery](./system-architecture-backup-recovery.md) and the versioning rules in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

### Rollback Protocol (Required)

Rollback must prevent previously queued work from a rolled-back `scriptPatchVersion` from continuing to affect gameplay.

At a minimum, rollback consists of:

1. **Fence new evaluation**
   - Pause tick execution and set Automation & Scripting admission to rollback pause mode for the affected scope before repin, so new triggers do not refill queues during cleanup.
   - Confirm the target is still tenant-`READY`, prepare the exact candidate if required, and repin the affected game instance(s) using Game Session / Logging & Admin control-plane APIs. Game Session commits a new `scriptPinEpoch` even for a previously used version.
   - Ensure Automation & Scripting rejects triggers for non-`READY` patches and records explicit outcomes (for example `version_unavailable`) rather than silently falling back.
2. **Drain/purge queued automation work**
   - Drain or purge queued script work items and staging entries that carry the rolled-back patch so they cannot enqueue into tick queues after repin.
   - If plugin versions are also being rolled back, disabled, or revoked, cancel pending work for those `pluginVersionId` values before queue purge.
   - Any purge must be scoped and auditable (tenant/region/script as appropriate) and must not require ad-hoc `redis-cli` deletes.
3. **Enforce execution-time version fencing**
   - Game Session must reject any queued tick commands whose embedded `(scriptPatchVersion, scriptPinEpoch)` does not match the instance’s current tuple, and record the rejection so operators can diagnose rollback impact.
4. **Resume in order**
   - Return Automation & Scripting admission to normal only after cancel/purge completes and rollback draining confirms that pre-pause executions and cancelable outbox work for the current rollback-scope `admissionEpoch` have quiesced, then resume ticks.
   - If an old-epoch execution reaches persist or handoff checks after rollback pause has advanced the scope `admissionEpoch`, it must fail as `finalOutcome=canceled` with a bounded `finalReason` such as `rollback_epoch_advanced` rather than creating new live work. Operators should expect to see these rows in `script_event_audit` during rollback convergence and draining.

Rollback orchestration should be modeled as a durable state machine (`PAUSING`, `REPINNING`, `CANCELING`, `PURGING`, `CONVERGING`, `DRAINING`, `RESUMING`, `COMPLETED`, terminal `ROLLBACK_CONVERGENCE_TIMEOUT`) keyed by `controlPlaneRequestId` so partial failures can be resumed deterministically.

Concrete rollback sequence example:

1. Call `PauseTicks(tenantId=T1, gameInstanceId=G7, controlPlaneRequestId=RB-42)` so Game Session stops new tick scheduling and command intake for that instance.
2. Call `SetAutomationAdmissionMode(tenantId=T1, gameInstanceId=G7, mode=PAUSED_FOR_ROLLBACK, controlPlaneRequestId=RB-42)` so new external and scheduler triggers are rejected with rollback backpressure outcome `TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK` and admission reason `rollback_paused`.
3. Call `RollbackScriptPatchVersion(tenantId=T1, gameInstanceId=G7, targetScriptPatchVersion=P21, controlPlaneRequestId=RB-42)` to repin the instance to the known-good patch.
4. Poll `GetAutomationPinConvergence` and `GetGameSessionPinConvergence` until both report `observedPinnedScriptPatchVersion=P21`, the epoch returned by the repin, and the latest observed `controlPlaneRequestId=RB-42`.
5. Cancel or purge queued outbox work and staging entries that still carry the displaced patch `P22`, then poll `GetAutomationDrainStatus` until `activeExecutionCount=0` and `pendingCancelableWorkItemCount=0` for the paused scope.
6. Expect a bounded number of old-epoch audit rows for executions that were admitted before pause and later fenced by the advanced `admissionEpoch`; these remain non-success outcomes, not silent loss.
7. Call `SetAutomationAdmissionMode(..., mode=NORMAL, controlPlaneRequestId=RB-42)` only after convergence and drain checks pass.
8. Call `ResumeTicks(..., controlPlaneRequestId=RB-42)` last so gameplay resumes only after both runtime services agree on the rollback target and old-version work has quiesced.

Operationally, use control-plane APIs rather than direct data-store edits for pending and dead-lettered work:

- `ListOutboxWorkItems` for scoped inspection.
- `ReplayDeadLetteredWorkItems` for bounded replay of recoverable items.
- `PurgeOutboxWorkItems` for auditable cleanup of terminally invalid/stale items.

These requirements are summarized in [`system-architecture-scripting-contracts.md`](./system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety).

### Misbehaving Script Patch After Activation

Symptoms:

- A script patch has already been marked `READY` for a tenant and pinned as the active `scriptPatchVersion`, but automation metrics and `script_event_audit` show sustained `sandbox_error` or `infrastructure_error` outcomes for one or more scripts.
- Players or operators report regressions that correlate with the newly active patch (for example, NPCs stuck in loops, missing timers, or over-aggressive automation).

Behavior:

- The Automation & Scripting Service continues to enforce quotas, budgets, and failure-rate circuit breakers for individual scripts; misbehaving handlers may be transitioned to `runtimeStatus=DISABLED_DUE_TO_ERRORS`.
- Timer and event triggers remain at-most-once per firing; skipped or failed triggers are not automatically replayed even if the patch is later rolled back.

Operator actions:

1. Identify the affected scripts and patch.
   - Use `script_event_audit` filtered by `tenantId`, `scriptPatchVersion`, and `scriptId` to confirm which handlers are failing.
   - Correlate with automation metrics such as `automation_script_sandbox_failures_total`, `automation_script_errors_total`, and `automation_script_triggers_dropped_total` to determine scope and severity.
   - Use `ScriptPatchTenantStatusChanged` for tenant readiness gates and `ScriptPatchInstanceRolloutChanged` for instance rollout history; do not infer one from the other.
2. Contain impact at the script level.
   - Use the normal disable/throttle flows in this document to set offending scripts to `runtimeStatus=DISABLED` or a drain state while you triage (for example, `DISABLE_AFTER_DRAIN`).
3. Roll back the active script patch if necessary.
   - If regressions are widespread or difficult to isolate, use Logging & Admin or Game Session tooling to repin the game back to the previous known-good `scriptPatchVersion` for the affected tenant and game instance. Concretely:
     - Query the Automation & Scripting Service via read-only APIs such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` and `GetScriptPatchInstanceRolloutStatus(...)` (or consume `ScriptPatchTenantStatusChanged` / `ScriptPatchInstanceRolloutChanged` events) to confirm tenant readiness and instance rollout state.
     - Call the Game Session control-plane APIs to update the pin (for example `SetPinnedScriptPatchVersion` or `RollbackScriptPatchVersion`) following the request/response contracts in [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md) and the sequencing rules in [Scripting & Automation: Control Plane Operations](./system-architecture-scripting-control-plane-operations.md).
   - Repinning does not attempt to backfill skipped triggers or rewrite existing automation queues; automation and tick processing continue from the current point in time under the older patch, and at-most-once guarantees for past triggers are preserved.
   - Repinning must also ensure rollback safety:
     - Automation admission should remain paused for the affected scope while repin and cancel/purge steps run.
     - Queued automation work items and staging entries that carry the rolled-back `scriptPatchVersion` are drained/purged so they cannot enqueue or execute after repin.
     - If plugin versions are also being rolled back, disabled, or revoked, pending work for displaced `pluginVersionId` values is canceled before queue purge.
     - Game Session enforces a version-and-epoch fence at execution time and must reject any tick-queue entries whose embedded tuple does not match the current pin.
     - These drops must be visible in `script_event_audit` and operator dashboards so rollback impact is diagnosable.
4. Repair and republish.
   - Fix the underlying script configuration in the Game Design Service and publish a new script-only patch version.
   - Verify that the new patch reaches `patchStatus=READY` for the tenant and that side-effect-free `onLoad` validation succeeds before explicitly pinning it through Game Session.
