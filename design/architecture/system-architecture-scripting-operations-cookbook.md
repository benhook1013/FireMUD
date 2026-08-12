# FireMUD Scripting Operations Cookbook

This document collects operator-facing procedures and examples for scripting and automation. [Scripting Quotas & Operations](./system-architecture-scripting-quotas-and-operations.md) owns steady-state quota and budget policy/status; the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md) are the sole authority for metric names, labels, and increment units; the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md) owns audit fields and handoff diagnostics; [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md) owns promotion and rollback semantics. The cookbook demonstrates those contracts without redefining them.

## Implementation Status

This is a non-authoritative operator guide. Required rollout, rollback, convergence, and timeout behavior belongs to [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md); the rollback section below is only a worked example. Current runtime limitations also remain canonical in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#current-implementation-status): the live handoff supports one target entity, `targetEntityIds[]` multi-target fan-out is target-state only, and the implementation has not yet converged on the target lease/fencing-generation recovery or separate evaluated descriptor/outbox boundary. Target-state recovery resumes a committed descriptor without DSL re-entry and dead-letters only a stale `EVALUATING` trigger with no committed descriptor. Until that recovery exists, the current fail-closed response to an expired or otherwise unresolved `EVALUATING` row is to keep it counted as active/unresolved, retain rollback admission and tick fences, and not infer reclaim, terminalization, or safe resumption from lease age.

## Operational Cookbook: Quotas, Budgets, and Metrics

Metric names, labels, and increment units in the examples below are illustrative query patterns only. The [normative Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix) is the sole authority for those metric definitions, while the [Observability Contract](./system-architecture-scripting-observability-contract.md) owns audit and handoff diagnostics; this cookbook records only local operator consequences and diagnostic usage.

Use the following patterns to answer common operational questions:

- **"Which events are being rejected before handler resolution by quotas or queues?"**
  - Look at `automation_script_triggers_dropped_total{reason="quota"}` for event-scope limiter rejections and `automation_script_triggers_dropped_total{reason="concurrency"}` / `automation_script_triggers_dropped_total{reason="concurrency_policy_drop_new"}` for pre-handler concurrency and queue-limit rejections. These are event-scope decisions, not per-script `ScriptQuotaService` charges.
  - For handler-level per-script `quota_denied` with `finalReason=script_quota_denied`, use `automation_script_triggers_total{outcome="quota_denied"}`, `script_quota_denied_total`, and the handler-scoped audit row as separate diagnostics; do not treat it as a dropped pre-handler event. For `finalReason=dry_run_capacity_exhausted`, use `automation_script_test_capacity_denied_total{scope}`, the same trigger outcome, and the audit row instead; it does not increment `script_quota_denied_total`.

- **"Is a tenant being throttled by its own automation budget?"**
  - Check pre-resolution throttling in `automation_script_skips_total{scope,reason="tenant_budget_exceeded"}`. For handler-level budget denials, use `automation_script_triggers_total{outcome="tenant_budget_exceeded"}` and the corresponding handler audit rows.
  - Use `automation_script_tenant_budget_allowed_total{scope, tier}` / `automation_script_tenant_budget_denied_total{scope, tier}` to see which priority tiers are consuming or exhausting bounded runtime budget. Use audit rows, Redis counters, and control-plane reads for tenant-specific drilldown instead of raw metric labels.

- **"Are cluster-wide ceilings causing drops?"**
  - Monitor `automation_script_triggers_dropped_total{reason="cluster_limit_reached"}` alongside `automation_tick_events_enqueued_total` and infrastructure-level CPU/time metrics. This combination indicates pressure at the cluster layer rather than within a single script or tenant.

- **"Are lower-priority scripts being throttled in favor of higher-priority ones?"**
  - Use `automation_script_skips_total{reason="priority_throttled"}` and compare `automation_script_triggers_total` broken out by `priorityTag` to confirm that background work is yielding capacity to high-priority scripts as configured.

- **"Are reloads or version issues causing skips?"**
  - For pre-handler denials, inspect the ingress response and `script_event_ingress_audit` for the bounded `admissionOutcome`/`admissionReason` pair, together with `automation_script_triggers_dropped_total{reason=...}`. Do not query handler `finalOutcome` for this path. For handler-scoped denials after resolution, use `automation_script_triggers_total{outcome=...}` and `script_event_audit.finalStage=ADMISSION`; do not treat the dropped metric as a handler audit join.
  - For a pre-handler stale control-plane pin, inspect the ingress response and `script_event_ingress_audit` for `admitted=false`, `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_PIN_STATE_UNAVAILABLE`, and `admissionReason=pin_state_unavailable`. Do not look for `script_event_audit.finalOutcome` for this pre-handler case; a pin failure after handler resolution remains handler-scoped.

### Tuning Playbook: Misbehaving Scripts

When a script or tenant consumes too many resources, adjust settings in this order:

1. **Per-script cadence and concurrency** – Start with the script’s own knobs in [Scripting Quotas & Operations](./system-architecture-scripting-quotas-and-operations.md#per-script-scheduling-policies): increase `intervalTicks`, reduce `maxConcurrent`, or switch `concurrencyPolicy` from `queue_until_free` to `drop_new` so the script enqueues less often and runs fewer overlapping instances.
2. **Per-script quota window** – If the script still runs too frequently, tighten `SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOW_SECONDS` for that script so abusive patterns are capped before they hit the tick queues.
3. **Per-tenant tier budgets** – When one tenant’s background work threatens others, adjust that tenant’s budgets per tier (for example, reduce `background` capacity), watching `automation_script_skips_total{reason="tenant_budget_exceeded"}`.
4. **Cluster-wide ceilings and capacity** – Only after tuning the above should you raise or lower global ceilings such as `AUTOMATION_TICK_MAX_EVENTS` or cluster CPU budgets. Use the metric families and labels defined by [normative Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix), with the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md) for audit and handoff diagnostics, to confirm whether you are cluster-bound or script and tenant-bound.

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

1. **Event-scope limiter and per-script quota layers**: An event-scope limiter may reject incoming events before handler resolution, increasing `automation_script_triggers_dropped_total{reason="quota"}` and the event-scope ingress audit without charging per-script quota. After resolution, apply the normative metric unit: `automation_script_triggers_total` counts once per resolved handler and adds no separate admitted-event increment; an admitted event with no handlers uses one event-scope `outcome="admitted_no_handlers"` count with `script_category="UNRESOLVED"` and no plugin-family labels. `admitted_no_handlers` is metric-only: it is not an ingress response or an Automation audit `finalOutcome`/`finalReason`. `ScriptQuotaService` applies `npc-logger`'s per-script quota to each handler; a handler-level denial instead uses `automation_script_triggers_total{outcome="quota_denied"}` and its handler-scoped audit row. `boss-ai` remains within its own per-script quota and continues to run when triggered.
2. **Per-tenant budget layer**: If Tenant A continues to generate background triggers, it may exhaust its tenant-level budget for the `background` tier. Once Tenant A’s background budget is exceeded, further background triggers for Tenant A (including `npc-logger`) are throttled or skipped and the corresponding `automation_script_skips_total{scope,reason="tenant_budget_exceeded"}` bucket increases. Tenant B’s budgets are independent; its `high`-priority `boss-ai` script is unaffected as long as Tenant B stays within its own budgets.
3. **Cluster-level ceilings**: If total automation work across all tenants (including other games) approaches the cluster ceiling, the scheduler continues to admit `high`-priority scripts like `boss-ai` as long as possible and preferentially defers or rejects `background` work such as `npc-logger`; pre-handler rejections are reflected in `automation_script_triggers_dropped_total` with reasons like `cluster_limit_reached`.

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
    - Pre-handler triggers referencing that patch produce event-scope ingress records and drop metrics such as `automation_script_triggers_dropped_total{reason="version_unavailable"}`; handler-scoped denials are diagnosed separately through `script_event_audit`.
  - Behavior:
    - The Automation & Scripting Service marks the patch `FAILED` for that tenant; running instances remain on their previously pinned patch.
    - No automatic rollback beyond "keep the last known good patch active" occurs.
  - Operator actions:
    - Use Game Design tooling to inspect and fix the script configuration, then publish a new patch.
    - Optionally disable the faulty script entirely (`runtimeStatus=DISABLED`) to stop further admission attempts while iterating.

- **Stale `onLoad` execution**
  - **Target state only:** A stale `ONLOAD_RUNNING` readiness execution is terminalized by the Automation recovery owner as an audited `finalStage=DSL_EVAL`, `finalOutcome=canceled`, and `finalReason=stale_execution_fenced` result and blocks `READY` for that publication/generation. The affected unresolved work and any rollback admission/tick fences remain in force until that fencing and audit result are durable.
  - **Current behavior:** Stale `ONLOAD_RUNNING` recovery is not implemented. Fail closed by retaining the unresolved work and any affected rollback admission/tick fences; do not infer a terminal audit record, reclaim, replay, or safe resumption from age alone.
  - Do not replay the same canonical readiness identity or permit a replacement readiness identity before the stale execution is fenced and its terminal audit is recorded. A later accepted publication supplies a distinct identity; it is not an implicit retry of the stale execution.

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
    - For `onInterval`, high counts for `automation_script_skips_total{scope,reason="tenant_budget_exceeded"}` indicate intentional pre-evaluation timer skips when tenant runtime budget is exhausted. Use `automation_script_triggers_dropped_total` only when the timer candidate is rejected under explicit pre-handler rejection semantics, such as `reason="cluster_limit_reached"`.
    - Handler-scoped `onInterval` denials are diagnosed separately through `automation_script_triggers_total` outcomes and `script_event_audit` entries with `finalStage=ADMISSION` and outcomes such as `quota_denied`, `tenant_budget_exceeded`, or `version_unavailable`.
  - Behavior:
    - Recurring timer triggers produce at most one logical durable firing per Trigger Identity; dropped or skipped intervals are not replayed, although future firings may still occur. A failed physical evaluation attempt before `EVALUATED_COMMITTED` may retry only under the same identity and must converge on that same logical firing.
  - Operator actions:
    - Reduce cadence (increase `intervalTicks`) or lower priority for noisy timers.
    - Adjust per-tenant budgets or cluster ceilings if drops reflect legitimate load rather than misbehaving scripts.
    - For persistent version-related drops, investigate patch status and either fix and republish or explicitly disable the affected scripts.

In all of these cases, `script_event_audit` remains the primary source of truth for Automation-owned handler lifecycle. For the current live diagnostic boundary and target per-command identity contract, use [Command Identity and Live Handoff Boundary](./system-architecture-scripting-rollout-and-rollback.md#command-identity-and-live-handoff-boundary). Metrics defined by [normative Table 4](./system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix), together with audit and handoff diagnostics from the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md), indicate whether the problem is localized to a script/plugin, a tenant budget, or cluster capacity.

## Rollback & Recovery Cookbook

This section summarizes common rollback and recovery scenarios for scripting and automation. It complements the broader backup and recovery guidance in [Backup and Recovery](./system-architecture-backup-recovery.md) and the versioning rules in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

### Rollback Protocol and Diagnostics

The required rollback sequencing, state transitions, convergence deadline, and timeout behavior are owned by [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#patch-rollback-operator-driven-required). The underlying RPC contracts, request fields, and mutable state boundaries are owned by [Scripting & Automation: Control Plane API](./system-architecture-scripting-control-plane-api.md). This cookbook does not define a second rollback sequence or state machine.

For local diagnosis during a rollback:

The current runtime does not prove target-state drain recovery from lease age: unresolved `EVALUATING` work remains active and handoff-capable `PENDING_EVALUATION` remains pending. These are pre-DSL states, not evaluated descriptor `PENDING` or `INDEXED` work. Keep the rollback admission and tick fences in place until the owner workflow reports the required state; do not infer reclaim, terminalization, or safe resumption locally.

For pending and dead-lettered work, use control-plane APIs rather than direct data-store edits:

- Current implementation: `ReplayDeadLetteredWorkItems(tenantId=11111111-1111-4111-8111-111111111111, workItemIds=[work-9], controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` uses `workItemIds` as the executable parent-row selector. It does not select independent command descriptors and must not be described as complete command-level replay.
- **Target-state only and capability-gated:** `ListOutboxWorkItems(tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, scriptPatchVersion=P22, pluginVersionId=plugin-v22, workItemStatus=PENDING)` inspects evaluated descriptor/outbox work for the instance; omit `pluginVersionId` only when the operation is not plugin-owned. The current diagnosis remains the `PENDING_EVALUATION` and `EVALUATING` state distinction above, without treating either state as evaluated-command `PENDING`.
- **Target-state only and capability-gated:** `ReplayDeadLetteredWorkItems(tenantId=11111111-1111-4111-8111-111111111111, references=[{tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, playableStateScope=shared, regionId=R1, regionEpoch=14, targetGameInstanceId=55555555-5555-4555-8555-555555555555, targetPlayableStateScope=isolated, targetRegionId=R2, targetRegionEpoch=21, automationDispatchId=work-9, commandOrdinal=0}], controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` selects one command by the complete Command-Handoff Identity. When the command has no distinct target runtime scope, omit all target scope fields. Plugin-backed candidates must match the immutable `(pluginId, pluginVersionId, bindingId)` stored on the selected dead-lettered descriptor against the active binding for the same scoped `<tenantId, gameInstanceId, pluginId>`; ingress audit is supplemental provenance, not the eligibility source.
- **Target-state only and capability-gated:** `PurgeOutboxWorkItems(tenantId=11111111-1111-4111-8111-111111111111, references=[{tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, playableStateScope=shared, regionId=R1, regionEpoch=14, targetGameInstanceId=55555555-5555-4555-8555-555555555555, targetPlayableStateScope=isolated, targetRegionId=R2, targetRegionEpoch=21, automationDispatchId=work-9, commandOrdinal=0}], controlPlaneRequestId=RB-42, actor=operator:alice, reason="rollback RB-42")` is auditable cleanup only when the selected descriptor is terminal (`HANDED_OFF`, `CANCELED`, or `DEAD_LETTERED`), its parent trigger is `EVALUATED_COMMITTED`, and its configured retention horizon has elapsed. The purge preserves the trigger marker, corresponding `script_event_audit`, and replay-causation evidence; it does not mutate active or nonterminal work.

For command-level diagnosis, use the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md) and [Command Identity and Live Handoff Boundary](./system-architecture-scripting-rollout-and-rollback.md#command-identity-and-live-handoff-boundary). The target query is `ListScriptHandoffEvents`; when a distinct target runtime exists, its complete query/reference identity includes `targetGameInstanceId`, optional `targetPlayableStateScope`, `targetRegionId`, and `targetRegionEpoch`. The current live fallback is the correlation of `script_event_audit`, narrower Automation handoff/work-item rows, `outboxWorkItemId`, `automationDispatchId`, `gameSessionCommandId`, and the Game Session command/result/fence fields currently exposed. Do not infer complete Trigger Identity or downstream per-command disposition from that fallback.

For the canonical rollback and version-fence requirements, follow [Scripting & Automation: Rollout and Rollback](./system-architecture-scripting-rollout-and-rollback.md#patch-rollback-operator-driven-required) and [Scripting & Automation: Cross-Service Contracts](./system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety).

### Misbehaving Script Patch After Activation

Symptoms:

- A script patch has already been marked `READY` for a tenant and pinned as the active `scriptPatchVersion`, but automation metrics and `script_event_audit` show sustained `sandbox_error` or `infrastructure_error` outcomes for one or more scripts.
- Players or operators report regressions that correlate with the newly active patch (for example, NPCs stuck in loops, missing timers, or over-aggressive automation).

Behavior:

- The Automation & Scripting Service continues to enforce quotas, budgets, and failure-rate circuit breakers for individual scripts; misbehaving handlers may be transitioned to `runtimeStatus=DISABLED_DUE_TO_ERRORS`.
- Recurring timer and event triggers produce at most one logical durable firing per Trigger Identity; skipped firings are not automatically replayed even if the patch is later rolled back. A failed physical evaluation attempt before `EVALUATED_COMMITTED` may retry only under the same identity and must converge on the same logical firing; committed descriptors recover without re-entering the DSL.

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
     - Game Session enforces a version fence at execution time and must reject any tick-queue entries whose embedded `scriptPatchVersion` does not match the currently pinned value.
     - Use the [Command Identity and Live Handoff Boundary](./system-architecture-scripting-rollout-and-rollback.md#command-identity-and-live-handoff-boundary) contract. For current live incidents, correlate `script_event_audit`, the narrower Automation handoff rows, and the Game Session command/result/fence fields currently exposed; do not infer a complete Trigger Identity or downstream per-command disposition.
4. Repair and republish.
   - Fix the underlying script configuration in the Game Design Service and publish a new script-only patch version.
   - Verify that the new patch reaches `patchStatus=READY` for the tenant and that `onLoad` initialization succeeds before promoting it to the active `scriptPatchVersion` again.
